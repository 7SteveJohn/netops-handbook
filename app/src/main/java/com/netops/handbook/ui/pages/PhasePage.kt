package com.netops.handbook.ui.pages

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.AppShellState
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsBlocks
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsChevron
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.PageData
import com.netops.handbook.ui.glassBadgeSurface
import com.netops.handbook.ui.glassChipSurface
import com.netops.handbook.ui.glassClickable
import com.netops.handbook.ui.rememberOpsPagePadding
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阶段详情页（2026-11-18 微组件液态玻璃 + 横屏宽视口限宽适配）：
 *
 * 形态 = 头卡（阶段标题 19sp + 描述 + 液态高光胶囊进度条 doneN/total）+ 模块卡（head =
 * 玻璃编号徽章 id + 标题 + meta + 箭头，body = OpsBlocks + 液态玻璃胶囊「已掌握/收藏」操作行）；
 * 横屏宽屏下居中限宽 `760.dp` 保持舒适阅读行距，并由 `rememberOpsPagePadding()` 自动避让左侧导轨与挖孔区。
 */
private val PhaseMaxReadWidth = 760.dp

@Composable
fun PhasePage(
    pid: String, focusId: String?, dataSupplier: () -> org.json.JSONObject?,
    dark: Boolean, onState: (String, String, Boolean) -> Unit,
) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val ver = PageData.version
    val phase = remember(ver, pid) {
        val phases = dataSupplier()?.optJSONArray("phases") ?: JSONArray()
        var hit: JSONObject? = null
        for (i in 0 until phases.length()) {
            val p = phases.optJSONObject(i) ?: continue
            if (p.optString("id") == pid) { hit = p; break }
        }
        hit
    }
    var done by remember(ver) {
        mutableStateOf<Set<String>>(
            HashSet(
                ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
                    .getString("done", "")!!.split(",").filter { it.isNotEmpty() }
            )
        )
    }
    var fav by remember(ver) {
        mutableStateOf<Set<String>>(
            HashSet(
                ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
                    .getString("fav", "")!!.split(",").filter { it.isNotEmpty() }
            )
        )
    }
    var openIds by remember(pid) {
        mutableStateOf(if (focusId != null) setOf(focusId) else setOf())
    }

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

    val mods = phase?.optJSONArray("mods") ?: JSONArray()
    var doneN = 0
    for (i in 0 until mods.length()) {
        if (done.contains(mods.optJSONObject(i)?.optString("id"))) doneN++
    }
    val frac = if (mods.length() == 0) 0f else doneN.toFloat() / mods.length()
    val listState = rememberLazyListState()

    LaunchedEffect(focusId, ver, pid) {
        if (focusId != null) {
            openIds = openIds + focusId
            val idx = (0 until mods.length()).firstOrNull {
                mods.optJSONObject(it)?.optString("id") == focusId
            }
            if (idx != null) listState.animateScrollToItem(idx + 1) // +1 = 头卡 item
        }
    }

    val pagePadding = rememberOpsPagePadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        /* 头卡 */
        item(key = "phase_head") {
            OpsCard(modifier = Modifier.widthIn(max = PhaseMaxReadWidth).fillMaxWidth()) {
                Text(
                    phase?.optString("title") ?: "",
                    color = colors.textMain,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
                val desc = phase?.optString("desc") ?: ""
                if (desc.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(desc, color = colors.textMute, fontSize = 12.5.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhaseProgressBar(
                        fraction = frac,
                        colors = colors,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "$doneN / ${mods.length()}",
                        color = colors.textSub,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        /* 模块卡 */
        for (i in 0 until mods.length()) {
            val m = mods.optJSONObject(i) ?: continue
            val mid = m.optString("id")
            item(key = mid) {
                ModuleCard(
                    m = m,
                    done = done,
                    fav = fav,
                    open = openIds.contains(mid),
                    colors = colors,
                    toggleDone = ::toggleDone,
                    toggleFav = ::toggleFav,
                    toggleOpen = { target ->
                        openIds = if (openIds.contains(target)) openIds - target else openIds + target
                    }
                )
            }
        }
    }
}

@Composable
private fun PhaseProgressBar(
    fraction: Float,
    colors: OpsColors,
    modifier: Modifier = Modifier,
) {
    val safeFrac = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val glassOn = AppShellState.glassLevel != "none"
    val trackColor = colors.hairline
    val rimColor = colors.border.copy(alpha = if (colors.dark) 0.45f else 0.60f)
    val accentColor = colors.accent
    val dark = colors.dark
    Spacer(
        modifier
            .height(6.dp)
            .drawWithCache {
                val r = size.height / 2f
                val cr = CornerRadius(r, r)
                val strokePx = 0.6.dp.toPx()
                val fillBrush = Brush.verticalGradient(
                    0.0f to accentColor.copy(alpha = if (glassOn) 0.94f else 1f),
                    1.0f to accentColor.copy(alpha = if (glassOn) 0.78f else 1f),
                )
                val sheenBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (dark) 0.26f else 0.38f),
                    0.55f to Color.Transparent,
                )
                onDrawBehind {
                    drawRoundRect(color = trackColor, cornerRadius = cr)
                    if (glassOn) {
                        drawRoundRect(color = rimColor, cornerRadius = cr, style = Stroke(width = strokePx))
                    }
                    if (safeFrac > 0.001f) {
                        val fillW = (size.width * safeFrac).coerceAtLeast(size.height).coerceAtMost(size.width)
                        val fillSize = Size(fillW, size.height)
                        drawRoundRect(brush = fillBrush, size = fillSize, cornerRadius = cr)
                        if (glassOn) {
                            drawRoundRect(brush = sheenBrush, size = fillSize, cornerRadius = cr)
                        }
                    }
                }
            }
    )
}

@Composable
private fun ModuleCard(
    m: JSONObject,
    done: Set<String>,
    fav: Set<String>,
    open: Boolean,
    colors: OpsColors,
    toggleDone: (String) -> Unit,
    toggleFav: (String) -> Unit,
    toggleOpen: (String) -> Unit,
) {
    val id = m.optString("id")
    val isDone = done.contains(id)
    val isFav = fav.contains(id)
    var meta = "${m.optString("pshort")} ${m.optInt("n")}/${m.optInt("tc")}"
    if (m.optBoolean("dg")) meta += " · 拓扑"
    if (m.optBoolean("lab")) meta += " · 实验"
    if (isDone) meta += " · ✓ 已掌握"
    if (isFav) meta += " · ★ 收藏"

    val actionShape = RoundedCornerShape(12.dp)

    OpsCard(modifier = Modifier.widthIn(max = PhaseMaxReadWidth).fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassClickable(
                    pressedScale = 0.99f,
                    glowBoost = 0.04f,
                    onClick = { toggleOpen(id) }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                id,
                color = colors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .glassBadgeSurface(colors, shape = RoundedCornerShape(9.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    m.optString("t"),
                    color = colors.textMain,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    meta,
                    color = if (isDone) colors.accent else colors.textMute,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(6.dp))
            OpsChevron(colors, rotation = if (open) -90f else 90f)
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            OpsBlocks(m.optJSONArray("blocks") ?: JSONArray())
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isDone) "✓ 已掌握" else "标记已掌握",
                    color = if (isDone) Color.White else colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .glassClickable(
                            pressedScale = 0.97f,
                            glowBoost = 0.08f,
                            onClick = { toggleDone(id) }
                        )
                        .glassChipSurface(colors, active = isDone, shape = actionShape)
                        .padding(vertical = 9.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isFav) "★ 已收藏" else "☆ 收藏",
                    color = if (isFav) Color.White else colors.textSub,
                    fontSize = 13.sp,
                    fontWeight = if (isFav) FontWeight.SemiBold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .glassClickable(
                            pressedScale = 0.97f,
                            glowBoost = 0.08f,
                            onClick = { toggleFav(id) }
                        )
                        .glassChipSurface(colors, active = isFav, shape = actionShape)
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                )
            }
        }
    }
}
