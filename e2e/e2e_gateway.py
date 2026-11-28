"""Probe: verifies jdtls-native resolves real symbols in sentry-gateway.

Usage: python e2e/e2e_gateway.py --exe target/native/jdtls-native.exe
"""

import json
import os
import pathlib
import subprocess
import sys
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
PROJECT = pathlib.Path(r"E:\workspace\sentry-gateway").resolve()
TARGET = PROJECT / "seal-gateway-sign" / "src" / "main" / "java" / "seal" / "gateway" / "sign" / "signer" / "HmacSigner.java"


class Client:
    def __init__(self, proc):
        self.proc = proc
        self.pending = {}
        self.notifications = []
        self.next_id = 1
        self.lock = threading.Lock()
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def send(self, obj):
        body = json.dumps(obj).encode("utf-8")
        self.proc.stdin.write(f"Content-Length: {len(body)}\r\n\r\n".encode("ascii") + body)
        self.proc.stdin.flush()

    def request(self, method, params):
        with self.lock:
            mid = self.next_id
            self.next_id += 1
        ev = threading.Event()
        holder = {}
        with self.lock:
            self.pending[mid] = (ev, holder)
        self.send({"jsonrpc": "2.0", "id": mid, "method": method, "params": params})
        return ev, holder

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})

    def wait_response(self, ev, holder, timeout=60):
        if not ev.wait(timeout):
            raise TimeoutError(f"no response within {timeout}s")
        msg = holder.get("msg", {})
        if "error" in msg:
            raise RuntimeError(f"server error: {msg['error']}")
        return msg.get("result")

    def _read(self):
        try:
            while True:
                headers = {}
                while True:
                    line = self.proc.stdout.readline()
                    if not line:
                        return
                    if line in (b"\r\n", b"\n"):
                        break
                    k, _, v = line.decode("ascii").partition(":")
                    headers[k.strip().lower()] = v.strip()
                if "content-length" not in headers:
                    continue
                body = self.proc.stdout.read(int(headers["content-length"]))
                msg = json.loads(body.decode("utf-8"))
                if "id" in msg and ("result" in msg or "error" in msg):
                    with self.lock:
                        entry = self.pending.pop(msg["id"], None)
                    if entry:
                        ev, holder = entry
                        holder["msg"] = msg
                        ev.set()
                elif "method" in msg:
                    self.notifications.append(msg)
        except Exception as e:  # noqa: BLE001
            print(f"[reader stopped: {e!r}]", file=sys.stderr)

    def last_diagnostics(self, uri):
        result = None
        for n in self.notifications:
            if n.get("method") == "textDocument/publishDiagnostics" and n["params"].get("uri") == uri:
                result = n["params"].get("diagnostics", [])
        return result


def pos(text, needle, off=0):
    lines = text.splitlines()
    for i, ln in enumerate(lines):
        idx = ln.find(needle)
        if idx >= 0:
            return {"line": i, "character": idx + off}
    raise ValueError(f"{needle!r} not found")


