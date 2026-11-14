package com.netops.handbook.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * OpsColors（2026-11-14 M2-2）：全站色板单源，1:1 对齐 NativeBlocks.Palette
 * （01-tokens.css 两套配色；direct = 直铺——有壁纸，页底透明+卡片半透明）。
 *
 * ThemeBus/DarkAware 22 处 register/unregister 在 Compose 侧消亡：读
 * LocalOpsColors 的组件随 (isDark, direct) 自动重组。
 */
data class OpsColors(
    val dark: Boolean,
    val direct: Boolean,
    val bg: Color,
    val cardBg: Color,
    val border: Color,
    val hairline: Color,
    val textMain: Color,
    val textSub: Color,
    val textMute: Color,
    val accent: Color,
    val chipBgOn: Color,
    val chipBgOff: Color,
    val noteInfo: Color,
    val noteWarn: Color,
    val noteDanger: Color,
    val noteOk: Color,
    val notePlain: Color,
    val codeBg: Color,
    val codeLabel: Color,
)

/** 明暗×direct 四象限色板（值逐条对齐 NativeBlocks.palette）。 */
fun opsColors(dark: Boolean, direct: Boolean): OpsColors {
    val c = if (dark) OpsColors(
        dark = true, direct = direct,
        bg = Color(0xFF0C0D10),
        cardBg = Color(0xFF1C1C1E),
        border = Color(0xFF38383A),
        hairline = Color(0xFF303032),
        textMain = Color(0xFFF5F5F7),
        textSub = Color(0xFFC7C7CC),
        textMute = Color(0xFF8E8E93),
        accent = Color(0xFF0A84FF),
        chipBgOn = Color(0x2E0A84FF),        // α46
        chipBgOff = Color(0xFF2C2C2E),
        noteInfo = Color(0x200A84FF),        // α32
        noteWarn = Color(0x26F59E0B),        // α38
        noteDanger = Color(0x26EF4444),      // α38
        noteOk = Color(0x2622C55E),          // α38
        notePlain = Color(0xFF2C2C2E),
        codeBg = Color(0xFF18181B),
        codeLabel = Color(0xFF94A3B8),
    ) else OpsColors(
        dark = false, direct = direct,
        bg = Color(0xFFF2F2F7),
        cardBg = Color.White,
        border = Color(0xFFE3E3E8),
        hairline = Color(0xFFEBEBF0),
        textMain = Color(0xFF1D1D1F),
        textSub = Color(0xFF515154),
        textMute = Color(0xFF8E8E93),
        accent = Color(0xFF2563EB),
        chipBgOn = Color(0x1C2563EB),        // α28
        chipBgOff = Color(0xFFF1F1F5),
        noteInfo = Color(0x142563EB),        // α20
        noteWarn = Color(0x1AF59E0B),        // α26
        noteDanger = Color(0x1AEF4444),      // α26
        noteOk = Color(0x1A22C55E),          // α26
        notePlain = Color(0xFFF6F6F8),
        codeBg = Color(0xFF0F172A),
        codeLabel = Color(0xFF94A3B8),
    )
    return if (!direct) c else c.copy(
        bg = Color.Transparent,
        cardBg = if (dark) Color(0xD61C1C1E) else Color(0xD6FFFFFF),   // α214
        border = if (dark) Color(0x28FFFFFF) else Color(0x24000000),   // α40/α36
    )
}

/** 页面组件读取入口（M3 起逐页替换 NativeBlocks.Palette）。 */
val LocalOpsColors = staticCompositionLocalOf { opsColors(dark = false, direct = false) }
