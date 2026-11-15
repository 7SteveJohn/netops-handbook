package com.netops.handbook.lens.glass2

/*
 * Antigravity 磨砂 Tone Lift（85ff84f 存档移植）：
 * 对齐 Apple Materials——压制高频深黑文字，将暗部抬升为干净漫白。
 * R' = 0.55 * R + 115（115/255 ≈ 0.451），纯黑 0 → 115，
 * 彻底洗净细碎黑点/脏污感，大色块保留优雅明亮的漫反射。
 * 输入为 premultiplied，先 unpremultiply 再提亮再 premultiply 回去。
 */
const val TONE_LIFT_SHADER = """
uniform shader content;

half4 main(float2 xy) {
    half4 c = content.eval(xy);
    half3 color = c.rgb / max(c.a, half(0.0001));
    color = color * 0.55 + half(0.451);
    return half4(color * c.a, c.a);
}
"""
