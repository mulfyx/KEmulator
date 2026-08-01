#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE_DIR="$ROOT_DIR/automation/test-fixtures/src"
MANIFEST_DIR="$ROOT_DIR/automation/test-fixtures"

usage() {
  cat <<'EOF'
Usage:
  ./automation/test-fixtures/build-fixtures.sh RUNTIME_CLASSPATH OUTPUT_DIR

Compiles the shared fixture sources once and packages every fixture JAR
(one per *.mf manifest in automation/test-fixtures) into OUTPUT_DIR:
  command-fixture.jar auto-snapshot-fixture.jar
  mutable-title-fixture.jar mega-cli-fixture.jar
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" || "$#" -ne 2 ]]; then
  usage >&2
  [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]] && exit 0
  exit 1
fi

resolve_path() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s\n' "$PWD/$1" ;;
  esac
}

RUNTIME_CLASSPATH="$(resolve_path "$1")"
OUTPUT_DIR="$(resolve_path "$2")"
CLASSES_DIR="$OUTPUT_DIR/classes"

for tool in javac jar; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool not found" >&2
    exit 1
  fi
done

if [[ ! -e "$RUNTIME_CLASSPATH" ]]; then
  echo "Runtime classpath not found: $RUNTIME_CLASSPATH" >&2
  exit 1
fi

rm -rf -- "$CLASSES_DIR"
mkdir -p -- "$CLASSES_DIR"

SOURCES_FILE="$OUTPUT_DIR/sources.txt"
find -- "$SOURCE_DIR" -type f -name '*.java' | sort > "$SOURCES_FILE"
if ! [[ -s "$SOURCES_FILE" ]]; then
  echo "No fixture sources found under $SOURCE_DIR" >&2
  exit 1
fi

javac \
  -encoding UTF-8 \
  -source 1.4 \
  -target 1.4 \
  -cp "$RUNTIME_CLASSPATH" \
  -d "$CLASSES_DIR" \
  @"$SOURCES_FILE"

built=0
for manifest in "$MANIFEST_DIR"/*.mf; do
  name="$(basename "$manifest" .mf)"
  jar cfm "$OUTPUT_DIR/$name.jar" "$manifest" -C "$CLASSES_DIR" .
  built=$((built + 1))
done

if [[ "$built" -eq 0 ]]; then
  echo "No *.mf manifests found under $MANIFEST_DIR" >&2
  exit 1
fi

echo "Built $built fixture jars in $OUTPUT_DIR (classpath: $RUNTIME_CLASSPATH)"
