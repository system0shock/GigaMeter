import json, re

print("="*72)
print("PARSER: qwen --output-format json escapes non-ASCII as \\uXXXX  [Cyrillic blocker]")
# Real CLIs serialize JSON with ensure_ascii: Cyrillic becomes \uXXXX, field is "response"
qwen_json = json.dumps({"response": "Привет, JMeter\nДобавьте Timer", "stats": {"t": 1}}, ensure_ascii=True)
print("   raw CLI stdout:", qwen_json)

# OLD prototype: regex for "content":"...", then manual unescape WITHOUT \uXXXX decoding
CONTENT_PATTERN = re.compile(r'"content"\s*:\s*"((?:[^"\\]|\\.)*)"')
m = list(CONTENT_PATTERN.finditer(qwen_json))
if m:
    old = m[-1].group(1).replace("\\n","\n").replace("\\t","\t").replace('\\"','"').replace("\\\\","\\")
else:
    old = qwen_json  # falls back to raw blob (no "content" field present)
print("   OLD result:", repr(old)[:80], "<- raw JSON blob / garbled" )

# NEW CliResponseParser: real JSON parse, field priority response>text>content>..., correct unicode
def new_parse(raw, json_mode):
    raw=raw.strip()
    if not json_mode or not raw: return raw
    try: root=json.loads(raw)
    except Exception: return raw
    for f in ("response","text","content","output","message"):
        if isinstance(root,dict) and f in root:
            v=root[f]
            if isinstance(v,str) and v: return v
    return raw
new = new_parse(qwen_json, True)
print("   NEW result:", repr(new))
assert new == "Привет, JMeter\nДобавьте Timer", "parser must decode Cyrillic + newlines"
assert "\\u04" not in new, "must not leave literal \\uXXXX"
print("   PASS: Cyrillic + newline decoded correctly\n")

print("WINDOWS LAUNCH RULE: resolveLaunchCommand wraps .cmd/.bat/bare names in cmd /c")
def needs_wrapper(exe):
    l=exe.lower()
    return not (l.endswith(".exe") or l.endswith(".com"))
def resolve(cmd, is_windows):
    if not is_windows: return cmd
    return (["cmd","/c"]+cmd) if needs_wrapper(cmd[0]) else cmd
cases = [
    (["qwen","--output-format","json"], True,  ["cmd","/c","qwen","--output-format","json"]),  # npm shim -> needs cmd /c
    (["qwen.cmd"], True,  ["cmd","/c","qwen.cmd"]),
    (["C:\\tools\\qwen.exe","-x"], True, ["C:\\tools\\qwen.exe","-x"]),                          # .exe direct
    (["qwen","-p"], False, ["qwen","-p"]),                                                       # linux: untouched
]
for cmd, win, expect in cases:
    got = resolve(cmd, win)
    ok = got==expect
    print(f"   win={str(win):5} {cmd} -> {got}  {'PASS' if ok else 'FAIL'}")
    assert ok
print()

print("ARGS PARSER: quote-aware split (replaces split('\\\\s+'))")
def parse_args(raw):
    if not raw or not raw.strip(): return []
    toks=[]; cur=[]; intok=False; q=None
    for c in raw:
        if q:
            if c==q: q=None
            else: cur.append(c)
            intok=True
        elif c in '"\'':
            q=c; intok=True
        elif c.isspace():
            if intok: toks.append("".join(cur)); cur=[]; intok=False
        else:
            cur.append(c); intok=True
    if intok: toks.append("".join(cur))
    return toks
got = parse_args('--include-directories "C:\\My Tests" --flag')
print("   in : --include-directories \"C:\\My Tests\" --flag")
print("   out:", got)
assert got == ["--include-directories", "C:\\My Tests", "--flag"], "quoted path with space must stay one token"
print("   PASS: quoted path with spaces kept as a single token")
print("="*72)
print("ALL LOGIC CHECKS PASSED")
