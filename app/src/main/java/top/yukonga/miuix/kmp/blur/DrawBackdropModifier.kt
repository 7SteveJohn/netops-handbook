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