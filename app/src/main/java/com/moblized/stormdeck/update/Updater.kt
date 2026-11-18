package com.moblized.stormdeck.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.moblized.stormdeck.BuildConfig
import com.moblized.stormdeck.MainActivity
import com.moblized.stormdeck.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** A version published on the app's GitHub Releases page. */
data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String = "")

/**
 * Keeps the app up to date from its own GitHub Releases. New versions download in the background and, with
 * "Install automatically" on, install by themselves shortly after you leave the app. Android checks they're signed
 * with the same key as the installed app, so only builds from this repository can replace it.
 */
object Updater {
    const val REPO = "moblized/moblized-stormdeck"
    const val EXTRA_INSTALL = "com.moblized.stormdeck.INSTALL_UPDATE"
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 4301

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object UpToDate : Status
        /** Found, but not downloaded yet (mobile data with Wi-Fi-only downloads on). */
        data class Available(val release: Release) : Status
        data class Downloading(val release: Release, val percent: Int) : Status
        /** [note] explains anything you need to do first, such as allowing installs. */
        data class Ready(val release: Release, val apk: File, val note: String? = null) : Status
        data object Installing : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val lock = Mutex()

    // A client of its own with long timeouts for the APK download.
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Picks the version, APK and notes out of GitHub's "latest release" answer. Tags look like v1.0.42. */
    fun parse(json: String): Release? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?.optString("browser_download_url")
            ?.takeIf { it.startsWith("https://") }
            ?: return null
        val notes = o.optString("body")
            .substringBefore("\n---")
            .lines()
            .map { it.removePrefix("### ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return Release(code, tag.removePrefix("v"), apk, notes)
    }

    fun isNewer(r: Release): Boolean = r.versionCode > BuildConfig.VERSION_CODE

    fun appInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** True on mobile data (or any connection Android counts as metered). */
    fun onMeteredNetwork(context: Context): Boolean =
        context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    /**
     * Looks for a newer version and, if [download] is true, downloads it. Returns the newer release,
     * or null when this is already the newest.
     */
    suspend fun check(context: Context, download: Boolean): Release? = lock.withLock {
        (status.value as? Status.Ready)?.let { if (it.apk.exists()) return it.release }
        _status.value = Status.Checking
        try {
            val release = latest()
            if (release == null || !isNewer(release)) {
                _status.value = Status.UpToDate
                return null
            }
            if (!download) {
                _status.value = Status.Available(release)
                return release
            }
            val apk = download(context, release)
            _status.value = Status.Ready(release, apk)
            release
        } catch (e: Exception) {
            _status.value = Status.Failed(
                when (e) {
                    is UnknownHostException -> "Can't reach GitHub. Check your connection."
                    else -> e.message ?: "Couldn't check for updates."
                },
            )
            throw e
        }
    }

    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "StormDeck-updater")
            .build()
        http.newCall(request).execute().use { r ->
            // No release published yet (or no repository): nothing to update to.
            if (r.code == 404) return@withContext null
            if (r.code == 403) throw IOException("GitHub is limiting update checks right now. Try again in an hour.")
            if (!r.isSuccessful) throw IOException("GitHub answered with an error (${r.code}).")
            parse(r.body?.string().orEmpty())
        }
    }

    private suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "stormdeck-${release.versionCode}.apk")
        if (target.exists() && target.length() > 0) return@withContext target
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, target.name + ".part")
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", "StormDeck-updater").build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The download answered with an error (${r.code}).")
            val body = r.body ?: throw IOException("The download was empty.")
            val total = body.contentLength()
            _status.value = Status.Downloading(release, 0)
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _status.value = Status.Downloading(release, (done * 100 / total).toInt())
                    }
                }
            }
        }
        if (!part.renameTo(target)) throw IOException("Couldn't save the update.")
        target
    }

    /**
     * Hands the downloaded APK to Android's installer. The first time, Android asks you to allow installs from
     * StormDeck. On Android 12 and newer, later updates install without a prompt; older versions ask to confirm.
     * The app closes when the update lands, and opens on the new version next time.
     */
    fun install(context: Context) {
        val ready = status.value as? Status.Ready ?: return
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            _status.value = ready.copy(note = "Allow StormDeck to install updates, then tap Install again.")
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        try {
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("stormdeck.apk", 0, ready.apk.length()).use { out ->
                    ready.apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateReceiver::class.java).setPackage(context.packageName),
                    flags,
                )
                // Remembered so the new version can say it was updated.
                prefs(context).edit().putInt("installing", ready.release.versionCode).apply()
                _status.value = Status.Installing
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            _status.value = Status.Failed("Couldn't start the install: ${e.message}")
        }
    }

    internal fun onInstallFinished(ok: Boolean, message: String?) {
        if (!ok) _status.value = Status.Failed(message ?: "The update didn't install.")
    }

    /** On start, and after Android's confirmation screen was dismissed: pick up an update already downloaded. */
    fun restoreReady(context: Context) {
        val dir = File(context.cacheDir, "updates")
        val apk = dir.listFiles()?.firstOrNull { it.name.endsWith(".apk") } ?: return
        val code = apk.name.removePrefix("stormdeck-").removeSuffix(".apk").toIntOrNull()
        if (code != null && code > BuildConfig.VERSION_CODE) {
            if (status.value !is Status.Ready) _status.value = Status.Ready(Release(code, "1.0.$code", ""), apk)
        } else {
            // Already installed (or unreadable): free the space.
            dir.deleteRecursively()
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel(context: Context): NotificationManager? {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return null
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New versions of StormDeck"
            },
        )
        return nm
    }

    /** "Version 1.0.N is ready", which opens the app and installs it. Once per version. */
    fun notifyReady(context: Context, release: Release, downloaded: Boolean) {
        if (!canNotify(context)) return
        val p = prefs(context)
        val key = if (downloaded) "notifiedReady" else "notifiedAvailable"
        if (p.getInt(key, 0) == release.versionCode) return
        val nm = channel(context) ?: return
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_INSTALL, downloaded),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("StormDeck ${release.versionName} is ${if (downloaded) "ready" else "available"}")
            .setContentText(if (downloaded) "Tap to install the update." else "It downloads once you're on Wi-Fi. Tap to open.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        p.edit().putInt(key, release.versionCode).apply()
    }

    /** Runs in the new version right after an update this app installed. */
    fun onUpdated(context: Context) {
        val p = prefs(context)
        val expected = p.getInt("installing", 0)
        p.edit().remove("installing").apply()
        File(context.cacheDir, "updates").deleteRecursively()
        if (expected == 0 || expected != BuildConfig.VERSION_CODE || !canNotify(context)) return
        val nm = channel(context) ?: return
        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("StormDeck updated to ${BuildConfig.VERSION_NAME}")
                .setContentText("Tap to open.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
