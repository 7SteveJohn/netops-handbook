package com.netops.handbook.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.PageData
import org.json.JSONArray
import org.json.JSONObject

/**
 * 速查表页（2026-11-14 M3-2①）：NativeRefsView 的 Compose 版。
 *
 * 形态 = 一页铺完 12 张表（2026-10-27 定案：快速扫不需要两级导航）；
 * 单卡 = 标题 15sp bold + 说明 11.5sp + cols/rows 表格（首列 weight 1.4、
 * 隔行 chipBgOff 底、表头 11sp bold textMute）；ref 深链 = detailId 直达单表
 * （Router ref 路由 → AppShellState.refsDetail）。
 */
@Composable
fun RefsPage(detailId: String?, dataSupplier: () -> org.json.JSONObject?) {
    val colors = LocalOpsColors.current
    val ver = PageData.version
    val refs = remember(ver) {
        val arr = dataSupplier()?.optJSONArray("refs") ?: JSONArray()
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        out
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        Modifier
            .fillMaxSize()
            .padding(
                top = statusTop + 84.dp,
                bottom = navBottom + 84.dp
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                val hit = detailId?.let { id -> refs.firstOrNull { it.optString("id") == id } }
                if (hit != null) {
                    RefsTableCard(hit, colors)
                } else {
                    OpsCard {
                        Text("速查表", color = colors.textMain, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "高频速查表 · 共 ${refs.size} 张。面试前 10 分钟过一遍最有效。",
                            color = colors.textMute, fontSize = 12.5.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    for (r in refs) {
                        RefsTableCard(r, colors)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RefsTableCard(r: JSONObject, colors: OpsColors) {
    OpsCard {
        Text(r.optString("t"), color = colors.textMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val d = r.optString("d")
        if (d.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(d, color = colors.textMute, fontSize = 11.5.sp)
        }
        val cols = r.optJSONArray("cols") ?: return@OpsCard
        val rows = r.optJSONArray("rows") ?: return@OpsCard
        val n = cols.length()
        if (n == 0) return@OpsCard
        Spacer(Modifier.height(4.dp))
        Row(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
            for (i in 0 until n) {
                Text(
                    cols.optString(i), color = colors.textMute,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(if (i == 0) 1.4f else 1f)
                        .padding(horizontal = 4.dp)
                )
            }
        }
                for (i in 0 until rows.length()) {
            val cells = rows.optJSONArray(i) ?: continue
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .then(
                        if (i % 2 == 1) Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.chipBgOff)
                        else Modifier
                    )
            ) {
                for (c in 0 until n) {
                    Text(
                        cells.optString(c, ""),
                        color = if (c == 0) colors.textMain else colors.textSub,
                        fontSize = 12.sp,
                        fontWeight = if (c == 0) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .weight(if (c == 0) 1.4f else 1f)
                            .padding(horizontal = 4.dp)
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
