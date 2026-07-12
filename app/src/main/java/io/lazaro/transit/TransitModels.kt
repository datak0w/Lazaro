package io.lazaro.transit

enum class TransitMode(val spokenLabel: String) {
    ANY("transporte público"),
    BUS("autobús"),
    METRO("metro"),
    TRAIN("tren"),
    TRAM("tranvía"),
}

data class TransitStop(
    val name: String,
    val type: TransitMode,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int,
    val lineInfo: String?,
)
