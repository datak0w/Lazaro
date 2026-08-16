package io.lazaro.directions

import com.google.gson.annotations.SerializedName

data class DirectionsResponse(
    @SerializedName("routes") val routes: List<Route>,
    @SerializedName("status") val status: String,
    @SerializedName("error_message") val errorMessage: String? = null,
)

data class Route(
    @SerializedName("legs") val legs: List<Leg>,
    @SerializedName("overview_polyline") val overviewPolyline: Polyline? = null,
    @SerializedName("bounds") val bounds: Bounds? = null,
    @SerializedName("copyrights") val copyrights: String? = null,
    @SerializedName("summary") val summary: String? = null,
    @SerializedName("warnings") val warnings: List<String>? = null,
)

data class Leg(
    @SerializedName("steps") val steps: List<Step>,
    @SerializedName("distance") val distance: TextValue? = null,
    @SerializedName("duration") val duration: TextValue? = null,
    @SerializedName("start_location") val startLocation: LatLngLiteral? = null,
    @SerializedName("end_location") val endLocation: LatLngLiteral? = null,
    @SerializedName("start_address") val startAddress: String? = null,
    @SerializedName("end_address") val endAddress: String? = null,
)

data class Step(
    @SerializedName("html_instructions") val htmlInstructions: String? = null,
    @SerializedName("distance") val distance: TextValue? = null,
    @SerializedName("duration") val duration: TextValue? = null,
    @SerializedName("start_location") val startLocation: LatLngLiteral? = null,
    @SerializedName("end_location") val endLocation: LatLngLiteral? = null,
    @SerializedName("polyline") val polyline: Polyline? = null,
    @SerializedName("maneuver") val maneuver: String? = null,
    @SerializedName("travel_mode") val travelMode: String? = null,
    @SerializedName("steps") val subSteps: List<Step>? = null,
)

data class TextValue(
    @SerializedName("text") val text: String? = null,
    @SerializedName("value") val value: Int? = null,
)

data class Polyline(
    @SerializedName("points") val points: String? = null,
)

data class LatLngLiteral(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double,
)

data class Bounds(
    @SerializedName("northeast") val northeast: LatLngLiteral? = null,
    @SerializedName("southwest") val southwest: LatLngLiteral? = null,
)

fun Step.cleanInstruction(): String {
    val raw = htmlInstructions ?: maneuver ?: "Continúa"
    return raw
        .replace(Regex("<[^>]*>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun Step.isArrivalStep(): Boolean {
    val m = maneuver?.lowercase() ?: ""
    val html = htmlInstructions?.lowercase() ?: ""
    return m.contains("arrive") || html.contains("has llegado") || html.contains("destino")
}
