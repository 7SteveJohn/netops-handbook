package com.netops.handbook.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.AppShellState
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.opsColors
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * M4-2 抽屉 Compose 版（2026-11-16）：NativeDrawerView(292 行 View)退役位。
 *
 * 结构 1:1 平移（2026-10-27 原版）：scrim(黑110α 点关) + 300dp 左面板
 * (实色 cardBg——直铺 cardBg=214α 会与页面叠出「两层字」(2026-10-28 用户报)，
 * 此处强制 opsColors(dark, false) 非 direct 板)/右圆角 22dp/顶栏下沿起
 * (statusTop+84dp，与页面让位同口径) + 品牌区 + 页面段 + 技能目录(phases)
 * + 查询工具(faults/dict/refs 计数)。
 *
 * 拖拽语义对齐 View 版 onInterceptTouchEvent 链：横向拖 > touchSlop 跟手
 * 平移，松手「位移过 40% 宽」或「向左甩速 >500dp/s」判收，否则 150ms 弹回；
 * 开合 200ms。Compose 1.9 的 anchoredDraggable 构造签名漂移大，两态横移
 * 手写 Animatable+VelocityTracker 更稳。
 *
 * 导航统一走 onGo(=nativeGo，标题/返回栈不旁路)；点击行卡先收抽屉、动画
 * 走完再跳（View 版 postDelayed 220 同语义）。
 */
