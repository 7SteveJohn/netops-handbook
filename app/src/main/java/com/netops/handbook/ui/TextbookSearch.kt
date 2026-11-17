package com.netops.handbook.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 教材全文检索桥（2026-11-18，教材接入 & 异步紧凑索引优化）：search.json 倒排索引
 * （gen-textbook.js 产出）的后台单例装载与零装箱交集查询。
 *
 * 索引单元 = h2 小节（章首到首个 h2 前 = 单元 0，bi=0 顶）；词表 =
 * 拉丁词(≥2 位小写) + 中文 2-gram（与生成端 tokensOf 同规则）；查询 =
 * 多词取交集，单个 CJK 词内部多 bigram 也取交集（"生成树"→生成∩成树）。
 * 命中粒度 = 小节：snips 按全书顺序存放，跳转 arg = "<chapterId>@<bi>"。
 */

class TbSearchIndex private constructor(
    /** [chapterId, chapterTitle] × 50（书序）。 */
    val chapters: List<Pair<String, String>>,
    /** 倒排快照（书序 = si 序）：ci 章序 / bi 跳转块 / sec 小节标题 / text 预览。 */
    private val snips: List<Snip>,
    /** 紧凑原始整型倒排表（装载完毕后即释放 4.7MB JSONObject DOM 树）。 */
    private val terms: Map<String, IntArray>,
) {
    class Snip(val ci: Int, val bi: Int, val sec: String, val text: String)
    class Hit(val chapterId: String, val chapterTitle: String, val bi: Int, val sec: String, val text: String)

    /** 两个升序 IntArray 线性归并求交集（零 Integer 装箱）。 */
    private fun intersectSorted(a: IntArray, b: IntArray): IntArray {
        if (a.isEmpty() || b.isEmpty()) return EMPTY_INTS
        val tmp = IntArray(minOf(a.size, b.size))
        var i = 0
        var j = 0
        var k = 0
        while (i < a.size && j < b.size) {
            val va = a[i]
            val vb = b[j]
            when {
                va == vb -> {
                    tmp[k++] = va
                    i++
                    j++
                }
                va < vb -> i++
                else -> j++
            }
        }
        return if (k == tmp.size) tmp else tmp.copyOf(k)
    }

    /** 单词 → 升序 si 数组；任一成分不在索引 = 该词命中失败（EMPTY_INTS）。 */
    private fun tokenSis(tok: String): IntArray {
        val required = if (tok.matches(LATIN)) listOf(tok)
        else tbTokenize(tok).filter { it.length >= 2 }
        if (required.isEmpty()) return EMPTY_INTS
        var acc: IntArray? = null
        for (w in required) {
            val arr = terms[w] ?: return EMPTY_INTS
            acc = if (acc == null) arr else intersectSorted(acc, arr)
            if (acc.isEmpty()) return EMPTY_INTS
        }
        return acc ?: EMPTY_INTS
    }

    /** 多词交集查询（SearchPage 分词口径：小写 + 空格切分），按书序取前 30。 */
    fun query(toks: List<String>): List<Hit> {
        if (toks.isEmpty()) return emptyList()
        var acc: IntArray? = null
        for (tok in toks) {
            val s = tokenSis(tok)
            acc = if (acc == null) s else intersectSorted(acc, s)
            if (acc.isEmpty()) return emptyList()
        }
        val final = acc ?: return emptyList()
        if (final.isEmpty()) return emptyList()
        val limit = minOf(final.size, 30)
        val out = ArrayList<Hit>(limit)
        for (idx in 0 until final.size) {
            val si = final[idx]
            if (si !in snips.indices) continue
            val sn = snips[si]
            if (sn.ci !in chapters.indices) continue
            val (id, title) = chapters[sn.ci]
            out.add(Hit(id, title, sn.bi, sn.sec, sn.text))
            if (out.size >= 30) break
        }
        return out
    }

    companion object {
        private val EMPTY_INTS = IntArray(0)
        private val LATIN = Regex("[a-z0-9][a-z0-9._+-]*")
        private val LATIN_ANY = Regex("[a-z0-9._+-]+")
        private val CJK = Regex("[\\u4e00-\\u9fff]{2,}")

        @Volatile
        private var cached: TbSearchIndex? = null
        private val loadLock = Any()

        /** 与 gen-textbook.js tokensOf 同规则：拉丁词(≥2) + 中文 2-gram。 */
        fun tbTokenize(s: String): Set<String> {
            val out = HashSet<String>()
            val lower = s.lowercase()
            LATIN_ANY.findAll(lower).forEach { if (it.value.length >= 2) out.add(it.value) }
            CJK.findAll(lower).forEach { m ->
                val w = m.value
                for (i in 0..w.length - 2) out.add(w.substring(i, i + 2))
            }
            return out
        }

        /** IO 协程懒加载进程级单例，避免 4.7MB JSON 阻塞主线程或进出搜索页重复解析。 */
        suspend fun getOrLoad(ctx: Context): TbSearchIndex? {
            cached?.let { return it }
            return withContext(Dispatchers.IO) {
                load(ctx.applicationContext)
            }
        }

        /** assets 读取并拍平为原始 IntArray 倒排表（search.json 缺失/损坏 = null）。 */
        fun load(ctx: Context): TbSearchIndex? {
            cached?.let { return it }
            synchronized(loadLock) {
                cached?.let { return it }
                return try {
                    val appCtx = ctx.applicationContext ?: ctx
                    val root = JSONObject(
                        appCtx.assets.open("textbook/search.json")
                            .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    )
                    val chArr = root.optJSONArray("ch") ?: JSONArray()
                    val chs = ArrayList<Pair<String, String>>(chArr.length())
                    for (i in 0 until chArr.length()) {
                        val o = chArr.optJSONArray(i) ?: continue
                        chs.add(o.optString(0) to o.optString(1))
                    }
                    val snArr = root.optJSONArray("snips") ?: JSONArray()
                    val snips = ArrayList<Snip>(snArr.length())
                    for (i in 0 until snArr.length()) {
                        val o = snArr.optJSONArray(i) ?: continue
                        snips.add(Snip(o.optInt(0), o.optInt(1), o.optString(2), o.optString(3)))
                    }
                    val termsObj = root.optJSONObject("terms")
                    val termMap = HashMap<String, IntArray>(
                        if (termsObj != null) (termsObj.length() * 4 / 3) + 16 else 16
                    )
                    if (termsObj != null) {
                        val keys = termsObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val arr = termsObj.optJSONArray(k) ?: continue
                            val len = arr.length()
                            if (len == 0) continue
                            val ints = IntArray(len)
                            var sorted = true
                            var prev = Int.MIN_VALUE
                            for (i in 0 until len) {
                                val v = arr.optInt(i)
                                ints[i] = v
                                if (v < prev) sorted = false
                                prev = v
                            }
                            if (!sorted) ints.sort()
                            termMap[k] = ints
                        }
                    }
                    TbSearchIndex(chs, snips, termMap).also { cached = it }
                } catch (e: Throwable) {
                    null
                }
            }
        }
    }
}
