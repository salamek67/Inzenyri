package cz.inzenyri.mobile

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal object ReminderNotifications {
    private const val PREFS = "reminders"
    private const val CHANNEL = "upcoming_items"
    private const val ID = 410
    val types = listOf("test" to "Testy", "task" to "Úkoly", "event" to "Akce")
    data class Timing(val days: Int, val hour: Int, val minute: Int)

    fun typeEnabled(context: Context, type: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("${type}_enabled", true)

    fun setTypeEnabled(context: Context, type: String, enabled: Boolean) {
        require(types.any { it.first == type })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("${type}_enabled", enabled).apply()
        schedule(context, type)
        if (!enabled) context.getSystemService(NotificationManager::class.java).cancel(ID + types.indexOfFirst { it.first == type })
    }

    fun sendTest(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Připomínky položek", NotificationManager.IMPORTANCE_DEFAULT))
        if (!allowed(context)) return false
        val open = PendingIntent.getActivity(context, 419, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            manager.notify(419, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("Testovací oznámení")
                .setContentText("Oznámení Inženýrů fungují.").setContentIntent(open).setAutoCancel(true).build())
            true
        } catch (_: SecurityException) { false }
    }

    fun timing(context: Context, type: String): Timing {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Timing(prefs.getInt("${type}_days", 1).coerceIn(0, 365),
            prefs.getInt("${type}_hour", 18).coerceIn(0, 23),
            prefs.getInt("${type}_minute", 0).coerceIn(0, 59))
    }

    fun saveTiming(context: Context, type: String, value: Timing) {
        require(types.any { it.first == type })
        require(value.days in 0..365 && value.hour in 0..23 && value.minute in 0..59)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("${type}_days", value.days).putInt("${type}_hour", value.hour)
            .putInt("${type}_minute", value.minute).apply()
        schedule(context, type)
    }

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("enabled", false)

    fun allowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled)
        if (!enabled) editor.remove("counts")
        editor.apply()
        if (enabled) context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Připomínky položek", NotificationManager.IMPORTANCE_DEFAULT))
        schedule(context)
        if (!enabled) types.indices.forEach { context.getSystemService(NotificationManager::class.java).cancel(ID + it) }
    }

    // Store only dates and counts; never persist decrypted item titles or passwords.
    fun sync(context: Context, database: JSONObject, completed: Set<String>) {
        if (!enabled(context)) return
        val counts = JSONObject()
        val tasks = database.optJSONArray("tasks")
        if (tasks != null) for (index in 0 until tasks.length()) {
            val item = tasks.optJSONObject(index) ?: continue
            if (DatabaseRecords.key(item) in completed) continue
            val date = DatabaseRecords.parseDate(item.optString("date")) ?: continue
            val key = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(date)
            val day = counts.optJSONObject(key) ?: JSONObject().also { counts.put(key, it) }
            val type = DatabaseRecords.type(item)
            day.put(type, day.optInt(type) + 1)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("counts", counts.toString()).apply()
        schedule(context)
    }

    fun schedule(context: Context, onlyType: String? = null) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        types.forEachIndexed { index, (type, _) ->
            if (onlyType != null && type != onlyType) return@forEachIndexed
            val value = timing(context, type)
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, value.hour); set(Calendar.MINUTE, value.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            val due = (next.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, value.days) }
            val intent = PendingIntent.getBroadcast(context, ID + index,
                Intent(context, ReminderReceiver::class.java).setAction("cz.inzenyri.mobile.REMINDER")
                    .putExtra("type", type)
                    .putExtra("due_day", SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(due.time)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.cancel(intent)
            if (enabled(context) && typeEnabled(context, type)) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, intent)
        }
    }

    fun deliver(context: Context, intent: Intent) {
        if (!enabled(context)) return
        val type = intent.getStringExtra("type") ?: return
        val index = types.indexOfFirst { it.first == type }
        if (index < 0) return
        if (!typeEnabled(context, type)) return
        val key = intent.getStringExtra("due_day") ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val counts = runCatching { JSONObject(prefs.getString("counts", "{}") ?: "{}").optJSONObject(key) }.getOrNull()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Připomínky položek", NotificationManager.IMPORTANCE_DEFAULT))
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Calendar.getInstance().time)
        val count = counts?.optInt(type) ?: 0
        if (count > 0 && key >= today && allowed(context) && prefs.getString("last_$type", null) != key) {
            val text = "Nesplněných položek: $count · datum ${key.split("-").reversed().joinToString(".")}"
            val open = PendingIntent.getActivity(context, ID + index, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("${types[index].second} · připomínka")
                .setContentText(text).setContentIntent(open).setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            try {
                manager.notify(ID + index, notification)
                prefs.edit().putString("last_$type", key).apply()
            } catch (_: SecurityException) { /* Permission can be revoked at any time. */ }
        }
        schedule(context, type)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "cz.inzenyri.mobile.REMINDER") ReminderNotifications.deliver(context, intent)
        else ReminderNotifications.schedule(context)
    }
}
