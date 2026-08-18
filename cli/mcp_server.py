#!/usr/bin/env python3
import json, sys, os, urllib.request
BASE = os.environ.get("OPENAI_BASE", "http://127.0.0.1:8080/v1")

def call(prompt):
    req = urllib.request.Request(
        BASE + "/chat/completions",
        data=json.dumps({"model": "local", "messages": [{"role": "user", "content": prompt}]}).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer local"},
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r)["choices"][0]["message"]["content"]

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        o = json.loads(line)
        prompt = o.get("prompt") or o.get("params", {}).get("text", "")
        print(json.dumps({"id": o.get("id"), "result": call(prompt)}), flush=True)
    except Exception as e:
        print(json.dumps({"error": str(e)}), flush=True)
