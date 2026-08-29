#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION=8.9
DIST="$ROOT/.gradle-dist/gradle-$VERSION"
if [[ ! -x "$DIST/bin/gradle" ]]; then
  mkdir -p "$ROOT/.gradle-dist"
  ZIP="$ROOT/.gradle-dist/gradle-$VERSION-bin.zip"
  echo "Downloading Gradle $VERSION..."
  curl -L --fail --retry 3 -o "$ZIP" "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  unzip -q -o "$ZIP" -d "$ROOT/.gradle-dist"
fi
exec "$DIST/bin/gradle" "$@"
