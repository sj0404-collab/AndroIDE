#!/usr/bin/env python3
import json, os, sys, urllib.request
BASE = os.environ.get("OPENAI_BASE", "http://127.0.0.1:8080/v1")

def chat(msg):
    req = urllib.request.Request(
        BASE + "/chat/completions",
        data=json.dumps({"model": "local", "messages": [{"role": "user", "content": msg}]}).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer local"},
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r)["choices"][0]["message"]["content"]

if __name__ == "__main__":
    print(chat(" ".join(sys.argv[1:]) or "hello"))
