package com.netops.handbook.lens.glass2

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import top.yukonga.miuix.kmp.blur.RuntimeShaderCache
import top.yukonga.miuix.kmp.blur.asComposeShader
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.HighlightStyle
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * iOS 26 方向性锐利边缘镜面高光（对齐 Kyant0/AndroidLiquidGlass DefaultHighlightShaderString，Apache-2.0）：
 * 利用圆角矩形 SDF 法向梯度 `abs(dot(grad, normal))^falloff` 生成对角双峰刀锋镜面高光，
 * 并通过精确 SDF 环带抗锯齿裁剪适配 miuix 全矩形 HighlightDrawing 管线。
 */
@Immutable
data class IosDirectionalHighlightStyle(
    override val color: Color = Color.White.copy(alpha = 0.54f),
    override val blendMode: BlendMode = BlendMode.Plus,
    val angleRadians: Float = (45.0 * PI / 180.0).toFloat(),
    val falloff: Float = 1.15f,
    val shaderKey: String = "IosDirectionalHighlight",
) : HighlightStyle {

    override fun DrawScope.createShader(
        shape: Shape,
        strokeWidthPx: Float,
        highlightAlpha: Float,
        runtimeShaderCache: RuntimeShaderCache,
    ): Shader? {
        if (!isRuntimeShaderSupported()) return null
        val sizePx = size
        val maxRadius = sizePx.minDimension / 2f
        val cornerShape = shape as? CornerBasedShape
        val isLtr = layoutDirection == LayoutDirection.Ltr
        val tl: Float
        val tr: Float
        val br: Float
        val bl: Float
        if (cornerShape == null) {
            tl = maxRadius; tr = maxRadius; br = maxRadius; bl = maxRadius
        } else {
            tl = (if (isLtr) cornerShape.topStart.toPx(sizePx, this) else cornerShape.topEnd.toPx(sizePx, this))
                .fastCoerceAtMost(maxRadius)
            tr = (if (isLtr) cornerShape.topEnd.toPx(sizePx, this) else cornerShape.topStart.toPx(sizePx, this))
                .fastCoerceAtMost(maxRadius)
            br = (if (isLtr) cornerShape.bottomEnd.toPx(sizePx, this) else cornerShape.bottomStart.toPx(sizePx, this))
                .fastCoerceAtMost(maxRadius)
            bl = (if (isLtr) cornerShape.bottomStart.toPx(sizePx, this) else cornerShape.bottomEnd.toPx(sizePx, this))
                .fastCoerceAtMost(maxRadius)
        }
        val shader = runtimeShaderCache.obtainRuntimeShader(shaderKey, IOS_DIRECTIONAL_HIGHLIGHT_SHADER)
        shader.setFloatUniform("size", sizePx.width, sizePx.height)
        shader.setFloatUniform("cornerRadii", tl, tr, br, bl)
        shader.setFloatUniform("strokeWidth", strokeWidthPx)
        shader.setColorUniform("color", color.copy(alpha = 1f))
        shader.setFloatUniform("alphaMul", color.alpha * highlightAlpha)
        shader.setFloatUniform("angle", angleRadians)
        shader.setFloatUniform("falloff", falloff)
        return shader.asComposeShader()
    }
}

private const val IOS_DIRECTIONAL_HIGHLIGHT_SHADER = """
uniform float2 size;
uniform float4 cornerRadii;
uniform float strokeWidth;
layout(color) uniform half4 color;
uniform float alphaMul;
uniform float angle;
uniform float falloff;

float radiusAt(float2 centeredCoord, float4 radii) {
    if (centeredCoord.x >= 0.0) {
        if (centeredCoord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (centeredCoord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x > 0.0001 || cornerCoord.y > 0.0001) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (sd > 0.6 || sd < -strokeWidth - 3.6) {
        return half4(0.0);
    }

    float outMask = smoothstep(0.5, -0.5, sd);
    float strokeMask = smoothstep(-strokeWidth - 0.85, -strokeWidth + 0.25, sd);
    float bevelSheen = smoothstep(-strokeWidth - 3.4, -strokeWidth, sd) * 0.22;
    float rim = outMask * max(strokeMask, bevelSheen);

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);

    float a = clamp(alphaMul * intensity * rim, 0.0, 1.0);
    return half4(color.rgb * half(a), half(a));
}
"""

val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

// Mirrors HighlightStyle.kt's LIGHT_REF — keep in sync.
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f // |g_xy| > 0.1, ≈ 6° tilt

// 3° quantization step for the gravity direction: finer changes are imperceptible.
private const val GRAVITY_ANGLE_STEP_RAD = (3.0 * PI / 180.0).toFloat()

@Composable
private fun rememberQuantizedGravityAngle(): State<Float> {
    val tiltState = rememberDeviceTilt()
    return remember(tiltState) {
        derivedStateOf {
            val tilt = tiltState.value
            val gx = tilt.gravityX
            val gy = tilt.gravityY
            val gMagSq = gx * gx + gy * gy
            if (gMagSq > GRAVITY_DIR_THRESHOLD_SQ) {
                (atan2(gy, gx) / GRAVITY_ANGLE_STEP_RAD).roundToInt() * GRAVITY_ANGLE_STEP_RAD
            } else {
                (-PI / 2).toFloat()
            }
        }
    }
}

/**
 * iOS 26 45° 刀锋对角镜面高光（随重力微幅偏转 ±20%，保持左上/右下主高光锚定不乱跑）。
 * 仅在 draw 期读取 `.value`，零重组。
 */
@Composable
internal fun rememberIosDirectionalHighlight(
    width: Dp = 0.75.dp,
    color: Color = Color.White.copy(alpha = 0.56f),
    baseDegrees: Float = 45f,
    falloff: Float = 1.15f,
    shaderKey: String = "IosDirectionalHighlight",
): State<Highlight> {
    val gravityAngle = rememberQuantizedGravityAngle()
    val neutralGravity = (-PI / 2).toFloat()
    return remember(gravityAngle, width, color, baseDegrees, falloff, shaderKey) {
        derivedStateOf {
            val tiltDelta = (gravityAngle.value - neutralGravity) * 0.25f
            val rad = (baseDegrees * PI / 180.0).toFloat() + tiltDelta
            Highlight(
                width = width,
                alpha = 1f,
                style = IosDirectionalHighlightStyle(
                    color = color,
                    blendMode = BlendMode.Plus,
                    angleRadians = rad,
                    falloff = falloff,
                    shaderKey = shaderKey,
                ),
            )
        }
    }
}

@Composable
internal fun rememberGravityRotatedHighlight(
    base: Highlight,
    extraDegrees: Float,
): State<Highlight> {
    val gravityAngle = rememberQuantizedGravityAngle()
    return remember(gravityAngle, base, extraDegrees) {
        derivedStateOf {
            val baseStyle = base.style as BloomStroke
            val basePrimary = baseStyle.primaryLight
            val rad = gravityAngle.value + (extraDegrees * PI / 180.0).toFloat()
            base.copy(
                style = baseStyle.copy(
                    primaryLight = basePrimary.copy(
                        position = LightPosition(
                            x = LIGHT_REF_X + cos(rad),
                            y = LIGHT_REF_Y + sin(rad),
                            z = basePrimary.position.z,
                        ),
                    ),
                ),
            )
        }
    }
}
