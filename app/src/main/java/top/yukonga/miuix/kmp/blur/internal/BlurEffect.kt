// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur.internal

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import top.yukonga.miuix.kmp.blur.BackdropEffectScopeImpl
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.buildRampBlendColorsEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Conversion factor from blur radius in pixels to Blur sigma. */
internal const val BLUR_RADIUS_TO_SIGMA = 0.45f

/**
 * Max kernel reach in downsampled pixels: the outermost merged tap pair tops out just below offset
 * 12.5, where bilinear sampling reaches texel 13 — so 13 exactly covers the kernel, no darkening.
 */
internal const val BLUR_KERNEL_REACH = 13

private const val WEIGHT_THRESHOLD = 0.002

/** Highest downscale exponent (downScale = 1 shl exp); covers 1/2/4/8/16. */
private const val MAX_DOWNSCALE_EXP = 4

/**
 * Pre-built Gaussian shader-cache keys, indexed `[tapCount][exp]` (exp = log2(downScale)), so
 * the per-frame rebuild path in [createBlurEffect] avoids allocating a key string per axis.
 */
private val BLUR_H_KEYS: Array<Array<String>> = Array(MAX_BLUR_TAPS + 1) { n ->
    Array(MAX_DOWNSCALE_EXP + 1) { exp -> "LMGauss${n}_H_d${1 shl exp}" }
}
private val BLUR_V_KEYS: Array<Array<String>> = Array(MAX_BLUR_TAPS + 1) { n ->
    Array(MAX_DOWNSCALE_EXP + 1) { exp -> "LMGauss${n}_V_d${1 shl exp}" }
}

/**
 * Builds the separable Blur [RenderEffect] (H then V) for an EXPLICIT [downScale] level using the
 * per-axis variances already compensated for that level's box prefilter (see [adjustedVarianceForExp]).
 * The shader cache keys carry [downScale] so the H/V passes and the cross-fade lo/hi levels each
 * build from a separate shader instance and never alias each other's uniform arrays.
 */
internal fun createBlurEffect(
    radiusX: Float,
    radiusY: Float,
    downScale: Int,
    adjustedVarianceX: Float,
    adjustedVarianceY: Float,
    size: Size,
    scope: BackdropEffectScopeImpl,
): RenderEffect? {
    if (radiusX <= 0f && radiusY <= 0f) return null

    // Texture size uses the same integer arithmetic as drawBackdropLayer
    // to match the actual recording dimensions (size includes padding).
    val texW = (size.width.toInt() / downScale).coerceAtLeast(1).toFloat()
    val texH = (size.height.toInt() / downScale).coerceAtLeast(1).toFloat()

    val rawScratch = scope.blurRawWeights
    val paramOffsets = scope.blurParamOffsets
    val paramWeights = scope.blurParamWeights

    // exp = log2(downScale); downScale is always a power of two in 1..16.
    val exp = downScale.countTrailingZeroBits()

    var effect: RenderEffect? = null

    // H / V use distinct cache keys so each pass builds from its own shader instance; the
    // _d$downScale suffix additionally isolates the cross-fade lo/hi levels from one another.
    if (radiusX > 0f) {
        val n = computeBlurParamsInto(adjustedVarianceX, rawScratch, paramOffsets, paramWeights)
        if (n > 0) {
            val shaderOffsets = scope.obtainShaderOffsetsBuffer(n)
            val shaderWeights = scope.obtainShaderWeightsBuffer(n)
            for (i in 0 until n) {
                shaderOffsets[i * 2] = paramOffsets[i]
                shaderOffsets[i * 2 + 1] = 0f
                shaderWeights[i] = paramWeights[i]
            }
            val hShader = scope.obtainRuntimeShader(
                BLUR_H_KEYS[n][exp],
                BLUR_SHADER_BY_TAP[n],
            ).apply {
                setFloatUniform("in_blurOffset", shaderOffsets)
                setFloatUniform("in_blurWeight", shaderWeights)
                setFloatUniform("in_maxCoord", texW - 0.5f, texH - 0.5f)
            }
            effect = runtimeShaderEffect(hShader, "child")
        }
    }

    if (radiusY > 0f) {
        val n = computeBlurParamsInto(adjustedVarianceY, rawScratch, paramOffsets, paramWeights)
        if (n > 0) {
            val shaderOffsets = scope.obtainShaderOffsetsBuffer(n)
            val shaderWeights = scope.obtainShaderWeightsBuffer(n)
            for (i in 0 until n) {
                shaderOffsets[i * 2] = 0f
                shaderOffsets[i * 2 + 1] = paramOffsets[i]
                shaderWeights[i] = paramWeights[i]
            }
            val vShader = scope.obtainRuntimeShader(
                BLUR_V_KEYS[n][exp],
                BLUR_SHADER_BY_TAP[n],
            ).apply {
                setFloatUniform("in_blurOffset", shaderOffsets)
                setFloatUniform("in_blurWeight", shaderWeights)
                setFloatUniform("in_maxCoord", texW - 0.5f, texH - 0.5f)
            }
            effect = effect?.chain(runtimeShaderEffect(vShader, "child"))
                ?: runtimeShaderEffect(vShader, "child")
        }
    }

    return effect
}

