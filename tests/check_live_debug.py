"""Exercise draft and versioned live-node debugging through a packaged JAR, using only loopback."""

import argparse
from contextlib import contextmanager
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import threading
import time
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener


DEFAULT_JAR = (
    Path(__file__).resolve().parents[1]
    / "backend/target/agent-studio-backend-0.1.0-SNAPSHOT.jar"
)
API_PATH = "/api/apps/meeting-notes"
TRANSCRIPT = "Morgan: Send the deployment notes"
DRAFT_INSTRUCTION = "Draft fixture: preserve the owner explicitly."
PRIVATE_ERROR = "fixture-private-provider-detail"
LOCAL_HTTP = build_opener(ProxyHandler({}))


def api(base, path, method="GET", body=None, expected=200):
    request = Request(
        base + API_PATH + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=method,
        headers={"Content-Type": "application/json"},
    )
    try:
        with LOCAL_HTTP.open(request, timeout=15) as response:
            status, payload = response.status, json.load(response)
    except HTTPError as error:
        with error:
            status, payload = error.code, json.load(error)
    if status != expected:
        raise AssertionError(
            f"{method} {path}: expected {expected}, received {status}: {payload}"
        )
    return payload["data"] if status < 400 else payload


@contextmanager
def fake_provider():
    """The fixture owns the API key and never forwards messages to another endpoint."""
    key = secrets.token_hex(20)
    calls = []
    fixtures = [
        ("Published result", 11, 4),
        ("Draft result", 17, 6),
        (None, 19, 3),
    ]

    class Provider(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def do_POST(self):
            if (
                self.path != "/v1/chat/completions"
                or self.headers.get("Authorization") != f"Bearer {key}"
            ):
                self.send_error(400)
                return
            call = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            calls.append(call)
            index = len(calls) - 1
            if index >= len(fixtures):
                status, payload = 503, {"error": {"message": PRIVATE_ERROR}}
            else:
                title, input_tokens, output_tokens = fixtures[index]
                content = (
                    json.dumps({"items": [{"title": title, "assignee": "Morgan"}]})
                    if title
                    else PRIVATE_ERROR
                )
                status, payload = (
                    200,
                    {
                        "id": f"local-{index}",
                        "object": "chat.completion",
                        "created": 1,
                        "model": "model-returned-by-fixture",
                        "choices": [
                            {
                                "index": 0,
                                "finish_reason": "stop",
                                "message": {"role": "assistant", "content": content},
                            }
                        ],
                        "usage": {
                            "prompt_tokens": input_tokens,
                            "completion_tokens": output_tokens,
                            "total_tokens": input_tokens + output_tokens,
                        },
                    },
                )
            encoded = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Connection", "close")
            self.close_connection = True
            self.end_headers()
            self.wfile.write(encoded)

    with ThreadingHTTPServer(("127.0.0.1", 0), Provider) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}/v1", key, calls
        finally:
            server.shutdown()
            thread.join(timeout=5)


@contextmanager
def packaged_app(jar, provider_url, key):
    with tempfile.TemporaryDirectory(prefix="agent-studio-live-check-") as directory:
        workdir = Path(directory)
        log_file = workdir / "server.log"
        env = {
            k: v
            for k, v in os.environ.items()
            if not k.upper().startswith(
                ("STUDIO_", "SPRING_", "SERVER_", "OPENAI_", "MANAGEMENT_")
            )
            and k.upper()
            not in {
                "JDK_JAVA_OPTIONS",
                "JAVA_TOOL_OPTIONS",
                "_JAVA_OPTIONS",
                "HTTP_PROXY",
                "HTTPS_PROXY",
                "ALL_PROXY",
            }
        }
        env.update(
            {
                "SERVER_ADDRESS": "127.0.0.1",
                "SERVER_PORT": "0",
                "STUDIO_DB_URL": "jdbc:h2:file:./testdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0",
                "STUDIO_DB_USERNAME": "studio_test",
                "STUDIO_DB_PASSWORD": secrets.token_hex(24),
                "STUDIO_LIVE_ENABLED": "true",
                "STUDIO_LIVE_DAILY_LIMIT": "4",
                "STUDIO_MODEL_BASE_URL": provider_url,
                "STUDIO_MODEL_API_KEY": key,
                "STUDIO_MODEL_NAME": "requested-fixture-model",
                "STUDIO_WORKFLOW_SCHEDULER_ENABLED": "false",
            }
        )
        with log_file.open("wb") as log:
            process = subprocess.Popen(
                ["java", "-jar", str(jar)],
                cwd=workdir,
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
            )
            try:
                deadline = time.monotonic() + 90
                while process.poll() is None and time.monotonic() < deadline:
                    text = log_file.read_text(encoding="utf-8", errors="replace")
                    port = re.search(r"Tomcat started on port (\d+)", text)
                    if port and "Started StudioApplication" in text:
                        yield f"http://127.0.0.1:{port.group(1)}"
                        break
                    time.sleep(0.2)
                else:
                    raise RuntimeError(
                        log_file.read_text(encoding="utf-8", errors="replace")[-3000:]
                    )
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


