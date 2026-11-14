package com.netops.handbook.ui.pages

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import com.netops.handbook.ui.LocalOpsColors
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.OpsChevron
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.PageData
import org.json.JSONObject

/**
 * 收藏夹页（2026-11-14 M3-2③）：NativeFavView 的 Compose 版。
 *
 * 三组行卡（知识模块→phase-focus / 排障案例→fault / 面试真题→iv），跳转走
 * Router（nav.go 同语义）；横屏双列 Flow 在 M3-2③ 保持单列（View 版双列只在
 * land 生效——Compose 版补齐放 M3 收口，行卡轻、单列可用）。
 * 数据源：SP netops_native/fav（逗号 id 集）+ native-data 全量对账。
 */
private data class FavRow(val title: String, val meta: String, val route: String, val arg: String)

@Composable
fun FavPage(dataSupplier: () -> org.json.JSONObject?, dark: Boolean, onGo: (String, String) -> Unit) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val ver = PageData.version
    val fav = remember(ver) {
        val sp = ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
        HashSet(sp.getString("fav", "")!!.split(",").filter { it.isNotEmpty() })
    }
    val groups = remember(ver, fav) {
        val root = dataSupplier()
        val mods = ArrayList<FavRow>()
        val faults = ArrayList<FavRow>()
        val ivs = ArrayList<FavRow>()
        val phases = root?.optJSONArray("phases")
        if (phases != null) {
            for (i in 0 until phases.length()) {
                val p = phases.optJSONObject(i) ?: continue
                val ms = p.optJSONArray("mods") ?: continue
                for (k in 0 until ms.length()) {
                    val m = ms.optJSONObject(k) ?: continue
                    if (fav.contains(m.optString("id")))
                        mods.add(FavRow(m.optString("t"), p.optString("short"),
                            "phase-focus", p.optString("id") + "|" + m.optString("id")))
                }
            }
        }
        val faultsArr = root?.optJSONArray("faults")
        if (faultsArr != null) for (i in 0 until faultsArr.length()) {
            val f = faultsArr.optJSONObject(i) ?: continue
            if (fav.contains(f.optString("id")))
                faults.add(FavRow(f.optString("t"), f.optString("cat"), "fault", ""))
        }
        val ivsArr = root?.optJSONArray("interview")
        if (ivsArr != null) for (i in 0 until ivsArr.length()) {
            val x = ivsArr.optJSONObject(i) ?: continue
            if (fav.contains(x.optString("id")))
                ivs.add(FavRow(x.optString("t"), x.optString("cat"), "iv", ""))
        }
        listOf("知识模块" to mods, "排障案例" to faults, "面试真题" to ivs)
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val total = groups.sumOf { it.second.size }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = statusTop + 84.dp, bottom = navBottom + 84.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                OpsCard {
                    Text("收藏夹", color = colors.textMain, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (total == 0) "还没有收藏任何内容" else "$total 条已收藏 · 点行跳转",
                        color = colors.textMute, fontSize = 12.5.sp
                    )
                }
            }
            for ((label, rows) in groups) {
                if (rows.isEmpty()) continue
                item {
                    Text(
                        label, color = colors.textMute, fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
                    )
                }
                for (r in rows) {
                    item {
                        OpsCard(Modifier.clickable { onGo(r.route, r.arg) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.title, color = colors.textMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(3.dp))
                                    Text(r.meta, color = colors.textMute, fontSize = 11.5.sp)
                                }
                                OpsChevron(colors, rotation = 90f)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Java 侧挂载入口（Composeable lambda 无法在 Java 源里书写）。 */
fun androidx.compose.ui.platform.ComposeView.setFavContent(
    data: () -> org.json.JSONObject?, dark: () -> Boolean, direct: () -> Boolean,
    onGo: (String, String) -> Unit,
) {
    setViewCompositionStrategy(
        androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    setContent {
        OpsTheme(dark(), direct()) {
            FavPage(data, dark(), onGo)
        }
    }
}
