package com.netops.handbook

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * P0 探针(2026-11-12)v2:FlClash/KernelSU 同代架构可行性验证,零外部依赖。
 *
 * 原理 = Kyant0/KernelSU 的内核:同树 GraphicsLayer 捕获内容(record)→
 * 玻璃节点 drawLayer 回放 + graphicsLayer.renderEffect 挂 GPU 效果链。
 * 三个玻璃块分别验证——
 *   A BLUR      RenderEffect.createBlurEffect(≈FlClash BackdropFilter 模糊)
 *   B SHADER    RuntimeShader(AGSL)content.eval 透传(≈折射链的载体)
 *   C BLUR+LENS blur.then(shader) 全链(= dock 栏体的最终形态)
 * 每块都回放同一张捕获层,捕获层内容含 **AndroidView**(View 体系)的绘制——
 * 同时验证「View 内容能否进 Compose GraphicsLayer」(dock 迁移的前提)。
 *
 * 判读(截图):
 *  - 三块都有背景内容:A/B/C 全活 → 路线成立;
 *  - A 糊 B 清 → RenderEffect+RuntimeShader 都活,只差接线精度;
 *  - 三块全是纯色/透明 → RenderNode 效果路在本机判死,dock 维持现架构。
 */
class GlassProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Probe() }
    }
}

/** 透传 shader:content.eval = AGSL 管线/采样/坐标体系全链的活性验证。 */
private const val SHADER_PASS = """
uniform shader content;
half4 main(float2 coord) {
    return content.eval(coord);
}
"""

/** 边缘折射 shader(极简版):中心 1:1 透传,边缘向外位移=玻璃弯折观感。 */
private const val SHADER_LENS = """
uniform shader content;
uniform float2 uSize;
half4 main(float2 coord) {
    float2 c = coord - uSize * 0.5;
    float d = length(c / uSize * 2.0);
    float bend = max(0.0, d - 0.82) * 40.0;
    float2 dir = normalize(c + float2(0.0001));
    return content.eval(coord + dir * bend);
}
"""

@Composable
private fun Probe() {
    val layer = rememberGraphicsLayer()
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        /* 捕获子树:内容层(AndroidView)。玻璃块必须在 record 范围**之外**,
           否则 layer 回放自己 = prepareTree 无限递归(hwui native crash,已踩)。 */
        Column(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
        ) {
            Text(
                "P0 PROBE v2  " + java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date()),
                Modifier.padding(12.dp),
                color = Color(0xFF222222)
            )
            AndroidView(
                factory = { ctx ->
                    object : View(ctx) {
                        val pTitle = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                            .apply { textSize = 64f; color = 0xFF1A56DB.toInt() }
                        val pBody = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                            .apply { textSize = 40f; color = 0xFFB23A48.toInt() }
                        val pShape = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                            .apply { color = 0xFF3E7CB1.toInt() }

                        override fun onDraw(c: Canvas) {
                            c.drawColor(0xFFF5F7FB.toInt())
                            c.drawText("ANDROID-VIEW SOURCE", 40f, 200f, pTitle)
                            c.drawText("VXLAN / EVPN / BGP unnumbered", 40f, 320f, pBody)
                            c.drawText("K8s CNI Ingress Gateway ARP eBPF", 40f, 420f, pBody)
                            c.drawText("SNMP gNMI YANG telemetry probe", 40f, 520f, pBody)
                            c.drawCircle(880f, 700f, 120f, pShape)
                            c.drawRect(40f, 780f, 620f, 900f, pShape)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(560.dp)
            )
        }
        /* 玻璃块与捕获子树平级(record 范围外) */
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 32.dp)
        ) {
            GlassBlock("A BLUR", layer, Modifier.weight(1f)) {
                if (Build.VERSION.SDK_INT >= 31) {
                    RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                } else null
            }
            GlassBlock("B SHADER", layer, Modifier.weight(1f)) {
                if (Build.VERSION.SDK_INT >= 33) {
                    val rs = RuntimeShader(SHADER_PASS)
                    RenderEffect.createRuntimeShaderEffect(rs, "content")
                        .asComposeRenderEffect()
                } else null
            }
            GlassBlock(
                "C BLUR+LENS", layer, Modifier.weight(1f),
                outer = {
                    val rs = RuntimeShader(SHADER_LENS)
                    rs.setFloatUniform("uSize", 420f, 160f)
                    RenderEffect.createRuntimeShaderEffect(rs, "content").asComposeRenderEffect()
                },
                inner = {
                    RenderEffect.createBlurEffect(6f, 6f, Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                }
            )
        }
    }
}

/**
 * 玻璃块:graphicsLayer{renderEffect} 挂 GPU 链,drawBehind 回放捕获层——
 * 回放内容在 layer 内,被 renderEffect 整体处理(与上游 backdrop 同构)。
 * inner/outer 两层嵌套 = blur 先行、shader 后行(平台 RenderEffect 无 then 链)。
 */
@Composable
private fun GlassBlock(
    label: String,
    captured: GraphicsLayer,
    modifier: Modifier,
    outer: (() -> androidx.compose.ui.graphics.RenderEffect?)? = null,
    inner: (() -> androidx.compose.ui.graphics.RenderEffect?)? = null
) {
    Box(
        modifier
            .height(160.dp)
            .padding(4.dp)
            .graphicsLayer { if (outer != null) renderEffect = outer() }
            .graphicsLayer { if (inner != null) renderEffect = inner() }
            .drawBehind { drawLayer(captured) }
            .background(Color(0x22888888))
    ) {
        Text(label, Modifier.align(Alignment.TopStart).padding(6.dp), color = Color(0xFFB23A48))
    }
}
