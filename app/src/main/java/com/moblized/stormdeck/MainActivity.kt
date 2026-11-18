package com.moblized.stormdeck

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moblized.stormdeck.alerts.Notify
import com.moblized.stormdeck.alerts.SyncWorker
import com.moblized.stormdeck.data.PhoneLocation
import com.moblized.stormdeck.data.isDark
import com.moblized.stormdeck.ui.AppViewModel
import com.moblized.stormdeck.ui.Permissions
import com.moblized.stormdeck.ui.Root
import com.moblized.stormdeck.ui.StormTheme
import com.moblized.stormdeck.ui.Tab
import com.moblized.stormdeck.update.Updater

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var needLocation by mutableStateOf(false)
    private var needNotifications by mutableStateOf(false)

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
        if (PhoneLocation.allowed(this)) vm.locate()
        // Then ask for notifications, so alerts can reach you.
        if (needNotifications && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions()
        if (it) SyncWorker.runSoon(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        handle(intent)
        applyBars(Graph.settings.value.isDark(this))
        setContent {
            val settings by Graph.settings.settings.collectAsState()
            val dark = settings.isDark(this)
            SideEffect { applyBars(dark) }
            StormTheme(dark) {
                Root(
                    vm,
                    dark,
                    Permissions(
                        needLocation = needLocation,
                        needNotifications = needNotifications,
                        askLocation = ::requestLocation,
                        askNotifications = { if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    ),
                )
            }
        }
        if (savedInstanceState == null) {
            if (needLocation) {
                requestLocation()
            } else if (needNotifications && Build.VERSION.SDK_INT >= 33) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private var barsDark: Boolean? = null

    private fun applyBars(dark: Boolean) {
        if (barsDark == dark) return
        barsDark = dark
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
    }

    private fun requestLocation() {
        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun refreshPermissions() {
        needLocation = !PhoneLocation.allowed(this)
        needNotifications = !Notify.allowed(this)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.getStringExtra(Notify.EXTRA_TAB)) {
            "tropics" -> vm.tab = Tab.Tropics
            "forecast" -> vm.tab = Tab.Forecast
        }
        if (intent?.getBooleanExtra(Updater.EXTRA_INSTALL, false) == true) {
            vm.tab = Tab.Settings
            Updater.install(this)
        }
    }
}
