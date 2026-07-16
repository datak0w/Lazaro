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
import io.lazaro.navigation.NavigationSessionManager
import io.lazaro.news.NewsReaderAction
import io.lazaro.receipt.ReceiptCheckerAction
import io.lazaro.tools.CalculatorAction
import io.lazaro.tools.TimeAction
import io.lazaro.tools.WeatherAction
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
    private val memoryActionHandler: MemoryActionHandler,
    private val skillExecutor: SkillExecutor,
    private val mediaLauncherAction: MediaLauncherAction,
    private val bookReaderAction: BookReaderAction,
    private val transitAction: TransitAction,
    private val newsReaderAction: NewsReaderAction,
    private val weatherAction: WeatherAction,
    private val timeAction: TimeAction,
    private val calculatorAction: CalculatorAction,
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
) {
    private var pendingConfirmation: PendingAction? = null
    private var pendingSkill: CustomSkill? = null
    private var lastPromptText: String = ""

    fun hasDeferredMapsLaunch(): Boolean = mapsLaunchDeferrer.hasDeferred()

    suspend fun runDeferredMapsLaunch(): Boolean {
        return mapsLaunchDeferrer.runDeferred()
    }

    private fun deferMapsLaunch(launch: suspend () -> Boolean) {
        mapsLaunchDeferrer.defer(launch)
    }

    fun getLastPromptText(): String = lastPromptText

    fun getPendingHint(): String {
        return when (pendingConfirmation?.toolName) {
            "select_book" -> "elegir un libro"
            "select_media_app" -> "elegir una app"
            "select_media_search_app" -> "elegir dónde buscar"
            "select_transit_stop" -> "elegir una parada"
            "select_contact_call" -> "elegir un contacto"
            ToolName.NavigateTo.id -> "confirmar el destino"
            "navigate_transit_stop" -> "confirmar ir a la parada"
            "plan_transit_route" -> "confirmar la ruta en transporte público"
            "read_book" -> "confirmar el libro"
            "reply_message" -> "confirmar el mensaje de WhatsApp"
            "launch_media" -> "confirmar abrir la app"
            "search_media" -> "confirmar la búsqueda"
            ToolName.MakeCall.id -> "confirmar la llamada"
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

    fun tryHandleCalculatorIntent(userText: String): ActionResult? {
        return calculatorAction.tryPrepare(userText)
    }

    suspend fun tryHandleReceiptIntent(userText: String): ActionResult? {
        return receiptCheckerAction.tryPrepare(userText)
    }

    suspend fun tryHandleWalkIntent(userText: String): ActionResult? {
        return when (walkModeIntentDetector.detect(userText)) {
            WalkIntent.START -> walkModeAction.start()
            WalkIntent.STOP -> walkModeAction.stop()
            null -> null
        }
    }

    suspend fun tryHandleRouteIntent(userText: String): ActionResult? {
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
        }
        return prep
    }

    suspend fun tryHandleMediaSelection(userText: String): ActionResult? {
        val pending = pendingConfirmation ?: return null
        if (pending.toolName != "select_media_app") return null

        val prep = mediaLauncherAction.confirmSelection(pending.args, userText)
        if (prep is ActionResult.NeedsConfirmation) {
            storePending(prep.pendingAction, prep.prompt)
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
            ToolName.ReadMessages -> messagesAction.readMessages()
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
            ToolName.ListSavedRoutes -> listSavedRoutes()
            ToolName.ListSavedPlaces -> listSavedPlaces()
            ToolName.ResumeActiveSession -> resumeActiveSession()
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
                    "Vale, te guío a pie hasta $destination. " +
                        "Abro Google Maps: Lazaro leerá cada giro y la cámara te guiará con pitidos. " +
                        "Los avisos de ruta tienen prioridad. Di Lazaro si necesitas algo.",
                    suspendListening = true,
                )
            }
            "save_saved_place" -> savedPlaceAction.confirmSave(pending.args)
            "delete_saved_place" -> savedPlaceAction.confirmDelete(pending.args)
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
            "reply_message" -> whatsAppReplyAction.executeReply(pending.args)
            "launch_media" -> mediaLauncherAction.confirmLaunch(pending.args)
            "search_media" -> mediaLauncherAction.confirmSearch(pending.args)
            "read_book" -> bookReaderAction.confirmRead(pending.args)
            "navigate_transit_stop" -> {
                val stopName = pending.args["name"].orEmpty()
                val lat = pending.args["lat"]?.toDoubleOrNull()
                val lng = pending.args["lng"]?.toDoubleOrNull()
                if (lat == null || lng == null) {
                    ActionResult.Error("No tengo la parada lista.")
                } else {
                    val location = locationAction.getCurrentLocation()
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
                        "Te guío a pie hasta $stopName. Lazaro te dira cada giro y vibrara al girar.",
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
                    "Abro la ruta en transporte público hasta $destination. " +
                        "Me callo mientras usas Maps. Di Lazaro o toca cuando quieras.",
                    suspendListening = true,
                )
            }
            else -> ActionResult.Error("Esta acción no requiere confirmación.")
        }
    }

    suspend fun cancelPending(): ActionResult {
        val pending = pendingConfirmation
        val wasResume = pending?.toolName == ToolName.ResumeActiveSession.id
        pendingConfirmation = null
        pendingSkill = null
        mapsLaunchDeferrer.clear()
        lastPromptText = ""
        memoryActionHandler.rejectMemoryProposal()
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
                "De acuerdo, cancelado. Di Lazaro cuando quieras otra cosa."
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
        )
        private val AFFIRMATIVE_PREFIXES = listOf(
            "si", "vale", "ok", "claro", "de acuerdo", "por supuesto",
        )
        private val NEGATIVE_EXACT = setOf(
            "no", "nope", "negativo", "cancelar", "cancela", "nel", "paso",
        )
        private val NEGATIVE_PREFIXES = listOf(
            "no", "cancela", "cancelar",
        )
        private val UNCERTAIN_DENY = listOf(
            "no se", "no lo se", "no entend", "no estoy seguro",
        )
    }
}
