// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur.internal

/** Builds a separable Blur shader with [tapCount] symmetric tap pairs (1..7). */
internal fun buildBlurShader(tapCount: Int): String {
    require(tapCount in 1..MAX_BLUR_TAPS)
    return """
    uniform shader child;
    uniform float2 in_blurOffset[$tapCount];
    uniform float in_blurWeight[$tapCount];
    uniform float2 in_maxCoord;

    half4 main(float2 xy) {
        half4 color = half4(0);
        for (int i = 0; i < $tapCount; i++) {
            float2 offset = in_blurOffset[i];
            float2 c1 = clamp(xy + offset, float2(0.5), in_maxCoord);
            float2 c2 = clamp(xy - offset, float2(0.5), in_maxCoord);
            color += (child.eval(c1) + child.eval(c2)) * half(in_blurWeight[i]);
        }
        if (color.a > 0.0039) {
            return half4(color.rgb / color.a, 1.0);
        }
        return color;
    }
"""
}

/** Maximum number of tap pairs for the Blur shader. */
internal const val MAX_BLUR_TAPS = 7

/** Pre-built per-tap-count shader sources; index 0 is unused. */
internal val BLUR_SHADER_BY_TAP: Array<String> = Array(MAX_BLUR_TAPS + 1) { i ->
    if (i == 0) "" else buildBlurShader(i)
}

/** Loop bound (± taps) for the progressive Blur shader; covers the max downscaled kernel radius. */
internal const val PROGRESSIVE_BLUR_MAX_TAP = 24

private const val PROGRESSIVE_MAX_DOWNSCALE_EXP = 4

/**
 * Builds the separable **progressive** Blur shader: each fragment derives a per-pixel radius from
 * the two-point gradient and runs a 1D Gaussian up to it (`σ = radius·0.5 + 0.5`). Adjacent taps
 * are merged into one bilinear fetch at their weight-averaged fractional offset (the per-pixel-σ
 * analogue of [buildBlurShader]'s static pair merge; child sampling is bilinear on both
 * platforms), sampled as symmetric ± pairs with the center hoisted out — halving the texture
 * fetches. The loop bound [maxTap] must be an EVEN compile-time constant (AGSL) — pick it via
 * [progressiveLoopTapBound] so a small max radius doesn't pay for [PROGRESSIVE_BLUR_MAX_TAP]
 * unrolled iterations per fragment.
 *
 * The outermost tap feathers in over its last pixel of reach (`clamp(radius − i + 1, 0, 1)`),
 * keeping the kernel — its normalization AND the merged offsets — continuous in radius: a hard
 * cutoff drops taps with weight up to `e⁻² ≈ 0.135` as the per-pixel radius crosses integer
 * offsets, which reads as equal-radius stripes along the gradient. The feather also makes the
 * kernel collapse smoothly to identity at radius 0, so the early-exit is a pure fast path for
 * the clear plateau, not a visible switch.
 *
 * With [masked], the sharp overlay's clear-end cross-fade weight (`smoothstep(clamp(3·raw′ − 2))`
 * over its own `in_maskBand`/`in_maskCurve`) is folded into the output, saving the separate
 * [PROGRESSIVE_LEVEL_MASK_SHADER] pass; fully-masked fragments (the band bbox's reach margin,
 * where the radius is at its most expensive) skip the kernel outright.
 */
