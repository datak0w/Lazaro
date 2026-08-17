package io.lazaro.media

import java.util.concurrent.atomic.AtomicReference

enum class MediaAutoplayPhase {
    /** Acaba de abrir Spotify/búsqueda. */
    OPENED,
    /** Ya pulsó pestaña Listas o un resultado; falta Play. */
    OPENED_ITEM,
}

data class PendingMediaAutoplay(
    val packageName: String,
    val query: String,
    val requestedAtMs: Long,
    val phase: MediaAutoplayPhase = MediaAutoplayPhase.OPENED,
)

object MediaAutoplayCoordinator {
    private val pending = AtomicReference<PendingMediaAutoplay?>(null)

    private val SUPPORTED_PACKAGES = setOf(
        "com.spotify.music",
        "com.spotify.lite",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
    )

    fun request(packageName: String, query: String) {
        if (packageName !in SUPPORTED_PACKAGES) return
        pending.set(
            PendingMediaAutoplay(
                packageName = packageName,
                query = query.trim(),
                requestedAtMs = System.currentTimeMillis(),
                phase = MediaAutoplayPhase.OPENED,
            ),
        )
    }

    fun peek(packageName: String): PendingMediaAutoplay? {
        val current = pending.get() ?: return null
        if (current.packageName != packageName) return null
        if (isExpired(current)) {
            pending.compareAndSet(current, null)
            return null
        }
        return current
    }

    fun advancePhase(packageName: String, phase: MediaAutoplayPhase) {
        val current = peek(packageName) ?: return
        pending.compareAndSet(
            current,
            current.copy(phase = phase, requestedAtMs = System.currentTimeMillis()),
        )
    }

    fun clear() {
        pending.set(null)
    }

    fun markCompleted() {
        pending.set(null)
    }

    private fun isExpired(request: PendingMediaAutoplay): Boolean {
        // Más margen: hay que abrir búsqueda → lista → Play
        return System.currentTimeMillis() - request.requestedAtMs > 35_000L
    }
}
