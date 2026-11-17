package com.netops.handbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 教材全书渲染层（2026-11-18 微组件液态玻璃 & 横屏宽视口表格自适应优化）：
 * - 后台 IO 线程预解析 `TbParsedBlock`，滚动期 UI 线程零 JSON 开销、零离屏缓冲；
 * - `TbParsedTable` 支持宽视口列宽自适应舒展（总列宽不足容器可用宽时等分铺满，超宽表保持横向平滑滚动），
 *   全部边框与列分隔线通过 `drawWithCache` 零额外节点、零重组分配绘制。
 */

/** 预解析行内富文本片段（kind: 'p'=普通, 'b'=加粗, 'c'=行内码）。 */
@Immutable
class TbRichSeg(val kind: Char, val text: String)

/** 预解析教材块模型（IO 线程一次性从 JSONObject 转换，滚动期 UI 线程零 JSON 开销）。 */
@Immutable
sealed interface TbParsedBlock {
    class H2(val text: String) : TbParsedBlock
    class H(val text: String) : TbParsedBlock
    class P(val segs: List<TbRichSeg>) : TbParsedBlock
    class Note(val segs: List<TbRichSeg>, val tone: String) : TbParsedBlock
    class Code(val text: String, val label: String) : TbParsedBlock
    class Ul(val items: List<String>, val danger: Boolean) : TbParsedBlock
    class Ol(val items: List<String>) : TbParsedBlock
    class Table(
        val colCount: Int,
        val head: List<List<TbRichSeg>>,
        val rows: List<List<List<TbRichSeg>>>,
    ) : TbParsedBlock
    data object Hr : TbParsedBlock
    data object Empty : TbParsedBlock
}

fun parseTbRichSegs(segs: JSONArray?): List<TbRichSeg> {
    if (segs == null || segs.length() == 0) return emptyList()
    val out = ArrayList<TbRichSeg>(segs.length())
    for (i in 0 until segs.length()) {
        val g = segs.optJSONObject(i) ?: continue
        val s = g.optString("s")
        if (s.isEmpty()) continue
        val kind = when (g.optString("t")) {
            "b" -> 'b'
            "c" -> 'c'
            else -> 'p'
        }
        out.add(TbRichSeg(kind, s))
    }
    return out
}

fun parseTbBlock(b: JSONObject): TbParsedBlock = when (b.optString("k")) {
    "h2" -> TbParsedBlock.H2(b.optString("t"))
    "h" -> TbParsedBlock.H(b.optString("t"))
    "p" -> TbParsedBlock.P(parseTbRichSegs(b.optJSONArray("rich")))
    "note" -> TbParsedBlock.Note(parseTbRichSegs(b.optJSONArray("rich")), b.optString("tone"))
    "code" -> TbParsedBlock.Code(b.optString("t"), b.optString("label"))
    "ul" -> {
        val items = b.optJSONArray("items")
        if (items == null || items.length() == 0) TbParsedBlock.Empty
        else TbParsedBlock.Ul(
            items = List(items.length()) { items.optString(it) },
            danger = b.optString("tone") == "danger",
        )
    }
    "ol" -> {
        val items = b.optJSONArray("items")
        if (items == null || items.length() == 0) TbParsedBlock.Empty
        else TbParsedBlock.Ol(List(items.length()) { items.optString(it) })
    }
    "table" -> {
        val headArr = b.optJSONArray("head")
        val rowsArr = b.optJSONArray("rows")
        val headList = if (headArr != null && headArr.length() > 0) {
            List(headArr.length()) { c -> parseTbRichSegs(headArr.optJSONArray(c)) }
        } else emptyList()
        val rowsList = if (rowsArr != null && rowsArr.length() > 0) {
            ArrayList<List<List<TbRichSeg>>>(rowsArr.length()).apply {
                for (r in 0 until rowsArr.length()) {
                    val row = rowsArr.optJSONArray(r) ?: continue
                    add(List(row.length()) { c -> parseTbRichSegs(row.optJSONArray(c)) })
                }
            }
        } else emptyList()
        val maxCols = maxOf(headList.size, rowsList.maxOfOrNull { it.size } ?: 0)
        if (maxCols == 0) TbParsedBlock.Empty
        else TbParsedBlock.Table(maxCols, headList, rowsList)
    }
    "hr" -> TbParsedBlock.Hr
    else -> TbParsedBlock.Empty
}

/** 等宽行内码文字色（与 OpsBlocks.codeTextColor 同值：dark E2E8F0 / light CBD5E1）。 */
private fun tbCodeTextColor(dark: Boolean) = if (dark) Color(0xFFE2E8F0) else Color(0xFFCBD5E1)

