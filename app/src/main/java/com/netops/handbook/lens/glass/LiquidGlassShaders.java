package com.netops.handbook.lens.glass;

/**
 * 液态玻璃 AGSL 常量真源 —— 照搬 Kyant0/AndroidLiquidGlass（Apache-2.0）。
 *
 * 来源与许可：
 * - 折射/色散/SDF：example/shared/src/commonMain/kotlin/component/liquid/Lens.kt
 *   （文件头声明 Adapted from Kyant0/AndroidLiquidGlass, Apache-2.0）。
 * - colorControls / 噪声抖动 / BloomStroke / 模糊常数：
 *   miuix-blur 模块（compose-miuix-ui/miuix，SPDX-License-Identifier: Apache-2.0），
 *   文件 internal/Shaders.kt、internal/BlurEffect.kt、BlurDefaults.kt。
 * - 以上文本已与 KernelSU v3.3.1（me.weishu.kernelsu v3.3.1-36-g28db74f6）装机包
 *   dex 内的 shader 字符串逐字核验一致（2026-11-04，REA/JADX 解剖）。
 * - 上游 CombinedBackdrop（页面层 + tab 镜像层录制）在原生 View 管线的等价物是
 *   LensBarView.buildCombined() 的 CPU 预烘焙位图；本文件全部常量逐字照搬，
 *   且每条 shader 的 input 只挂单层 BitmapShader（嵌套 RuntimeShader 会在部分
 *   GPU 驱动上绘制期链接失败）。
 *
 * ❗cornerRadii 打包顺序两套约定，喂法必须与 shader 配对（上游原样）：
 * - 折射族（REFRACTION*，Lens.kt radiusAt）：[TL, TR, BR, BL]
 * - BloomStroke（HighlightStyle.setCornerRadiiUniform / pickRadius）：[TL, TR, BL, BR]
 */
public final class LiquidGlassShaders {
    private LiquidGlassShaders() {
    }

    /* BlurDefaults.kt / BlurEffect.kt 原值 */
    /** blur 半径→σ 换算（BlurEffect.kt:22）。 */
    public static final float BLUR_RADIUS_TO_SIGMA = 0.45f;
    /** 模糊核 reach（BlurEffect.kt:28）——padding 需 ≥ reach×sf。 */
    public static final int BLUR_KERNEL_REACH = 13;
    /** 噪声抖动强度（BlurDefaults.kt:153）。 */
    public static final float NOISE_COEFFICIENT = 0.0045f;

    private static final float[] IMPLIED_BOX_VARIANCE = {0f, 3.0265f, 7.5625f, 9.0f, 202.696f};

    /** 降采样档位（BlurEffect.kt:489 downScaleExpFor 原式）：返回 exp，档位 = 1 shl exp。 */
    public static int downScaleExpFor(float sigmaSquared) {
        if (sigmaSquared >= 1945f) return 4;
        if (sigmaSquared > 400f) return 3;
        if (sigmaSquared >= 90.25f) return 2;
        if (sigmaSquared >= 12.6f) return 1;
        return 0;
    }

    /** Box 补偿方差（BlurEffect.kt:500 adjustedVarianceForExp）。本管线 CPU 模糊未用，留作对齐参照。 */
    public static float adjustedVarianceForExp(float sigmaSquared, int exp) {
        float ds = (float) (1 << exp);
        return Math.max((sigmaSquared - IMPLIED_BOX_VARIANCE[exp]) / (ds * ds), 0.1f);
    }

