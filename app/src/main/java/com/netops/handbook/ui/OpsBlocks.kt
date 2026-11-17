package com.netops.handbook.ui

import android.view.ViewGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.netops.handbook.lens.NativeBlocks
import com.netops.handbook.lens.NativeTopoView
import kotlinx.coroutines.delay
import org.json.JSONArray

/**
 * 全站渲染组件库（iOS 26 + MagicOS 11 微组件液态玻璃升级版）：
 * 全部列表子项原语均走单遍 `drawWithCache` 零离屏缓冲路径，严控 25,000+ 块滚动帧率。
 */

/** note tone → 底色（NativeBlocks.noteColor 同款默认链）。 */
private fun noteColorOf(c: OpsColors, tone: String): Color = when (tone) {
    "warn" -> c.noteWarn
    "danger" -> c.noteDanger
    "ok" -> c.noteOk
    "plain" -> c.notePlain
    else -> c.noteInfo
}

/** 等宽代码文字色（dark? E2E8F0 : CBD5E1，NativeBlocks.code 同款）。 */
private fun codeTextColor(dark: Boolean) = if (dark) Color(0xFFE2E8F0) else Color(0xFFCBD5E1)

private val CardCornerShape = RoundedCornerShape(16.dp)
private val NoteCornerShape = RoundedCornerShape(12.dp)
private val CodeCornerShape = RoundedCornerShape(12.dp)
private val CopyPillShape = RoundedCornerShape(8.dp)
private val BadgeCornerShape = RoundedCornerShape(9.dp)
private val StepBadgeShape = RoundedCornerShape(7.dp)

/** 卡片：单遍 `glassCardSurface` 空间玻璃层板 + 可选 `glassClickable` 弹性聚光反馈。 */
@Composable
fun OpsCard(
    modifier: Modifier = Modifier,
    colors: OpsColors = LocalOpsColors.current,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val cardMod = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .glassClickable(pressedScale = 0.98f, glowBoost = 0.06f, onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }
    Column(
        cardMod
            .glassCardSurface(colors, CardCornerShape)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) { content() }
}