@Composable
fun DrawerOverlay(
    data: () -> JSONObject?,
    dark: Boolean,
    onGo: (String, String) -> Unit,
    onClosed: () -> Unit,
) {
    if (!AppShellState.drawerOpen) return

    val view = LocalView.current
    val density = LocalDensity.current
    /* 抽屉=导航面板必须实色（View 版 palette(context, dark, false) 同口径）。 */
    val colors = remember(dark) { opsColors(dark, false) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val panelW = with(density) { 300.dp.toPx() }
    val touchSlop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
    val scope = rememberCoroutineScope()

    /* 面板 x 偏移唯一源：0=全开，-panelW=全关。State 直驱（拖拽在 restricted
       scope 内写 State 合法；动画走 animate() 帧回调写回 State——Animatable
       的 snapTo 是 suspend 不能进 AwaitPointerEventScope）。 */
    var off by remember { mutableFloatStateOf(-panelW) }
    var routeSeen by remember { mutableStateOf(AppShellState.pageRoute) }
    var closing by remember { mutableStateOf(false) }

    fun finishClose() {
        AppShellState.drawerOpen = false
        onClosed()
    }
    fun animateOff(from: Float, to: Float, dur: Int, after: (() -> Unit)? = null) {
        scope.launch {
            androidx.compose.animation.core.animate(
                initialValue = 0f, targetValue = 1f,
                animationSpec = tween(dur),
            ) { v, _ -> off = from + (to - from) * v }
            after?.invoke()
        }
    }
    fun animateClose(after: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        animateOff(off, -panelW, 200) {
            after?.invoke()
            finishClose()
        }
    }

    /* 入场：挂载即从全关滑到全开（View 版 open() 200ms 同口径）。 */
    LaunchedEffect(Unit) {
        animateOff(-panelW, 0f, 200)
    }
    /* 路由切换强制收（View 版 hideNativePagesExcept 的抽屉收起等效）：只认
       「组合期间 route 真的变化」，避免入场动画被首帧 effect 掐掉。 */
    LaunchedEffect(AppShellState.pageRoute) {
        if (AppShellState.pageRoute != routeSeen) {
            routeSeen = AppShellState.pageRoute
            if (AppShellState.drawerOpen) {
                animateOff(off, -panelW, 120)
                finishClose()
            }
        }
    }
    BackHandler { animateClose() }

    Box(Modifier.fillMaxSize()) {
        /* scrim：黑 110α，淡入随面板位移，点击关。 */
        val scrimAlpha = (1f - abs(off) / panelW).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .background(Color(0x6E000000))
                .pointerInput(Unit) {
                    detectTapGestures { animateClose() }
                }
        )
        /* 面板：实色 cardBg + 右圆角 22dp + 顶栏下沿起 + 横向跟手拖拽关闭。 */
        Box(
            Modifier
                .width(300.dp)
                .fillMaxHeight()
                .offset { IntOffset(off.roundToInt(), 0) }
                .background(colors.cardBg, RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp))
                .padding(top = statusTop + 84.dp)
                .pointerInput(Unit) {
                    var tracker: VelocityTracker? = null
                    while (true) {
                        /* 0=无手势 1=判收 2=弹回——restricted scope 外处理动画。 */
                        var verdict = 0
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var draggingNow = false
                            val downX = down.position.x
                            val downY = down.position.y
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pt: androidx.compose.ui.input.pointer.PointerInputChange =
                                    ev.changes.firstOrNull { it.id == down.id }
                                        ?: ev.changes.first()
                                if (!pt.pressed) {
                                    if (draggingNow) {
                                        /* 松手判定：位移过 40% 宽，或向左甩速 >500dp/s。 */
                                        val v = tracker?.calculateVelocity()?.x ?: 0f
                                        val fling = with(density) { -500.dp.toPx() }
                                        verdict = if (off < -panelW * 0.4f || v < fling) 1 else 2
                                    }
                                    break
                                }
                                val dx = pt.position.x - downX
                                val dy = pt.position.y - downY
                                if (!draggingNow && dx < -touchSlop && abs(dx) > abs(dy)) {
                                    draggingNow = true
                                    if (tracker == null) tracker = VelocityTracker()
                                    tracker.resetTracking()
                                }
                                if (draggingNow) {
                                    tracker!!.addPosition(pt.uptimeMillis, pt.position)
                                    off = (off + pt.positionChange().x)
                                        .coerceIn(-panelW, 0f)
                                    pt.consume()
                                }
                            }
                        }
                        if (verdict == 1) animateClose()
                        else if (verdict == 2) animateOff(off, 0f, 150)
                    }
                }
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 24.dp)
            ) {
                Text("NetOps 2.0", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = colors.textMain)
                Text(
                    "技能目录", fontSize = 11.5.sp, fontWeight = FontWeight.Normal,
                    color = colors.textMute,
                    modifier = Modifier.padding(top = 3.dp, bottom = 10.dp)
                )
                DrawerSection(colors, "页面")
                DrawerItem(colors, "学习手册", "${count(data(), "phases")} 阶段 · 89 模块") {
                    animateClose { onGo("learn", "") }
                }
                DrawerItem(colors, "我的", "进度 · 收藏 · 设置") {
                    animateClose { onGo("me", "") }
                }
                DrawerSection(colors, "技能目录")
                val phases = data()?.optJSONArray("phases")
                if (phases != null) for (i in 0 until phases.length()) {
                    val p = phases.optJSONObject(i) ?: continue
                    val n = p.optJSONArray("mods")?.length() ?: 0
                    DrawerItem(colors, p.optString("title"), "$n 模块") {
                        animateClose { onGo("phase", p.optString("id")) }
                    }
                }
                DrawerSection(colors, "查询工具")
                DrawerItem(colors, "排障字典", "${count(data(), "faults")} 个案例") {
                    animateClose { onGo("fault", "") }
                }
                DrawerItem(colors, "命令字典", "${dictRows(data())} 条对照") {
                    animateClose { onGo("dict", "") }
                }
                DrawerItem(colors, "速查表", "${count(data(), "refs")} 张") {
                    animateClose { onGo("refs", "") }
                }
            }
        }
    }
}

private fun count(root: JSONObject?, key: String): Int =
    root?.optJSONArray(key)?.length() ?: 0

private fun dictRows(root: JSONObject?): Int =
    root?.optJSONObject("dict")?.optJSONArray("rows")?.length() ?: 0

@Composable
private fun DrawerSection(colors: OpsColors, title: String) {
    Text(
        title, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = colors.textMute,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
    )
}

@Composable
private fun DrawerItem(colors: OpsColors, title: String, meta: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.chipBgOff)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.textMain)
        Text(meta, fontSize = 11.sp, fontWeight = FontWeight.Normal, color = colors.textMute, modifier = Modifier.padding(top = 2.dp))
    }
}
