"""End-to-end verification of jdtls-native against a real Maven project.

Drives a full LSP session over stdio and asserts a broad feature matrix:
diagnostics, completion over a third-party jar (maven import), hover,
definition, references, documentSymbol, workspaceSymbol, rename, format.

Usage:
  python e2e_demo.py --jvm            # against java -cp classes+libs
  python e2e_demo.py --exe <path>     # against the native executable
"""

import json
import os
import sys

# windows consoles default to a legacy codepage (cp1252); keep unicode safe
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass
import pathlib
import subprocess
import sys
import threading
import time

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
DEMO = ROOT / "demo-project"

APP_TEXT = (DEMO / "src/main/java/demo/App.java").read_text(encoding="utf-8")
GREET_TEXT = (DEMO / "src/main/java/demo/Greet.java").read_text(encoding="utf-8")


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
        return mid, ev, holder

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

    def wait_response(self, ev, holder, timeout=30):
        if not ev.wait(timeout):
            raise TimeoutError(f"no response within {timeout}s")
        msg = holder.get("msg", {})
        if "error" in msg:
            raise RuntimeError(f"server error: {msg['error']}")
        return msg.get("result")

    def last_diagnostics(self, uri):
        result = None
        for n in self.notifications:
            if n.get("method") == "textDocument/publishDiagnostics" and n["params"].get("uri") == uri:
                result = n["params"].get("diagnostics", [])
        return result


