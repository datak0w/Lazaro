package io.lazaro.actions

import io.lazaro.audiobook.BookReaderAction
import io.lazaro.contacts.ContactMatch
import io.lazaro.media.MediaCategory
import io.lazaro.media.MediaLauncherAction
import io.lazaro.memory.entity.CustomSkill
import io.lazaro.memory.SkillExecutor
import io.lazaro.messaging.WhatsAppReplyAction
import io.lazaro.news.NewsReaderAction
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
    private val mapsLaunchDeferrer: MapsLaunchDeferrer,
) {
    private var pendingConfirmation: PendingAction? = null
    private var pendingSkill: CustomSkill? = null
    private var lastPromptText: String = ""

    fun hasDeferredMapsLaunch(): Boolean = mapsLaunchDeferrer.hasDeferred()

    fun runDeferredMapsLaunch() {
        mapsLaunchDeferrer.runDeferred()
    }

    private fun deferMapsLaunch(launch: () -> Boolean) {
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
            else -> "tu respuesta"
        }
    }

    private fun storePending(action: PendingAction, prompt: String? = null) {
        pendingConfirmation = action
        if (!prompt.isNullOrBlank()) {
            lastPromptText = prompt
        }
    }

    fun setPendingSkillExecution(skill: CustomSkill) {
        pendingSkill = skill
        storePending(PendingAction("execute_skill", mapOf("skill_id" to skill.id.toString())))
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
            ToolName.NavigateTo.id -> {
                val destination = pending.args["destination"].orEmpty()
                deferMapsLaunch { navigationAction.launchWalkingNavigation(destination) }
                ActionResult.Success(
                    "Vale, te guío a pie hasta $destination. " +
                        "Abro Google Maps. Lazaro te dira cada giro, por ejemplo: " +
                        "ahora gira a la derecha en tal calle. Vibrara al girar. " +
                        "Di Lazaro si necesitas algo.",
                    suspendListening = true,
                )
            }
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
                    deferMapsLaunch {
                        navigationAction.launchWalkingNavigationToCoordinates(lat, lng, stopName)
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
        val hadAction = pendingConfirmation != null
        pendingConfirmation = null
        pendingSkill = null
        mapsLaunchDeferrer.clear()
        lastPromptText = ""
        memoryActionHandler.rejectMemoryProposal()
        return ActionResult.Success(
            if (hadAction) "De acuerdo, cancelado. Di Lazaro cuando quieras otra cosa." else "De acuerdo, no lo guardaré.",
        )
    }

    fun hasPendingConfirmation(): Boolean = pendingConfirmation != null

    fun isAffirmative(text: String): Boolean {
        val normalized = text.lowercase().trim()
        return normalized in AFFIRMATIVE || normalized.startsWith("sí") || normalized == "yes"
    }

    fun isNegative(text: String): Boolean {
        val normalized = text.lowercase().trim()
        return normalized in NEGATIVE || normalized.startsWith("no") || normalized == "cancel"
    }

    companion object {
        private val AFFIRMATIVE = setOf("si", "sí", "confirmo", "confirmar", "vale", "ok", "de acuerdo", "yes")
        private val NEGATIVE = setOf("no", "cancelar", "cancela", "negativo", "nope")
    }
}
