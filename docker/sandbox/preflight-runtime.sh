#!/usr/bin/env bash
set -euo pipefail

for executable in sh mkdir dirname rm mv test printf sort sed grep find stat tar base64 \
  python3 bash curl file git jq node npm npx python uv uvx; do
  command -v "$executable" >/dev/null
done

python -c "import sys; assert sys.version_info >= (3, 12)"
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 22) process.exit(1)"
test "$(id -u)" -ne 0

probe="$(mktemp /workspace/.melonpaw-runtime-XXXXXX)"
stat -c '%s' "$probe" >/dev/null
printf 'sandbox-preflight' | base64 | base64 -d >/dev/null
rm -f "$probe"
