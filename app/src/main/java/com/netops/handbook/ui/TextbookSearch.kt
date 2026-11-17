package com.netops.handbook.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 教材全文检索桥（2026-11-18，教材接入）：search.json 倒排索引
 * （gen-textbook.js 产出）的读取与查询。
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
    private val terms: JSONObject,
) {
    class Snip(val ci: Int, val bi: Int, val sec: String, val text: String)
    class Hit(val chapterId: String, val chapterTitle: String, val bi: Int, val sec: String, val text: String)

    /** 单词 → si 集；任一成分不在索引 = 该词命中失败（emptySet）。 */
    private fun tokenSis(tok: String): Set<Int> {
        val required = if (tok.matches(LATIN)) listOf(tok)
        else tbTokenize(tok).filter { it.length >= 2 }
        if (required.isEmpty()) return emptySet()
        var acc: MutableSet<Int>? = null
        for (w in required) {
            val arr = terms.optJSONArray(w) ?: return emptySet()
            val s = HashSet<Int>(arr.length())
            for (i in 0 until arr.length()) s.add(arr.optInt(i))
            acc = if (acc == null) s else acc!!.apply { retainAll(s) }
            if (acc!!.isEmpty()) return acc
        }
        return acc ?: emptySet()
    }

    /** 多词交集查询（SearchPage 分词口径：小写 + 空格切分），按书序取前 30。 */
    fun query(toks: List<String>): List<Hit> {
        if (toks.isEmpty()) return emptyList()
        var acc: Set<Int>? = null
        for (tok in toks) {
            val s = tokenSis(tok)
            acc = if (acc == null) s else acc!!.intersect(s)
        }
        val final = acc ?: return emptyList()
        if (final.isEmpty()) return emptyList()
        val out = ArrayList<Hit>(30)
        for (si in snips.indices) {
            if (si !in final) continue
            val sn = snips[si]
            val (id, title) = chapters[sn.ci]
            out.add(Hit(id, title, sn.bi, sn.sec, sn.text))
            if (out.size >= 30) break
        }
        return out
    }

    companion object {
        private val LATIN = Regex("[a-z0-9][a-z0-9._+-]*")
        private val LATIN_ANY = Regex("[a-z0-9._+-]+")
        private val CJK = Regex("[\\u4e00-\\u9fff]{2,}")

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

        /** assets 读取（search.json 缺失/损坏 = null，搜索页静默降级）。 */
        fun load(ctx: Context): TbSearchIndex? = try {
            val root = JSONObject(ctx.assets.open("textbook/search.json")
                .bufferedReader().use { it.readText() })
            val chs = ArrayList<Pair<String, String>>()
            val chArr = root.optJSONArray("ch") ?: JSONArray()
            for (i in 0 until chArr.length()) {
                val o = chArr.optJSONArray(i) ?: continue
                chs.add(o.optString(0) to o.optString(1))
            }
            val snips = ArrayList<Snip>()
            val snArr = root.optJSONArray("snips") ?: JSONArray()
            for (i in 0 until snArr.length()) {
                val o = snArr.optJSONArray(i) ?: continue
                snips.add(Snip(o.optInt(0), o.optInt(1), o.optString(2), o.optString(3)))
            }
            TbSearchIndex(chs, snips, root.optJSONObject("terms") ?: JSONObject())
        } catch (e: Throwable) {
            null
        }
    }
}
