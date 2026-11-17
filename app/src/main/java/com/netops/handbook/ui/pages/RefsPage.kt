package com.netops.handbook.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.PageData
import com.netops.handbook.ui.glassBadgeSurface
import com.netops.handbook.ui.isOpsLandscape
import com.netops.handbook.ui.rememberOpsPagePadding
import org.json.JSONArray
import org.json.JSONObject

/**
 * 速查表页（2026-11-14 M3-2① / 2026-11-18 液态玻璃徽章与横屏双列速查网格适配）。
 *
 * 形态 = 一页铺完 12 张高频表（快速扫不需要两级导航）；
 * 横屏宽视口下采用双列卡片并排（单列宽约 370dp+，与竖屏单卡同宽不挤压列数），
 * 深链 ref 路由 = detailId 居中舒适限宽 780dp 直达单表。
 */
@Composable
fun RefsPage(detailId: String?, dataSupplier: () -> org.json.JSONObject?) {
    val colors = LocalOpsColors.current
    val ver = PageData.version
    val refs = remember(ver) {
        val arr = dataSupplier()?.optJSONArray("refs") ?: JSONArray()
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        out
    }

    val pagePad = rememberOpsPagePadding(hasDock = true, hasTopBar = true)
    val land = isOpsLandscape()
    val cfg = LocalConfiguration.current
    val twoCol = land && cfg.screenWidthDp >= 680

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePad,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val hit = detailId?.let { id -> refs.firstOrNull { it.optString("id") == id } }
        if (hit != null) {
            item(key = hit.optString("id", "hit")) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = if (land) Modifier.widthIn(max = 780.dp).fillMaxWidth() else Modifier.fillMaxWidth()
                    ) {
                        RefsTableCard(hit, colors)
                    }
                }
            }
        } else {
            item(key = "__refs_head__") {
                OpsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("速查表", color = colors.textMain, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "共 ${refs.size} 张",
                            color = colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .glassBadgeSurface(colors, RoundedCornerShape(9.dp))
                                .padding(horizontal = 8.dp, vertical = 3.5.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "高频速查表 · 面试前 10 分钟或排障现场过一遍最有效。",
                        color = colors.textMute,
                        fontSize = 12.5.sp
                    )
                }
            }
            if (twoCol && refs.size > 1) {
                val pairs = refs.chunked(2)
                items(
                    count = pairs.size,
                    key = { pIdx ->
                        val p = pairs[pIdx]
                        "rp:" + p.joinToString("|") { it.optString("id") }
                    }
                ) { pIdx ->
                    val pair = pairs[pIdx]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(Modifier.weight(1f)) {
                            RefsTableCard(pair[0], colors)
                        }
                        if (pair.size == 2) {
                            Box(Modifier.weight(1f)) {
                                RefsTableCard(pair[1], colors)
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(
                    count = refs.size,
                    key = { idx -> refs[idx].optString("id").ifEmpty { "ref_$idx" } }
                ) { idx ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = if (land) Modifier.widthIn(max = 780.dp).fillMaxWidth() else Modifier.fillMaxWidth()
                        ) {
                            RefsTableCard(refs[idx], colors)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefsTableCard(r: JSONObject, colors: OpsColors) {
    val cols = r.optJSONArray("cols")
    val rows = r.optJSONArray("rows")
    OpsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                r.optString("t"),
                color = colors.textMain,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (rows != null && rows.length() > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${rows.length()} 项",
                    color = colors.accent,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .glassBadgeSurface(colors, RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                )
            }
        }
        val d = r.optString("d")
        if (d.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(d, color = colors.textMute, fontSize = 11.5.sp)
        }
        if (cols == null || rows == null) return@OpsCard
        val n = cols.length()
        if (n == 0) return@OpsCard

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 5.dp)
        ) {
            for (i in 0 until n) {
                Text(
                    cols.optString(i),
                    color = colors.textMute,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(if (i == 0) 1.4f else 1f)
                        .padding(horizontal = 5.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.7.dp)
                .background(colors.hairline)
        )
        Spacer(Modifier.height(3.dp))
        for (i in 0 until rows.length()) {
            val cells = rows.optJSONArray(i) ?: continue
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (i % 2 == 1) Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (colors.direct) colors.chipBgOff.copy(alpha = 0.68f)
                                else colors.chipBgOff
                            )
                        else Modifier
                    )
                    .padding(vertical = 6.dp)
            ) {
                for (c in 0 until n) {
                    Text(
                        cells.optString(c, ""),
                        color = if (c == 0) colors.textMain else colors.textSub,
                        fontSize = 12.sp,
                        fontWeight = if (c == 0) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .weight(if (c == 0) 1.4f else 1f)
                            .padding(horizontal = 5.dp)
                    )
                }
            }
        }
    }
}

/** Java 侧挂载入口（Composeable lambda 无法在 Java 源里书写）。 */
fun androidx.compose.ui.platform.ComposeView.setRefsContent(
    data: () -> org.json.JSONObject?, dark: () -> Boolean, direct: () -> Boolean,
    detailId: () -> String?,
) {
    setViewCompositionStrategy(
        androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    setContent {
        OpsTheme(dark(), direct()) {
            RefsPage(detailId(), data)
        }
    }
}
