# Control del Samsung desde el PC (ADB + scrcpy)

Para depurar Lazaro y el bastón WeWALK sin apps intermedias.

## Requisitos en el teléfono

1. **Opciones de desarrollador** (pulsar 7 veces el número de compilación).
2. Activar **Depuración USB**.
3. Cable USB de **datos** al PC.
4. Aceptar **¿Permitir depuración USB?** en el Samsung.

## Scripts

Desde la raíz del repo:

```bash
chmod +x scripts/*.sh

# 0) Estado rápido
./scripts/adb-status.sh

# 1) Esperar conexión
./scripts/adb-wait-device.sh

# 2) Ver pantalla / controlar (opcional)
./scripts/adb-scrcpy.sh

# 3) Logs de botones del bastón
./scripts/adb-cane-logcat.sh
```

Instalar APK:

```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Mapa de botones P2P (Lazaro)

| Hex   | Acción      |
|-------|-------------|
| 00 01 | LISTEN      |
| 01 01 | CANCEL      |
| 04 01 | WHERE_AM_I  |
| 02 01 | VOLUME_DOWN |
| 05 01 | VOLUME_UP   |

En logcat busca: `Botón P2P → …`

## scrcpy

Instalado en `~/.local/bin/scrcpy` (binario Genymobile). Asegura `~/.local/bin` en el `PATH`.
