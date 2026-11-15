package com.netops.handbook.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import java.util.Random

/**
 * 液态玻璃视觉原语三件套（2026-11-16 vendor 自 LiquidGlassAndroid-v2
 * GlassModifiers.kt——用户示库「还缺哪里」差距清单的 Step A；原包名零改写，
 * 仅把 MaterialTheme/LocalGlassConfig 数据源换成 OpsColors 显式参数）。
 *
 * ① glassSpecularBorder：四段渐变镜面描边（顶部受光亮→中部通透→底部环境反
 *    光，浅色底部用 border 灰线非白线）——玻璃倒角受光，消「塑料感」。
 * ② glassMicroNoise：64×64 程序噪点平铺（固定种子可复现）——消高模糊下的
 *    色彩断层（banding），并给玻璃颗粒质感。
 * ③ glassPressFeedback：按压微缩(0.965)+白色聚光 glow(0.14)——实体反馈。
 *
 * 均为纯视觉 Modifier：不依赖 Haze/不依赖模糊链，实色组件同样可用。
 */
fun Modifier.glassSpecularBorder(
    shape: androidx.compose.ui.graphics.Shape,
    width: Dp = 0.8.dp,
    intensity: Float = 1.0f,
): Modifier = composed {
    val ops = LocalOpsColors.current
    val dark = ops.dark
    val factor = intensity.coerceIn(0f, 1.6f)
    /* 2026-11-16 对齐 dock bar.kt 规范真源（菲涅尔四停：顶亮→中隐→底微反光；
       浅色底部=灰 outline 线非白线），线宽规范区间 0.6~0.85dp。 */
    val brush = Brush.verticalGradient(
        0.0f to Color.White.copy(alpha = ((if (dark) 0.36f else 0.92f) * factor).coerceIn(0f, 1f)),
        0.32f to Color.White.copy(alpha = ((if (dark) 0.08f else 0.22f) * factor).coerceIn(0f, 1f)),
        0.72f to Color.White.copy(alpha = 0.02f),
        1.0f to if (dark) {
            Color.White.copy(alpha = (0.18f * factor).coerceIn(0f, 1f))
        } else {
            Color(0xFFC4C6D0).copy(alpha = (0.38f * factor).coerceIn(0f, 1f))
        }
    )
    this.border(width = width, brush = brush, shape = shape)
}

fun Modifier.glassMicroNoise(
    alpha: Float = 0.016f,
    tileSizePx: Int = 64
): Modifier = this.drawWithCache {
    val shaderBrush = ShaderBrush(
        ImageShader(noiseBitmap(tileSizePx).asImageBitmap(), TileMode.Repeated, TileMode.Repeated)
    )

    onDrawWithContent {
        drawContent()
        drawRect(brush = shaderBrush, alpha = alpha)
    }
}

fun Modifier.glassPressFeedback(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.965f,
    specularGlowAlpha: Float = 0.14f
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val active = isPressed && enabled

    val scale by animateFloatAsState(
        targetValue = if (active) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 460f),
        label = "glass_press_scale"
    )
    val glow by animateFloatAsState(
        targetValue = if (active) specularGlowAlpha else 0f,
        animationSpec = spring(stiffness = 550f),
        label = "glass_press_glow"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithCache {
            onDrawWithContent {
                drawContent()
                if (glow > 0f) {
                    drawRect(color = Color.White.copy(alpha = glow))
                }
            }
        }
}

/** glassMicroNoise 的位图 remember 缓存辅助（同 tile 尺寸复用同一张噪点图）。 */
private val noiseCache = object : LinkedHashMap<Int, Bitmap>(4) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>): Boolean = size > 4
}

internal fun noiseBitmap(tileSizePx: Int): Bitmap =
    noiseCache.getOrPut(tileSizePx) {
        val pixels = IntArray(tileSizePx * tileSizePx)
        val rnd = Random(2026L)
        for (i in pixels.indices) {
            val lum = rnd.nextInt(256)
            pixels[i] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
        }
        Bitmap.createBitmap(pixels, tileSizePx, tileSizePx, Bitmap.Config.ARGB_8888)
    }