internal fun buildProgressiveBlurShader(maxTap: Int, masked: Boolean = false): String {
    require(maxTap % 2 == 0) { "maxTap must be even for pair merging" }
    val maskUniforms = if (masked) {
        """
    uniform float2 in_maskBand;
    uniform float in_maskCurve;
"""
    } else {
        ""
    }
    val maskWeight = if (masked) {
        """
        float mraw = clamp((p - in_maskBand.x) / (in_maskBand.y - in_maskBand.x), 0.0, 1.0);
        mraw = pow(mraw * mraw * (3.0 - 2.0 * mraw), in_maskCurve);
        float mw = clamp(mraw * 3.0 - 2.0, 0.0, 1.0);
        mw = mw * mw * (3.0 - 2.0 * mw);
        if (mw <= 0.0) {
            return half4(0.0);
        }
"""
    } else {
        "        float mw = 1.0;"
    }
    return """
    uniform shader child;
    uniform float2 in_maxCoord;
    uniform float2 in_step;
    uniform float in_maxRadius;
    uniform float2 in_gradAxis;
    uniform float2 in_gradBand;
    uniform float in_curve;
$maskUniforms

    half4 main(float2 xy) {
        float p = dot(xy, in_gradAxis);
$maskWeight
        float raw = clamp((p - in_gradBand.x) / (in_gradBand.y - in_gradBand.x), 0.0, 1.0);
        float intensity = 1.0 - pow(raw * raw * (3.0 - 2.0 * raw), in_curve);
        float radius = in_maxRadius * intensity;
        if (radius <= 0.0) {
            half4 s = child.eval(clamp(xy, float2(0.5), in_maxCoord));
            if (s.a > 0.0039) {
                return half4(s.rgb / s.a, 1.0) * half(mw);
            }
            return s * half(mw);
        }
        float inv2s2 = -1.0 / (2.0 * (radius * 0.5 + 0.5) * (radius * 0.5 + 0.5));
        half4 color = child.eval(clamp(xy, float2(0.5), in_maxCoord));
        float total = 1.0;
        for (int j = 0; j < ${maxTap / 2}; j++) {
            float a = float(2 * j + 1);
            float ra = clamp(radius - a + 1.0, 0.0, 1.0);
            if (ra <= 0.0) continue;
            float b = a + 1.0;
            float wa = exp(a * a * inv2s2) * ra;
            float wb = exp(b * b * inv2s2) * clamp(radius - b + 1.0, 0.0, 1.0);
            float wsum = wa + wb;
            float2 o = ((a * wa + b * wb) / wsum) * in_step;
            color += (child.eval(clamp(xy + o, float2(0.5), in_maxCoord)) +
                child.eval(clamp(xy - o, float2(0.5), in_maxCoord))) * half(wsum);
            total += 2.0 * wsum;
        }
        color = color / half(max(total, 0.0001));
        if (color.a > 0.0039) {
            return half4(color.rgb / color.a, 1.0) * half(mw);
        }
        return color * half(mw);
    }
"""
}

/** Lazily built progressive Blur shader sources, indexed by tap bound; second array = masked. */
private val PROGRESSIVE_BLUR_SHADER_BY_TAP = arrayOfNulls<String>(PROGRESSIVE_BLUR_MAX_TAP + 1)
private val PROGRESSIVE_BLUR_SHADER_MASKED_BY_TAP = arrayOfNulls<String>(PROGRESSIVE_BLUR_MAX_TAP + 1)

/** The progressive Blur shader source with loop bound [tapBound] (from [progressiveLoopTapBound]). */
internal fun progressiveBlurShaderForTap(tapBound: Int, masked: Boolean = false): String {
    val cache = if (masked) PROGRESSIVE_BLUR_SHADER_MASKED_BY_TAP else PROGRESSIVE_BLUR_SHADER_BY_TAP
    return cache[tapBound] ?: buildProgressiveBlurShader(tapBound, masked).also { cache[tapBound] = it }
}

/**
 * Compile-time loop bound for [maxRadius]: the next multiple of 4 covering it, capped at
 * [PROGRESSIVE_BLUR_MAX_TAP]. Quantizing keeps the shader-variant count small (and an animating
 * radius from recompiling per frame) while a small radius skips most of the unrolled iterations
 * the full bound would cost per fragment.
 */
internal fun progressiveLoopTapBound(maxRadius: Float): Int {
    val needed = kotlin.math.ceil(maxRadius).toInt()
    return (((needed + 3) / 4) * 4).coerceIn(4, PROGRESSIVE_BLUR_MAX_TAP)
}

private val PROGRESSIVE_LOOP_KEYS: Array<Array<Array<String>>> = Array(2) { axis ->
    Array(PROGRESSIVE_MAX_DOWNSCALE_EXP + 1) { exp ->
        Array(PROGRESSIVE_BLUR_MAX_TAP + 1) { tap ->
            if (tap > 0 && tap % 4 == 0) {
                "LMPGaussLoop_${if (axis == 0) 'H' else 'V'}_d${1 shl exp}_t$tap"
            } else {
                ""
            }
        }
    }
}

internal fun progressiveLoopShaderKey(axis: Int, downScale: Int, tap: Int): String = PROGRESSIVE_LOOP_KEYS[axis][downScale.countTrailingZeroBits()][tap]

