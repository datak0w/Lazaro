package io.lazaro.pathguide

class OpeningDetector(
    private val doorwayDetector: DoorwayDetector = DoorwayDetector(),
) {

    fun detect(gray: ByteArray, width: Int, height: Int): OpeningCandidate {
        val doorway = doorwayDetector.detect(gray, width, height)
        if (!doorway.detected) {
            return OpeningCandidate(confidence = doorway.confidence)
        }

        return OpeningCandidate(
            type = classifyType(doorway),
            detected = true,
            confidence = doorway.confidence,
            leftJambNorm = doorway.leftJambNorm,
            rightJambNorm = doorway.rightJambNorm,
            centerNorm = doorway.centerNorm,
            openingWidthNorm = doorway.openingWidthNorm,
            approachFactor = doorway.approachFactor,
            leftProximity = doorway.leftProximity,
            rightProximity = doorway.rightProximity,
            isCentered = doorway.isCentered,
        )
    }

    fun reset() {
        doorwayDetector.reset()
    }

    private fun classifyType(doorway: DoorwayState): OpeningType {
        return when {
            doorway.openingWidthNorm >= ARCH_WIDTH_NORM -> OpeningType.ARCH
            doorway.openingWidthNorm >= CORRIDOR_END_WIDTH_NORM &&
                doorway.centerNorm in 0.38f..0.62f -> OpeningType.CORRIDOR_END
            else -> OpeningType.DOOR
        }
    }

    companion object {
        private const val ARCH_WIDTH_NORM = 0.48f
        private const val CORRIDOR_END_WIDTH_NORM = 0.40f
    }
}
