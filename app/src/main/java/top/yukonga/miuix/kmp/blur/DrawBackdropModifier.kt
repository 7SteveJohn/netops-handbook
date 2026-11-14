// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.drawHighlight
import top.yukonga.miuix.kmp.blur.internal.DOWNSAMPLE_2X_SHADER
import top.yukonga.miuix.kmp.blur.internal.DOWNSAMPLE_4X_SHADER
import top.yukonga.miuix.kmp.blur.internal.NOISE_DITHER_SHADER
import top.yukonga.miuix.kmp.blur.internal.ShapeProvider
import top.yukonga.miuix.kmp.blur.internal.chain
import top.yukonga.miuix.kmp.blur.internal.createProgressiveSharpOverlayEffect
import top.yukonga.miuix.kmp.blur.internal.createProgressiveStackEffect
import top.yukonga.miuix.kmp.blur.internal.progressiveLoopTapBound
import top.yukonga.miuix.kmp.blur.internal.progressiveSharpBandBounds
import top.yukonga.miuix.kmp.blur.internal.progressiveSharpLoopRadius
import top.yukonga.miuix.kmp.blur.internal.recordLayer
import top.yukonga.miuix.kmp.blur.internal.runtimeShaderEffect
import kotlin.math.ceil
import kotlin.math.floor

private val DefaultOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }

// The stack dither exists to break quantization staircases, which ~1 LSB (≈ 0.008 peak-to-peak)
// already does; anything above only adds downscale-texel-sized grain that grows with the radius.
private const val STACK_DITHER_MAX = 0.009f

// Above this coefficient the noise is deliberate visible grain, which must land per screen pixel
// to stay radius-independent — the full-resolution noise pass runs. At or below it the grain is
// imperceptible and the capped stack dither alone covers anti-banding, so the round-trip is
// skipped.
private const val NOISE_GRAIN_THRESHOLD = 0.005f

/**
 * Applies a backdrop effect to this composable.
 *
 * @param backdrop The [Backdrop] providing the background content to capture and blur.
 * @param shape The shape provider for the blur region clipping; re-read on each draw so an
 *   animated shape stays current.
 * @param effects The effect pipeline applied to the captured backdrop, configured against a
 *   [BackdropEffectScope] (e.g. [blur], [blendColors], [colorControls]).
 * @param highlight Optional edge highlight resolved against the [BackdropEffectScope] and painted
 *   on top of the content; returning `null` skips drawing.
 * @param layerBlock Optional graphics layer transformation applied to this composable and inverted
 *   on the backdrop sampling so the backdrop stays screen-aligned; safe to animate.
 * @param onDrawBehind Optional draw callback invoked before the blurred backdrop, behind it.
 * @param onDrawBackdrop Wraps the backdrop drawing call, letting callers transform or intercept the
 *   recorded content; defaults to invoking it directly.
 * @param onDrawSurface Optional draw callback invoked after the blurred backdrop and before the
 *   composable's own content.
 * @param onDrawFront Optional draw callback invoked last, on top of the content and highlight.
 * @param contentBlendMode The [BlendMode] used to composite the composable's content over the blur.
 *   [BlendMode.DstIn] masks the blur by the content alpha (foreground blur).
 * @param progressiveGradient When non-null, renders the backdrop as a multi-level progressive
 *   composite: graduated native Gaussian levels cross-faded along this gradient on the downscaled
 *   layer, with a genuinely sharp full-resolution clear end — the blur strength ramps
 *   continuously from full to pixel-sharp. Requires a matching [progressiveBlur] in [effects] (as
 *   [Modifier.progressiveTextureBlur] wires) — it records the target radii the composite renders;
 *   without one the gradient is ignored and [effects] draws uniformly.
 * @param enabled Whether the backdrop effect is active. When false, content draws normally without
 *   blur. Also gated by [isRuntimeShaderSupported].
 */
fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    highlight: (BackdropEffectScope.() -> Highlight?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null,
    contentBlendMode: BlendMode = BlendMode.SrcOver,
    progressiveGradient: ProgressiveBlur? = null,
    enabled: Boolean = true,
): Modifier {
    // The effect pipeline (blur / blend / highlight / custom passes) is built on RuntimeShader.
    val effectiveEnabled = enabled && isRuntimeShaderSupported()
    return this
        .then(
            if (layerBlock != null) Modifier.graphicsLayer(layerBlock) else Modifier,
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shape = shape,
                effects = effects,
                highlight = highlight,
                layerBlock = layerBlock,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
                contentBlendMode = contentBlendMode,
                progressiveGradient = progressiveGradient,
                enabled = effectiveEnabled,
            ),
        )
}

private class DrawBackdropElement(
    val backdrop: Backdrop,
    val shape: () -> Shape,
    val effects: BackdropEffectScope.() -> Unit,
    val highlight: (BackdropEffectScope.() -> Highlight?)?,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val onDrawBehind: (DrawScope.() -> Unit)?,
    val onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    val onDrawSurface: (DrawScope.() -> Unit)?,
    val onDrawFront: (DrawScope.() -> Unit)?,
    val contentBlendMode: BlendMode = BlendMode.SrcOver,
    val progressiveGradient: ProgressiveBlur? = null,
    val enabled: Boolean = true,
) : ModifierNodeElement<DrawBackdropNode>() {

    override fun create(): DrawBackdropNode = DrawBackdropNode(
        backdrop = backdrop,
        shape = shape,
        effects = effects,
        highlight = highlight,
        layerBlock = layerBlock,
        onDrawBehind = onDrawBehind,
        onDrawBackdrop = onDrawBackdrop,
        onDrawSurface = onDrawSurface,
        onDrawFront = onDrawFront,
        contentBlendMode = contentBlendMode,
        progressiveGradient = progressiveGradient,
        enabled = enabled,
    )

    override fun update(node: DrawBackdropNode) {
        val enabledChanged = node.enabled != enabled
        val gradientCleared = node.progressiveGradient != null && progressiveGradient == null
        node.backdrop = backdrop
        node.updateShape(shape)
        node.effects = effects
        node.highlight = highlight
        node.layerBlock = layerBlock
        node.onDrawBehind = onDrawBehind
        node.onDrawBackdrop = onDrawBackdrop
        node.onDrawSurface = onDrawSurface
        node.onDrawFront = onDrawFront
        node.contentBlendMode = contentBlendMode
        node.progressiveGradient = progressiveGradient
        node.enabled = enabled
        if (gradientCleared) {
            node.releaseCompositeResources()
        }
        if (enabledChanged) {
            if (!enabled) {
                node.releaseGraphicsLayers()
            }
            node.invalidateMeasurement()
        }
        node.invalidateDrawCache()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBackdrop"
        properties["backdrop"] = backdrop
        properties["enabled"] = enabled
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DrawBackdropElement) return false
        if (backdrop != other.backdrop) return false
        if (shape != other.shape) return false
        if (effects != other.effects) return false
        if (highlight != other.highlight) return false
        if (layerBlock != other.layerBlock) return false
        if (onDrawBehind != other.onDrawBehind) return false
        if (onDrawBackdrop != other.onDrawBackdrop) return false
        if (onDrawSurface != other.onDrawSurface) return false
        if (onDrawFront != other.onDrawFront) return false
        if (contentBlendMode != other.contentBlendMode) return false
        if (progressiveGradient != other.progressiveGradient) return false
        if (enabled != other.enabled) return false
        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + shape.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + (highlight?.hashCode() ?: 0)
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        result = 31 * result + (onDrawBehind?.hashCode() ?: 0)
        result = 31 * result + onDrawBackdrop.hashCode()
        result = 31 * result + (onDrawSurface?.hashCode() ?: 0)
        result = 31 * result + (onDrawFront?.hashCode() ?: 0)
        result = 31 * result + contentBlendMode.hashCode()
        result = 31 * result + (progressiveGradient?.hashCode() ?: 0)
        result = 31 * result + enabled.hashCode()
        return result
    }
}

