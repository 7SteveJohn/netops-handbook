package com.netops.handbook.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.AppShellState
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.glassChipSurface
import com.netops.handbook.ui.glassClickable
import com.netops.handbook.ui.isOpsCompactLandscape
import com.netops.handbook.ui.isOpsLandscape
import com.netops.handbook.ui.rememberOpsPagePadding
import org.json.JSONObject

/**
 * 设置页 Compose 版（2026-11-16 M4-3 / 2026-11-18 液态玻璃分栏卡片与紧凑横屏适配）。
 *
 * 完整保留四项真源能力：外观主题（自动/浅色/深色）、背景壁纸（分组三列网格 + 横竖屏 pair 自动切换
 * + 相册自定义壁纸）、底栏样式（悬浮胶囊/原生贴底）、液态玻璃三档（无/标准/极致）。
 * 横屏优化：自动适配紧凑 48dp 顶栏与挖孔安全区（无导轨偏移），采用左控制卡 + 右壁纸网格双栏布局。
 */
@Composable
fun SettingsPage(
    data: () -> JSONObject?,
    dark: Boolean,
    onApply: (String, String) -> Unit,
    onAlbum: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalOpsColors.current

    val sp = remember {
        context.getSharedPreferences("netops.shell", android.content.Context.MODE_PRIVATE)
    }
    /* 本地镜像（乐观更新）：SP 无 Compose 通知，apply 后手动置值即时驱动芯片态 */
    var themeSel by remember { mutableStateOf(sp.getString("theme", "auto") ?: "auto") }
    var dockSel by remember {
        mutableStateOf(if (sp.getBoolean("dockstyle_docked", false)) "regular" else "float")
    }
    var glassSel by remember {
        mutableStateOf(
            AppShellState.glassLevel.ifEmpty {
                com.netops.handbook.lens.NativeBlocks.GLASS_LEVEL.ifEmpty { "ultra" }
            }
        )
    }
    val shellGlass = AppShellState.glassLevel
    LaunchedEffect(shellGlass) {
        if (shellGlass.isNotEmpty() && glassSel != shellGlass) {
            glassSel = shellGlass
        }
    }
    val wallpaperSel = com.netops.handbook.lens.WallpaperPrefs.select

    fun apply(kind: String, value: String) {
        when (kind) {
            "theme" -> themeSel = value
            "dockstyle" -> dockSel = value
            "glasslevel" -> glassSel = value
            "wallpaper" -> Unit
        }
        onApply(kind, value)
    }

    BackHandler { onBack() }

    val compactLand = isOpsCompactLandscape()
    val land = isOpsLandscape()
    val pagePad = rememberOpsPagePadding(
        extraTop = if (compactLand) 8.dp else 12.dp,
        extraBottom = 28.dp,
        horizontal = 16.dp,
        hasDock = false,
        hasTopBar = true,
    )

    val root = data()
    val wallpaperGroups = remember(root) {
        val wallpapers = root?.optJSONArray("wallpapers")
        val groups = linkedMapOf<String, MutableList<JSONObject>>()
        if (wallpapers != null) {
            for (i in 0 until wallpapers.length()) {
                val wp = wallpapers.optJSONObject(i) ?: continue
                if ("landscape" == wp.optString("orientation")) continue
                var cat = wp.optString("cat", "其他")
                if (cat.contains("二次元")) cat = "二次元壁纸（自适应横竖屏）"
                else if (cat.contains("简约")) cat = "简约质感（自适应横竖屏）"
                groups.getOrPut(cat) { mutableListOf() }.add(wp)
            }
        }
        groups
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
            .verticalScroll(rememberScrollState())
            .padding(pagePad),
        contentAlignment = Alignment.TopCenter
    ) {
        if (land) {
            /* 横屏双栏：左栏（主题 + 底栏样式 + 液态玻璃三档），右栏（自适应壁纸网格 + 相册自定义入口） */
            Row(
                modifier = Modifier
                    .widthIn(max = 920.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(0.44f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsControlCards(
                        colors = colors,
                        themeSel = themeSel,
                        dockSel = dockSel,
                        glassSel = glassSel,
                        onApply = ::apply
                    )
                }
                Column(
                    modifier = Modifier.weight(0.56f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsWallpaperCard(
                        colors = colors,
                        wallpaperGroups = wallpaperGroups,
                        wallpaperSel = wallpaperSel,
                        isLandscape = true,
                        onApply = ::apply,
                        onAlbum = onAlbum
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsControlCards(
                    colors = colors,
                    themeSel = themeSel,
                    dockSel = dockSel,
                    glassSel = glassSel,
                    onApply = ::apply
                )
                SettingsWallpaperCard(
                    colors = colors,
                    wallpaperGroups = wallpaperGroups,
                    wallpaperSel = wallpaperSel,
                    isLandscape = false,
                    onApply = ::apply,
                    onAlbum = onAlbum
                )
            }
        }
    }
}

@Composable
private fun SettingsControlCards(
    colors: OpsColors,
    themeSel: String,
    dockSel: String,
    glassSel: String,
    onApply: (String, String) -> Unit,
) {
    OpsCard(colors = colors) {
        SettingsCardTitle(colors, "外观主题", "跟随系统日夜或手动锁定")
        Spacer(Modifier.height(8.dp))
        ChipRow(
            colors = colors,
            options = listOf("auto" to "自动", "light" to "浅色", "dark" to "深色"),
            current = themeSel,
            equalWeight = true
        ) { onApply("theme", it) }
    }

    OpsCard(colors = colors) {
        SettingsCardTitle(colors, "底栏样式", "横屏悬浮模式自动切换为左侧玻璃导轨")
        Spacer(Modifier.height(8.dp))
        ChipRow(
            colors = colors,
            options = listOf("float" to "悬浮胶囊", "regular" to "原生贴底"),
            current = dockSel,
            equalWeight = true
        ) { onApply("dockstyle", it) }
    }

    val glassSubtitle = when (glassSel) {
        "none" -> "纯平实底 · 关闭实时模糊、折射与动态波纹（最省电）"
        "standard" -> "温润云母磨砂 · 柔雾护眼、柔和微弹与均衡功耗"
        else -> "通透冕牌水晶 · 全凸镜水滴折光、彩虹色散与景深透射"
    }
    OpsCard(colors = colors) {
        SettingsCardTitle(colors, "液态玻璃", glassSubtitle)
        Spacer(Modifier.height(8.dp))
        ChipRow(
            colors = colors,
            options = listOf("none" to "无", "standard" to "标准", "ultra" to "极致"),
            current = glassSel,
            equalWeight = true
        ) { onApply("glasslevel", it) }
    }
}

@Composable
private fun SettingsWallpaperCard(
    colors: OpsColors,
    wallpaperGroups: Map<String, List<JSONObject>>,
    wallpaperSel: String,
    isLandscape: Boolean,
    onApply: (String, String) -> Unit,
    onAlbum: () -> Unit,
) {
    OpsCard(colors = colors) {
        SettingsCardTitle(colors, "背景壁纸（自适应横竖屏）", "点击即时预览玻璃透色效果")
        Spacer(Modifier.height(8.dp))
        ChipRow(
            colors = colors,
            options = listOf("none" to "无壁纸（纯净实底）"),
            current = wallpaperSel,
            equalWeight = false
        ) { onApply("wallpaper", it) }

        for ((cat, list) in wallpaperGroups) {
            Text(
                cat,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textMute,
                modifier = Modifier.padding(top = 10.dp, bottom = 5.dp)
            )
            val rows = list.chunked(3)
            rows.forEachIndexed { rIdx, rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (i in 0 until 3) {
                        val wp = rowItems.getOrNull(i)
                        Box(Modifier.weight(1f)) {
                            if (wp != null) {
                                val idP = wp.optString("id")
                                val idL = wp.optString("pair")
                                val active = wallpaperSel == idP || (idL.isNotEmpty() && wallpaperSel == idL)
                                GlassChip(
                                    colors = colors,
                                    text = wp.optString("name"),
                                    active = active,
                                    center = true,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    onApply(
                                        "wallpaper",
                                        if (isLandscape && idL.isNotEmpty()) idL else idP
                                    )
                                }
                            }
                        }
                    }
                }
                if (rIdx < rows.lastIndex) {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        val isCustom = "custom" == wallpaperSel
        Text(
            if (isCustom) "✓ 更换自定义壁纸（从相册选取）" else "从相册选取自定义壁纸",
            fontSize = 12.5.sp,
            color = if (isCustom) Color.White else colors.accent,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .glassChipSurface(colors, active = isCustom, shape = RoundedCornerShape(12.dp))
                .glassClickable { onAlbum() }
                .padding(horizontal = 14.dp, vertical = 11.dp)
        )
    }
}

@Composable
private fun SettingsCardTitle(colors: OpsColors, title: String, sub: String) {
    Column {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textMain)
        Spacer(Modifier.height(2.dp))
        Text(sub, fontSize = 11.sp, color = colors.textMute)
    }
}

@Composable
private fun ChipRow(
    colors: OpsColors,
    options: List<Pair<String, String>>,
    current: String,
    equalWeight: Boolean,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (key, label) ->
            val active = key == current
            GlassChip(
                colors = colors,
                text = label,
                active = active,
                center = true,
                modifier = if (equalWeight) Modifier.weight(1f) else Modifier
            ) {
                if (!active) onPick(key)
            }
        }
    }
}

@Composable
private fun GlassChip(
    colors: OpsColors,
    text: String,
    active: Boolean,
    center: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        color = if (active) Color.White else colors.textSub,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .glassChipSurface(colors, active = active, shape = RoundedCornerShape(11.dp))
            .glassClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.5.dp),
        textAlign = if (center) TextAlign.Center else TextAlign.Start
    )
}
