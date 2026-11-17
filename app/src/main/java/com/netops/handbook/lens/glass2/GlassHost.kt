package com.netops.handbook.lens.glass2

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.netops.handbook.ui.AppShellState
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.components.FloatingGlassDock
import com.netops.handbook.ui.components.LiquidGlassTopBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * P1 宿主（Compose 统一 LayerBackdrop 液态玻璃架构）：
 * 一次捕获页面与高清壁纸层，供吸顶栏、空间磨砂抽屉与底部/侧边液态玻璃导航栏零延迟同帧模糊与折射采样。
 */
interface GlassHostHooks {
    fun onGo(route: String, arg: String?)
    fun onExport()
    fun onImport()
    fun onSettings()
    fun onState(id: String, kind: String, on: Boolean)
    fun onDrawerClosed()
    fun onSettingsApply(kind: String, value: String)
    fun onAlbum()
    fun onBack()
}

fun ComponentActivity.installGlassHost(
    under: View,
    over: View,
    hooks: GlassHostHooks,
): ComposeView {
    val holder = FrameLayout(this)
    setContentView(holder)
    holder.addView(
        under,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
    val composeA = newComposeView()
    holder.addView(
        composeA,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
    holder.addView(
        over,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
    composeA.setContent {
        val pageBackdrop = rememberLayerBackdrop()
        val dark = AppShellState.isDark
        val direct = AppShellState.direct
        val cfg = LocalConfiguration.current
        val layoutDirection = LocalLayoutDirection.current
        val isCompactLandscape = cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenHeightDp < 480
        val cutoutStart = WindowInsets.displayCutout.asPaddingValues().calculateStartPadding(layoutDirection)
        val navPadding = WindowInsets.navigationBars.asPaddingValues()
        val navStart = navPadding.calculateStartPadding(layoutDirection)
        val navBottom = navPadding.calculateBottomPadding()
        val safeStart = maxOf(cutoutStart, navStart)

        OpsTheme(dark, direct) {
            Box(Modifier.fillMaxSize()) {
                // 1. 内容宿主：全屏高清壁纸 + 页面列表，统一挂载 layerBackdrop(pageBackdrop)
                GlassContentHost(backdrop = pageBackdrop) {
                    com.netops.handbook.ui.pages.PagesOverlay(
                        data = { com.netops.handbook.ui.PageData.dataRoot },
                        onGo = { r, a -> hooks.onGo(r, a) },
                        onExport = { hooks.onExport() },
                        onImport = { hooks.onImport() },
                        onState = { id, kind, on -> hooks.onState(id, kind, on) },
                        onSettingsApply = { k, v -> hooks.onSettingsApply(k, v) },
                        onAlbum = { hooks.onAlbum() },
                        onBack = { hooks.onBack() },
                    )
                }

                // 2. MagicOS 11 空间液态毛玻璃抽屉覆盖层
                com.netops.handbook.ui.pages.DrawerOverlay(
                    data = { com.netops.handbook.ui.PageData.dataRoot },
                    dark = dark,
                    onGo = { r, a -> hooks.onGo(r, a) },
                    onClosed = { hooks.onDrawerClosed() },
                    backdrop = pageBackdrop,
                )

                // 3. iOS 26 + MagicOS 11 渐进模糊顶栏与悬浮玻璃控制胶囊
                val route = AppShellState.pageRoute
                val hideTopBar = AppShellState.overlaid || AppShellState.drawerOpen || route == "cli"
                if (!hideTopBar) {
                    LiquidGlassTopBar(
                        backdrop = pageBackdrop,
                        onNavClick = {
                            if (AppShellState.navBack) {
                                hooks.onBack()
                            } else {
                                AppShellState.drawerOpen = true
                            }
                        },
                        onSearchClick = {
                            hooks.onGo("search", "")
                        },
                        onThemeToggle = {
                            val next = if (AppShellState.isDark) "light" else "dark"
                            hooks.onSettingsApply("theme", next)
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                // 4. iOS 26 / FlClash 液态玻璃导航栏（紧凑横屏且非贴底时锚定左侧居中偏下导轨）
                val hideDock = AppShellState.overlaid || AppShellState.drawerOpen ||
                    route == "cli" || route == "search" || route == "settings"
                if (!hideDock) {
                    val isDocked = AppShellState.dockDocked
                    val dockModifier = when {
                        isDocked -> Modifier.align(Alignment.BottomCenter)
                        isCompactLandscape -> {
                            val railStartMargin = if (safeStart > 0.dp) safeStart + 8.dp else 12.dp
                            Modifier
                                .align(Alignment.CenterStart)
                                .statusBarsPadding()
                                .padding(start = railStartMargin, top = 48.dp, bottom = navBottom)
                        }
                        else -> Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 8.dp)
                    }
                    FloatingGlassDock(
                        backdrop = pageBackdrop,
                        selectedIndex = AppShellState.selected,
                        onSelect = { idx ->
                            val r = when (idx) {
                                1 -> "fault"
                                2 -> "dict"
                                3 -> "me"
                                else -> "learn"
                            }
                            hooks.onGo(r, null)
                        },
                        modifier = dockModifier,
                    )
                }
            }
        }
    }

    return composeA
}

private fun ComponentActivity.newComposeView(): ComposeView {
    val v = ComposeView(this)
    v.setViewCompositionStrategy(
        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    return v
}

/** 内容树：高清壁纸同画布直绘 + 挂载全局 LayerBackdrop 捕获源。 */
@Composable
private fun GlassContentHost(
    backdrop: LayerBackdrop,
    pages: @Composable () -> Unit,
) {
    val bmp = com.netops.handbook.lens.WallpaperPrefs.wallBitmap
    val wallImage = androidx.compose.runtime.remember(bmp) { bmp?.asImageBitmap() }
    val darkWallFilter = androidx.compose.runtime.remember {
        ColorFilter.colorMatrix(
            ColorMatrix().apply {
                setToScale(0.45f, 0.45f, 0.45f, 1f)
            }
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width.coerceAtLeast(1f)
                    val h = size.height.coerceAtLeast(1f)
                    val maxDim = maxOf(w, h)
                    val topRightCenter = Offset(w * 0.84f, h * 0.16f)
                    val topRightRadius = (maxDim * 0.68f).coerceAtLeast(8f)
                    val bottomLeftCenter = Offset(w * 0.16f, h * 0.80f)
                    val bottomLeftRadius = (maxDim * 0.66f).coerceAtLeast(8f)

                    // iOS 26 空间极光环境微晕（按尺寸在 drawWithCache 零分配预构建，仅无壁纸且 ultra 档启用）
                    val lightIceAurora = Brush.radialGradient(
                        0.00f to Color(0xFF38BDF8).copy(alpha = 0.11f),
                        0.42f to Color(0xFF2563EB).copy(alpha = 0.07f),
                        0.76f to Color(0xFF38BDF8).copy(alpha = 0.022f),
                        1.00f to Color.Transparent,
                        center = topRightCenter,
                        radius = topRightRadius,
                    )
                    val darkIceAurora = Brush.radialGradient(
                        0.00f to Color(0xFF38BDF8).copy(alpha = 0.16f),
                        0.42f to Color(0xFF2563EB).copy(alpha = 0.10f),
                        0.76f to Color(0xFF1D4ED8).copy(alpha = 0.032f),
                        1.00f to Color.Transparent,
                        center = topRightCenter,
                        radius = topRightRadius,
                    )
                    val lightDawnAurora = Brush.radialGradient(
                        0.00f to Color(0xFFA855F7).copy(alpha = 0.09f),
                        0.45f to Color(0xFFF472B6).copy(alpha = 0.056f),
                        0.78f to Color(0xFFC084FC).copy(alpha = 0.018f),
                        1.00f to Color.Transparent,
                        center = bottomLeftCenter,
                        radius = bottomLeftRadius,
                    )
                    val darkDawnAurora = Brush.radialGradient(
                        0.00f to Color(0xFFA855F7).copy(alpha = 0.13f),
                        0.45f to Color(0xFFF472B6).copy(alpha = 0.082f),
                        0.78f to Color(0xFF9333EA).copy(alpha = 0.026f),
                        1.00f to Color.Transparent,
                        center = bottomLeftCenter,
                        radius = bottomLeftRadius,
                    )

                    onDrawWithContent {
                        val darkNow = AppShellState.isDark
                        val ultraNow = AppShellState.glassLevel == "ultra"
                        // 保底实色底，确保 LayerBackdrop 任意像素 alpha=1.0 不透明
                        drawRect(if (darkNow) Color(0xFF0C0D10) else Color(0xFFF2F2F7))
                        if (wallImage != null) {
                            val s = maxOf(size.width / wallImage.width, size.height / wallImage.height)
                            val srcW = (size.width / s).toInt().coerceAtMost(wallImage.width)
                            val srcH = (size.height / s).toInt().coerceAtMost(wallImage.height)
                            drawImage(
                                image = wallImage,
                                srcOffset = IntOffset(
                                    (wallImage.width - srcW) / 2, (wallImage.height - srcH) / 2
                                ),
                                srcSize = IntSize(srcW, srcH),
                                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                colorFilter = if (darkNow) darkWallFilter else null,
                                filterQuality = FilterQuality.Medium,
                            )
                        } else if (ultraNow) {
                            // 无壁纸默认态下切至「极致」：加绘右上冰晶蓝 + 左下晨曦紫粉空间极光微晕
                            drawRect(brush = if (darkNow) darkIceAurora else lightIceAurora)
                            drawRect(brush = if (darkNow) darkDawnAurora else lightDawnAurora)
                        }
                        drawContent()
                    }
                }
        ) {
            pages()
        }
    }
}
