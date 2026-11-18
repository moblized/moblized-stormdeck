package com.moblized.stormdeck.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.moblized.stormdeck.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Twice a day, and each time the app starts: look for a new version on GitHub and download it
 * (on Wi-Fi only, if that's set). If "Install automatically" is on and the app isn't on screen, install it;
 * otherwise say it's ready.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val s = Graph.settings.value
        // With checking off, an update that already downloaded can still install; nothing new is fetched.
        if (!s.autoUpdate && Updater.status.value !is Updater.Status.Ready) return Result.success()
        return try {
            val mayDownload = !(s.wifiOnly && Updater.onMeteredNetwork(context))
            val release = Updater.check(context, download = mayDownload) ?: return Result.success()
            val ready = Updater.status.value is Updater.Status.Ready
            val foreground = withContext(Dispatchers.Main) { Updater.appInForeground() }
            when {
                // On screen: the badge on the Settings gear says it's ready; it installs once you leave the app.
                foreground -> Unit
                ready && s.autoInstall && Updater.canInstall(context) ->
                    withContext(Dispatchers.Main) { Updater.install(context) }
                else -> Updater.notifyReady(context, release, downloaded = ready)
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val PERIODIC = "update-check"
        private const val NOW = "update-check-now"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS).setConstraints(online).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private const val INSTALL = "update-install"

        /** Installs a downloaded update a minute from now, if the app is still in the background then. */
        fun installSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>().setInitialDelay(1, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniqueWork(INSTALL, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancelInstallSoon(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(INSTALL)
        }

        /** A check right away (once the phone is online), used when the app starts. */
        fun checkSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
