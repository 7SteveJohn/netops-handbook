package com.netops.handbook.ui.pages

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.WindowInsets
import com.netops.handbook.ui.LocalOpsColors
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.PageData
import org.json.JSONObject

/**
 * 我的页（2026-11-14 M3-2③）：NativeMeView 的 Compose 版。
 *
 * 形态 = 进度环卡（112dp 环 + 已完成计数）+ 四格统计（目录总量口径，2026-11-11
 * 走查定案）+ 我的内容行（收藏夹/面试题库）+ 设置区（外观壁纸/备份导出/恢复）
 * + footer。四格一律是「目录总量」不是完成数（口径见 View 版注释）。
 */
@Composable
fun MePage(
    dataSupplier: () -> org.json.JSONObject?,
    dark: Boolean,
    onGo: (String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val ver = PageData.version
    val done = remember(ver) {
        val sp = ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
        HashSet(sp.getString("done", "")!!.split(",").filter { it.isNotEmpty() })
    }
    val fav = remember(ver) {
        val sp = ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
        HashSet(sp.getString("fav", "")!!.split(",").filter { it.isNotEmpty() })
    }
    val root = remember(ver) { dataSupplier() }
    val phases = root?.optJSONArray("phases")
    var dm = 0
    var totalMods = 0
    if (phases != null) for (i in 0 until phases.length()) {
        val ms = phases.optJSONObject(i)?.optJSONArray("mods") ?: continue
        totalMods += ms.length()
        for (k in 0 until ms.length()) {
            val m = ms.optJSONObject(k) ?: continue
            if (done.contains(m.optString("id"))) dm++
        }
    }
    val faults = root?.optJSONArray("faults")
    var df = 0
    if (faults != null) for (i in 0 until faults.length()) {
        if (done.contains(faults.optJSONObject(i)?.optString("id"))) df++
    }
    val ivs = root?.optJSONArray("interview")
    var di = 0
    if (ivs != null) for (i in 0 until ivs.length()) {
        if (done.contains(ivs.optJSONObject(i)?.optString("id"))) di++
    }
    val all = dm + df + di
    val total = totalMods + (faults?.length() ?: 0) + (ivs?.length() ?: 0)
    val pct = if (total == 0) 0 else Math.round(all * 100f / total)
    val nFaults = faults?.length() ?: 0
    val nIvs = ivs?.length() ?: 0
    val nQuiz = root?.optJSONArray("quiz")?.length() ?: 0

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
            item {
                OpsCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        /* 进度环（NativeRing 112dp 同款：底圈 hairline + 进度弧 accent
                           + 中心百分比，stroke 8dp round cap） */
                        Canvas(Modifier.size(112.dp).padding(top = 4.dp)) {
                            val stroke = 8.dp.toPx()
                            val inset = stroke / 2f + 2.dp.toPx()
                            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                            val tl = Offset(inset, inset)
                            drawArc(
                                color = colors.hairline, startAngle = 0f, sweepAngle = 360f,
                                useCenter = false, topLeft = tl, size = arcSize,
                                style = Stroke(stroke, cap = StrokeCap.Round)
                            )
                            if (pct > 0) drawArc(
                                color = colors.accent, startAngle = -90f,
                                sweepAngle = 360f * pct / 100f, useCenter = false,
                                topLeft = tl, size = arcSize,
                                style = Stroke(stroke, cap = StrokeCap.Round)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("$all / $total 项已完成", color = colors.textMute, fontSize = 11.5.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            item {
                OpsCard(Modifier.fillMaxWidth()) {
                    Row {
                        StatCell("$totalMods", "知识模块", colors, Modifier.weight(1f))
                        StatCell("$nFaults", "排障案例", colors, Modifier.weight(1f))
                        StatCell("$nIvs", "面试真题", colors, Modifier.weight(1f))
                        StatCell("$nQuiz 题", "题库", colors, Modifier.weight(1f))
                    }
                }
            }
            item { Section("我的内容", colors) }
            item {
                NavRow("收藏夹",
                    if (fav.isEmpty()) "还没有收藏任何内容" else "${fav.size} 条已收藏",
                    colors) { onGo("fav") }
            }
            item {
                NavRow("面试题库", "$nIvs 题 · 辅助参考", colors) { onGo("iv") }
            }
            item { Section("设置", colors) }
            item { NavRow("外观与壁纸", "主题 · 壁纸", colors) { onGo("settings") } }
            item { NavRow("备份导出", "进度 · 收藏 · 错题 → Downloads", colors) { onExport() } }
            item { NavRow("恢复备份", "从 JSON 文件导入", colors) { onImport() } }
            item {
                Text(
                    "NetOps 2.0 · 仅供学习交流，禁止商用",
                    color = colors.textMute, fontSize = 10.5.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                )
            }
        }
    }
}

@Composable
private fun StatCell(num: String, label: String, colors: com.netops.handbook.ui.OpsColors, mod: Modifier) {
    Column(
        mod,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(num, color = colors.textMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(label, color = colors.textMute, fontSize = 10.5.sp)
    }
}

@Composable
private fun Section(t: String, colors: com.netops.handbook.ui.OpsColors) {
    Text(
        t, color = colors.textMute, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun NavRow(title: String, sub: String, colors: com.netops.handbook.ui.OpsColors, onClick: () -> Unit) {
    OpsCard(Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.textMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(sub, color = colors.textMute, fontSize = 11.5.sp)
            }
            OpsChevron(colors, rotation = 90f)
        }
    }
}

/** Java 侧挂载入口（Composeable lambda 无法在 Java 源里书写）。 */
fun androidx.compose.ui.platform.ComposeView.setMeContent(
    data: () -> org.json.JSONObject?, dark: () -> Boolean, direct: () -> Boolean,
    onGo: (String) -> Unit, onExport: () -> Unit, onImport: () -> Unit,
) {
    setViewCompositionStrategy(
        androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    setContent {
        OpsTheme(dark(), direct()) {
            MePage(data, dark(), onGo, onExport, onImport)
        }
    }
}
