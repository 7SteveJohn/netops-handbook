package com.netops.handbook.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.netops.handbook.ui.glassSearchFieldSurface
import com.netops.handbook.ui.isOpsLandscape
import com.netops.handbook.ui.rememberOpsPagePadding
import org.json.JSONArray
import org.json.JSONObject

private class DictRowEntry(
    val raw: JSONObject,
    val fn: String,
    val cat: String,
    val hayLower: String,
)

private val DICT_WS_REGEX = Regex("\\s+")

/**
 * 命令字典页（2026-11-14 M3-2⑤ / 2026-11-18 液态玻璃微组件 + 预计算索引 + 横屏双列优化）。
 *
 * 形态 = 头卡（计数+免责+内凹冰晶搜索胶囊+宝石蓝/冰晶平台玻璃芯片）+ cat 分组折叠卡
 * （点开才组内渲染命令卡；横屏下收起组两列成行、展开组内部命令卡双列并排）。
 * 性能：parsed = remember(ver) 一次性预拼接每行小写 hayLower，输入筛选零 StringBuilder / lowercase 分配。
 */
@Composable
fun DictPage(dataSupplier: () -> org.json.JSONObject?, dark: Boolean) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ver = PageData.version

    val parsed = remember(ver) {
        val dict = dataSupplier()?.optJSONObject("dict") ?: JSONObject()
        val colsArr = dict.optJSONArray("cols") ?: JSONArray()
        val keys = ArrayList<Pair<String, String>>()   // k→n（键→显示名）
        for (i in 0 until colsArr.length()) {
            val c = colsArr.optJSONObject(i) ?: continue
            keys.add(c.optString("k") to c.optString("n"))
        }
        val rows = dict.optJSONArray("rows") ?: JSONArray()
        val allRows = ArrayList<DictRowEntry>(rows.length())
        val groups = LinkedHashMap<String, MutableList<DictRowEntry>>()
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            val fn = o.optString("fn")
            val catRaw = o.optString("cat")
            val catGroup = catRaw.ifEmpty { "未分类" }
            val sb = StringBuilder(fn.length + catRaw.length + 128)
                .append(fn).append(' ').append(catRaw)
            for ((k, _) in keys) sb.append(' ').append(o.optString(k))
            val entry = DictRowEntry(
                raw = o,
                fn = fn,
                cat = catRaw,
                hayLower = sb.toString().lowercase(),
            )
            allRows.add(entry)
            groups.getOrPut(catGroup) { ArrayList() }.add(entry)
        }
        Triple(keys, groups, allRows)
    }
    val colKeys = parsed.first
    val rawGroups = parsed.second
    val allRows = parsed.third

    var query by remember { mutableStateOf("") }
    var selectedPlatform by remember { mutableStateOf("all") }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    var collapsedFilterGroups by remember { mutableStateOf(setOf<String>()) }

    val toks = remember(query) {
        query.trim().lowercase().split(DICT_WS_REGEX).filter { it.isNotEmpty() }
    }
    val isFiltering = toks.isNotEmpty() || selectedPlatform != "all"

    val displayGroups = remember(toks, selectedPlatform, rawGroups) {
        if (!isFiltering) rawGroups
        else {
            val filtered = LinkedHashMap<String, MutableList<DictRowEntry>>()
            for ((cat, list) in rawGroups) {
                val matchedList = list.filter { entry ->
                    if (selectedPlatform != "all") {
                        val v = entry.raw.optString(selectedPlatform, "")
                        if (v.isEmpty() || v == "-") return@filter false
                    }
                    if (toks.isNotEmpty()) {
                        toks.all { entry.hayLower.contains(it) }
                    } else true
                }
                if (matchedList.isNotEmpty()) {
                    filtered[cat] = matchedList.toMutableList()
                }
            }
            filtered
        }
    }
    val totalFound = remember(displayGroups) { displayGroups.values.sumOf { it.size } }

    val pagePad = rememberOpsPagePadding()
    val land = isOpsLandscape()

    fun copyCmd(text: String) {
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(ctx, "已复制：$text", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun isGroupOpen(cat: String): Boolean = if (isFiltering) {
        !collapsedFilterGroups.contains(cat)
    } else {
        expandedGroups.contains(cat)
    }

    fun toggleGroup(cat: String, currentlyOpen: Boolean) {
        if (isFiltering) {
            collapsedFilterGroups = if (currentlyOpen) collapsedFilterGroups + cat else collapsedFilterGroups - cat
        } else {
            expandedGroups = if (currentlyOpen) expandedGroups - cat else expandedGroups + cat
        }
    }

    /* 横屏行编排：收起组两列并排，展开组独占满宽并在组内双列渲染命令卡 */
    val groupRows = remember(displayGroups, land, isFiltering, expandedGroups, collapsedFilterGroups) {
        val entries = displayGroups.entries.toList()
        if (!land) {
            entries.map { listOf(it) }
        } else {
            val rows = ArrayList<List<Map.Entry<String, MutableList<DictRowEntry>>>>()
            var pending: Map.Entry<String, MutableList<DictRowEntry>>? = null
            for (e in entries) {
                val open = if (isFiltering) !collapsedFilterGroups.contains(e.key) else expandedGroups.contains(e.key)
                if (open) {
                    pending?.let { rows.add(listOf(it)); pending = null }
                    rows.add(listOf(e))
                } else {
                    val p = pending
                    if (p == null) {
                        pending = e
                    } else {
                        rows.add(listOf(p, e))
                        pending = null
                    }
                }
            }
            pending?.let { rows.add(listOf(it)) }
            rows
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePad,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        /* 头卡：标题 + 内凹冰晶搜索胶囊 + 宝石蓝/冰晶平台筛选芯片 */
        item(key = "__dict_head__") {
            OpsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "命令字典",
                        color = colors.textMain,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (isFiltering) "$totalFound / ${allRows.size} 条" else "共 ${allRows.size} 条",
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
                    if (isFiltering) "筛选结果 · 找到 $totalFound 条匹配命令"
                    else "多厂商平台命令横向对照 · 命令在不同版本/平台可能有差异，请以官方文档为准。",
                    color = colors.textMute,
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(10.dp))

                /* 内凹冰晶搜索胶囊 */
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        collapsedFilterGroups = emptySet()
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.textMain, fontSize = 14.sp),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSearchFieldSurface(colors, RoundedCornerShape(13.dp))
                        .padding(horizontal = 13.dp, vertical = 9.5.dp),
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) {
                                    Text(
                                        "搜索命令 / 功能 / 关键字（例如 vlan, route, interface）",
                                        color = colors.textMute,
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                }
                                inner()
                            }
                            if (query.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .glassChipSurface(colors, active = false, shape = CircleShape)
                                        .glassClickable(pressedScale = 0.90f, glowBoost = 0.12f) {
                                            query = ""
                                            collapsedFilterGroups = emptySet()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "✕",
                                        color = colors.textSub,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                )

                Spacer(Modifier.height(10.dp))

                /* 平台筛选液态玻璃胶囊芯片行 */
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isAll = selectedPlatform == "all"
                    DictPlatformChip(
                        label = "全部平台",
                        active = isAll,
                        colors = colors,
                        onClick = {
                            selectedPlatform = "all"
                            collapsedFilterGroups = emptySet()
                        }
                    )
                    for ((k, n) in colKeys) {
                        val active = selectedPlatform == k
                        DictPlatformChip(
                            label = n,
                            active = active,
                            colors = colors,
                            onClick = {
                                selectedPlatform = if (active) "all" else k
                                collapsedFilterGroups = emptySet()
                            }
                        )
                    }
                }
            }
        }

        if (displayGroups.isEmpty()) {
            item(key = "__dict_empty__") {
                OpsCard {
                    Text(
                        "没有匹配的命令，请尝试更换关键字或切换平台筛选",
                        color = colors.textMute,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    )
                }
            }
        }

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
                                DictGroupCard(
                                    cat = entry.key,
                                    list = entry.value,
                                    open = isGroupOpen(entry.key),
                                    land = land,
                                    colKeys = colKeys,
                                    selectedPlatform = selectedPlatform,
                                    colors = colors,
                                    onToggle = { toggleGroup(entry.key, isGroupOpen(entry.key)) },
                                    copyCmd = ::copyCmd
                                )
                            }
                        }
                    }
                } else {
                    val single = row[0]
                    val open = isGroupOpen(single.key)
                    if (land && !open) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(Modifier.weight(1f)) {
                                DictGroupCard(
                                    cat = single.key,
                                    list = single.value,
                                    open = false,
                                    land = true,
                                    colKeys = colKeys,
                                    selectedPlatform = selectedPlatform,
                                    colors = colors,
                                    onToggle = { toggleGroup(single.key, false) },
                                    copyCmd = ::copyCmd
                                )
                            }
                            Spacer(Modifier.weight(1f))
                        }
                    } else {
                        DictGroupCard(
                            cat = single.key,
                            list = single.value,
                            open = open,
                            land = land,
                            colKeys = colKeys,
                            selectedPlatform = selectedPlatform,
                            colors = colors,
                            onToggle = { toggleGroup(single.key, open) },
                            copyCmd = ::copyCmd
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DictPlatformChip(
    label: String,
    active: Boolean,
    colors: OpsColors,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (active) Color.White else colors.textSub,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier
            .glassChipSurface(colors, active = active, shape = RoundedCornerShape(10.dp))
            .glassClickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    )
}

@Composable
private fun DictGroupCard(
    cat: String,
    list: List<DictRowEntry>,
    open: Boolean,
    land: Boolean,
    colKeys: List<Pair<String, String>>,
    selectedPlatform: String,
    colors: OpsColors,
    onToggle: () -> Unit,
    copyCmd: (String) -> Unit,
) {
    OpsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassClickable(pressedScale = 0.985f, glowBoost = 0.05f, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(cat, color = colors.textMain, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    if (open) "点击收起分组" else "点击展开命令对照",
                    color = colors.textMute,
                    fontSize = 11.5.sp
                )
            }
            Text(
                "${list.size} 条",
                color = colors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .glassBadgeSurface(colors, RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.5.dp, vertical = 3.dp)
            )
            Spacer(Modifier.width(8.dp))
            OpsChevron(colors, rotation = if (open) -90f else 90f)
        }
        /* 组内命令卡：展开时惰性组合；横屏下双列并排避免超宽拉伸 */
        if (open) {
            Spacer(Modifier.height(10.dp))
            if (land && list.size > 1) {
                val pairs = list.chunked(2)
                pairs.forEachIndexed { idx, pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(Modifier.weight(1f)) {
                            DictCmdCard(pair[0], colKeys, selectedPlatform, colors, copyCmd)
                        }
                        if (pair.size == 2) {
                            Box(Modifier.weight(1f)) {
                                DictCmdCard(pair[1], colKeys, selectedPlatform, colors, copyCmd)
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    if (idx < pairs.lastIndex) {
                        Spacer(Modifier.height(10.dp))
                    }
                }
            } else {
                list.forEachIndexed { idx, entry ->
                    DictCmdCard(entry, colKeys, selectedPlatform, colors, copyCmd)
                    if (idx < list.lastIndex) {
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DictCmdCard(
    entry: DictRowEntry,
    colKeys: List<Pair<String, String>>,
    highlightPlatform: String,
    colors: OpsColors,
    copyCmd: (String) -> Unit,
) {
    val o = entry.raw
    OpsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.fn,
                color = colors.textMain,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (entry.cat.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    entry.cat,
                    color = colors.accent,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .glassBadgeSurface(colors, RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
        for ((k, n) in colKeys) {
            val v = o.optString(k, "")
            val na = v.isEmpty() || v == "-"
            val isFocusedPlatform = highlightPlatform != "all" && highlightPlatform == k
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 7.dp)
                    .then(
                        if (isFocusedPlatform) Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(colors.chipBgOn.copy(alpha = 0.55f))
                            .padding(horizontal = 5.dp, vertical = 2.5.dp)
                        else Modifier
                    ),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    n,
                    color = if (isFocusedPlatform) colors.accent else colors.textMute,
                    fontSize = 11.sp,
                    fontWeight = if (isFocusedPlatform) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.width(90.dp)
                )
                Text(
                    if (na) "不适用" else v,
                    color = if (na) colors.textMute else colors.textMain,
                    fontSize = 12.5.sp,
                    fontFamily = if (na) null else FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (na) Modifier else Modifier.clickable { copyCmd(v) })
                )
            }
        }
    }
}

/** Java 侧挂载入口（Composeable lambda 无法在 Java 源里书写）。 */
fun androidx.compose.ui.platform.ComposeView.setDictContent(
    data: () -> org.json.JSONObject?, dark: () -> Boolean, direct: () -> Boolean,
) {
    setViewCompositionStrategy(
        androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    setContent {
        OpsTheme(dark(), direct()) {
            DictPage(data, dark())
        }
    }
}
