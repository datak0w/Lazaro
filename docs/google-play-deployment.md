# Despliegue en Google Play Store — Lázaro

Este documento explica paso a paso cómo firmar, subir y distribuir Lázaro en Google Play Store para que el usuario ciego reciba actualizaciones automáticas.

---

## 1. Generar el keystore de firma (una sola vez)

Desde tu terminal con Java instalado:

```bash
keytool -genkey -v \
  -keystore lazaro-release.keystore \
  -alias lazaro \
  -keyalg RSA -keysize 2048 -validity 10000
```

Te pedirá datos como nombre, organización, etc. La contraseña que pongas aquí es la que usarás en los pasos siguientes.

**Guarda este archivo (`lazaro-release.keystore`) en un lugar seguro. Sin él no podrás actualizar la app en el futuro.**

---

## 2. Configurar secrets en GitHub (firma automática)

Ve a tu repositorio en GitHub → **Settings → Secrets and variables → Actions** → **New repository secret**:

| Secret | Valor |
|---|---|
| `GOOGLE_MAPS_API_KEY` | Tu clave de Google Cloud Console |
| `GEMINI_API_KEY` | Tu clave de Gemini API |
| `RELEASE_KEYSTORE_BASE64` | El keystore codificado en base64 (ver comando abajo) |
| `RELEASE_KEYSTORE_PASSWORD` | La contraseña del keystore |
| `RELEASE_KEY_ALIAS` | `lazaro` (o el alias que pusiste) |
| `RELEASE_KEY_PASSWORD` | La contraseña de la clave (puede ser igual que la del keystore) |

Para obtener `RELEASE_KEYSTORE_BASE64`:

```bash
base64 -w 0 lazaro-release.keystore | pbcopy   # macOS
base64 -w 0 lazaro-release.keystore | xclip -selection clipboard   # Linux
```

---

## 3. Compilar APK firmado automáticamente

Cada `push` a `main` dispara el workflow `.github/workflows/build-apk.yml` que:
1. Descarga el keystore desde secrets.
2. Compila `app-release.apk` firmado.
3. Lo sube como artifact descargable.

También puedes lanzarlo manualmente desde **Actions → Build APK → Run workflow**.

---

## 4. Subir a Google Play Console

### 4.1 Crear cuenta de desarrollador
- Ve a [Google Play Console](https://play.google.com/console).
- Paga la tarifa de registro (25 USD, única vez).
- Crea la ficha de Lázaro con descripción, iconos y capturas.

### 4.2 Crear la app en Play Console
1. **Crear aplicación** → nombre: **Lázaro** → idioma: **Español**.
2. En **Configuración → Acceso a la app**, selecciona **Acceso total** (la app es gratuita).
3. En **Ficha de Google Play**, rellena:
   - Título corto: "Lázaro — Asistente para ciegos"
   - Descripción completa: copia desde `README.md` o adapta.
   - Capturas de pantalla: genera 2-3 con un emulador.

### 4.3 Subir el APK firmado
1. Ve a **Producción → Crear versión nueva**.
2. Sube `app-release.apk` (descárgalo del artifact de GitHub Actions).
3. Google Play requiere **Android App Bundle (AAB)** para nuevas apps desde 2021. El workflow actual genera APK. Si Play lo rechaza:
   - Cambia `./gradlew assembleRelease` por `./gradlew bundleRelease` en el workflow.
   - Sube `app/build/outputs/bundle/release/app-release.aab` en vez del APK.
   - Ajusta el artifact path en el workflow a `**/*.aab`.

### 4.4 Políticas y declaraciones
- **Política de privacidad**: crea una página simple (puedes usar GitHub Pages) declarando qué datos recoge Lázaro (ubicación, micrófono, cámara solo local, sin servidores propios).
- **Grupo de contenido**: **Aplicaciones** → **Herramientas**.
- **Clasificación de contenido**: Responde el cuestionario (probablemente **PEGI 3** ya que no hay contenido sensible).

### 4.5 Distribución interna / cerrada (recomendado para pruebas)
Antes de lanzar a producción pública:
1. Ve a **Pruebas internas** o **Pruebas cerradas**.
2. Sube el APK/AAB firmado.
3. Añade testers (correos de confianza).
4. Los testers reciben un enlace por email; al instalar, Google Play gestiona actualizaciones automáticas.

---

## 5. Auto-actualización para el usuario ciego

Una vez Lázaro está en Google Play Store:

- **Play Store → Configuración → Preferencias de actualización** → activa **Actualizar aplicaciones automáticamente** en el dispositivo del usuario.
- Cuando hagas `push` a `main` y la versión suba en Play Console, el usuario recibe la actualización en segundo plano **sin tener que hacer nada**.
- Para no perder la configuración del usuario entre versiones, asegúrate de incrementar `versionCode` en cada release (ya está automatizado con `GITHUB_RUN_NUMBER`).

### Flujo recomendado de releases
1. Desarrollas y testeas en local.
2. Haces `git push` a `main`.
3. GitHub Actions compila y sube el artifact.
4. Tú descargas el APK/AAB firmado y lo subes a **Play Console → Producción** (o pruebas internas).
5. El usuario ciego recibe la actualización automáticamente cuando Google Play la distribuya.

---

## 6. Alternativa: Firebase App Distribution (más rápido para betas)

Si quieres distribuir betas sin pasar por la revisión de Play:
1. Crea proyecto en [Firebase Console](https://console.firebase.google.com/).
2. Ve a **App Distribution**.
3. Sube el APK firmado y añade testers por email.
4. Los testers reciben un email con enlace de descarga; la app se instala por APK y **no** se auto-actualiza vía Play.

Para auto-actualización definitiva, **Google Play Store es la única opción fiable**.

---

## Checklist antes de subir a Play

- [ ] Keystore generado y guardado en lugar seguro.
- [ ] Secrets configurados en GitHub (API keys + keystore).
- [ ] Workflow ejecutado al menos una vez y artifact descargado correctamente.
- [ ] Política de privacidad publicada en una URL.
- [ ] Ficha de Play Store completa (título, descripción, icono 512×512, capturas).
- [ ] APK/AAB firmado con el keystore correcto.
- [ ] Pruebas internas con 2-3 testers antes de producción.
- [ ] App probada en TalkBack (lector de pantalla) para asegurar accesibilidad total.
