package cz.inzenyri.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.roundToInt

internal data class QuizIdentity(val key: String, val fingerprint: String, val size: Int)
internal data class QuizAttempt(var order: List<Int>, var cursor: Int, val answers: MutableList<Int>) {
    val answered get() = answers.count { it == 0 || it == 1 }
    val complete get() = answered == answers.size
    val percent get() = (answers.count { it == 1 } * 100.0 / answers.size).roundToInt()
    val failed get() = order.take(cursor).filter { answers[it] != 1 }
    val roundScore get() = order.take(cursor).count { answers[it] == 1 }
}

/** Stores hashes, indexes and outcome codes only; no questions, written answers or credentials. */
internal class QuizProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences("quiz_progress", Context.MODE_PRIVATE)

    fun identity(item: JSONObject, quiz: JSONObject, index: Int): QuizIdentity {
        val quizzes = PracticeQuizzes.forTest(item)
        val id = quiz.optString("id").takeIf { it.isNotBlank() && quizzes.count { other -> other.optString("id") == it } == 1 }
        val questions = JSONArray(PracticeQuizzes.questions(quiz))
        return QuizIdentity(hash(DatabaseRecords.key(item) + "\u001f" + (id ?: "legacy:$index")),
            hash(canonical(JSONObject().put("questions", questions).put("shuffle", quiz.optBoolean("shuffle")))), questions.length())
    }

    fun read(identity: QuizIdentity): QuizAttempt? { return runCatching {
        val value = JSONObject(preferences.getString(identity.key, null) ?: return null)
        if (value.optInt("version") != 1 || value.optString("fingerprint") != identity.fingerprint) return null
        fun indexes(array: JSONArray): List<Int> = (0 until array.length()).map {
            val number = array.get(it) as? Number ?: error("Invalid index")
            number.toInt().also { index -> require(number.toDouble() == index.toDouble()) }
        }
        val order = indexes(value.getJSONArray("order"))
        val answers = indexes(value.getJSONArray("answers"))
        val rawCursor = value.get("cursor") as? Number ?: error("Invalid cursor")
        val cursor = rawCursor.toInt()
        require(rawCursor.toDouble() == cursor.toDouble())
        require(identity.size > 0 && answers.size == identity.size && answers.all { it in -1..2 })
        require(order.isNotEmpty() && order.distinct().size == order.size && order.all { it in answers.indices })
        require(cursor in 0..order.size && order.take(cursor).none { answers[it] == -1 })
        QuizAttempt(order, cursor, answers.toMutableList())
    }.getOrNull() }

    fun fresh(identity: QuizIdentity, shuffle: Boolean): QuizAttempt = QuizAttempt(
        (0 until identity.size).toList().let { if (shuffle) it.shuffled() else it }, 0,
        MutableList(identity.size) { -1 })

    fun resume(identity: QuizIdentity, shuffle: Boolean): QuizAttempt {
        val attempt = read(identity) ?: fresh(identity, shuffle)
        if (attempt.cursor == attempt.order.size && !attempt.complete) {
            attempt.order = attempt.answers.indices.filter { attempt.answers[it] == -1 || attempt.answers[it] == 2 }
            if (shuffle) attempt.order = attempt.order.shuffled()
            attempt.cursor = 0
        }
        write(identity, attempt)
        return attempt
    }

    fun write(identity: QuizIdentity, attempt: QuizAttempt) {
        preferences.edit().putString(identity.key, JSONObject().put("version", 1)
            .put("fingerprint", identity.fingerprint).put("order", JSONArray(attempt.order))
            .put("cursor", attempt.cursor).put("answers", JSONArray(attempt.answers)).toString()).apply()
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
