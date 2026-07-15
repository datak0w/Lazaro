package io.lazaro.navigation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Activity transparente que lanza Google Maps desde primer plano.
 * Necesaria porque Android bloquea startActivity() desde servicios en segundo plano.
 */
class MapsLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            launched = savedInstanceState.getBoolean(KEY_LAUNCHED, false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (launched) return
        launched = true

        val intents = Companion.pendingIntents.orEmpty()
        var success = false
        for (candidate in intents) {
            try {
                startActivity(
                    candidate.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    ),
                )
                success = true
                Log.i(TAG, "Maps intent launched: ${candidate.data}")
                break
            } catch (e: Exception) {
                Log.w(TAG, "Maps intent failed: ${candidate.data}", e)
            }
        }

        Companion.completeLaunch(success)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED, launched)
    }

    private var launched = false

    companion object {
        private const val TAG = "MapsLaunchActivity"
        private const val KEY_LAUNCHED = "maps_launched"

        private val mutex = Mutex()
        private var pendingIntents: List<Intent>? = null
        private var continuation: CancellableContinuation<Boolean>? = null

        suspend fun launch(context: Context, intents: List<Intent>): Boolean {
            if (intents.isEmpty()) return false

            return mutex.withLock {
                suspendCancellableCoroutine { cont ->
                    continuation = cont
                    pendingIntents = intents
                    cont.invokeOnCancellation {
                        pendingIntents = null
                        continuation = null
                    }

                    val launchIntent = Intent(context, MapsLaunchActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                        )
                    }
                    try {
                        context.startActivity(launchIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not start MapsLaunchActivity", e)
                        pendingIntents = null
                        continuation = null
                        cont.resume(false)
                    }
                }
            }
        }

        private fun completeLaunch(success: Boolean) {
            pendingIntents = null
            continuation?.resume(success)
            continuation = null
        }
    }
}
