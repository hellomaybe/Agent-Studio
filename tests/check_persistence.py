"""Run the packaged service twice against the same disposable H2 or MySQL database."""

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
JAR_DIRECTORY = ROOT / "backend/target"


@contextmanager
def database(use_mysql):
    if not use_mysql:
        yield {"STUDIO_DB_USERNAME": "studio_test", "STUDIO_DB_PASSWORD": secrets.token_hex(24)}
        return

    password = secrets.token_hex(24)
    args = [
        "docker",
        "run",
        "--detach",
        "--rm",
        "--tmpfs",
        "/var/lib/mysql",
        "-p",
        "127.0.0.1::3306",
        "-e",
        "MYSQL_RANDOM_ROOT_PASSWORD=yes",
        "-e",
        "MYSQL_DATABASE=agent_studio_test",
        "-e",
        "MYSQL_USER=studio_test",
        "-e",
        "MYSQL_PASSWORD",
        "mysql:8.4",
    ]
    container = subprocess.check_output(
        args, env={**os.environ, "MYSQL_PASSWORD": password}, text=True
    ).strip()
    try:
        for _ in range(90):
            probe = subprocess.run(
                [
                    "docker",
                    "exec",
                    container,
                    "sh",
                    "-c",
                    'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=tcp -h127.0.0.1 '
                    '-u"$MYSQL_USER" "$MYSQL_DATABASE" -Nse "SELECT 1"',
                ],
                capture_output=True,
            )
            if probe.returncode == 0 and probe.stdout.strip() == b"1":
                break
            time.sleep(1)
        else:
            raise RuntimeError("The disposable MySQL server did not become ready.")
        address = subprocess.check_output(
            ["docker", "port", container, "3306/tcp"], text=True
        ).strip()
        yield {
            "STUDIO_DB_URL": f"jdbc:mysql://{address}/agent_studio_test?sslMode=DISABLED&allowPublicKeyRetrieval=true",
            "STUDIO_DB_USERNAME": "studio_test",
            "STUDIO_DB_PASSWORD": password,
        }
    finally:
        subprocess.run(
            ["docker", "rm", "--force", container],
            check=True,
            stdout=subprocess.DEVNULL,
        )


