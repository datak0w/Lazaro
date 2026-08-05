#!/usr/bin/env bash
export PATH="${HOME}/Android/Sdk/platform-tools:${HOME}/.local/bin:${PATH}"
adb start-server >/dev/null 2>&1 || true
echo "=== adb devices ==="
adb devices -l
echo "=== scrcpy ==="
command -v scrcpy && scrcpy --version | head -1
echo "=== USB (Samsung suele ser 04e8) ==="
lsusb | grep -iE 'samsung|04e8|google|android|adb' || echo "(ningún Samsung en lsusb — enchufa el cable)"
