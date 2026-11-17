package com.netops.handbook.ui.pages

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.netops.handbook.ui.AppShellState
import com.netops.handbook.ui.SimpleKind
import com.netops.handbook.ui.SimpleListPage
import com.netops.handbook.ui.CliScreen
import com.netops.handbook.ui.OpsTheme
import org.json.JSONObject

/**
 * 页面路由总出口（2026-11-14 M3 终局；View 层退役轮次修订）：
 * GlassDockHost 内容层 Box 内按 AppShellState.pageRoute when 切页——
 * 退役 11 个独立 ComposeView 页宿主。
 *
 * View 层退役后页面与壁纸 Image 同在一个 record Box 内：dock 玻璃折射
 * 对象 = 壁纸 + 页面本体（KernelSU 同款「滚动实时折射」）。覆盖层
 * （search/settings/drawer=View 版）经 overLayer（ComposeView 之上的
 * FrameLayout 兄弟层）显示，M4 起逐个迁 Compose。
 */
@Composable
fun PagesOverlay(
    data: () -> org.json.JSONObject?,
    onGo: (String, String) -> Unit,
    onExport: () -> Unit, onImport: () -> Unit,
    onState: (String, String, Boolean) -> Unit,
    onSettingsApply: (String, String) -> Unit = { _, _ -> },
    onAlbum: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val route = AppShellState.pageRoute
    val arg = AppShellState.pageArg
    val dark = AppShellState.isDark
    val direct = AppShellState.direct

    when (route) {
        "learn" -> OpsTheme(dark, direct) {
            HomePage(data, dark) { r, a -> onGo(r, a) }
        }
        "phase", "phase-focus" -> {
            val pp = (arg ?: "").split("|")
            val pid = pp[0].ifEmpty { "p-1" }
            val focus = pp.getOrNull(1)?.takeIf { it.isNotEmpty() }
            OpsTheme(dark, direct) {
                PhasePage(pid, focus, data, dark, onState)
            }
        }
        "fault" -> OpsTheme(dark, direct) {
            DocListPage(FaultCfg, data, dark, onState)
        }
        "iv" -> OpsTheme(dark, direct) {
            DocListPage(IvCfg, data, dark, onState)
        }
        "dict" -> OpsTheme(dark, direct) {
            DictPage(data, dark)
        }
        "refs" -> OpsTheme(dark, direct) {
            RefsPage(null, data)
        }
        "ref" -> OpsTheme(dark, direct) {
            RefsPage(arg, data)
        }
        "glossary" -> OpsTheme(dark, direct) {
            SimpleListPage(SimpleKind.Glossary, data, dark)
        }
        "roadmap" -> OpsTheme(dark, direct) {
            SimpleListPage(SimpleKind.Roadmap, data, dark)
        }
        "quiz" -> OpsTheme(dark, direct) {
            QuizPage(data, dark)
        }
        "fav" -> OpsTheme(dark, direct) {
            FavPage(data, dark) { r, a -> onGo(r, a) }
        }
        "me" -> OpsTheme(dark, direct) {
            MePage(data, dark,
                onGo = { r -> if (r == "settings") onGo("settings", "") else onGo(r, "") },
                onExport = onExport, onImport = onImport)
        }
        "cli" -> OpsTheme(dark, direct) {
            CliScreen(data, dark)
        }
        "search" -> OpsTheme(dark, direct) {
            SearchPage(data, dark) { r, a -> onGo(r, a) }
        }
        "settings" -> OpsTheme(dark, direct) {
            SettingsPage(
                data = data, dark = dark,
                onApply = onSettingsApply, onAlbum = onAlbum, onBack = onBack,
            )
        }
        /* 教材接入（2026-11-18）：书架 + 章节阅读（arg = id 或 id@bi 搜索深链） */
        "textbook" -> OpsTheme(dark, direct) {
            TextbookShelf { r, a -> onGo(r, a) }
        }
        "tbread" -> OpsTheme(dark, direct) {
            TextbookReader(arg ?: "", { r, a -> onGo(r, a) })
        }
    }
}


/** Java 桥用配置对象（fault/iv 两页共用 DocListPage）。 */
val FaultCfg = DocListConfig("fault", "排障字典", "案例", "已解决", "faults", "", "", true)
val IvCfg = DocListConfig("iv", "面试题库", "真题", "已掌握", "interview", "IVQ", "Q", true)
