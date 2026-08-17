package io.lazaro.media

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.accessibility.AccessibilityAccessHelper
import io.lazaro.actions.ActionResult
import io.lazaro.actions.PendingAction
import io.lazaro.voice.VoiceOptionParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaLauncherAction @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaIntentDetector: MediaIntentDetector,
    private val mediaSearchIntentDetector: MediaSearchIntentDetector,
    private val installedMediaAppsResolver: InstalledMediaAppsResolver,
    private val mediaFavoritesRepository: MediaFavoritesRepository,
    private val accessibilityAccessHelper: AccessibilityAccessHelper,
) {
    private val mediaKeyScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    suspend fun tryPrepare(userText: String): ActionResult? {
        tryPrepareSearch(userText)?.let { return it }
        val category = mediaIntentDetector.detect(userText) ?: return null
        return prepareForCategory(category)
    }

    suspend fun tryPrepareSearch(userText: String): ActionResult? {
        val request = mediaSearchIntentDetector.detect(userText) ?: return null
        return prepareSearch(request.query, request.appAlias.orEmpty())
    }

    suspend fun prepareSearch(query: String, appHint: String = ""): ActionResult {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return ActionResult.Error("No he entendido qué quieres buscar.")
        }

        val app = resolveSearchApp(trimmedQuery, appHint)
            ?: return askSearchApp(trimmedQuery)

        // Sin «sí/no»: poner al momento
        return confirmSearch(
            mapOf(
                "query" to trimmedQuery,
                "package" to app.packageName,
                "label" to app.label,
            ),
        )
    }

    suspend fun confirmSearch(args: Map<String, String>): ActionResult {
        val query = args["query"].orEmpty().trim()
        val packageName = args["package"].orEmpty()
        val label = args["label"].orEmpty()

        if (query.isBlank() || packageName.isBlank()) {
            return ActionResult.Error("No sé qué buscar.")
        }

        val intent = installedMediaAppsResolver.buildSearchIntent(packageName, query)
            ?: return ActionResult.Error("No encuentro ${label.ifBlank { "la app" }}.")

        MediaAutoplayCoordinator.request(packageName, query)
        context.startActivity(intent)
        // Empujón extra: tecla Play si Spotify ya tiene sesión (y accesibilidad abre el ítem)
        scheduleMediaPlayNudges(packageName)

        mediaFavoritesRepository.rememberPlay(query, packageName, label)
        mediaFavoritesRepository.saveFavorite(
            MediaCategory.MUSIC,
            InstalledMediaApp(
                packageName = packageName,
                label = label.ifBlank { installedMediaAppsResolver.resolveLabel(packageName, packageName) },
                categories = setOf(MediaCategory.MUSIC),
            ),
        )

        val spokenLabel = label.ifBlank { installedMediaAppsResolver.resolveLabel(packageName, packageName) }
        return ActionResult.Success("Pongo «$query» en $spokenLabel.")
    }

    private fun scheduleMediaPlayNudges(packageName: String) {
        if (packageName !in setOf(
                "com.spotify.music",
                "com.spotify.lite",
                "com.google.android.apps.youtube.music",
            )
        ) {
            return
        }
        mediaKeyScope.launch {
            val delays = listOf(2_500L, 4_500L, 7_000L, 10_000L)
            for (wait in delays) {
                delay(wait)
                if (MediaAutoplayCoordinator.peek(packageName) == null) return@launch
                dispatchMediaPlay()
            }
        }
    }

    private fun dispatchMediaPlay() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        am.dispatchMediaKeyEvent(down)
        am.dispatchMediaKeyEvent(up)
    }

    private suspend fun resolveSearchApp(query: String, appHint: String): InstalledMediaApp? {
        if (appHint.isNotBlank()) {
            return installedMediaAppsResolver.findInstalledByAlias(appHint)
        }

        val favorite = mediaFavoritesRepository.getFavorite(MediaCategory.MUSIC)
        if (favorite != null && installedMediaAppsResolver.isInstalled(favorite.packageName)) {
            return InstalledMediaApp(
                packageName = favorite.packageName,
                label = installedMediaAppsResolver.resolveLabel(favorite.packageName, favorite.label),
                categories = setOf(MediaCategory.MUSIC),
            )
        }

        val recent = mediaFavoritesRepository.getRecentPlays(1).firstOrNull()
        if (recent != null && installedMediaAppsResolver.isInstalled(recent.packageName)) {
            return InstalledMediaApp(
                packageName = recent.packageName,
                label = installedMediaAppsResolver.resolveLabel(recent.packageName, recent.label),
                categories = setOf(MediaCategory.MUSIC),
            )
        }

        val capable = installedMediaAppsResolver.getSearchCapableApps()
        if (capable.size == 1) {
            return capable.first()
        }
        // Preferir Spotify si está
        capable.firstOrNull {
            it.packageName == "com.spotify.music" || it.packageName == "com.spotify.lite"
        }?.let { return it }

        return null
    }

    private fun askSearchApp(query: String): ActionResult {
        val installed = installedMediaAppsResolver.getSearchCapableApps()
        if (installed.isEmpty()) {
            return ActionResult.Error(
                "No tienes apps donde buscar. Instala Spotify o YouTube y vuelve a pedírmelo.",
            )
        }

        val options = installed.take(5).mapIndexed { index, app ->
            "${index + 1}: ${app.label}"
        }.joinToString(". ")

        return ActionResult.NeedsConfirmation(
            prompt = "¿Dónde pongo «$query»? Tienes: $options. Di el número o el nombre.",
            pendingAction = PendingAction(
                toolName = "select_media_search_app",
                args = installed.take(5).mapIndexed { index, app ->
                    "candidate_$index" to "${app.packageName}|${app.label}"
                }.toMap() + mapOf(
                    "query" to query,
                ),
            ),
        )
    }

    suspend fun confirmSearchAppSelection(args: Map<String, String>, selection: String): ActionResult {
        val query = args["query"].orEmpty().trim()
        if (query.isBlank()) {
            return ActionResult.Error("No sé qué buscar.")
        }

        val app = resolveCandidateSelection(args, selection)
            ?: return ActionResult.Error("No he entendido tu elección. Di el número o el nombre de la app.")

        return confirmSearch(
            mapOf(
                "query" to query,
                "package" to app.packageName,
                "label" to app.label,
            ),
        )
    }

    private fun searchPending(query: String, app: InstalledMediaApp): PendingAction {
        return PendingAction(
            toolName = "search_media",
            args = mapOf(
                "query" to query,
                "package" to app.packageName,
                "label" to app.label,
            ),
        )
    }

    suspend fun prepareForCategory(category: MediaCategory): ActionResult {
        if (category == MediaCategory.MUSIC) {
            prepareMusicWithMemory()?.let { return it }
        }

        val favorite = mediaFavoritesRepository.getFavorite(category)
        if (favorite != null && installedMediaAppsResolver.isInstalled(favorite.packageName)) {
            val label = installedMediaAppsResolver.resolveLabel(favorite.packageName, favorite.label)
            return ActionResult.NeedsConfirmation(
                prompt = "Tu favorito para ${category.spokenLabel} es $label. ¿Lo abro? Di sí o no.",
                pendingAction = PendingAction(
                    toolName = "launch_media",
                    args = mapOf(
                        "category" to category.id,
                        "package" to favorite.packageName,
                        "label" to label,
                        "save_favorite" to "false",
                    ),
                ),
            )
        }

        val installed = installedMediaAppsResolver.getInstalledForCategory(category)
        if (installed.isEmpty()) {
            return ActionResult.Error(
                "No tienes apps de ${category.spokenLabel} instaladas. Instala Spotify, YouTube, COPE u otra y vuelve a pedírmelo.",
            )
        }

        if (installed.size == 1) {
            val app = installed.first()
            return ActionResult.NeedsConfirmation(
                prompt = "Solo tienes ${app.label} para ${category.spokenLabel}. ¿La abro? Di sí o no. La guardaré como favorita.",
                pendingAction = launchPending(category, app, saveFavorite = true),
            )
        }

        val options = installed.take(5).mapIndexed { index, app ->
            "${index + 1}: ${app.label}"
        }.joinToString(". ")

        return ActionResult.NeedsConfirmation(
            prompt = "Tienes ${installed.size} apps de ${category.spokenLabel}: $options. Di el número o el nombre.",
            pendingAction = PendingAction(
                toolName = "select_media_app",
                args = installed.take(5).mapIndexed { index, app ->
                    "candidate_$index" to "${app.packageName}|${app.label}"
                }.toMap() + mapOf(
                    "category" to category.id,
                    "query" to category.spokenLabel,
                ),
            ),
        )
    }

    /**
     * «Pon música» sin query: repite la última al momento, o pregunta qué poner.
     */
    private suspend fun prepareMusicWithMemory(): ActionResult? {
        val recent = mediaFavoritesRepository.getRecentPlays(5)
            .filter { installedMediaAppsResolver.isInstalled(it.packageName) }
        if (recent.isNotEmpty()) {
            val last = recent.first()
            return confirmSearch(
                mapOf(
                    "query" to last.query,
                    "package" to last.packageName,
                    "label" to last.label,
                ),
            )
        }

        val favorite = mediaFavoritesRepository.getFavorite(MediaCategory.MUSIC)
        val spotify = installedMediaAppsResolver.findInstalledByAlias("spotify")
        val app = when {
            favorite != null && installedMediaAppsResolver.isInstalled(favorite.packageName) ->
                InstalledMediaApp(
                    favorite.packageName,
                    installedMediaAppsResolver.resolveLabel(favorite.packageName, favorite.label),
                    setOf(MediaCategory.MUSIC),
                )
            spotify != null -> spotify
            else -> installedMediaAppsResolver.getInstalledForCategory(MediaCategory.MUSIC).firstOrNull()
        }
        if (app == null) return null

        return ActionResult.NeedsConfirmation(
            prompt = "¿Qué pongo?",
            pendingAction = PendingAction(
                toolName = "await_music_query",
                args = mapOf(
                    "package" to app.packageName,
                    "label" to app.label,
                ),
            ),
        )
    }

    suspend fun confirmRecentMediaSelection(args: Map<String, String>, selection: String): ActionResult {
        val normalized = selection.trim().lowercase()
        if (normalized in setOf("si", "sí", "vale", "ok", "okay", "claro", "dale", "ponla", "esa")) {
            val query = args["default_query"].orEmpty()
            val pkg = args["default_package"].orEmpty()
            val label = args["default_label"].orEmpty()
            if (query.isBlank() || pkg.isBlank()) {
                return ActionResult.Error("No tengo una selección reciente.")
            }
            return confirmSearch(
                mapOf("query" to query, "package" to pkg, "label" to label),
            )
        }

        val candidates = args.filterKeys { it.startsWith("candidate_") }
            .values
            .mapNotNull { encoded ->
                val parts = encoded.split("|", limit = 3)
                if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else null
            }

        VoiceOptionParser.parseIndex(selection, candidates.size)?.let { index ->
            if (index in candidates.indices) {
                val (pkg, label, query) = candidates[index]
                return confirmSearch(
                    mapOf("query" to query, "package" to pkg, "label" to label),
                )
            }
        }

        // El usuario dijo otra cosa → tratar como nueva búsqueda
        return prepareSearch(selection, "")
    }

    suspend fun confirmAwaitMusicQuery(args: Map<String, String>, selection: String): ActionResult {
        val query = selection.trim()
        val normalized = query.lowercase()
        if (normalized in setOf("si", "sí", "vale", "ok", "okay", "claro", "dale", "no")) {
            return ActionResult.Error("Dime un artista, estilo o playlist.")
        }
        if (query.length < 2) {
            return ActionResult.Error("Dime un artista, estilo o playlist.")
        }
        val pkg = args["package"].orEmpty()
        val label = args["label"].orEmpty()
        if (pkg.isNotBlank()) {
            return confirmSearch(
                mapOf(
                    "query" to query,
                    "package" to pkg,
                    "label" to label.ifBlank { "Spotify" },
                ),
            )
        }
        return prepareSearch(query, "")
    }

    fun resolveSelection(args: Map<String, String>, selection: String): InstalledMediaApp? {
        val category = MediaCategory.fromId(args["category"].orEmpty()) ?: return null
        return resolveCandidateSelection(args, selection, category)
    }

    private fun resolveCandidateSelection(
        args: Map<String, String>,
        selection: String,
        category: MediaCategory? = null,
    ): InstalledMediaApp? {
        val candidates = args.filterKeys { it.startsWith("candidate_") }
            .values
            .mapNotNull { encoded ->
                val parts = encoded.split("|", limit = 2)
                if (parts.size >= 2) {
                    InstalledMediaApp(parts[0], parts[1], category?.let { setOf(it) } ?: emptySet())
                } else {
                    null
                }
            }

        VoiceOptionParser.parseIndex(selection, candidates.size)?.let { index ->
            if (index in candidates.indices) return candidates[index]
        }

        category?.let {
            installedMediaAppsResolver.findInstalledByName(selection, it)?.let { return it }
        }

        return candidates.find { it.label.equals(selection, ignoreCase = true) }
            ?: installedMediaAppsResolver.findInstalledByAlias(selection)
    }

    suspend fun confirmLaunch(args: Map<String, String>): ActionResult {
        val packageName = args["package"].orEmpty()
        val label = args["label"].orEmpty()
        val category = MediaCategory.fromId(args["category"].orEmpty())
        val saveFavorite = args["save_favorite"] != "false"

        if (packageName.isBlank()) {
            return ActionResult.Error("No sé qué app abrir.")
        }

        val launchResult = openApp(packageName, label)
        if (launchResult is ActionResult.Error) return launchResult

        if (saveFavorite && category != null) {
            mediaFavoritesRepository.saveFavorite(
                category,
                InstalledMediaApp(
                    packageName = packageName,
                    label = label.ifBlank { installedMediaAppsResolver.resolveLabel(packageName, packageName) },
                    categories = setOf(category),
                ),
            )
        }

        val spokenLabel = label.ifBlank { installedMediaAppsResolver.resolveLabel(packageName, packageName) }
        return ActionResult.Success("Abriendo $spokenLabel.")
    }

    suspend fun confirmSelection(args: Map<String, String>, selection: String): ActionResult {
        val app = resolveSelection(args, selection)
            ?: return ActionResult.Error("No he entendido tu elección. Di el número o el nombre de la app.")

        val category = MediaCategory.fromId(args["category"].orEmpty())
            ?: return ActionResult.Error("No sé qué tipo de contenido quieres.")

        return ActionResult.NeedsConfirmation(
            prompt = "¿Abro ${app.label} y la guardo como favorita para ${category.spokenLabel}? Di sí o no.",
            pendingAction = launchPending(category, app, saveFavorite = true),
        )
    }

    private fun openApp(packageName: String, label: String): ActionResult {
        val launchIntent = installedMediaAppsResolver.buildLaunchIntent(packageName)
            ?: return ActionResult.Error("No encuentro la app ${label.ifBlank { packageName }}.")

        context.startActivity(launchIntent)
        return ActionResult.Success("OK")
    }

    private fun launchPending(
        category: MediaCategory,
        app: InstalledMediaApp,
        saveFavorite: Boolean,
    ): PendingAction {
        return PendingAction(
            toolName = "launch_media",
            args = mapOf(
                "category" to category.id,
                "package" to app.packageName,
                "label" to app.label,
                "save_favorite" to saveFavorite.toString(),
            ),
        )
    }
}
