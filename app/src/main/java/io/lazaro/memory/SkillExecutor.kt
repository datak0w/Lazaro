package io.lazaro.memory

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.actions.ActionResult
import io.lazaro.actions.MapsLaunchDeferrer
import io.lazaro.actions.NavigationAction
import io.lazaro.memory.entity.SkillActionType
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val navigationAction: NavigationAction,
    private val mapsLaunchDeferrer: MapsLaunchDeferrer,
) {
    suspend fun execute(skill: io.lazaro.memory.entity.CustomSkill): ActionResult {
        memoryRepository.incrementSkillUse(skill.id)
        return when (skill.actionType) {
            SkillActionType.OPEN_APP -> openApp(skill.actionPayload)
            SkillActionType.CALL_PHONE -> callPhone(skill.actionPayload)
            SkillActionType.NAVIGATE -> {
                val destination = memoryRepository.resolveMemoryValue(skill.actionPayload)
                    ?: skill.actionPayload
                mapsLaunchDeferrer.defer {
                    navigationAction.launchWalkingNavigation(destination)
                }
                ActionResult.Success(
                    "Te guío a pie hasta $destination. Abro Google Maps con voz. " +
                        "Me callo para que oigas a Maps.",
                    suspendListening = true,
                )
            }
            SkillActionType.OPEN_URL -> openUrl(skill.actionPayload)
            SkillActionType.RECALL_MEMORY -> {
                val value = memoryRepository.resolveMemoryValue(skill.actionPayload)
                    ?: return ActionResult.Error("No tengo guardado ${skill.actionPayload}.")
                ActionResult.Success(value)
            }
            else -> ActionResult.Error("Skill no soportado: ${skill.actionType}")
        }
    }

    private fun openApp(payload: String): ActionResult {
        val packageName = parsePayload(payload, "package") ?: payload
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Error("No encuentro la app $packageName.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return ActionResult.Success("Abriendo $packageName.")
    }

    private suspend fun callPhone(payload: String): ActionResult {
        val number = memoryRepository.resolveMemoryValue(payload) ?: parsePayload(payload, "phone") ?: payload
        val intent = Intent(Intent.ACTION_DIAL, "tel:$number".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult.Success("Abriendo llamada a $number.")
    }

    private fun openUrl(payload: String): ActionResult {
        val url = parsePayload(payload, "url") ?: payload
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult.Success("Abriendo enlace.")
    }

    private fun parsePayload(payload: String, key: String): String? {
        return try {
            JSONObject(payload).optString(key).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
