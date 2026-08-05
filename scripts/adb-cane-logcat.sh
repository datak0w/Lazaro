#!/usr/bin/env bash
# Logcat filtrado para botones BLE del bastón WeWALK / Lazaro.
set -euo pipefail
export PATH="${HOME}/Android/Sdk/platform-tools:${HOME}/.local/bin:${PATH}"

adb start-server >/dev/null
if ! adb get-state >/dev/null 2>&1; then
  echo "No hay dispositivo ADB. Ejecuta primero: scripts/adb-wait-device.sh"
  exit 1
fi

echo "=== Logcat bastón (Ctrl+C para parar) ==="
echo "Mapa esperado: 00=LISTEN 01=CANCEL 04=WHERE_AM_I 02=VOL- 05=VOL+"
echo "Pulsa botones del WeWALK con la app oficial cerrada."
echo

adb logcat -c
adb logcat -v time \
  '*:S' \
  'CaneButtonMapper:I' \
  'CaneBleManager:I' \
  'CaneBle:I' \
  'CaneBleService:I' \
  'Lazaro:I' \
  'WeWalk:I'