/** note tone → 底色（与 OpsBlocks.noteColorOf 同款默认链）。 */
private fun tbNoteColor(c: OpsColors, tone: String): Color = when (tone) {
    "warn" -> c.noteWarn
    "danger" -> c.noteDanger
    "ok" -> c.noteOk
    "plain" -> c.notePlain
    else -> c.noteInfo
}

/** 预解析片段列表 → AnnotatedString。 */
private fun buildTbAnnotated(segs: List<TbRichSeg>, colors: OpsColors, fontSize: TextUnit): AnnotatedString {
    if (segs.isEmpty()) return AnnotatedString("")
    if (segs.size == 1 && segs[0].kind == 'p') return AnnotatedString(segs[0].text)
    return buildAnnotatedString {
        for (i in segs.indices) {
            val g = segs[i]
            when (g.kind) {
                'b' -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textMain)) { append(g.text) }
                'c' -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize * 0.88f,
                        color = tbCodeTextColor(colors.dark),
                        background = colors.codeBg,
                    )
                ) { append(g.text) }
                else -> append(g.text)
            }
        }
    }
}

/** rich 段 → AnnotatedString（兼容 JSONArray 直调入口）。 */
fun tbRich(segs: JSONArray?, colors: OpsColors, fontSize: TextUnit): AnnotatedString {
    if (segs == null || segs.length() == 0) return AnnotatedString("")
    return buildTbAnnotated(parseTbRichSegs(segs), colors, fontSize)
}

