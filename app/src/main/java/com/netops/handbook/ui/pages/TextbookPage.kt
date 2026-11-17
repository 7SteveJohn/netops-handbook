package com.netops.handbook.ui.pages

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.AppShellState
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsChevron
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.TbBlock
import com.netops.handbook.ui.TbParsedBlock
import com.netops.handbook.ui.glassBadgeSurface
import com.netops.handbook.ui.glassChipSurface
import com.netops.handbook.ui.glassClickable
import com.netops.handbook.ui.isOpsLandscape
import com.netops.handbook.ui.parseTbBlock
import com.netops.handbook.ui.rememberOpsPagePadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 教材全书页（2026-11-18 微组件液态玻璃 + 书架横屏双列 + 阅读器舒适限宽与安全区适配）：
 * 书架（route "textbook"）+ 章节阅读（route "tbread"，arg = "<chapterId>" 或 "<chapterId>@<bi>" 搜索深链）。
 *
 * 性能与体验保障：
 * 1. `index.json` 解析为进程级不可变 `TbShelfData` 单例缓存（含 `titleById` O(1) 字典），
 *    章尾「上一章/下一章」零磁盘 I/O；
 * 2. `ch/<id>.json` 在 `Dispatchers.IO` 一次性预解析为 `TbChapterDoc` + `List<TbParsedBlock>`，
 *    配合 6 章 LRU 内存缓存与下一章静默预热，正文滚动与翻章零 UI 线程 JSON 开销；
 * 3. 书架横屏按领域双列（2 章/行）排布，阅读器居中限宽 `780.dp` 且 H2 小节快跳条升级为晶莹液态玻璃胶囊。
 */
internal val TbMaxReadWidth = 780.dp

@Immutable
private class TbChapterMeta(
    val id: String,
    val n: Int,
    val title: String,
    val sub: String,
    val words: Int,
)

@Immutable
private class TbDomainMeta(
    val id: String,
    val n: Int,
    val title: String,
    val chapters: List<TbChapterMeta>,
)

@Immutable
private class TbShelfData(
    val domainCount: Int,
    val chapterCount: Int,
    val totalWords: Int,
    val domains: List<TbDomainMeta>,
    val titleById: Map<String, String>,
)

@Immutable
private class TbSectionMeta(
    val bi: Int,
    val title: String,
)

@Immutable
private class TbChapterDoc(
    val id: String,
    val title: String,
    val domainTitle: String,
    val words: Int,
    val prevId: String,
    val nextId: String,
    val sections: List<TbSectionMeta>,
    val blocks: List<TbParsedBlock>,
)

private object TbDataRepo {
    @Volatile
    var cachedShelf: TbShelfData? = null
        private set

    private val shelfLock = Any()
    private val chapterCache = LruCache<String, TbChapterDoc>(6)

    fun loadShelfSync(ctx: Context): TbShelfData? {
        cachedShelf?.let { return it }
        synchronized(shelfLock) {
            cachedShelf?.let { return it }
            return try {
                val appCtx = ctx.applicationContext ?: ctx
                val root = JSONObject(
                    appCtx.assets.open("textbook/index.json")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                )
                val domArr = root.optJSONArray("domains") ?: JSONArray()
                val domains = ArrayList<TbDomainMeta>(domArr.length())
                val titleMap = HashMap<String, String>(64)
                var wordsSum = 0
                var chSum = 0
                for (i in 0 until domArr.length()) {
                    val d = domArr.optJSONObject(i) ?: continue
                    val chArr = d.optJSONArray("chapters") ?: JSONArray()
                    val chs = ArrayList<TbChapterMeta>(chArr.length())
                    for (k in 0 until chArr.length()) {
                        val c = chArr.optJSONObject(k) ?: continue
                        val cid = c.optString("id")
                        val ctitle = c.optString("title")
                        val cwords = c.optInt("words")
                        wordsSum += cwords
                        chSum++
                        if (cid.isNotEmpty() && ctitle.isNotEmpty()) {
                            titleMap[cid] = ctitle
                        }
                        chs.add(
                            TbChapterMeta(
                                id = cid,
                                n = c.optInt("n"),
                                title = ctitle,
                                sub = c.optString("sub"),
                                words = cwords,
                            )
                        )
                    }
                    domains.add(
                        TbDomainMeta(
                            id = d.optString("id"),
                            n = d.optInt("n"),
                            title = d.optString("title"),
                            chapters = chs,
                        )
                    )
                }
                val counts = root.optJSONObject("counts")
                TbShelfData(
                    domainCount = counts?.optInt("domains") ?: domains.size,
                    chapterCount = counts?.optInt("chapters") ?: chSum,
                    totalWords = wordsSum,
                    domains = domains,
                    titleById = titleMap,
                ).also { cachedShelf = it }
            } catch (e: Throwable) {
                null
            }
        }
    }

