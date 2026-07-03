#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <version> <apk-path> [changelog]" >&2
  exit 2
fi

VERSION="$1"
APK_PATH="$2"
CHANGELOG="${3:-Rikka ${VERSION} release.}"

SERVER_USER="${SERVER_USER:-ops}"
SERVER_HOST="${SERVER_HOST:-85.121.50.23}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/github_my_codex_ed25519}"
SERVER_DIR="${SERVER_DIR:-/opt/rikka}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-https://yuxinjiang218.cloud/rikka}"

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found: $APK_PATH" >&2
  exit 1
fi

APK_NAME="rikka-${VERSION}-arm64.apk"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cp "$APK_PATH" "$TMP_DIR/$APK_NAME"
SIZE="$(stat -c '%s' "$TMP_DIR/$APK_NAME")"
SHA256="$(sha256sum "$TMP_DIR/$APK_NAME" | awk '{print $1}')"
PUBLISHED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

MANIFEST="$TMP_DIR/latest.json"
cat > "$MANIFEST" <<JSON
{
  "version": "$VERSION",
  "name": "$VERSION",
  "published_at": "$PUBLISHED_AT",
  "changelog": "$CHANGELOG",
  "downloads": [
    {
      "name": "$APK_NAME",
      "url": "$PUBLIC_BASE_URL/$APK_NAME",
      "size": $SIZE,
      "sha256": "$SHA256"
    }
  ]
}
JSON

SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes "$SERVER_USER@$SERVER_HOST")
SCP=(scp -i "$SSH_KEY" -o BatchMode=yes)

"${SSH[@]}" "sudo -n install -d -m 0755 -o $SERVER_USER -g $SERVER_USER '$SERVER_DIR'"

"${SCP[@]}" "$TMP_DIR/$APK_NAME" "$MANIFEST" "$SERVER_USER@$SERVER_HOST:/tmp/"

"${SSH[@]}" "set -euo pipefail
sudo -n find '$SERVER_DIR' -maxdepth 1 -type f \\( -name '*.apk' -o -name 'latest.json' \\) -delete
sudo -n install -m 0644 -o root -g root '/tmp/$APK_NAME' '$SERVER_DIR/$APK_NAME'
sudo -n install -m 0644 -o root -g root '/tmp/latest.json' '$SERVER_DIR/latest.json'
rm -f '/tmp/$APK_NAME' '/tmp/latest.json'
"

echo "Published $APK_NAME"
echo "URL: $PUBLIC_BASE_URL/$APK_NAME"
echo "Manifest: $PUBLIC_BASE_URL/latest.json"
echo "SHA256: $SHA256"
