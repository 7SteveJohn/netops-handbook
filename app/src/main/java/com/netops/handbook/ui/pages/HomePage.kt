package com.netops.handbook.ui.pages

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.AppShellState
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsChevron
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.PageData
import com.netops.handbook.ui.glassBadgeSurface
import com.netops.handbook.ui.glassChipSurface
import com.netops.handbook.ui.glassClickable
import com.netops.handbook.ui.isOpsLandscape
import com.netops.handbook.ui.rememberOpsPagePadding
import org.json.JSONArray
import org.json.JSONObject

/**
 * 学习首页（2026-11-18 微组件液态玻璃 + 教材全书直达卡 + 横屏双列网格）：
 *
 * 形态：
 * - 英雄欢迎卡（OFFLINE · NETCRAFT 玻璃徽章 + 标题 + 三行描述 + 四格统计）
 * - 学习进度卡（64dp 环 + 学习进度 + 宝石蓝液态玻璃「继续学习」胶囊按钮）
 * - 运维教材全书直达大卡（50 章玻璃徽标 + 12 领域 · 50 章 · 约 120 万字 · 全文可搜）
 * - 学习路径阶段卡（1..4 玻璃徽章 + 微高光胶囊进度槽，横屏 2×2 双列网格）
 * - 工具箱 5 项（速查表 / 路线图 / 术语 / 测验 / CLI，横屏双列网格）
 */
@Immutable
private class ToolEntry(val title: String, val sub: String, val route: String)

