package com.netops.handbook.lens

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import com.netops.handbook.lens.glass.LiquidGlassShaders
import androidx.compose.ui.unit.Dp

/**
 * 手写 drawBackdrop（KernelSU FloatingBottomBar 同效，替代 miuix-blur）。
 * RenderEffect 链：color(vibrancy) ∘ blur ∘ lens(content)，GPU 逐帧采样零回读。
 * shader 原语全部来自 LiquidGlassShaders.java（KernelSU verbatim）。
 *
 * @param glassRootPos 玻璃左上角在 root（= backdrop）坐标中的位置，
 *   由调用方 onGloballyPositioned { it.boundsInRoot().topLeft } 广播。
 */
fun Modifier.drawGlassBackdrop(
    backdrop: GraphicsLayer,
    glassRootPos: Offset,
    contentRect: Rect,          // 玻璃在本 AndroidView 坐标里的矩形
    cornerRadius: Dp,
    blurRadius: Dp,
    lensHeight: Dp,
    lensAmount: Dp,
    dark: Boolean,
) = drawWithContent {
    val r = cornerRadius.toPx()
    val outer = Rect(glassRootPos + contentRect.topLeft, contentRect.size)  // 玻璃全屏矩形
    val localPath = Path().apply {
        addRoundRect(RoundRect(contentRect.left, contentRect.top, contentRect.right, contentRect.bottom, CornerRadius(r)))
    }
    if (Build.VERSION.SDK_INT >= 33) {
        val halfW = contentRect.width / 2f
        val halfH = contentRect.height / 2f
        val cxC = outer.left + halfW
        val cyC = outer.top + halfH
        // shader 注释原话：offset 喂 (hw-cx, hh-cy)，把玻璃中心移到 shader 原点
        val shader = android.graphics.RuntimeShader(LiquidGlassShaders.REFRACTION)
        shader.setFloatUniform("size", contentRect.width, contentRect.height)
        shader.setFloatUniform("offset", halfW - cxC, halfH - cyC)
        shader.setFloatUniform("cornerRadii", r, r, r, r)
        shader.setFloatUniform("refractionHeight", lensHeight.toPx())
        shader.setFloatUniform("refractionAmount", lensAmount.toPx())
        shader.setFloatUniform("depthEffect", 0f)
        val lensEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")

        val b = blurRadius.toPx()
        val blurEffect = RenderEffect.createBlurEffect(b, b, android.graphics.Shader.TileMode.CLAMP)

        val colorShader = android.graphics.RuntimeShader(LiquidGlassShaders.COLOR_CONTROLS)
        colorShader.setFloatUniform("in_brightness", 0f)
        colorShader.setFloatUniform("in_contrast", 1f)
        colorShader.setFloatUniform("in_saturation", 1.5f)
        val colorEffect = RenderEffect.createRuntimeShaderEffect(colorShader, "child")

        // KernelSU effects 声明 vibrancy→blur→lens，包裹后 = color(blur(lens(content)))
        val chain = RenderEffect.createChainEffect(
            colorEffect, RenderEffect.createChainEffect(blurEffect, lensEffect)
        ).asComposeRenderEffect()

        val prev = backdrop.renderEffect
        backdrop.renderEffect = chain
        // 平移后画布坐标 = backdrop 全屏坐标，shader/content 的 eval 全部对上
        clipPath(localPath) {
            translate(-outer.left, -outer.top) { drawLayer(backdrop) }
        }
        backdrop.renderEffect = prev
    } else {
        // 平绘兜底（API 24~32，无 RuntimeShader）
        drawRoundRect(
            color = if (dark) Color(0xCC1C1C1E) else Color(0xCCF7F7FA),
            topLeft = contentRect.topLeft, size = contentRect.size, cornerRadius = CornerRadius(r),
        )
    }
    // surfaceContainer tint 40%（KernelSU FloatingBottomBar:220，同底栏）
    drawRoundRect(
        color = if (dark) Color(0x661C1C1E) else Color(0x66F4F4F4),
        topLeft = contentRect.topLeft, size = contentRect.size, cornerRadius = CornerRadius(r),
    )
    // ❗必须放行后续绘制：不调 drawContent 会吞掉修饰符链之后的
    // 第二层玻璃与 tabs 内容（2026-11-08 平板实锤：dock 整体不显示）。
    drawContent()
}