    suspend fun getShelf(ctx: Context): TbShelfData? {
        cachedShelf?.let { return it }
        return withContext(Dispatchers.IO) { loadShelfSync(ctx) }
    }

    fun peekChapter(id: String): TbChapterDoc? {
        if (id.isEmpty()) return null
        synchronized(chapterCache) { return chapterCache.get(id) }
    }

    suspend fun getChapter(ctx: Context, id: String): TbChapterDoc? {
        if (id.isEmpty()) return null
        peekChapter(id)?.let { return it }
        return withContext(Dispatchers.IO) {
            peekChapter(id)?.let { return@withContext it }
            try {
                val appCtx = ctx.applicationContext ?: ctx
                val root = JSONObject(
                    appCtx.assets.open("textbook/ch/$id.json")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                )
                val secArr = root.optJSONArray("sections") ?: JSONArray()
                val sections = ArrayList<TbSectionMeta>(secArr.length())
                for (i in 0 until secArr.length()) {
                    val s = secArr.optJSONObject(i) ?: continue
                    sections.add(TbSectionMeta(s.optInt("bi"), s.optString("t")))
                }
                val blkArr = root.optJSONArray("blocks") ?: JSONArray()
                val blocks = ArrayList<TbParsedBlock>(blkArr.length())
                for (i in 0 until blkArr.length()) {
                    val b = blkArr.optJSONObject(i)
                    blocks.add(if (b != null) parseTbBlock(b) else TbParsedBlock.Empty)
                }
                TbChapterDoc(
                    id = root.optString("id", id),
                    title = root.optString("title"),
                    domainTitle = root.optString("domainTitle"),
                    words = root.optInt("words"),
                    prevId = root.optString("prev"),
                    nextId = root.optString("next"),
                    sections = sections,
                    blocks = blocks,
                ).also { doc ->
                    synchronized(chapterCache) { chapterCache.put(id, doc) }
                }
            } catch (e: Throwable) {
                null
            }
        }
    }
}

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

/* ---- 书架（route "textbook"） ---- */

