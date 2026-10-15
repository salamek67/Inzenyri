package cz.inzenyri.mobile

import org.json.JSONObject

/** Legacy quiz remains readable alongside additional, named practice sets. */
internal object PracticeQuizzes {
    fun questions(quiz: JSONObject): List<JSONObject> = quiz.optJSONArray("questions")?.let { values ->
        (0 until values.length()).mapNotNull { values.optJSONObject(it) }
    }.orEmpty()

    fun forTest(item: JSONObject): List<JSONObject> = buildList {
        fun include(quiz: JSONObject?) {
            val questions = quiz?.optJSONArray("questions") ?: return
            if ((0 until questions.length()).any { questions.optJSONObject(it) != null }) add(quiz)
        }
        include(item.optJSONObject("quiz"))
        item.optJSONArray("quizzes")?.let { quizzes ->
            for (index in 0 until quizzes.length()) include(quizzes.optJSONObject(index))
        }
    }

    fun title(quiz: JSONObject, index: Int): String = quiz.optString("title").trim()
        .ifBlank { if (index == 0) "Základní procvičování" else "Kvíz ${index + 1}" }
}