private class DrawBackdropNode(
    var backdrop: Backdrop,
    val shape: () -> Shape,
    var effects: BackdropEffectScope.() -> Unit,
    var highlight: (BackdropEffectScope.() -> Highlight?)?,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var onDrawBehind: (DrawScope.() -> Unit)?,
    var onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    var onDrawSurface: (DrawScope.() -> Unit)?,
    var onDrawFront: (DrawScope.() -> Unit)?,
    var contentBlendMode: BlendMode = BlendMode.SrcOver,
    var progressiveGradient: ProgressiveBlur? = null,
    var enabled: Boolean = true,
) : Modifier.Node(),
    LayoutModifierNode,
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    ObserverModifierNode,
    CompositionLocalConsumerModifierNode {

    override val shouldAutoInvalidate: Boolean = false

    private val shapeProvider: ShapeProvider = ShapeProvider(shape)

    fun updateShape(shape: () -> Shape) {
        shapeProvider.shapeBlock = shape
    }

    private val effectScope = object : BackdropEffectScopeImpl() {
        override val shape: Shape get() = shapeProvider.innerShape
    }

    /** The node's graphics context; the (necessarily `inner`) [LevelTarget] reaches it through here. */
    private val graphicsContext get() = requireGraphicsContext()

    /** Layers + level parameters for rendering one downscale level (primary = lo, secondary = hi). */
    private inner class LevelTarget {
        var mainLayer: GraphicsLayer? = null
        val cascadeLayers: MutableList<GraphicsLayer> = mutableListOf()
        var noiseLayer: GraphicsLayer? = null
        var downscaleFactor: Int = 1
        var padding: Float by mutableFloatStateOf(0f)

        /** First step downscale for cascade (backdrop draw uses this, not the total factor). */
        var cascadeFirstStepScale: Int = 1

        // Draw-path RenderEffects cached for reuse while the source size / noise coefficient hold.
        // A RenderEffect captures its shader's uniforms when created, so a built instance is
        // unaffected by later re-sets of the tree-shared RuntimeShader — reuse is safe and saves a
        // native allocation per frame. Two slots cover the deepest cascade (sf=16).
        val dsKeys = arrayOf("", "")
        val dsW = intArrayOf(-1, -1)
        val dsH = intArrayOf(-1, -1)
        val dsEffects = arrayOfNulls<RenderEffect>(2)
        var noiseEffect: RenderEffect? = null
        var noiseEffectCoeff: Float = Float.NaN

        fun ensureMain(): GraphicsLayer = mainLayer ?: graphicsContext.createGraphicsLayer().also { mainLayer = it }

        fun obtainCascade(index: Int): GraphicsLayer {
            val ctx = graphicsContext
            while (cascadeLayers.size <= index) {
                cascadeLayers.add(ctx.createGraphicsLayer())
            }
            return cascadeLayers[index]
        }

        /**
         * Returns the cached downsample [RenderEffect] for [slot] when its [key]/[w]/[h] are
         * unchanged, otherwise builds it via [build] (setting the shader uniforms) and caches it.
         */
        inline fun downsampleEffect(slot: Int, key: String, w: Int, h: Int, build: () -> RenderEffect): RenderEffect {
            val cached = dsEffects[slot]
            if (cached != null && dsKeys[slot] == key && dsW[slot] == w && dsH[slot] == h) return cached
            return build().also {
                dsEffects[slot] = it
                dsKeys[slot] = key
                dsW[slot] = w
                dsH[slot] = h
            }
        }

        /** Returns the cached noise [RenderEffect] for [coeff], building via [build] on a miss. */
        inline fun noiseRenderEffect(coeff: Float, build: () -> RenderEffect): RenderEffect {
            val cached = noiseEffect
            if (cached != null && noiseEffectCoeff == coeff) return cached
            return build().also {
                noiseEffect = it
                noiseEffectCoeff = coeff
            }
        }

        fun release() {
            val ctx = graphicsContext
            mainLayer?.let { ctx.releaseGraphicsLayer(it) }
            mainLayer = null
            for (cascade in cascadeLayers) ctx.releaseGraphicsLayer(cascade)
            cascadeLayers.clear()
            noiseLayer?.let { ctx.releaseGraphicsLayer(it) }
            noiseLayer = null
            dsEffects.fill(null)
            dsKeys.fill("")
            dsW.fill(-1)
            dsH.fill(-1)
            noiseEffect = null
            noiseEffectCoeff = Float.NaN
        }
    }

    private val primary = LevelTarget()
    private val secondary = LevelTarget()
    private var crossfadeResultLayer: GraphicsLayer? = null

    /** True when the radius sits in a transition band and [primary] (lo) + [secondary] (hi) cross-fade. */
    private var blending: Boolean by mutableStateOf(false)

    /** Smoothstep weight of the higher (secondary) level while [blending]. */
    private var blendFactor: Float by mutableFloatStateOf(0f)

    private val layoutLayerBlock: GraphicsLayerScope.() -> Unit = {
        clip = true
        shape = shapeProvider.shape
        compositingStrategy = CompositingStrategy.Offscreen
    }

    private var layoutCoordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())

    private fun DrawScope.recordBackdrop(target: LevelTarget) {
        val currentPadding = target.padding
        val scaleFactor = target.cascadeFirstStepScale
        val scaledPadding = if (currentPadding == 0f) {
            0f
        } else if (scaleFactor > 1) {
            (currentPadding / scaleFactor).toInt().toFloat()
        } else {
            currentPadding
        }
        translate(scaledPadding, scaledPadding) {
            onDrawBackdrop {
                with(backdrop) {
                    drawBackdrop(
                        density = effectScope,
                        coordinates = layoutCoordinates,
                        layerBlock = layerBlock,
                        downscaleFactor = scaleFactor,
                    )
                }
            }
        }
    }

    /** Renders [target]'s blurred backdrop (record → cascade downsample → upscale) into this DrawScope. */
    private fun DrawScope.renderBlurInto(target: LevelTarget) {
        val layer = target.mainLayer ?: return
        val currentPadding = target.padding
        val scaleFactor = target.downscaleFactor
        val fullWidth = (size.width + currentPadding * 2).toInt()
        val fullHeight = (size.height + currentPadding * 2).toInt()

        if (scaleFactor <= 1) {
            target.cascadeFirstStepScale = 1
            recordLayer(layer, size = IntSize(fullWidth, fullHeight)) { recordBackdrop(target) }
            layer.topLeft =
                if (currentPadding != 0f) {
                    IntOffset(-currentPadding.toInt(), -currentPadding.toInt())
                } else {
                    IntOffset.Zero
                }
            drawLayer(layer)
        } else if (scaleFactor <= 2) {
            target.cascadeFirstStepScale = 2
            val w = (fullWidth / 2).coerceAtLeast(1)
            val h = (fullHeight / 2).coerceAtLeast(1)
            recordLayer(layer, size = IntSize(w, h)) { recordBackdrop(target) }
            drawUpscaledLayer(
                target,
                layer,
                scaleFactor.toFloat(),
                currentPadding,
                scaleFactor,
                backdrop.offsetResidualX,
                backdrop.offsetResidualY,
                fullWidth,
                fullHeight,
            )
        } else {
            // Multistep cascade, single-pass wider filter when possible:
            //   sf =  4: backdrop ½ → 2x box → ¼                     (1 cascade layer)
            //   sf =  8: backdrop ½ → 4x box → ⅛                     (1 cascade layer)
            //   sf = 16: backdrop ½ → 4x box → ⅛ → 2x box → 1/16     (2 cascade layers)
            target.cascadeFirstStepScale = 2
            val firstW = (fullWidth / 2).coerceAtLeast(1)
            val firstH = (fullHeight / 2).coerceAtLeast(1)

            // Step 0: record backdrop at ½ size (GPU bilinear, no shader).
            val firstCascade = target.obtainCascade(0)
            recordLayer(firstCascade, size = IntSize(firstW, firstH)) { recordBackdrop(target) }

            when (scaleFactor) {
                4 -> {
                    // Single 2× box step: ½ → ¼
                    applyDownsampleStep(
                        target = target,
                        slot = 0,
                        source = firstCascade,
                        sourceW = firstW,
                        sourceH = firstH,
                        dest = layer,
                        destW = (firstW / 2).coerceAtLeast(1),
                        destH = (firstH / 2).coerceAtLeast(1),
                        scale = 0.5f,
                        shaderKey = "Downsample2x",
                        shaderSrc = DOWNSAMPLE_2X_SHADER,
                    )
                }

                8 -> {
                    // Single 4× box step: ½ → ⅛
                    applyDownsampleStep(
                        target = target,
                        slot = 0,
                        source = firstCascade,
                        sourceW = firstW,
                        sourceH = firstH,
                        dest = layer,
                        destW = (firstW / 4).coerceAtLeast(1),
                        destH = (firstH / 4).coerceAtLeast(1),
                        scale = 0.25f,
                        shaderKey = "Downsample4x",
                        shaderSrc = DOWNSAMPLE_4X_SHADER,
                    )
                }

                16 -> {
                    // 4× then 2× cascade: ½ → ⅛ → 1/16
                    val midW = (firstW / 4).coerceAtLeast(1)
                    val midH = (firstH / 4).coerceAtLeast(1)
                    val midCascade = target.obtainCascade(1)
                    applyDownsampleStep(
                        target = target,
                        slot = 0,
                        source = firstCascade,
                        sourceW = firstW,
                        sourceH = firstH,
                        dest = midCascade,
                        destW = midW,
                        destH = midH,
                        scale = 0.25f,
                        shaderKey = "Downsample4x",
                        shaderSrc = DOWNSAMPLE_4X_SHADER,
                    )
                    applyDownsampleStep(
                        target = target,
                        slot = 1,
                        source = midCascade,
                        sourceW = midW,
                        sourceH = midH,
                        dest = layer,
                        destW = (midW / 2).coerceAtLeast(1),
                        destH = (midH / 2).coerceAtLeast(1),
                        scale = 0.5f,
                        shaderKey = "Downsample2x",
                        shaderSrc = DOWNSAMPLE_2X_SHADER,
                    )
                }

                else -> error("Unsupported scaleFactor: $scaleFactor (must be 1/2/4/8/16)")
            }

            drawUpscaledLayer(
                target,
                layer,
                scaleFactor.toFloat(),
                currentPadding,
                scaleFactor,
                backdrop.offsetResidualX,
                backdrop.offsetResidualY,
                fullWidth,
                fullHeight,
            )
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            if (enabled) {
                placeable.placeWithLayer(IntOffset.Zero, layerBlock = layoutLayerBlock)
            } else {
                placeable.place(IntOffset.Zero)
            }
        }
    }

    private val contentPaint = Paint()
    private val highlightPaint = Paint()

    // Reused saveLayer bounds for the non-SrcOver content path (DstIn / foreground blur): Rect is a
    // plain class, so caching it avoids a per-frame allocation; rebuilt only when the size changes.
    private var contentBoundsRect: Rect? = null

    // Progressive (gradient) blur, split by resolution: the level stack renders through the
    // downscaled [primary] machinery, and [sharpLayer] overlays the full-resolution backdrop
    // through the overlay effect (variable loop blur + ramp mask) on the ramp's clear end — the
    // only band that needs native pixels. At full resolution the stack completes the sharp end
    // itself (nativeSharpEnd) and the overlay stays inactive. The supported flags gate platforms
    // whose progressiveStackEffect() returns null.
    private var sharpLayer: GraphicsLayer? = null
    private var compositeAttempted = false
    private var compositeSupported = false
    private var sharpOverlayActive = false

    // Downscaled level-stack effect, cached on its inputs; pre/post chains compared by identity
    // (the scope's per-effect caches keep the references stable while their inputs hold), the
    // recorded post blend by value.
    private var cachedStackEffect: RenderEffect? = null
    private var cachedStackRadiusX = Float.NaN
    private var cachedStackRadiusY = Float.NaN
    private var cachedStackW = Float.NaN
    private var cachedStackH = Float.NaN
    private var cachedStackGradient: ProgressiveBlur? = null
    private var cachedStackPre: RenderEffect? = null
    private var cachedStackPost: RenderEffect? = null
    private var cachedStackPostBlend: BlurColors? = null
    private var cachedStackDither = Float.NaN

    // Dither/noise pass chained after the assembled stack, cached on its coefficient. exp > 0:
    // pre-quantization dither at min(2 × coefficient, [STACK_DITHER_MAX]) — the stack's smooth
    // ramps band when first written to the 8-bit layer, and a noise pass after that write can
    // only mask the staircase (the default 0.0045 alone is ≈ ±0.6 LSB, short of the ~1 LSB the
    // staircase needs; grain lands at downscale-texel size but stays invisible at this
    // amplitude). exp == 0: the same pass carries the full coefficient, already per screen pixel.
    private var stackDitherEffect: RenderEffect? = null
    private var stackDitherCoeff = Float.NaN

    // True while the stack's pre-quantization dither fully covers the noise duty (coefficient at
    // or below [NOISE_GRAIN_THRESHOLD]): the noise rides the upscale, so the full-resolution
    // noise layer round-trip (record + noise pass) is skipped — quantization of the final write
    // is dithered by the noise the signal carries. Deliberate visible grain keeps the full-res
    // pass so its size stays one screen pixel at every radius.
    private var stackDitherChained = false

    private fun obtainStackDitherEffect(coeff: Float): RenderEffect {
        val cached = stackDitherEffect
        if (cached != null && stackDitherCoeff == coeff) return cached
        val shader = effectScope.obtainRuntimeShader("NoiseDither", NOISE_DITHER_SHADER)
        shader.setFloatUniform("noise_coeff", coeff)
        return runtimeShaderEffect(shader, "child").also {
            stackDitherEffect = it
            stackDitherCoeff = coeff
        }
    }

    // Sharp overlay effect (variable loop blur + ramp mask) + band bbox (the only region paying
    // full-resolution cost), cached on gradient + size + radii/exp (the loop blur's handoff radius
    // tracks blur2). A null effect with matching cache keys means the band lies outside the
    // component and the overlay is skipped entirely.
    private var sharpOverlayEffect: RenderEffect? = null
    private var sharpOverlayGradient: ProgressiveBlur? = null
    private var sharpOverlayW = Float.NaN
    private var sharpOverlayH = Float.NaN
    private var sharpRadiusX = Float.NaN
    private var sharpRadiusY = Float.NaN
    private var sharpExp = -1
    private var sharpBandOffset = IntOffset.Zero
    private var sharpBandSize = IntSize.Zero

    /** Renders the blurred backdrop into [primary] and, in a cross-fade band, the [secondary] level. */
    private fun DrawScope.drawBlurredBackdrop() {
        renderBlurInto(primary)
        if (blending) {
            // Cross-fade band: composite the higher level on top of the lower one with alpha = blendFactor.
            val result = crossfadeResultLayer
                ?: graphicsContext.createGraphicsLayer().also { crossfadeResultLayer = it }
            recordLayer(
                result,
                size = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
            ) {
                renderBlurInto(secondary)
            }
            result.alpha = blendFactor
            drawLayer(result)
        }
    }

    /**
     * Records the backdrop at full resolution into [sharpLayer] — only the band bbox where the
     * ramp mask is non-zero — and draws it over the upscaled level stack through the overlay
     * effect: a variable loop blur ramping from blur2's strength to zero, masked to the ramp's
     * clear-end cross-fade. Renders the composite's `mix(blur2, clear, …)` segment as one
     * continuous sharpening with a genuinely sharp native end.
     */
    private fun DrawScope.drawSharpOverlay() {
        val bandSize = sharpBandSize
        if (bandSize.width <= 0 || bandSize.height <= 0) return
        val bandOffset = sharpBandOffset
        val layer = sharpLayer ?: graphicsContext.createGraphicsLayer().also { sharpLayer = it }
        recordLayer(layer, size = bandSize) {
            translate(-bandOffset.x.toFloat(), -bandOffset.y.toFloat()) {
                onDrawBackdrop {
                    with(backdrop) {
                        drawBackdrop(
                            density = effectScope,
                            coordinates = layoutCoordinates,
                            layerBlock = layerBlock,
                            downscaleFactor = 1,
                        )
                    }
                }
            }
        }
        layer.renderEffect = sharpOverlayEffect
        layer.topLeft = bandOffset
        drawLayer(layer)
    }

    override fun ContentDrawScope.draw() {
        if (!enabled) {
            drawContent()
            return
        }
        if (effectScope.update(this)) {
            updateEffects()
        }
        onDrawBehind?.invoke(this)

        drawBlurredBackdrop()
        if (sharpOverlayActive) {
            drawSharpOverlay()
        }

        onDrawSurface?.invoke(this)

        if (contentBlendMode == BlendMode.SrcOver) {
            drawContent()
        } else {
            val w = size.width
            val h = size.height
            val bounds = contentBoundsRect?.takeIf { it.right == w && it.bottom == h } ?: Rect(0f, 0f, w, h).also { contentBoundsRect = it }
            contentPaint.blendMode = contentBlendMode
            drawContext.canvas.saveLayer(bounds, contentPaint)
            drawContent()
            drawContext.canvas.restore()
        }

        highlight?.invoke(effectScope)?.let { resolved ->
            drawHighlight(
                highlight = resolved,
                shape = effectScope.shape,
                // onAttach cached this from LocalRuntimeShaderCache (a staticCompositionLocalOf, so
                // its identity is stable) — read the field instead of a per-frame currentValueOf.
                runtimeShaderCache = effectScope.runtimeShaderCache,
                paint = highlightPaint,
            )
        }

        onDrawFront?.invoke(this)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            if (backdrop.isCoordinatesDependent) {
                layoutCoordinates = coordinates
            } else {
                if (layoutCoordinates != null) {
                    layoutCoordinates = null
                }
            }
        }
    }

    override fun onObservedReadsChanged() {
        invalidateDrawCache()
    }

    fun invalidateDrawCache() {
        observeEffects()
    }

    private fun observeEffects() {
        observeReads { updateEffects() }
    }

    private fun updateEffects() {
        if (!enabled) return
        primary.ensureMain()

        // Gradient mode: progressiveBlur() records radii + downscale and splits the chain; the
        // stack is assembled below once the post chain is known. Re-derives as false once a
        // platform reports the stack unsupported.
        effectScope.progressiveCompositeActive =
            progressiveGradient != null && !(compositeAttempted && !compositeSupported)

        // Pass 1 (auto): build the lower bracket level into [primary]; blur() also stashes the
        // cross-fade bracket (computeDownScaleBlend) on the scope for us to read below.
        effectScope.forcedDownscaleExp = -1
        effectScope.apply(effects)
        if (effectScope.progressiveCompositeActive) {
            // Noise is finalize's duty here — chained after the assembled stack, not into the
            // post chain (which the composite ramp-weights).
            finalizeProgressiveComposite()
        } else {
            chainFullResNoiseIfNeeded()
            sharpOverlayActive = false
            stackDitherChained = false
        }
        primary.mainLayer?.renderEffect = effectScope.renderEffect
        primary.padding = effectScope.padding
        primary.downscaleFactor = effectScope.downscaleFactor.coerceAtLeast(1)

        val expLo = effectScope.blurBlendExpLo
        val expHi = effectScope.blurBlendExpHi
        val factor = effectScope.blurBlendFactor

        if (expLo == expHi || factor <= 0.001f) {
            blending = false
            return
        }
