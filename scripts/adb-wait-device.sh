#!/usr/bin/env bash
# Espera el Samsung por ADB y muestra estado.
set -euo pipefail
export PATH="${HOME}/Android/Sdk/platform-tools:${HOME}/.local/bin:${PATH}"

echo "ADB: $(command -v adb)"
echo "Esperando dispositivo (conecta USB + Depuración USB + acepta el diálogo)…"
adb start-server >/dev/null
adb wait-for-device
adb devices -l
MODEL=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)
echo "Conectado: ${MODEL:-desconocido}"
