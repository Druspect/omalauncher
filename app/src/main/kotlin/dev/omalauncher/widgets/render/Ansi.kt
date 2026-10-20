package dev.omalauncher.widgets.render

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.omalauncher.ui.Palette
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.num
import dev.omalauncher.widgets.lang.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.floor

/** At most this often an Ansi lays out new text; what arrives in between is skipped, never the last. */
private const val FRAME_MS = 80L

/** How long its size must hold before onGrid hears of it: the keyboard and resizing change it many times. */
private const val GRID_CALM_MS = 150L

/** Lines are this many times the font size apart. */
private const val LINE_HEIGHT = 1.25f

/**
 * `Ansi { text: … }`: a terminal's output with its colors, one line per row, never wrapped. Its font shrinks
 * from fontSize down to minFontSize until the widest line fits; it scrolls, and follows the end while it's
 * there. `pan: true` lets it scroll sideways too, which a tile only wants once tapped (a swipe turns the page
 * otherwise). onGrid hears the columns and rows that fit at fontSize, for a program that sizes itself to it.
 */
@Composable
internal fun AnsiNode(
    p: Props,
    mod: Modifier,
    sized: Boolean,
) {
    val text = p.observe("text")?.text().orEmpty()
    val maxSp = (p.observe("fontSize")?.num() ?: 11.0).toFloat().coerceIn(4f, 64f)
    val minSp = (p.observe("minFontSize")?.num()?.toFloat() ?: maxSp).coerceIn(4f, maxSp)
    val pan = p.observe("pan")?.truthy == true
    val palette = List(16) { i -> Palette.named("color$i") ?: DEFAULT_PALETTE[i] }
    val fg = Palette.foreground
    val bg = Palette.background
    val renderer = remember(palette, fg, bg) { AnsiRenderer(palette, fg, bg) }
    val latest by rememberUpdatedState(text)
    // Parsed off the main thread; a busy program's output is thinned to a frame per FRAME_MS.
    val rendered by produceState<AnsiRenderer.Frame?>(null, renderer) {
        snapshotFlow { latest }.conflate().collect { t ->
            value = withContext(Dispatchers.Default) { renderer.render(t) }
            delay(FRAME_MS)
        }
    }
    val lines = rendered?.lines.orEmpty()
    val widest = rendered?.columns ?: 0

    val family = WidgetMono.fontFamily
    BoxWithConstraints(mod.then(if (sized) Modifier else Modifier.fillMaxSize())) {
        val density = LocalDensity.current
        // A monospace cell's real advance, as a share of the font size.
        val measurer = rememberTextMeasurer()
        val cellEm =
            remember(family, density) {
                val probe = TextStyle(fontFamily = family, fontSize = 100.sp)
                (measurer.measure("M".repeat(20), probe).size.width / 20f / with(density) { 100.sp.toPx() }).takeIf { it.isFinite() && it > 0 } ?: 0.6f
            }
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }

        p.el.handlers["onGrid"]?.let { onGrid ->
            val cell = with(density) { maxSp.sp.toPx() }
            val columns = if (width.isFinite()) floor(width / (cell * cellEm)).toInt() else 0
            val rows = if (height.isFinite()) floor(height / (cell * LINE_HEIGHT)).toInt() else 0
            LaunchedEffect(columns, rows) {
                delay(GRID_CALM_MS)
                if (columns > 0 && rows > 0) onGrid.run(p.env, listOf(Obj(mapOf("columns" to Num(columns.toDouble()), "rows" to Num(rows.toDouble())))))
            }
        }

        val fit = if (widest > 0 && width.isFinite()) with(density) { (width / (widest * cellEm)).toSp().value } else maxSp
        val size = fit.coerceIn(minSp, maxSp).sp
        val style = remember(family, size) { TextStyle(fontFamily = family, fontSize = size, lineHeight = size * LINE_HEIGHT, color = fg) }
        // As wide as the widest line, so panning reaches its end when it's still wider than the node.
        val contentWidth = maxOf(maxWidth, with(density) { (widest * cellEm * size.toPx() * 1.02f).toDp() })
        val list = rememberLazyListState()
        // Follows new output while at the end; stays put once scrolled up to read.
        var follow by remember { mutableStateOf(true) }
        LaunchedEffect(list.isScrollInProgress) {
            if (!list.isScrollInProgress) follow = !list.canScrollForward
        }
        LaunchedEffect(lines, follow) { if (follow && lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
        val sideways = rememberScrollState()
        // Let go of, it shows the start of its lines again.
        LaunchedEffect(pan) { if (!pan) sideways.animateScrollTo(0) }
        Box(Modifier.fillMaxSize().horizontalScroll(sideways, enabled = pan)) {
            LazyColumn(Modifier.width(contentWidth).fillMaxHeight(), state = list) {
                // By position: an unchanged line keeps its AnnotatedString, so it's skipped.
                items(lines.size) { i -> BasicText(lines[i], style = style, softWrap = false, maxLines = 1) }
            }
        }
    }
}

/**
 * Styles frames line by line. A frame is mostly the last one's lines, so each line's runs are kept by its text
 * and the style it starts in, and an unchanged line returns the same [AnnotatedString] as before.
 */
internal class AnsiRenderer(
    private val palette: List<Color>,
    private val foreground: Color,
    private val background: Color,
) {
    class Frame(
        val lines: List<AnnotatedString>,
        /** The widest line's length in cells. */
        val columns: Int,
    )

    private class Line(
        val text: AnnotatedString,
        val end: AnsiText.Style,
        val columns: Int,
    )

    private var cache = HashMap<Pair<AnsiText.Style, String>, Line>()

    /** [text]'s lines, without the blank ones a terminal leaves below its cursor. */
    fun render(text: String): Frame {
        val raw = text.split('\n').map { it.removeSuffix("\r") }.dropLastWhile { AnsiText.plain(it).isBlank() }
        val next = HashMap<Pair<AnsiText.Style, String>, Line>(raw.size * 2)
        var style = AnsiText.Style()
        var columns = 0
        val out = ArrayList<AnnotatedString>(raw.size)
        for (l in raw) {
            val key = style to l
            val line =
                next[key] ?: cache[key] ?: run {
                    val (runs, end) = AnsiText.parse(l, style)
                    Line(styled(runs), end, runs.sumOf { it.text.length })
                }
            next[key] = line
            out += line.text
            columns = maxOf(columns, line.columns)
            style = line.end
        }
        cache = next
        return Frame(out, columns)
    }

    private fun color(c: Long): Color? =
        when {
            c == AnsiText.DEFAULT -> null
            c < 16 -> palette[c.toInt()]
            else -> Color(0xFF000000.toInt() or (c and 0xFFFFFF).toInt())
        }

    private fun styled(runs: List<AnsiText.Run>) =
        buildAnnotatedString {
            for (r in runs) {
                val s = r.style
                var fg = color(s.fg)
                var bg = color(s.bg)
                if (s.inverse) {
                    val f = fg
                    fg = bg ?: background
                    bg = f ?: foreground
                }
                if (s.dim) fg = (fg ?: foreground).copy(alpha = 0.6f)
                if (fg == null && bg == null && !s.bold && !s.italic && !s.underline) {
                    append(r.text)
                } else {
                    val span =
                        SpanStyle(
                            color = fg ?: Color.Unspecified,
                            background = bg ?: Color.Unspecified,
                            fontWeight = if (s.bold) FontWeight.Bold else null,
                            fontStyle = if (s.italic) FontStyle.Italic else null,
                            textDecoration = if (s.underline) TextDecoration.Underline else null,
                        )
                    withStyle(span) { append(r.text) }
                }
            }
        }
}

/** Tokyo Night's terminal colors, for a theme without all sixteen. */
private val DEFAULT_PALETTE =
    listOf(
        0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
        0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5,
    ).map { Color(it) }
