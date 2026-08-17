package io.lazaro.phone

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.contacts.ContactResolver
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class IncomingCallEvent(
    val phoneNumber: String,
    val displayName: String,
)

enum class CallLifecycleEvent {
    RINGING,
    /** Llamada en curso (contestada o saliente). */
    OFFHOOK,
    /** Sin llamada. */
    IDLE,
}

/**
 * Escucha llamadas entrantes y emite quién llama (contacto o número).
 */
@Singleton
class IncomingCallMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactResolver: ContactResolver,
) {
    private val _incoming = MutableSharedFlow<IncomingCallEvent>(extraBufferCapacity = 2)
    val incomingCalls: SharedFlow<IncomingCallEvent> = _incoming.asSharedFlow()

    private val _lifecycle = MutableSharedFlow<CallLifecycleEvent>(extraBufferCapacity = 4)
    val lifecycle: SharedFlow<CallLifecycleEvent> = _lifecycle.asSharedFlow()

    @Volatile
    private var started = false

    @Volatile
    private var lastRingKey: String = ""

    @Volatile
    private var lastRingAtMs: Long = 0L

    @Volatile
    private var inActiveCall: Boolean = false

    @Volatile
    private var isRinging: Boolean = false

    fun isInActiveCall(): Boolean {
        refreshFromTelephony()
        return inActiveCall
    }

    fun isRinging(): Boolean {
        refreshFromTelephony()
        return isRinging
    }

    /** Suena o hay llamada en curso: hay que poder colgar/rechazar. */
    fun isCallSessionActive(): Boolean {
        refreshFromTelephony()
        return isRinging || inActiveCall
    }

    /**
     * Alinea flags internos con [TelephonyManager] (evita OFFHOOK atascado
     * que silencia mic/TTS tras un hook de auricular fallido).
     */
    fun refreshFromTelephony() {
        if (!hasPhoneStatePermission()) return
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        when (tm.callState) {
            TelephonyManager.CALL_STATE_RINGING -> {
                isRinging = true
                inActiveCall = false
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                isRinging = false
                inActiveCall = true
            }
            else -> {
                isRinging = false
                inActiveCall = false
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE).orEmpty()
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    isRinging = true
                    inActiveCall = false
                    val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                        .orEmpty()
                        .trim()
                    onRinging(rawNumber)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    isRinging = false
                    inActiveCall = true
                    lastRingKey = ""
                    _lifecycle.tryEmit(CallLifecycleEvent.OFFHOOK)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    isRinging = false
                    inActiveCall = false
                    lastRingKey = ""
                    _lifecycle.tryEmit(CallLifecycleEvent.IDLE)
                }
            }
        }
    }

    fun start() {
        if (started) return
        if (!hasPhoneStatePermission()) return
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            started = true
            refreshFromTelephony()
        } catch (_: Exception) {
            started = false
        }
    }

    fun stop() {
        if (!started) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
            // already unregistered
        }
        started = false
        inActiveCall = false
        isRinging = false
    }

    private fun onRinging(rawNumber: String) {
        val now = System.currentTimeMillis()
        val key = rawNumber.ifBlank { "unknown" }
        if (key == lastRingKey && now - lastRingAtMs < 4_000L) return
        lastRingKey = key
        lastRingAtMs = now
        isRinging = true
        inActiveCall = false

        _lifecycle.tryEmit(CallLifecycleEvent.RINGING)

        val displayName = if (rawNumber.isNotBlank()) {
            contactResolver.lookupByPhone(rawNumber)?.displayName
                ?: formatUnknownNumber(rawNumber)
        } else {
            "número desconocido"
        }

        _incoming.tryEmit(
            IncomingCallEvent(
                phoneNumber = rawNumber,
                displayName = displayName,
            ),
        )
    }

    private fun formatUnknownNumber(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 6) return "número desconocido"
        val spoken = digits.takeLast(9).map { it.toString() }.joinToString(" ")
        return "el $spoken"
    }

    private fun hasPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
