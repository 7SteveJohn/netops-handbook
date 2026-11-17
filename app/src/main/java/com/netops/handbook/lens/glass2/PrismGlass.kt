package com.netops.handbook.lens.glass2

/*
 * AGSL 移植自 prism_ui(shaders/liquid_glass.frag,FlClash 液态玻璃真源,
 * Apache-2.0),保留 dock 用到的路径:圆形圆角 + lens/bevel 光学 + 雾合成 +
 * 色散 + rim 高光 + 暗线 + endShade + contrast clash + glow。裁剪:
 * outline/goo/pills/menu-shine(dock 不用);superellipse 圆角走库内圆形回退。
 *
 * 与原版的坐标差异:原版 uFrame/uSample/uClamp 做 atlas 槽位映射,雾由宿主
 * 预模糊进 atlas;本移植 content = 节点自绘的清晰捕获(单输入),雾在
 * shader 内以 Vogel 盘采样(64 tap,同原 sampleFrosted),节点按雾 reach 外扩。
 */

const val PRISM_GLASS = """
uniform shader content;   // RuntimeShaderEffect 固定以 content 绑定节点内容
uniform float2 uNodeOrigin;   // 节点(含外扩)原点,窗口 px
uniform float2 uGlassTL;      // 玻璃左上,窗口 px
uniform float2 uNodeSize;     // 节点尺寸 px
uniform float uDp;            // px per dp
uniform float4 uShape;        // w, h, bevel, frost (dp)
uniform float4 uCorners;      // 圆角半径 dp
uniform float3 uOptics;       // refraction(<0 = lens), saturation, brightness
uniform float4 uTint;         // 0..1 straight alpha
uniform float3 uLight;        // rim, rimWidth(fade dp), endShade
uniform float4 uGlow;         // center xy (glass dp), strength, contrast
uniform float2 uGlowSpread;   // reach, wash
uniform float3 uEdge;         // line alpha, channel part dp, rim frost dp
uniform float4 uRim;          // tail, focus, added(light), side line
uniform float uHaze;          // 0..1 雾份额
uniform float uHazeBlur;      // 雾 sigma dp

const float3 kLuma = float3(0.2126, 0.7152, 0.0722);
const float kGoldenAngle = 2.39996322973;
const float kFrostReach = 2.07846096908;
const float kLine = 0.67;
const float kLineCap = 0.298;
const float kCornerGap = 0.03;
const float kEndShade = 3.0;
const float kPartReach = 10.0;
const float kPartHold = 7.0;
const float kRimTail = 4.5;

float2 clampTexel(float2 windowPx) {
    return clamp(windowPx - uNodeOrigin, float2(0.5), uNodeSize - 0.5);
}

half4 texel(float2 windowPx) {
    return content.eval(clampTexel(windowPx));
}

half4 sampleGlass(float2 point) {
    return texel(uGlassTL + point * uDp);
}

// 引擎高斯核在 Vogel 盘上的读取(同原 FROST_DISC 宏;AGSL 循环上限须常量,
// 故按原宏展开成三份字面量版本)
half4 frostDisc16(float2 center, float2 reach, float falloff) {
    float decay = exp(-falloff / 16.0);
    float weight = sqrt(decay);
    float2 turn = float2(cos(kGoldenAngle), sin(kGoldenAngle));
    float2 ray = float2(1.0, 0.0);
    half4 sum = half4(0.0);
    float total = 0.0;
    for (int i = 0; i < 16; i++) {
        float spread = sqrt((float(i) + 0.5) / 16.0);
        sum += texel(uNodeOrigin + center + ray * reach * spread) * weight;
        total += weight;
        weight *= decay;
        ray = float2(ray.x * turn.x - ray.y * turn.y,
                     ray.x * turn.y + ray.y * turn.x);
    }
    return half4(sum.rgb / total, sum.a / total);
}

half4 frostDisc32(float2 center, float2 reach, float falloff) {
    float decay = exp(-falloff / 32.0);
    float weight = sqrt(decay);
    float2 turn = float2(cos(kGoldenAngle), sin(kGoldenAngle));
    float2 ray = float2(1.0, 0.0);
    half4 sum = half4(0.0);
    float total = 0.0;
    for (int i = 0; i < 32; i++) {
        float spread = sqrt((float(i) + 0.5) / 32.0);
        sum += texel(uNodeOrigin + center + ray * reach * spread) * weight;
        total += weight;
        weight *= decay;
        ray = float2(ray.x * turn.x - ray.y * turn.y,
                     ray.x * turn.y + ray.y * turn.x);
    }
    return half4(sum.rgb / total, sum.a / total);
}

half4 frostDisc64(float2 center, float2 reach, float falloff) {
    float decay = exp(-falloff / 64.0);
    float weight = sqrt(decay);
    float2 turn = float2(cos(kGoldenAngle), sin(kGoldenAngle));
    float2 ray = float2(1.0, 0.0);
    half4 sum = half4(0.0);
    float total = 0.0;
    for (int i = 0; i < 64; i++) {
        float spread = sqrt((float(i) + 0.5) / 64.0);
        sum += texel(uNodeOrigin + center + ray * reach * spread) * weight;
        total += weight;
        weight *= decay;
        ray = float2(ray.x * turn.x - ray.y * turn.y,
                     ray.x * turn.y + ray.y * turn.x);
    }
    return half4(sum.rgb / total, sum.a / total);
}

half4 sampleFrosted(float2 point, float frostDp) {
    float2 sigma = float2(frostDp * uDp);
    float2 reach = max(sigma - 0.5, float2(0.0)) * kFrostReach;
    if (reach.x <= 0.0 || reach.y <= 0.0) {
        return sampleGlass(point);
    }
    float2 ratio = reach / sigma;
    float falloff = 0.5 * ratio.x * ratio.y;
    float2 center = uGlassTL + point * uDp - uNodeOrigin;
    float widest = max(sigma.x, sigma.y);
    if (widest <= 2.0 * uDp) {
        return frostDisc16(center, reach, falloff);
    }
    if (widest <= 3.5 * uDp) {
        return frostDisc32(center, reach, falloff);
    }
    return frostDisc64(center, reach, falloff);
}

half4 sampleHaze(float2 point) {
    return sampleFrosted(point, uHazeBlur);
}

half4 sampleBehind(float2 point) {
    if (uHaze > 0.0) {
        return sampleHaze(point);
    }
    return sampleFrosted(point, uShape.w);
}

float roundedBoxDistance(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - halfSize + radius;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
}

float2 roundedBoxNormal(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - halfSize + radius;
    float2 n = q.x > 0.0 && q.y > 0.0
        ? normalize(q)
        : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    return n * float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
}

half4 main(float2 fragCoord) {
    // 玻璃 dp 坐标,自玻璃左上
    float2 point = (fragCoord + uNodeOrigin - uGlassTL) / uDp;
    float2 halfSize = uShape.xy * 0.5;
    float halfMinor = min(halfSize.x, halfSize.y);
    float2 centered = point - halfSize;

    float2 side = step(0.0, centered);
    float radius = min(
        mix(mix(uCorners.x, uCorners.y, side.x),
            mix(uCorners.w, uCorners.z, side.x), side.y),
        halfMinor);
    float inset = max(-roundedBoxDistance(centered, halfSize, radius), 0.0);
    float2 normal = roundedBoxNormal(centered, halfSize, radius);
    float edge = inset;

    /* 形状外碎片原样透出(FlClash 节点=玻璃精确矩形无此区;我方节点外扩采样
       pad,形状外若继续走全管线会被 rim 暗线+shine 染成脏晕环——2026-11-15
       按压药丸灰晕环实证)。1:1 透 content=与页面像素重合=不可见。 */
    if (roundedBoxDistance(centered, halfSize, radius) > 0.0) {
        return content.eval(fragCoord);
    }

    bool lens = uOptics.x < 0.0;
    float bevel = max(min(uShape.z, halfMinor * 0.5), 1e-3);
    float rise = 1.0 - clamp(inset / bevel, 0.0, 1.0);
    float2 shiftDp;
    if (lens) {
        // Liquid Glass 抬起镜:沿法线向外,rim 处为 0、一个 bevel 处最深,向中部缓退
        shiftDp = normal * -uOptics.x
            * smoothstep(0.0, bevel, inset)
            * (1.0 - smoothstep(bevel, halfMinor, inset));
    } else {
        // 圆形 bevel:贴面平坦、向 rim 陡化,最外带呈挤压镜像
        float depth = min(uOptics.x, halfMinor) * (1.0 - sqrt(1.0 - rise * rise));
        shiftDp = -normal * depth;
    }
    float2 shift = shiftDp * uDp;

    float frost = uShape.w + uEdge.z * rise * rise;
    float2 windowPx = uGlassTL + point * uDp;
    half4 base = sampleFrosted(windowPx + shift, frost);
    if (uEdge.y > 0.0 && inset < kPartReach) {
        // Liquid Glass 沿法线镜像分色:两侧横向、圆角纵向,rim 处最宽向内收
        float2 part = normal.yx * 0.5 * uEdge.y
            * (0.6 + 0.4 * abs(normal.y) * max(1.0 - inset / kPartHold, 0.0))
            * (1.0 - smoothstep(kPartHold, kPartReach, inset));
        half4 red = sampleFrosted(windowPx + shift + part * uDp, frost);
        half4 blue = sampleFrosted(windowPx + shift - part * uDp, frost);
        base = half4(red.r, base.g, blue.b,
            max(base.a, max(red.a, blue.a)));
    }
    if (uHaze > 0.0) {
        base = mix(base, sampleHaze(windowPx + shift), uHaze);
    }
    // capture 全窗不透明,无 veil 留空
    float alpha = base.a;
    float3 color = base.rgb / max(alpha, 1e-4);
    color = mix(float3(dot(color, kLuma)), color, uOptics.y) * uOptics.z;
    color *= 1.0 - uLight.z * abs(normal.x) * exp(-inset / kEndShade);

    // tint 与背景亮暗对撞:内容远深于 tint 时加厚(可读性),远浅时反向
    float clash = 0.0;
    if (uGlow.w != 0.0) {
        half4 behind = sampleBehind(halfSize)
            + sampleBehind(halfSize * float2(0.4, 0.6))
            + sampleBehind(halfSize * float2(1.6, 0.6))
            + sampleBehind(halfSize * float2(0.4, 1.4))
            + sampleBehind(halfSize * float2(1.6, 1.4));
        float behindLuma = dot(behind.rgb / max(behind.a, 1e-4), kLuma);
        clash = clamp(
            (sign(uGlow.w) * (dot(uTint.rgb, kLuma) - behindLuma) - 0.3) / 0.5,
            0.0, 1.0);
    }
    float cover = uTint.a + (1.0 - uTint.a) * clash * abs(uGlow.w);
    float coverage = alpha * (1.0 - cover) + cover;
    float3 glass = color * alpha * (1.0 - cover) + uTint.rgb * cover;

    // iOS 沿 rim 上下打光,tab bar 在圆端以法线上扬分量平方收尾
    float within = max(edge - kLine, 0.0);
    float facing;
    if (lens) {
        facing = smoothstep(0.65, 0.95, abs(normal.y));
    } else {
        float rise2 = normal.y * normal.y;
        float rise4 = rise2 * rise2;
        float rise16 = rise4 * rise4 * rise4 * rise4;
        facing = mix(rise2, 0.5 * rise2 * abs(normal.y) * (1.0 + rise16), uRim.y);
    }
    float band = clamp(1.0 - within / max(uLight.y, 1e-3), 0.0, 1.0);
    float tail = clamp(1.0 - max(inset - kLine, 0.0) / kRimTail, 0.0, 1.0);
    float shine = min(uLight.x * facing * mix(band, tail, uRim.x), 1.0);
    glass += (float3(coverage) - (1.0 - uRim.z) * glass) * shine;

    // Liquid Glass 的 rim 暗线:画在最外像素,两侧最深
    float line = clamp((kLine - edge) / (1.0 / uDp) + 0.5, 0.0, 1.0);
    float across = abs(normal.x);
    float darken = mix(uEdge.x, uRim.w, across * across * across);
    if ((uEdge.x > 0.0 || uRim.w > 0.0) && line > 0.0) {
        half4 under = sampleGlass(point);
        float3 dark = max(under.rgb - kLineCap * under.a, 0.22 * under.rgb);
        glass = mix(glass, mix(under.rgb, dark, darken), line);
        coverage = mix(coverage, under.a, line);
    }

    // 指尖发光:高斯洗过整条栏,可达分量铺满
    float2 toGlow = point - uGlow.xy;
    float reach = max(uGlowSpread.x * uGlowSpread.x, 1.0);
    glass += uGlow.z * coverage
        * mix(exp(-dot(toGlow, toGlow) / reach), 1.0, uGlowSpread.y);

    return half4(min(glass, float3(coverage)), coverage);
}
"""
