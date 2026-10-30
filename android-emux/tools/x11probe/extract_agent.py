#!/usr/bin/env python3
"""Extract the nested guest input-agent script out of assets/bin/emux-x11 so it
can be syntax-checked and run standalone by agent_test.sh.

emux-x11 launches the agent as `nohup /bin/sh -c '<body>'`, so the body is a
shell script embedded in single quotes with every literal ' escaped as '"'"'.
This reverses that escaping and writes a runnable script.
"""
import re, subprocess, sys, os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "bin", "emux-x11")
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/agent_extracted.sh"

src = open(SRC).read()
m = re.search(r"nohup /bin/sh -c '\n(.*?)\n  ' >/tmp/emux_agent\.log", src, re.S)
if not m:
    print("could not locate the agent heredoc in emux-x11 - has the launcher changed?", file=sys.stderr)
    sys.exit(1)
body = m.group(1).replace("'\"'\"'", "'")
open(OUT, "w").write("#!/bin/sh\n" + body)
r = subprocess.run(["sh", "-n", OUT], capture_output=True, text=True)
print(f"wrote {OUT} ({body.count(chr(10))+1} lines); sh -n: {'OK' if r.returncode == 0 else 'FAILED'}")
if r.returncode:
    print(r.stderr[:600]); sys.exit(1)
