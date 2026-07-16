package io.lazaro.actions

sealed class ActionResult {
    data class Success(
        val message: String,
        val suspendListening: Boolean = false,
    ) : ActionResult()
    data class NeedsConfirmation(val prompt: String, val pendingAction: PendingAction) : ActionResult()
    data class Error(val message: String) : ActionResult()
}

data class PendingAction(
    val toolName: String,
    val args: Map<String, String>,
)

enum class ToolName(val id: String) {
    NavigateTo("navigate_to"),
    WhereAmI("where_am_i"),
    WebSearch("web_search"),
    ReadMessages("read_messages"),
    MakeCall("make_call"),
    ReplyMessage("reply_message"),
    SaveMemory("save_memory"),
    CreateSkill("create_skill"),
    RecallMemory("recall_memory"),
    GetLocationTrail("get_location_trail"),
    PlayMedia("play_media"),
    FindTransit("find_transit"),
    PlanTransitRoute("plan_transit_route"),
    StartWalkMode("start_walk_mode"),
    StopWalkMode("stop_walk_mode"),
    ListSavedRoutes("list_saved_routes"),
    ListSavedPlaces("list_saved_places"),
    ResumeActiveSession("resume_active_session"),
    ;

    companion object {
        fun fromId(id: String): ToolName? = entries.find { it.id == id }
    }
}
