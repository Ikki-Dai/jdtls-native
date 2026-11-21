"""Smoke test client for jdtls-native.

Drives a full LSP session over stdio against target/jdtls-native.jar:
initialize -> initialized -> didOpen (bad java file) -> expect publishDiagnostics
with >= 2 errors -> shutdown -> exit.

Usage: python smoke_client.py [--jar PATH]
"""

import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import threading
import time

HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_JAR = HERE.parent / "target" / "jdtls-native.jar"

BAD_SOURCE = """package demo;

public class Bad {
    int x = "not an int";
    Missing missingMethod() { return null; }
    public void todo() {}
}
"""

GOOD_SOURCE = """package demo;

import java.util.List;

public class Good {
    List<String> names = new java.util.ArrayL;

    public String greet(String who) {
        return "Hi " + who + " (called " + greetCount() + ")";
    }

    public int greetCount() {
        return 1;
    }
}
"""


class Pending:
    def __init__(self):
        self.response = None
        self.event = threading.Event()


class Client:
    def __init__(self, proc):
        self.proc = proc
        self.pending = {}
        self.notifications = []
        self.all_messages = []
        self.lock = threading.Lock()
        self.next_id = 1
        self.reader = threading.Thread(target=self._read_loop, daemon=True)
        self.reader.start()

    def send(self, obj):
        body = json.dumps(obj).encode("utf-8")
        header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
        self.proc.stdin.write(header + body)
        self.proc.stdin.flush()

    def request(self, method, params):
        with self.lock:
            msg_id = self.next_id
            self.next_id += 1
        p = Pending()
        with self.lock:
            self.pending[msg_id] = p
        self.send({"jsonrpc": "2.0", "id": msg_id, "method": method, "params": params})
        return msg_id, p

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})

    def _read_loop(self):
        try:
            while True:
                # read the full header block, then the body
                headers = {}
                while True:
                    line = self.proc.stdout.readline()
                    if not line:
                        return
                    if line in (b"\r\n", b"\n"):
                        break
                    key, _, value = line.decode("ascii").partition(":")
                    headers[key.strip().lower()] = value.strip()
                if "content-length" not in headers:
                    continue
                length = int(headers["content-length"])
                body = self.proc.stdout.read(length)
                try:
                    msg = json.loads(body.decode("utf-8"))
                except Exception as e:  # noqa: BLE001
                    print(f"[reader JSON FAIL: {e!r}]\n  body={body!r}", file=sys.stderr, flush=True)
                    raise
                self._dispatch(msg)
        except Exception as e:  # noqa: BLE001
            print(f"[reader stopped: {e!r}]", file=sys.stderr)

    def _dispatch(self, msg):
        self.all_messages.append(msg)
        if "id" in msg and ("result" in msg or "error" in msg):
            with self.lock:
                p = self.pending.pop(msg["id"], None)
            if p:
                p.response = msg
                p.event.set()
        elif "method" in msg:
            self.notifications.append(msg)


def wait_for_response(client, pending, timeout=20):
    if not pending.event.wait(timeout):
        raise TimeoutError(f"no response within {timeout}s")
    resp = pending.response
    if "error" in resp:
        raise RuntimeError(f"server error: {resp['error']}")
    return resp["result"]


def wait_for_diagnostics(client, uri, min_errors, timeout=25):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for n in client.notifications:
            if n.get("method") == "textDocument/publishDiagnostics":
                params = n.get("params", {})
                if params.get("uri") == uri or os.path.normpath(params.get("uri", "")) == os.path.normpath(uri):
                    diags = params.get("diagnostics", [])
                    errors = [d for d in diags if d.get("severity") == 1]
                    if len(errors) >= min_errors:
                        return diags
        time.sleep(0.1)
    return None


