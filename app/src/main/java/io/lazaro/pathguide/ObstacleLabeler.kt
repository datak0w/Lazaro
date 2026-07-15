package io.lazaro.pathguide

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObstacleLabeler @Inject constructor() {

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(LABEL_THRESHOLD)
            .build(),
    )

    suspend fun labelFromImage(imageProxy: ImageProxy): String {
        return analyzeScene(imageProxy).primaryOrDefault()
    }

    suspend fun analyzeScene(imageProxy: ImageProxy): SceneLabels {
        val mediaImage = imageProxy.image ?: return SceneLabels()
        return try {
            val image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees,
            )
            val labels = labeler.process(image).await()
            buildSceneLabels(labels.map { it.text to it.confidence })
        } catch (_: Exception) {
            SceneLabels()
        }
    }

    suspend fun analyzeScene(bitmap: Bitmap): SceneLabels {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val labels = labeler.process(image).await()
            buildSceneLabels(labels.map { it.text to it.confidence })
        } catch (_: Exception) {
            SceneLabels()
        }
    }

    suspend fun confirmsStairsFromImage(imageProxy: ImageProxy): Boolean {
        val mediaImage = imageProxy.image ?: return false
        return try {
            val image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees,
            )
            val labels = labeler.process(image).await()
            labels.any { label ->
                val text = label.text.lowercase()
                isStairLabel(text) && label.confidence >= 0.40f
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun label(bitmap: Bitmap): String {
        return analyzeScene(bitmap).primaryOrDefault()
    }

    suspend fun confirmsStairs(bitmap: Bitmap): Boolean {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val labels = labeler.process(image).await()
            labels.any { label ->
                val text = label.text.lowercase()
                isStairLabel(text) && label.confidence >= 0.40f
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildSceneLabels(raw: List<Pair<String, Float>>): SceneLabels {
        val mapped = raw
            .asSequence()
            .filter { (_, confidence) -> confidence >= LABEL_THRESHOLD }
            .mapNotNull { (text, confidence) ->
                val spanish = mapLabel(text)
                if (spanish == null) return@mapNotNull null
                SceneLabel(spanish = spanish, confidence = confidence, raw = text)
            }
            .distinctBy { it.spanish }
            .sortedByDescending { it.confidence }
            .take(4)
            .toList()

        return SceneLabels(
            items = mapped,
            primary = mapped.firstOrNull()?.spanish,
        )
    }

    private fun isStairLabel(text: String): Boolean {
        return text.contains("stair") ||
            text.contains("step") ||
            text.contains("escalera") ||
            text.contains("escalinata") ||
            text.contains("stairs")
    }

    private fun mapLabel(raw: String): String? {
        val label = raw.lowercase()
        if (GENERIC_LABELS.any { label.contains(it) }) return null

        return when {
            label.contains("car") || label.contains("vehicle") || label.contains("automobile") ||
                label.contains("truck") || label.contains("bus") || label.contains("van") -> "coche"
            label.contains("motorcycle") || label.contains("motorbike") -> "moto"
            label.contains("bicycle") || label.contains("bike") -> "bicicleta"
            label.contains("person") || label.contains("people") || label.contains("man") ||
                label.contains("woman") || label.contains("child") -> "persona"
            label.contains("dog") || label.contains("cat") || label.contains("pet") -> "animal"
            label.contains("tree") || label.contains("plant") || label.contains("flower") -> "vegetación"
            label.contains("pole") || label.contains("post") || label.contains("pillar") -> "poste"
            label.contains("sign") || label.contains("traffic") -> "señal"
            label.contains("bench") || label.contains("chair") || label.contains("seat") -> "asiento"
            label.contains("sofa") || label.contains("couch") || label.contains("settee") -> "sofá"
            label.contains("table") || label.contains("desk") -> "mesa"
            label.contains("door") || label.contains("doorway") -> "puerta"
            label.contains("window") -> "ventana"
            label.contains("stair") || label.contains("step") || label.contains("escalera") -> null
            label.contains("wheelchair") -> "silla de ruedas"
            label.contains("scooter") -> "patinete"
            label.contains("suitcase") || label.contains("luggage") || label.contains("bag") -> "equipaje"
            label.contains("box") || label.contains("package") || label.contains("carton") -> "caja"
            label.contains("trash") || label.contains("bin") || label.contains("garbage") -> "contenedor"
            label.contains("fence") || label.contains("railing") || label.contains("barrier") -> "barrera"
            label.contains("building") || label.contains("house") || label.contains("facade") -> "edificio"
            label.contains("road") || label.contains("street") || label.contains("sidewalk") ||
                label.contains("pavement") -> "vía"
            label.contains("wall") -> "pared"
            label.contains("furniture") || label.contains("cabinet") || label.contains("shelf") -> "mueble"
            label.contains("food") || label.contains("drink") -> null
            else -> if (label.length <= 3) null else "obstáculo"
        }
    }

    companion object {
        private const val LABEL_THRESHOLD = 0.38f

        private val GENERIC_LABELS = listOf(
            "monochrome",
            "pattern",
            "texture",
            "material",
            "indoor",
            "outdoor",
            "room",
            "floor",
            "ceiling",
            "sky",
            "cloud",
            "light",
            "darkness",
            "color",
            "line",
            "shape",
            "object",
            "thing",
            "structure",
            "area",
            "space",
            "scene",
            "image",
            "photo",
            "snapshot",
        )
    }
}
