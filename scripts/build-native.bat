@echo off
rem Thin wrapper: requires GRAALVM_HOME (GraalVM with native-image) and MSVC env
rem (run from an x64 Native Tools prompt, or set VCVARS to vcvars64.bat).
setlocal
python "%~dp0build_native.py" %*
endlocal