@Composable
fun TextbookShelf(onGo: (String, String) -> Unit) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    var shelf by remember { mutableStateOf(TbDataRepo.cachedShelf) }
    var loading by remember { mutableStateOf(shelf == null) }
    val tbLast = remember { readTbLast(ctx) }

    LaunchedEffect(Unit) {
        if (shelf == null) {
            shelf = TbDataRepo.getShelf(ctx)
            loading = false
        }
    }

    val lastTitle = shelf?.titleById?.get(tbLast)
    val pagePadding = rememberOpsPagePadding()
    val landscape = isOpsLandscape()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val data = shelf
        if (data != null) {
            item(key = "head") {
                OpsCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "运维教材全书",
                            color = colors.textMain,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${data.chapterCount} 章精编",
                            color = colors.accent,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .glassBadgeSurface(colors, shape = RoundedCornerShape(8.dp))
                                .padding(horizontal = 7.dp, vertical = 2.5.dp)
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "网络与 IT 运维教材级知识库 · ${data.domainCount} 领域" +
                            " · ${data.chapterCount} 章 · 约 ${data.totalWords / 10000} 万字 · 全文可搜",
                        color = colors.textMute,
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp
                    )
                    if (lastTitle != null) {
                        Spacer(Modifier.height(10.dp))
                        val resumeShape = RoundedCornerShape(12.dp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassClickable(
                                    pressedScale = 0.98f,
                                    glowBoost = 0.07f,
                                    onClick = { onGo("tbread", tbLast) }
                                )
                                .glassChipSurface(colors, active = false, shape = resumeShape)
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Text(
                                "上次读到",
                                color = colors.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .glassBadgeSurface(colors, shape = RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.5.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                lastTitle,
                                color = colors.textMain,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(6.dp))
                            OpsChevron(colors, rotation = 0f, sizeDp = 18)
                        }
                    }
                }
            }
            for (d in data.domains) {
                item(key = "h" + d.id) {
                    Text(
                        "${d.n}. ${d.title}",
                        color = colors.textMute,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 0.dp)
                    )
                }
                if (landscape) {
                    /* 横屏双列章节卡（每行 2 章），50 章浏览效率翻倍 */
                    for (idx in 0 until d.chapters.size step 2) {
                        val c0 = d.chapters[idx]
                        val c1 = d.chapters.getOrNull(idx + 1)
                        item(key = "${d.id}_r_${c0.id}") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(Modifier.weight(1f)) {
                                    ChapterShelfCard(c0, c0.id == tbLast, colors) {
                                        onGo("tbread", c0.id)
                                    }
                                }
                                if (c1 != null) {
                                    Box(Modifier.weight(1f)) {
                                        ChapterShelfCard(c1, c1.id == tbLast, colors) {
                                            onGo("tbread", c1.id)
                                        }
                                    }
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    for (ch in d.chapters) {
                        val id = ch.id
                        item(key = id) {
                            ChapterShelfCard(ch, id == tbLast, colors) {
                                onGo("tbread", id)
                            }
                        }
                    }
                }
            }
        } else if (!loading) {
            item {
                Text(
                    "教材数据缺失（assets/textbook/ 未生成）",
                    color = colors.textMute,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun ChapterShelfCard(
    ch: TbChapterMeta,
    isLastRead: Boolean,
    colors: OpsColors,
    onClick: () -> Unit,
) {
    OpsCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%02d".format(ch.n),
                color = colors.accent,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .glassBadgeSurface(colors, shape = RoundedCornerShape(9.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ch.title,
                    color = colors.textMain,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (ch.sub.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (isLastRead) "上次读到 · ${ch.sub}" else ch.sub,
                        color = if (isLastRead) colors.accent else colors.textMute,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            OpsChevron(colors, rotation = 0f, sizeDp = 18)
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

    var shelf by remember { mutableStateOf(TbDataRepo.cachedShelf) }
    var doc by remember(chapterId) { mutableStateOf(TbDataRepo.peekChapter(chapterId)) }
    var loading by remember(chapterId) { mutableStateOf(doc == null && chapterId.isNotEmpty()) }

    LaunchedEffect(chapterId) {
        if (chapterId.isNotEmpty()) {
            writeTbLast(ctx, chapterId)
            if (shelf == null) shelf = TbDataRepo.getShelf(ctx)
            val loaded = doc ?: TbDataRepo.getChapter(ctx, chapterId)
            doc = loaded
            loading = false
            if (loaded != null && AppShellState.pageRoute == "tbread") {
                AppShellState.title = loaded.title
                AppShellState.subtitle = loaded.domainTitle
            }
            // 静默预热相邻下一章，使翻章实现 0ms 瞬开
            if (loaded != null && loaded.nextId.isNotEmpty()) {
                TbDataRepo.getChapter(ctx, loaded.nextId)
            }
        }
    }

    val layoutDir = LocalLayoutDirection.current
    val pagePad = rememberOpsPagePadding(extraTop = 10.dp)
    val startPad = pagePad.calculateStartPadding(layoutDir)
    val endPad = pagePad.calculateEndPadding(layoutDir)
    val topPad = pagePad.calculateTopPadding()
    val bottomPad = pagePad.calculateBottomPadding()

    val curDoc = doc
    if (curDoc == null || chapterId.isEmpty()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = topPad + 2.dp, start = startPad, end = endPad),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (loading) "正在加载章节内容…" else "章节不存在或教材数据缺失",
                color = colors.textMute,
                fontSize = 13.sp,
                modifier = Modifier.widthIn(max = TbMaxReadWidth).fillMaxWidth()
            )
        }
        return
    }

    val listState = remember(chapterId) { LazyListState() }
    val scope = rememberCoroutineScope()

    /* 搜索深链：落到 h2 块（头卡占 item 0，块 bi → item bi+1），支持同章跨小节与异步就绪后定位 */
    LaunchedEffect(chapterId, jumpBi, curDoc.id) {
        if (jumpBi > 0 && jumpBi < curDoc.blocks.size) {
            listState.scrollToItem(jumpBi + 1)
        }
    }

    val prevTitle = if (curDoc.prevId.isNotEmpty()) {
        shelf?.titleById?.get(curDoc.prevId) ?: curDoc.prevId
    } else ""
    val nextTitle = if (curDoc.nextId.isNotEmpty()) {
        shelf?.titleById?.get(curDoc.nextId) ?: curDoc.nextId
    } else ""

    val sectionChipShape = RoundedCornerShape(9.dp)
    val glassLevel = AppShellState.glassLevel
    val glassOn = glassLevel != "none"
    val isUltra = glassLevel == "ultra"

    Column(Modifier.fillMaxSize()) {
        /* 章内 H2 小节快跳条（晶莹液态玻璃胶囊，自动避让紧凑横屏 48dp 顶栏、左侧导轨与挖孔区） */
        if (curDoc.sections.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topPad, bottom = 8.dp, start = startPad, end = endPad),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = TbMaxReadWidth)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in curDoc.sections.indices) {
                        val s = curDoc.sections[i]
                        val label = s.title
                        Text(
                            text = "${i + 1} ${if (label.length > 10) label.take(10) + "…" else label}",
                            color = colors.textSub,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier
                                .glassClickable(
                                    pressedScale = 0.96f,
                                    glowBoost = 0.08f,
                                    onClick = {
                                        scope.launch { listState.animateScrollToItem(s.bi + 1) }
                                    }
                                )
                                .glassChipSurface(colors, active = false, shape = sectionChipShape)
                                .padding(horizontal = 10.dp, vertical = 5.5.dp)
                        )
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(
                top = if (curDoc.sections.isNotEmpty()) 2.dp else topPad + 2.dp,
                bottom = bottomPad,
                start = startPad,
                end = endPad
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            /* 章头卡 */
            item(key = "head") {
                Column(Modifier.widthIn(max = TbMaxReadWidth).fillMaxWidth()) {
                    OpsCard {
                        Text(
                            curDoc.domainTitle,
                            color = colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .glassBadgeSurface(colors, shape = RoundedCornerShape(9.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            curDoc.title,
                            color = colors.textMain,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 23.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "第 ${curDoc.id.takeLast(2).toIntOrNull() ?: ""} 章 · " +
                                "约 ${(curDoc.words / 10000.0).formatWords()} 万字" +
                                (if (curDoc.sections.isNotEmpty()) " · ${curDoc.sections.size} 小节" else ""),
                            color = colors.textMute,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            /* 正文块（连续毛玻璃阅读页片承托，drawWithCache 零重组分配、零离屏缓冲，保持每块虚拟化与 bi+1 定位） */
            val lastBi = curDoc.blocks.lastIndex
            val borderColor = colors.border
            val dark = colors.dark
            val sliceBaseBg = when {
                // 壁纸直铺 + 极致档：长文阅读纸片维持 ~81%~82% 舒适透光底（既透壁纸流光，又保证连读数万字不花眼）
                colors.direct && isUltra -> if (dark) Color(0xCF141720) else Color(0xD1FFFFFF)
                // 无壁纸 + 极致档：微透瓷晶阅读纸片，温和承接空间极光微晕且保障长文高对比度
                !colors.direct && isUltra -> if (dark) Color(0xE6151821) else Color(0xE8FFFFFF)
                glassOn && !dark && !colors.direct -> Color(0xF5FFFFFF)
                else -> colors.cardBg
            }
            items(
                count = curDoc.blocks.size,
                key = { bi -> "b$bi" },
                contentType = { bi -> curDoc.blocks[bi]::class }
            ) { bi ->
                val isFirst = bi == 0
                val isLast = bi == lastBi
                val sliceShape = when {
                    isFirst && isLast -> RoundedCornerShape(16.dp)
                    isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    else -> RectangleShape
                }
                Column(
                    Modifier
                        .widthIn(max = TbMaxReadWidth)
                        .fillMaxWidth()
                        .clip(sliceShape)
                        .background(sliceBaseBg)
                        .drawWithCache {
                            val level = AppShellState.glassLevel
                            val glassNow = level != "none"
                            val ultraNow = level == "ultra"
                            val sw = when (level) {
                                "none" -> 1.dp.toPx()
                                "standard" -> 0.72.dp.toPx()
                                else -> 1.05.dp.toPx()
                            }
                            val r = 16.dp.toPx()
                            val cr = CornerRadius(r, r)
                            val topSheen = if (isFirst && glassNow) {
                                val topAlpha = when {
                                    dark && ultraNow -> 0.14f
                                    dark -> 0.045f
                                    ultraNow -> 0.50f
                                    else -> 0.24f
                                }
                                Brush.verticalGradient(
                                    0.0f to Color.White.copy(alpha = topAlpha),
                                    1.0f to Color.Transparent,
                                    startY = 0f,
                                    endY = (if (ultraNow) 42.dp else 30.dp).toPx()
                                        .coerceAtMost(size.height.coerceAtLeast(1f))
                                )
                            } else null
                            val rimColor = when (level) {
                                "none" -> borderColor
                                "standard" -> if (dark) Color.White.copy(alpha = 0.10f) else borderColor
                                else -> if (dark) Color.White.copy(alpha = 0.24f) else borderColor
                            }
                            val topRimHighlight = when (level) {
                                "none" -> rimColor
                                "standard" -> Color.White.copy(alpha = if (dark) 0.22f else 0.62f)
                                else -> Color.White.copy(alpha = if (dark) 0.56f else 0.96f)
                            }
                            onDrawWithContent {
                                if (topSheen != null) {
                                    drawRect(brush = topSheen)
                                }
                                drawContent()
                                when {
                                    isFirst && isLast -> drawRoundRect(
                                        color = rimColor,
                                        cornerRadius = cr,
                                        style = Stroke(width = sw),
                                    )
                                    isFirst -> {
                                        drawRoundRect(
                                            color = rimColor,
                                            topLeft = Offset(0f, 0f),
                                            size = Size(size.width, size.height + r * 2f),
                                            cornerRadius = cr,
                                            style = Stroke(width = sw),
                                        )
                                        if (glassNow && size.width > r * 2f) {
                                            drawLine(
                                                color = topRimHighlight,
                                                start = Offset(r, sw * 0.5f),
                                                end = Offset(size.width - r, sw * 0.5f),
                                                strokeWidth = sw
                                            )
                                        }
                                    }
                                    isLast -> drawRoundRect(
                                        color = rimColor,
                                        topLeft = Offset(0f, -r * 2f),
                                        size = Size(size.width, size.height + r * 2f),
                                        cornerRadius = cr,
                                        style = Stroke(width = sw),
                                    )
                                    else -> {
                                        drawLine(rimColor, Offset(0f, 0f), Offset(0f, size.height), sw)
                                        drawLine(rimColor, Offset(size.width, 0f), Offset(size.width, size.height), sw)
                                    }
                                }
                            }
                        }
                        .padding(
                            start = 14.dp,
                            end = 14.dp,
                            top = if (isFirst) 14.dp else 5.dp,
                            bottom = if (isLast) 14.dp else 5.dp,
                        )
                ) {
                    TbBlock(curDoc.blocks[bi])
                }
            }
            /* 上一章 / 下一章（O(1) 内存映射取标题，零磁盘 I/O，玻璃卡片弹性反馈） */
            item(key = "nav") {
                Column(Modifier.widthIn(max = TbMaxReadWidth).fillMaxWidth()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (curDoc.prevId.isNotEmpty()) {
                            OpsCard(
                                modifier = Modifier.weight(1f),
                                onClick = { onGo("tbread", curDoc.prevId) }
                            ) {
                                Text(
                                    "← 上一章",
                                    color = colors.accent,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .glassBadgeSurface(colors, shape = RoundedCornerShape(7.dp))
                                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    prevTitle,
                                    color = colors.textMain,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        if (curDoc.nextId.isNotEmpty()) {
                            OpsCard(
                                modifier = Modifier.weight(1f),
                                onClick = { onGo("tbread", curDoc.nextId) }
                            ) {
                                Text(
                                    "下一章 →",
                                    color = colors.accent,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .glassBadgeSurface(colors, shape = RoundedCornerShape(7.dp))
                                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    nextTitle,
                                    color = colors.textMain,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** 章号格式化（0.0 一步到位避免 "0.0万"）。 */
private fun Double.formatWords(): String =
    if (this >= 10) this.toInt().toString() else String.format("%.1f", this)
