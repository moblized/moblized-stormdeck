package com.moblized.stormdeck

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.moblized.stormdeck.alerts.Notify
import com.moblized.stormdeck.alerts.SyncWorker
import com.moblized.stormdeck.data.Http
import com.moblized.stormdeck.data.isDark
import com.moblized.stormdeck.update.UpdateWorker
import com.moblized.stormdeck.update.Updater
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        Graph.init(this)
        MapLibre.getInstance(this)
        // Map tiles go through the same client (and its cache and user agent) as everything else.
        runCatching { HttpRequestUtil.setOkHttpClient(Http.client) }
        Notify.channels(this)
        SyncWorker.schedule(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                UpdateWorker.cancelInstallSoon(this@App)
            }

            override fun onStop(owner: LifecycleOwner) {
                // The icon switches when you leave the app, so it never changes under your finger.
                AppIcon.sync(this@App)
                if (Graph.settings.value.autoInstall && Updater.status.value is Updater.Status.Ready) {
                    UpdateWorker.installSoon(this@App)
                }
            }
        })

        // App updates from GitHub: pick up one already downloaded, keep checking twice a day, and look now.
        Updater.restoreReady(this)
        UpdateWorker.schedule(this, Graph.settings.value.autoUpdate)
        if (Graph.settings.value.autoUpdate) UpdateWorker.checkSoon(this)
    }
}

/**
 * Two launcher entries (activity aliases) share the app: the light icon and the dark icon. Whenever the app is in
 * dark mode, the dark one is shown; otherwise the light one.
 */
object AppIcon {
    private const val LIGHT = "com.moblized.stormdeck.LauncherLight"
    private const val DARK = "com.moblized.stormdeck.LauncherDark"

    fun sync(context: Context) {
        val wantDark = Graph.settings.value.isDark(context)
        val pm = context.packageManager
        val light = ComponentName(context.packageName, LIGHT)
        val dark = ComponentName(context.packageName, DARK)
        val darkOn = isEnabled(pm, dark, default = false)
        val lightOn = isEnabled(pm, light, default = true)
        if (darkOn == wantDark && lightOn == !wantDark) return
        val (on, off) = if (wantDark) dark to light else light to dark
        // Turn the new one on before the old one off, so the app never vanishes from the launcher.
        runCatching {
            pm.setComponentEnabledSetting(on, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(off, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    private fun isEnabled(pm: PackageManager, name: ComponentName, default: Boolean): Boolean =
        when (pm.getComponentEnabledSetting(name)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> default
        }
}
