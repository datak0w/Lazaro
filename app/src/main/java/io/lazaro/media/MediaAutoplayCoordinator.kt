package io.lazaro.media

import java.util.concurrent.atomic.AtomicReference

data class PendingMediaAutoplay(
    val packageName: String,
    val query: String,
    val requestedAtMs: Long,
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

    fun clear() {
        pending.set(null)
    }

    fun markCompleted() {
        pending.set(null)
    }

    private fun isExpired(request: PendingMediaAutoplay): Boolean {
        return System.currentTimeMillis() - request.requestedAtMs > 18_000L
    }
}
