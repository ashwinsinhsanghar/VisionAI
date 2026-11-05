#!/usr/bin/env bash
# Self-bootstrapping Gradle launcher. Not the official Gradle-generated
# wrapper script (that script ships with a companion gradle-wrapper.jar
# binary that this environment could not produce offline). Instead this
# script reads the Gradle version from gradle/wrapper/gradle-wrapper.properties
# and, if a `gradle` command isn't already on PATH, downloads the official
# Gradle distribution straight from services.gradle.org (same source the
# real wrapper would use) and runs it. Behaves like ./gradlew for any task.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROPS="$DIR/gradle/wrapper/gradle-wrapper.properties"

GRADLE_VERSION="8.7"
if [ -f "$PROPS" ]; then
  V=$(grep -o 'gradle-[0-9.]*-bin' "$PROPS" | head -1 | sed -e 's/gradle-//' -e 's/-bin//')
  if [ -n "$V" ]; then GRADLE_VERSION="$V"; fi
fi

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

CACHE_ROOT="${GRADLE_PORTABLE_HOME:-$HOME/.gradle-portable}"
INSTALL_DIR="$CACHE_ROOT/gradle-$GRADLE_VERSION"

if [ ! -x "$INSTALL_DIR/bin/gradle" ]; then
  mkdir -p "$CACHE_ROOT"
  TMP_ZIP="$(mktemp)"
  curl -fsSL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$TMP_ZIP"
  unzip -q "$TMP_ZIP" -d "$CACHE_ROOT"
  rm -f "$TMP_ZIP"
fi

exec "$INSTALL_DIR/bin/gradle" "$@"
