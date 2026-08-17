package io.lazaro.routes.editor

import io.lazaro.pathguide.DoorwayVoiceCue
import io.lazaro.routes.RouteRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispara anuncios del editor («frente al cementerio…») al pasar cerca en modo RUTA.
 */
@Singleton
class RouteAnnouncementEngine @Inject constructor(
    private val routeRepository: RouteRepository,
) {
    private var announcements: List<LazaroRouteDocument.RouteAnnouncement> = emptyList()
    private var crossings: List<LazaroRouteDocument.RouteCrossing> = emptyList()
    private val spokenIds = mutableSetOf<String>()
    private var lastCueMs = 0L

    suspend fun loadRoute(routeId: Long) {
        spokenIds.clear()
        lastCueMs = 0L
        val route = routeRepository.getRoute(routeId)
        val doc = route?.let { LazaroRouteDocumentCodec.parseStoredDocument(it) }
        announcements = doc?.announcements.orEmpty()
        crossings = doc?.crossings.orEmpty()
    }

    fun reset() {
        announcements = emptyList()
        crossings = emptyList()
        spokenIds.clear()
        lastCueMs = 0L
    }

    fun maybeCue(lat: Double, lng: Double, now: Long = System.currentTimeMillis()): DoorwayVoiceCue? {
        if (now - lastCueMs < MIN_GAP_MS) return null

        for (ann in announcements) {
            val id = ann.id.ifBlank { "ann-${ann.lat}-${ann.lng}" }
            if (id in spokenIds) continue
            val d = LazaroRouteDocumentCodec.haversineM(lat, lng, ann.lat, ann.lng)
            if (d <= ann.radiusM) {
                spokenIds.add(id)
                lastCueMs = now
                return DoorwayVoiceCue(
                    message = ann.text,
                    debounceMs = MIN_GAP_MS,
                    cueId = "route_ann_$id",
                )
            }
        }

        for (c in crossings) {
            val id = "cross-${c.lat}-${c.lng}"
            if (id in spokenIds) continue
            val d = LazaroRouteDocumentCodec.haversineM(lat, lng, c.lat, c.lng)
            if (d <= c.radiusM) {
                spokenIds.add(id)
                lastCueMs = now
                val label = c.label.ifBlank { "Paso de cebra" }
                return DoorwayVoiceCue(
                    message = "Ahora tienes que cruzar. $label cerca. Cruza con cuidado.",
                    debounceMs = MIN_GAP_MS,
                    cueId = "route_cross_$id",
                )
            }
        }
        return null
    }

    companion object {
        private const val MIN_GAP_MS = 8_000L
    }
}
