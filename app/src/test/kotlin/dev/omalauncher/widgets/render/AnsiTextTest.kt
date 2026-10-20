package dev.omalauncher.widgets.render

import dev.omalauncher.widgets.render.AnsiText.RGB
import dev.omalauncher.widgets.render.AnsiText.Run
import dev.omalauncher.widgets.render.AnsiText.Style
import org.junit.Assert.assertEquals
import org.junit.Test

class AnsiTextTest {
    private val e = "\u001b"

    @Test fun colorsAndStyles() {
        val (runs, end) = AnsiText.parse("a$e[1;31mb$e[22;39;44mc$e[0md", Style())
        assertEquals(listOf(Run("a", Style()), Run("b", Style(fg = 1, bold = true)), Run("c", Style(bg = 4)), Run("d", Style())), runs)
        assertEquals(Style(), end)
        assertEquals(Style(fg = 9, bg = 10), AnsiText.parse("$e[91;102m", Style()).second)
        assertEquals(Style(dim = true, italic = true, underline = true, inverse = true), AnsiText.parse("$e[2;3;4;7m", Style()).second)
    }

    @Test fun indexedAndTrueColor() {
        assertEquals(Style(fg = 3), AnsiText.parse("$e[38;5;3m", Style()).second)
        // The cube's 16 is black, 231 white; 232 the darkest gray.
        assertEquals(Style(fg = RGB), AnsiText.parse("$e[38;5;16m", Style()).second)
        assertEquals(Style(fg = RGB or 0xFFFFFF), AnsiText.parse("$e[38;5;231m", Style()).second)
        assertEquals(Style(bg = RGB or 0x080808), AnsiText.parse("$e[48;5;232m", Style()).second)
        assertEquals(Style(fg = RGB or 0x0A141E), AnsiText.parse("$e[38;2;10;20;30m", Style()).second)
        assertEquals(Style(fg = RGB or 0x0A141E), AnsiText.parse("$e[38:2::10:20:30m", Style()).second)
        // Out of range or cut short: no color, and the rest still applies.
        assertEquals(Style(bold = true), AnsiText.parse("$e[38;2;300;0;0;1m", Style()).second.copy(fg = AnsiText.DEFAULT))
    }

    @Test fun otherEscapesAreDropped() {
        val line = "$e]0;title\u0007$e[2K$e[1;1Hhi$e]8;;https://x$e\\link$e]8;;$e\\$e(B!"
        assertEquals("hilink!", AnsiText.plain(line))
        assertEquals("hilink!", AnsiText.parse(line, Style()).first.joinToString("") { it.text })
    }

    @Test fun styleCarriesToTheNextLine() {
        val (_, end) = AnsiText.parse("$e[32mgreen", Style())
        assertEquals(listOf(Run("still", Style(fg = 2))), AnsiText.parse("still", end).first)
    }
}
