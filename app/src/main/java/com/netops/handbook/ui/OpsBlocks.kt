package com.netops.handbook.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.netops.handbook.lens.NativeBlocks
import com.netops.handbook.lens.NativeTopoView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 全站渲染组件库（2026-11-14 M2-3）：NativeBlocks.java 的 Compose 版，
 * 逐组件对齐（卡片/芯片/头行/Chevron/note/code/ul/steps/tree/topo/双列流）。
 *
 * 数据形态 = blocks JSON（k/t/tone/items/dg 字段与原生同源），过渡期直接吃
 * JSONObject；topo 分支过渡期 AndroidView 包 NativeTopoView（M5 迁声明式）。
 * glassWrap 不复刻——glass2 record 同树折射时代逐卡玻璃包裹整体作废。
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

/** 卡片：cardBg 16dp 圆角 + 1dp border + padding 14/13（NativeBlocks.card）。 */
@Composable
fun OpsCard(
    modifier: Modifier = Modifier,
    colors: OpsColors = LocalOpsColors.current,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.cardBg)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) { content() }
}

/** 芯片行文本：11sp bold accent + chipBgOn 9dp 圆角 + padding 8/4。 */
@Composable
fun OpsChip(text: String, colors: OpsColors = LocalOpsColors.current) {
    Text(
        text, color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(colors.chipBgOn)
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
            path, color = colors.textMute,
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

/** note 块：13.5sp 正文 + tone 底色 12dp 圆角 + 半透明 border。 */
@Composable
fun OpsNote(text: String, tone: String, colors: OpsColors = LocalOpsColors.current) {
    val bg = noteColorOf(colors, tone)
    Text(
        text, color = colors.textMain, fontSize = 13.5.sp, lineHeight = 17.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/** 终端块：等宽 12sp + 横向滚动 + 标题栏（NativeBlocks.code）。 */
@Composable
fun OpsCodeBlock(text: String, label: String, colors: OpsColors = LocalOpsColors.current) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.codeBg)
    ) {
        if (label.isNotEmpty()) {
            Text(
                label, color = colors.codeLabel, fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp, top = 7.dp, end = 12.dp, bottom = 2.dp)
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 12.dp)
        ) {
            Text(
                text, color = codeTextColor(colors.dark), fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 15.sp
            )
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
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(16.dp)
                )
                Text(item, color = colors.textMain, fontSize = 13.5.sp, lineHeight = 17.5.sp)
            }
        }
    }
}

/** steps/tree 列表：序号 chip 20dp + 可选「头：正文」拆分（tree 语义）。 */
@Composable
fun OpsSteps(items: List<String>, tree: Boolean, colors: OpsColors = LocalOpsColors.current) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((idx, s) in items.withIndex()) {
            var head: String? = null
            var rest = s
            if (tree) {
                val parts = s.split(':', '：', limit = 2)
                if (parts.size == 2) { head = parts[0]; rest = parts[1] }
            }
            Row {
                Text(
                    (idx + 1).toString(),
                    color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.chipBgOn)
                        .wrapContentSize(Alignment.Center)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    if (head != null) {
                        Text(head, color = colors.textMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        rest, color = if (head != null) colors.textSub else colors.textMain,
                        fontSize = 13.sp, lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

/** blocks 渲染器（k/t/tone/items/dg 与原生同源）。 */
@Composable
fun OpsBlocks(blocks: JSONArray, colors: OpsColors = LocalOpsColors.current) {
    val ctx = LocalContext.current
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
                    /* 过渡期：AndroidView 包 NativeTopoView（M5 迁声明式）。
                       key(colors)：NativeTopoView 无 setPalette 接口，主题切换时
                       以 key 强制重建实例换新色板。 */
                    key(colors) {
                        AndroidView(
                            factory = { c2 ->
                                NativeTopoView(c2, nativePalette(colors, c2), dg).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT)
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
    /* 预计算行序列：Single(idx) / Pair(a, b)，顺序 = 原序遍历语义 */
    val rows = ArrayList<List<Int>>()
    var pair = IntArray(2)
    var count = 0
    for (k in cards.indices) {
        if (wideSet.contains(k)) {
            if (count == 1) rows.add(listOf(pair[0]))
            rows.add(listOf(k))
            count = 0
        } else {
            pair[count] = k
            count++
            if (count == 2) { rows.add(listOf(pair[0], pair[1])); count = 0 }
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
