package io.lazaro.routes.editor

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.routes.RouteRepository
import io.lazaro.routes.entity.SavedRoute
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouteImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routeRepository: RouteRepository,
) {
    data class ImportResult(
        val routeId: Long,
        val name: String,
        val announcementCount: Int,
        val crossingCount: Int,
        val replaced: Boolean,
    )

    suspend fun importFromUri(uri: Uri): Result<ImportResult> {
        return try {
            val text = readText(uri) ?: return Result.failure(IllegalArgumentException("Archivo vacío"))
            importFromJson(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importFromJson(raw: String): Result<ImportResult> {
        val doc = LazaroRouteDocumentCodec.fromJson(raw)
            ?: return Result.failure(IllegalArgumentException("JSON no válido (formato lazaro-route)"))
        if (doc.waypoints.size < 2) {
            return Result.failure(IllegalArgumentException("La ruta necesita al menos 2 puntos"))
        }
        val existing = routeRepository.findRouteByNameOrLabel(doc.name)
        val route = LazaroRouteDocumentCodec.toSavedRoute(doc, existingId = existing?.id ?: 0)
        val id = if (existing != null) {
            routeRepository.updateRoute(route.copy(id = existing.id, createdAt = existing.createdAt))
            existing.id
        } else {
            // Cap de 20 rutas: si lleno, borrar la más antigua de baja calidad
            val all = routeRepository.getAllRoutes()
            if (all.size >= 20) {
                all.minByOrNull { it.updatedAt }?.let { routeRepository.deleteRoute(it.id) }
            }
            routeRepository.insertRoute(route)
        }
        val key = route.destinationKey
        if (!key.isNullOrBlank()) {
            routeRepository.linkMemory(key, id)
            routeRepository.linkMemory(doc.name.lowercase(), id)
        }
        return Result.success(
            ImportResult(
                routeId = id,
                name = doc.name,
                announcementCount = doc.announcements.size,
                crossingCount = doc.crossings.size,
                replaced = existing != null,
            ),
        )
    }

    private fun readText(uri: Uri): String? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }
    }
}
