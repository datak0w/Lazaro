# Lázaro

**Asistente de voz en Android diseñado para personas ciegas y con baja visión.**

Lázaro es un compañero de voz en español que funciona por completo sin mirar la pantalla: navegación peatonal con instrucciones habladas y vibración en los giros, lectura de mensajes, llamadas, noticias, música, transporte público, audiolibros y memoria personal. Está pensado desde cero para accesibilidad, no como un añadido.

---

## Tabla de contenidos

- [Motivación](#motivación)
- [Características principales](#características-principales)
- [Navegación accesible](#navegación-accesible)
- [Arquitectura](#arquitectura)
- [Requisitos](#requisitos)
- [Instalación y configuración](#instalación-y-configuración)
- [Permisos necesarios](#permisos-necesarios)
- [Uso por voz](#uso-por-voz)
- [Configuración de Gemini](#configuración-de-gemini)
- [Compilación](#compilación)
- [Estructura del proyecto](#estructura-del-proyecto)
- [Contribuir](#contribuir)
- [Licencia](#licencia)
- [Autor y contacto](#autor-y-contacto)

---

## Motivación

La mayoría de asistentes asumen que el usuario puede ver la interfaz. Lázaro invierte esa premisa:

- Toda interacción es **por voz**.
- Las respuestas son **cortas y claras**, pensadas para escuchar, no para leer.
- Las acciones críticas (navegar, llamar, enviar mensajes) piden **confirmación**.
- La palabra clave **«Lázaro»** activa el asistente en segundo plano sin bloquear otras apps.

Este proyecto es **Open Source** para que la comunidad de accesibilidad pueda auditarlo, mejorarlo y adaptarlo.

---

## Características principales

| Área | Qué hace |
|------|----------|
| **Voz** | Escucha pasiva continua; al decir «Lázaro» suena un tono breve y escucha tu comando |
| **Navegación** | Google Maps a pie + Lázaro lee cada giro («Ahora, gira a la derecha en Calle…») y vibra al girar |
| **Ubicación** | «¿Dónde estoy?», historial de lugares recientes |
| **Mensajes** | Lee WhatsApp y otras apps; responde por dictado |
| **Llamadas** | Busca contactos por nombre y llama con confirmación |
| **Noticias** | Lee titulares de España hoy en voz alta (no abre YouTube) |
| **Música / vídeo** | Busca en Spotify, YouTube, etc. («Motorhead en Spotify») |
| **Transporte** | Parada cercana, guiado a pie, ruta en transporte público |
| **Audiolibros** | Gutenberg, Librivox; soporte Libby |
| **Memoria** | Guarda direcciones, contactos, preferencias y skills personalizados |
| **IA** | Google Gemini con function calling para acciones estructuradas |

---

## Navegación accesible

Lázaro no sustituye Google Maps: lo **complementa** para personas ciegas.

1. Confirmas el destino por voz.
2. Se abre **Google Maps** en modo navegación peatonal (`dir_action=navigate`).
3. Lázaro **lee en voz alta** las instrucciones que Maps publica en sus notificaciones, con formato natural:
   - *«Ahora, gira a la derecha en Calle Mayor»*
   - *«Ahora, gira a la izquierda hacia Avenida de la Constitución»*
4. En cada **giro** (izquierda, derecha, retorno), el móvil emite una **vibración corta** distinta según el lado.

### Requisitos para que funcione bien

- **Google Maps** instalado y actualizado.
- **Acceso a notificaciones** activado para Lázaro (lee las instrucciones de navegación de Maps).
- Volumen de medios audible; en Maps, comprobar que la guía por voz no esté en silencio.

### Patrones de vibración

| Maniobra | Vibración |
|----------|-----------|
| Giro a la **izquierda** | Dos pulsos cortos |
| Giro a la **derecha** | Un pulso medio |
| **Retorno** / U-turn | Tres pulsos |

---

## Arquitectura

```
┌─────────────────────────────────────────────────────────────┐
│                    AssistantForegroundService               │
│  (micrófono + ubicación en primer plano)                    │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                   AssistantController                       │
│  Pasivo (wake word) → Activo → Procesar → Hablar → Repetir   │
└──────┬───────────────┬────────────────┬───────────────────────┘
       │               │                │
       ▼               ▼                ▼
 SpeechRecognition  TextToSpeech   GeminiOrchestrator
       │               │                │
       │               │                ▼
       │               │         ActionExecutor
       │               │    (navegación, llamadas, media…)
       ▼               ▼
 NavigationGuidanceMonitor ← Notificaciones de Google Maps
       │
       └── TurnHapticFeedback (vibración en giros)
```

**Stack:** Kotlin · Jetpack Compose · Hilt · Room · Google Gemini · Android Speech API

---

## Requisitos

- **Android 8.0** (API 26) o superior
- **Clave API de Google Gemini** ([Google AI Studio](https://aistudio.google.com/))
- **Google Maps** (recomendado para navegación)
- Conexión a internet para IA, noticias y búsquedas

---

## Instalación y configuración

### 1. Clonar el repositorio

```bash
git clone https://github.com/datak0w/Lazaro.git
cd Lazaro
```

### 2. Configurar secretos locales

Copia el ejemplo y edita tus claves (este archivo **no se sube** a Git):

```bash
cp local.properties.example local.properties
```

```properties
sdk.dir=/ruta/a/Android/Sdk
GEMINI_API_KEY=tu_clave_aqui
GEMINI_MODEL=gemini-3.5-flash
```

En el primer arranque, Lázaro descarga el modelo Vosk en español (~40 MB) para detectar "Lázaro" en reposo **sin pitidos** y sin cuentas externas. Google SR solo se activa tras la wake word.

### 3. Compilar e instalar

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. Activar permisos en el móvil

Tras instalar, abre Lázaro y concede:

1. Micrófono  
2. Ubicación (incluida en segundo plano si el sistema lo pide)  
3. Notificaciones  
4. **Acceso a notificaciones** (Ajustes → Apps → Acceso especial → Acceso a notificaciones → Lázaro)  
5. Contactos y teléfono (para llamadas)  
6. Servicio de accesibilidad (solo si usas respuesta automática en WhatsApp)

---

## Permisos necesarios

| Permiso | Motivo |
|---------|--------|
| `RECORD_AUDIO` | Reconocimiento de voz y palabra clave |
| `ACCESS_FINE_LOCATION` | Navegación, paradas de transporte, «¿dónde estoy?» |
| `FOREGROUND_SERVICE_MICROPHONE` | Asistente siempre escuchando de forma estable |
| `POST_NOTIFICATIONS` | Servicio en primer plano |
| `READ_CONTACTS` / `CALL_PHONE` | Llamadas por nombre |
| `VIBRATE` | Vibración al girar en navegación |
| Acceso a notificaciones | Leer WhatsApp y **instrucciones de Google Maps** |
| Accesibilidad (opcional) | Confirmar envío en WhatsApp |

---

## Uso por voz

### Activar el asistente

1. Abre Lázaro y pulsa **Iniciar asistente** (o deja el servicio en marcha).
2. Di **«Lázaro»** — oirás un tono breve.
3. Di tu comando: *«Llévame a la estación de metro»*, *«Noticias»*, *«Lee el WhatsApp»*…

También puedes decir todo junto: *«Lázaro, pon Motorhead en Spotify»*.

### Comandos de interrupción

- **«Para»** / **«Detente»** — detiene lo que esté haciendo (hablar, navegar, procesar).
- **«Cancela»** — cancela opciones pendientes o confirmaciones.
- **«Repíteme las opciones»** — cuando hay una lista numerada pendiente.

### Ejemplos

```
«Lázaro, noticias»
→ Lee titulares de España hoy

«Lázaro, llévame a Calle Alcalá 42»
→ Confirma → Maps + instrucciones habladas con vibración

«Lázaro, Motorhead en Spotify»
→ Busca y abre Spotify

«Lázaro, parada de bus cercana»
→ Encuentra parada y ofrece guiarte a pie
```

---

## Configuración de Gemini

El modelo por defecto es `gemini-3.5-flash`. Si recibes error 404, actualiza en `local.properties`:

```properties
GEMINI_MODEL=gemini-3.5-flash
```

Modelos antiguos como `gemini-2.0-flash` pueden estar deprecados en la API.

---

## Compilación

```bash
# Depuración
./gradlew assembleDebug

# Release (requiere firma configurada)
./gradlew assembleRelease
```

Salida APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## Estructura del proyecto

```
app/src/main/java/io/lazaro/
├── assistant/          # Flujo de voz y estado del asistente
├── voice/              # STT, TTS, wake word, notificador
├── navigation/         # Lectura de giros Maps + vibración
├── ai/                 # Gemini, herramientas, prompts
├── actions/            # Ejecutor de acciones (llamadas, maps…)
├── news/               # Titulares de España por voz
├── media/              # Spotify, YouTube, radio…
├── transit/            # Transporte público (Overpass + Maps)
├── audiobook/          # Gutenberg, Librivox, Libby
├── messaging/          # WhatsApp y notificaciones
├── memory/             # Room, skills, ubicación
└── service/            # Servicio en primer plano
```

---

## Contribuir

Las contribuciones son bienvenidas, especialmente en:

- Accesibilidad y pruebas con usuarios reales
- Idiomas y variantes del español
- Navegación y transporte público
- Documentación

### Flujo sugerido

1. Haz fork del repositorio.
2. Crea una rama: `git checkout -b feature/mi-mejora`
3. Commit con mensajes claros en español o inglés.
4. Abre un Pull Request describiendo el cambio y cómo probarlo.

Por favor, no incluyas claves API ni `local.properties` en los commits.

---

## Licencia

Este proyecto está licenciado bajo la **Apache License 2.0**.

- Puedes usar, modificar y distribuir el código libremente.
- Debes **mantener el aviso de copyright** y la licencia en las copias.
- El autor conserva los **derechos de autor**; la licencia no transfiere la marca «Lázaro».

Consulta el archivo [LICENSE](LICENSE) para el texto legal completo.

---

## Autor y contacto

**Copyright © 2026 [datak0w](https://github.com/datak0w)**

- Repositorio: [github.com/datak0w/Lazaro](https://github.com/datak0w/Lazaro)
- Issues y sugerencias: [GitHub Issues](https://github.com/datak0w/Lazaro/issues)

Si Lázaro te ayuda en tu día a día, considera dar una estrella al repositorio o contribuir con código, pruebas o traducciones.

---

<p align="center">
  <strong>Lázaro — porque no hace falta ver para ir lejos.</strong>
</p>
