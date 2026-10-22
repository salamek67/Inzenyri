package cz.inzenyri.mobile

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure helpers for reading and identifying database items. */
internal object DatabaseRecords {
    fun time(item: JSONObject): String = item.optString("time")
        .takeIf { Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(it) }.orEmpty()

    fun type(item: JSONObject): String {
        val explicit = item.optString("type")
        if (explicit in setOf("task", "test", "event")) return explicit
        val text = "${item.optString("name")} ${item.optString("task")}".lowercase(Locale.ROOT)
        return when {
            Regex("\\b(test|písem|zkoušen|prověrk)").containsMatchIn(text) -> "test"
            Regex("\\b(akce|výlet|exkur|vysvědčen|divad|koncert)").containsMatchIn(text) -> "event"
            else -> "task"
        }
    }

    fun legacyKey(item: JSONObject): String = listOf("type", "name", "date", "task")
        .joinToString("\u001f") { field -> if (field == "type") type(item) else item.optString(field) }

    fun key(item: JSONObject): String = item.optString("id").takeIf { it.isNotBlank() }
        ?.let { "id:$it" } ?: legacyKey(item)

    fun parseDate(value: String): Date? {
        for (pattern in listOf("dd.MM.yyyy", "dd.MM.yy", "yyyy-MM-dd")) {
            try {
                return SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }.parse(value)
            } catch (_: Exception) { }
        }
        return null
    }

    fun hash(value: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
