package com.netops.handbook.ui.pages

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.netops.handbook.ui.LocalOpsColors
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsChevron
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.PageData
import org.json.JSONArray
import org.json.JSONObject

/**
 * 学习首页（2026-11-14 M3-2④）：NativeHomeView 的 Compose 版。
 *
 * 形态 = 英雄卡（OFFLINE READY + 标题 + 三行描述 + 四格统计）+ 进度卡
 * （64dp 环 + 学习进度 + 继续学习按钮，首个未完成模块深链 phase-focus）+
 * 学习路径阶段卡（序号 chip + 名称/元信息 + 描述 + 进度条 dn/total）+
 * 工具箱 5 项（速查表/路线图/术语/测验/CLI）。
 * 横屏双列暂单列（阶段卡重，LazyColumn 双列放 M3 收口统一 Flow）。
 */
@Composable
fun HomePage(dataSupplier: () -> org.json.JSONObject?, dark: Boolean, onGo: (String, String) -> Unit) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val ver = PageData.version
    val root = remember(ver) { dataSupplier() }
    val phases = remember(ver) {
        val arr = root?.optJSONArray("phases") ?: JSONArray()
        val out = ArrayList<JSONObject>()
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
                nextPid = p.optString("id"); nextId = m.optString("id"); nextTitle = m.optString("t")
                hit = true; break
            }
        }
        if (hit) break
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = statusTop + 84.dp, bottom = navBottom)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            /* 英雄卡 */
            item {
                OpsCard {
                    Text("OFFLINE READY", color = colors.accent, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("网络学习手册 2.0", color = colors.textMain, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
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
            /* 进度卡 */
            item {
                OpsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ring64(pct, colors, Modifier.padding(end = 14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("学习进度", color = colors.textMain, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text("$doneTotal / $modTotal 个模块", color = colors.textMute, fontSize = 11.5.sp)
                            if (nextPid != null) {
                                Spacer(Modifier.height(9.dp))
                                val label = "继续：" + (nextTitle ?: "") +
                                        (if ((nextTitle ?: "").length > 10) "…" else "")
                                Text(
                                    label, color = Color.White, fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(colors.accent)
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .clickable { onGo("phase-focus", "$nextPid|$nextId") }
                                )
                            } else {
                                Spacer(Modifier.height(8.dp))
                                Text("全部模块已掌握", color = colors.textMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            item { Section("学习路径", colors) }
            for ((idx, p) in phases.withIndex()) {
                item(key = p.optString("id")) {
                    PhaseCard(p, idx + 1, doneSet, colors) { onGo("phase", p.optString("id")) }
                }
            }
            item { Section("工具箱", colors) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolRow("速查表", "$nRefs 张 · 端口 / 掩码 / OSPF / BGP", colors) { onGo("refs", "") }
                    ToolRow("学习路线图", "0-12 个月成长节奏与目标", colors) { onGo("roadmap", "") }
                    ToolRow("术语词典", "$nGloss 条云原生与数通黑话", colors) { onGo("glossary", "") }
                    ToolRow("模拟测验", "$nQuiz 道选择题", colors) { onGo("quiz", "") }
                    ToolRow("CLI 模拟器", "离线练习 VRP / Linux 常用命令", colors) { onGo("cli", "") }
                }
            }
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
    Canvas(mod.size(64.dp)) {
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
}

@Composable
private fun Section(t: String, colors: OpsColors) {
    Text(
        t, color = colors.textMute, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
    )
}

@Composable
private fun PhaseCard(p: JSONObject, idx: Int, doneSet: Set<String>, colors: OpsColors, onClick: () -> Unit) {
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
    val pc = if (total == 0) 0 else Math.round(dn * 100f / total)
    val name = p.optString("title").replace(Regex("^阶段[一二三四五]："), "")
    val desc = p.optString("desc")
    OpsCard(Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                idx.toString(), color = colors.accent, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.chipBgOn)
                    .wrapContentSize(Alignment.Center)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = colors.textMain, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text("${p.optString("short")} · $total 模块", color = colors.textMute, fontSize = 11.sp)
            }
            OpsChevron(colors, rotation = 90f)
        }
        if (desc.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(desc, color = colors.textSub, fontSize = 12.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(colors.hairline)
            ) {
                if (pc > 0) Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(Math.max(0.0001f, pc / 100f))
                        .height(5.dp)
                        .background(colors.accent)
                )
                if (pc < 100) Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(Math.max(0.0001f, 1f - pc / 100f))
                        .height(5.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("$dn/$total", color = colors.textMute, fontSize = 10.5.sp)
        }
    }
}

@Composable
private fun ToolRow(title: String, sub: String, colors: OpsColors, onClick: () -> Unit) {
    OpsCard(Modifier.clickable { onClick() }) {
        Text(title, color = colors.textMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(sub, color = colors.textMute, fontSize = 11.5.sp)
    }
}
