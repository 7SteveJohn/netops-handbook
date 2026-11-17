package com.netops.handbook.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.PageData
import com.netops.handbook.ui.TbSearchIndex
import com.netops.handbook.ui.glassBadgeSurface
import com.netops.handbook.ui.glassChipSurface
import com.netops.handbook.ui.glassClickable
import com.netops.handbook.ui.glassSearchFieldSurface
import com.netops.handbook.ui.isOpsCompactLandscape
import com.netops.handbook.ui.isOpsLandscape
import com.netops.handbook.ui.rememberOpsPagePadding
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜索页（2026-11-15 M4-1 / 2026-11-18 冰晶搜索槽 + 微徽章 + 横屏安全区与双列适配）。
 *
 * 索引五类（setPageData 同语义）：mod(标题+blocks 平铺文本)/fault/iv/dict
 * (fn+全列值)/gloss/ref(标题+描述) + 50 章教材全文后台异步预热索引；
 * 查询=分词空格分隔取交集，hay=title+text+meta 全小写；上限 60 条；
 * AnnotatedString 命中高亮（琥珀底，同旧 highlight）。
 */

private class SItem(
    val type: String, val id: String, val pid: String,
    val title: String, val meta: String, val text: String,
) {
    val hayLower: String = "$title $text $meta".lowercase()
}

@Volatile
private var cachedIndexRoot: JSONObject? = null
@Volatile
private var cachedIndexItems: List<SItem> = emptyList()
private val WS_REGEX = Regex("\\s+")

private fun flatBlocks(blocks: JSONArray?): String {
    if (blocks == null) return ""
    val sb = StringBuilder()
    for (i in 0 until blocks.length()) {
        val b = blocks.optJSONObject(i) ?: continue
        sb.append(b.optString("t")).append(' ')
        val arr = b.optJSONArray("items")
        if (arr != null) for (k in 0 until arr.length()) sb.append(arr.optString(k)).append(' ')
    }
    return sb.toString()
}

private fun buildIndex(root: JSONObject?): List<SItem> {
    if (root == null) return emptyList()
    if (cachedIndexRoot === root && cachedIndexItems.isNotEmpty()) return cachedIndexItems
    val out = ArrayList<SItem>(400)
    fun doc(key: String, type: String, label: String) {
        val arr = root.optJSONArray(key) ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(SItem(type, o.optString("id"), "",
                o.optString("t"), "$label · ${o.optString("cat")}", o.optString("d").ifEmpty { flatBlocks(o.optJSONArray("blocks")) }))
        }
    }
    root.optJSONArray("phases")?.let { phases ->
        for (i in 0 until phases.length()) {
            val p = phases.optJSONObject(i) ?: continue
            val mods = p.optJSONArray("mods") ?: continue
            for (k in 0 until mods.length()) {
                val m = mods.optJSONObject(k) ?: continue
                out.add(SItem("mod", m.optString("id"), p.optString("id"),
                    m.optString("t"),
                    "${p.optString("short")} ${m.optInt("n")}/${m.optInt("tc")}",
                    flatBlocks(m.optJSONArray("blocks"))))
            }
        }
    }
    doc("faults", "fault", "排障")
    doc("interview", "iv", "面试")
    root.optJSONObject("dict")?.let { d ->
        val rows = d.optJSONArray("rows")
        val keys = ArrayList<String>()
        d.optJSONArray("cols")?.let { cols ->
            for (i in 0 until cols.length()) keys.add(cols.optJSONObject(i)?.optString("k") ?: "")
        }
        if (rows != null) for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val sb = StringBuilder()
            for (k in keys) sb.append(r.optString(k)).append(' ')
            out.add(SItem("dict", "dict$i", "", r.optString("fn"),
                "命令 · ${r.optString("cat")}", sb.toString()))
        }
    }
    fun simple(key: String, type: String, label: String) {
        val arr = root.optJSONArray(key) ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(SItem(type, o.optString("id", "$label$i"), "",
                o.optString("t"), label, o.optString("d")))
        }
    }
    simple("glossary", "gloss", "术语")
    simple("refs", "ref", "速查")
    cachedIndexRoot = root
    cachedIndexItems = out
    return out
}

