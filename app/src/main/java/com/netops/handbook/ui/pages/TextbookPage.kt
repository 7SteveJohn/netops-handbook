package com.netops.handbook.ui.pages

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsChip
import com.netops.handbook.ui.TbBlock
import com.netops.handbook.ui.PageData
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 教材全书页（2026-11-18 教材接入）：书架（route "textbook"）+ 章节阅读
 * （route "tbread"，arg = "<chapterId>" 或 "<chapterId>@<bi>" 搜索深链）。
 *
 * 数据 = assets/textbook/（gen-textbook.js 产出）：index.json 书架、
 * ch/<id>.json 章文（自包含 sections/prev/next）。阅读进度 = SP
 * netops_native "tbLast"（QuizPage 同款 SP 口径）。
 * 视觉 = 全复用既有口径（OpsCard/OpsChip、Section 小字写法、statusTop+56+12
 * 顶栏避让、navBottom+96 dock 避让），不动任何现有页面设计。
 */

/* ---- 数据装载（assets 直读，native-data.json 同款同步口径） ---- */

private fun loadTbIndex(ctx: Context): JSONObject? = try {
    JSONObject(ctx.assets.open("textbook/index.json").bufferedReader().use { it.readText() })
} catch (e: Throwable) { null }

private fun loadTbChapter(ctx: Context, id: String): JSONObject? = try {
    JSONObject(ctx.assets.open("textbook/ch/$id.json").bufferedReader().use { it.readText() })
} catch (e: Throwable) { null }

private fun readTbLast(ctx: Context): String = try {
    ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
        .getString("tbLast", "") ?: ""
} catch (e: Throwable) { "" }

private fun writeTbLast(ctx: Context, id: String) {
    try {
        ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
            .edit().putString("tbLast", id).apply()
    } catch (e: Throwable) { }
}