/**
 * Builds the separable **progressive** Blur [RenderEffect] (H then V): a variable-radius Gaussian
 * whose radius ramps from full to zero along [angleDeg], at the same adaptive [downScale] as
 * [createBlurEffect]. The gradient axis/band ([startFraction]/[endFraction] of the component's
 * projected extent) are converted here into the downscaled, padded layer's coordinate space;
 * [size] is the padded layer size, [compW]/[compH] the unpadded component in full-resolution px.
 */
internal fun createProgressiveBlurEffect(
    radiusX: Float,
    radiusY: Float,
    downScale: Int,
    adjustedVarianceX: Float,
    adjustedVarianceY: Float,
    size: Size,
    padding: Float,
    compW: Float,
    compH: Float,
    angleDeg: Float,
    startFraction: Float,
    endFraction: Float,
    curve: Float,
    scope: BackdropEffectScopeImpl,
): RenderEffect? {
    if (radiusX <= 0f && radiusY <= 0f) return null

    val texW = (size.width.toInt() / downScale).coerceAtLeast(1).toFloat()
    val texH = (size.height.toInt() / downScale).coerceAtLeast(1).toFloat()

    // Gradient axis + band in the downscaled, padded layer's coordinate space.
    val rad = angleDeg * (PI / 180.0)
    val ax = cos(rad).toFloat()
    val ay = sin(rad).toFloat()
    val projMin = (if (ax > 0f) 0f else ax * compW) + (if (ay > 0f) 0f else ay * compH)
    val projMax = (if (ax > 0f) ax * compW else 0f) + (if (ay > 0f) ay * compH else 0f)
    val span = projMax - projMin
    val projFullComp = projMin + startFraction * span
    val projZeroComp = projMin + endFraction * span
    val padProj = padding * (ax + ay)
    val projFull = (projFullComp + padProj) / downScale
    var projZero = (projZeroComp + padProj) / downScale
    // Degenerate band (start == end, or a zero-extent axis) would divide by zero in the shader.
    if (abs(projZero - projFull) < 1e-3f) projZero = projFull + 1e-3f

    // Shader uses σ = radius·0.5 + 0.5, so radius = 2·σ_ds − 1; capped at the constant loop bound.
    val maxCap = PROGRESSIVE_BLUR_MAX_TAP.toFloat()
    val maxRadiusX = (2f * sqrt(adjustedVarianceX) - 1f).coerceIn(0f, maxCap)
    val maxRadiusY = (2f * sqrt(adjustedVarianceY) - 1f).coerceIn(0f, maxCap)

    var effect: RenderEffect? = null

    val clampedCurve = curve.coerceIn(0.05f, 20f)

    if (radiusX > 0f && maxRadiusX >= 0.5f) {
        val tap = progressiveLoopTapBound(maxRadiusX)
        val hShader = scope.obtainRuntimeShader(
            progressiveLoopShaderKey(axis = 0, downScale = downScale, tap = tap),
            progressiveBlurShaderForTap(tap),
        ).apply {
            setFloatUniform("in_maxCoord", texW - 0.5f, texH - 0.5f)
            setFloatUniform("in_step", 1f, 0f)
            setFloatUniform("in_maxRadius", maxRadiusX)
            setFloatUniform("in_gradAxis", ax, ay)
            setFloatUniform("in_gradBand", projFull, projZero)
            setFloatUniform("in_curve", clampedCurve)
        }
        effect = runtimeShaderEffect(hShader, "child")
    }

    if (radiusY > 0f && maxRadiusY >= 0.5f) {
        val tap = progressiveLoopTapBound(maxRadiusY)
        val vShader = scope.obtainRuntimeShader(
            progressiveLoopShaderKey(axis = 1, downScale = downScale, tap = tap),
            progressiveBlurShaderForTap(tap),
        ).apply {
            setFloatUniform("in_maxCoord", texW - 0.5f, texH - 0.5f)
            setFloatUniform("in_step", 0f, 1f)
            setFloatUniform("in_maxRadius", maxRadiusY)
            setFloatUniform("in_gradAxis", ax, ay)
            setFloatUniform("in_gradBand", projFull, projZero)
            setFloatUniform("in_curve", clampedCurve)
        }
        effect = effect?.chain(runtimeShaderEffect(vShader, "child"))
            ?: runtimeShaderEffect(vShader, "child")
    }

    return effect
}

/**
 * Converts radii + gradient into the [progressiveStackEffect] inputs: per-level box-compensated
 * sigmas in the downscaled layer's space and the band in downscaled, padded px. Zero radii are
 * valid — the levels collapse to identity while the pre/post chains keep riding the ramp;
 * returning null instead would poison the caller's supported flag.
 *
 * At `exp == 0` the levels run at full resolution and the stack binds its clear end to the
 * native source (`nativeSharpEnd`, see [progressiveStackEffect]) — the caller skips the sharp
 * overlay entirely.
 *
 * [postBlend] is the recorded blend-only post chain, folded in as a single ramp-mixing pass
 * chained after the stack ([buildRampBlendColorsEffect]) so the stack is evaluated exactly once;
 * [postEffect] is the general branch form and at most one of the two is non-null.
 */