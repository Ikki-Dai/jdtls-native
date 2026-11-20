#!/usr/bin/env bash
# Thin wrapper: requires GRAALVM_HOME (GraalVM with native-image; gcc on Linux).
set -e
python "$(dirname "$0")/build_native.py" "$@"