private fun domainsOf(index: JSONObject?): List<JSONObject> {
    val arr = index?.optJSONArray("domains") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

private fun chaptersOf(domain: JSONObject): List<JSONObject> {
    val arr = domain.optJSONArray("chapters") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

/* ---- 书架（route "textbook"） ---- */

@Composable
fun TextbookShelf(onGo: (String, String) -> Unit) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val ver = PageData.version
    val index = remember(ver) { loadTbIndex(ctx) }
    var tbLast by remember { mutableStateOf(readTbLast(ctx)) }

    val domains = domainsOf(index)
    val totalWords = domains.sumOf { d -> chaptersOf(d).sumOf { it.optInt("words") } }
    val lastTitle = domains.firstNotNullOfOrNull { d ->
        chaptersOf(d).firstOrNull { it.optString("id") == tbLast }?.optString("title")
    }
    val counts = index?.optJSONObject("counts")

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = statusTop + 56.dp + 12.dp,
            bottom = navBottom + 96.dp,
            start = 14.dp, end = 14.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "head") {
            OpsCard {
                Text("运维教材全书", color = colors.textMain, fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "网络与 IT 运维教材级知识库 · ${counts?.optInt("domains") ?: domains.size} 领域" +
                        " · ${counts?.optInt("chapters") ?: 0} 章 · 约 ${totalWords / 10000} 万字 · 全文可搜",
                    color = colors.textMute, fontSize = 12.5.sp, lineHeight = 16.sp
                )
                if (lastTitle != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.chipBgOff)
                            .clickable { onGo("tbread", tbLast) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        OpsChip("上次读到")
                        Spacer(Modifier.width(10.dp))
                        Text(lastTitle, color = colors.textMain, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        for (d in domains) {
            item(key = "h" + d.optString("id")) {
                /* Section 小字写法（HomePage.kt:227 同款）：mute 12.5sp SemiBold */
                Text(
                    "${d.optInt("n")}. ${d.optString("title")}",
                    color = colors.textMute, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 0.dp)
                )
            }
            for (ch in chaptersOf(d)) {
                val id = ch.optString("id")
                item(key = id) {
                    OpsCard(onClick = { onGo("tbread", id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OpsChip("%02d".format(ch.optInt("n")))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ch.optString("title"), color = colors.textMain,
                                    fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (ch.optString("sub").isNotEmpty()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        if (id == tbLast) "上次读到 · ${ch.optString("sub")}"
                                        else ch.optString("sub"),
                                        color = if (id == tbLast) colors.accent else colors.textMute,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (index == null) {
            item {
                Text("教材数据缺失（assets/textbook/ 未生成）", color = colors.textMute,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 20.dp))
            }
        }
    }
}

/* ---- 章节阅读（route "tbread"，arg = id 或 id@bi） ---- */

@Composable
fun TextbookReader(arg: String, onGo: (String, String) -> Unit) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val chapterId = arg.substringBefore('@')
    val jumpBi = arg.substringAfter('@', "").toIntOrNull() ?: -1
    val doc = remember(chapterId) { loadTbChapter(ctx, chapterId) }

    LaunchedEffect(chapterId) { if (chapterId.isNotEmpty()) writeTbLast(ctx, chapterId) }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    if (doc == null || chapterId.isEmpty()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = statusTop + 56.dp + 12.dp, start = 14.dp, end = 14.dp)
        ) {
            Text("章节不存在或教材数据缺失", color = colors.textMute, fontSize = 13.sp)
        }
        return
    }

    val blocks = doc.optJSONArray("blocks") ?: JSONArray()
    val sections = doc.optJSONArray("sections") ?: JSONArray()
    val listState = remember(chapterId) { LazyListState() }
    val scope = rememberCoroutineScope()

    /* 搜索深链：落到 h2 块（头卡占 item 0，块 bi → item bi+1） */
    LaunchedEffect(chapterId) {
        if (jumpBi > 0 && jumpBi < blocks.length()) listState.scrollToItem(jumpBi + 1)
    }

    Column(Modifier.fillMaxSize()) {
        /* 章内小节快跳条（固定在顶栏下，不随正文滚动） */
        if (sections.length() > 0) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        top = statusTop + 56.dp + 10.dp,
                        start = 14.dp, end = 14.dp, bottom = 8.dp
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (i in 0 until sections.length()) {
                    val s = sections.optJSONObject(i) ?: continue
                    val label = s.optString("t")
                    Text(
                        "${i + 1} ${if (label.length > 9) label.take(9) + "…" else label}",
                        color = colors.textSub, fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.chipBgOff)
                            .clickable {
                                scope.launch { listState.animateScrollToItem(s.optInt("bi") + 1) }
                            }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = if (sections.length() > 0) 2.dp else statusTop + 56.dp + 12.dp,
                bottom = navBottom + 96.dp,
                start = 14.dp, end = 14.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            /* 章头卡 */
            item(key = "head") {
                OpsCard {
                    OpsChip(doc.optString("domainTitle"))
                    Spacer(Modifier.height(6.dp))
                    Text(doc.optString("title"), color = colors.textMain,
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "第 ${doc.optString("id").takeLast(2).toIntOrNull() ?: ""} 章 · " +
                            "约 ${(doc.optInt("words") / 10000.0).formatWords()} 万字" +
                            (if (sections.length() > 0) " · ${sections.length()} 小节" else ""),
                        color = colors.textMute, fontSize = 11.sp
                    )
                }
            }
            /* 正文块（每块一 item：h2 跳转 bi+1 定位） */
            for (bi in 0 until blocks.length()) {
                val b = blocks.optJSONObject(bi) ?: continue
                item(key = "b$bi") { TbBlock(b) }
            }
            /* 上一章 / 下一章 */
            item(key = "nav") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val prev = doc.optString("prev")
                    val next = doc.optString("next")
                    if (prev.isNotEmpty()) {
                        OpsCard(Modifier.weight(1f), onClick = { onGo("tbread", prev) }) {
                            Text("← 上一章", color = colors.textMute, fontSize = 10.5.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(tbNavTitle(ctx, prev), color = colors.textMain, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    } else Spacer(Modifier.weight(1f))
                    if (next.isNotEmpty()) {
                        OpsCard(Modifier.weight(1f), onClick = { onGo("tbread", next) }) {
                            Text("下一章 →", color = colors.textMute, fontSize = 10.5.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(tbNavTitle(ctx, next), color = colors.textMain, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    } else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 章号格式化（0.0 一步到位避免 "0.0万"）。 */
private fun Double.formatWords(): String =
    if (this >= 10) this.toInt().toString() else String.format("%.1f", this)

/** 上/下章标题（读章 JSON 首行 title；缺失即返回 id）。 */
private fun tbNavTitle(ctx: Context, id: String): String =
    loadTbChapter(ctx, id)?.optString("title").takeUnless { it.isNullOrEmpty() } ?: id
