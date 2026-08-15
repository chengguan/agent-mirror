package dev.chengguan.mirror

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMarkdownTest {
    @Test
    fun boldAndPlain() {
        val runs = parseInlineMarkdown("use **bold** here")
        assertEquals(
            listOf(
                ChatRun("use ", ChatStyle.Normal),
                ChatRun("bold", ChatStyle.Bold),
                ChatRun(" here", ChatStyle.Normal),
            ),
            runs,
        )
    }

    @Test
    fun italicAndCode() {
        val runs = parseInlineMarkdown("say *hi* and `x`")
        assertEquals(
            listOf(
                ChatRun("say ", ChatStyle.Normal),
                ChatRun("hi", ChatStyle.Italic),
                ChatRun(" and ", ChatStyle.Normal),
                ChatRun("x", ChatStyle.Code),
            ),
            runs,
        )
    }

    @Test
    fun unmatchedStaysLiteral() {
        val runs = parseInlineMarkdown("score ** 3*")
        assertEquals(listOf(ChatRun("score ** 3*", ChatStyle.Normal)), runs)
    }

    @Test
    fun pathsAreCyanButUrlsStayPlain() {
        val runs = parseInlineMarkdown("see /Users/chengguan/.grok/config.toml and https://example.com")
        assertEquals(ChatStyle.Path, runs.first { it.text.contains("config.toml") }.style)
        assertEquals(ChatStyle.Normal, runs.first { it.text.contains("https://example.com") }.style)
    }

    @Test
    fun namedColorTag() {
        val runs = parseInlineMarkdown("see [red]alert[/red] now")
        assertEquals(
            listOf(
                ChatRun("see ", ChatStyle.Normal),
                ChatRun("alert", ChatStyle.Color, "red"),
                ChatRun(" now", ChatStyle.Normal),
            ),
            runs,
        )
    }

    @Test
    fun unknownColorStaysLiteral() {
        val runs = parseInlineMarkdown("[pink]nope[/pink]")
        assertEquals(listOf(ChatRun("[pink]nope[/pink]", ChatStyle.Normal)), runs)
    }
}
