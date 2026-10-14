package cz.inzenyri.mobile

internal object WorksheetSolutionTemplate {
    const val PLACEHOLDER = "Doplň řešení…"

    fun create(page: String, exercises: List<String>): String =
        exercises.joinToString("\n\n") { exercise ->
            "**${page.trim()}/${exercise.trim()}**\n\n$PLACEHOLDER"
        }
}