class Scenario:
    def __init__(self, client):
        self.client = client
        self.app_uri = (DEMO / "src/main/java/demo/App.java").as_uri()
        self.greet_uri = (DEMO / "src/main/java/demo/Greet.java").as_uri()
        self.ok = True

    def check(self, name, condition, detail=""):
        status = "PASS" if condition else "FAIL"
        print(f"  [{status}] {name}" + (f" — {detail}" if detail else ""))
        if not condition:
            self.ok = False
        return condition

    @staticmethod
    def pos(text, needle, offset_in_needle=0):
        lines = text.splitlines()
        remaining = text
        consumed = 0
        for i, line in enumerate(lines):
            idx = line.find(needle)
            if idx >= 0:
                return {"line": i, "character": idx + offset_in_needle}
        raise ValueError(f"needle {needle!r} not found")

    def run(self):
        c = self.client

        # 3. completion over the maven-imported third-party jar
        items = self.poll_completion()
        self.check("completion via maven jar (StringUtils members)",
                   any("upperCase" in it.get("label", "") for it in items),
                   f"{len(items)} items")

        # 4. hover on g.greet()
        _mid, ev, holder = c.request("textDocument/hover", {
            "textDocument": {"uri": self.app_uri}, "position": self.pos(APP_TEXT, "g.greet()", 3)})
        hover = self.client.wait_response(ev, holder)
        value = (hover or {}).get("contents", {}).get("value", "") if isinstance(hover, dict) else ""
        self.check("hover on greet() shows signature", "greet" in value, repr(value[:80]))

        # 5. definition of greet() -> Greet.java
        _mid, ev, holder = c.request("textDocument/definition", {
            "textDocument": {"uri": self.app_uri}, "position": self.pos(APP_TEXT, "g.greet()", 3)})
        defs = self.client.wait_response(ev, holder)
        def_uris = [d.get("uri", "") for d in (defs or [])]
        self.check("definition -> Greet.java", any(u.endswith("Greet.java") for u in def_uris), str(def_uris))

        # 5b. definition into the third-party jar (decompiler / sources)
        _mid, ev, holder = c.request("textDocument/definition", {
            "textDocument": {"uri": self.app_uri},
            "position": self.pos(APP_TEXT, "StringUtils.isNotEmpty", 14)})
        jardefs = self.client.wait_response(ev, holder)
        jar_uris = [d.get("uri", "") for d in (jardefs or [])]
        self.check("definition into commons-lang3 jar (decompiled)",
                   len(jar_uris) >= 1 and jar_uris[0].endswith(".java"),
                   str(jar_uris)[:120])

        # 5c. open the decompiled third-party file -> documentSymbol over it
        if jar_uris:
            dec_uri = jar_uris[0]
            import urllib.parse
            import urllib.request
            dec_path = pathlib.Path(urllib.request.url2pathname(
                urllib.parse.urlparse(dec_uri).path))
            dec_text = dec_path.read_text(encoding="utf-8")
            self.client.notify("textDocument/didOpen", {"textDocument": {
                "uri": dec_uri, "languageId": "java", "version": 1, "text": dec_text}})
            _mid, ev, holder = c.request("textDocument/documentSymbol", {
                "textDocument": {"uri": dec_uri}})
            dsyms = self.client.wait_response(ev, holder)
            names = []

            def walk(syms):
                for s in syms or []:
                    names.append(s.get("name", ""))
                    walk(s.get("children"))

            # hover inside the decompiled third-party source
            hover_at = None
            dlines = dec_text.splitlines()
            for i, ln in enumerate(dlines):
                if 'public static boolean isNotEmpty' in ln:
                    hover_at = {'line': i, 'character': ln.index('isNotEmpty') + 2}
                    break
            if hover_at:
                _mid, evh, hh = c.request('textDocument/hover', {
                    'textDocument': {'uri': dec_uri}, 'position': hover_at})
                hv = self.client.wait_response(evh, hh)
                hvv = (hv or {}).get('contents', {}).get('value', '') if isinstance(hv, dict) else ''
                self.check('hover on decompiled isNotEmpty',
                           'isNotEmpty' in hvv and 'CharSequence' in hvv, repr(hvv[:90]))

            walk(dsyms)
            self.check("documentSymbol on decompiled StringUtils",
                       any("StringUtils" in n for n in names)
                       and any("isNotEmpty" in n for n in names),
                       f"{len(names)} symbol(s); top={names[:3]}")
            self.client.notify("textDocument/didClose", {"textDocument": {"uri": dec_uri}})

        # 6. references on greet() declaration -> ≥2 (decl + App use)
        _mid, ev, holder = c.request("textDocument/references", {
            "textDocument": {"uri": self.greet_uri},
            "position": self.pos(GREET_TEXT, "public String greet()", 16),
            "context": {"includeDeclaration": True}})
        refs = self.client.wait_response(ev, holder)
        self.check("references of greet() >= 2", len(refs or []) >= 2, f"{len(refs or [])} found")

        # 7. documentSymbol on Greet.java
        _mid, ev, holder = c.request("textDocument/documentSymbol", {
            "textDocument": {"uri": self.greet_uri}})
        symbols = self.client.wait_response(ev, holder)
        names = []

        def walk(syms):
            for s in syms or []:
                names.append(s.get("name", ""))
                walk(s.get("children"))

        walk(symbols)
        self.check("documentSymbol Greet + greet()+getCount()",
                   "Greet" in names and any("greet" in n for n in names) and any("getCount" in n for n in names),
                   str(names))

        # 8. workspaceSymbol "Greet"
        _mid, ev, holder = c.request("workspace/symbol", {"query": "Greet"})
        wsyms = self.client.wait_response(ev, holder)
        wsnames = [s.get("name", "") for s in (wsyms or [])]
        self.check("workspaceSymbol finds demo.Greet*", any(n.startswith("demo.Greet") for n in wsnames), str(wsnames[:4]))

        # 9. rename greet -> sayHello
        _mid, ev, holder = c.request("textDocument/rename", {
            "textDocument": {"uri": self.app_uri}, "position": self.pos(APP_TEXT, "g.greet()", 3),
            "newName": "sayHello"})
        wedit = self.client.wait_response(ev, holder)
        changes = (wedit or {}).get("changes", {})
        n_edits = sum(len(v) for v in changes.values())
        self.check("rename covers call sites across files",
                   len(changes) >= 2 and n_edits >= 3,
                   f"files={len(changes)}, edits={n_edits}")

        # 11b. incremental didChange (ranged sync): append a marker method to Greet.java
        glines2 = GREET_TEXT.splitlines()
        ins_line = len(glines2) - 1
        marker_method = "    public int e2eMarker() { return 42; }\n"
        self.client.notify("textDocument/didChange", {"textDocument": {"uri": self.greet_uri, "version": 99},
            "contentChanges": [{"range": {"start": {"line": ins_line, "character": 0},
                                          "end": {"line": ins_line, "character": 0}},
                                "rangeLength": 1, "text": marker_method}]})
        time.sleep(0.5)
        _mid, ev, holder = c.request("textDocument/documentSymbol", {
            "textDocument": {"uri": self.greet_uri}})
        dsyms = self.client.wait_response(ev, holder)
        names2 = []

        def walk2(syms):
            for s in syms or []:
                names2.append(s.get("name", ""))
                walk2(s.get("children") or [])

        walk2(dsyms)
        self.check("incremental didChange visible in model (e2eMarker)",
                   any("e2eMarker" in n for n in names2), str(names2[:5]))

        # restore original content via full sync
        self.client.notify("textDocument/didChange", {"textDocument": {"uri": self.greet_uri, "version": 100},
            "contentChanges": [{"text": GREET_TEXT}]})


        # 10. format Greet.java (mess it up first)
        messy = GREET_TEXT.replace("public String greet() {", "public String greet()   {")
        messy = messy.replace("count++;", "count++;   ")
        c.notify("textDocument/didChange", {"textDocument": {"uri": self.greet_uri, "version": 2},
                                            "contentChanges": [{"text": messy}]})
        time.sleep(0.4)
        _mid, ev, holder = c.request("textDocument/formatting", {
            "textDocument": {"uri": self.greet_uri}, "options": {"tabSize": 4, "insertSpaces": True}})
        edits = self.client.wait_response(ev, holder)
        self.check("formatting produces edits", bool(edits), f"{len(edits or [])} edit(s)")

        # 11. diagnostics clean for App.java (StringUtils resolved via maven classpath)
        time.sleep(1.0)
        diags = c.last_diagnostics(self.app_uri)
        errors = [d for d in (diags or []) if d.get("severity") == 1]
        self.check("App.java diagnostics clean (no unresolved imports)",
                   diags is not None and len(errors) == 0,
                   f"{len(diags or [])} diagnostic(s), {len(errors)} error(s)")

        return self.ok

    def poll_completion(self, timeout=120):
        c = self.client
        deadline = time.time() + timeout
        attempts = 0
        while time.time() < deadline:
            attempts += 1
            try:
                _mid, ev, holder = c.request("textDocument/completion", {
                    "textDocument": {"uri": self.app_uri},
                    "position": self.pos(APP_TEXT, "if (StringUtils.", 16)})
                items = self.client.wait_response(ev, holder, timeout=15)
            except Exception:  # noqa: BLE001
                items = None
            if items:
                print(f"  [info] completion ready after {attempts} poll(s)")
                return items
            time.sleep(1.0)
        return []


