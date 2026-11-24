"""Exercise a live local Spring AI development server; uses its configured model budget."""
import http.cookiejar
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request
import uuid

origin = "http://127.0.0.1:29479"
base = origin + "/open-source/lab/agent/api/"
jar = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar))

def api(path, payload=None):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    req = urllib.request.Request(base + path, data, {"Content-Type":"application/json", "Origin":origin})
    with client.open(req,timeout=180) as response:
        return json.load(response)

def run(app,text,version=1):
    body={"id":str(uuid.uuid4()),"input":text,"version":version}
    record=api(app+"/run",body)
    again=api(app+"/run",body)
    assert record["id"]==again["id"] and record["ledger"]==again["ledger"]
    return record

def publish(app, change):
    state=api(app); graph=state["draft"];change(graph)
    updated=api(app+"/draft",{"revision":state["revision"],"graph":graph})
    return api(app+"/publish",{"revision":updated["revision"]})["version"]

def main(out):
    caps=api("session",{})
    assert caps["ready"],"A real dedicated model endpoint is required."
    records=[]
    for app,text in [
        ("packing-list","出行天数为 6 天。输出 6"),
        ("quote-invoice","报价规范：印刷 | 2 | 100"),
        ("batch-rename","文件名：photo.png"),
        ("subtitle-editor","0 | 1000 | 开始做字幕"),
        ("title-assistant","标题编辑：六月旅行收纳清单。"),
    ]:
        data=run(app,text);print(app,data["status"],[(x["node"],x.get("reason","")) for x in data["trace"]],flush=True)
        assert data["status"] in ("completed","waiting"), data.get("error")
        model=next(x for x in data["trace"] if x["node"]=="model")
        assert model["inputTokens"]>0 and model["outputTokens"]>0 and data["ledger"]
        rag=next(x for x in data["trace"] if x["node"]=="retrieve");assert rag["hits"]
        if app=="quote-invoice":assert "212.00" in data["result"]["summary"],data["result"]
        if app=="batch-rename":
            assert data["status"]=="waiting"
            data=api(app+"/confirm",{"id":data["id"],"approve":True});assert data["status"]=="completed"
        records.append(data)
    app="title-assistant"
    def loop(g):
        g["edges"]["start.next"]="tool";g["maxCalls"]=1;g["prompt"]="只输出这两个汉字：最好。不要添加其他字符。"
    v=publish(app,loop);record=run(app,"最好",v)
    assert any(e.get("edge")=="check.fail" and e.get("next")=="model" for e in record["trace"])
    assert record["status"] in ("completed","limit")
    records.append(record)
    v=publish(app,lambda g:g.update(budget=0,edges={**g["edges"],"start.next":"model"}))
    budget=run(app,"预算检查",v)
    assert budget["status"]=="budget_limit" and not budget["ledger"];records.append(budget)
    original=records[4];replay=api(app+"/runs/"+original["id"])
    assert replay["version"]==1 and replay["graph"]==original["graph"]
    other=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    other.open(urllib.request.Request(base+"session",b"{}",{"Origin":origin})).close()
    try: other.open(base+app+"/runs/"+original["id"])
    except urllib.error.HTTPError as exc: assert exc.code==404
    else: raise AssertionError("Other room was able to read private run")
    Path(out).write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding="utf8")
    print("PASS: five live agents, duplicate submit, review, conditional loop, budget cutoff, historical graph and visitor isolation.",flush=True)

if __name__=="__main__": main(sys.argv[1])
