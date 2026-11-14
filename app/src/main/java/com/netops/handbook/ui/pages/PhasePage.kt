package com.netops.handbook.ui.pages

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsBlocks
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsChevron
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.PageData
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阶段详情页（2026-11-14 M3-2⑦）：NativePhaseView 的 Compose 版。
 *
 * 形态 = 头卡（阶段标题 19sp + 描述 + 进度条 doneN/total）+ 模块卡（head=芯片
 * id+标题+meta pshort n/tc 拓扑实验后缀+箭头，body=OpsBlocks blocks + 掌握/收藏
 * 操作行）；focus 深链 = 自动展开目标模块卡并滚动定位（lazyListState.
 * animateScrollToItem 退役 smoothScrollTo）。
 * 状态：done/fav = SP netops_native（与 doclist/phase View 版同源）；滚动记忆
 * （scroll_<pid> px）暂缺，M3 收口统一 rememberSaveable。
 */
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
        mutableStateOf<Set<String>>(HashSet(ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
            .getString("done", "")!!.split(",").filter { it.isNotEmpty() }))
    }
    var fav by remember(ver) {
        mutableStateOf<Set<String>>(HashSet(ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE)
            .getString("fav", "")!!.split(",").filter { it.isNotEmpty() }))
    }
    var openIds by remember(ver, pid) { mutableStateOf(if (focusId != null) setOf(focusId) else setOf<String>()) }
    var stateTick by remember { mutableStateOf(0) }

    fun toggleDone(id: String) {
        val on = !done.contains(id)
        done = if (on) done + id else done - id
        ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE).edit()
            .putString("done", done.joinToString(",")).apply()
        onState(id, "done", on)
        stateTick++
    }
    fun toggleFav(id: String) {
        val on = !fav.contains(id)
        fav = if (on) fav + id else fav - id
        ctx.getSharedPreferences("netops_native", Context.MODE_PRIVATE).edit()
            .putString("fav", fav.joinToString(",")).apply()
        onState(id, "fav", on)
        stateTick++
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
            val idx = (0 until mods.length()).firstOrNull {
                mods.optJSONObject(it)?.optString("id") == focusId
            }
            if (idx != null) listState.animateScrollToItem(idx + 1)   // +1 = 头卡 item
        }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = statusTop + 84.dp, bottom = navBottom + 84.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            /* 头卡 */
            item {
                OpsCard {
                    Text(phase?.optString("title") ?: "", color = colors.textMain,
                        fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    val desc = phase?.optString("desc") ?: ""
                    if (desc.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(desc, color = colors.textMute, fontSize = 12.5.sp, lineHeight = 15.5.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).height(6.dp)
                                .clip(RoundedCornerShape(3.dp)).background(colors.hairline)
                        ) {
                            if (frac > 0f) Box(
                                Modifier.weight(frac).height(6.dp).background(colors.accent)
                            )
                            if (frac < 1f) Box(
                                Modifier.weight(1f - frac).height(6.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("$doneN / ${mods.length()}", color = colors.textSub,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            /* 模块卡 */
            for (i in 0 until mods.length()) {
                val m = mods.optJSONObject(i) ?: continue
                item(key = m.optString("id")) {
                    ModuleCard(m, done, fav, openIds.contains(m.optString("id")),
                        colors, ::toggleDone, ::toggleFav,
                        toggleOpen = { mid ->
                            openIds = if (openIds.contains(mid)) openIds - mid else openIds + mid
                        })
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(
    m: JSONObject, done: Set<String>, fav: Set<String>,
    open: Boolean, colors: com.netops.handbook.ui.OpsColors,
    toggleDone: (String) -> Unit, toggleFav: (String) -> Unit,
    toggleOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val id = m.optString("id")
    val isDone = done.contains(id)
    val isFav = fav.contains(id)
    var meta = "${m.optString("pshort")} ${m.optInt("n")}/${m.optInt("tc")}"
    if (m.optBoolean("dg")) meta += " · 拓扑"
    if (m.optBoolean("lab")) meta += " · 实验"
    if (isDone) meta += " · ✓ 已掌握"
    if (isFav) meta += " · ★ 收藏"

    OpsCard {
        Row(
            Modifier.fillMaxWidth().clickable { toggleOpen(id) },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                id, color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.chipBgOn)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(m.optString("t"), color = colors.textMain, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(meta, color = if (isDone) colors.accent else colors.textMute, fontSize = 11.sp)
            }
            OpsChevron(colors, rotation = if (open) -90f else 90f)
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            OpsBlocks(m.optJSONArray("blocks") ?: JSONArray())
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(
                    if (isDone) "✓ 已掌握" else "标记已掌握",
                    color = if (isDone) Color.White else colors.accent,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDone) colors.accent else colors.chipBgOff)
                        .padding(vertical = 9.dp)
                        .clickable { toggleDone(id) }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isFav) "★ 已收藏" else "☆ 收藏",
                    color = if (isFav) colors.textMain else colors.textSub,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.chipBgOff)
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                        .clickable { toggleFav(id) }
                )
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") ctx
}

