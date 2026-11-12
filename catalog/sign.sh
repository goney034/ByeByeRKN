#!/bin/sh
# Signs catalog/warp.json with the project's catalog key. The key stays in keystore/, out of Git:
# back it up together with the release keystore. Raise "version" before signing, or installed apps
# keep the list they have.
set -eu
cd "$(dirname "$0")/.."
openssl dgst -sha256 -sign keystore/catalog-signing.pem catalog/warp.json | openssl base64 -A > catalog/warp.json.sig
echo "signed $(grep -m1 '"version"' catalog/warp.json | tr -d ' ,')"
