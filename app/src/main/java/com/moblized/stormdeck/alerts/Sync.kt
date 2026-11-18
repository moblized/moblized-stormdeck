package com.moblized.stormdeck.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import com.moblized.stormdeck.MainActivity
import com.moblized.stormdeck.R
import com.moblized.stormdeck.data.AlertKind
import com.moblized.stormdeck.data.AlertLevel
import com.moblized.stormdeck.data.Fmt
import com.moblized.stormdeck.data.Geo
import com.moblized.stormdeck.data.NewStormScope
import com.moblized.stormdeck.data.Nws
import com.moblized.stormdeck.data.Place
import com.moblized.stormdeck.data.Storm
import com.moblized.stormdeck.data.Tropics
import com.moblized.stormdeck.data.WeatherAlert
import com.moblized.stormdeck.data.WeatherRepo
import com.moblized.stormdeck.widget.WeatherWidget
import androidx.glance.appwidget.updateAll
import java.util.concurrent.TimeUnit

object Notify {
    const val ALERTS = "weather-alerts"
    const val STORMS = "tropical-storms"
    const val EXTRA_TAB = "com.moblized.stormdeck.TAB"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(ALERTS, "Severe weather alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "National Weather Service warnings, watches and advisories for your place"
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(STORMS, "Hurricanes & tropical storms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New tropical cyclones, and storms forecast to come near you"
            },
        )
    }

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openApp(context: Context, tab: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context,
        code,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TAB, tab),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun alert(context: Context, a: WeatherAlert, place: Place) {
        if (!allowed(context)) return
        channels(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val color = when (a.kind) {
            AlertKind.Warning -> 0xFFE53935.toInt()
            AlertKind.Watch -> 0xFFFFA000.toInt()
            else -> 0xFFFDD835.toInt()
        }
        val body = a.headline.ifBlank { a.description.take(200) }
        val n = NotificationCompat.Builder(context, ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color)
            .setContentTitle("${a.event} · ${place.name}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body + if (a.instruction.isNotBlank()) "\n\n" + a.instruction.take(400) else ""))
            .setPriority(if (a.kind == AlertKind.Warning) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp(context, "forecast", a.id.hashCode()))
            .setAutoCancel(true)
            .apply { if (a.expires > 0) setTimeoutAfter((a.expires - System.currentTimeMillis()).coerceAtLeast(60_000)) }
            .build()
        nm.notify(a.id.hashCode(), n)
    }

    fun storm(context: Context, key: String, title: String, text: String) {
        if (!allowed(context)) return
        channels(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = NotificationCompat.Builder(context, STORMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF38BDF8.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context, "tropics", key.hashCode()))
            .setAutoCancel(true)
            .build()
        nm.notify(key.hashCode(), n)
    }
}

/**
 * Every 15 minutes in the background: new NWS alerts for your place, the widget's forecast (every half hour)
 * and the tropics (every hour).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        Graph.init(context)
        val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
        val s = Graph.settings.value
        val place = Graph.places.selected()
        val now = System.currentTimeMillis()
        var failed = false

        if (place != null && s.alerts != AlertLevel.Off && Geo.inUsRadarArea(place.point)) {
            runCatching { checkAlerts(context, place, s.alerts) }.onFailure { failed = true }
        }

        if (place != null && now - prefs.getLong("widgetAt", 0) > 25 * 60_000L) {
            runCatching {
                WeatherRepo.forecast(place, s)
                WeatherWidget().updateAll(context)
                prefs.edit().putLong("widgetAt", now).apply()
            }.onFailure { failed = true }
        }

        if ((s.newStorms != NewStormScope.Off || s.stormNearby) && now - prefs.getLong("stormsAt", 0) > 55 * 60_000L) {
            runCatching {
                checkStorms(context, place)
                prefs.edit().putLong("stormsAt", now).apply()
            }.onFailure { failed = true }
        }
        return if (failed && runAttemptCount < 2) Result.retry() else Result.success()
    }

    private suspend fun checkAlerts(context: Context, place: Place, level: AlertLevel) {
        val alerts = Nws.alerts(place.lat, place.lon).filter { it.matches(level) }
        val prefs = context.getSharedPreferences("seen-alerts", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // Forget alerts that have expired, so the store stays small.
        val e = prefs.edit()
        prefs.all.forEach { (k, v) -> if ((v as? Long ?: 0L) < now) e.remove(k) }
        for (a in alerts) {
            if (prefs.contains(a.id)) continue
            Notify.alert(context, a, place)
            e.putLong(a.id, if (a.expires > now) a.expires else now + 12 * 3_600_000L)
        }
        e.apply()
    }

    private suspend fun checkStorms(context: Context, place: Place?) {
        val s = Graph.settings.value
        val storms = Tropics.active()
        val prefs = context.getSharedPreferences("seen-storms", Context.MODE_PRIVATE)
        val firstRun = !prefs.getBoolean("seeded", false)
        val known = prefs.getStringSet("known", emptySet()).orEmpty()
        val e = prefs.edit()

        if (!firstRun && s.newStorms != NewStormScope.Off) {
            storms.filter { it.key !in known && sameStormNotKnown(it, known) }
                .filter { s.newStorms == NewStormScope.Everywhere || it.basin.nhc }
                .forEach { st ->
                    Notify.storm(
                        context,
                        "new-" + st.key,
                        "New ${st.type.lowercase()}: ${st.name}",
                        "${st.basin.label} · ${Fmt.knots(st.windKt, s)} winds" +
                            (if (st.countries.isNotBlank()) " · near ${st.countries}" else "") + ". Tap to see its track.",
                    )
                }
        }
        e.putStringSet("known", storms.map { it.key }.toSet() + storms.map { "name:" + it.name.lowercase() }.toSet())
        e.putBoolean("seeded", true)

        if (s.stormNearby && place != null) {
            val limitKm = s.stormNearbyMiles * 1.609344
            for (st in storms) {
                // Only fetch tracks for storms that could plausibly reach you.
                if (Geo.distanceKm(st.point, place.point) > 4000) continue
                val track = runCatching { Tropics.track(st) }.getOrNull() ?: continue
                val km = track.closestKm(place.point, st.point)
                if (km > limitKm) continue
                val stage = if (km == 0.0) 2 else 1
                val prevStage = prefs.getInt("near:" + st.key, 0)
                if (stage <= prevStage) continue
                e.putInt("near:" + st.key, stage)
                val where = if (km == 0.0) "${place.name} is inside the forecast cone" else
                    "Forecast track passes within ${Fmt.km(km, s)} of ${place.name}"
                Notify.storm(
                    context,
                    "near-" + st.key,
                    "${st.type} ${st.name} may come your way",
                    "$where. Now ${st.category.short}, ${Fmt.knots(st.windKt, s)}" + (st.movement?.let { ", moving $it" } ?: "") + ".",
                )
            }
        }
        e.apply()
    }

    /** A storm can change id when NHC takes it over from GDACS; don't announce it twice. */
    private fun sameStormNotKnown(st: Storm, known: Set<String>) = "name:" + st.name.lowercase() !in known

    companion object {
        private const val PERIODIC = "sync"
        private const val NOW = "sync-now"
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runSoon(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
