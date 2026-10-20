package dev.omalauncher.widgets.render

/**
 * Text with SGR escapes, as `tmux capture-pane -e` and most programs print it, split into styled runs.
 * Colors are numbers: [DEFAULT], a palette index 0–15 (the theme's color0..color15), or [RGB] or'd with
 * 0xRRGGBB for the 256-color cube, its grays and true color. Other escapes (cursor moves, titles, links)
 * are dropped. Pure Kotlin, so it's tested without Android.
 */
internal object AnsiText {
    const val DEFAULT = -1L
    const val RGB = 1L shl 32

    data class Style(
        val fg: Long = DEFAULT,
        val bg: Long = DEFAULT,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false,
    )

    data class Run(
        val text: String,
        val style: Style,
    )

    /** CSI sequences (SGR among them), OSC sequences ended by BEL or ST, and the rest, like ESC ( B for a character set. */
    private val ESCAPES = Regex("\u001b\\[[0-?]*[ -/]*[@-~]|\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)?|\u001b[ -/]*[0-~]")

    /** [line] without escapes: what the terminal shows. */
    fun plain(line: String) = if ('\u001b' in line) line.replace(ESCAPES, "") else line

    /** [line]'s runs, starting in [start]; returns them and the style in effect at its end, which the next line starts in. */
    fun parse(
        line: String,
        start: Style,
    ): Pair<List<Run>, Style> {
        val runs = ArrayList<Run>()
        var style = start
        var from = 0
        for (m in ESCAPES.findAll(line)) {
            if (m.range.first > from) runs += Run(line.substring(from, m.range.first), style)
            val e = m.value
            if (e.length >= 3 && e[1] == '[' && e.last() == 'm') style = sgr(e.substring(2, e.length - 1), style)
            from = m.range.last + 1
        }
        if (from < line.length) runs += Run(line.substring(from), style)
        return runs to style
    }

    private fun sgr(
        params: String,
        start: Style,
    ): Style {
        // 38:2::r:g:b is the colon form of 38;2;r;g;b; the empty color space id drops out.
        val codes = params.split(';', ':').let { if (':' in params) it.filter(String::isNotEmpty) else it }.map { if (it.isEmpty()) 0 else it.toIntOrNull() ?: -1 }
        var s = start
        var i = 0
        while (i < codes.size) {
            when (val c = codes[i++]) {
                0 -> s = Style()
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> s = s.copy(inverse = true)
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> s = s.copy(inverse = false)
                in 30..37 -> s = s.copy(fg = (c - 30).toLong())
                39 -> s = s.copy(fg = DEFAULT)
                in 40..47 -> s = s.copy(bg = (c - 40).toLong())
                49 -> s = s.copy(bg = DEFAULT)
                in 90..97 -> s = s.copy(fg = (c - 90 + 8).toLong())
                in 100..107 -> s = s.copy(bg = (c - 100 + 8).toLong())
                38, 48 -> {
                    val color =
                        when (codes.getOrNull(i++)) {
                            5 -> codes.getOrNull(i++)?.let(::indexed)
                            2 -> {
                                val rgb = codes.subList(i.coerceAtMost(codes.size), (i + 3).coerceAtMost(codes.size))
                                i += 3
                                if (rgb.size == 3 && rgb.all { it in 0..255 }) RGB or (rgb[0].toLong() shl 16) or (rgb[1].toLong() shl 8) or rgb[2].toLong() else null
                            }
                            else -> null
                        }
                    if (color != null) s = if (c == 38) s.copy(fg = color) else s.copy(bg = color)
                }
            }
        }
        return s
    }

    /** A 256-color index: the palette's 16, then a 6×6×6 cube and 24 grays, as xterm draws them. */
    private fun indexed(n: Int): Long? =
        when (n) {
            in 0..15 -> n.toLong()
            in 16..231 -> {
                val k = n - 16
                fun level(v: Int) = if (v == 0) 0L else 55L + v * 40
                RGB or (level(k / 36) shl 16) or (level(k / 6 % 6) shl 8) or level(k % 6)
            }
            in 232..255 -> (8L + (n - 232) * 10).let { RGB or (it shl 16) or (it shl 8) or it }
            else -> null
        }
}