/** 小节头（h2）：accent 竖条 + 15sp SemiBold，章内导航锚点。 */
@Composable
private fun TbH2(t: String, colors: OpsColors) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    ) {
        Box(
            Modifier
                .requiredWidth(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(t, color = colors.textMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 段落（p）：rich 14sp / 21sp 行高，remember 缓存避免重组重复构建 AnnotatedString。 */
@Composable
private fun TbParsedP(segs: List<TbRichSeg>, colors: OpsColors) {
    val text = remember(segs, colors.dark, colors.textMain, colors.codeBg) {
        buildTbAnnotated(segs, colors, 14.sp)
    }
    Text(text, color = colors.textMain, fontSize = 14.sp, lineHeight = 21.sp)
}

/** 引用块（note）：rich 版 OpsNote 同观感（tone 底色 12dp 圆角 + 柔和镜面细边，drawWithCache 零分配）。 */
@Composable
private fun TbParsedNote(segs: List<TbRichSeg>, tone: String, colors: OpsColors) {
    val text = remember(segs, colors.dark, colors.textMain, colors.codeBg) {
        buildTbAnnotated(segs, colors, 13.5.sp)
    }
    val noteShape = RoundedCornerShape(12.dp)
    Text(
        text,
        color = colors.textMain,
        fontSize = 13.5.sp,
        lineHeight = 17.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(noteShape)
            .background(tbNoteColor(colors, tone))
            .glassSpecularBorder(shape = noteShape, width = 0.75.dp, intensity = 0.85f)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/** 表格单列保底宽度（列少或横屏宽视口时自动等分舒展；超宽多列表保持 112dp 横向平滑滚动）。 */
private val TbCellMinWidth = 112.dp
private val TbTableReadMaxWidth = 780.dp

/**
 * GFM 表格（table）：
 * - 横竖屏自适应列宽：无 `BoxWithConstraints` 二次子组合开销，基于视口安全区算得阅读页片内宽，
 *   当 `TbCellMinWidth * colCount < innerAvail` 时各列等分自然舒展铺满，超宽表则保持横向滚动；
 * - 外框圆角描边与列间细分隔线均由 `drawWithCache` 零额外节点、零重组分配绘制。
 */
@Composable
private fun TbParsedTable(table: TbParsedBlock.Table, colors: OpsColors) {
    val colCount = table.colCount
    if (colCount <= 0) return

    val cfg = LocalConfiguration.current
    val layoutDir = LocalLayoutDirection.current
    val pagePad = rememberOpsPagePadding()
    val outerAvail = (
        cfg.screenWidthDp.dp -
            pagePad.calculateStartPadding(layoutDir) -
            pagePad.calculateEndPadding(layoutDir)
        ).coerceAtLeast(200.dp)
    val innerAvail = (outerAvail.coerceAtMost(TbTableReadMaxWidth) - 28.dp)
        .coerceAtLeast(TbCellMinWidth)

    val naturalWidth = TbCellMinWidth * colCount
    val cellWidth = if (naturalWidth < innerAvail) innerAvail / colCount else TbCellMinWidth
    val tableWidth = cellWidth * colCount

    val tableShape = RoundedCornerShape(10.dp)
    val frameBorderColor = if (colors.dark) colors.border else colors.hairline
    val colDividerColor = colors.hairline.copy(alpha = if (colors.dark) 0.50f else 0.70f)
    val rowDividerColor = colors.hairline.copy(alpha = if (colors.dark) 0.65f else 0.80f)
    val zebraBg = colors.hairline.copy(alpha = if (colors.dark) 0.22f else 0.32f)

    Box(
        Modifier
            .fillMaxWidth()
            .clip(tableShape)
            .drawWithCache {
                val strokePx = 1.dp.toPx()
                val half = strokePx * 0.5f
                val cr = CornerRadius(10.dp.toPx() - half, 10.dp.toPx() - half)
                val topLeft = Offset(half, half)
                val strokeSize = Size(
                    (size.width - strokePx).coerceAtLeast(0f),
                    (size.height - strokePx).coerceAtLeast(0f)
                )
                val strokeStyle = Stroke(width = strokePx)
                onDrawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = frameBorderColor,
                        topLeft = topLeft,
                        size = strokeSize,
                        cornerRadius = cr,
                        style = strokeStyle,
                    )
                }
            }
            .horizontalScroll(rememberScrollState())
    ) {
        Column(
            Modifier
                .width(tableWidth)
                .drawWithCache {
                    val colW = cellWidth.toPx()
                    val divStroke = 0.75.dp.toPx()
                    onDrawWithContent {
                        drawContent()
                        for (c in 1 until colCount) {
                            val x = colW * c
                            drawLine(
                                color = colDividerColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = divStroke,
                            )
                        }
                    }
                }
        ) {
            if (table.head.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.chipBgOn)
                ) {
                    for (c in 0 until colCount) {
                        val cellSegs = table.head.getOrNull(c) ?: emptyList()
                        val cellText = remember(cellSegs, colors.dark, colors.textMain, colors.codeBg) {
                            buildTbAnnotated(cellSegs, colors, 11.5.sp)
                        }
                        Box(Modifier.width(cellWidth)) {
                            Text(
                                cellText,
                                color = colors.accent,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 14.5.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.5.dp)
                            )
                        }
                    }
                }
            }
            for (r in table.rows.indices) {
                val row = table.rows[r]
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.8.dp)
                        .background(rowDividerColor)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (r % 2 == 1) zebraBg else Color.Transparent)
                ) {
                    for (c in 0 until colCount) {
                        val cellSegs = row.getOrNull(c) ?: emptyList()
                        val cellText = remember(cellSegs, colors.dark, colors.textMain, colors.codeBg) {
                            buildTbAnnotated(cellSegs, colors, 12.sp)
                        }
                        Box(Modifier.width(cellWidth)) {
                            Text(
                                cellText,
                                color = colors.textMain,
                                fontSize = 12.sp,
                                lineHeight = 15.5.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 预解析单块渲染（LazyColumn 逐项零 JSON 渲染路径）。 */
@Composable
fun TbBlock(block: TbParsedBlock) {
    val colors = LocalOpsColors.current
    when (block) {
        is TbParsedBlock.H2 -> TbH2(block.text, colors)
        is TbParsedBlock.H -> Text(
            block.text, color = colors.textMute, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp)
        )
        is TbParsedBlock.P -> TbParsedP(block.segs, colors)
        is TbParsedBlock.Note -> TbParsedNote(block.segs, block.tone, colors)
        is TbParsedBlock.Code -> OpsCodeBlock(block.text, block.label, colors)
        is TbParsedBlock.Ul -> OpsUl(block.items, danger = block.danger, colors)
        is TbParsedBlock.Ol -> OpsSteps(block.items, tree = false, colors)
        is TbParsedBlock.Table -> TbParsedTable(block, colors)
        TbParsedBlock.Hr -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(1.dp)
                .background(colors.hairline)
        )
        TbParsedBlock.Empty -> Unit
    }
}

/** 兼容入口：JSONObject 单块渲染（remember 预解析，避免重组重复解析 JSON）。 */
@Composable
fun TbBlock(b: JSONObject) {
    val parsed = remember(b) { parseTbBlock(b) }
    TbBlock(parsed)
}

/** blocks 渲染器（教材扩展版：p/table/ol/hr/h2 新增，其余复用 OpsBlocks 组件）。 */
@Composable
fun TextbookBlocks(blocks: JSONArray) {
    val parsedList = remember(blocks) {
        ArrayList<TbParsedBlock>(blocks.length()).apply {
            for (i in 0 until blocks.length()) {
                val b = blocks.optJSONObject(i) ?: continue
                add(parseTbBlock(b))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in parsedList.indices) {
            TbBlock(parsedList[i])
        }
    }
}
