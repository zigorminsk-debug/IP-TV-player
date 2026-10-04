#!/usr/bin/env bash
# Generates a fresh release signing keystore (PKCS#12, RSA 4096, ~100 years).
# Usage:  ./scripts/generate_kestore.sh [output-dir]
#
# WARNING: replacing the keystore changes the app signature -> users will have
# to uninstall the old app. See docs/06-SIGNING-KEYS.md before doing this.
set -euo pipefail

OUT_DIR="${1:-keystore}"
mkdir -p "$OUT_DIR"

PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 28)"

openssl req -x509 -newkey rsa:4096 -keyout /tmp/iptv_key.pem -out /tmp/iptv_cert.pem \
  -days 36500 -nodes \
  -subj "/CN=IP-TV Player Release/O=IP-TV Player Project/C=XX"

# "-legacy" keeps 3DES/RC2 algorithms readable by every JDK and apksigner.
openssl pkcs12 -export -legacy \
  -in /tmp/iptv_cert.pem -inkey /tmp/iptv_key.pem \
  -out "$OUT_DIR/release-signing.p12" \
  -name iptvplayer -passout pass:"$PASS"

rm -f /tmp/iptv_key.pem /tmp/iptv_cert.pem

cat > "$OUT_DIR/keystore.properties" <<EOF
# Release signing keystore (PKCS#12). Used by GitHub Actions and local builds.
# WARNING: this key is committed to the repo by design (public CI signing key).
# See docs/06-SIGNING-KEYS.md for details, backup and rotation instructions.
storeFile=release-signing.p12
storeType=pkcs12
storePassword=$PASS
keyAlias=iptvplayer
keyPassword=$PASS
EOF

echo "Keystore written to $OUT_DIR/release-signing.p12"
openssl x509 -in <(openssl pkcs12 -in "$OUT_DIR/release-signing.p12" -passin pass:"$PASS" -legacy -nokeys) -noout -subject -enddate
