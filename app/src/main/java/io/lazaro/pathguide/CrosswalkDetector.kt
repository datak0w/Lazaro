package io.lazaro.pathguide

import kotlin.math.abs

class CrosswalkDetector {
    private var stableFrames = 0
    private var latched = false

    fun detect(gray: ByteArray, width: Int, height: Int): CrosswalkState {
        if (width < 40 || height < 40 || gray.size < width * height) {
            return CrosswalkState()
        }

        val roiTop = (height * 0.55f).toInt()
        val roiBottom = (height * 0.88f).toInt()
        val roiLeft = (width * 0.18f).toInt()
        val roiRight = (width * 0.82f).toInt()

        var stripeRows = 0
        var scannedRows = 0

        var y = roiTop
        while (y < roiBottom) {
            scannedRows++
            var transitions = 0
            var prevDark = false
            var x = roiLeft
            val step = 4
            while (x < roiRight) {
                val idx = y * width + x
                if (idx >= gray.size) break
                val dark = gray[idx].toInt() and 0xFF < 110
                if (x > roiLeft && dark != prevDark) transitions++
                prevDark = dark
                x += step
            }
            if (transitions >= 6) stripeRows++
            y += 6
        }

        val ratio = if (scannedRows > 0) stripeRows.toFloat() / scannedRows else 0f
        val candidate = ratio >= 0.28f

        if (candidate) {
            stableFrames++
        } else {
            stableFrames = (stableFrames - 1).coerceAtLeast(0)
        }

        if (stableFrames >= STABLE_ROWS) {
            latched = true
        } else if (stableFrames == 0) {
            latched = false
        }

        val confidence = ratio.coerceIn(0f, 1f)
        val distanceMeters = if (latched) {
            SpatialPhraseBuilder.estimateMeters(
                proximity = 0.42f + confidence * 0.25f,
                mode = PathGuideMode.NAVEGACION,
            )
        } else {
            0f
        }

        return CrosswalkState(
            detected = latched,
            confidence = confidence,
            distanceMeters = distanceMeters,
        )
    }

    fun reset() {
        stableFrames = 0
        latched = false
    }

    companion object {
        private const val STABLE_ROWS = 3
    }
}
