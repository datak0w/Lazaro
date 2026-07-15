package io.lazaro.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object MediaAutoplayAccessibility {

    private val SPOTIFY_PACKAGES = setOf("com.spotify.music", "com.spotify.lite")
    private val YOUTUBE_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
    )

    fun tryAutoplay(packageName: String, root: AccessibilityNodeInfo): Boolean {
        return when (packageName) {
            in SPOTIFY_PACKAGES -> trySpotifyAutoplay(root)
            in YOUTUBE_PACKAGES -> tryYouTubeAutoplay(root)
            else -> false
        }
    }

    private fun trySpotifyAutoplay(root: AccessibilityNodeInfo): Boolean {
        val playPatterns = listOf(
            Regex("(?i)^reproducir\\b"),
            Regex("(?i)^play\\b"),
            Regex("(?i)reproducir .*"),
            Regex("(?i)play .*"),
        )

        for (pattern in playPatterns) {
            findClickableByContentDescription(root, pattern)?.let { node ->
                if (performClick(node)) return true
            }
        }

        for (label in listOf("Reproducir", "Play")) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (node in nodes) {
                if (performClick(findClickableAncestor(node) ?: node)) return true
            }
        }

        return clickFirstSearchResultRow(root, minTop = 180)
    }

    private fun tryYouTubeAutoplay(root: AccessibilityNodeInfo): Boolean {
        val playLabels = listOf("Reproducir video", "Play video", "Ver video", "Watch video")
        for (label in playLabels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (node in nodes) {
                if (performClick(findClickableAncestor(node) ?: node)) return true
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
                if (performClick(node)) return true
            }
        }

        return candidates.firstOrNull()?.let { performClick(it.first) } ?: false
    }

    private fun clickFirstSearchResultRow(root: AccessibilityNodeInfo, minTop: Int): Boolean {
        val candidates = collectClickableNodes(root)
            .mapNotNull { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.top < minTop) return@mapNotNull null
                if (bounds.height() < 48 || bounds.width() < 120) return@mapNotNull null
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
            child.recycle()
            if (found != null) return found
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
            child.recycle()
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
