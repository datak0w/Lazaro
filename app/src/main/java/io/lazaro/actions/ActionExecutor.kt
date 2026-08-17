package io.lazaro.actions

import io.lazaro.audiobook.BookReaderAction
import io.lazaro.assistant.ActiveSessionKind
import io.lazaro.assistant.ActiveSessionTracker
import io.lazaro.contacts.ContactMatch
import io.lazaro.media.MediaCategory
import io.lazaro.media.MediaLauncherAction
import io.lazaro.memory.entity.CustomSkill
import io.lazaro.memory.SkillExecutor
import io.lazaro.messaging.WhatsAppReplyAction
import io.lazaro.messaging.WhatsAppSendIntentDetector
import io.lazaro.messaging.WhatsAppVoiceNoteAction
import io.lazaro.messaging.MessagesIntentDetector
import io.lazaro.phone.IncomingCallMonitor
import io.lazaro.navigation.NavigationContextAction
import io.lazaro.navigation.NavigationSessionManager
import io.lazaro.navigation.NavigationTarget
import io.lazaro.navigation.StreetSideAction
import io.lazaro.news.NewsReaderAction
import io.lazaro.receipt.ReceiptCheckerAction
import io.lazaro.tools.BatteryAction
import io.lazaro.tools.CalculatorAction
import io.lazaro.tools.TimeAction
import io.lazaro.tools.WeatherAction
import io.lazaro.alarm.AlarmAction
import io.lazaro.alarm.ParsedClockTime
import io.lazaro.vision.SceneLookAction
import io.lazaro.pathguide.PathGuideController
import io.lazaro.pathguide.PathGuideMode
import io.lazaro.pathguide.WalkModeAction
import io.lazaro.pathguide.WalkModeIntentDetector
import io.lazaro.pathguide.WalkIntent
import io.lazaro.memory.SavedPlaceRepository
import io.lazaro.routes.RouteAction
import io.lazaro.routes.RouteRepository
import io.lazaro.transit.TransitAction
import io.lazaro.transit.TransitMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionExecutor @Inject constructor(
    private val navigationAction: NavigationAction,
    private val locationAction: LocationAction,
    private val messagesAction: MessagesAction,
    private val callAction: CallAction,
    private val whatsAppReplyAction: WhatsAppReplyAction,
    private val whatsAppVoiceNoteAction: WhatsAppVoiceNoteAction,
    private val incomingCallMonitor: IncomingCallMonitor,
    private val memoryActionHandler: MemoryActionHandler,
    private val skillExecutor: SkillExecutor,
    private val mediaLauncherAction: MediaLauncherAction,
    private val bookReaderAction: BookReaderAction,
    private val transitAction: TransitAction,
    private val newsReaderAction: NewsReaderAction,
    private val weatherAction: WeatherAction,
    private val timeAction: TimeAction,
    private val batteryAction: BatteryAction,
    private val calculatorAction: CalculatorAction,
    private val alarmAction: AlarmAction,
    private val sceneLookAction: SceneLookAction,
    private val receiptCheckerAction: ReceiptCheckerAction,
    private val mapsLaunchDeferrer: MapsLaunchDeferrer,
    private val navigationIntentDetector: NavigationIntentDetector,
    private val walkModeIntentDetector: WalkModeIntentDetector,
    private val walkModeAction: WalkModeAction,
    private val pathGuideController: PathGuideController,
    private val routeAction: RouteAction,
    private val savedPlaceAction: SavedPlaceAction,
    private val savedPlaceRepository: SavedPlaceRepository,
    private val routeRepository: RouteRepository,
    private val activeSessionTracker: ActiveSessionTracker,
    private val navigationSessionManager: NavigationSessionManager,
    private val navigationContextAction: NavigationContextAction,
    private val streetSideAction: StreetSideAction,
) {
    private var pendingConfirmation: PendingAction? = null
    private var pendingSkill: CustomSkill? = null
    private var lastPromptText: String = ""
    private var pendingNavigationTarget: NavigationTarget? = null

    fun hasDeferredMapsLaunch(): Boolean = mapsLaunchDeferrer.hasDeferred()

    suspend fun runDeferredMapsLaunch(): Boolean {
        return mapsLaunchDeferrer.runDeferred()
    }

    fun consumePendingNavigationTarget(): NavigationTarget? {
        val t = pendingNavigationTarget
        pendingNavigationTarget = null
        return t
    }

    fun peekPendingNavigationTarget(): NavigationTarget? = pendingNavigationTarget

    private fun deferMapsLaunch(launch: suspend () -> Boolean) {
        mapsLaunchDeferrer.defer(launch)
    }

    fun getLastPromptText(): String = lastPromptText

    fun getPendingHint(): String {
        return when (pendingConfirmation?.toolName) {
            "select_book" -> "elegir un libro"
            "select_media_app" -> "elegir una app"
            "select_media_search_app" -> "elegir dónde buscar"
            "select_recent_media" -> "elegir música reciente"
            "await_music_query" -> "decir qué música poner"
            "select_transit_stop" -> "elegir una parada"
            "select_contact_call" -> "elegir un contacto"
            ToolName.NavigateTo.id -> "confirmar el destino"
            "navigate_transit_stop" -> "confirmar ir a la parada"
            "plan_transit_route" -> "confirmar la ruta en transporte público"
            "read_book" -> "confirmar el libro"
            "reply_message" -> "confirmar el mensaje de WhatsApp"
            WhatsAppVoiceNoteAction.TOOL_OFFER_VOICE_REPLY -> "responder con mensaje de voz"
            WhatsAppVoiceNoteAction.TOOL_SELECT_VOICE_RECIPIENT -> "elegir contacto de WhatsApp"
            "dictate_whatsapp_text" -> "dictar el mensaje de WhatsApp"
            "launch_media" -> "confirmar abrir la app"
            "search_media" -> "confirmar la búsqueda"
            ToolName.MakeCall.id -> "confirmar la llamada"
            CallAction.TOOL_ANSWER_INCOMING -> "responder o rechazar la llamada"
            "execute_skill" -> "confirmar el skill"
            "confirm_memory", "confirm_skill" -> "confirmar guardar en memoria"
            ToolName.ResumeActiveSession.id -> "reanudar la sesión activa"
            "follow_saved_route" -> "confirmar la ruta guardada"
            "delete_saved_route" -> "confirmar borrar la ruta"
            "save_saved_place" -> "confirmar guardar el sitio"
            "delete_saved_place" -> "confirmar borrar el sitio"
            else -> "tu respuesta"
        }
    }

    private fun storePending(action: PendingAction, prompt: String? = null) {
        pendingConfirmation = action
        if (!prompt.isNullOrBlank()) {
            lastPromptText = prompt
        }
    }

    fun setPendingSkillExecution(skill: CustomSkill, prompt: String) {
        pendingSkill = skill
        storePending(
            PendingAction("execute_skill", mapOf("skill_id" to skill.id.toString())),
            prompt,
        )
    }

    fun setAwaitingMemoryConfirmation(prompt: String) {
        storePending(PendingAction("confirm_memory", emptyMap()), prompt)
    }

    suspend fun tryHandleTransitSelection(userText: String): ActionResult? {
        val pending = pendingConfirmation ?: return null
        if (pending.toolName != "select_transit_stop") return null

        val prep = transitAction.confirmSelection(pending.args, userText)
        if (prep is ActionResult.NeedsConfirmation) {
            storePending(prep.pendingAction, prep.prompt)
        }
        return prep
    }

    suspend fun tryHandleTransitIntent(userText: String): ActionResult? {
        val result = transitAction.tryPrepare(userText) ?: return null
        if (result is ActionResult.NeedsConfirmation) {
            storePending(result.pendingAction, result.prompt)
        }
        return result
    }

    suspend fun tryHandleBookSelection(userText: String): ActionResult? {
        val pending = pendingConfirmation ?: return null
        if (pending.toolName != "select_book") return null

        val prep = bookReaderAction.confirmSelection(pending.args, userText)
        if (prep is ActionResult.NeedsConfirmation) {
            storePending(prep.pendingAction, prep.prompt)
        }
        return prep
    }

    suspend fun tryHandleBookIntent(userText: String): ActionResult? {
        val result = bookReaderAction.tryPrepare(userText) ?: return null
        if (result is ActionResult.NeedsConfirmation) {
            storePending(result.pendingAction, result.prompt)
        }
        return result
    }

    suspend fun tryHandleNewsIntent(userText: String): ActionResult? {
        return newsReaderAction.tryPrepare(userText)
    }

    suspend fun tryHandleWeatherIntent(userText: String): ActionResult? {
        return weatherAction.tryPrepare(userText)
    }

    fun tryHandleTimeIntent(userText: String): ActionResult? {
        return timeAction.tryPrepare(userText)
    }

    suspend fun tryHandleAlarmIntent(userText: String): ActionResult? {
        return alarmAction.tryPrepare(userText)
    }

    suspend fun tryHandleSceneLookIntent(userText: String): ActionResult? {
        return sceneLookAction.tryPrepare(userText)
    }

    suspend fun tryHandleWhereAmIIntent(userText: String): ActionResult? {
        if (!WhereAmIIntentDetector.detect(userText)) return null
        return locationAction.whereAmI()
    }

    fun tryHandleBatteryIntent(userText: String): ActionResult? {
        return batteryAction.tryPrepare(userText)
    }

    fun tryHandleCalculatorIntent(userText: String): ActionResult? {
        return calculatorAction.tryPrepare(userText)
    }

    suspend fun tryHandleReceiptIntent(userText: String): ActionResult? {
        return receiptCheckerAction.tryPrepare(userText)
    }

    suspend fun tryHandleWalkIntent(userText: String): ActionResult? {
        return when (walkModeIntentDetector.detect(userText)) {
            WalkIntent.START -> {
                val result = walkModeAction.start()
                if (result is ActionResult.Success) {
                    activeSessionTracker.start(ActiveSessionKind.WALK, "paseo")
                }
                result
            }
            WalkIntent.STOP -> {
                activeSessionTracker.clear()
                walkModeAction.stop()
            }
            WalkIntent.ENABLE_HARNESS -> walkModeAction.setHarnessMountMode(true)
            WalkIntent.DISABLE_HARNESS -> walkModeAction.setHarnessMountMode(false)
            null -> null
        }
    }

    /** Perdido / rumbo / cuánto falta durante navegación. */
    suspend fun tryHandleNavigationContextIntent(userText: String): ActionResult? {
        return navigationContextAction.handle(userText)
    }

    suspend fun tryHandleRouteIntent(userText: String): ActionResult? {
        streetSideAction.tryPrepare(userText)?.let { result ->
            if (result is ActionResult.NeedsConfirmation) {
                storePending(result.pendingAction, result.prompt)
            }
            return result
        }
        val result = routeAction.tryPrepare(userText) ?: return null
        if (result is ActionResult.NeedsConfirmation) {
            storePending(result.pendingAction, result.prompt)
        }
        return result
    }

    suspend fun tryHandleSavedPlaceIntent(userText: String): ActionResult? {
        val result = savedPlaceAction.tryPrepare(userText) ?: return null
        if (result is ActionResult.NeedsConfirmation) {
            storePending(result.pendingAction, result.prompt)
        }
        return result
    }

    suspend fun tryHandleNavigationIntent(userText: String): ActionResult? {
        val rawDestination = navigationIntentDetector.detectDestination(userText) ?: return null

        routeAction.tryPrepareHybridNavigation(rawDestination)?.let { hybrid ->
            if (hybrid is ActionResult.NeedsConfirmation) {
                storePending(hybrid.pendingAction, hybrid.prompt)
            }
            return hybrid
        }

        savedPlaceRepository.resolvePlace(rawDestination)?.let { place ->
            val prompt = "¿Confirmas que quieres ir a ${place.displayName} a pie?"
            val action = PendingAction(
                ToolName.NavigateTo.id,
                mapOf(
                    "destination" to place.displayName,
                    "latitude" to place.latitude.toString(),
                    "longitude" to place.longitude.toString(),
                ),
            )
            storePending(action, prompt)
            return ActionResult.NeedsConfirmation(prompt = prompt, pendingAction = action)
        }

        val destination = memoryActionHandler.navigateUsingMemory(rawDestination) ?: rawDestination
        val prompt = "¿Confirmas que quieres ir a $destination a pie?"
        val action = PendingAction(ToolName.NavigateTo.id, mapOf("destination" to destination))
        storePending(action, prompt)
        return ActionResult.NeedsConfirmation(prompt = prompt, pendingAction = action)
    }

    suspend fun tryHandleMediaSearchSelection(userText: String): ActionResult? {
        val pending = pendingConfirmation ?: return null
        if (pending.toolName != "select_media_search_app") return null

        val prep = mediaLauncherAction.confirmSearchAppSelection(pending.args, userText)
        if (prep is ActionResult.NeedsConfirmation) {
            storePending(prep.pendingAction, prep.prompt)
        } else {
            pendingConfirmation = null
        }
        return prep
    }

    suspend fun tryHandleMediaSelection(userText: String): ActionResult? {
        val pending = pendingConfirmation ?: return null
        if (pending.toolName != "select_media_app") return null

        val prep = mediaLauncherAction.confirmSelection(pending.args, userText)
        if (prep is ActionResult.NeedsConfirmation) {
            storePending(prep.pendingAction, prep.prompt)
        } else {
            pendingConfirmation = null
        }
        return prep
    }

    suspend fun tryHandleRecentMediaSelection(userText: String): ActionResult? {
        val pending = pendingConfirmation ?: return null
        if (pending.toolName != "select_recent_media") return null

        val prep = mediaLauncherAction.confirmRecentMediaSelection(pending.args, userText)
        if (prep is ActionResult.NeedsConfirmation) {
            storePending(prep.pendingAction, prep.prompt)
        } else {
            pendingConfirmation = null
        }
        return prep
    }

    suspend fun tryHandleAwaitMusicQuery(userText: String): ActionResult? {
        val pending = pendingConfirmation ?: return null
        if (pending.toolName != "await_music_query") return null

        val prep = mediaLauncherAction.confirmAwaitMusicQuery(pending.args, userText)
        if (prep is ActionResult.NeedsConfirmation) {
            storePending(prep.pendingAction, prep.prompt)
        } else {
            pendingConfirmation = null
        }
        return prep
    }

    suspend fun tryHandleMediaIntent(userText: String): ActionResult? {
        val result = mediaLauncherAction.tryPrepare(userText) ?: return null
        if (result is ActionResult.NeedsConfirmation) {
            storePending(result.pendingAction, result.prompt)
        }
        return result
    }

    suspend fun tryHandleCallIntent(userText: String): ActionResult? {
        val contact = CallIntentDetector.detectContactQuery(userText) ?: return null
        val prep = callAction.prepareCall(contact)
        if (prep is ActionResult.NeedsConfirmation) {
            storePending(prep.pendingAction, prep.prompt)
        }
        return prep
    }

    suspend fun tryHandleMessagesIntent(userText: String): ActionResult? {
        if (!MessagesIntentDetector.isReadMessagesRequest(userText)) return null
        val result = messagesAction.readMessages()
        if (result is ActionResult.NeedsConfirmation) {
            storePending(result.pendingAction, result.prompt)
        }
        return result
    }

    suspend fun tryHandleWhatsAppSendIntent(userText: String): ActionResult? {
        val detected = WhatsAppSendIntentDetector.detectRecipient(userText) ?: return null
        val result = if (detected.needsRecipient || detected.recipient.isNullOrBlank()) {
            whatsAppVoiceNoteAction.prepareSendToContact("")
        } else {
            whatsAppVoiceNoteAction.prepareSendToContact(detected.recipient)
        }
        if (result is ActionResult.NeedsConfirmation) {
            storePending(result.pendingAction, result.prompt)
        }
        return result
    }

    fun tryHandleHangupIntent(userText: String): ActionResult? {
        if (!incomingCallMonitor.isInActiveCall()) return null
        if (!WhatsAppSendIntentDetector.isHangupDuringCall(userText)) return null
        return callAction.hangUpActiveCall()
    }

    /** Pendiente de dictado texto WhatsApp (sin número para nota de voz). */
    fun tryHandleWhatsAppTextDictate(userText: String): ActionResult? {
        val pending = pendingConfirmation ?: return null
        if (pending.toolName != "dictate_whatsapp_text") return null
        val message = userText.trim()
        if (message.isBlank()) return ActionResult.Error("No he oído el mensaje. Repítelo.")
        pendingConfirmation = null
        lastPromptText = ""
        val args = pending.args + ("message" to message)
        return whatsAppReplyAction.executeReply(args)
    }

    suspend fun tryHandleWhatsAppVoiceRecipientSelection(userText: String): ActionResult? {
        val pending = pendingConfirmation ?: return null
        if (pending.toolName != WhatsAppVoiceNoteAction.TOOL_SELECT_VOICE_RECIPIENT) {
            return null
        }
        val candidates = pending.args.filterKeys { it.startsWith("candidate_") }
            .toList()
            .sortedBy { it.first }
            .mapNotNull { (_, encoded) ->
                val parts = encoded.split("|", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }

        if (candidates.isNotEmpty()) {
            val index = io.lazaro.voice.VoiceOptionParser.parseIndex(userText, candidates.size)
            val chosen = when {
                index != null && index in candidates.indices -> candidates[index]
                else -> candidates.find { (name, _) ->
                    name.equals(userText.trim(), ignoreCase = true) ||
                        name.contains(userText.trim(), ignoreCase = true)
                }
            }
            if (chosen == null) {
                return ActionResult.Error(
                    "No he entendido. Di el número o el nombre del contacto.",
                )
            }
            pendingConfirmation = null
            lastPromptText = ""
            return whatsAppVoiceNoteAction.armRecordingPrompt(chosen.first, chosen.second)
        }

        pendingConfirmation = null
        lastPromptText = ""
        val result = whatsAppVoiceNoteAction.prepareSendToContact(userText)
        if (result is ActionResult.NeedsConfirmation) {
            storePending(result.pendingAction, result.prompt)
        }
        return result
    }

    fun prepareIncomingCallPrompt(displayName: String, phoneNumber: String): ActionResult {
        val prep = callAction.prepareIncomingAnswer(displayName, phoneNumber)
        if (prep is ActionResult.NeedsConfirmation) {
            storePending(prep.pendingAction, prep.prompt)
        }
        return prep
    }

    fun clearIncomingCallPending() {
        if (pendingConfirmation?.toolName == CallAction.TOOL_ANSWER_INCOMING) {
            pendingConfirmation = null
            lastPromptText = ""
        }
    }

    fun isVoiceNoteRecording(): Boolean = whatsAppVoiceNoteAction.isRecording()

    fun finishVoiceNoteRecording(): ActionResult = whatsAppVoiceNoteAction.finishAndSend()

    fun cancelVoiceNoteRecording(): ActionResult = whatsAppVoiceNoteAction.cancelRecording()

    fun consumeArmedVoiceNoteStart(): Map<String, String>? =
        whatsAppVoiceNoteAction.consumeArmedStart()

    fun beginArmedVoiceNoteCapture(
        scope: kotlinx.coroutines.CoroutineScope,
        args: Map<String, String>,
    ): Boolean = whatsAppVoiceNoteAction.beginMicCapture(scope, args)

    fun isVoiceNoteTimedOut(): Boolean = whatsAppVoiceNoteAction.isTimedOut()

    suspend fun tryHandleContactSelection(userText: String): ActionResult? {
        val pending = pendingConfirmation ?: return null
        if (pending.toolName != "select_contact_call") return null

        val contact = callAction.resolveContactSelection(pending.args, userText)
            ?: return ActionResult.Error(
                "No he entendido tu elección. Di el número o el nombre del contacto.",
            )

        val prep = callAction.requestCallConfirmation(contact)
        if (prep is ActionResult.NeedsConfirmation) {
            storePending(prep.pendingAction, prep.prompt)
        }
        return prep
    }

    suspend fun execute(toolName: String, args: Map<String, String>): ActionResult {
        val tool = ToolName.fromId(toolName)
            ?: return ActionResult.Error("Acción desconocida: $toolName")

        return when (tool) {
            ToolName.NavigateTo -> {
                val rawDestination = args["destination"].orEmpty()
                routeAction.tryPrepareHybridNavigation(rawDestination)?.let { hybrid ->
                    if (hybrid is ActionResult.NeedsConfirmation) {
                        storePending(hybrid.pendingAction, hybrid.prompt)
                    }
                    return hybrid
                }
                savedPlaceRepository.resolvePlace(rawDestination)?.let { place ->
                    val prompt = "¿Confirmas que quieres ir a ${place.displayName} a pie?"
                    val action = PendingAction(
                        toolName,
                        mapOf(
                            "destination" to place.displayName,
                            "latitude" to place.latitude.toString(),
                            "longitude" to place.longitude.toString(),
                        ),
                    )
                    storePending(action, prompt)
                    return ActionResult.NeedsConfirmation(prompt = prompt, pendingAction = action)
                }
                val destination = memoryActionHandler.navigateUsingMemory(rawDestination) ?: rawDestination
                val prompt = "¿Confirmas que quieres ir a $destination a pie?"
                val action = PendingAction(toolName, mapOf("destination" to destination))
                storePending(action, prompt)
                ActionResult.NeedsConfirmation(prompt = prompt, pendingAction = action)
            }
            ToolName.WhereAmI -> locationAction.whereAmI()
            ToolName.WebSearch -> ActionResult.Success("Buscaré eso en internet. Un momento.")
            ToolName.ReadMessages -> {
                val result = messagesAction.readMessages()
                if (result is ActionResult.NeedsConfirmation) {
                    storePending(result.pendingAction, result.prompt)
                }
                result
            }
            ToolName.MakeCall -> {
                val prep = callAction.prepareCall(args["contact_name"].orEmpty())
                if (prep is ActionResult.NeedsConfirmation) {
                    storePending(prep.pendingAction, prep.prompt)
                }
                prep
            }
            ToolName.ReplyMessage -> {
                val prep = whatsAppReplyAction.prepareReply(
                    args["recipient"],
                    args["message"].orEmpty(),
                )
                if (prep is ActionResult.NeedsConfirmation) {
                    storePending(prep.pendingAction, prep.prompt)
                }
                prep
            }
            ToolName.SaveMemory -> memoryActionHandler.saveMemory(args).also { result ->
                if (result is ActionResult.NeedsConfirmation) {
                    storePending(PendingAction("confirm_memory", emptyMap()), result.prompt)
                }
            }
            ToolName.CreateSkill -> memoryActionHandler.createSkill(args).also { result ->
                if (result is ActionResult.NeedsConfirmation) {
                    storePending(PendingAction("confirm_skill", emptyMap()), result.prompt)
                }
            }
            ToolName.RecallMemory -> memoryActionHandler.recallMemory(args["key"].orEmpty())
            ToolName.GetLocationTrail -> memoryActionHandler.getLocationTrail(args["hours"])
            ToolName.PlayMedia -> {
                val query = args["query"].orEmpty().trim()
                val appHint = args["app"].orEmpty().trim()
                if (query.isNotBlank()) {
                    val prep = mediaLauncherAction.prepareSearch(query, appHint)
                    if (prep is ActionResult.NeedsConfirmation) {
                        storePending(prep.pendingAction, prep.prompt)
                    }
                    prep
                } else {
                    val category = resolveMediaCategory(args["media_type"].orEmpty())
                        ?: return ActionResult.Error("No sé si quieres música, noticias, radio, podcast o vídeo.")
                    val prep = mediaLauncherAction.prepareForCategory(category)
                    if (prep is ActionResult.NeedsConfirmation) {
                        storePending(prep.pendingAction, prep.prompt)
                    }
                    prep
                }
            }
            ToolName.FindTransit -> {
                val mode = resolveTransitMode(args["transit_type"].orEmpty())
                val prep = transitAction.prepareFindTransit(mode)
                if (prep is ActionResult.NeedsConfirmation) {
                    storePending(prep.pendingAction, prep.prompt)
                }
                prep
            }
            ToolName.PlanTransitRoute -> {
                val rawDestination = args["destination"].orEmpty()
                val destination = memoryActionHandler.navigateUsingMemory(rawDestination) ?: rawDestination
                val prep = transitAction.prepareTransitRoute(destination)
                if (prep is ActionResult.NeedsConfirmation) {
                    storePending(prep.pendingAction, prep.prompt)
                }
                prep
            }
            ToolName.StartWalkMode -> {
                val result = walkModeAction.start()
                if (result is ActionResult.Success) {
                    activeSessionTracker.start(ActiveSessionKind.WALK, "paseo")
                }
                result
            }
            ToolName.StopWalkMode -> {
                activeSessionTracker.clear()
                walkModeAction.stop()
            }
            ToolName.SetHarnessMountMode -> {
                val raw = args["enabled"].orEmpty().lowercase()
                val enabled = raw == "true" || raw == "1" || raw == "sí" || raw == "si" ||
                    raw == "on" || raw == "activar" || raw == "activado"
                walkModeAction.setHarnessMountMode(enabled)
            }
            ToolName.ListSavedRoutes -> listSavedRoutes()
            ToolName.ListSavedPlaces -> listSavedPlaces()
            ToolName.ResumeActiveSession -> resumeActiveSession()
            ToolName.ManageAlarm -> executeManageAlarm(args)
            ToolName.DescribeScene -> sceneLookAction.describeWhatIsAhead()
        }
    }

    private suspend fun executeManageAlarm(args: Map<String, String>): ActionResult {
        val action = args["action"].orEmpty().lowercase().trim()
        val hour = args["hour"]?.toIntOrNull()
        val minute = args["minute"]?.toIntOrNull() ?: 0
        val fromHour = args["from_hour"]?.toIntOrNull()
        val fromMinute = args["from_minute"]?.toIntOrNull()
        val label = args["label"].orEmpty().ifBlank { "Alarma" }
        return when (action) {
            "set", "poner", "crea", "crear" -> {
                if (hour == null) ActionResult.Error("¿A qué hora pongo la alarma?")
                else alarmAction.setAlarm(hour, minute, label)
            }
            "change", "cambiar", "modificar" -> {
                if (hour == null) ActionResult.Error("¿A qué hora la cambio?")
                else {
                    val from = if (fromHour != null) {
                        ParsedClockTime(fromHour, fromMinute ?: 0)
                    } else {
                        null
                    }
                    alarmAction.changeAlarm(from, ParsedClockTime(hour, minute))
                }
            }
            "cancel", "cancela", "quitar", "borrar" -> {
                val time = if (hour != null) ParsedClockTime(hour, minute) else null
                alarmAction.cancelAlarm(time)
            }
            "stop", "apagar", "parar" -> alarmAction.stopRinging()
            "list", "lista", "listar" -> alarmAction.listAlarms()
            else -> ActionResult.Error("No entiendo esa acción de alarma.")
        }
    }

    fun setPendingResumeSession(prompt: String) {
        storePending(PendingAction(ToolName.ResumeActiveSession.id, emptyMap()), prompt)
    }

    private suspend fun listSavedRoutes(): ActionResult {
        val routes = routeRepository.getAllRoutes()
        if (routes.isEmpty()) {
            return ActionResult.Success("No tienes rutas grabadas todavía.")
        }
        val names = routes.take(10).joinToString(", ") { it.name }
        return ActionResult.Success("Tus rutas: $names.")
    }

    private suspend fun listSavedPlaces(): ActionResult {
        val places = savedPlaceRepository.getAllPlaces()
        if (places.isEmpty()) {
            return ActionResult.Success("No tienes sitios favoritos guardados.")
        }
        val names = places.take(10).joinToString(", ") { place ->
            val addr = place.address?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            "${place.displayName}$addr"
        }
        return ActionResult.Success("Tus sitios: $names.")
    }

    private fun resumeActiveSession(): ActionResult {
        val snap = activeSessionTracker.snapshot()
            ?: return ActionResult.Error("No hay ninguna sesión en pausa para reanudar.")
        return when (snap.kind) {
            ActiveSessionKind.NAVIGATION,
            ActiveSessionKind.ROUTE_REPLAY,
            -> ActionResult.Success(navigationSessionManager.resumeFromChat())
            ActiveSessionKind.WALK -> {
                activeSessionTracker.resumeFromChat()
                if (pathGuideController.currentMode() != PathGuideMode.PASEO) {
                    // PathGuide should still be in PASEO if we only muted chat
                }
                ActionResult.Success("De acuerdo. Seguimos con el paseo.")
            }
            ActiveSessionKind.RECORDING -> {
                activeSessionTracker.resumeFromChat()
                ActionResult.Success("De acuerdo. Seguimos grabando.")
            }
        }
    }

    private fun resolveTransitMode(raw: String): TransitMode {
        return when (raw.lowercase().trim()) {
            "bus", "autobus", "autobús" -> TransitMode.BUS
            "metro", "subte" -> TransitMode.METRO
            "tren", "train", "cercanias" -> TransitMode.TRAIN
            "tranvia", "tranvía", "tram" -> TransitMode.TRAM
            else -> TransitMode.ANY
        }
    }

    private fun resolveMediaCategory(raw: String): MediaCategory? {
        MediaCategory.fromId(raw)?.let { return it }
        return when (raw.lowercase().trim()) {
            "música", "musica", "music" -> MediaCategory.MUSIC
            "noticias", "news" -> MediaCategory.NEWS
            "radio" -> MediaCategory.RADIO
            "podcast" -> MediaCategory.PODCAST
            "vídeo", "video", "youtube" -> MediaCategory.VIDEO
            else -> null
        }
    }

    suspend fun confirmPending(): ActionResult {
        val pending = pendingConfirmation
        if (pending == null) {
            return memoryActionHandler.confirmMemoryProposal()
        }

        pendingConfirmation = null
        lastPromptText = ""
        return when (pending.toolName) {
            "confirm_memory", "confirm_skill" -> memoryActionHandler.confirmMemoryProposal()
            "execute_skill" -> {
                val skill = pendingSkill
                pendingSkill = null
                if (skill == null) {
                    ActionResult.Error("No hay skill pendiente.")
                } else {
                    skillExecutor.execute(skill)
                }
            }
            ToolName.ResumeActiveSession.id -> resumeActiveSession()
            ToolName.NavigateTo.id -> {
                val destination = pending.args["destination"].orEmpty()
                val lat = pending.args["latitude"]?.toDoubleOrNull()
                val lng = pending.args["longitude"]?.toDoubleOrNull()
                if (pathGuideController.currentMode() == PathGuideMode.PASEO) {
                    pathGuideController.stop()
                }
                val location = locationAction.getCurrentLocation()
                pendingNavigationTarget = NavigationTarget(
                    label = destination,
                    latitude = lat,
                    longitude = lng,
                )
                deferMapsLaunch {
                    if (lat != null && lng != null) {
                        navigationAction.launchWalkingNavigationToCoordinates(
                            lat,
                            lng,
                            destination,
                            location?.latitude,
                            location?.longitude,
                        )
                    } else {
                        navigationAction.launchWalkingNavigation(
                            destination,
                            location?.latitude,
                            location?.longitude,
                        )
                    }
                }
                ActionResult.Success(
                    "Vale, te guío a pie hasta $destination.",
                    suspendListening = true,
                )
            }
            "save_saved_place" -> savedPlaceAction.confirmSave(pending.args)
            "delete_saved_place" -> savedPlaceAction.confirmDelete(pending.args)
            "save_street_side" -> streetSideAction.confirmSave(pending.args)
            "follow_saved_route" -> routeAction.confirmFollowSavedRoute(pending.args)
            "delete_saved_route" -> routeAction.confirmDeleteRoute(pending.args)
            ToolName.MakeCall.id -> {
                val contact = ContactMatch(
                    displayName = pending.args["contact_name"].orEmpty(),
                    phoneNumber = pending.args["phone_number"].orEmpty(),
                    source = "confirmado",
                )
                callAction.executeCall(contact)
            }
            CallAction.TOOL_ANSWER_INCOMING -> callAction.answerIncomingCall()
            "reply_message" -> whatsAppReplyAction.executeReply(pending.args)
            WhatsAppVoiceNoteAction.TOOL_OFFER_VOICE_REPLY -> {
                val phone = pending.args["phone_number"].orEmpty()
                val recipient = pending.args["recipient"].orEmpty()
                if (phone.filter { it.isDigit() }.length < 8) {
                    // Sin número: dictado de texto vía notificación / intent
                    val prompt = "Dime el mensaje para $recipient."
                    val action = PendingAction(
                        "dictate_whatsapp_text",
                        pending.args,
                    )
                    storePending(action, prompt)
                    ActionResult.NeedsConfirmation(prompt = prompt, pendingAction = action)
                } else {
                    whatsAppVoiceNoteAction.armRecordingPrompt(recipient, phone)
                }
            }
            "launch_media" -> mediaLauncherAction.confirmLaunch(pending.args)
            "search_media" -> mediaLauncherAction.confirmSearch(pending.args)
            "select_recent_media" -> mediaLauncherAction.confirmRecentMediaSelection(pending.args, "sí")
            "await_music_query" -> ActionResult.Error("Dime un artista, estilo o playlist.")
            "read_book" -> bookReaderAction.confirmRead(pending.args)
            "navigate_transit_stop" -> {
                val stopName = pending.args["name"].orEmpty()
                val lat = pending.args["lat"]?.toDoubleOrNull()
                val lng = pending.args["lng"]?.toDoubleOrNull()
                if (lat == null || lng == null) {
                    ActionResult.Error("No tengo la parada lista.")
                } else {
                    val location = locationAction.getCurrentLocation()
                    pendingNavigationTarget = NavigationTarget(
                        label = stopName,
                        latitude = lat,
                        longitude = lng,
                    )
                    deferMapsLaunch {
                        navigationAction.launchWalkingNavigationToCoordinates(
                            lat,
                            lng,
                            stopName,
                            location?.latitude,
                            location?.longitude,
                        )
                    }
                    ActionResult.Success(
                        "Te guío a pie hasta $stopName.",
                        suspendListening = true,
                    )
                }
            }
            "plan_transit_route" -> {
                val destination = pending.args["destination"].orEmpty()
                val location = locationAction.getCurrentLocation()
                val originLat = location?.latitude
                val originLng = location?.longitude
                deferMapsLaunch {
                    navigationAction.launchTransitRoute(destination, originLat, originLng)
                }
                ActionResult.Success(
                    "Abro la ruta en transporte público hasta $destination.",
                    suspendListening = true,
                )
            }
            else -> ActionResult.Error("Esta acción no requiere confirmación.")
        }
    }

    suspend fun cancelPending(): ActionResult {
        val pending = pendingConfirmation
        val wasResume = pending?.toolName == ToolName.ResumeActiveSession.id
        val wasIncoming = pending?.toolName == CallAction.TOOL_ANSWER_INCOMING
        pendingConfirmation = null
        pendingSkill = null
        pendingNavigationTarget = null
        mapsLaunchDeferrer.clear()
        lastPromptText = ""
        whatsAppVoiceNoteAction.cancelArmed()
        if (whatsAppVoiceNoteAction.isRecording()) {
            whatsAppVoiceNoteAction.cancelRecording()
        }
        memoryActionHandler.rejectMemoryProposal()
        if (wasIncoming) {
            return callAction.rejectIncomingCall()
        }
        if (wasResume) {
            val kind = activeSessionTracker.snapshot()?.kind
            when (kind) {
                ActiveSessionKind.NAVIGATION,
                ActiveSessionKind.ROUTE_REPLAY,
                -> {
                    navigationSessionManager.endSession(speakConfirmation = false)
                    return ActionResult.Success("Vale, cancelo la navegación.")
                }
                ActiveSessionKind.WALK -> {
                    activeSessionTracker.clear()
                    walkModeAction.stop()
                    return ActionResult.Success("Vale, paro el paseo.")
                }
                ActiveSessionKind.RECORDING -> {
                    activeSessionTracker.clear()
                    return ActionResult.Success("Vale, dejo la grabación en pausa. Di para de grabar si quieres guardarla.")
                }
                null -> Unit
            }
        }
        return ActionResult.Success(
            if (pending != null) {
                "Cancelado."
            } else {
                "De acuerdo, no lo guardaré."
            },
        )
    }

    fun hasPendingConfirmation(): Boolean = pendingConfirmation != null

    fun isAffirmative(text: String): Boolean {
        val normalized = normalizeResponse(text)
        if (normalized.isBlank()) return false
        if (AFFIRMATIVE.contains(normalized)) return true
        return AFFIRMATIVE_PREFIXES.any { prefix ->
            normalized == prefix || normalized.startsWith("$prefix ")
        }
    }

    fun isNegative(text: String): Boolean {
        val normalized = normalizeResponse(text)
        if (normalized.isBlank()) return false
        if (UNCERTAIN_DENY.any { normalized.contains(it) }) return false
        if (NEGATIVE_EXACT.contains(normalized)) return true
        return NEGATIVE_PREFIXES.any { prefix ->
            normalized == prefix || normalized.startsWith("$prefix ")
        }
    }

    private fun normalizeResponse(text: String): String {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val AFFIRMATIVE = setOf(
            "si", "confirmo", "confirmar", "vale", "ok", "de acuerdo", "yes",
            "claro", "adelante", "por supuesto", "correcto", "afirmativo",
            "responde", "responder", "contesta", "contestar", "acepta", "aceptar",
            "coge", "coger", "cogelo", "cogela", "cogelos",
            "descuélgame", "descuelga", "atiende", "atiendela",
        )
        private val AFFIRMATIVE_PREFIXES = listOf(
            "si", "vale", "ok", "claro", "de acuerdo", "por supuesto",
            "responde", "contesta", "acepta", "coge", "cogelo", "atiende",
        )
        private val NEGATIVE_EXACT = setOf(
            "no", "nope", "negativo", "cancelar", "cancela", "nel", "paso",
            "rechaza", "rechazar", "cuelga", "colgar", "ignora", "ignorar",
        )
        private val NEGATIVE_PREFIXES = listOf(
            "no", "cancela", "cancelar", "rechaza", "cuelga", "ignora",
        )
        private val UNCERTAIN_DENY = listOf(
            "no se", "no lo se", "no entend", "no estoy seguro",
        )
    }
}
