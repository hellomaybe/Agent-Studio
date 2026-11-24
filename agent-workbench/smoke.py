"""Start the packaged editor on loopback and verify an offline graph and workspace isolation."""
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

root = Path(__file__).resolve().parent
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
origin = f"http://127.0.0.1:{port}"
base = origin + "/open-source/lab/agent/"

def client():
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def call(opener, path, payload=None):
    body = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(base+"api/"+path, body, {"Origin":origin,"Content-Type":"application/json"})
    with opener.open(request,timeout=5) as r:
        return json.load(r)

with tempfile.TemporaryDirectory() as temp:
    env = {k:v for k,v in os.environ.items() if not k.startswith("AGENT_DEMO_")}
    env.update(AGENT_DEMO_DB_PASSWORD=secrets.token_hex(24), AGENT_DEMO_PORT=str(port), AGENT_DEMO_ORIGIN=origin)
    process = subprocess.Popen(["java","-jar",str(root/"target/agent-workbench-0.2.0.jar"),str(root/"site")],cwd=temp,env=env)
    try:
        a,b=client(),client()
        for attempt in range(60):
            if process.poll() is not None: raise RuntimeError("Server exited during startup")
            try:
                with a.open(base,timeout=2) as response: assert response.status==200
                break
            except OSError: time.sleep(.3)
        session=call(a,"session",{})
        assert not session["ready"] and len(session["features"])==5
        rid=str(uuid.uuid4())
        run=call(a,"quote-invoice/run",{"id":rid,"version":1,"input":"Print | 2 | 100"})
        assert run["status"]=="completed" and "212.00" in run["result"]["summary"]
        assert call(a,"quote-invoice/runs/"+rid)["id"]==rid
        call(b,"session",{})
        try: call(b,"quote-invoice/runs/"+rid)
        except urllib.error.HTTPError as error: assert error.code==404
        else: raise AssertionError("Workspace isolation failed")
        print("Packaged executable, five tools, quote calculation, storage and isolation passed.")
    finally:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill(); process.wait()
