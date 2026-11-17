package com.netops.handbook.ui.pages

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsChevron
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.PageData
import com.netops.handbook.ui.glassBadgeSurface
import com.netops.handbook.ui.glassChipSurface
import com.netops.handbook.ui.isOpsLandscape
import com.netops.handbook.ui.rememberOpsPagePadding

/**
 * 我的页（2026-11-14 M3-2③ / 2026-11-18 液态玻璃统计格与横屏双列仪表盘重构）。
 *
 * 形态 = 进度环卡（112dp 环 + 已完成计数 + 分项玻璃徽章）+ 四格统计（目录总量口径）
 * + 我的内容（收藏夹/面试题库）+ 设置区（外观壁纸/备份导出/恢复）+ footer。
 * 横屏优化：总进度圆环卡与四格统计卡左右并排，下方导航行按双列卡片网格并排，彻底消除大白片。
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

    val pagePad = rememberOpsPagePadding(hasDock = true, hasTopBar = true)
    val land = isOpsLandscape()
    val favSub = if (fav.isEmpty()) "还没有收藏任何内容" else "${fav.size} 条已收藏"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePad,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (land) {
            /* 横屏首屏：112dp 总进度圆环卡 与 四格目录总量统计卡 左右等宽并排 */
            item(key = "__me_hero_land__") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MeRingCard(
                        pct = pct,
                        all = all,
                        total = total,
                        dm = dm,
                        df = df,
                        di = di,
                        colors = colors,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    MeStatsGridCard(
                        totalMods = totalMods,
                        nFaults = nFaults,
                        nIvs = nIvs,
                        nQuiz = nQuiz,
                        twoByTwo = true,
                        colors = colors,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
            item(key = "__me_sec_content__") { Section("我的内容", colors) }
            item(key = "__me_content_land__") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NavRow(
                        badge = "★",
                        title = "收藏夹",
                        sub = favSub,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onClick = { onGo("fav") }
                    )
                    NavRow(
                        badge = "Q&A",
                        title = "面试题库",
                        sub = "$nIvs 题 · 高频真题与 STAR 话术",
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onClick = { onGo("iv") }
                    )
                }
            }
            item(key = "__me_sec_settings__") { Section("设置与数据", colors) }
            item(key = "__me_settings_row1__") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NavRow(
                        badge = "✦",
                        title = "外观与壁纸",
                        sub = "主题 · 壁纸 · 底栏 · 液态玻璃",
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onClick = { onGo("settings") }
                    )
                    NavRow(
                        badge = "↑",
                        title = "备份导出",
                        sub = "进度 · 收藏 · 错题 → Downloads",
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onClick = { onExport() }
                    )
                }
            }
            item(key = "__me_settings_row2__") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NavRow(
                        badge = "↓",
                        title = "恢复备份",
                        sub = "从 JSON 备份文件导入学习记录",
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onClick = { onImport() }
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        } else {
            item(key = "__me_ring_port__") {
                MeRingCard(
                    pct = pct,
                    all = all,
                    total = total,
                    dm = dm,
                    df = df,
                    di = di,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item(key = "__me_stats_port__") {
                MeStatsGridCard(
                    totalMods = totalMods,
                    nFaults = nFaults,
                    nIvs = nIvs,
                    nQuiz = nQuiz,
                    twoByTwo = false,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item(key = "__me_sec_content_p__") { Section("我的内容", colors) }
            item(key = "__me_fav_p__") {
                NavRow(
                    badge = "★",
                    title = "收藏夹",
                    sub = favSub,
                    colors = colors,
                    onClick = { onGo("fav") }
                )
            }
            item(key = "__me_iv_p__") {
                NavRow(
                    badge = "Q&A",
                    title = "面试题库",
                    sub = "$nIvs 题 · 辅助参考",
                    colors = colors,
                    onClick = { onGo("iv") }
                )
            }
            item(key = "__me_sec_settings_p__") { Section("设置", colors) }
            item(key = "__me_set_p__") {
                NavRow(
                    badge = "✦",
                    title = "外观与壁纸",
                    sub = "主题 · 壁纸 · 底栏 · 液态玻璃",
                    colors = colors,
                    onClick = { onGo("settings") }
                )
            }
            item(key = "__me_exp_p__") {
                NavRow(
                    badge = "↑",
                    title = "备份导出",
                    sub = "进度 · 收藏 · 错题 → Downloads",
                    colors = colors,
                    onClick = { onExport() }
                )
            }
            item(key = "__me_imp_p__") {
                NavRow(
                    badge = "↓",
                    title = "恢复备份",
                    sub = "从 JSON 文件导入",
                    colors = colors,
                    onClick = { onImport() }
                )
            }
        }

        item(key = "__me_footer__") {
            Text(
                "脉络 NetCraft · 离线网络与运维手册 · 仅供学习交流，禁止商用",
                color = colors.textMute,
                fontSize = 10.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun MeRingCard(
    pct: Int,
    all: Int,
    total: Int,
    dm: Int,
    df: Int,
    di: Int,
    colors: OpsColors,
    modifier: Modifier = Modifier,
) {
    OpsCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            /* 进度环（112dp：底圈 hairline + 进度弧 accent + 中心百分比，stroke 8dp round cap） */
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(112.dp).padding(top = 2.dp)) {
                    val stroke = 8.dp.toPx()
                    val inset = stroke / 2f + 2.dp.toPx()
                    val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                    val tl = Offset(inset, inset)
                    drawArc(
                        color = colors.hairline,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = tl,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                    if (pct > 0) {
                        drawArc(
                            color = colors.accent,
                            startAngle = -90f,
                            sweepAngle = 360f * pct / 100f,
                            useCenter = false,
                            topLeft = tl,
                            size = arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                Text(
                    "$pct%",
                    color = colors.textMain,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("$all / $total 项已完成", color = colors.textSub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "模块 $dm · 排障 $df · 面试 $di",
                color = colors.accent,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .glassBadgeSurface(colors, RoundedCornerShape(9.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun MeStatsGridCard(
    totalMods: Int,
    nFaults: Int,
    nIvs: Int,
    nQuiz: Int,
    twoByTwo: Boolean,
    colors: OpsColors,
    modifier: Modifier = Modifier,
) {
    OpsCard(modifier = modifier) {
        if (twoByTwo) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
            ) {
                Text(
                    "知识库规模",
                    color = colors.textMute,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCell("$totalMods", "知识模块", colors, Modifier.weight(1f))
                    StatCell("$nFaults", "排障案例", colors, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCell("$nIvs", "面试真题", colors, Modifier.weight(1f))
                    StatCell("$nQuiz 题", "测验题库", colors, Modifier.weight(1f))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatCell("$totalMods", "知识模块", colors, Modifier.weight(1f))
                StatCell("$nFaults", "排障案例", colors, Modifier.weight(1f))
                StatCell("$nIvs", "面试真题", colors, Modifier.weight(1f))
                StatCell("$nQuiz 题", "题库", colors, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(
    num: String,
    label: String,
    colors: OpsColors,
    mod: Modifier,
) {
    Column(
        modifier = mod
            .glassChipSurface(colors, active = false, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            num,
            color = colors.textMain,
            fontSize = 16.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = colors.textMute,
            fontSize = 10.5.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun Section(t: String, colors: OpsColors) {
    Text(
        t,
        color = colors.textMute,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun NavRow(
    badge: String,
    title: String,
    sub: String,
    colors: OpsColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OpsCard(
        modifier = modifier,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .glassBadgeSurface(colors, RoundedCornerShape(9.dp))
                    .padding(horizontal = 8.5.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    badge,
                    color = colors.accent,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = colors.textMain,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    sub,
                    color = colors.textMute,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(6.dp))
            OpsChevron(colors, rotation = 0f)
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
