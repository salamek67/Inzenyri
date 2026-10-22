package cz.inzenyri.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin

/** Native text rendering: no WebView, scripts or external image requests. */
internal class RichTextRenderer(private val context: Context) {
    private val renderers = mutableMapOf<Pair<Int, Int>, Markwon>()

    fun setText(view: TextView, value: String) {
        val renderer =
            renderers.getOrPut(view.currentTextColor to view.textSize.toInt()) {
                Markwon.builder(context)
                    .usePlugin(
                        object : AbstractMarkwonPlugin() {
                            override fun configureConfiguration(
                                builder: MarkwonConfiguration.Builder,
                            ) {
                                builder.linkResolver { view, link ->
                                    val uri = Uri.parse(link)
                                    if (
                                        uri.scheme?.lowercase() in setOf("https", "http", "mailto")
                                    ) {
                                        try {
                                            view.context.startActivity(
                                                Intent(Intent.ACTION_VIEW, uri),
                                            )
                                        } catch (_: Exception) {
                                            Toast.makeText(
                                                    view.context,
                                                    "Odkaz se nepodařilo otevřít.",
                                                    Toast.LENGTH_SHORT,
                                                )
                                                .show()
                                        }
                                    }
                                }
                            }
                        },
                    )
                    .usePlugin(SoftBreakAddsNewLinePlugin.create())
                    .usePlugin(MarkwonInlineParserPlugin.create())
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(
                        JLatexMathPlugin.create(view.textSize) { builder ->
                            builder.inlinesEnabled(true)
                            builder.theme().textColor(view.currentTextColor)
                        },
                    )
                    .build()
            }
        renderer.setMarkdown(view, normalizeMath(value))
    }

    private fun normalizeMath(value: String): String {
        // The database uses $...$ and legacy sqrt(...), /cdot, /approx commands.
        val pattern = Regex("""(`[^`]*`|(?<!\\)\x24{1,2}([^\x24\n]+?)(?<!\\)\x24{1,2})""")
        return pattern.replace(value) { match ->
            if (match.value.startsWith("`")) match.value
            else {
                var math =
                    match.groupValues[2]
                        .replace("/approxeq", "\\approx")
                        .replace("/aproxeq", "\\approx")
                        .replace("/approx", "\\approx")
                        .replace("/cdot", "\\cdot ")
                        .replace("*", "\\cdot ")
                repeat(8) {
                    math =
                        math.replace(Regex("""sqrt\(([^()]*)\)""")) {
                            "\\sqrt{${it.groupValues[1]}}"
                        }
                }
                math = math.replace(Regex("""(?<!\\)sqrt\{"""), "\\\\sqrt{")
                math =
                    math.replace(Regex("""\^(-?[\p{L}\p{N}]+(?:[.,][0-9]+)?)""")) {
                        "^{${it.groupValues[1]}}"
                    }
                "\u0024\u0024$math\u0024\u0024"
            }
        }
    }
}
