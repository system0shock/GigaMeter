#!/usr/bin/env python3
"""Faithful OS-level verification of the CliTransport design.
Python subprocess uses the same OS pipe buffers (~64 KiB) and blocking-read
semantics as the JVM, so the deadlock and dead-timeout bugs reproduce as in Java.
OLD = prototype (read stdout fully, then stderr, then waitFor).
NEW = CliTransport (drain stdout+stderr on separate threads, waitFor(timeout) governs)."""
import subprocess, threading, os
FAKE = os.path.join(os.path.dirname(__file__), "fakecli")
HARD_CAP = 6.0
TIMEOUT_MS = 2000

def run_old(cmd, stdin_text, timeout_ms):
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    p.stdin.write(stdin_text.encode()); p.stdin.close()
    out = p.stdout.read()          # blocks on hung/noisy child
    err = p.stderr.read()
    p.wait(timeout=timeout_ms/1000.0)
    return out.decode(errors="replace"), err.decode(errors="replace"), p.returncode, False

def run_new(cmd, stdin_text, timeout_ms):
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                         start_new_session=True)  # own process group == process tree
    buf = {}
    def drain(name, stream): buf[name] = stream.read()
    t_out = threading.Thread(target=drain, args=("out", p.stdout), daemon=True)
    t_err = threading.Thread(target=drain, args=("err", p.stderr), daemon=True)
    t_out.start(); t_err.start()
    try:
        p.stdin.write(stdin_text.encode()); p.stdin.close()
    except BrokenPipeError:
        pass
    timed_out = False
    try:
        p.wait(timeout=timeout_ms/1000.0)
    except subprocess.TimeoutExpired:
        timed_out = True
        import signal
        try: os.killpg(os.getpgid(p.pid), signal.SIGKILL)  # kill the whole tree
        except ProcessLookupError: pass
        p.wait()
    t_out.join(2); t_err.join(2)
    return (buf.get("out", b"").decode(errors="replace"),
            buf.get("err", b"").decode(errors="replace"), p.returncode, timed_out)

def with_cap(fn, *args):
    box = {}
    def go():
        try: box["r"] = fn(*args)
        except Exception as e: box["e"] = e
    th = threading.Thread(target=go, daemon=True); th.start(); th.join(HARD_CAP)
    if th.is_alive(): return None
    if "e" in box: raise box["e"]
    return box["r"]

def cli(name): return [os.path.join(FAKE, name)]
def show(label, res):
    if res is None:
        print(f"   {label:5} -> HANG (blocked past {HARD_CAP}s cap)"); return
    out, err, code, to = res
    snip = out.strip().replace("\n"," ")[:50]
    print(f"   {label:5} -> finished | timedOut={to} exit={code} stdout='{snip}' stderrLen={len(err)}")

print("="*72)
print("S1: normal CLI (stdout JSON, exit 0)")
show("OLD", with_cap(run_old, cli("normal.sh"), "hello", TIMEOUT_MS))
show("NEW", with_cap(run_new, cli("normal.sh"), "hello", TIMEOUT_MS))
print("\nS2: noisy stderr ~2MB -> two-channel deadlock  [BLOCKER #3]")
show("OLD", with_cap(run_old, cli("noisy.sh"), "hello", TIMEOUT_MS))
show("NEW", with_cap(run_new, cli("noisy.sh"), "hello", TIMEOUT_MS))
print("\nS3: hung CLI (sleep 120) -> timeout enforcement  [BLOCKER #2]")
show("OLD", with_cap(run_old, cli("hang.sh"), "hello", TIMEOUT_MS))
show("NEW", with_cap(run_new, cli("hang.sh"), "hello", TIMEOUT_MS))
print("\nS4: failing CLI (stderr + exit 3)")
show("OLD", with_cap(run_old, cli("fail.sh"), "hello", TIMEOUT_MS))
show("NEW", with_cap(run_new, cli("fail.sh"), "hello", TIMEOUT_MS))
print("="*72)
