#!/usr/bin/env bash
# .Neo-Client installer for macOS / Linux
# Usage: ./install.sh [path-to-minecraft-dir]
# Default dir: macOS ~/Library/Application Support/minecraft, Linux ~/.minecraft
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JSON_SRC="$HERE/.Neo-Client.json"
JAR_SRC="$HERE/neoclient.jar"

if [ "${1:-}" != "" ]; then
  MC="$1"
elif [ "$(uname -s)" = "Darwin" ]; then
  MC="$HOME/Library/Application Support/minecraft"
else
  MC="$HOME/.minecraft"
fi

[ -d "$MC" ] || { echo "minecraft dir not found: $MC (pass it as the first argument)" >&2; exit 1; }
[ -f "$JSON_SRC" ] || { echo "missing: $JSON_SRC" >&2; exit 1; }
[ -f "$JAR_SRC" ]  || { echo "missing: $JAR_SRC" >&2; exit 1; }

if pgrep -x "minecraft-launcher" >/dev/null 2>&1 || pgrep -x "Minecraft Launcher" >/dev/null 2>&1 || pgrep -x "launcher" >/dev/null 2>&1; then
  echo "Minecraft Launcher seems to be running. Close it completely and run again." >&2
  exit 1
fi

sha1_of() {
  if command -v sha1sum >/dev/null 2>&1; then sha1sum "$1" | cut -d' ' -f1
  else shasum -a 1 "$1" | cut -d' ' -f1; fi
}
size_of() { wc -c < "$1" | tr -d ' '; }

# Read the expected values and jar path from the JSON itself (first library = neoclient)
json_field() { # $1 = key ; first occurrence after "artifact"
  awk '/"artifact"/{f=1} f' "$JSON_SRC" | grep -m1 "\"$1\"" | sed -E 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"?([^",}]*)"?.*/\1/'
}
EXPECT_SHA1="$(json_field sha1)"
EXPECT_SIZE="$(json_field size)"
REL_PATH="$(json_field path)"
[ -n "$EXPECT_SHA1" ] && [ -n "$EXPECT_SIZE" ] && [ -n "$REL_PATH" ] || { echo "cannot read sha1/size/path from JSON" >&2; exit 1; }
case "$REL_PATH" in /*|*..*) echo "unsafe path in JSON: $REL_PATH" >&2; exit 1;; esac

GOT_SHA1="$(sha1_of "$JAR_SRC")"
GOT_SIZE="$(size_of "$JAR_SRC")"
if [ "$GOT_SHA1" != "$EXPECT_SHA1" ] || [ "$GOT_SIZE" != "$EXPECT_SIZE" ]; then
  echo "neoclient.jar does not match the JSON (sha1=$GOT_SHA1 size=$GOT_SIZE, expected $EXPECT_SHA1 / $EXPECT_SIZE)" >&2
  exit 1
fi

VDIR="$MC/versions/.Neo-Client"
JAR_DST="$MC/libraries/$REL_PATH"
mkdir -p "$VDIR" "$(dirname "$JAR_DST")"
cp "$JSON_SRC" "$VDIR/.Neo-Client.json"
cp "$JAR_SRC" "$JAR_DST"

# move old layouts aside (not deleted)
for old in libraries/com/neo/neoclient libraries/com/neo/neo libraries/com/example/neoclient; do
  p="$MC/$old"
  if [ -d "$p" ] && [ "$p/" != "$(dirname "$JAR_DST")/" ]; then
    rm -rf "$p.old"; mv "$p" "$p.old"; echo "moved aside: $p"
  fi
done

[ -f "$VDIR/.Neo-Client.json" ] && [ "$(sha1_of "$JAR_DST")" = "$EXPECT_SHA1" ] || { echo "verification failed" >&2; exit 1; }
echo "OK: $VDIR/.Neo-Client.json"
echo "OK: $JAR_DST"