def main():
    args = sys.argv[1:]
    server_cmd = None
    run_cwd = None
    if "--exe" in args:
        exe = pathlib.Path(args[args.index("--exe") + 1]).resolve()
        if not exe.exists() and exe.with_suffix("").exists():
            exe = exe.with_suffix("")  # linux binaries have no extension
        server_cmd = [str(exe)]
        run_cwd = str(exe.parent)
    else:
        cp = os.pathsep.join([str(ROOT / "target" / "classes"), str(ROOT / "target" / "libs" / "*")])
        server_cmd = ["java", "-cp", cp, "dev.jdtls.lite.Main"]
        if "--verbose" in args:
            server_cmd = ["java", "-Xlog:class+load=info:stderr", "-cp", cp, "dev.jdtls.lite.Main"]

    proc = subprocess.Popen(server_cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, cwd=run_cwd)
    threading.Thread(target=lambda: [sys.stderr.write("[server] " + line.decode("utf-8", "replace"))
                                     for line in iter(proc.stderr.readline, b"")], daemon=True).start()

    client = Client(proc)
    scenario = Scenario(client)
    ok = True
    try:
        _mid, ev, holder = client.request("initialize", {
            "processId": os.getpid(),
            "rootUri": DEMO.as_uri(),
            "capabilities": {},
            "workspaceFolders": [{"uri": DEMO.as_uri(), "name": DEMO.name}],
        })
        result = client.wait_response(ev, holder, timeout=60)
        print(f"  [PASS] initialize — server={result.get('serverInfo')}")
        client.notify("initialized", {})

        # open both documents (engine replays once ready)
        client.notify("textDocument/didOpen", {"textDocument": {
            "uri": scenario.app_uri, "languageId": "java", "version": 1, "text": APP_TEXT}})
        client.notify("textDocument/didOpen", {"textDocument": {
            "uri": scenario.greet_uri, "languageId": "java", "version": 1, "text": GREET_TEXT}})

        ok = scenario.run()
    except Exception as e:  # noqa: BLE001
        print(f"  [FAIL] exception: {e!r}")
        ok = False
    finally:
        try:
            client.send({"jsonrpc": "2.0", "id": 999, "method": "shutdown", "params": None})
            client.send({"jsonrpc": "2.0", "method": "exit"})
            proc.wait(10)
        except Exception:  # noqa: BLE001
            proc.kill()

    print(f"E2E {'PASS' if ok else 'FAIL'} ({'native exe' if '--exe' in args else 'JVM'})")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