/** 命中高亮（琥珀底，旧 highlight 同观感）。 */
private fun highlighted(s: String, toks: List<String>, hl: Color): AnnotatedString {
    val sb = AnnotatedString.Builder(s)
    if (toks.isNotEmpty()) {
        val lower = s.lowercase()
        var i = 0
        while (i < lower.length) {
            var hitLen = 0
            for (t in toks) if (lower.startsWith(t, i)) hitLen = maxOf(hitLen, t.length)
            if (hitLen > 0) {
                sb.addStyle(SpanStyle(background = hl), i, i + hitLen)
                i += hitLen
            } else i++
        }
    }
    return sb.toAnnotatedString()
}

@Composable
fun SearchPage(data: () -> JSONObject?, dark: Boolean, onGo: (String, String) -> Unit) {
    val colors = LocalOpsColors.current
    val index = remember(PageData.version) { buildIndex(data()) }
    var query by remember { mutableStateOf("") }
    val toks = remember(query) {
        query.trim().lowercase().split(WS_REGEX).filter { it.isNotEmpty() }
    }
    val hl = if (dark) Color(0x5A816E3A) else Color(0x66E3B341)   // 琥珀底(旧 highlight 同系)

    /* 教材全文检索（2026-11-18）：进入搜索页即后台 IO 协程预热单例索引，主线程零阻塞 */
    val ctx = LocalContext.current
    var tbIndex by remember { mutableStateOf<TbSearchIndex?>(null) }
    var tbLoading by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        tbIndex = TbSearchIndex.getOrLoad(ctx)
        tbLoading = false
    }
    val tbHits = remember(toks, tbIndex) {
        val tb = tbIndex
        if (toks.isEmpty() || tb == null) emptyList() else tb.query(toks)
    }

    val results = remember(toks, index) {
        if (toks.isEmpty()) emptyList()
        else {
            val matched = ArrayList<SItem>(60)
            for (it2 in index) {
                if (toks.all { it2.hayLower.contains(it) }) {
                    matched.add(it2)
                    if (matched.size >= 60) break
                }
            }
            matched
        }
    }

    val compactLand = isOpsCompactLandscape()
    val land = isOpsLandscape()
    /* 搜索页底栏隐藏、无左侧竖排导轨，但需避让紧凑横屏 48dp 顶栏与左右挖孔屏安全区 */
    val pagePad = rememberOpsPagePadding(
        extraTop = if (compactLand) 8.dp else 12.dp,
        extraBottom = 14.dp,
        hasDock = false,
        hasTopBar = true,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(pagePad)
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
        ) {
            /* 顶部冰晶内凹搜索胶囊 + 右侧命中计数与一键清空圆钮 */
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.textMain, fontSize = 15.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .glassSearchFieldSurface(colors, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = if (compactLand) 9.5.dp else 11.5.dp),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(
                                    "搜索模块 / 排障 / 面试 / 命令 / 术语 / 教材",
                                    color = colors.textMute,
                                    fontSize = 14.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            inner()
                        }
                        if (toks.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${results.size + tbHits.size} 条",
                                color = colors.accent,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .glassBadgeSurface(colors, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
                            )
                        }
                        if (query.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .glassChipSurface(colors, active = false, shape = CircleShape)
                                    .glassClickable(pressedScale = 0.90f, glowBoost = 0.12f) {
                                        query = ""
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "✕",
                                    color = colors.textSub,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            )

            Spacer(Modifier.height(if (compactLand) 10.dp else 14.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (toks.isEmpty()) {
                    item(key = "__search_hint__") {
                        OpsCard {
                            Text(
                                "全局离线秒搜",
                                color = colors.textMain,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "输入关键词：模块名、命令、排障现象、术语或 50 章教材内容均可检索。\n多个关键词用空格分隔，自动按交集精准匹配。",
                                color = colors.textMute,
                                fontSize = 12.5.sp,
                                lineHeight = 17.5.sp
                            )
                        }
                    }
                } else if (results.isEmpty() && tbHits.isEmpty()) {
                    item(key = "__search_empty__") {
                        OpsCard {
                            Text(
                                if (tbLoading) "正在检索教材全书索引…" else "没有匹配的内容，请尝试缩减或更换关键词",
                                color = colors.textMute,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                } else {
                    if (land && results.size > 1) {
                        val rows = results.chunked(2)
                        for (pair in rows) {
                            val rowKey = "sr:" + pair.joinToString("|") { it.type + ":" + it.id }
                            item(key = rowKey) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(Modifier.weight(1f)) {
                                        SearchHitCard(pair[0], toks, hl, colors, onGo)
                                    }
                                    if (pair.size == 2) {
                                        Box(Modifier.weight(1f)) {
                                            SearchHitCard(pair[1], toks, hl, colors, onGo)
                                        }
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        for (it2 in results) {
                            item(key = it2.type + ":" + it2.id) {
                                SearchHitCard(it2, toks, hl, colors, onGo)
                            }
                        }
                    }

                    /* 教材全书命中段（2026-11-18）：小节粒度跳转 tbread@bi */
                    if (tbHits.isNotEmpty()) {
                        item(key = "tb-head") {
                            Row(
                                modifier = Modifier.padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "教材全书 · ${tbHits.size} 条命中",
                                    color = colors.accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .glassBadgeSurface(colors, RoundedCornerShape(9.dp))
                                        .padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }
                        }
                        if (land && tbHits.size > 1) {
                            val tbRows = tbHits.chunked(2)
                            for (pair in tbRows) {
                                val rowKey = "tbp:" + pair.joinToString("|") { it.chapterId + ":" + it.bi }
                                item(key = rowKey) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Box(Modifier.weight(1f)) {
                                            SearchTbHitCard(pair[0], toks, hl, colors, onGo)
                                        }
                                        if (pair.size == 2) {
                                            Box(Modifier.weight(1f)) {
                                                SearchTbHitCard(pair[1], toks, hl, colors, onGo)
                                            }
                                        } else {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        } else {
                            for (h in tbHits) {
                                item(key = "tb:" + h.chapterId + ":" + h.bi) {
                                    SearchTbHitCard(h, toks, hl, colors, onGo)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHitCard(
    it2: SItem,
    toks: List<String>,
    hl: Color,
    colors: OpsColors,
    onGo: (String, String) -> Unit,
) {
    OpsCard(
        onClick = {
            when (it2.type) {
                "mod" -> onGo("phase-focus", it2.pid.ifEmpty { "p-1" } + "|" + it2.id)
                "gloss" -> onGo("glossary", "")
                "ref" -> onGo("refs", "")
                else -> onGo(it2.type, "")
            }
        }
    ) {
        Text(
            it2.meta,
            color = colors.accent,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .glassBadgeSurface(colors, RoundedCornerShape(8.dp))
                .padding(horizontal = 7.5.dp, vertical = 2.5.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            highlighted(it2.title, toks, hl),
            color = colors.textMain,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        it2.text.takeIf { t -> t.isNotEmpty() }?.let { t ->
            Spacer(Modifier.height(5.dp))
            Text(
                highlighted(t.take(80), toks, hl),
                color = colors.textSub,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SearchTbHitCard(
    h: TbSearchIndex.Hit,
    toks: List<String>,
    hl: Color,
    colors: OpsColors,
    onGo: (String, String) -> Unit,
) {
    OpsCard(
        onClick = { onGo("tbread", h.chapterId + "@" + h.bi) }
    ) {
        Text(
            "教材 · " + h.chapterTitle,
            color = colors.accent,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .glassBadgeSurface(colors, RoundedCornerShape(8.dp))
                .padding(horizontal = 7.5.dp, vertical = 2.5.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            highlighted(h.sec.ifEmpty { h.chapterTitle }, toks, hl),
            color = colors.textMain,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(5.dp))
        Text(
            highlighted(h.text.take(80), toks, hl),
            color = colors.textSub,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
