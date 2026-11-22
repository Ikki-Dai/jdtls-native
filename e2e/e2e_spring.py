"""E2E against real-world Spring projects (not the toy demo).

Targets:
  petclinic  - spring-projects/spring-petclinic (Maven, spring-boot jars imported)
  framework  - spring-projects/spring-framework (multi-module gradle; source-scan fallback)

Usage:
  python e2e/e2e_spring.py --jvm
  python e2e/e2e_spring.py --exe target/native/jdtls-native.exe --project framework
"""

import json
import os
import pathlib
import subprocess
import sys
import threading
import time

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
WORKSPACE = ROOT.parent  # E:\workspace\jdtlsp (holds the cloned spring repos)


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

    def wait_response(self, ev, holder, timeout=60):
        if not ev.wait(timeout):
            raise TimeoutError(f"no response within {timeout}s")
        msg = holder.get("msg", {})
        if "error" in msg:
            raise RuntimeError(f"server error: {msg['error']}")
        return msg.get("result")


class Scenario:
    def __init__(self, client, project_dir):
        self.client = client
        self.project = pathlib.Path(project_dir).resolve()
        self.project_uri = self.project.as_uri()
        self.ok = True

    def check(self, name, condition, detail=""):
        print(f"  [{'PASS' if condition else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))
        if not condition:
            self.ok = False
        return condition

    @staticmethod
    def pos(text, needle, offset_in_needle=0):
        lines = text.splitlines()
        for i, line in enumerate(lines):
            idx = line.find(needle)
            if idx >= 0:
                return {"line": i, "character": idx + offset_in_needle}
        raise ValueError(f"needle {needle!r} not found")

    def wait_model_ready(self, timeout_s):
        """Model engine is ready when a model completion answer arrives."""
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            ev, holder = self.probe_completion()
            if ev.wait(15):
                result = holder.get("msg", {}).get("result")
                if result is not None:
                    return True
            time.sleep(1.0)
        return False

    def probe_completion(self):
        return self.client.request("textDocument/completion", {
            "textDocument": {"uri": self.target_uri},
            "position": self.target_pos,
        })

    def run(self, target_rel, hover_needle, symbol_query, hover_offset=2):
        c = self.client
        target = self.project / target_rel
        self.target_uri = target.as_uri()
        text = target.read_text(encoding="utf-8")
        self.target_pos = self.pos(text, "return")

        c.notify("textDocument/didOpen", {"textDocument": {
            "uri": self.target_uri, "languageId": "java", "version": 1, "text": text}})
        print(f"  didOpen {target_rel} ({len(text)} chars)")

        if not self.wait_model_ready(300):
            self.check("model engine ready", False, "timeout")
            return False
        self.check("model engine ready", True)

        # diagnostics for the real file (count them; content varies per project)
        deadline = time.time() + 30
        diags = None
        while time.time() < deadline:
            diags = self.last_diagnostics(self.target_uri)
            if diags is not None:
                break
            time.sleep(0.5)
        errors = [d for d in (diags or []) if d.get("severity") == 1]
        self.check("model diagnostics published", diags is not None,
                   f"{len(diags or [])} diagnostic(s), {len(errors)} error(s)")

        # documentSymbol on the real file
        ev, holder = c.request("textDocument/documentSymbol", {"textDocument": {"uri": self.target_uri}})
        symbols = c.wait_response(ev, holder)
        names = []

        def walk(syms):
            for s in syms or []:
                names.append(s.get("name", ""))
                walk(s.get("children") or [])

        walk(symbols)
        self.check("documentSymbol non-empty", len(names) >= 1, f"{len(names)} symbol(s)")

        # hover on a symbol inside the real file
        hpos = self.pos(text, hover_needle, hover_offset)
        _ev, holder = c.request("textDocument/hover", {
            "textDocument": {"uri": self.target_uri}, "position": hpos})
        hv = c.wait_response(_ev, holder)
        hvv = (hv or {}).get("contents", {}).get("value", "") if isinstance(hv, dict) else ""
        self.check("hover on real code", bool(hvv), repr(hvv[:80]))

        # definition on the same symbol
        _ev, holder = c.request("textDocument/definition", {
            "textDocument": {"uri": self.target_uri}, "position": hpos})
        defs = c.wait_response(_ev, holder)
        self.check("definition resolves", bool(defs), str(defs)[:100])

        # completion on a member access chain found in the file
        comp_pos = self.pos(text, "return", 6)
        _ev, holder = c.request("textDocument/completion", {
            "textDocument": {"uri": self.target_uri}, "position": comp_pos})
        comp = c.wait_response(_ev, holder)
        self.check("completion non-empty", bool(comp), f"{len(comp or [])} item(s)")

        # workspace symbol search
        # first query may arrive while the index is still building on a cold start
        deadline = time.time() + 120
        wsym = None
        while time.time() < deadline:
            _ev, holder = c.request("workspace/symbol", {"query": symbol_query})
            wsym = c.wait_response(_ev, holder)
            if wsym:
                break
            time.sleep(2.0)
        self.check(f"workspaceSymbol '{symbol_query}'", bool(wsym),
                   f"{len(wsym or [])} found; " + str([s.get('name') for s in (wsym or [])][:3]))

        return self.ok

    def last_diagnostics(self, uri):
        result = None
        for n in self.client.notifications:
            if n.get("method") == "textDocument/publishDiagnostics" and n["params"].get("uri") == uri:
                result = n["params"].get("diagnostics", [])
        return result


def main():
    args = sys.argv[1:]
    project = "petclinic"
    if "--project" in args:
        project = args[args.index("--project") + 1]

    targets = {
        "petclinic": {
            "dir": WORKSPACE / "spring-petclinic",
            "file": "src/main/java/org/springframework/samples/petclinic/owner/PetController.java",
            "hover": "owners.findById",
            "hoverOffset": 8,
            "symbol": "OwnerRepository",
        },
        "multimodule": {
            "dir": ROOT / "demo-multimodule",
            "file": "gateway-web/src/main/java/seal/gateway/web/GatewayHandler.java",
            "hover": "Althgorhm althgorhm",
            "hoverOffset": 2,
            "symbol": "Althgorhm",
        },
        "framework": {
            "dir": WORKSPACE / "spring-framework",
            "file": "spring-core/src/main/java/org/springframework/core/io/FileSystemResource.java",
            "hover": "getFile",
            "hoverOffset": 2,
            "symbol": "ApplicationContext",
        },
    }
    if project not in targets:
        print(f"unknown project {project}")
        sys.exit(2)
    cfg = targets[project]
    if not cfg["dir"].exists():
        print(f"FAIL: {cfg['dir']} missing (clone spring-projects/{project} first)")
        sys.exit(2)

    server_cmd = None
    run_cwd = None
    if "--exe" in args:
        exe = pathlib.Path(args[args.index("--exe") + 1]).resolve()
        if not exe.exists() and exe.with_suffix("").exists():
            exe = exe.with_suffix("")
        server_cmd = [str(exe)]
        run_cwd = str(exe.parent)
    else:
        cp = os.pathsep.join([str(ROOT / "target" / "classes"), str(ROOT / "target" / "libs" / "*")])
        server_cmd = ["java", "-cp", cp, "dev.jdtls.lite.Main"]
        if "--agent" in args:
            server_cmd = ["java", "-agentlib:native-image-agent=config-output-dir=" +
                          str(ROOT / "agent-config"), "-cp", cp, "dev.jdtls.lite.Main"]

    proc = subprocess.Popen(server_cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, cwd=run_cwd)
    threading.Thread(target=lambda: [sys.stderr.write("[server] " + line.decode("utf-8", "replace"))
                                     for line in iter(proc.stderr.readline, b"")], daemon=True).start()

    scenario = Scenario(Client(proc), cfg["dir"])
    ok = True
    try:
        result = scenario.client.wait_response(
            *scenario.client.request("initialize", {
                "processId": os.getpid(),
                "rootUri": scenario.project_uri,
                "capabilities": {},
                "workspaceFolders": [{"uri": scenario.project_uri, "name": scenario.project.name}],
            }), timeout=60)
        print(f"  [PASS] initialize — server={result.get('serverInfo')}")
        scenario.client.notify("initialized", {})
        ok = scenario.run(cfg["file"], cfg["hover"], cfg["symbol"], cfg.get("hoverOffset", 2))
    except Exception as e:  # noqa: BLE001
        print(f"  [FAIL] exception: {e!r}")
        ok = False
    finally:
        try:
            scenario.client.send({"jsonrpc": "2.0", "id": 999, "method": "shutdown", "params": None})
            scenario.client.send({"jsonrpc": "2.0", "method": "exit"})
            proc.wait(10)
        except Exception:  # noqa: BLE001
            proc.kill()

    print(f"E2E {'PASS' if ok else 'FAIL'} ({project}, "
          f"{'native exe' if '--exe' in args else 'JVM'})")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
