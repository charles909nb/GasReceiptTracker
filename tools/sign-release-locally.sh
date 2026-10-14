#!/usr/bin/env bash
set -euo pipefail
# This script runs privately, NEVER on public GitHub Actions with a private key.
# USAGE: GRT_STORE_PASSWORD=... GRT_KEY_PASSWORD=... ./sign-release-locally.sh unsigned.apk signed.apk apksigner.jar key.jks
if [ "$#" -ne 4 ]; then
  echo 'Usage: sign-release-locally.sh unsigned.apk signed.apk apksigner.jar key.jks' >&2
  exit 2
fi
: "${GRT_STORE_PASSWORD:?Set the private keystore password outside the public repository}"
: "${GRT_KEY_PASSWORD:?Set the private key password outside the public repository}"
test -s "$1"
test -s "$3"
test -s "$4"
java -jar "$3" sign --ks "$4" --ks-key-alias gasreceipttracker-release \
  --ks-pass env:GRT_STORE_PASSWORD --key-pass env:GRT_KEY_PASSWORD \
  --min-sdk-version 26 --v1-signing-enabled true --v2-signing-enabled true \
  --v3-signing-enabled true --out "$2" "$1"
# Pin the one authorized certificate, so a new accidental key can never be
# mistaken for an upgrade-compatible release.
expected_cert=8058c63d913384ab6d340d1a88dd7058695d6746948a2e416cab1ceedf420254
actual_cert=$(java -jar "$3" verify --min-sdk-version 26 --print-certs "$2" \
  | sed -n 's/.*certificate SHA-256 digest: //p' | head -n 1 | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')
if [ "$actual_cert" != "$expected_cert" ]; then
  rm -f "$2"
  echo "ERROR: Signing certificate is not the permanent Gas Receipt Tracker update key." >&2
  exit 3
fi
java -jar "$3" verify --min-sdk-version 26 --verbose --print-certs "$2"