def main():
    jar = DEFAULT_JAR
    args = sys.argv[1:]
    if "--jar" in args:
        jar = pathlib.Path(args[args.index("--jar") + 1])
    if not jar.exists():
        print(f"FAIL: server jar not found: {jar} (run mvn package first)")
        sys.exit(2)

    project, bad_file, good_file = make_project()
    print(f"project: {project}")
    java_exe = os.environ.get("JDTLS_NATIVE_JAVA", "java")
    server_cmd = [java_exe]
    if "--agent" in args:
        agent_dir = HERE.parent / "agent-config"
        server_cmd += [f"-agentlib:native-image-agent=config-output-dir={agent_dir}"]
    # real classpath launch (platform jars must stay separate for manifest/plugin.xml access)
    cp = os.pathsep.join([str(HERE.parent / "target" / "classes"),
                          str(HERE.parent / "target" / "libs" / "*")])
    server_cmd += ["-cp", cp, "dev.jdtls.lite.Main"]
    run_cwd = None
    if "--exe" in args:
        exe = pathlib.Path(args[args.index("--exe") + 1]).resolve()
        if not exe.exists() and exe.with_suffix("").exists():
            exe = exe.with_suffix("")  # linux binaries have no extension
        server_cmd = [str(exe)]
        run_cwd = str(exe.parent)
        if "--agent" in args:
            agent_dir = HERE.parent / "agent-config-exe"
            server_cmd = [str(exe), f"-agentlib:native-image-agent=config-output-dir={agent_dir}"]
    print(f"server cmd: {server_cmd}")
    proc = subprocess.Popen(
        server_cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=run_cwd,
    )

    def pump_stderr():
        for line in iter(proc.stderr.readline, b""):
            sys.stderr.write("[server] " + line.decode("utf-8", "replace"))

    threading.Thread(target=pump_stderr, daemon=True).start()

    client = Client(proc)
    bad_uri = bad_file.as_uri()
    ok = True
    try:
        # initialize
        init_id, init_pending = client.request(
            "initialize",
            {
                "processId": os.getpid(),
                "rootUri": project.as_uri(),
                "capabilities": {},
                "workspaceFolders": [{"uri": project.as_uri(), "name": project.name}],
            },
        )
        init_result = wait_for_response(client, init_pending)
        caps = init_result.get("capabilities", {})
        print(f"initialize OK; server={init_result.get('serverInfo')}; "
              f"sync={caps.get('textDocumentSync')}, hover={caps.get('hoverProvider')}")
        client.notify("initialized", {})

        # didOpen with a broken file
        client.notify(
            "textDocument/didOpen",
            {
                "textDocument": {
                    "uri": bad_uri,
                    "languageId": "java",
                    "version": 1,
                    "text": BAD_SOURCE,
                }
            },
        )
        diags = wait_for_diagnostics(client, bad_uri, min_errors=2)
        if diags is None:
            print("FAIL: no publishDiagnostics with >=2 errors received")
            ok = False
        else:
            print(f"diagnostics OK: {len(diags)} item(s)")
            for d in diags:
                r = d["range"]
                print(f"  [{d['severity']}] {r['start']['line']}:{r['start']['character']} "
                      f"{d.get('message', '')[:100]}")

        # completion on Good.java at the end of "ArrayL"
        client.notify(
            "textDocument/didOpen",
            {
                "textDocument": {
                    "uri": good_file.as_uri(),
                    "languageId": "java",
                    "version": 1,
                    "text": GOOD_SOURCE,
                }
            },
        )
        lines = GOOD_SOURCE.splitlines()
        comp_line = next(i for i, ln in enumerate(lines) if "ArrayL" in ln)
        comp_char = lines[comp_line].index("ArrayL") + len("ArrayL")
        got_items = None
        deadline = time.time() + 40  # model engine boots asynchronously
        attempts = 0
        while time.time() < deadline:
            attempts += 1
            cid, c_pending = client.request(
                "textDocument/completion",
                {"textDocument": {"uri": good_file.as_uri()},
                 "position": {"line": comp_line, "character": comp_char}},
            )
            try:
                res = wait_for_response(client, c_pending, timeout=10)
            except Exception as e:  # noqa: BLE001
                res = None
            items = None
            if isinstance(res, list):
                items = res
            elif isinstance(res, dict) and "items" in res:
                items = res["items"]
            if items:
                got_items = items
                break
            time.sleep(1.0)
        if got_items:
            labels = [str(it.get("label", "")) for it in got_items]
            print(f"completion OK: {len(got_items)} item(s) after {attempts} attempt(s): {labels[:6]}")
            if not any("ArrayList" in l for l in labels):
                print("FAIL: expected an ArrayList proposal")
                ok = False
        else:
            print("FAIL: no completion items received")
            ok = False

        # feature matrix on Good.java (gives the agent full coverage)
        greet_pos = None
        glines = GOOD_SOURCE.splitlines()
        for i, ln in enumerate(glines):
            if "public String greet(" in ln:
                greet_pos = {"line": i, "character": ln.index("greet") + 2}
        # definition into the JDK via jrt (sources zip or decompiler)
        list_pos = None
        for i, ln in enumerate(glines):
            if "import java.util.List;" in ln:
                list_pos = {"line": i, "character": ln.index("List") + 2}
        if list_pos:
            _hi, lp = client.request("textDocument/definition", {
                "textDocument": {"uri": good_file.as_uri()}, "position": list_pos})
            jdef = wait_for_response(client, lp)
            print(f"  jdk definition: {str(jdef)[:120]}")
            if not jdef:
                print("FAIL: jdk definition empty")
                ok = False

        if greet_pos:
            _hi, hp = client.request("textDocument/hover", {
                "textDocument": {"uri": good_file.as_uri()}, "position": greet_pos})
            hover = wait_for_response(client, hp)
            hv = (hover or {}).get("contents", {}).get("value", "") if isinstance(hover, dict) else ""
            print(f"  hover: {hv[:60]!r}")
            if "greet" not in hv:
                print("FAIL: hover empty")
                ok = False
            _i, pend2 = client.request("textDocument/definition", {
                "textDocument": {"uri": good_file.as_uri()}, "position": greet_pos})
            defs = wait_for_response(client, pend2)
            print(f"  definition: {defs}")
            _i, pend3 = client.request("textDocument/references", {
                "textDocument": {"uri": good_file.as_uri()}, "position": greet_pos,
                "context": {"includeDeclaration": True}})
            refs = wait_for_response(client, pend3)
            print(f"  references: {len(refs or [])} found")
            if not refs:
                print("FAIL: references empty")
                ok = False
            _i, pend4 = client.request("textDocument/rename", {
                "textDocument": {"uri": good_file.as_uri()}, "position": greet_pos,
                "newName": "salute"})
            wedit = wait_for_response(client, pend4)
            nchanges = len((wedit or {}).get("changes", {}))
            print(f"  rename: {nchanges} file(s)")
            if nchanges < 1:
                print("FAIL: rename empty")
                ok = False
            _i, pend5 = client.request("textDocument/formatting", {
                "textDocument": {"uri": good_file.as_uri()}, "options": {"tabSize": 4, "insertSpaces": True}})
            fmt = wait_for_response(client, pend5)
            print(f"  formatting: {len(fmt or [])} edit(s)")
            _i, pend6 = client.request("workspace/symbol", {"query": "Good"})
            wsym = wait_for_response(client, pend6)
            print(f"  workspaceSymbol: {len(wsym or [])} found")
            if not wsym:
                print("FAIL: workspaceSymbol empty")
                ok = False

        # shutdown / exit
        sid, s_pending = client.request("shutdown", None)
        wait_for_response(client, s_pending)
        client.notify("exit", None)
        code = proc.wait(timeout=10)
        print(f"exit OK (code={code})")
    except Exception as e:  # noqa: BLE001
        print(f"FAIL: {e!r}")
        ok = False
        proc.kill()
    finally:
        shutil.rmtree(project, ignore_errors=True)

    print("SMOKE PASS" if ok else "SMOKE FAIL")
    sys.exit(0 if ok else 1)


def make_project():
    project = pathlib.Path(tempfile.mkdtemp(prefix="jdtls-native-smoke-"))
    src = project / "src" / "main" / "java" / "demo"
    src.mkdir(parents=True)
    bad = src / "Bad.java"
    bad.write_text(BAD_SOURCE, encoding="utf-8")
    good = src / "Good.java"
    good.write_text(GOOD_SOURCE, encoding="utf-8")
    return project, bad, good


if __name__ == "__main__":
    main()
