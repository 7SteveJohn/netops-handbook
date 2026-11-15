package com.netops.handbook.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.glassPressFeedback
import com.netops.handbook.ui.glassSpecularBorder
import com.netops.handbook.ui.opsColors
import org.json.JSONObject

/**
 * M4-3 设置页 Compose 版（2026-11-16）：NativeSettingsPanel(342 行 View)退役位，
 * 路由化（M4-1 search 同模式——原「覆盖层特例」改走 Router.onNavigate→pagesGo）。
 *
 * 结构 1:1 平移（View 版全屏实色直铺口径）：header(设置|完成) + 外观主题 /
 * 背景壁纸(分组三列网格+横竖 pair 切换) / 底栏样式 / 液态玻璃三档 + 相册入口。
 *
 * 设置四项真源=SP/WallpaperPrefs（M1c 原生收口），本页只发 apply(kind,value)
 * 给宿主（复用原 setter 链），本地 State 乐观更新驱动 chip 高亮（SP 无通知）。
 *
 * 视觉=Step A 原语落地首站：行卡/选项 chip 挂 glassSpecularBorder（四段镜面
 * 描边）+ glassPressFeedback（按压微缩+聚光）——LiquidGlassAndroid-v2
 * SettingsScreen 形态对齐。
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
    /* 设置面板=导航页必须实色（View 版 palette(context, dark, false) 同口径）。 */
    val colors = remember(dark) { opsColors(dark, false) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val sp = remember {
        context.getSharedPreferences("netops.shell", android.content.Context.MODE_PRIVATE)
    }
    /* 本地镜像（乐观更新）：SP 无 Compose 通知，apply 后手动置值刷 chip。 */
    var themeSel by remember { mutableStateOf(sp.getString("theme", "auto") ?: "auto") }
    var dockSel by remember {
        mutableStateOf(if (sp.getBoolean("dockstyle_docked", false)) "regular" else "float")
    }
    var glassSel by remember {
        mutableStateOf(com.netops.handbook.lens.NativeBlocks.GLASS_LEVEL.ifEmpty { "ultra" })
    }
    val wallpaperSel = com.netops.handbook.lens.WallpaperPrefs.select   // State 重组 ✓

    fun apply(kind: String, value: String) {
        when (kind) {
            "theme" -> themeSel = value
            "dockstyle" -> dockSel = value
            "glasslevel" -> glassSel = value
            "wallpaper" -> Unit   // WallpaperPrefs.select 自带 State
        }
        onApply(kind, value)
    }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .pointerInput(Unit) { detectTapGestures { } }   // 吞掉透传（View 版点空白收起已由 route/back 语义替代）
            .padding(top = statusTop + 84.dp, bottom = 84.dp)
    ) {
        /* header：设置 | 完成（View 版 headerRow 同构） */
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.textMain)
            Text(
                "完成", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            SettingsSection(colors, "外观主题")
            ChipRow(
                colors = colors,
                options = listOf("auto" to "自动", "light" to "浅色", "dark" to "深色"),
                current = themeSel
            ) { apply("theme", it) }

            SettingsSection(colors, "背景壁纸（自适应横竖屏）")
            ChipRow(
                colors = colors,
                options = listOf("none" to "无壁纸"),
                current = wallpaperSel
            ) { apply("wallpaper", it) }

            /* 壁纸分组三列网格（View 版 build() 分组逻辑平移；cat 二次元/简约
               挂「自适应横竖屏」尾缀；横屏走 pair 配对 id）。 */
            val root = data()
            val wallpapers = root?.optJSONArray("wallpapers")
            if (wallpapers != null) {
                val groups = linkedMapOf<String, MutableList<JSONObject>>()
                for (i in 0 until wallpapers.length()) {
                    val wp = wallpapers.optJSONObject(i) ?: continue
                    if ("landscape" == wp.optString("orientation")) continue
                    var cat = wp.optString("cat", "其他")
                    if (cat.contains("二次元")) cat = "二次元壁纸（自适应横竖屏）"
                    else if (cat.contains("简约")) cat = "简约质感（自适应横竖屏）"
                    groups.getOrPut(cat) { mutableListOf() }.add(wp)
                }
                val isLandscape = context.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val sel = wallpaperSel
                for ((cat, list) in groups) {
                    Text(
                        cat, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = colors.textMute,
                        modifier = Modifier.padding(top = 8.dp, bottom = 3.dp)
                    )
                    list.chunked(3).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth()) {
                            for (i in 0 until 3) {
                                val wp = rowItems.getOrNull(i)
                                Box(Modifier.weight(1f).padding(start = if (i > 0) 4.dp else 0.dp)) {
                                    if (wp != null) {
                                        val idP = wp.optString("id")
                                        val idL = wp.optString("pair")
                                        val active = sel == idP || (idL.isNotEmpty() && sel == idL)
                                        GlassChip(
                                            colors = colors,
                                            text = wp.optString("name"),
                                            active = active,
                                            center = true,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            apply(
                                                "wallpaper",
                                                if (isLandscape && idL.isNotEmpty()) idL else idP
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            SettingsSection(colors, "底栏样式")
            ChipRow(
                colors = colors,
                options = listOf("float" to "悬浮胶囊", "regular" to "原生贴底"),
                current = dockSel
            ) { apply("dockstyle", it) }

            SettingsSection(colors, "液态玻璃")
            ChipRow(
                colors = colors,
                options = listOf("none" to "无", "standard" to "标准", "ultra" to "极致"),
                current = glassSel
            ) { apply("glasslevel", it) }

            /* 相册自定义壁纸入口（View 版 album 行同构）。 */
            Text(
                if ("custom" == wallpaperSel) "更换自定义壁纸（从相册选取）"
                else "从相册选取自定义壁纸",
                fontSize = 12.5.sp, color = colors.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.chipBgOff)
                    .clickable { onAlbum() }
                    .padding(vertical = 12.dp)
                    .then(Modifier)
            )
        }
    }
}

@Composable
private fun SettingsSection(colors: OpsColors, title: String) {
    Text(
        title, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = colors.textMute,
        modifier = Modifier.padding(top = 9.dp, bottom = 5.dp)
    )
}

/** 选项 chip 行（View 版 chipRow 同构；chip 带镜面描边+按压反馈）。 */
@Composable
private fun ChipRow(
    colors: OpsColors,
    options: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (key, label) ->
            val active = key == current
            GlassChip(
                colors = colors,
                text = label,
                active = active,
                center = false,
                modifier = Modifier
                    .padding(start = if (i > 0) 8.dp else 0.dp)
            ) {
                if (!active) onPick(key)
            }
        }
    }
}

/** 选项 chip：accent 实底选中 / chipBgOff 未选 + glassSpecularBorder + pressFeedback。 */
@Composable
private fun GlassChip(
    colors: OpsColors,
    text: String,
    active: Boolean,
    center: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text,
        fontSize = 12.5.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) Color.White else colors.textSub,
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (active) colors.accent else colors.chipBgOff)
            .glassSpecularBorder(
                shape = RoundedCornerShape(13.dp),
                width = 0.75.dp,
                intensity = if (active) 0.9f else 1.0f
            )
            .glassPressFeedback(interactionSource = interaction)
            .clickable(
                interactionSource = interaction,
                indication = null
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center
        else androidx.compose.ui.text.style.TextAlign.Start
    )
}
