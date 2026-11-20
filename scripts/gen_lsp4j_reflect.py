"""Generates custom-config/reflect-config.json covering every org.eclipse.lsp4j class.

lsp4j DTOs are deserialized by Gson, which on a native image uses
Unsafe.allocateInstance when a type has no usable constructor. Without
"unsafeAllocated" registration each request type would fail at runtime.
Run before native-image (invoked from scripts/build-native.bat).
"""

import json
import pathlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "custom-config" / "reflect-config.json"

PREFIXES = ("org/eclipse/lsp4j", "org/eclipse/lsp4j/jsonrpc", "com/google/gson")


def main():
    entries = {}
    for jar in sorted((ROOT / "target" / "libs").glob("*.jar")):
        name = jar.name
        if not (name.startswith("org.eclipse.lsp4j") or name.startswith("gson")):
            continue
        with zipfile.ZipFile(jar) as zf:
            for n in zf.namelist():
                if not n.endswith(".class") or "$" in n:
                    continue
                cls = n[:-len(".class")].replace("/", ".")
                if not cls.startswith(tuple(p.replace("/", ".") for p in PREFIXES)):
                    continue
                entries.setdefault(cls, {
                    "name": cls,
                    "allDeclaredConstructors": True,
                    "allPublicConstructors": True,
                    "allDeclaredMethods": True,
                    "allPublicMethods": True,
                    "allDeclaredFields": True,
                    "allPublicFields": True,
                    "unsafeAllocated": True,
                })
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(list(entries.values()), indent=1), encoding="utf-8")
    print(f"wrote {len(entries)} reflect entries to {OUT}")


if __name__ == "__main__":
    main()
