// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import top.yukonga.miuix.kmp.blur.internal.recordLayer

/**
 * Captures the content of this composable into the given [LayerBackdrop]'s graphics layer.
 * Place this modifier on the container whose content should appear as the blurred background.
 *
 * @param backdrop The [LayerBackdrop] whose graphics layer records this composable's content.
 */
fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier = this then LayerBackdropElement(backdrop, drawToScreen = true)

/**
 * Records this composable's content into [backdrop]'s graphics layer ONLY, without drawing to screen.
 * Avoids `.alpha(0f)` short-circuiting in Compose 1.9 while preventing offscreen layers from appearing twice.
 */
fun Modifier.offscreenLayerBackdrop(backdrop: LayerBackdrop): Modifier = this then LayerBackdropElement(backdrop, drawToScreen = false)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop,
    val drawToScreen: Boolean = true,
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode = LayerBackdropNode(backdrop, drawToScreen)

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop !== backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.drawToScreen = drawToScreen
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = if (drawToScreen) "layerBackdrop" else "offscreenLayerBackdrop"
        properties["backdrop"] = backdrop
        properties["drawToScreen"] = drawToScreen
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false
        return backdrop == other.backdrop && drawToScreen == other.drawToScreen
    }

    override fun hashCode(): Int = 31 * backdrop.hashCode() + drawToScreen.hashCode()
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop,
    var drawToScreen: Boolean,
) : Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode {

    override fun ContentDrawScope.draw() {
        if (drawToScreen) {
            drawContent()
        }
        recordLayer(this@LayerBackdropNode, backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}