def request(base, path, method="GET", body=None, expected=200):
    data = json.dumps(body).encode() if body is not None else None
    req = Request(
        base + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urlopen(req, timeout=5) as reply:
            status, payload = reply.status, json.load(reply)
    except HTTPError as error:
        status, payload = error.code, json.load(error)
    if status != expected:
        raise AssertionError(
            f"{method} {path}: expected {expected}, got {status}: {payload}"
        )
    return payload.get("data")


@contextmanager
def application(workdir, settings, jar):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    base = f"http://127.0.0.1:{port}/api/studio/meeting-notes"
    log_path = workdir / "server.log"
    clean_env = {
        key: value
        for key, value in os.environ.items()
        if not key.startswith(("STUDIO_", "SPRING_", "SERVER_"))
    }
    with log_path.open("w", encoding="utf-8") as log:
        service = subprocess.Popen(
            ["java", "-jar", str(jar)],
            cwd=workdir,
            env={
                **clean_env,
                **settings,
                "SERVER_ADDRESS": "127.0.0.1",
                "SERVER_PORT": str(port),
                "STUDIO_LIVE_ENABLED": "false",
                "STUDIO_MODEL_API_KEY": "",
            },
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        try:
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline and service.poll() is None:
                try:
                    request(base, "/draft")
                    break
                except (URLError, ConnectionError, TimeoutError):
                    time.sleep(0.35)
            else:
                raise RuntimeError(
                    log_path.read_text(encoding="utf-8", errors="replace")[-4000:]
                )
            yield base
        finally:
            if service.poll() is None:
                service.terminate()
                try:
                    service.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    service.kill()
                    service.wait()


def check(use_mysql):
    suffix = "-mysql" if use_mysql else ""
    jar = JAR_DIRECTORY / f"agent-studio-backend-0.1.0-SNAPSHOT{suffix}.jar"
    if not jar.is_file():
        raise FileNotFoundError(
            f"Build the backend before running the test: {jar.name}"
        )
    with database(use_mysql) as settings, tempfile.TemporaryDirectory() as temp:
        workdir = Path(temp)
        legacy_schema = {
            **settings,
            "SPRING_FLYWAY_TARGET": "2",
            "STUDIO_WORKFLOW_SCHEDULER_ENABLED": "false",
        }
        with application(workdir, legacy_schema, jar) as api:
            draft = request(api, "/draft")
            assert request(api, "/runs") == []
            run_input = {
                "mode": "sample",
                "sampleKey": "weekly",
                "requestId": "restart-weekly",
                "revision": draft["revision"],
            }
            original = request(api, "/runs", "POST", run_input)
            assert original["status"] == "waiting" and len(original["items"]) == 2
            config = {**draft["config"], "threshold": 3}
            saved = request(
                api, "/draft", "PUT", {"revision": draft["revision"], "config": config}
            )
            assert saved["revision"] == draft["revision"] + 1
            request(
                api,
                "/draft",
                "PUT",
                {"revision": draft["revision"], "config": config},
                409,
            )

        with application(workdir, settings, jar) as api:
            assert request(api, "/draft") == saved
            assert (
                request(api, "/runs", "POST", run_input)["runId"] == original["runId"]
            )
            path = "/runs/" + original["runId"]
            assert request(api, path)["config"] == draft["config"]
            selection = {"selectedIds": [original["items"][1]["id"]]}
            done = request(api, path + "/confirm", "POST", selection)
            assert (
                done["status"] == "completed"
                and done["selectedIds"] == selection["selectedIds"]
            )
            assert request(api, path + "/confirm", "POST", selection) == done
            request(api, path + "/confirm", "POST", {"selectedIds": []}, 409)
            skipped = request(
                api,
                "/runs",
                "POST",
                {
                    **run_input,
                    "revision": saved["revision"],
                    "requestId": "after-restart",
                },
            )
            assert skipped["status"] == "completed" and "confirm" not in skipped["path"]
            assert len(request(api, "/runs")) == 2
            meeting, feedback = exercise_graphs(api)

        # Both checkpoints were recorded by the previous process.
        with application(workdir, settings, jar) as api:
            root = api.removesuffix("/api/studio/meeting-notes") + "/api/apps"
            meeting_api = root + "/meeting-notes"
            old_path = "/runs/" + meeting["runId"]
            restored = request(meeting_api, old_path)
            assert restored["versionId"] == 1 and restored["status"] == "waiting"
            approval = {
                "signalId": "approve-once",
                "waitId": restored["wait"]["waitId"],
                "values": {"selectedIds": [restored["wait"]["items"][0]["id"]]},
            }
            request(meeting_api, old_path + "/resume", "POST", approval)
            completed = await_status(meeting_api, old_path, "completed")
            assert completed["versionId"] == 1 and len(completed["effects"]) == 1
            request(meeting_api, old_path + "/resume", "POST", approval)
            assert len(request(meeting_api, old_path)["effects"]) == 1
            assert restored["graph"] == meeting["graph"]
            feedback_api = root + "/feedback-triage"
            fpath = "/runs/" + feedback["runId"]
            assert request(feedback_api, fpath)["status"] == "paused"
            request(
                feedback_api, fpath + "/continue", "POST", {"signalId": "continue-once"}
            )
            done = await_status(feedback_api, fpath, "completed")
            assert not done["effects"] and "review" not in done["path"]
            assert [e["seq"] for e in done["events"]] == sorted(
                {e["seq"] for e in done["events"]}
            )
    print(
        "MySQL 8.4" if use_mysql else "H2 file",
        "V2 upgrade, graph publication, both templates, pause/restart and idempotent effects passed.",
    )


def await_status(api, path, expected):
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        result = request(api, path)
        if result["status"] == expected:
            return result
        if result["status"] in ("failed", "unknown", "expired", "terminated"):
            raise AssertionError(
                (result["status"], result.get("errorCode"), result.get("currentNodeId"))
            )
        time.sleep(0.2)
    raise AssertionError(f"{path} did not reach {expected}.")


def exercise_graphs(legacy_api):
    root = legacy_api.removesuffix("/api/studio/meeting-notes") + "/api/apps"
    meeting_api = root + "/meeting-notes"
    feedback_api = root + "/feedback-triage"
    for api in [meeting_api, feedback_api]:
        draft = request(api, "/draft")
        report = request(
            api,
            "/evaluations",
            "POST",
            {"revision": draft["revision"], "graph": draft["graph"]},
        )
        assert len(report["cases"]) >= 3 and all(
            case["candidate"]["pass"] for case in report["cases"]
        )
    pinned = {
        "revision": 1,
        "nodeId": "filter",
        "mode": "sample",
        "inputs": {
            "items": [
                {"id": "a", "title": "Send report", "assignee": "Alex"},
                {"id": "b", "title": "Schedule", "assignee": ""},
            ]
        },
    }
    debug = request(meeting_api, "/debug", "POST", pinned)
    assert (
        len(debug["outputs"]["items"]) == 1 and len(debug["outputs"]["excluded"]) == 1
    )
    body = {
        "requestId": "frozen-run",
        "versionId": 1,
        "mode": "sample",
        "input": {"text": "Alex: Send report\n- Schedule"},
    }
    started = request(meeting_api, "/runs", "POST", body)
    assert request(meeting_api, "/runs", "POST", body)["runId"] == started["runId"]
    waiting = await_status(meeting_api, "/runs/" + started["runId"], "waiting")
    assert len(waiting["wait"]["items"]) == 1 and "Send report" not in json.dumps(
        waiting["events"]
    )
    graph = request(meeting_api, "/draft")["graph"]
    next(n for n in graph["nodes"] if n["id"] == "end")["config"]["message"] = (
        "Version two"
    )
    next_draft = request(meeting_api, "/draft", "PUT", {"revision": 1, "graph": graph})
    publish = {"revision": next_draft["revision"]}
    request(meeting_api, "/publish", "POST", publish, 409)
    request(meeting_api, "/evaluations", "POST", {**publish, "graph": graph})
    published = request(meeting_api, "/publish", "POST", publish)
    assert (
        published["version"] == 2
        and request(meeting_api, "/publish", "POST", publish)["version"] == 2
    )
    paused = request(
        feedback_api,
        "/runs",
        "POST",
        {
            "requestId": "bookmark",
            "pauseAfterNode": "start",
            "input": {"text": "Please add a dark theme"},
        },
    )
    paused = await_status(feedback_api, "/runs/" + paused["runId"], "paused")
    return waiting, paused


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--mysql",
        action="store_true",
        help="Start a disposable MySQL 8.4 Docker container.",
    )
    check(parser.parse_args().mysql)