def run(jar):
    jar = jar.resolve(strict=True)
    with fake_provider() as (url, key, calls), packaged_app(jar, url, key) as base:
        draft = api(base, "/draft")
        assert draft["liveEnabled"] is True
        published_version = draft["version"]
        target = next(
            n for n in draft["graph"]["nodes"] if n["type"] == "extract-tasks"
        )
        target["config"]["prompt"] = DRAFT_INSTRUCTION
        saved = api(
            base,
            "/draft",
            "PUT",
            {"revision": draft["revision"], "graph": draft["graph"]},
        )

        def debug(source, request_id, mode="live", status=200):
            return api(
                base,
                "/debug",
                "POST",
                {
                    **source,
                    "nodeId": target["id"],
                    "inputs": {"text": TRANSCRIPT},
                    "mode": mode,
                    "requestId": request_id,
                },
                status,
            )

        current, fixed = {"revision": saved["revision"]}, {"version": published_version}
        sample = debug(current, "sample", mode="sample")
        assert (
            sample["effects"] == [] and "_usage" not in sample["outputs"] and not calls
        )
        for source, request_id, title, inp, out in [
            (fixed, "live-published", "Published result", 11, 4),
            (current, "live-draft", "Draft result", 17, 6),
        ]:
            result = debug(source, request_id)
            assert result["nodeId"] == target["id"] and result["mode"] == "live"
            assert (
                result["effects"] == []
                and not result["waiting"]
                and result["durationMs"] >= 0
            )
            assert result["outputs"]["items"] == [
                {"id": "task-1", "title": title, "assignee": "Morgan"}
            ]
            assert result["outputs"]["_usage"] == {
                "model": "model-returned-by-fixture",
                "inputTokens": inp,
                "outputTokens": out,
            }
        assert len(calls) == 2
        for index, request in enumerate(calls):
            assert (
                request["model"] == "requested-fixture-model"
                and request["max_completion_tokens"] == 2048
            )
            system, user = request["messages"]
            assert system["role"] == "system" and user == {
                "role": "user",
                "content": TRANSCRIPT,
            }
            assert (DRAFT_INSTRUCTION in system["content"]) == (index == 1)
            assert TRANSCRIPT not in system["content"]
        debug(current, "live-draft", status=409)
        assert len(calls) == 2
        malformed = debug(current, "malformed", status=502)
        assert malformed["nodeId"] == target["id"] and malformed["field"] == "outputs"
        assert (
            malformed["model"],
            malformed["inputTokens"],
            malformed["outputTokens"],
        ) == (
            "model-returned-by-fixture",
            19,
            3,
        )
        assert PRIVATE_ERROR not in json.dumps(malformed) and len(calls) == 3
        failed = debug(fixed, "unavailable", status=502)
        assert PRIVATE_ERROR not in json.dumps(failed) and len(calls) == 4
        debug(fixed, "unavailable", status=409)
        debug(fixed, "over-budget", status=429)
        assert len(calls) == 4
    print(
        "Live debug passed: draft and published prompts differ; equal inputs returned "
        "11/4 and 17/6 fixture tokens; invalid output preserved 19/3; four local requests, "
        "no retries, no paid provider calls."
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    run(parser.parse_args().jar)
