"""Cross-platform native-image build for jdtls-native.

Replaces the per-machine batch script. Environment:
  GRAALVM_HOME  GraalVM distribution containing bin/native-image (required)
  JAVA_HOME     JDK used for lib/jrt-fs.jar (defaults to GRAALVM_HOME)
  VCVARS        (Windows, optional) path to vcvars64.bat; not needed when the
                MSVC environment is already active (e.g. ilammy/msvc-dev-cmd)

Usage: python scripts/build_native.py [--out DIR]
"""

import argparse
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
GRAAL = pathlib.Path(os.environ.get("GRAALVM_HOME", ""))
JAVA = pathlib.Path(os.environ.get("JAVA_HOME", GRAAL))


def fail(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def find_native_image():
    if not GRAAL or not GRAAL.is_dir():
        fail("GRAALVM_HOME is not set or invalid (needs a GraalVM with native-image)")
    names = ["native-image.cmd", "native-image.bat", "native-image"] if os.name == "nt" else ["native-image"]
    for n in names:
        p = GRAAL / "bin" / n
        if p.is_file():
            return p
    fail(f"native-image not found under {GRAAL}/bin")


def ensure_jrt_fs_stub():
    """The jrt-fs stub provider must be on the build classpath so
    ServiceLoaderFeature registers it in the image (native runs have no jrt NIO FS)."""
    target_libs = ROOT / "target" / "libs"
    target_libs.mkdir(parents=True, exist_ok=True)
    dest = target_libs / "jrt-fs.jar"
    if dest.is_file():
        return
    candidates = [JAVA / "lib" / "jrt-fs.jar", GRAAL / "lib" / "jrt-fs.jar"]
    for c in candidates:
        if c.is_file():
            shutil.copyfile(c, dest)
            print(f"copied {c} -> {dest}")
            return
    fail("jrt-fs.jar not found (set JAVA_HOME to a JDK)")


def run_native_image(out_dir):
    cp = os.pathsep.join([str(ROOT / "target" / "classes"), str(ROOT / "target" / "libs" / "*")])
    exe = find_native_image()
    config_dirs = ",".join([str(ROOT / "agent-config"), str(ROOT / "custom-config")])
    args = [
        "-cp", cp,
        f"-H:ConfigurationFileDirectories={config_dirs}",
        "--no-fallback",
        "-H:+AllowJRTFileSystem",
        "-Ob",
        "-H:Name=jdtls-native",
        f"-H:Path={out_dir}",
        "dev.jdtls.lite.Main",
    ]
    env = dict(os.environ)
    exe_str = f'"{exe}"'
    if os.name == "nt" and env.get("VCVARS"):
        # run through cmd so vcvars64 sets up the MSVC environment first
        inner = exe_str + " " + " ".join(f'"{a}"' if " " in a else a for a in args)
        cmd = f'call "{env["VCVARS"]}" >nul && {inner}'
        return subprocess.run(cmd, shell=True, cwd=ROOT, env=env).returncode
    if os.name == "nt":
        args = [exe_str] + args
        return subprocess.run(" ".join(args), shell=True, cwd=ROOT, env=env).returncode
    return subprocess.run([str(exe)] + args, cwd=ROOT, env=env).returncode


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(ROOT / "target" / "native"))
    opts = parser.parse_args()
    out_dir = pathlib.Path(opts.out).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    if not (ROOT / "target" / "classes").is_dir():
        fail("target/classes missing — run 'mvn package -DskipTests' first")
    if not (ROOT / "target" / "libs").is_dir() or not any((ROOT / "target" / "libs").glob("*.jar")):
        fail("target/libs missing — run 'mvn package -DskipTests' first (copy-dependencies)")

    ensure_jrt_fs_stub()

    # regenerate the lsp4j reflection config from the actual dependency jars
    gen = ROOT / "scripts" / "gen_lsp4j_reflect.py"
    subprocess.run([sys.executable, str(gen)], check=True)

    print(f"building native image with {find_native_image()} -> {out_dir}")
    code = run_native_image(out_dir)
    if code != 0:
        fail(f"native-image exited with {code}")

    # distro layout: exe + libs/ (platform jars stay on disk for manifest/plugin.xml access)
    libs_out = out_dir / "libs"
    libs_out.mkdir(exist_ok=True)
    for jar in (ROOT / "target" / "libs").glob("*.jar"):
        shutil.copyfile(jar, libs_out / jar.name)
    print('NATIVE BUILD OK ->', out_dir / ('jdtls-native' + ('.exe' if os.name == 'nt' else '')))


if __name__ == "__main__":
    main()