/** 芯片行文本：11sp SemiBold accent + `glassBadgeSurface` 晶莹弧光细边。 */
@Composable
fun OpsChip(text: String, colors: OpsColors = LocalOpsColors.current) {
    Text(
        text,
        color = colors.accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .glassBadgeSurface(colors, BadgeCornerShape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/** Chevron 矢量指示箭头（rotation: 0=右 / 90=下 / -90=上）。 */
@Composable
fun OpsChevron(colors: OpsColors = LocalOpsColors.current, rotation: Float = 90f, sizeDp: Int = 22) {
    androidx.compose.foundation.Canvas(Modifier.size(sizeDp.dp).rotate(rotation)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val dx = 3.6.dp.toPx()
        val dy = 7.0.dp.toPx()
        val path = Path().apply {
            moveTo(cx - dx, cy - dy)
            lineTo(cx + dx, cy)
            lineTo(cx - dx, cy + dy)
        }
        drawPath(
            path,
            color = colors.textMute,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** 卡片头行：芯片 + 标题(14.5sp bold) + 元信息(11sp) + 下箭头。 */
@Composable
fun OpsHead(
    chip: String,
    title: String,
    meta: String,
    metaAccent: Boolean = false,
    expanded: Boolean = false,
    colors: OpsColors = LocalOpsColors.current,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        OpsChip(chip, colors)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textMain, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(meta, color = if (metaAccent) colors.accent else colors.textMute, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        OpsChevron(colors, rotation = if (expanded) 0f else 90f)
    }
}

/** note 块：13.5sp 正文 + tone 底色 12dp 圆角 + 语义色柔和镜面细边（单遍 `drawWithCache`）。 */
@Composable
fun OpsNote(text: String, tone: String, colors: OpsColors = LocalOpsColors.current) {
    val bg = noteColorOf(colors, tone)
    Text(
        text,
        color = colors.textMain,
        fontSize = 13.5.sp,
        lineHeight = 17.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(NoteCornerShape)
            .drawWithCache {
                val level = AppShellState.glassLevel
                val h = size.height.coerceAtLeast(1f)
                val strokePx = 0.75.dp.toPx()
                val half = strokePx * 0.5f
                val cr = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                val insetCr = CornerRadius(
                    (cr.x - half).coerceAtLeast(0f),
                    (cr.y - half).coerceAtLeast(0f),
                )
                val insetSize = Size(
                    (size.width - strokePx).coerceAtLeast(0f),
                    (h - strokePx).coerceAtLeast(0f),
                )
                val stroke = Stroke(width = strokePx)
                val toneRim = when (tone) {
                    "warn" -> Color(0xFFF59E0B)
                    "danger" -> Color(0xFFEF4444)
                    "ok" -> Color(0xFF22C55E)
                    "plain" -> colors.border
                    else -> colors.accent
                }
                if (level == "none") {
                    val flatBorder = colors.border.copy(alpha = 0.55f)
                    onDrawBehind {
                        drawRoundRect(color = bg, cornerRadius = cr)
                        drawRoundRect(
                            color = flatBorder,
                            topLeft = Offset(half, half),
                            size = insetSize,
                            cornerRadius = insetCr,
                            style = stroke,
                        )
                    }
                } else {
                    val sheenEndY = (h * 0.34f).coerceAtLeast(1f)
                    val sheenBrush = Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = if (colors.dark) 0.06f else 0.32f),
                        1.0f to Color.White.copy(alpha = 0f),
                        startY = 0f,
                        endY = sheenEndY,
                    )
                    val rimBrush = Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = if (colors.dark) 0.24f else 0.74f),
                        0.45f to toneRim.copy(alpha = if (colors.dark) 0.22f else 0.18f),
                        1.0f to toneRim.copy(alpha = if (colors.dark) 0.30f else 0.28f),
                        startY = 0f,
                        endY = h,
                    )
                    onDrawBehind {
                        drawRoundRect(color = bg, cornerRadius = cr)
                        drawRoundRect(brush = sheenBrush, cornerRadius = cr)
                        drawRoundRect(
                            brush = rimBrush,
                            topLeft = Offset(half, half),
                            size = insetSize,
                            cornerRadius = insetCr,
                            style = stroke,
                        )
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/** 终端块右上角小巧液态玻璃复制胶囊（静止期零协程开销）。 */
@Composable
private fun CodeCopyGlassPill(
    codeText: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    if (copied) {
        LaunchedEffect(Unit) {
            delay(1400)
            copied = false
        }
    }
    Text(
        text = if (copied) "✓ 已复制" else "复制",
        color = if (copied) Color(0xFF93C5FD) else Color(0xFFCBD5E1),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .glassClickable(
                pressedScale = 0.93f,
                glowBoost = 0.10f,
                onClick = {
                    clipboard.setText(AnnotatedString(codeText))
                    copied = true
                }
            )
            .clip(CopyPillShape)
            .drawWithCache {
                val level = AppShellState.glassLevel
                val h = size.height.coerceAtLeast(1f)
                val strokePx = 0.6.dp.toPx()
                val half = strokePx * 0.5f
                val cr = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                val insetCr = CornerRadius(
                    (cr.x - half).coerceAtLeast(0f),
                    (cr.y - half).coerceAtLeast(0f),
                )
                val insetSize = Size(
                    (size.width - strokePx).coerceAtLeast(0f),
                    (h - strokePx).coerceAtLeast(0f),
                )
                val stroke = Stroke(width = strokePx)
                if (level == "none") {
                    val bg = if (copied) Color(0x382563EB) else Color(0x16FFFFFF)
                    val rim = if (copied) Color(0x8060A5FA) else Color(0x2EFFFFFF)
                    onDrawBehind {
                        drawRoundRect(color = bg, cornerRadius = cr)
                        drawRoundRect(
                            color = rim,
                            topLeft = Offset(half, half),
                            size = insetSize,
                            cornerRadius = insetCr,
                            style = stroke,
                        )
                    }
                } else {
                    val baseBg = if (copied) {
                        Color(0xFF2563EB).copy(alpha = 0.36f)
                    } else {
                        Color.White.copy(alpha = 0.09f)
                    }
                    val domeBrush = Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = if (copied) 0.28f else 0.18f),
                        1.0f to Color.White.copy(alpha = 0f),
                        startY = 0f,
                        endY = (h * 0.48f).coerceAtLeast(1f),
                    )
                    val rimBrush = Brush.verticalGradient(
                        0.0f to if (copied) Color(0xFF93C5FD).copy(alpha = 0.78f) else Color.White.copy(alpha = 0.36f),
                        0.5f to Color.White.copy(alpha = 0.10f),
                        1.0f to if (copied) Color(0xFF60A5FA).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.18f),
                        startY = 0f,
                        endY = h,
                    )
                    onDrawBehind {
                        drawRoundRect(color = baseBg, cornerRadius = cr)
                        drawRoundRect(brush = domeBrush, cornerRadius = cr)
                        drawRoundRect(
                            brush = rimBrush,
                            topLeft = Offset(half, half),
                            size = insetSize,
                            cornerRadius = insetCr,
                            style = stroke,
                        )
                    }
                }
            }
            .padding(horizontal = 7.5.dp, vertical = 3.dp)
    )
}

/** 终端块：等宽 12sp + 横向滚动 + 曜石暗玻璃细边 + 小巧液态玻璃复制胶囊。 */
@Composable
fun OpsCodeBlock(text: String, label: String, colors: OpsColors = LocalOpsColors.current) {
    val bg = colors.codeBg
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CodeCornerShape)
            .drawWithCache {
                val level = AppShellState.glassLevel
                val h = size.height.coerceAtLeast(1f)
                val strokePx = 0.7.dp.toPx()
                val half = strokePx * 0.5f
                val cr = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                val insetCr = CornerRadius(
                    (cr.x - half).coerceAtLeast(0f),
                    (cr.y - half).coerceAtLeast(0f),
                )
                val insetSize = Size(
                    (size.width - strokePx).coerceAtLeast(0f),
                    (h - strokePx).coerceAtLeast(0f),
                )
                val stroke = Stroke(width = strokePx)
                if (level == "none") {
                    val flatRim = Color.White.copy(alpha = 0.10f)
                    onDrawBehind {
                        drawRoundRect(color = bg, cornerRadius = cr)
                        drawRoundRect(
                            color = flatRim,
                            topLeft = Offset(half, half),
                            size = insetSize,
                            cornerRadius = insetCr,
                            style = stroke,
                        )
                    }
                } else {
                    val rimBrush = Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = 0.20f),
                        0.4f to Color.White.copy(alpha = 0.05f),
                        1.0f to Color.White.copy(alpha = 0.10f),
                        startY = 0f,
                        endY = h,
                    )
                    onDrawBehind {
                        drawRoundRect(color = bg, cornerRadius = cr)
                        drawRoundRect(
                            brush = rimBrush,
                            topLeft = Offset(half, half),
                            size = insetSize,
                            cornerRadius = insetCr,
                            style = stroke,
                        )
                    }
                }
            }
    ) {
        if (label.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 6.dp, end = 8.dp, bottom = 2.dp)
            ) {
                Text(
                    label,
                    color = colors.codeLabel,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                CodeCopyGlassPill(codeText = text)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, top = 3.dp, end = 12.dp, bottom = 11.dp)
            ) {
                Text(
                    text,
                    color = codeTextColor(colors.dark),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.5.sp
                )
            }
        } else {
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, top = 10.dp, end = 60.dp, bottom = 10.dp)
                ) {
                    Text(
                        text,
                        color = codeTextColor(colors.dark),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.5.sp
                    )
                }
                CodeCopyGlassPill(
                    codeText = text,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 7.dp)
                )
            }
        }
    }
}

