package com.netops.handbook.ui.pages

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.PageData
import com.netops.handbook.ui.glassBadgeSurface
import com.netops.handbook.ui.glassChipSurface
import com.netops.handbook.ui.glassClickable
import com.netops.handbook.ui.glassSpecularBorder
import com.netops.handbook.ui.isOpsLandscape
import com.netops.handbook.ui.rememberOpsPagePadding
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模拟测验页（2026-11-14 M3-2② / 2026-11-18 液态玻璃胶囊选项与判分条 + 横屏左右分栏刷题适配）。
 *
 * 形态 = 点选项即判分（对=翡绿玻璃、错=绯红玻璃并标出正确项）+ 解析展开 + 下一题平滑跳转；
 * 错题本：判分写 netops_native/quizWrong（qKey=FNV-1a 32bit→base36，32-boot.js 1882 同款）；
 * 横屏优化：左侧题干与解析、右侧选项胶囊与操作按钮，横屏一屏看全免上下反复滚动。
 */
@Composable
fun QuizPage(dataSupplier: () -> org.json.JSONObject?, dark: Boolean) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val ver = PageData.version
    val quiz = remember(ver) {
        val arr = dataSupplier()?.optJSONArray("quiz") ?: JSONArray()
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        out
    }
    var wrongOnly by remember { mutableStateOf(false) }
    var wrongTick by remember { mutableStateOf(0) }
    var sessionResetKey by remember { mutableStateOf(0) }
    /* 会话内答题记录上提至页面级，滑出屏幕再滑回不丢判分态，并实时驱动进度芯片 */
    val pickedMap = remember(ver, sessionResetKey) { mutableStateMapOf<String, Int>() }

    fun wrongMap(): JSONObject = try {
        JSONObject(
            ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
                .getString("quizWrong", "{}") ?: "{}"
        )
    } catch (_: Throwable) {
        JSONObject()
    }

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
        } catch (_: Throwable) {
        }
    }

    val wrongMapNow = remember(wrongTick, ver) { wrongMap() }
    val wrongN = wrongMapNow.length()
    val shown = remember(quiz, wrongOnly, wrongTick) {
        if (wrongOnly) {
            quiz.filter { wrongMapNow.has(qKey(it.optString("q"))) }
        } else {
            quiz
        }
    }
    val answeredInShown = shown.count { pickedMap.containsKey(qKey(it.optString("q"))) }

    val pagePad = rememberOpsPagePadding(hasDock = true, hasTopBar = true)
    val land = isOpsLandscape()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePad,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "__quiz_head__") {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                OpsCard(
                    modifier = if (land) Modifier.widthIn(max = 880.dp) else Modifier
                ) {
                    if (land) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "模拟测验",
                                        color = colors.textMain,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "进度 $answeredInShown / ${shown.size}",
                                        color = colors.accent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .glassBadgeSurface(colors, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "共 ${quiz.size} 题 · 点选项即判分，横屏左右对照一屏直刷",
                                    color = colors.textMute,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            QuizModeChipsRow(
                                wrongOnly = wrongOnly,
                                wrongN = wrongN,
                                colors = colors,
                                onSelectAll = {
                                    if (wrongOnly) {
                                        wrongOnly = false
                                        wrongTick++
                                    }
                                },
                                onSelectWrong = {
                                    if (wrongN == 0) {
                                        android.widget.Toast.makeText(
                                            ctx, "错题本是空的，先做一组题",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        wrongOnly = true
                                    }
                                },
                                onReset = {
                                    sessionResetKey++
                                    android.widget.Toast.makeText(
                                        ctx, "测验已重置",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "模拟测验",
                                color = colors.textMain,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "进度 $answeredInShown / ${shown.size}",
                                color = colors.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .glassBadgeSurface(colors, RoundedCornerShape(9.dp))
                                    .padding(horizontal = 8.5.dp, vertical = 3.5.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "共 ${quiz.size} 题 · 点选项即判分，不做记忆、只做查漏",
                            color = colors.textMute,
                            fontSize = 12.5.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 2.dp)
                        ) {
                            QuizModeChipsRow(
                                wrongOnly = wrongOnly,
                                wrongN = wrongN,
                                colors = colors,
                                onSelectAll = {
                                    if (wrongOnly) {
                                        wrongOnly = false
                                        wrongTick++
                                    }
                                },
                                onSelectWrong = {
                                    if (wrongN == 0) {
                                        android.widget.Toast.makeText(
                                            ctx, "错题本是空的，先做一组题",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        wrongOnly = true
                                    }
                                },
                                onReset = {
                                    sessionResetKey++
                                    android.widget.Toast.makeText(
                                        ctx, "测验已重置",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            item(key = "__quiz_empty__") {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    OpsCard(
                        modifier = if (land) Modifier.widthIn(max = 880.dp) else Modifier
                    ) {
                        Text(
                            "太棒了，错题本已全部清空！",
                            color = colors.textMain,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "所有错题均已正确解答，可切换到全部题目继续巩固。",
                            color = colors.textMute,
                            fontSize = 12.5.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "查看全部题目",
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .glassChipSurface(colors, active = true, shape = RoundedCornerShape(10.dp))
                                .glassClickable { wrongOnly = false }
                                .padding(horizontal = 14.dp, vertical = 7.5.dp)
                        )
                    }
                }
            }
        }

        itemsIndexed(shown, key = { _, q -> qKey(q.optString("q")) }) { idx, q ->
            val key = qKey(q.optString("q"))
            val picked = pickedMap[key] ?: -1
            val hasNext = idx + 1 < shown.size
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                QuizCard(
                    q = q,
                    no = idx + 1,
                    totalShown = shown.size,
                    dark = dark,
                    land = land,
                    picked = picked,
                    hasNext = hasNext,
                    onPick = { optIdx ->
                        pickedMap[key] = optIdx
                        recordJudge(q, optIdx == q.optInt("a", -1))
                    },
                    onRetry = {
                        pickedMap.remove(key)
                    },
                    onNext = {
                        if (hasNext) {
                            scope.launch {
                                listState.animateScrollToItem(idx + 2)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun QuizModeChipsRow(
    wrongOnly: Boolean,
    wrongN: Int,
    colors: OpsColors,
    onSelectAll: () -> Unit,
    onSelectWrong: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "全部题库",
            color = if (!wrongOnly) Color.White else colors.textSub,
            fontSize = 12.sp,
            fontWeight = if (!wrongOnly) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier
                .glassChipSurface(colors, active = !wrongOnly, shape = RoundedCornerShape(10.dp))
                .glassClickable(onClick = onSelectAll)
                .padding(horizontal = 11.dp, vertical = 6.dp)
        )
        Text(
            "只刷错题" + (if (wrongN > 0) " · $wrongN" else ""),
            color = if (wrongOnly) Color.White else colors.textSub,
            fontSize = 12.sp,
            fontWeight = if (wrongOnly) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier
                .glassChipSurface(colors, active = wrongOnly, shape = RoundedCornerShape(10.dp))
                .glassClickable(onClick = onSelectWrong)
                .padding(horizontal = 11.dp, vertical = 6.dp)
        )
        Text(
            "重新测验",
            color = colors.textSub,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .glassChipSurface(colors, active = false, shape = RoundedCornerShape(10.dp))
                .glassClickable(onClick = onReset)
                .padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun QuizCard(
    q: JSONObject,
    no: Int,
    totalShown: Int,
    dark: Boolean,
    land: Boolean,
    picked: Int,
    hasNext: Boolean,
    onPick: (Int) -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
) {
    val colors = LocalOpsColors.current
    val opts = q.optJSONArray("o")
    val answer = q.optInt("a", -1)
    val modLabel = q.optString("m")

    OpsCard(
        modifier = if (land) Modifier.widthIn(max = 880.dp) else Modifier
    ) {
        if (land && opts != null) {
            /* 横屏左右分栏：左侧题干与判分解析，右侧选项胶囊与重做/下一题操作区 */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(0.45f)) {
                    QuizStemHeader(no, totalShown, modLabel, colors)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        q.optString("q"),
                        color = colors.textMain,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 20.sp
                    )
                    if (picked >= 0) {
                        Spacer(Modifier.height(10.dp))
                        QuizJudgePanel(
                            isCorrect = picked == answer,
                            answer = answer,
                            explanation = q.optString("e"),
                            dark = dark,
                            colors = colors,
                            hasNext = hasNext,
                            onRetry = onRetry,
                            onNext = onNext
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(0.55f),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    for (i in 0 until opts.length()) {
                        QuizOptionCapsule(
                            index = i,
                            text = opts.optString(i),
                            picked = picked,
                            answer = answer,
                            dark = dark,
                            compact = true,
                            colors = colors,
                            onClick = { onPick(i) }
                        )
                    }
                }
            }
        } else {
            QuizStemHeader(no, totalShown, modLabel, colors)
            Spacer(Modifier.height(8.dp))
            Text(
                q.optString("q"),
                color = colors.textMain,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 19.5.sp
            )
            if (opts != null) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until opts.length()) {
                        QuizOptionCapsule(
                            index = i,
                            text = opts.optString(i),
                            picked = picked,
                            answer = answer,
                            dark = dark,
                            compact = false,
                            colors = colors,
                            onClick = { onPick(i) }
                        )
                    }
                }
                if (picked >= 0) {
                    Spacer(Modifier.height(10.dp))
                    QuizJudgePanel(
                        isCorrect = picked == answer,
                        answer = answer,
                        explanation = q.optString("e"),
                        dark = dark,
                        colors = colors,
                        hasNext = hasNext,
                        onRetry = onRetry,
                        onNext = onNext
                    )
                }
            }
        }
    }
}

@Composable
private fun QuizStemHeader(
    no: Int,
    totalShown: Int,
    modLabel: String,
    colors: OpsColors,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "#$no / $totalShown",
            color = colors.accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .glassBadgeSurface(colors, RoundedCornerShape(8.dp))
                .padding(horizontal = 7.5.dp, vertical = 3.dp)
        )
        if (modLabel.isNotEmpty()) {
            Text(
                modLabel,
                color = colors.textSub,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .glassChipSurface(colors, active = false, shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.5.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun QuizOptionCapsule(
    index: Int,
    text: String,
    picked: Int,
    answer: Int,
    dark: Boolean,
    compact: Boolean,
    colors: OpsColors,
    onClick: () -> Unit,
) {
    val judged = picked >= 0
    val right = judged && index == answer
    val wrongPick = picked == index && index != answer
    val shape = RoundedCornerShape(12.dp)

    val rowMod = when {
        right -> Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) Color(0x3622C55E) else Color(0x2216A34A))
            .glassSpecularBorder(shape = shape, width = 0.85.dp, intensity = 1.05f)
        wrongPick -> Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) Color(0x36EF4444) else Color(0x20DC2626))
            .glassSpecularBorder(shape = shape, width = 0.85.dp, intensity = 1.05f)
        !judged -> Modifier
            .fillMaxWidth()
            .glassChipSurface(colors, active = false, shape = shape)
            .glassClickable(pressedScale = 0.98f, glowBoost = 0.07f, onClick = onClick)
        else -> Modifier
            .fillMaxWidth()
            .glassChipSurface(colors, active = false, shape = shape)
    }

    val fg = when {
        right -> if (dark) Color(0xFF86EFAC) else Color(0xFF15803D)
        wrongPick -> if (dark) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
        judged -> colors.textMute
        else -> colors.textMain
    }

    val letterText = when {
        right -> "✓"
        wrongPick -> "✕"
        else -> "${'A' + index}"
    }

    Row(
        modifier = rowMod.padding(
            horizontal = 11.dp,
            vertical = if (compact) 8.5.dp else 10.dp
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            letterText,
            color = if (right || wrongPick) fg else colors.accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .then(
                    if (right || wrongPick) {
                        Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(fg.copy(alpha = 0.16f))
                    } else {
                        Modifier.glassBadgeSurface(colors, RoundedCornerShape(7.dp))
                    }
                )
                .padding(horizontal = 6.5.dp, vertical = 2.5.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = fg,
            fontSize = 13.5.sp,
            fontWeight = if (right || wrongPick) FontWeight.SemiBold else FontWeight.Normal,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuizJudgePanel(
    isCorrect: Boolean,
    answer: Int,
    explanation: String,
    dark: Boolean,
    colors: OpsColors,
    hasNext: Boolean,
    onRetry: () -> Unit,
    onNext: () -> Unit,
) {
    val panelShape = RoundedCornerShape(12.dp)
    val statusColor = if (isCorrect) {
        if (dark) Color(0xFF86EFAC) else Color(0xFF15803D)
    } else {
        if (dark) Color(0xFFFCA5A5) else Color(0xFFDC2626)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(panelShape)
            .background(if (isCorrect) colors.noteOk else colors.noteDanger)
            .glassSpecularBorder(shape = panelShape, width = 0.75.dp, intensity = 0.9f)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isCorrect) "✓ 回答正确" else "✗ 回答错误（正确答案：${'A' + answer}）",
                color = statusColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isCorrect) {
                    Text(
                        "重做此题",
                        color = colors.accent,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .glassChipSurface(colors, active = false, shape = RoundedCornerShape(8.dp))
                            .glassClickable(onClick = onRetry)
                            .padding(horizontal = 9.dp, vertical = 4.5.dp)
                    )
                }
                if (hasNext) {
                    Text(
                        "下一题 →",
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .glassChipSurface(colors, active = true, shape = RoundedCornerShape(8.dp))
                            .glassClickable(onClick = onNext)
                            .padding(horizontal = 9.5.dp, vertical = 4.5.dp)
                    )
                }
            }
        }
        if (explanation.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "解析：$explanation",
                color = colors.textSub,
                fontSize = 12.sp,
                lineHeight = 16.5.sp
            )
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
