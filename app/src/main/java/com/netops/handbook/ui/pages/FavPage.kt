package com.netops.handbook.ui.pages

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.netops.handbook.ui.glassClickable
import com.netops.handbook.ui.isOpsLandscape
import com.netops.handbook.ui.rememberOpsPagePadding
import org.json.JSONObject

/**
 * 收藏夹页（2026-11-14 M3-2③ / 2026-11-18 液态玻璃徽章与胶囊按钮 + 横屏双列卡片适配）。
 *
 * 三组行卡（知识模块→phase-focus / 排障案例→fault / 面试真题→iv），跳转走 Router；
 * 横屏下按双列卡片网格排布，条目编号升级为 glassBadgeSurface，收藏按钮升级为 glassChipSurface + glassClickable。
 */
private data class FavRow(
    val id: String,
    val badge: String,
    val title: String,
    val meta: String,
    val route: String,
    val arg: String,
)

@Composable
fun FavPage(
    dataSupplier: () -> org.json.JSONObject?,
    dark: Boolean,
    onGo: (String, String) -> Unit,
    onState: ((String, String, Boolean) -> Unit)? = null,
) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val ver = PageData.version
    var fav by remember(ver) {
        val sp = ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
        mutableStateOf<Set<String>>(HashSet(sp.getString("fav", "")!!.split(",").filter { it.isNotEmpty() }))
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
                    val mid = m.optString("id")
                    if (fav.contains(mid)) {
                        val badge = "M${m.optInt("n", k + 1)}"
                        mods.add(
                            FavRow(
                                id = mid,
                                badge = badge,
                                title = m.optString("t"),
                                meta = p.optString("short"),
                                route = "phase-focus",
                                arg = p.optString("id") + "|" + mid
                            )
                        )
                    }
                }
            }
        }
        val faultsArr = root?.optJSONArray("faults")
        if (faultsArr != null) for (i in 0 until faultsArr.length()) {
            val f = faultsArr.optJSONObject(i) ?: continue
            val fid = f.optString("id")
            if (fav.contains(fid)) {
                faults.add(
                    FavRow(
                        id = fid,
                        badge = fid.uppercase(),
                        title = f.optString("t"),
                        meta = f.optString("cat"),
                        route = "fault",
                        arg = ""
                    )
                )
            }
        }
        val ivsArr = root?.optJSONArray("interview")
        if (ivsArr != null) for (i in 0 until ivsArr.length()) {
            val x = ivsArr.optJSONObject(i) ?: continue
            val xid = x.optString("id")
            if (fav.contains(xid)) {
                ivs.add(
                    FavRow(
                        id = xid,
                        badge = xid.replace("IVQ", "Q"),
                        title = x.optString("t"),
                        meta = x.optString("cat"),
                        route = "iv",
                        arg = ""
                    )
                )
            }
        }
        listOf("知识模块" to mods, "排障案例" to faults, "面试真题" to ivs)
    }

    val pagePad = rememberOpsPagePadding(hasDock = true, hasTopBar = true)
    val land = isOpsLandscape()
    val total = groups.sumOf { it.second.size }

    fun unfav(id: String) {
        val newFav = fav - id
        fav = newFav
        ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE).edit()
            .putString("fav", newFav.joinToString(",")).apply()
        onState?.invoke(id, "fav", false)
        android.widget.Toast.makeText(ctx, "已取消收藏", android.widget.Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePad,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        item(key = "__fav_head__") {
            OpsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("收藏夹", color = colors.textMain, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$total 条",
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
                    if (total == 0) "还没有收藏任何内容，可在学习模块、排障案例或面试真题中点击收藏"
                    else "$total 条已收藏 · 点卡片直达原模块，点星标取消收藏",
                    color = colors.textMute,
                    fontSize = 12.5.sp
                )
            }
        }
        for ((label, rows) in groups) {
            if (rows.isEmpty()) continue
            item(key = "sec:$label") {
                Row(
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        color = colors.textMute,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${rows.size}",
                        color = colors.accent,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .glassBadgeSurface(colors, RoundedCornerShape(7.dp))
                            .padding(horizontal = 6.5.dp, vertical = 2.dp)
                    )
                }
            }
            if (land && rows.size > 1) {
                val pairs = rows.chunked(2)
                for (pair in pairs) {
                    val rowKey = "fp:" + pair.joinToString("|") { it.id }
                    item(key = rowKey) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(Modifier.weight(1f)) {
                                FavItemCard(pair[0], colors, onGo, ::unfav)
                            }
                            if (pair.size == 2) {
                                Box(Modifier.weight(1f)) {
                                    FavItemCard(pair[1], colors, onGo, ::unfav)
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                for (r in rows) {
                    item(key = r.id) {
                        FavItemCard(r, colors, onGo, ::unfav)
                    }
                }
            }
        }
    }
}

@Composable
private fun FavItemCard(
    r: FavRow,
    colors: OpsColors,
    onGo: (String, String) -> Unit,
    onUnfav: (String) -> Unit,
) {
    OpsCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .glassClickable(pressedScale = 0.985f, glowBoost = 0.05f) {
                        onGo(r.route, r.arg)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    r.badge,
                    color = colors.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .glassBadgeSurface(colors, RoundedCornerShape(9.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        r.title,
                        color = colors.textMain,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        r.meta,
                        color = colors.textMute,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "★ 已收藏",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .glassChipSurface(colors, active = true, shape = RoundedCornerShape(10.dp))
                    .glassClickable { onUnfav(r.id) }
                    .padding(horizontal = 9.dp, vertical = 5.5.dp)
            )
            Spacer(Modifier.width(6.dp))
            Box(Modifier.glassClickable { onGo(r.route, r.arg) }) {
                OpsChevron(colors, rotation = 0f)
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
