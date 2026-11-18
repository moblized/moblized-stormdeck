package com.moblized.stormdeck.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** Hears back from Android's installer: show its confirmation screen, or report a failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                if (Updater.appInForeground()) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Updater.onInstallFinished(false, "Open Settings and tap Install to finish updating.") }
                } else {
                    // Android wants a tap to confirm and the app isn't on screen: leave a notification instead.
                    Updater.restoreReady(context)
                    (Updater.status.value as? Updater.Status.Ready)?.let { Updater.notifyReady(context, it.release, downloaded = true) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.onInstallFinished(true, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.restoreReady(context)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Updater.onInstallFinished(
                false,
                "This copy of the app was signed with a different key, so Android won't update it in place. " +
                    "Uninstall it once, then install the latest APK from the Releases page; updates work after that.",
            )
            PackageInstaller.STATUS_FAILURE_STORAGE -> Updater.onInstallFinished(false, "Not enough space on the phone to install the update.")
            else -> Updater.onInstallFinished(
                false,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update didn't install (code $status).",
            )
        }
    }
}

/** The app was just replaced by a newer version. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.onUpdated(context)
    }
}
