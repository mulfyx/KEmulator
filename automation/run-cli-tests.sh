#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"

usage() {
  cat <<'EOF'
Usage:
  ./automation/run-cli-tests.sh [--release-dir DIR] [--keep] [PYTEST_ARGS...]

Single entrypoint for the CLI test suite:
  1. builds the release bundle ONCE (reused when DIR already has KEmulator.jar);
  2. prepares the fixture pack ONCE, outside the bundle;
  3. runs pytest over automation/tests.

Without PYTEST_ARGS the full suite runs with the command-coverage gate on.
Any PYTEST_ARGS (e.g. -k resize, -x) disable the coverage gate so partial
runs stay usable.

Options:
  --release-dir DIR  reuse/build the bundle here instead of a temp dir
  --keep             keep the temp work dir for debugging

Requires: java/javac/jar, xvfb-run, python3 with pytest.
EOF
}

RELEASE_DIR=""
KEEP=0
PYTEST_ARGS=()
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --release-dir)
      [[ "$#" -ge 2 ]] || { usage >&2; exit 1; }
      RELEASE_DIR="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    *) PYTEST_ARGS+=("$1"); shift ;;
  esac
done

if ! "$PYTHON_BIN" -c 'import pytest' >/dev/null 2>&1; then
  echo "pytest is required: $PYTHON_BIN -m pip install --user pytest" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d /tmp/kemu-cli-tests.XXXXXX)"
cleanup() {
  if [[ "$KEEP" -eq 0 ]]; then
    rm -rf -- "$WORK_DIR"
  else
    echo "Kept work dir: $WORK_DIR"
  fi
}
trap cleanup EXIT

if [[ -z "$RELEASE_DIR" ]]; then
  RELEASE_DIR="$WORK_DIR/release"
fi
case "$RELEASE_DIR" in
  /*) ;;
  *) RELEASE_DIR="$PWD/$RELEASE_DIR" ;;
esac

if [[ ! -f "$RELEASE_DIR/KEmulator.jar" ]]; then
  echo "[build] $RELEASE_DIR"
  "$ROOT_DIR/build-release.sh" "$RELEASE_DIR"
else
  echo "[build] reusing $RELEASE_DIR"
fi

echo "[fixtures] $WORK_DIR/fixtures"
"$ROOT_DIR/automation/test-fixtures/prepare-cli-fixtures.sh" \
  "$RELEASE_DIR/KEmulator.jar" "$WORK_DIR/fixtures"

export KEMU_RELEASE_DIR="$RELEASE_DIR"
export KEMU_FIXTURES_ENV="$WORK_DIR/fixtures/fixtures.env"
export PYTHONDONTWRITEBYTECODE=1

if [[ "${#PYTEST_ARGS[@]}" -eq 0 ]]; then
  export KEMU_COVERAGE_CHECK=1
else
  export KEMU_COVERAGE_CHECK=0
fi

echo "[pytest] automation/tests"
"$PYTHON_BIN" -m pytest "$ROOT_DIR/automation/tests" \
  -p no:cacheprovider -q --durations=10 \
  ${PYTEST_ARGS[@]+"${PYTEST_ARGS[@]}"}
