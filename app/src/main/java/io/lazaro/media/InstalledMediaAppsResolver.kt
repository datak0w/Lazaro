package io.lazaro.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledMediaApp(
    val packageName: String,
    val label: String,
    val categories: Set<MediaCategory>,
)

@Singleton
class InstalledMediaAppsResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val packageManager get() = context.packageManager

    fun getInstalledForCategory(category: MediaCategory): List<InstalledMediaApp> {
        val results = linkedMapOf<String, InstalledMediaApp>()

        for (entry in MediaAppCatalog.knownApps) {
            if (category !in entry.categories) continue
            if (!isInstalled(entry.packageName)) continue

            val label = resolveLabel(entry.packageName, entry.defaultLabel)
            results[entry.packageName] = InstalledMediaApp(
                packageName = entry.packageName,
                label = label,
                categories = entry.categories,
            )
        }

        return results.values.sortedBy { it.label.lowercase() }
    }

    fun isInstalled(packageName: String): Boolean {
        if (buildLaunchIntent(packageName) != null) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun buildLaunchIntent(packageName: String): Intent? {
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val launcherQuery = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val resolveInfo = packageManager.queryIntentActivities(
            launcherQuery,
            PackageManager.MATCH_DEFAULT_ONLY,
        ).firstOrNull() ?: return null

        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(packageName, resolveInfo.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun resolveLabel(packageName: String, fallback: String): String {
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            fallback
        }
    }

    fun findInstalledByName(query: String, category: MediaCategory): InstalledMediaApp? {
        val normalized = query.lowercase().trim()
        return getInstalledForCategory(category).find { app ->
            app.label.lowercase().contains(normalized) ||
                normalized.contains(app.label.lowercase()) ||
                app.packageName.lowercase().contains(normalized)
        }
    }

    fun findInstalledByAlias(alias: String): InstalledMediaApp? {
        val normalized = normalizeAlias(alias)
        if (normalized.isBlank()) return null

        val matches = MediaAppCatalog.knownApps
            .filter { entry ->
                isInstalled(entry.packageName) &&
                    entry.searchAliases.any { candidate ->
                        normalized == candidate || normalized.contains(candidate)
                    }
            }
            .sortedWith(compareBy({ it.searchPriority }, { it.defaultLabel }))

        val best = matches.firstOrNull() ?: return null
        return InstalledMediaApp(
            packageName = best.packageName,
            label = resolveLabel(best.packageName, best.defaultLabel),
            categories = best.categories,
        )
    }

    fun getSearchCapableApps(): List<InstalledMediaApp> {
        return MediaAppCatalog.knownApps
            .filter { it.searchAliases.isNotEmpty() && isInstalled(it.packageName) }
            .sortedWith(compareBy({ it.searchPriority }, { it.defaultLabel }))
            .distinctBy { it.packageName }
            .map { entry ->
                InstalledMediaApp(
                    packageName = entry.packageName,
                    label = resolveLabel(entry.packageName, entry.defaultLabel),
                    categories = entry.categories,
                )
            }
    }

    fun buildSearchIntent(packageName: String, query: String): Intent? {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return buildLaunchIntent(packageName)

        for (candidate in MediaSearchIntents.candidates(packageName, trimmedQuery)) {
            if (canResolve(candidate)) {
                return candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return buildLaunchIntent(packageName)
    }

    private fun canResolve(intent: Intent): Boolean {
        return packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }

    private fun normalizeAlias(alias: String): String {
        return alias
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
