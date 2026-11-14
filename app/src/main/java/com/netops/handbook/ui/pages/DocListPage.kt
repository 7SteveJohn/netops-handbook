package com.netops.handbook.ui.pages

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsBlocks
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsChevron
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.PageData
import org.json.JSONArray
import org.json.JSONObject

/**
 * 排障案例 / 面试题库页（2026-11-14 M3-2⑥）：NativeDocList 的 Compose 版，
 * 一个 Composable 按 DocListConfig 驱动两路由（fault 平铺 / iv 分组折叠）。
 *
 * 条目卡 = 头行（chip=id 经 chipFrom→chipTo 替换、标题、meta=cat+done/fav 后缀、
 * 箭头）+ 展开 body（OpsBlocks 渲染 blocks + done/fav 操作行）。判分后展开
 * 180ms 动画由 Compose 直接即时呈现（expand 状态切换即重组）。
 * 状态：done/fav = SP netops_native（逗号集，与 View 版/phase 页同源）；
 * onState 回调 → MainActivity markStatePagesDirty（JS 回写环随 WebView 摘除退役）。
 * 横屏双列暂单列（M3 收口统一 Flow）；分组「收起即销毁」= Compose 惰性重组
 * 天然继承（未展开组不组内渲染）。
 */
data class DocListConfig(
    val route: String,
    val title: String,
    val unit: String,
    val doneLabel: String,
    val dataKey: String,
    val chipFrom: String = "",
    val chipTo: String = "",
    val grouped: Boolean = false,
)

private fun chipText(id: String, cfg: DocListConfig): String =
    if (cfg.chipFrom.isEmpty()) id else id.replace(cfg.chipFrom, cfg.chipTo)

@Composable
fun DocListPage(cfg: DocListConfig, dataSupplier: () -> org.json.JSONObject?, dark: Boolean,
                onState: (String, String, Boolean) -> Unit) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val ver = PageData.version
    val items = remember(ver, cfg.dataKey) {
        val arr = dataSupplier()?.optJSONArray(cfg.dataKey) ?: JSONArray()
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        out
    }
    var done by remember(ver, cfg.route) {
        mutableStateOf<Set<String>>(HashSet(ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
            .getString("done", "")!!.split(",").filter { it.isNotEmpty() }))
    }
    var fav by remember(ver, cfg.route) {
        mutableStateOf<Set<String>>(HashSet(ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
            .getString("fav", "")!!.split(",").filter { it.isNotEmpty() }))
    }
    var expandedIds by remember(ver, cfg.route) { mutableStateOf(setOf<String>()) }
    var expandedGroups by remember(ver, cfg.route) { mutableStateOf(setOf<String>()) }
    fun saveState() {
        ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE).edit()
            .putString("done", done.joinToString(","))
            .putString("fav", fav.joinToString(",")).apply()
    }
    var doneN = 0
    for (o in items) if (done.contains(o.optString("id"))) doneN++

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = statusTop + 84.dp, bottom = navBottom + 84.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OpsCard {
                    Text(cfg.title, color = colors.textMain, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "共 ${items.size} 个${cfg.unit} · ${cfg.doneLabel} $doneN",
                        color = colors.textMute, fontSize = 12.5.sp
                    )
                }
            }
            if (cfg.grouped) {
                /* 分组折叠：未展开组不组内渲染（收起即销毁语义=惰性重组继承） */
                val groups = LinkedHashMap<String, MutableList<JSONObject>>()
                for (o in items) {
                    val c = o.optString("cat").ifEmpty { "未分类" }
                    groups.getOrPut(c) { ArrayList() }.add(o)
                }
                for ((cat, list) in groups) {
                    item(key = "g:$cat") {
                        val open = expandedGroups.contains(cat)
                        OpsCard(Modifier.clickable {
                            expandedGroups = if (open) expandedGroups - cat else expandedGroups + cat
                        }) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(cat, color = colors.textMain, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${list.size} 题", color = colors.textMute, fontSize = 12.sp)
                                }
                                OpsChevron(colors, rotation = if (open) 90f else 0f)
                            }
                            if (open) {
                                Spacer(Modifier.height(8.dp))
                                for (o in list) {
                                    DocItemCard(o, cfg, done, fav, colors, onState,
                                        onToggleDone = { id ->
                                            val on = !done.contains(id)
                                            done = if (on) done + id else done - id
                                            ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE).edit()
                                                .putString("done", done.joinToString(",")).apply()
                                            onState(id, "done", on)
                                        },
                                        onToggleFav = { id ->
                                            val on = !fav.contains(id)
                                            fav = if (on) fav + id else fav - id
                                            ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE).edit()
                                                .putString("fav", fav.joinToString(",")).apply()
                                            onState(id, "fav", on)
                                        },
                                        onToggleExpand = { id ->
                                            expandedIds = if (expandedIds.contains(id)) expandedIds - id
                                            else expandedIds + id
                                        },
                                        expanded = expandedIds.contains(o.optString("id")))
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                for (o in items) {
                    item(key = o.optString("id")) {
                        DocItemCard(o, cfg, done, fav, colors, onState,
                                        onToggleDone = { id ->
                                            val on = !done.contains(id)
                                            done = if (on) done + id else done - id
                                            ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE).edit()
                                                .putString("done", done.joinToString(",")).apply()
                                            onState(id, "done", on)
                                        },
                                        onToggleFav = { id ->
                                            val on = !fav.contains(id)
                                            fav = if (on) fav + id else fav - id
                                            ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE).edit()
                                                .putString("fav", fav.joinToString(",")).apply()
                                            onState(id, "fav", on)
                                        },
                            onToggleExpand = { id ->
                                expandedIds = if (expandedIds.contains(id)) expandedIds - id
                                else expandedIds + id
                            },
                            expanded = expandedIds.contains(o.optString("id")))
                    }
                }
            }
        }
    }
}

