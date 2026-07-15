package io.lazaro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import io.lazaro.ui.AssistantScreen
import io.lazaro.ui.cane.CaneSetupWizardScreen
import io.lazaro.ui.sensor.PiHubSetupWizardScreen
import io.lazaro.ui.memory.MemoryManagementScreen
import io.lazaro.ui.theme.LazaroTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
    }
}