    /* ==== Lens.kt ROUNDED_RECT_SDF（verbatim）==== */
    public static final String SDF_ROUNDED_RECT =
            "float radiusAt(float2 coord, float4 radii) {\n" +
            "    if (coord.x >= 0.0) {\n" +
            "        if (coord.y <= 0.0) return radii.y;\n" +
            "        else return radii.z;\n" +
            "    } else {\n" +
            "        if (coord.y <= 0.0) return radii.x;\n" +
            "        else return radii.w;\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    float outside = length(max(cornerCoord, 0.0)) - radius;\n" +
            "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n" +
            "    return outside + inside;\n" +
            "}\n" +
            "\n" +
            "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n" +
            "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n" +
            "    } else {\n" +
            "        float gradX = step(cornerCoord.y, cornerCoord.x);\n" +
            "        return sign(coord) * float2(gradX, 1.0 - gradX);\n" +
            "    }\n" +
            "}\n";

    /* ==== Lens.kt ROUNDED_RECT_REFRACTION_SHADER（verbatim）====
       坐标约定：size=玻璃半尺寸×2；centeredCoord=(coord+offset)-halfSize；
       offset 由调用方把玻璃中心移到原点（我们喂 (hw-cx, hh-cy)）。 */
    public static final String REFRACTION =
            "uniform shader content;\n" +
            "\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            "\n" +
            SDF_ROUNDED_RECT +
            "float circleMap(float x) {\n" +
            "    return 1.0 - sqrt(1.0 - x * x);\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "    float radius = radiusAt(centeredCoord, cornerRadii);\n" +
            "\n" +
            "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "    if (-sd >= refractionHeight) {\n" +
            "        return content.eval(coord);\n" +
            "    }\n" +
            "    sd = min(sd, 0.0);\n" +
            "\n" +
            "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));\n" +
            "\n" +
            "    float2 refractedCoord = coord + d * grad;\n" +
            "    return content.eval(refractedCoord);\n" +
            "}\n";

    /* ==== Lens.kt ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER（verbatim）==== */
    public static final String REFRACTION_DISPERSION =
            "uniform shader content;\n" +
            "\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            "uniform float chromaticAberration;\n" +
            "\n" +
            SDF_ROUNDED_RECT +
            "float circleMap(float x) {\n" +
            "    return 1.0 - sqrt(1.0 - x * x);\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "    float radius = radiusAt(centeredCoord, cornerRadii);\n" +
            "\n" +
            "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "    if (-sd >= refractionHeight) {\n" +
            "        return content.eval(coord);\n" +
            "    }\n" +
            "    sd = min(sd, 0.0);\n" +
            "\n" +
            "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));\n" +
            "\n" +
            "    float2 refractedCoord = coord + d * grad;\n" +
            "    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n" +
            "    float2 dispersedCoord = d * grad * dispersionIntensity;\n" +
            "\n" +
            "    half4 color = half4(0.0);\n" +
            "\n" +
            "    half4 red = content.eval(refractedCoord + dispersedCoord);\n" +
            "    color.r += red.r / 3.5;\n" +
            "    color.a += red.a / 7.0;\n" +
            "\n" +
            "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n" +
            "    color.r += orange.r / 3.5;\n" +
            "    color.g += orange.g / 7.0;\n" +
            "    color.a += orange.a / 7.0;\n" +
            "\n" +
            "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n" +
            "    color.r += yellow.r / 3.5;\n" +
            "    color.g += yellow.g / 3.5;\n" +
            "    color.a += yellow.a / 7.0;\n" +
            "\n" +
            "    half4 green = content.eval(refractedCoord);\n" +
            "    color.g += green.g / 3.5;\n" +
            "    color.a += green.a / 7.0;\n" +
            "\n" +
            "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n" +
            "    color.g += cyan.g / 3.5;\n" +
            "    color.b += cyan.b / 3.0;\n" +
            "    color.a += cyan.a / 7.0;\n" +
            "\n" +
            "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n" +
            "    color.b += blue.b / 3.0;\n" +
            "    color.a += blue.a / 7.0;\n" +
            "\n" +
            "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n" +
            "    color.r += purple.r / 7.0;\n" +
            "    color.b += purple.b / 3.0;\n" +
            "    color.a += purple.a / 7.0;\n" +
            "\n" +
            "    return color;\n" +
            "}\n";

    /* ==== Shaders.kt COLOR_CONTROLS_SHADER（verbatim）====
       brightness 在线性（gamma 2.2）空间，contrast/saturation 在 sRGB 空间。 */
    public static final String COLOR_CONTROLS =
            "uniform shader child;\n" +
            "uniform float in_brightness;\n" +
            "uniform float in_contrast;\n" +
            "uniform float in_saturation;\n" +
            "\n" +
            "half4 main(float2 xy) {\n" +
            "    half4 src = child.eval(xy);\n" +
            "    half a = src.a;\n" +
            "    if (a < 0.001) return src;\n" +
            "\n" +
            "    half3 c = src.rgb / a;\n" +
            "\n" +
            "    if (in_brightness != 0.0) {\n" +
            "        c = pow(c, half3(2.2));\n" +
            "        if (in_brightness > 0.0) {\n" +
            "            c = mix(c, half3(1.0), half(in_brightness));\n" +
            "        } else {\n" +
            "            c = c * half(1.0 + in_brightness);\n" +
            "        }\n" +
            "        c = pow(c, half3(0.45454545));\n" +
            "    }\n" +
            "\n" +
            "    if (in_contrast != 1.0) {\n" +
            "        c = (c - 0.5) * half(in_contrast) + 0.5;\n" +
            "    }\n" +
            "\n" +
            "    if (in_saturation != 1.0) {\n" +
            "        half lum = dot(c, half3(0.2126, 0.7152, 0.0722));\n" +
            "        c = mix(half3(lum), c, half(in_saturation));\n" +
            "    }\n" +
            "\n" +
            "    c = clamp(c, half3(0.0), half3(1.0));\n" +
            "    return half4(c * a, a);\n" +
            "}\n";

    /* ==== Shaders.kt NOISE_DITHER_SHADER（verbatim）==== */
    public static final String NOISE_DITHER =
            "uniform shader child;\n" +
            "uniform float noise_coeff;\n" +
            "\n" +
            "half4 main(float2 xy) {\n" +
            "    float3 d = float3(\n" +
            "        dot(xy, float2(6.9898, 78.233)),\n" +
            "        dot(xy, float2(7.9898, 78.233)),\n" +
            "        dot(xy, float2(8.9898, 78.233))\n" +
            "    );\n" +
            "    float3 n = (fract(sin(d) * float3(43734.5453, 43745.5453, 43767.5453)) - 0.5) * noise_coeff;\n" +
            "    half4 color = child.eval(xy);\n" +
            "    color.rg += half2(n.x);\n" +
            "    color.rb += half2(n.y);\n" +
            "    color.gb += half2(n.z);\n" +
            "    return color;\n" +
            "}\n";

    /* ==== Shaders.kt buildBloomStrokeShader(dualPeak = true) 展开（verbatim）====
       cornerRadii 顺序 = [TL, TR, BL, BR]（HighlightStyle.pickRadius 约定）。
       合成用 BlendMode.PLUS（Compose BlendMode.Plus → android PorterDuff.ADD 同义）。 */
    public static final String BLOOM_STROKE_DUAL =
            "uniform float2 halfView;\n" +
            "uniform float2 halfViewFloor;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float strokeWidth;\n" +
            "uniform float innerBlurRadius;\n" +
            "uniform float innerBlurRadiusSq;\n" +
            "uniform float highlightAlpha;\n" +
            "\n" +
            "layout(color) uniform half4 strokeColor;\n" +
            "uniform float strokeAlphaMul;\n" +
            "\n" +
            "uniform float3 lightDir1;\n" +
            "layout(color) uniform half4 lightColor1;\n" +
            "uniform float lightIntensity1;\n" +
            "\n" +
            "uniform float3 lightDir2;\n" +
            "layout(color) uniform half4 lightColor2;\n" +
            "uniform float lightIntensity2;\n" +
            "\n" +
            "float pickRadius(float2 fragCoord, float4 radii) {\n" +
            "    float2 up = fragCoord.y < halfView.y ? radii.xy : radii.zw;\n" +
            "    return fragCoord.x < halfView.x ? up.x : up.y;\n" +
            "}\n" +
            "\n" +
            "// caller passes non-negative pos (already abs-folded), so skip the redundant abs.\n" +
            "float roundedBoxSDF(float2 pos, float2 halfSize, float radius) {\n" +
            "    radius = min(radius, min(halfSize.x, halfSize.y));\n" +
            "    float2 d = pos - halfSize + radius;\n" +
            "    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - radius;\n" +
            "}\n" +
            "\n" +
            "float3 getNormal(float2 fragCoord, float sdf, float R) {\n" +
            "    float2 xy = fragCoord - halfViewFloor;\n" +
            "    float2 xy_a = abs(xy);\n" +
            "    float t = smoothstep(-innerBlurRadius, 0.0, sdf);\n" +
            "    float z = sqrt(max(innerBlurRadiusSq - t * t, 0.0));\n" +
            "    float3 coord = float3(xy_a, -z);\n" +
            "\n" +
            "    float2 corner = halfView - R;\n" +
            "    corner.x = min(corner.x, xy_a.x);\n" +
            "    corner.y = min(corner.y, xy_a.y);\n" +
            "\n" +
            "    float2 dir = normalize(coord.xy - corner.xy);\n" +
            "    corner += dir * (R - innerBlurRadius);\n" +
            "\n" +
            "    if (any(lessThan(xy_a, corner))) {\n" +
            "        return float3(0.0, 0.0, -1.0);\n" +
            "    }\n" +
            "\n" +
            "    float2 signal = sign(xy);\n" +
            "    float3 n = normalize(coord - float3(corner, 0.0));\n" +
            "    n.xy *= signal;\n" +
            "    return n;\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 fragCoord) {\n" +
            "    float2 xy = abs(fragCoord - halfView);\n" +
            "\n" +
            "    float originRadius = pickRadius(fragCoord, cornerRadii);\n" +
            "    float R = max(originRadius, innerBlurRadius);\n" +
            "\n" +
            "    if (all(lessThan(xy, halfView - R))) {\n" +
            "        return half4(0.0);\n" +
            "    }\n" +
            "\n" +
            "    float sdf = roundedBoxSDF(xy, halfView, originRadius);\n" +
            "    half outMask = half(smoothstep(0.0, -1.0, sdf));\n" +
            "    float strokeAlpha = smoothstep(-strokeWidth, -strokeWidth + 1.0, sdf);\n" +
            "\n" +
            "    // Native: stroke = uStrokeColor * sa; result += stroke.rgb * stroke.a\n" +
            "    //       = strokeColor.rgb * strokeColor.a * sa^2\n" +
            "    half3 rgb = strokeColor.rgb * half(strokeAlphaMul * strokeAlpha * strokeAlpha);\n" +
            "\n" +
            "    float3 n = getNormal(fragCoord, sdf, R);\n" +
            "    float l1 = dot(n.xy, lightDir1.xy);\n" +
            "    rgb += half(l1 * l1 * lightIntensity1) * lightColor1.rgb;\n" +
            "    float l2 = dot(n.xy, lightDir2.xy);\n" +
            "    rgb += half(l2 * l2 * lightIntensity2) * lightColor2.rgb;\n" +
            "    return half4(rgb * half(highlightAlpha), 1.0) * outMask;\n" +
            "}\n";

    /* CombinedBackdrop 的双层合成已移到 LensBarView.buildCombined()（CPU 预烘焙
       单张位图）：嵌套 RuntimeShader 在部分 Adreno 驱动上绘制期链接失败，且
       try/catch 抓不到。NOISE_DITHER 同样退役，抖动量级随 CPU 霜链完成。 */
}