def main():
    exe = None
    if "--exe" in sys.argv:
        exe = pathlib.Path(sys.argv[sys.argv.index("--exe") + 1]).resolve()
        if not exe.exists() and exe.with_suffix("").exists():
            exe = exe.with_suffix("")
        cmd = [str(exe)]
        cwd = str(exe.parent)
    else:
        cp = os.pathsep.join([str(ROOT / "target" / "classes"), str(ROOT / "target" / "libs" / "*")])
        cmd = ["java", "-cp", cp, "dev.jdtls.lite.Main"]
        cwd = None

    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, cwd=cwd)
    threading.Thread(target=lambda: [sys.stderr.write("[server] " + l.decode("utf-8", "replace"))
                                     for l in iter(proc.stderr.readline, b"")], daemon=True).start()

    c = Client(proc)
    target_uri = TARGET.as_uri()
    text = TARGET.read_text(encoding="utf-8")
    ok = True

    def check(name, cond, detail=""):
        nonlocal ok
        print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))
        if not cond:
            ok = False

    try:
        result = c.wait_response(*c.request("initialize", {
            "processId": os.getpid(),
            "rootUri": PROJECT.as_uri(),
            "capabilities": {},
            "workspaceFolders": [{"uri": PROJECT.as_uri(), "name": "sentry-gateway"}],
        }), timeout=60)
        print(f"  [PASS] initialize")
        c.notify("initialized", {})
        c.notify("textDocument/didOpen", {"textDocument": {
            "uri": target_uri, "languageId": "java", "version": 1, "text": text}})

        # wait for model engine + diagnostics
        deadline = time.time() + 120
        ready = False
        while time.time() < deadline:
            ev, holder = c.request("textDocument/completion", {
                "textDocument": {"uri": target_uri}, "position": pos(text, "return", 6)})
            r = c.wait_response(ev, holder, timeout=15)
            if r is not None:
                ready = True
                break
            time.sleep(2)
        check("model engine ready", ready)

        time.sleep(1)
        # the model-engine replay publishes diagnostics right after readiness;
        # poll briefly so the publish notification has landed before we judge
        diags = None
        deadline = time.time() + 15
        while time.time() < deadline:
            diags = c.last_diagnostics(target_uri)
            if diags is not None:
                break
            time.sleep(0.5)
        errors = [d for d in (diags or []) if d.get("severity") == 1]
        check("diagnostics published", diags is not None, f"{len(diags or [])} total, {len(errors)} error(s)")
        if errors:
            for d in errors[:3]:
                print(f"    error: {d['message'][:80]} @ line {d['range']['start']['line']}")

        # hover on Signer (same module interface)
        hov = c.wait_response(*c.request("textDocument/hover", {
            "textDocument": {"uri": target_uri}, "position": pos(text, "implements Signer", 13)}))
        hv = (hov or {}).get("contents", {}).get("value", "") if isinstance(hov, dict) else ""
        check("hover Signer (same module)", "Signer" in hv, repr(hv[:70]))

        # hover on MessageDigest (JDK via jrt)
        hov2 = c.wait_response(*c.request("textDocument/hover", {
            "textDocument": {"uri": target_uri}, "position": pos(text, "return MessageDigest", 12)}))
        hv2 = (hov2 or {}).get("contents", {}).get("value", "") if isinstance(hov2, dict) else ""
        check("hover MessageDigest (JDK)", "MessageDigest" in hv2, repr(hv2[:70]))

        # hover on Algorithm (same module, different package)
        hov3 = c.wait_response(*c.request("textDocument/hover", {
            "textDocument": {"uri": target_uri}, "position": pos(text, "Algorithm algorithm", 2)}))
        hv3 = (hov3 or {}).get("contents", {}).get("value", "") if isinstance(hov3, dict) else ""
        check("hover Algorithm (same module, config pkg)", "Algorithm" in hv3, repr(hv3[:70]))

        # definition of Signer (project source)
        defs = c.wait_response(*c.request("textDocument/definition", {
            "textDocument": {"uri": target_uri}, "position": pos(text, "implements Signer", 13)}))
        def_uris = [d.get("uri", "") for d in (defs or [])]
        check("definition → Signer.java", any("Signer.java" in u for u in def_uris), str(def_uris)[:80])

        # definition of MessageDigest (JDK via src.zip / decompiler)
        jdk_defs = c.wait_response(*c.request("textDocument/definition", {
            "textDocument": {"uri": target_uri}, "position": pos(text, "return MessageDigest", 12)}))
        jdk_uris = [d.get("uri", "") for d in (jdk_defs or [])]
        check("definition → MessageDigest (JDK)",
              len(jdk_uris) >= 1 and any(".java" in u for u in jdk_uris),
              str(jdk_uris)[:100])

        # hover on MessageDigest declaration (from the decompiled/jdk source)
        if jdk_uris:
            jdk_uri = jdk_uris[0]
            jdk_text = pathlib.Path(jdk_uri.replace("file:///", "").replace("file:/", "")).read_text(encoding="utf-8")                 if jdk_uri.startswith("file:") else ""
            if jdk_text:
                # find "class MessageDigest" in the decompiled/jdk source
                for i, ln in enumerate(jdk_text.splitlines()):
                    if "class MessageDigest" in ln:
                        # hover on the class declaration
                        _ev, holder = c.request("textDocument/hover", {
                            "textDocument": {"uri": jdk_uri},
                            "position": {"line": i, "character": ln.index("MessageDigest") + 2}})
                        jhov = c.wait_response(_ev, holder)
                        jhv = (jhov or {}).get("contents", {}).get("value", "") if isinstance(jhov, dict) else ""
                        check("hover on JDK source (MessageDigest)", "MessageDigest" in jhv, repr(jhv[:70]))
                        break

        # documentSymbol
        syms = c.wait_response(*c.request("textDocument/documentSymbol", {
            "textDocument": {"uri": target_uri}}))
        snames = []

        def walk(s):
            for x in s or []:
                snames.append(x.get("name", ""))
                walk(x.get("children") or [])

        walk(syms)
        check("documentSymbol has HmacSigner+sign+verify",
              any("HmacSigner" in n for n in snames) and any("sign" in n for n in snames),
              f"{len(snames)} symbol(s)")

        # workspaceSymbol
        wsym = c.wait_response(*c.request("workspace/symbol", {"query": "Gateway"}), timeout=120)
        self_check = bool(wsym)
        check("workspaceSymbol 'Gateway'", self_check,
              f"{len(wsym or [])} found")

        # java/buildWorkspace
        build_ok = c.wait_response(*c.request("java/buildWorkspace", {}), timeout=120)
        check("java/buildWorkspace", build_ok is True, f"result={build_ok}")

        # java/resolveClasspath
        rcp = c.wait_response(*c.request("java/resolveClasspath",
                                         {"mainClass": "seal.gateway.server.SealGatewayApplication"}), timeout=60)
        rcp_data = rcp or {}
        cp_entries = rcp_data.get("classpath", [])
        check("java/resolveClasspath", bool(rcp_data.get("jreHome")) and len(cp_entries) >= 3,
              f"{len(cp_entries)} entries, jre={rcp_data.get('jreHome', '')[:40]}")

    except Exception as e:  # noqa: BLE001
        print(f"  [FAIL] exception: {e!r}")
        ok = False
    finally:
        try:
            c.send({"jsonrpc": "2.0", "id": 999, "method": "shutdown", "params": None})
            c.send({"jsonrpc": "2.0", "method": "exit"})
            proc.wait(10)
        except Exception:  # noqa: BLE001
            proc.kill()

    print(f"\n{'PASS' if ok else 'FAIL'}: sentry-gateway ({'native' if exe else 'JVM'})")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
