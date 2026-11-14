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
 * 页面路由总出口（2026-11-14 M3 终局）：GlassDockHost 组合内的兄弟节点层，
 * 按 AppShellState.pageRoute when 切页——退役 11 个独立 ComposeView 页宿主。
 *
 * 背景：MagicOS 上 AndroidView 内嵌套 ComposeView 内容不合成（三连实验+
 * M3 首验空屏实锤），页面必须与 dock 同层组合；dock 玻璃 record 的对象
 * 是 AndroidView（壁纸层），页面为兄弟节点不参与 record。
 * 未迁移页（search=View 版）经 nativeSearch.show() 走 View 层。
 */
@Composable
fun PagesOverlay(
    data: () -> org.json.JSONObject?,
    onGo: (String, String) -> Unit,
    onExport: () -> Unit, onImport: () -> Unit,
    onState: (String, String, Boolean) -> Unit,
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
    }
}


/** Java 桥用配置对象（fault/iv 两页共用 DocListPage）。 */
val FaultCfg = DocListConfig("fault", "排障字典", "案例", "已解决", "faults")
val IvCfg = DocListConfig("iv", "面试题库", "真题", "已掌握", "interview", "IVQ", "Q", true)