@Composable
private fun DocItemCard(
    o: JSONObject, cfg: DocListConfig,
    doneSet: Set<String>, favSet: Set<String>, colors: com.netops.handbook.ui.OpsColors,
    onState: (String, String, Boolean) -> Unit,
    onToggleDone: (String) -> Unit, onToggleFav: (String) -> Unit,
    onToggleExpand: (String) -> Unit,
    expanded: Boolean,
) {
    val ctx = LocalContext.current
    val id = o.optString("id")
    val isDone = doneSet.contains(id)
    val isFav = favSet.contains(id)
    var meta = o.optString("cat")
    if (isDone) meta += " · ✓ ${cfg.doneLabel}"
    if (isFav) meta += " · ★ 收藏"

    OpsCard {
        Row(
            Modifier.fillMaxWidth().clickable { onToggleExpand(id) },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                chipText(id, cfg), color = colors.accent, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.chipBgOn)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(o.optString("t"), color = colors.textMain, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(meta, color = if (isDone) colors.accent else colors.textMute, fontSize = 11.sp)
            }
            OpsChevron(colors, rotation = if (expanded) -90f else 90f)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            OpsBlocks(o.optJSONArray("blocks") ?: JSONArray())
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(
                    if (isDone) "✓ ${cfg.doneLabel}" else "标记${cfg.doneLabel}",
                    color = if (isDone) Color.White else colors.accent,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDone) colors.accent else colors.chipBgOff)
                        .padding(vertical = 9.dp)
                        .clickable { onToggleDone(id) }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isFav) "★ 已收藏" else "☆ 收藏",
                    color = if (isFav) colors.textMain else colors.textSub,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.chipBgOff)
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                        .clickable { onToggleFav(id) }
                )
            }
        }
    }
}

/** Java 侧挂载入口（Composeable lambda 无法在 Java 源里书写）。 */
fun androidx.compose.ui.platform.ComposeView.setDocListContent(
    cfg: DocListConfig,
    data: () -> org.json.JSONObject?, dark: () -> Boolean, direct: () -> Boolean,
    onState: (String, String, Boolean) -> Unit,
) {
    setViewCompositionStrategy(
        androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    setContent {
        OpsTheme(dark(), direct()) {
            DocListPage(cfg, data, dark(), onState)
        }
    }
}
