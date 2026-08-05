package io.lazaro.navigation

/**
 * Destino de una sesión de navegación a pie.
 * [latitude]/[longitude] pueden ser null si solo tenemos el nombre (geocode diferido).
 */
data class NavigationTarget(
    val label: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)
