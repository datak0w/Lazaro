package io.lazaro.actions

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MapsLaunchDeferrer @Inject constructor() {
    private var deferredLaunch: (() -> Boolean)? = null

    fun defer(launch: () -> Boolean) {
        deferredLaunch = launch
    }

    fun runDeferred(): Boolean {
        val launch = deferredLaunch ?: return false
        deferredLaunch = null
        return launch()
    }

    fun hasDeferred(): Boolean = deferredLaunch != null

    fun clear() {
        deferredLaunch = null
    }
}
