package io.lazaro.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.lazaro.media.MediaAutoplayPhase

enum class AutoplayActionResult {
    /** Sigue intentando (pestaña, resultado intermedio). */
    PROGRESS,
    /** Ya pulsó Play / reproducción en marcha. */
    DONE,
    /** Nada útil en esta pasada. */
    NONE,
}

object MediaAutoplayAccessibility {

    private val SPOTIFY_PACKAGES = setOf("com.spotify.music", "com.spotify.lite")
    private val YOUTUBE_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
    )

    fun tryAutoplay(
        packageName: String,
        root: AccessibilityNodeInfo,
        phase: MediaAutoplayPhase,
    ): AutoplayActionResult {
        return when (packageName) {
            in SPOTIFY_PACKAGES -> trySpotifyAutoplay(root, phase)
            in YOUTUBE_PACKAGES -> tryYouTubeAutoplay(root)
            else -> AutoplayActionResult.NONE
        }
    }

    private fun trySpotifyAutoplay(
        root: AccessibilityNodeInfo,
        phase: MediaAutoplayPhase,
    ): AutoplayActionResult {
        // Prioridad: botón Play grande (playlist / álbum ya abierto)
        if (clickSpotifyPlayButton(root)) {
            return AutoplayActionResult.DONE
        }

        if (phase == MediaAutoplayPhase.OPENED_ITEM) {
            // Ya abrimos un ítem; insistir en Play / shuffle
            if (clickSpotifyPlayButton(root)) return AutoplayActionResult.DONE
            return AutoplayActionResult.NONE
        }

        // Fase OPENED: en pantalla de búsqueda
        // 1) Pestaña Listas / Playlists (mejor para «pon rock»)
        if (clickSearchTab(root, listOf("Listas", "Playlists", "Lista", "Playlist"))) {
            return AutoplayActionResult.PROGRESS
        }
        // Canciones si no hay pestaña Listas visible
        if (clickSearchTab(root, listOf("Canciones", "Songs", "Temas", "Tracks"))) {
            return AutoplayActionResult.PROGRESS
        }

        // 2) Abrir primer resultado de playlist / canción / artista
        if (clickFirstPlaylistResult(root) || clickFirstSearchResultRow(root, minTop = 200)) {
            return AutoplayActionResult.PROGRESS
        }

        return AutoplayActionResult.NONE
    }

    private fun clickSpotifyPlayButton(root: AccessibilityNodeInfo): Boolean {
        val playPatterns = listOf(
            Regex("(?i)^reproducir$"),
            Regex("(?i)^play$"),
            Regex("(?i)^reproducir todo"),
            Regex("(?i)^play all"),
            Regex("(?i)reproducir lista"),
            Regex("(?i)shuffle play"),
            Regex("(?i)^aleatorio"),
            Regex("(?i)^shuffle$"),
            Regex("(?i)reproducir\\b"),
            Regex("(?i)^play\\b"),
        )
        for (pattern in playPatterns) {
            findClickableByContentDescription(root, pattern)?.let { node ->
                if (looksLikePrimaryPlay(node) && performClick(node)) return true
            }
        }
        for (label in listOf(
            "Reproducir", "Play", "Reproducir todo", "Play all",
            "Aleatorio", "Shuffle", "Reproducir lista", "Shuffle play",
        )) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (node in nodes) {
                val target = findClickableAncestor(node) ?: node
                if (looksLikePrimaryPlay(target) && performClick(target)) return true
            }
        }
        // Botón verde típico: clickable grande en zona media-baja / derecha
        return clickLargePlayLikeControl(root)
    }

    private fun looksLikePrimaryPlay(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        // Aceptar también iconos Play compactos de Spotify (~40–56 dp)
        if (bounds.width() < 36 || bounds.height() < 36) return false
        // Evitar filas de resultados altas y estrechas
        if (bounds.height() > 220 && bounds.width() > bounds.height() * 2.2f) return false
        val desc = (node.contentDescription?.toString().orEmpty() + " " +
            node.text?.toString().orEmpty()).lowercase()
        if (desc.contains("pausar") || desc.contains("pause")) return false
        return true
    }

    private fun clickLargePlayLikeControl(root: AccessibilityNodeInfo): Boolean {
        val candidates = collectClickableNodes(root)
            .mapNotNull { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val desc = (node.contentDescription?.toString().orEmpty()).lowercase()
                val isPlayish = desc.contains("reproducir") || desc.contains("play") ||
                    desc.contains("aleatori") || desc.contains("shuffle")
                if (!isPlayish) return@mapNotNull null
                if (bounds.width() < 56 || bounds.height() < 56) return@mapNotNull null
                node to bounds
            }
            .sortedByDescending { it.second.width() * it.second.height() }
        return candidates.firstOrNull()?.let { performClick(it.first) } ?: false
    }

    private fun clickSearchTab(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        for (tab in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(tab)
            for (node in nodes) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.top < 520 && performClick(findClickableAncestor(node) ?: node)) {
                    return true
                }
            }
        }
        return false
    }

    private fun clickFirstPlaylistResult(root: AccessibilityNodeInfo): Boolean {
        val candidates = collectClickableNodes(root)
            .mapNotNull { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.top < 240) return@mapNotNull null
                if (bounds.height() < 64 || bounds.width() < 160) return@mapNotNull null
                val desc = (node.contentDescription?.toString().orEmpty() + " " +
                    node.text?.toString().orEmpty()).lowercase()
                // Evitar la barra de búsqueda / filtros
                if (desc.contains("buscar") || desc.contains("search") ||
                    desc.contains("filtro") || desc.contains("cancelar")
                ) {
                    return@mapNotNull null
                }
                val score = when {
                    desc.contains("playlist") || desc.contains("lista de reproducción") ||
                        desc.contains("lista") -> 0
                    desc.contains("álbum") || desc.contains("album") -> 1
                    desc.contains("canción") || desc.contains("song") || desc.contains("tema") -> 2
                    desc.isNotBlank() -> 3
                    else -> 4
                }
                Triple(node, bounds, score)
            }
            .sortedWith(compareBy({ it.third }, { it.second.top }))

        return candidates.firstOrNull()?.let { performClick(it.first) } ?: false
    }

    private fun tryYouTubeAutoplay(root: AccessibilityNodeInfo): AutoplayActionResult {
        val playLabels = listOf("Reproducir video", "Play video", "Ver video", "Watch video")
        for (label in playLabels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (node in nodes) {
                if (performClick(findClickableAncestor(node) ?: node)) {
                    return AutoplayActionResult.DONE
                }
            }
        }

        val candidates = collectClickableNodes(root)
            .mapNotNull { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.height() < 72 || bounds.width() < 180) return@mapNotNull null
                if (bounds.top < 220) return@mapNotNull null
                node to bounds
            }
            .sortedBy { it.second.top }

        for ((node, _) in candidates.take(4)) {
            val desc = node.contentDescription?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            if (desc.contains("minuto", ignoreCase = true) ||
                desc.contains("minute", ignoreCase = true) ||
                text.contains("minuto", ignoreCase = true) ||
                desc.length > 8
            ) {
                if (performClick(node)) return AutoplayActionResult.DONE
            }
        }

        return if (candidates.firstOrNull()?.let { performClick(it.first) } == true) {
            AutoplayActionResult.DONE
        } else {
            AutoplayActionResult.NONE
        }
    }

    private fun clickFirstSearchResultRow(root: AccessibilityNodeInfo, minTop: Int): Boolean {
        val candidates = collectClickableNodes(root)
            .mapNotNull { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.top < minTop) return@mapNotNull null
                if (bounds.height() < 64 || bounds.width() < 160) return@mapNotNull null
                val desc = (node.contentDescription?.toString().orEmpty()).lowercase()
                if (desc.contains("buscar") || desc.contains("search")) return@mapNotNull null
                node to bounds
            }
            .sortedBy { it.second.top }

        return candidates.firstOrNull()?.let { performClick(it.first) } ?: false
    }

    private fun findClickableByContentDescription(
        node: AccessibilityNodeInfo,
        pattern: Regex,
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString().orEmpty()
        if (desc.isNotBlank() && pattern.containsMatchIn(desc)) {
            return findClickableAncestor(node) ?: node.takeIf { it.isClickable }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableByContentDescription(child, pattern)
            if (found != null) return found
            // No recycle: found puede ser child o un descendiente
        }
        return null
    }

    private fun collectClickableNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        collectClickableNodesRecursive(node, results)
        return results
    }

    private fun collectClickableNodesRecursive(
        node: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node.isClickable && node.isVisibleToUser) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableNodesRecursive(child, results)
            // No reciclar aquí: si el hijo está en results, recycle lo invalidaría
            // y el clic de Play/resultado nunca funcionaría.
        }
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            val parent = current.parent
            if (current !== node) current.recycle()
            current = parent
            depth++
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        val clickable = findClickableAncestor(node) ?: return false
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
