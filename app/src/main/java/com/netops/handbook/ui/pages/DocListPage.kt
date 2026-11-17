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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsBlocks
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 排障案例 / 面试题库页（2026-11-14 M3-2⑥ / 2026-11-18 液态玻璃徽章与胶囊按钮 + 横屏双列适配）。
 *
 * 一个 Composable 按 DocListConfig 驱动两路由（fault / iv 分组折叠）。
 * 条目卡 = 头行（glassBadgeSurface 编号芯片、标题、meta=cat+done/fav 后缀、箭头）
 * + 展开 body（OpsBlocks 渲染 blocks + 宝石蓝/冰晶玻璃操作胶囊）。
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
fun DocListPage(
    cfg: DocListConfig,
    dataSupplier: () -> org.json.JSONObject?,
    dark: Boolean,
    onState: (String, String, Boolean) -> Unit,
) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val ver = PageData.version
    val items = remember(ver, cfg.dataKey) {
        val arr = dataSupplier()?.optJSONArray(cfg.dataKey) ?: JSONArray()
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        out
    }
    var done by remember(ver, cfg.route) {
        mutableStateOf<Set<String>>(
            HashSet(
                ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
                    .getString("done", "")!!.split(",").filter { it.isNotEmpty() }
            )
        )
    }
    var fav by remember(ver, cfg.route) {
        mutableStateOf<Set<String>>(
            HashSet(
                ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
                    .getString("fav", "")!!.split(",").filter { it.isNotEmpty() }
            )
        )
    }
    var expandedIds by remember(cfg.route) { mutableStateOf(setOf<String>()) }
    var expandedGroups by remember(cfg.route) { mutableStateOf(setOf<String>()) }

    val doneN = remember(items, done) {
        var c = 0
        for (o in items) if (done.contains(o.optString("id"))) c++
        c
    }

    val groups = remember(items, cfg.grouped) {
        if (!cfg.grouped) emptyMap()
        else {
            val map = LinkedHashMap<String, MutableList<JSONObject>>()
            for (o in items) {
                val c = o.optString("cat").ifEmpty { "未分类" }
                map.getOrPut(c) { ArrayList() }.add(o)
            }
            map
        }
    }

    val pagePad = rememberOpsPagePadding(hasDock = true, hasTopBar = true)
    val land = isOpsLandscape()

    fun toggleDone(id: String) {
        val on = !done.contains(id)
        done = if (on) done + id else done - id
        ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE).edit()
            .putString("done", done.joinToString(",")).apply()
        onState(id, "done", on)
    }

    fun toggleFav(id: String) {
        val on = !fav.contains(id)
        fav = if (on) fav + id else fav - id
        ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE).edit()
            .putString("fav", fav.joinToString(",")).apply()
        onState(id, "fav", on)
    }

    fun toggleExpand(id: String) {
        expandedIds = if (expandedIds.contains(id)) expandedIds - id else expandedIds + id
    }

    /* 横屏分组行编排：收起组两列并排，展开组独占满宽（居中舒适限宽 780dp 保证代码/拓扑完整舒展） */
    val groupRows = remember(groups, land, expandedGroups) {
        val entries = groups.entries.toList()
        if (!land) {
            entries.map { listOf(it) }
        } else {
            val rows = ArrayList<List<Map.Entry<String, MutableList<JSONObject>>>>()
            var pending: Map.Entry<String, MutableList<JSONObject>>? = null
            for (e in entries) {
                val open = expandedGroups.contains(e.key)
                if (open) {
                    pending?.let { rows.add(listOf(it)); pending = null }
                    rows.add(listOf(e))
                } else {
                    val p = pending
                    if (p == null) pending = e
                    else {
                        rows.add(listOf(p, e))
                        pending = null
                    }
                }
            }
            pending?.let { rows.add(listOf(it)) }
            rows
        }
    }

    val countUnit = if (cfg.unit == "真题") "题" else "个${cfg.unit}"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePad,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "__doc_head__") {
            OpsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(cfg.title, color = colors.textMain, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${cfg.doneLabel} $doneN / ${items.size}",
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
                    "共 ${items.size} $countUnit · 点开分组按场景实战复盘",
                    color = colors.textMute,
                    fontSize = 12.5.sp
                )
            }
        }

        if (cfg.grouped) {
            for (row in groupRows) {
                val rowKey = if (row.size == 1) "g:${row[0].key}" else "gp:${row[0].key}|${row[1].key}"
                item(key = rowKey) {
                    if (row.size == 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for (entry in row) {
                                Box(Modifier.weight(1f)) {
                                    DocGroupCard(
                                        cat = entry.key,
                                        list = entry.value,
                                        countUnit = countUnit,
                                        open = expandedGroups.contains(entry.key),
                                        cfg = cfg,
                                        done = done,
                                        fav = fav,
                                        expandedIds = expandedIds,
                                        colors = colors,
                                        onToggleGroup = {
                                            val openNow = expandedGroups.contains(entry.key)
                                            expandedGroups = if (openNow) expandedGroups - entry.key else expandedGroups + entry.key
                                        },
                                        onToggleDone = ::toggleDone,
                                        onToggleFav = ::toggleFav,
                                        onToggleExpand = ::toggleExpand,
                                    )
                                }
                            }
                        }
                    } else {
                        val single = row[0]
                        val open = expandedGroups.contains(single.key)
                        if (land && !open) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(Modifier.weight(1f)) {
                                    DocGroupCard(
                                        cat = single.key,
                                        list = single.value,
                                        countUnit = countUnit,
                                        open = false,
                                        cfg = cfg,
                                        done = done,
                                        fav = fav,
                                        expandedIds = expandedIds,
                                        colors = colors,
                                        onToggleGroup = { expandedGroups = expandedGroups + single.key },
                                        onToggleDone = ::toggleDone,
                                        onToggleFav = ::toggleFav,
                                        onToggleExpand = ::toggleExpand,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Box(
                                    modifier = if (land) Modifier.widthIn(max = 780.dp).fillMaxWidth() else Modifier.fillMaxWidth()
                                ) {
                                    DocGroupCard(
                                        cat = single.key,
                                        list = single.value,
                                        countUnit = countUnit,
                                        open = open,
                                        cfg = cfg,
                                        done = done,
                                        fav = fav,
                                        expandedIds = expandedIds,
                                        colors = colors,
                                        onToggleGroup = {
                                            expandedGroups = if (open) expandedGroups - single.key else expandedGroups + single.key
                                        },
                                        onToggleDone = ::toggleDone,
                                        onToggleFav = ::toggleFav,
                                        onToggleExpand = ::toggleExpand,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            for (o in items) {
                val id = o.optString("id")
                item(key = id) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = if (land) Modifier.widthIn(max = 780.dp).fillMaxWidth() else Modifier.fillMaxWidth()
                        ) {
                            DocItemCard(
                                o = o,
                                cfg = cfg,
                                doneSet = done,
                                favSet = fav,
                                colors = colors,
                                onToggleDone = ::toggleDone,
                                onToggleFav = ::toggleFav,
                                onToggleExpand = ::toggleExpand,
                                expanded = expandedIds.contains(id)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocGroupCard(
    cat: String,
    list: List<JSONObject>,
    countUnit: String,
    open: Boolean,
    cfg: DocListConfig,
    done: Set<String>,
    fav: Set<String>,
    expandedIds: Set<String>,
    colors: OpsColors,
    onToggleGroup: () -> Unit,
    onToggleDone: (String) -> Unit,
    onToggleFav: (String) -> Unit,
    onToggleExpand: (String) -> Unit,
) {
    val groupDone = remember(list, done) { list.count { done.contains(it.optString("id")) } }
    OpsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassClickable(pressedScale = 0.985f, glowBoost = 0.05f, onClick = onToggleGroup),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(cat, color = colors.textMain, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    if (groupDone > 0) "已完成 $groupDone / ${list.size}" else "共 ${list.size} $countUnit",
                    color = if (groupDone > 0) colors.accent else colors.textMute,
                    fontSize = 11.5.sp
                )
            }
            Text(
                "${list.size}",
                color = colors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .glassBadgeSurface(colors, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            Spacer(Modifier.width(8.dp))
            OpsChevron(colors, rotation = if (open) -90f else 90f)
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            list.forEachIndexed { idx, o ->
                val id = o.optString("id")
                DocItemCard(
                    o = o,
                    cfg = cfg,
                    doneSet = done,
                    favSet = fav,
                    colors = colors,
                    onToggleDone = onToggleDone,
                    onToggleFav = onToggleFav,
                    onToggleExpand = onToggleExpand,
                    expanded = expandedIds.contains(id)
                )
                if (idx < list.lastIndex) {
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun DocItemCard(
    o: JSONObject,
    cfg: DocListConfig,
    doneSet: Set<String>,
    favSet: Set<String>,
    colors: OpsColors,
    onToggleDone: (String) -> Unit,
    onToggleFav: (String) -> Unit,
    onToggleExpand: (String) -> Unit,
    expanded: Boolean,
) {
    val id = o.optString("id")
    val isDone = doneSet.contains(id)
    val isFav = favSet.contains(id)
    var meta = o.optString("cat")
    if (isDone) meta += " · ✓ ${cfg.doneLabel}"
    if (isFav) meta += " · ★ 收藏"

    OpsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .glassClickable(pressedScale = 0.985f, glowBoost = 0.05f) { onToggleExpand(id) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                chipText(id, cfg),
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
                    o.optString("t"),
                    color = colors.textMain,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(meta, color = if (isDone) colors.accent else colors.textMute, fontSize = 11.sp)
            }
            Spacer(Modifier.width(6.dp))
            OpsChevron(colors, rotation = if (expanded) -90f else 90f)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            OpsBlocks(o.optJSONArray("blocks") ?: JSONArray())
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isDone) "✓ ${cfg.doneLabel}" else "标记${cfg.doneLabel}",
                    color = if (isDone) Color.White else colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .glassChipSurface(colors, active = isDone, shape = RoundedCornerShape(12.dp))
                        .glassClickable { onToggleDone(id) }
                        .padding(vertical = 9.5.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isFav) "★ 已收藏" else "☆ 收藏",
                    color = if (isFav) Color.White else colors.textSub,
                    fontSize = 13.sp,
                    fontWeight = if (isFav) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier
                        .glassChipSurface(colors, active = isFav, shape = RoundedCornerShape(12.dp))
                        .glassClickable { onToggleFav(id) }
                        .padding(horizontal = 15.dp, vertical = 9.5.dp)
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
