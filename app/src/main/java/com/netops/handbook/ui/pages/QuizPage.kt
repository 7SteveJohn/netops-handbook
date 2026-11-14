package com.netops.handbook.ui.pages

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.PageData
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模拟测验页（2026-11-14 M3-2②）：NativeQuizView 的 Compose 版。
 *
 * 形态 = 一页铺完所有题，点选项即判分（对=绿、错=红并标出正确项）+ 解析展开；
 * 错题本：判分写 netops_native/quizWrong（qKey=FNV-1a 32bit→base36，32-boot.js
 * 1882 同款），「全部 / 只刷错题」chips 过滤（M1d-2 原生化口径延续）。
 */
@Composable
fun QuizPage(dataSupplier: () -> org.json.JSONObject?, dark: Boolean) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val ver = PageData.version
    val quiz = remember(ver) {
        val arr = dataSupplier()?.optJSONArray("quiz") ?: JSONArray()
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        out
    }
    var wrongOnly by remember { mutableStateOf(false) }
    var wrongTick by remember { mutableStateOf(0) }

    fun wrongMap(): JSONObject = try {
        JSONObject(ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
            .getString("quizWrong", "{}") ?: "{}")
    } catch (e: Throwable) { JSONObject() }

    /* qKey = FNV-1a 32bit → base36（int 位型：-2128831035 = 2166136261） */
    fun qKey(text: String): String {
        var h = -2128831035
        for (ch in text) {
            h = h xor ch.code
            h *= 16777619
        }
        return java.lang.Long.toString(h.toLong() and 0xFFFFFFFFL, 36)
    }

    fun recordJudge(q: JSONObject, right: Boolean) {
        try {
            val sp = ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
            val w = wrongMap()
            val k = qKey(q.optString("q"))
            if (right) {
                w.remove(k)
            } else {
                val e = w.optJSONObject(k) ?: JSONObject()
                e.put("m", q.optString("m"))
                e.put("n", e.optInt("n", 0) + 1)
                w.put(k, e)
            }
            sp.edit().putString("quizWrong", w.toString()).apply()
            wrongTick++
        } catch (_: Throwable) { }
    }

    val wrongMapNow = wrongMap()
    val wrongN = wrongMapNow.length()
    val shown = if (wrongOnly) {
        quiz.filter { wrongMapNow.has(qKey(it.optString("q"))) }
    } else quiz

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = statusTop + 84.dp, bottom = navBottom + 84.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                OpsCard {
                    Text("模拟测验", color = colors.textMain, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "共 ${quiz.size} 题 · 点选项即判分，不做记忆、只做查漏",
                        color = colors.textMute, fontSize = 12.5.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "全部",
                            color = if (!wrongOnly) colors.accent else colors.textMute,
                            fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (!wrongOnly) colors.chipBgOn else colors.chipBgOff)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .clickable { if (wrongOnly) { wrongOnly = false; wrongTick++ } }
                        )
                        Text(
                            "只刷错题" + (if (wrongN > 0) " · $wrongN" else ""),
                            color = if (wrongOnly) colors.accent else colors.textMute,
                            fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (wrongOnly) colors.chipBgOn else colors.chipBgOff)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .clickable {
                                    if (wrongN == 0) {
                                        android.widget.Toast.makeText(
                                            ctx, "错题本是空的，先做一组题",
                                            android.widget.Toast.LENGTH_SHORT).show()
                                    } else wrongOnly = true
                                }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(shown, key = { _, q -> qKey(q.optString("q")) }) { idx, q ->
                QuizCard(q, idx + 1, dark, ::recordJudge)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun QuizCard(q: JSONObject, no: Int, dark: Boolean, judge: (JSONObject, Boolean) -> Unit) {
    val colors = LocalOpsColors.current
    val opts = q.optJSONArray("o")
    val answer = q.optInt("a", -1)
    var picked by remember { mutableStateOf(-1) }   // 判分状态（点选即锁）
    OpsCard {
        Text(
            "$no. ${q.optString("q")}", color = colors.textMain,
            fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.5.sp
        )
        if (opts != null) {
            Spacer(Modifier.height(8.dp))
            for (i in 0 until opts.length()) {
                val right = picked >= 0 && i == answer
                val wrongPick = picked == i && i != answer
                val bg = when {
                    right -> colors.noteOk
                    wrongPick -> colors.noteDanger
                    else -> colors.chipBgOff
                }
                val fg = when {
                    right -> if (dark) Color.White else Color(0xFF15803D)
                    wrongPick -> Color(0xFFB91C1C)
                    else -> colors.textMain
                }
                Text(
                    "${'A' + i}.  ${opts.optString(i)}",
                    color = fg, fontSize = 13.5.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .clickable(enabled = picked < 0) {
                            picked = i
                            judge(q, i == answer)
                        }
                )
                Spacer(Modifier.height(8.dp))
            }
            /* 解析（判分后展开） */
            if (picked >= 0) {
                Text(
                    q.optString("e"), color = colors.textSub,
                    fontSize = 12.5.sp, lineHeight = 16.5.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.noteOk)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** Java 侧挂载入口（Composeable lambda 无法在 Java 源里书写）。 */
fun androidx.compose.ui.platform.ComposeView.setQuizContent(
    data: () -> org.json.JSONObject?, dark: () -> Boolean, direct: () -> Boolean,
) {
    setViewCompositionStrategy(
        androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    setContent {
        OpsTheme(dark(), direct()) {
            QuizPage(data, dark())
        }
    }
}