private val PROGRESSIVE_SHARP_KEYS: Array<Array<Array<String>>> = Array(2) { axis ->
    Array(2) { masked ->
        Array(PROGRESSIVE_BLUR_MAX_TAP + 1) { tap ->
            if (tap > 0 && tap % 4 == 0) {
                "ProgSharpLoop_${if (axis == 0) 'H' else 'V'}_t$tap${if (masked == 1) "_m" else ""}"
            } else {
                ""
            }
        }
    }
}

internal fun progressiveSharpShaderKey(axis: Int, tap: Int, masked: Boolean): String = PROGRESSIVE_SHARP_KEYS[axis][if (masked) 1 else 0][tap]

/** Gaussian sigma fractions of the three graduated composite levels (`blur0` … `blur2`). */
internal const val PROGRESSIVE_LEVEL_FRACTION_0 = 1.0f
internal const val PROGRESSIVE_LEVEL_FRACTION_1 = 0.4f
internal const val PROGRESSIVE_LEVEL_FRACTION_2 = 0.13f

/**
 * Multi-level progressive composite (Skiko multi-child runtime shader): three graduated Gaussian
 * levels ([blur0] strongest … [blur2] lightest) interpolated along the gradient so the blur
 * strength itself ramps — graduated levels are what make the ramp read as progressive rather than
 * one blur cross-faded with sharp. Runs on the downscaled layer with [clearChild] bound to the
 * lightest level (degenerating the last mix segment; the true sharp end is a separate
 * full-resolution overlay masked by [PROGRESSIVE_LEVEL_MASK_SHADER]) — except at full resolution,
 * where [clearChild] binds the source and the blend to sharp completes in-stack. Android builds
 * the identical image as a blend-mode DAG of the same masks. Each segment's mix fraction is smoothstepped: the
 * walk's derivative kinks at the segment stops otherwise read as Mach-band stripes, and zeroing
 * the slope at the stops removes them without spatial noise (which would scale with the
 * downscale's texel size). Must stay in lockstep with [PROGRESSIVE_LEVEL_MASK_SHADER]'s weight
 * smoothstep — `s(1 − x) = 1 − s(x)` is what keeps the Android DAG identical to this mix chain.
 */
internal const val PROGRESSIVE_COMPOSITE_SHADER = """
    uniform shader clearChild;
    uniform shader blur0;
    uniform shader blur1;
    uniform shader blur2;
    uniform float2 in_gradAxis;
    uniform float2 in_gradBand;
    uniform float in_curve;

    half4 main(float2 xy) {
        float p = dot(xy, in_gradAxis);
        float raw = clamp((p - in_gradBand.x) / (in_gradBand.y - in_gradBand.x), 0.0, 1.0);
        raw = pow(raw * raw * (3.0 - 2.0 * raw), in_curve);
        // raw: 0 at the full-strength end, 1 at the clear end. Walk the level stack
        // [blur0 -> blur1 -> blur2 -> sharp] so blur strength decreases continuously.
        float pos = raw * 3.0;
        half4 color;
        if (pos < 1.0) {
            float f = pos * pos * (3.0 - 2.0 * pos);
            color = mix(blur0.eval(xy), blur1.eval(xy), f);
        } else if (pos < 2.0) {
            float f = pos - 1.0;
            f = f * f * (3.0 - 2.0 * f);
            color = mix(blur1.eval(xy), blur2.eval(xy), f);
        } else {
            float f = pos - 2.0;
            f = f * f * (3.0 - 2.0 * f);
            color = mix(blur2.eval(xy), clearChild.eval(xy), f);
        }
        // Unpremul terminator (same rationale as buildBlurShader): the native level blurs mix
        // the padding ring's transparency into the edges; without renormalizing to opaque, the
        // sharp content below bleeds through the semi-transparent rim.
        if (color.a > 0.0039) {
            return half4(color.rgb / color.a, 1.0);
        }
        return color;
    }
"""

/**
 * Renormalizes a premultiplied blur result to opaque — [buildBlurShader]'s unpremul terminator as
 * a standalone pass. Chained after the Android progressive stack's unmasked base blur, the one
 * level whose alpha nothing else renormalizes (masked levels get it inline from
 * [PROGRESSIVE_LEVEL_MASK_SHADER]; the Skiko composite applies it as its own terminator).
 */
internal const val UNPREMUL_SHADER = """