package com.netops.handbook.lens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 24×24 stroke 图标（web-src/html/sprite.svg verbatim path data）。 */
private data class TabIcon(val paths: List<Pair<String, Float>>)   // d to strokeWidth

private val I_LAYERS = listOf(
    "m12 3 9 5-9 5-9-5 9-5Z" to 1.7f,
    "m3 13 9 5 9-5" to 1.7f,
    "m3 17.5 9 5 9-5" to 1.7f,
)
private val I_WRENCH = listOf(
    "M14.6 6.4a4.5 4.5 0 0 0 5.9 5.9l-8.3 8.3a2.4 2.4 0 0 1-3.4 0l-2.5-2.5a2.4 2.4 0 0 1 0-3.4Z" to 1.7f,
    "M14.6 6.4 18 3l3 3-3.4 3.4" to 1.7f,
)
private val I_TERMINAL = listOf(
    "M4.7 4h14.6A2.2 2.2 0 0 1 21.5 6.2v11.6a2.2 2.2 0 0 1-2.2 2.2H4.7a2.2 2.2 0 0 1-2.2-2.2V6.2A2.2 2.2 0 0 1 4.7 4Z" to 1.7f,
    "m6.5 9.5 3 2.7-3 2.7M12.5 15.4h5" to 1.7f,
)
private val I_BRIEFCASE = listOf(
    "M4.7 7h14.6A2.2 2.2 0 0 1 21.5 9.2v8.8a2.2 2.2 0 0 1-2.2 2.2H4.7a2.2 2.2 0 0 1-2.2-2.2V9.2A2.2 2.2 0 0 1 4.7 7Z" to 1.7f,
    "M9 7V5.2A1.7 1.7 0 0 1 10.7 3.5h2.6A1.7 1.7 0 0 1 15 5.2V7" to 1.7f,
    "M2.5 12.5h19" to 1.7f,
)
private val I_USER = listOf(
    "M12 4a4 4 0 1 1 0 8 4 4 0 0 1 0-8Z" to 1.7f,
    "M4.5 20.5a7.5 7.5 0 0 1 15 0" to 1.7f,
)
private val TAB_ICONS = listOf(I_LAYERS, I_WRENCH, I_TERMINAL, I_BRIEFCASE, I_USER)
private val TAB_LABELS = listOf("学习", "排障", "字典", "面试", "我的")

private fun parsedPath(d: String): Path =
    PathParser().parsePathString(d).toPath()

/**
 * Compose 版 dock（KernelSU FloatingBottomBar 同构）。
 * 玻璃 = drawGlassBackdrop(backdrop)，uContent=pages 层实时内容，GPU 零回读；
 * tabs/药丸 = Compose 绘制。点击/按压/触觉全在这里，切页经 NetOpsShell.onCommit 回 Java。
 */
@Composable
fun NetOpsDock(backdrop: GraphicsLayer, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val density = LocalDensity.current
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var pressIndex by remember { mutableIntStateOf(-1) }
    val pill = remember { Animatable(0f) }

    LaunchedEffect(NetOpsShell.selected) {
        pill.animateTo(NetOpsShell.selected.toFloat(), spring(1f, 300f, 0.5f))
    }

    val dark = NetOpsShell.dark
    val cellW = if (sizePx.width > 0) sizePx.width / 5f else 0f
    val selColor = Color(0xFF2563EB)
    val idleColor = if (dark) Color(0xFF9A9AA2) else Color(0xFF6B7280)

    val barRect = Rect(0f, 0f, sizePx.width.toFloat(), sizePx.height.toFloat())
    val pressK = if (pressIndex >= 0) 1f else 0f
    val pillCx = (pill.value + 0.5f) * cellW
    val pillW = cellW * (1f + 0.2f * pressK)
    val pillH = (if (sizePx.height > 0) sizePx.height.toFloat() else 0f) * (1f + 0.15f * pressK)
    val pillRect = Rect(pillCx - pillW / 2f, (sizePx.height.toFloat() - pillH) / 2f,
            pillCx + pillW / 2f, (sizePx.height.toFloat() + pillH) / 2f)

    Box(
        modifier
            .padding(horizontal = 12.dp)
            .navigationBarsPadding()
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .height(64.dp)
            .onGloballyPositioned {
                rootPos = it.positionInRoot()
                sizePx = it.size
            }
            // 栏体玻璃 → 药丸玻璃 → 内容（修饰符外→内的绘制顺序）
                        .drawGlassBackdrop(backdrop, rootPos, barRect, 32.dp, 4.dp, 24.dp, 24.dp, dark)
            .drawGlassBackdrop(backdrop, rootPos, pillRect, 24.dp, 4.dp, 10.dp, 14.dp, dark)
    ) {
        Row(Modifier.fillMaxSize()) {
            repeat(5) { i ->
                val interactions = remember { MutableInteractionSource() }
                val pressed by interactions.collectIsPressedAsState()
                if (pressed && pressIndex != i) pressIndex = i
                if (!pressed && pressIndex == i) pressIndex = -1
                val isSel = i == NetOpsShell.selected
                val tintColor = if (isSel) selColor else idleColor
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            val p = if (pressIndex == i) 1f else 0f
                            val s = 1f + 0.12f * p
                            scaleX = s; scaleY = s
                            alpha = 1f
                        }
                        .pointerInput(i) {
                            detectTapGestures(
                                onTap = {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                                    NetOpsShell.selected = i
                                    NetOpsShell.onCommit?.invoke(i)
                                },
                                onPress = { pressIndex = i },
                            )
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    androidx.compose.foundation.Canvas(
                        Modifier.size(22.dp, 22.dp),
                    ) {
                        val sw = TAB_ICONS[i]
                        sw.forEach { (d, w) ->
                            drawPath(
                                path = parsedPath(d),
                                color = tintColor,
                                style = Stroke(width = w * density.density, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                    Spacer(Modifier.size(2.dp))
                    Text(
                        TAB_LABELS[i],
                        color = tintColor,
                        fontSize = 11.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