@Composable
fun HomePage(dataSupplier: () -> org.json.JSONObject?, dark: Boolean, onGo: (String, String) -> Unit) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val ver = PageData.version
    val root = remember(ver) { dataSupplier() }
    val phases = remember(ver) {
        val arr = root?.optJSONArray("phases") ?: JSONArray()
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        out
    }
    val doneSet = remember(ver) {
        val sp = ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
        HashSet(sp.getString("done", "")!!.split(",").filter { it.isNotEmpty() })
    }
    var modTotal = 0
    var doneTotal = 0
    for (p in phases) {
        val ms = p.optJSONArray("mods") ?: continue
        modTotal += ms.length()
        for (k in 0 until ms.length()) {
            val m = ms.optJSONObject(k) ?: continue
            if (doneSet.contains(m.optString("id"))) doneTotal++
        }
    }
    val pct = if (modTotal == 0) 0 else Math.round(doneTotal * 100f / modTotal)
    val nRefs = root?.optJSONArray("refs")?.length() ?: 0
    val nGloss = root?.optJSONArray("glossary")?.length() ?: 0
    val nQuiz = root?.optJSONArray("quiz")?.length() ?: 0
    val nFaults = root?.optJSONArray("faults")?.length() ?: 0
    val nIvs = root?.optJSONArray("interview")?.length() ?: 0
    val dictRows = root?.optJSONObject("dict")?.optJSONArray("rows")?.length() ?: 0
    var nextPid: String? = null
    var nextId: String? = null
    var nextTitle: String? = null
    for (p in phases) {
        val ms = p.optJSONArray("mods") ?: continue
        var hit = false
        for (k in 0 until ms.length()) {
            val m = ms.optJSONObject(k) ?: continue
            if (!doneSet.contains(m.optString("id"))) {
                nextPid = p.optString("id")
                nextId = m.optString("id")
                nextTitle = m.optString("t")
                hit = true
                break
            }
        }
        if (hit) break
    }

    val tools = remember(nRefs, nGloss, nQuiz) {
        listOf(
            ToolEntry("速查表", "$nRefs 张 · 端口 / 掩码 / OSPF / BGP", "refs"),
            ToolEntry("学习路线图", "0-12 个月成长节奏与目标", "roadmap"),
            ToolEntry("术语词典", "$nGloss 条云原生与数通黑话", "glossary"),
            ToolEntry("模拟测验", "$nQuiz 道选择题", "quiz"),
            ToolEntry("CLI 模拟器", "离线练习 VRP / Linux 常用命令", "cli"),
        )
    }

    val pagePadding = rememberOpsPagePadding()
    val landscape = isOpsLandscape()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (landscape) {
            /* 横屏顶区：左「英雄欢迎卡」+ 右「学习进度 + 运维教材全书直达卡」双列并排 */
            item(key = "top_land") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(Modifier.weight(1f)) {
                        HeroWelcomeCard(modTotal, nFaults, dictRows, nIvs, colors)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ProgressSummaryCard(pct, doneTotal, modTotal, nextPid, nextId, nextTitle, colors, onGo)
                        TextbookEntryCard(colors, onGo)
                    }
                }
            }
        } else {
            item(key = "hero") {
                HeroWelcomeCard(modTotal, nFaults, dictRows, nIvs, colors)
            }
            item(key = "progress") {
                ProgressSummaryCard(pct, doneTotal, modTotal, nextPid, nextId, nextTitle, colors, onGo)
            }
            item(key = "textbook_entry") {
                TextbookEntryCard(colors, onGo)
            }
        }

        item(key = "sec_phases") { Section("学习路径", colors) }

        if (landscape) {
            /* 横屏学习路径：2×2 双列网格行 */
            for (rowIdx in 0 until phases.size step 2) {
                val p0 = phases[rowIdx]
                val p1 = phases.getOrNull(rowIdx + 1)
                item(key = "phase_row_${p0.optString("id")}") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.weight(1f)) {
                            PhaseCard(p0, rowIdx + 1, doneSet, colors, compactDesc = true) {
                                onGo("phase", p0.optString("id"))
                            }
                        }
                        if (p1 != null) {
                            Box(Modifier.weight(1f)) {
                                PhaseCard(p1, rowIdx + 2, doneSet, colors, compactDesc = true) {
                                    onGo("phase", p1.optString("id"))
                                }
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            for ((idx, p) in phases.withIndex()) {
                item(key = p.optString("id")) {
                    PhaseCard(p, idx + 1, doneSet, colors, compactDesc = false) {
                        onGo("phase", p.optString("id"))
                    }
                }
            }
        }

        item(key = "sec_tools") { Section("工具箱", colors) }

        if (landscape) {
            /* 横屏工具箱：双列网格行 */
            item(key = "tools_land") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in tools.indices step 2) {
                        val t0 = tools[i]
                        val t1 = tools.getOrNull(i + 1)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(Modifier.weight(1f)) {
                                ToolRow(t0.title, t0.sub, colors) { onGo(t0.route, "") }
                            }
                            if (t1 != null) {
                                Box(Modifier.weight(1f)) {
                                    ToolRow(t1.title, t1.sub, colors) { onGo(t1.route, "") }
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        } else {
            item(key = "tools_port") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in tools) {
                        ToolRow(t.title, t.sub, colors) { onGo(t.route, "") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroWelcomeCard(
    modTotal: Int,
    nFaults: Int,
    dictRows: Int,
    nIvs: Int,
    colors: OpsColors,
) {
    OpsCard {
        Text(
            "OFFLINE · NETCRAFT",
            color = colors.accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .glassBadgeSurface(colors, shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text("脉络 · 网络与运维手册", color = colors.textMain, fontSize = 21.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            "从零基础数通到云原生架构 · 排障字典\n辅助工具，配合教材与真机实验使用\n纯离线运行，无需任何网络连接",
            color = colors.textMute, fontSize = 12.5.sp, lineHeight = 16.5.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            HeroStat("$modTotal", "知识模块", colors, Modifier.weight(1f))
            HeroStat("$nFaults", "排障案例", colors, Modifier.weight(1f))
            HeroStat("$dictRows", "命令对照", colors, Modifier.weight(1f))
            HeroStat("$nIvs", "面试真题", colors, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressSummaryCard(
    pct: Int,
    doneTotal: Int,
    modTotal: Int,
    nextPid: String?,
    nextId: String?,
    nextTitle: String?,
    colors: OpsColors,
    onGo: (String, String) -> Unit,
) {
    val btnShape = RoundedCornerShape(11.dp)
    OpsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring64(pct, colors, Modifier.padding(end = 14.dp))
            Column(Modifier.weight(1f)) {
                Text("学习进度", color = colors.textMain, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text("$doneTotal / $modTotal 个模块", color = colors.textMute, fontSize = 11.5.sp)
                if (nextPid != null) {
                    Spacer(Modifier.height(9.dp))
                    Text(
                        text = "继续：${nextTitle ?: ""}",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassClickable(
                                pressedScale = 0.97f,
                                glowBoost = 0.09f,
                                onClick = { onGo("phase-focus", "$nextPid|$nextId") }
                            )
                            .glassChipSurface(colors, active = true, shape = btnShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text("✓ 全部模块已掌握", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** 首页「运维教材全书」直达玻璃大卡（无需打开左上角抽屉即可一键进入 50 章 120 万字全书）。 */
@Composable
private fun TextbookEntryCard(colors: OpsColors, onGo: (String, String) -> Unit) {
    OpsCard(onClick = { onGo("textbook", "") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glassBadgeSurface(colors, shape = RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "50",
                        color = colors.accent,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 14.sp
                    )
                    Text(
                        "章",
                        color = colors.accent,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 10.sp
                    )
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "运维教材全书",
                        color = colors.textMain,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "全书直达",
                        color = colors.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .glassBadgeSurface(colors, shape = RoundedCornerShape(7.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "12 领域 · 50 章 · 约 120 万字 · 全文可搜",
                    color = colors.textSub,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )
            }
            Spacer(Modifier.width(6.dp))
            OpsChevron(colors, rotation = 0f)
        }
    }
}

@Composable
private fun HeroStat(num: String, label: String, colors: OpsColors, mod: Modifier) {
    Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(num, color = colors.textMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = colors.textMute, fontSize = 10.sp)
    }
}

@Composable
private fun Ring64(pct: Int, colors: OpsColors, mod: Modifier) {
    Box(mod.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2f + 1.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val tl = Offset(inset, inset)
            drawArc(
                color = colors.hairline, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = tl, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
            if (pct > 0) drawArc(
                color = colors.accent, startAngle = -90f, sweepAngle = 360f * pct / 100f,
                useCenter = false, topLeft = tl, size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Text(
            "$pct%", color = colors.textMain, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun Section(t: String, colors: OpsColors) {
    Text(
        t, color = colors.textMute, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
    )
}

@Composable
private fun PhaseCard(
    p: JSONObject,
    idx: Int,
    doneSet: Set<String>,
    colors: OpsColors,
    compactDesc: Boolean,
    onClick: () -> Unit,
) {
    val mods = p.optJSONArray("mods")
    var total = 0
    var dn = 0
    if (mods != null) {
        total = mods.length()
        for (i in 0 until total) {
            val m = mods.optJSONObject(i) ?: continue
            if (doneSet.contains(m.optString("id"))) dn++
        }
    }
    val frac = if (total == 0) 0f else dn.toFloat() / total
    val name = p.optString("title").replace(Regex("^阶段[一二三四五]："), "")
    val desc = p.optString("desc")
    OpsCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .glassBadgeSurface(colors, shape = RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    idx.toString(),
                    color = colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = colors.textMain,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text("${p.optString("short")} · $total 模块", color = colors.textMute, fontSize = 11.sp)
            }
            OpsChevron(colors, rotation = 0f)
        }
        if (desc.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                desc,
                color = colors.textSub,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = if (compactDesc) 2 else 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassProgressCapsule(
                fraction = frac,
                colors = colors,
                modifier = Modifier.weight(1f),
                height = 5.5.dp
            )
            Spacer(Modifier.width(8.dp))
            Text("$dn/$total", color = colors.textMute, fontSize = 10.5.sp)
        }
    }
}

/** 零离屏缓冲、drawWithCache 缓存画刷的微高光液态玻璃进度胶囊槽。 */
@Composable
private fun GlassProgressCapsule(
    fraction: Float,
    colors: OpsColors,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val clamped = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val glassOn = AppShellState.glassLevel != "none"
    val trackColor = colors.hairline
    val rimColor = colors.border.copy(alpha = if (colors.dark) 0.45f else 0.60f)
    val accentColor = colors.accent
    val dark = colors.dark
    Spacer(
        modifier
            .height(height)
            .drawWithCache {
                val r = size.height / 2f
                val cr = CornerRadius(r, r)
                val strokePx = 0.6.dp.toPx()
                val fillBrush = Brush.verticalGradient(
                    0.0f to accentColor.copy(alpha = if (glassOn) 0.94f else 1f),
                    1.0f to accentColor.copy(alpha = if (glassOn) 0.78f else 1f),
                )
                val sheenBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (dark) 0.26f else 0.38f),
                    0.55f to Color.Transparent,
                )
                val trackSheen = Brush.verticalGradient(
                    0.0f to Color.Black.copy(alpha = if (dark) 0.16f else 0.04f),
                    1.0f to Color.White.copy(alpha = if (dark) 0.05f else 0.25f),
                )
                onDrawBehind {
                    drawRoundRect(color = trackColor, cornerRadius = cr)
                    if (glassOn) {
                        drawRoundRect(brush = trackSheen, cornerRadius = cr)
                        drawRoundRect(color = rimColor, cornerRadius = cr, style = Stroke(width = strokePx))
                    }
                    if (clamped > 0.001f) {
                        val fillW = (size.width * clamped).coerceAtLeast(size.height).coerceAtMost(size.width)
                        val fillSize = Size(fillW, size.height)
                        drawRoundRect(brush = fillBrush, size = fillSize, cornerRadius = cr)
                        if (glassOn) {
                            drawRoundRect(brush = sheenBrush, size = fillSize, cornerRadius = cr)
                        }
                    }
                }
            }
    )
}

@Composable
private fun ToolRow(title: String, sub: String, colors: OpsColors, onClick: () -> Unit) {
    OpsCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.textMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    sub,
                    color = colors.textMute,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            OpsChevron(colors, rotation = 0f, sizeDp = 18)
        }
    }
}
