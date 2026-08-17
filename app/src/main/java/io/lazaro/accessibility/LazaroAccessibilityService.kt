package io.lazaro.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
import io.lazaro.media.MediaAutoplayCoordinator
import io.lazaro.media.MediaAutoplayPhase
import io.lazaro.messaging.entity.MessageApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@AndroidEntryPoint
class LazaroAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mediaAutoplayAttempts = mutableMapOf<String, Int>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank()) return

        if (WhatsAppSendCoordinator.pendingSend.get() && packageName in WHATSAPP_PACKAGES) {
            scope.launch {
                delay(600)
                val root = rootInActiveWindow ?: return@launch
                if (clickSendButton(root)) {
                    WhatsAppSendCoordinator.pendingSend.set(false)
                }
                root.recycle()
            }
            return
        }

        val pendingAutoplay = MediaAutoplayCoordinator.peek(packageName) ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }

        val attempts = mediaAutoplayAttempts.getOrDefault(packageName, 0)
        if (attempts >= 24) {
            MediaAutoplayCoordinator.markCompleted()
            mediaAutoplayAttempts.remove(packageName)
            return
        }
        mediaAutoplayAttempts[packageName] = attempts + 1

        scope.launch {
            val delayMs = when {
                attempts == 0 -> 2_200L
                attempts < 3 -> 1_400L
                attempts < 8 -> 1_000L
                else -> 800L
            }
            delay(delayMs)
            val root = rootInActiveWindow ?: return@launch
            val pending = MediaAutoplayCoordinator.peek(packageName)
            if (pending == null) {
                root.recycle()
                return@launch
            }
            when (
                MediaAutoplayAccessibility.tryAutoplay(
                    packageName,
                    root,
                    pending.phase,
                )
            ) {
                AutoplayActionResult.DONE -> {
                    MediaAutoplayCoordinator.markCompleted()
                    mediaAutoplayAttempts.remove(packageName)
                }
                AutoplayActionResult.PROGRESS -> {
                    MediaAutoplayCoordinator.advancePhase(
                        packageName,
                        MediaAutoplayPhase.OPENED_ITEM,
                    )
                    // Tras abrir un resultado, reintentar Play enseguida
                    mediaAutoplayAttempts[packageName] =
                        mediaAutoplayAttempts.getOrDefault(packageName, 0).coerceAtMost(4)
                }
                AutoplayActionResult.NONE -> Unit
            }
            root.recycle()
        }
    }

    override fun onInterrupt() = Unit

    private fun clickSendButton(root: AccessibilityNodeInfo): Boolean {
        val sendNode = findSendButton(root)
        if (sendNode != null) {
            val clicked = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            sendNode.recycle()
            return clicked
        }
        return false
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val sendLabels = listOf("Enviar", "Send", "enviar", "send", "Enviar mensaje", "Send message")
        for (label in sendLabels) {
            val nodes = node.findAccessibilityNodeInfosByText(label)
            for (n in nodes) {
                if (n.isClickable) return n
                val parent = n.parent
                if (parent?.isClickable == true) {
                    n.recycle()
                    return parent
                }
                n.recycle()
            }
        }

        val contentDescCandidates = listOf("Enviar", "Send", "enviar")
        for (desc in contentDescCandidates) {
            val found = findByContentDescription(node, desc)
            if (found != null) return found
        }
        return null
    }

    private fun findByContentDescription(
        node: AccessibilityNodeInfo,
        description: String,
    ): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString()?.contains(description, ignoreCase = true) == true &&
            node.isClickable
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByContentDescription(child, description)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    companion object {
        private val WHATSAPP_PACKAGES = setOf(
            MessageApps.WHATSAPP,
            MessageApps.WHATSAPP_BUSINESS,
        )
    }
}

object WhatsAppSendCoordinator {
    val pendingSend = AtomicBoolean(false)

    fun requestSend() {
        pendingSend.set(true)
    }
}
