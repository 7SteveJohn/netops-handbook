package com.netops.handbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * 教材全书渲染层（2026-11-18，教材接入）：OpsBlocks 的教材扩展——
 * OpsBlocks.kt 一行不动（现有 11 路由零回归），本文件只新增教材侧块型：
 * h2（小节头）/ p（段落）/ table（GFM 表）/ ol（序号列表）/ hr（分隔线）
 * 与 rich 行内格式（**粗** / `码`）；h/note/code/ul 直接复用 OpsBlocks
 * 公开组件，视觉口径逐项对齐。
 * 数据形态 = gen-textbook.js 产出的 blocks JSON（k/t/rich/items/label/bi），
 * rich 段 = [{t:"plain"|"b"|"c", s}]。
 */

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

/** rich 段 → AnnotatedString（b=SemiBold 加粗，c=等宽行内码 codeBg 小底）。 */
fun tbRich(segs: JSONArray?, colors: OpsColors, fontSize: TextUnit): AnnotatedString {
    if (segs == null || segs.length() == 0) return AnnotatedString("")
    return buildAnnotatedString {
        for (i in 0 until segs.length()) {
            val g = segs.optJSONObject(i) ?: continue
            val s = g.optString("s")
            if (s.isEmpty()) continue
            when (g.optString("t")) {
                "b" -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textMain)) { append(s) }
                "c" -> withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize * 0.88f,
                    color = tbCodeTextColor(colors.dark),
                    background = colors.codeBg
                )) { append(s) }
                else -> append(s)
            }
        }
    }
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

/** 段落（p）：rich 14sp / 21sp 行高，正文可读口径。 */
@Composable
private fun TbP(rich: JSONArray?, colors: OpsColors) {
    Text(
        tbRich(rich, colors, 14.sp),
        color = colors.textMain, fontSize = 14.sp, lineHeight = 21.sp
    )
}

/** 引用块（note）：rich 版 OpsNote 同观感（tone 底色 12dp 圆角 + 半透明 border）。 */
@Composable
private fun TbNote(rich: JSONArray?, tone: String, colors: OpsColors) {
    Text(
        tbRich(rich, colors, 13.5.sp),
        color = colors.textMain, fontSize = 13.5.sp, lineHeight = 17.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tbNoteColor(colors, tone))
            .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/** 表格统一列宽（固定宽保证跨行对齐；超宽表由横向滚动承载）。 */
private val TbCellWidth = 112.dp

/** GFM 表格（table）：横向滚动，表头 chipBgOn + accent，单元格 rich 12sp，斑马纹。 */
@Composable
private fun TbTable(head: JSONArray?, rows: JSONArray?, colors: OpsColors) {
    val nRows = rows?.length() ?: 0
    if ((head?.length() ?: 0) == 0 && nRows == 0) return
    Column(
        Modifier
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
    ) {
        if ((head?.length() ?: 0) > 0) {
            Row(
                Modifier.height(IntrinsicSize.Min).background(colors.chipBgOn)
            ) {
                for (c in 0 until head!!.length()) {
                    Box(Modifier.width(TbCellWidth).fillMaxHeight()) {
                        Text(
                            tbRich(head.optJSONArray(c), colors, 11.5.sp),
                            color = colors.accent, fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold, lineHeight = 14.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        for (r in 0 until nRows) {
            val row = rows?.optJSONArray(r) ?: continue
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline.copy(alpha = 0.6f)))
            Row(
                Modifier.height(IntrinsicSize.Min)
                    .background(if (r % 2 == 1) colors.hairline.copy(alpha = 0.25f) else Color.Transparent)
            ) {
                for (c in 0 until row.length()) {
                    Box(Modifier.width(TbCellWidth).fillMaxHeight()) {
                        Text(
                            tbRich(row.optJSONArray(c), colors, 12.sp),
                            color = colors.textMain, fontSize = 12.sp, lineHeight = 15.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 单块渲染（LazyColumn 逐项使用；k 分发同 TextbookBlocks）。 */
@Composable
fun TbBlock(b: JSONObject) {
    val colors = LocalOpsColors.current
    when (b.optString("k")) {
        "h2" -> TbH2(b.optString("t"), colors)
        "h" -> Text(
            b.optString("t"), color = colors.textMute, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp)
        )
        "p" -> TbP(b.optJSONArray("rich"), colors)
        "note" -> TbNote(b.optJSONArray("rich"), b.optString("tone"), colors)
        "code" -> OpsCodeBlock(b.optString("t"), b.optString("label"), colors)
        "ul" -> {
            val items = b.optJSONArray("items") ?: return
            OpsUl((0 until items.length()).map { items.optString(it) },
                danger = b.optString("tone") == "danger", colors)
        }
        "ol" -> {
            val items = b.optJSONArray("items") ?: return
            OpsSteps((0 until items.length()).map { items.optString(it) },
                tree = false, colors)
        }
        "table" -> TbTable(b.optJSONArray("head"), b.optJSONArray("rows"), colors)
        "hr" -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(1.dp)
                .background(colors.hairline)
        )
    }
}

/** blocks 渲染器（教材扩展版：p/table/ol/hr/h2 新增，其余复用 OpsBlocks 组件）。 */
@Composable
fun TextbookBlocks(blocks: JSONArray) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            TbBlock(b)
        }
    }
}
