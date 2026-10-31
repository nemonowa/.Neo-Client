#!/usr/bin/env bash
# .Neo-Client installer for macOS / Linux: copies the .Neo-Client folder into <minecraft>/versions
# Usage: ./install.sh [path-to-minecraft-dir]
# Default dir: macOS ~/Library/Application Support/minecraft, Linux ~/.minecraft
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/.Neo-Client"

if [ "${1:-}" != "" ]; then
  MC="$1"
elif [ "$(uname -s)" = "Darwin" ]; then
  MC="$HOME/Library/Application Support/minecraft"
else
  MC="$HOME/.minecraft"
fi

[ -d "$MC" ] || { echo "minecraft dir not found: $MC (pass it as the first argument)" >&2; exit 1; }
[ -f "$SRC/.Neo-Client.json" ] || { echo "missing: $SRC/.Neo-Client.json" >&2; exit 1; }

if pgrep -x "minecraft-launcher" >/dev/null 2>&1 || pgrep -x "Minecraft Launcher" >/dev/null 2>&1; then
  echo "Minecraft Launcher seems to be running. Close it completely and run again." >&2
  exit 1
fi

mkdir -p "$MC/versions/.Neo-Client"
cp "$SRC/.Neo-Client.json" "$MC/versions/.Neo-Client/.Neo-Client.json"
echo "OK: $MC/versions/.Neo-Client/.Neo-Client.json"
