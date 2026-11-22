"""Generates custom-config/reflect-config.json for the native image.

Two sources:
1. lsp4j/gson classes: deserialized by Gson (needs unsafeAllocated).
2. Eclipse extension-registry executable extensions: every class named in any
   bundle's plugin.xml/fragment.xml (attribute class=, or nested <run class=>)
   must be registered so createExecutableExtension works in a native image.

Run before native-image (invoked from scripts/build_native.py).
"""

import json
import pathlib
import zipfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "custom-config" / "reflect-config.json"

LSP4J_PREFIXES = ("org.eclipse.lsp4j", "org.eclipse.lsp4j.jsonrpc", "com.google.gson")

REFLECT_ENTRY = {
    "allDeclaredConstructors": True,
    "allPublicConstructors": True,
    "allDeclaredMethods": True,
    "allPublicMethods": True,
    "allDeclaredFields": True,
    "allPublicFields": True,
}


def entry(cls, unsafe=False):
    e = dict(REFLECT_ENTRY)
    e["name"] = cls
    e["unsafeAllocated"] = unsafe
    return e


def main():
    entries = {}
    all_class_names = set()
    plugin_xml_sources = []

    libs = sorted((ROOT / "target" / "libs").glob("*.jar"))
    for jar in libs:
        try:
            with zipfile.ZipFile(jar) as zf:
                names = set(zf.namelist())
                for n in names:
                    if n.endswith(".class") and "$" not in n:
                        all_class_names.add(n[:-6].replace("/", "."))
                for xml_name in ("plugin.xml", "fragment.xml"):
                    if xml_name in names:
                        try:
                            plugin_xml_sources.append(zf.read(xml_name))
                        except Exception:
                            pass
        except zipfile.BadZipFile:
            continue

    # 1. Gson-deserialized DTOs
    for jar in libs:
        if not (jar.name.startswith("org.eclipse.lsp4j") or jar.name.startswith("gson")):
            continue
        with zipfile.ZipFile(jar) as zf:
            for n in zf.namelist():
                if not n.endswith(".class") or "$" in n:
                    continue
                cls = n[:-6].replace("/", ".")
                if cls.startswith(LSP4J_PREFIXES):
                    entries.setdefault(cls, entry(cls, unsafe=True))

    # 2. Extension-registry executable extensions from plugin.xml/fragment.xml
    emitted_ext = 0
    for xml_bytes in plugin_xml_sources:
        try:
            root_el = ET.fromstring(xml_bytes)
        except ET.ParseError:
            continue
        for el in root_el.iter():
            cls_name = el.get("class")
            if not cls_name:
                for sub in el.iter("run"):
                    cls_name = sub.get("class")
                    if cls_name:
                        break
            if not cls_name or ":" in cls_name:
                continue
            cls_name = cls_name.strip()
            if cls_name not in all_class_names:
                continue  # class lives in a jar we don't ship
            if cls_name in entries:
                continue
            entries[cls_name] = entry(cls_name)
            emitted_ext += 1
    # lsp4j @JsonDelegate method on our server: discovered reflectively at startup
    server_method = "dev.jdtls.lite.server.JdtlsNativeServer.getJavaExtensions"
    if server_method.replace(".", "/") + ".class" in {c.replace(".", "/") + ".class" for c in all_class_names}:
        # register the declaring class with method-level access
        entries.setdefault("dev.jdtls.lite.server.JdtlsNativeServer", {
            "name": "dev.jdtls.lite.server.JdtlsNativeServer",
            "allDeclaredMethods": True,
            "allPublicMethods": True,
        })
        print(f"server delegate method registered: {server_method}")

    print(f"extension classes registered: {emitted_ext}")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(list(entries.values()), indent=1), encoding="utf-8")
    print(f"wrote {len(entries)} reflect entries to {OUT}")


if __name__ == "__main__":
    main()