/** ul 列表：dot（danger="!" / "·"）+ 13.5sp 条目。 */
@Composable
fun OpsUl(items: List<String>, danger: Boolean, colors: OpsColors = LocalOpsColors.current) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in items) {
            Row {
                Text(
                    if (danger) "!" else "·",
                    color = if (danger) colors.noteDanger else colors.textMute,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(16.dp)
                )
                Text(item, color = colors.textMain, fontSize = 13.5.sp, lineHeight = 17.5.sp)
            }
        }
    }
}

/** steps/tree 列表：玻璃序号徽章 20dp + 可选「头：正文」拆分（tree 语义）。 */
@Composable
fun OpsSteps(items: List<String>, tree: Boolean, colors: OpsColors = LocalOpsColors.current) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((idx, s) in items.withIndex()) {
            var head: String? = null
            var rest = s
            if (tree) {
                val parts = s.split(':', '：', limit = 2)
                if (parts.size == 2) {
                    head = parts[0]
                    rest = parts[1]
                }
            }
            Row {
                Text(
                    (idx + 1).toString(),
                    color = colors.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .size(20.dp)
                        .glassBadgeSurface(colors, StepBadgeShape)
                        .wrapContentSize(Alignment.Center)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    if (head != null) {
                        Text(head, color = colors.textMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        rest,
                        color = if (head != null) colors.textSub else colors.textMain,
                        fontSize = 13.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

/** blocks 渲染器（k/t/tone/items/dg 与原生同源）。 */
@Composable
fun OpsBlocks(blocks: JSONArray, colors: OpsColors = LocalOpsColors.current) {
    LocalContext.current
    for (i in 0 until blocks.length()) {
        val b = blocks.optJSONObject(i) ?: continue
        val k = b.optString("k")
        val t = b.optString("t")
        Column(Modifier.fillMaxWidth()) {
            when (k) {
                "h" -> Column(Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                    Text(t, color = colors.textMute, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                "note" -> Column(Modifier.padding(bottom = 8.dp)) { OpsNote(t, b.optString("tone"), colors) }
                "code" -> Column(Modifier.padding(bottom = 8.dp)) { OpsCodeBlock(t, b.optString("label"), colors) }
                "ul" -> {
                    val items = b.optJSONArray("items") ?: return@Column
                    val list = (0 until items.length()).map { items.optString(it) }
                    Column(Modifier.padding(bottom = 6.dp)) {
                        OpsUl(list, danger = b.optString("tone") == "danger", colors)
                    }
                }
                "steps", "tree" -> {
                    val items = b.optJSONArray("items") ?: return@Column
                    val list = (0 until items.length()).map { items.optString(it) }
                    Column(Modifier.padding(bottom = 8.dp)) {
                        OpsSteps(list, tree = k == "tree", colors)
                    }
                }
                "topo" -> {
                    val dg = b.optJSONObject("dg") ?: return@Column
                    key(colors) {
                        AndroidView(
                            factory = { c2 ->
                                NativeTopoView(c2, nativePalette(colors, c2), dg).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .heightIn(min = 60.dp)
                        )
                    }
                }
            }
        }
    }
}

/** OpsColors → 旧 NativeBlocks.Palette（topo 等过渡期桥）。 */
fun nativePalette(colors: OpsColors, ctx: android.content.Context): NativeBlocks.Palette {
    val p = NativeBlocks.Palette()
    p.density = ctx.resources.displayMetrics.density
    p.dark = colors.dark
    p.direct = colors.direct
    p.bg = colors.bg.toArgb(); p.cardBg = colors.cardBg.toArgb()
    p.border = colors.border.toArgb(); p.hairline = colors.hairline.toArgb()
    p.textMain = colors.textMain.toArgb(); p.textSub = colors.textSub.toArgb()
    p.textMute = colors.textMute.toArgb(); p.accent = colors.accent.toArgb()
    p.chipBgOn = colors.chipBgOn.toArgb(); p.chipBgOff = colors.chipBgOff.toArgb()
    p.noteInfo = colors.noteInfo.toArgb(); p.noteWarn = colors.noteWarn.toArgb()
    p.noteDanger = colors.noteDanger.toArgb(); p.noteOk = colors.noteOk.toArgb()
    p.notePlain = colors.notePlain.toArgb(); p.codeBg = colors.codeBg.toArgb()
    p.codeLabel = colors.codeLabel.toArgb()
    return p
}

/**
 * 横屏双列流（NativeBlocks.Flow 声明式版）：按原序遍历，wide 卡满宽独占一行并
 * 重置配对流，非 wide 卡两两成行（右卡缺位补 Spacer）。
 */
@Composable
fun OpsFlow(cards: List<@Composable () -> Unit>, wideSet: Set<Int>, land: Boolean) {
    if (!land) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { cards.forEach { it() } }
        return
    }
    val rows = ArrayList<List<Int>>()
    val pair = IntArray(2)
    var count = 0
    for (k in cards.indices) {
        if (wideSet.contains(k)) {
            if (count == 1) rows.add(listOf(pair[0]))
            rows.add(listOf(k))
            count = 0
        } else {
            pair[count] = k
            count++
            if (count == 2) {
                rows.add(listOf(pair[0], pair[1]))
                count = 0
            }
        }
    }
    if (count == 1) rows.add(listOf(pair[0]))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in rows) {
            if (row.size == 1) cards[row[0]]()
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Column(Modifier.weight(1f)) { cards[row[0]]() }
                Column(Modifier.weight(1f)) { cards[row[1]]() }
            }
        }
    }
}
