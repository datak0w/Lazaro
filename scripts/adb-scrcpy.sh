#!/usr/bin/env bash
# Mirror / control del Samsung con scrcpy (requiere ADB).
set -euo pipefail
export PATH="${HOME}/Android/Sdk/platform-tools:${HOME}/.local/bin:${PATH}"

if ! command -v scrcpy >/dev/null; then
  echo "scrcpy no está en PATH. Debería estar en ~/.local/bin/scrcpy"
  exit 1
fi

adb start-server >/dev/null
adb wait-for-device
exec scrcpy --stay-awake --window-title "Lazaro Samsung" "$@"
