#!/usr/bin/env bash
# Placeholder: this project has no native (NDK/C++) code to build.
# Left in place only because build.yml's workflow chmods and may call this
# path; it exits cleanly with no-op so the pipeline doesn't fail on it.
set -e
echo "native/build-native.sh: nothing to build for this project."
exit 0
