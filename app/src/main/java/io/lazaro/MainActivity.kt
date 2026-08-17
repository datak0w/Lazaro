package io.lazaro

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.lazaro.cane.CaneButtonAction
import io.lazaro.cane.CaneTriggerBridge
import io.lazaro.routes.editor.RouteImportService
import io.lazaro.ui.AssistantScreen
import io.lazaro.ui.cane.CaneSetupWizardScreen
import io.lazaro.ui.memory.MemoryManagementScreen
import io.lazaro.ui.sensor.PiHubSetupWizardScreen
import io.lazaro.ui.theme.LazaroTheme
import io.lazaro.voice.TextToSpeechManager
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var caneTriggerBridge: CaneTriggerBridge
    @Inject lateinit var routeImportService: RouteImportService
    @Inject lateinit var textToSpeechManager: TextToSpeechManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LazaroTheme {
                var screen by rememberSaveable { mutableStateOf("assistant") }
                when (screen) {
                    "memory" -> MemoryManagementScreen(
                        onBack = { screen = "assistant" },
                        onOpenCaneWizard = { screen = "cane_wizard" },
                        onOpenHubWizard = { screen = "hub_wizard" },
                    )
                    "cane_wizard" -> CaneSetupWizardScreen(
                        onBack = { screen = "assistant" },
                        onComplete = { screen = "assistant" },
                    )
                    "hub_wizard" -> PiHubSetupWizardScreen(
                        onBack = { screen = "memory" },
                        onComplete = { screen = "memory" },
                    )
                    "pathguide_debug" -> io.lazaro.ui.pathguide.PathGuideDebugScreen(
                        onBack = { screen = "assistant" },
                    )
                    else -> AssistantScreen(
                        onOpenMemory = { screen = "memory" },
                        onOpenCaneWizard = { screen = "cane_wizard" },
                        onOpenPathGuideDebug = { screen = "pathguide_debug" },
                    )
                }
            }
        }
        handleImportIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }

    private fun handleImportIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
        if (uri == null) return
        lifecycleScope.launch {
            val result = routeImportService.importFromUri(uri)
            val msg = result.fold(
                onSuccess = { r ->
                    val verb = if (r.replaced) "actualizada" else "importada"
                    "Ruta ${r.name} $verb. Di llévame a ${r.name}."
                },
                onFailure = { e ->
                    Log.w(TAG, "import falló: ${e.message}")
                    "No pude importar la ruta."
                },
            )
            try {
                textToSpeechManager.initialize()
                textToSpeechManager.speak(msg)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Si el bastón se empareja como teclado HID del sistema, las teclas llegan aquí
     * (Samsung bloquea leer el GATT HID sin BLUETOOTH_PRIVILEGED).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "KeyEvent keyCode=${event.keyCode} scan=${event.scanCode} src=${event.source}")
            val action = when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK,
                -> CaneButtonAction.LISTEN
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_DPAD_DOWN,
                -> CaneButtonAction.CANCEL
                KeyEvent.KEYCODE_DPAD_UP,
                -> CaneButtonAction.WHERE_AM_I
                KeyEvent.KEYCODE_VOLUME_DOWN -> CaneButtonAction.VOLUME_DOWN
                KeyEvent.KEYCODE_VOLUME_UP -> CaneButtonAction.VOLUME_UP
                else -> null
            }
            if (action != null) {
                Log.i(TAG, "KeyEvent → bastón $action")
                caneTriggerBridge.emit(action)
                if (action != CaneButtonAction.VOLUME_UP && action != CaneButtonAction.VOLUME_DOWN) {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val TAG = "LazaroKeys"
    }
}
