#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
  echo "Usage: $0 /path/to/release-bundle (run inside the designated test container)" >&2
  exit 2
fi
if [[ ! -e /.dockerenv && ! -e /run/.containerenv ]]; then
  echo "TLS tests must run inside a container; host Java execution is prohibited." >&2
  exit 2
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_DIR="$(cd "$1" && pwd)"
[[ -f "$RELEASE_DIR/KEmulator.jar" ]] || { echo "Missing release KEmulator.jar" >&2; exit 2; }
for tool in javac java openssl python3; do
  command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 2; }
done
TEST_DIR="$(mktemp -d /tmp/kemu-tls-tests.XXXXXX)"
trap 'rm -rf -- "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/classes" "$TEST_DIR/fixture"

generate_certificate() {
  local name="$1" algorithm="$2"
  case "$algorithm" in
    RSA) openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$TEST_DIR/$name.key.pem" >/dev/null 2>&1 ;;
    EC) openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:prime256v1 -out "$TEST_DIR/$name.key.pem" >/dev/null 2>&1 ;;
    ED25519) openssl genpkey -algorithm ED25519 -out "$TEST_DIR/$name.key.pem" >/dev/null 2>&1 ;;
    ED448) openssl genpkey -algorithm ED448 -out "$TEST_DIR/$name.key.pem" >/dev/null 2>&1 ;;
  esac
  openssl req -new -x509 -key "$TEST_DIR/$name.key.pem" -subj "/CN=$name.invalid" -days 2 -out "$TEST_DIR/$name.cert.pem"
  openssl x509 -in "$TEST_DIR/$name.cert.pem" -outform DER -out "$TEST_DIR/$name.cert.der"
  openssl pkcs8 -topk8 -nocrypt -in "$TEST_DIR/$name.key.pem" -outform DER -out "$TEST_DIR/$name.key.der"
}
generate_certificate server RSA
generate_certificate client-rsa RSA
generate_certificate client-ec EC
generate_certificate client-ed25519 ED25519
generate_certificate client-ed448 ED448
openssl pkcs12 -export -name server -inkey "$TEST_DIR/server.key.pem" -in "$TEST_DIR/server.cert.pem" -passout pass:test-only -out "$TEST_DIR/server.p12"

CLASSPATH="$RELEASE_DIR/KEmulator.jar:$RELEASE_DIR/lib/*"
javac -encoding UTF-8 -cp "$CLASSPATH" -d "$TEST_DIR/classes" "$ROOT_DIR/src/TlsIntegrationTest.java"
# CustomClassAdapter accepts only CLDC-era class versions. This fixture uses
# solely Java 1.4 instructions; modern javac cannot directly target 1.4.
javac --release 8 -d "$TEST_DIR/fixture" "$ROOT_DIR/src/tlsfixture/ApiProbe.java"
python3 - "$TEST_DIR/fixture/tlsfixture/ApiProbe.class" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
bytecode = bytearray(path.read_bytes())
assert bytecode[:4] == b'\xca\xfe\xba\xbe'
bytecode[6:8] = (48).to_bytes(2, 'big')
path.write_bytes(bytecode)
PY
java -Djava.awt.headless=true -Dkemu.data.dir="$TEST_DIR/data" -cp "$TEST_DIR/classes:$CLASSPATH" TlsIntegrationTest "$TEST_DIR" "$TEST_DIR/fixture"
