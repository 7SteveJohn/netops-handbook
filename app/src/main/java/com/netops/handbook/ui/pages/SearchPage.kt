package com.netops.handbook.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.TbSearchIndex
import com.netops.handbook.ui.PageData
import androidx.compose.ui.draw.clip
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜索页（2026-11-15 M4-1，全 Compose）：NativeSearchView 的 1:1 移植。
 *
 * 索引五类（ setPageData 同语义）：mod(标题+blocks 平铺文本)/fault/iv/dict
 * (fn+全列值)/gloss/ref(标题+描述)；查询=分词空格分隔取交集，hay=title+text+
 * meta 全小写；上限 60 条；AnnotatedString 命中高亮（琥珀底，同旧 highlight）。
 * 路由=pageRoute "search"（dock 退位走 pagesGo 单点），返回=Router.back。
 */

private class SItem(
    val type: String, val id: String, val pid: String,
    val title: String, val meta: String, val text: String,
)

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
    val out = ArrayList<SItem>()
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
    val toks = query.trim().lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
    val hl = if (dark) Color(0x5A816E3A) else Color(0x66E3B341)   // 琥珀底(旧 highlight 同系)

    /* 教材全文检索（2026-11-18）：search.json 倒排索引，首次有查询词时懒加载并缓存 */
    val ctx = LocalContext.current
    var tbIndex by remember { mutableStateOf<TbSearchIndex?>(null) }
    LaunchedEffect(toks.isNotEmpty()) {
        if (toks.isNotEmpty() && tbIndex == null) tbIndex = TbSearchIndex.load(ctx)
    }
    val tbHits = remember(query, tbIndex) {
        val tb = tbIndex
        if (toks.isEmpty() || tb == null) emptyList() else tb.query(toks)
    }

    val results = remember(query, index) {
        if (toks.isEmpty()) emptyList()
        else index.filter { it2 ->
            val hay = (it2.title + " " + it2.text + " " + it2.meta).lowercase()
            toks.all { hay.contains(it) }
        }.take(60)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 10.dp
            )
            .padding(horizontal = 14.dp)
            .imePadding()
    ) {
        /* 输入行 */
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = colors.textMain, fontSize = 15.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.chipBgOff)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                androidx.compose.foundation.layout.Box {
                    if (query.isEmpty()) Text(
                        "搜索模块 / 排障 / 面试 / 命令 / 术语",
                        color = colors.textMute, fontSize = 15.sp
                    )
                    inner()
                }
            }
        )
        Spacer(Modifier.height(14.dp))

        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (toks.isEmpty()) {
                item {
                    Text(
                        "输入关键词：模块名、命令、现象、术语、教材内容都行\n多个词用空格分隔，会取交集",
                        color = colors.textMute, fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }
            } else if (results.isEmpty() && tbHits.isEmpty()) {
                item {
                    Text("没有匹配的内容", color = colors.textMute, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 24.dp))
                }
            } else {
                for (it2 in results) {
                    item(key = it2.type + ":" + it2.id) {
                        OpsCard(Modifier.fillMaxWidth().clickable {
                            when (it2.type) {
                                "mod" -> onGo("phase-focus", it2.pid.ifEmpty { "p-1" } + "|" + it2.id)
                                "gloss" -> onGo("glossary", "")
                                "ref" -> onGo("refs", "")
                                else -> onGo(it2.type, "")
                            }
                        }) {
                            Text(it2.meta, color = colors.accent, fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(highlighted(it2.title, toks, hl), color = colors.textMain,
                                fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            it2.text.takeIf { t -> t.isNotEmpty() }?.let { t ->
                                Spacer(Modifier.height(5.dp))
                                Text(highlighted(t.take(70), toks, hl),
                                    color = colors.textSub, fontSize = 12.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                /* 教材全书命中段（2026-11-18）：小节粒度跳转 tbread@bi */
                if (tbHits.isNotEmpty()) {
                    item(key = "tb-head") {
                        Text(
                            "教材全书 · ${tbHits.size} 条命中",
                            color = colors.accent, fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    for (h in tbHits) {
                        item(key = "tb:" + h.chapterId + ":" + h.bi) {
                            OpsCard(Modifier.fillMaxWidth().clickable {
                                onGo("tbread", h.chapterId + "@" + h.bi)
                            }) {
                                Text("教材 · " + h.chapterTitle, color = colors.accent,
                                    fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(highlighted(h.sec.ifEmpty { h.chapterTitle }, toks, hl),
                                    color = colors.textMain, fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(5.dp))
                                Text(highlighted(h.text.take(70), toks, hl),
                                    color = colors.textSub, fontSize = 12.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
