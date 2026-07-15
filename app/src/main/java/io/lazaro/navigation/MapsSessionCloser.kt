package io.lazaro.navigation

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.MainActivity
import io.lazaro.messaging.LazaroNotificationListenerService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MapsSessionCloser @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun closeMapsNavigation(): Boolean {
        return LazaroNotificationListenerService.closeMapsNavigation()
    }

    fun bringLazaroToFront() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
