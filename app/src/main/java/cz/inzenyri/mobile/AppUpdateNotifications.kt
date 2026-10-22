package cz.inzenyri.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal object AppUpdateNotifications {
    const val OWNER = "salamek67"
    const val REPOSITORY = "Inzenyri"
    const val APK_NAME = "inzenyri-android.apk"
    const val OPEN_UPDATE = "cz.inzenyri.mobile.OPEN_UPDATE"
    private const val PREFS = "app_updates"
    private const val CHANNEL = "app_updates"
    private const val ID = 421
    private const val DAY = 24L * 60 * 60 * 1000

    fun enabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", enabled)
            .apply()
        schedule(context)
        if (!enabled) context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled(context)) {
            scheduler.cancel(ID)
            return
        }
        if (scheduler.getPendingJob(ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(ID, ComponentName(context, AppUpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(DAY, 6 * 60 * 60 * 1000L)
                .setPersisted(true)
                .setBackoffCriteria(30 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build(),
        )
    }

    fun available(context: Context, release: JSONObject): Triple<String, JSONObject, String>? {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name").removePrefix("v")
        if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag)) return null
        val current =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        val a = tag.split('.').map { it.toLongOrNull() ?: return null }
        val b = current.substringBefore('-').split('.').map { it.toLongOrNull() ?: return null }
        val comparison =
            (0 until maxOf(a.size, b.size))
                .asSequence()
                .map { (a.getOrElse(it) { 0L }).compareTo(b.getOrElse(it) { 0L }) }
                .firstOrNull { it != 0 } ?: 0
        if (comparison <= 0) return null
        val assets =
            release.optJSONArray("assets")
                ?: throw IOException("Nejnovější release neobsahuje $APK_NAME.")
        val asset =
            (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name") == APK_NAME }
                ?: throw IOException("Nejnovější release neobsahuje $APK_NAME.")
        return Triple(tag, asset, release.optString("body").trim())
    }

    @Synchronized
    fun notifyAvailable(context: Context, version: String, cancelled: () -> Boolean = { false }) {
        if (!enabled(context) || cancelled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Aktualizace aplikace",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        if (
            !NotificationManagerCompat.from(context).areNotificationsEnabled() ||
                manager.getNotificationChannel(CHANNEL)?.importance ==
                    NotificationManager.IMPORTANCE_NONE
        )
            return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_notified_at", 0)
        if (last > 0 && now >= last && now - last < 3 * DAY) return
        val open =
            PendingIntent.getActivity(
                context,
                ID,
                Intent(context, MainActivity::class.java)
                    .setAction(OPEN_UPDATE)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        if (cancelled()) return
        try {
            manager.notify(
                ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Je dostupná aktualizace Inženýrů")
                    .setContentText(
                        "Verze $version je připravená. Klepni pro zobrazení aktualizace.",
                    )
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build(),
            )
            prefs.edit().putLong("last_notified_at", now).apply()
        } catch (_: SecurityException) {
            /* Notification permission can be revoked. */
        }
    }

    fun check(context: Context, cancelled: () -> Boolean) {
        if (
            !enabled(context) ||
                !NotificationManagerCompat.from(context).areNotificationsEnabled() ||
                cancelled()
        )
            return
        val connection =
            URL("https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest").openConnection()
                as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Inzenyri-Android")
            if (connection.responseCode == 404) return
            if (connection.responseCode !in 200..299)
                throw IOException("Kontrola aktualizace selhala.")
            val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val update = available(context, release)
            if (update == null) context.getSystemService(NotificationManager::class.java).cancel(ID)
            else notifyAvailable(context, update.first, cancelled)
        } finally {
            connection.disconnect()
        }
    }
}

class AppUpdateJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var cancellation: AtomicBoolean? = null
    private var task: Future<*>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val cancelled = AtomicBoolean(false)
        cancellation = cancelled
        task = executor.submit {
            val result = runCatching {
                AppUpdateNotifications.check(applicationContext) { cancelled.get() }
            }
            if (!cancelled.get()) jobFinished(params, result.isFailure)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancellation?.set(true)
        task?.cancel(true)
        return true
    }

    override fun onDestroy() {
        cancellation?.set(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}
