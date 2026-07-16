package io.lazaro.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineWakeWordEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var model: Model? = null
    private var loadedModelPath: String? = null
    private var speechService: SpeechService? = null
    private val running = AtomicBoolean(false)
    private var onWakeWord: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var lastDetectionMs = 0L

    fun isRunning(): Boolean = running.get()

    suspend fun ensureModel(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val modelRoot = File(context.filesDir, MODEL_DIR_NAME)
            if (!isValidModel(modelRoot)) {
                downloadAndUnpackModel(modelRoot)
            }
            ensureUuidFile(modelRoot)
            modelRoot.absolutePath
        }
    }

    suspend fun start(
        modelPath: String,
        onDetected: () -> Unit,
        onError: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        onWakeWord = onDetected
        onErrorCallback = onError
        stopInternal()

        try {
            val loadedModel = loadModel(modelPath)
            val recognizer = Recognizer(loadedModel, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            val started = service.startListening(recognitionListener)
            if (!started) {
                service.shutdown()
                throw IllegalStateException("El micrófono offline ya estaba en uso.")
            }
            speechService = service
            running.set(true)
        } catch (e: Exception) {
            running.set(false)
            stopInternal()
            onError(e.message ?: "No pude iniciar la escucha offline.")
        }
    }

    fun stop() {
        // Cortar callbacks ANTES de stop interno para no re-disparar wake
        // (partial+final del mismo «Lázaro» tras pause).
        running.set(false)
        onWakeWord = null
        stopInternal()
    }

    fun shutdown() {
        running.set(false)
        stopInternal()
        releaseModel()
        onWakeWord = null
        onErrorCallback = null
    }

    private fun loadModel(modelPath: String): Model {
        if (model != null && loadedModelPath == modelPath) {
            return model!!
        }
        releaseModel()
        loadedModelPath = modelPath
        model = Model(modelPath)
        return model!!
    }

    private fun stopInternal() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) {
            // Micrófono ya liberado.
        } finally {
            speechService = null
        }
    }

    private fun releaseModel() {
        try {
            model?.close()
        } catch (_: Exception) {
            // Modelo ya liberado.
        } finally {
            model = null
            loadedModelPath = null
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            handleHypothesis(hypothesis)
        }

        override fun onResult(hypothesis: String?) {
            handleHypothesis(hypothesis)
        }

        override fun onFinalResult(hypothesis: String?) {
            handleHypothesis(hypothesis)
        }

        override fun onError(exception: Exception?) {
            if (!running.getAndSet(false)) return
            stopInternal()
            val message = exception?.message?.ifBlank { null }
                ?: "El micrófono offline dejó de escuchar."
            onErrorCallback?.invoke(message)
        }

        override fun onTimeout() {
            if (!running.getAndSet(false)) return
            stopInternal()
            onErrorCallback?.invoke("")
        }
    }

    private fun handleHypothesis(hypothesis: String?) {
        if (!running.get() || hypothesis.isNullOrBlank()) return
        val callback = onWakeWord ?: return

        val text = extractText(hypothesis)
        if (text.isBlank() || text == "[unk]") return
        if (!matchesWakeHypothesis(text)) return

        val now = System.currentTimeMillis()
        if (now - lastDetectionMs < DETECTION_COOLDOWN_MS) return
        lastDetectionMs = now

        // Un solo disparo por detección; evita 2º wake que corta el STT pendiente
        onWakeWord = null
        callback.invoke()
    }

    private fun matchesWakeHypothesis(text: String): Boolean {
        if (WakeWordDetector.containsWakeWord(text)) return true

        val compact = text
            .lowercase()
            .replace(Regex("[^a-záéíóúñ]"), "")
            .replace('á', 'a')
            .replace('é', 'e')
            .replace('í', 'i')
            .replace('ó', 'o')
            .replace('ú', 'u')

        return compact.contains("lazaro") ||
            compact.contains("lasaro") ||
            compact.contains("lazzaro") ||
            compact.contains("lazarro") ||
            compact.contains("hazaro")
    }

    private fun extractText(hypothesis: String): String {
        return try {
            val json = JSONObject(hypothesis)
            json.optString("partial")
                .ifBlank { json.optString("text") }
                .trim()
        } catch (_: Exception) {
            hypothesis.trim()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000f
        private const val MODEL_DIR_NAME = "vosk-model-small-es-0.42"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
        private const val DETECTION_COOLDOWN_MS = 4_000L

        private fun isValidModel(modelRoot: File): Boolean {
            return File(modelRoot, "conf/model.conf").exists() &&
                File(modelRoot, "am/final.mdl").exists()
        }

        private fun ensureUuidFile(modelRoot: File) {
            val uuidFile = File(modelRoot, "uuid")
            if (!uuidFile.exists()) {
                uuidFile.writeText(UUID.randomUUID().toString())
            }
        }

        private fun downloadAndUnpackModel(targetDir: File) {
            val cacheZip = File(targetDir.parentFile ?: targetDir, "$MODEL_DIR_NAME.zip")
            downloadFile(MODEL_URL, cacheZip)

            val parent = targetDir.parentFile ?: throw IllegalStateException("Sin directorio padre.")
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            unzip(cacheZip, parent)

            val extracted = File(parent, MODEL_DIR_NAME)
            if (!extracted.exists()) {
                throw IllegalStateException("El modelo Vosk descargado no es válido.")
            }
            cacheZip.delete()
        }

        private fun downloadFile(url: String, destination: File) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 300_000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("Descarga del modelo falló (HTTP $responseCode).")
            }

            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        private fun unzip(zipFile: File, destinationDir: File) {
            ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(destinationDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            zip.copyTo(output)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }
}
