package com.netops.handbook.lens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.vector.PathParser

private val I_MENU = listOf("M3 6h18M3 12h18M3 18h13" to 1.7f)
private val I_SEARCH = listOf(
    "M18.2 11a7.2 7.2 0 1 1-14.4 0 7.2 7.2 0 0 1 14.4 0Z" to 1.7f,
    "m20 20-3.5-3.5" to 1.7f,
)
private val I_SUN = listOf(
    "M16.2 12a4.2 4.2 0 1 1-8.4 0 4.2 4.2 0 0 1 8.4 0Z" to 1.7f,
    "M12 2v2.4M12 19.6V22M4.2 4.2l1.7 1.7M18.1 18.1l1.7 1.7M2 12h2.4M19.6 12H22M4.2 19.8l1.7-1.7M18.1 5.9l1.7-1.7" to 1.7f,
)

private fun parsedPath(d: String): Path = PathParser().parsePathString(d).toPath()

/** Compose 顶栏（原 AppBarView 同构）：全宽玻璃 + 双行标题 + 三键（menu/search/theme）。 */
@Composable
fun NetOpsTopBar(backdrop: GraphicsLayer, modifier: Modifier = Modifier) {
    val dark = NetOpsShell.dark
    val overlaid = NetOpsShell.overlaid
    val density = LocalDensity.current
    var topSize by remember { mutableStateOf(IntSize.Zero) }
    val titleColor = if (dark) Color(0xFFF5F5F7) else Color(0xFF1D1D1F)
    val subColor = if (dark) Color(0xFF8E8E93) else Color(0xFF6B7280)
    val iconColor = if (dark) Color(0xFFD8D8DC) else Color(0xFF3C3C43)

    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (overlaid || !NetOpsShell.nativeBar) 0f else 1f }
            .statusBarsPadding()
            .height(54.dp)
            .onGloballyPositioned { topSize = IntSize(it.size.width, it.size.height) }
            // 顶栏全宽方角：radius 0（原 AppBarView 同样方角）
            .drawGlassBackdrop(backdrop, Offset.Zero,
                Rect(0f, 0f, topSize.width.toFloat(), topSize.height.toFloat()),
                cornerRadius = 0.dp, blurRadius = 4.dp, lensHeight = 24.dp, lensAmount = 24.dp, dark = dark)
    ) {
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 22.dp),
        ) {
            Text(NetOpsShell.title, color = titleColor, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
            if (NetOpsShell.subtitle.isNotEmpty())
                Text(NetOpsShell.subtitle, color = subColor, fontSize = 10.5.sp)
        }
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                0 to I_MENU,   // nav（抽屉）
                1 to I_SEARCH, // 搜索
                2 to I_SUN,    // 主题
            ).forEach { (idx, icon) ->
                Box(
                    Modifier
                        .size(44.dp)
                        .pointerInput(idx) {
                            detectTapGestures(onTap = {
                                NetOpsShell.onTopButton?.invoke(idx)
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                        icon.forEach { (d, w) ->
                            drawPath(
                                path = parsedPath(d),
                                color = iconColor,
                                style = Stroke(width = w * density.density, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
            }
        }
    }
}
