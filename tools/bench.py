"""Startup/latency/memory comparison: JVM jar vs native exe.

Measures: process spawn -> initialize response (cold start LSP readiness),
and the server process working set right after boot.
"""

import json
import os
import pathlib
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent


class Client:
    def __init__(self, proc):
        self.proc = proc
        self.pending = {}
        self.next_id = 1
        import threading
        self.lock = threading.Lock()
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def send(self, obj):
        body = json.dumps(obj).encode()
        self.proc.stdin.write(f"Content-Length: {len(body)}\r\n\r\n".encode() + body)
        self.proc.stdin.flush()

    def request(self, method, params):
        with self.lock:
            mid = self.next_id
            self.next_id += 1
        p = __import__("threading").Event()
        holder = {}

        def waiter():
            # read until response with matching id arrives
            pass

        self.pending[mid] = (p, holder)
        self.send({"jsonrpc": "2.0", "id": mid, "method": method, "params": params})
        return mid, p, holder

    def _read(self):
        import threading
        while True:
            try:
                headers = {}
                while True:
                    line = self.proc.stdout.readline()
                    if not line:
                        return
                    if line in (b"\r\n", b"\n"):
                        break
                    k, _, v = line.decode("ascii").partition(":")
                    headers[k.strip().lower()] = v.strip()
                n = int(headers["content-length"])
                body = self.proc.stdout.read(n)
                msg = json.loads(body)
                if "id" in msg and msg["id"] in self.pending:
                    ev, holder = self.pending.pop(msg["id"])
                    holder["msg"] = msg
                    ev.set()
            except Exception:
                return


def rss_mb(pid):
    try:
        out = subprocess.check_output(
            ["powershell", "-Command",
             f"(Get-Process -Id {pid}).WorkingSet64 / 1MB"], text=True)
        return round(float(out.strip()), 1)
    except Exception:
        return -1


def bench(name, cmd, cwd=None, iterations=3):
    results = []
    for i in range(iterations):
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, cwd=cwd)
        client = Client(proc)
        t0 = time.perf_counter()
        mid, ev, holder = client.request("initialize", {
            "processId": os.getpid(),
            "rootUri": "file:///C:/Windows/Temp",
            "capabilities": {},
        })
        ev.wait(120)
        t1 = time.perf_counter()
        ready_ms = (t1 - t0) * 1000
        time.sleep(0.3)  # let background boot threads allocate
        mem = rss_mb(proc.pid)
        results.append((ready_ms, mem))
        try:
            client.send({"jsonrpc": "2.0", "id": 9999, "method": "shutdown", "params": None})
            client.send({"jsonrpc": "2.0", "method": "exit"})
            proc.wait(5)
        except Exception:
            proc.kill()
        print(f"  {name} run{i + 1}: initialize-response {ready_ms:.0f} ms, RSS {mem} MB")
    best = min(results)
    print(f"  {name} BEST: {best[0]:.0f} ms / {best[1]} MB")
    return best


def main():
    print("== JVM (java -cp classes+libs, Temurin 21)")
    jvm = bench("JVM", ["java", "-cp",
                        os.pathsep.join([str(ROOT / "target" / "classes"),
                                         str(ROOT / "target" / "libs" / "*")]),
                        "dev.jdtls.lite.Main"])
    print("== native (target/native/jdtls-native.exe)")
    native = bench("native", [str(ROOT / "target" / "native" / "jdtls-native.exe")],
                   cwd=str(ROOT / "target" / "native"))
    print(f"\n== summary: JVM {jvm[0]:.0f} ms / {jvm[1]} MB  vs  native {native[0]:.0f} ms / {native[1]} MB")


if __name__ == "__main__":
    main()
