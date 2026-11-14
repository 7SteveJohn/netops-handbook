package com.netops.handbook.lens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Java(MainActivity) ↔ Compose(dock/顶栏) 的双向状态桥。
 * MainActivity 写 selected/title 等；Compose 点击通过 onCommit/onXxxClick 回 Java。
 *
 * M2-2(2026-11-14)：dark 单源化——委托 AppShellState.isDark（ThemeBus 只写
 * 单源）；其余字段渐进收编（M6 拆除时本 object 随 View 层退役）。
 */
object NetOpsShell : ThemeBus.DarkAware {
    var selected by mutableIntStateOf(0)          // dock 选中 tab（0..4）
    var dark: Boolean
        get() = com.netops.handbook.ui.AppShellState.isDark
        set(v) { com.netops.handbook.ui.AppShellState.isDark = v }
    var overlaid by mutableStateOf(false)         // 抽屉/搜索打开 → dock/顶栏退位
    var nativeBar by mutableStateOf(true)         // 原生接管总开关
    var title by mutableStateOf("NetOps 2.0")
    var subtitle by mutableStateOf("")
    var onCommit: ((Int) -> Unit)? = null
    var onNavClick: (() -> Unit)? = null
    var onSearchClick: (() -> Unit)? = null
    var onThemeClick: (() -> Unit)? = null
    var onTopButton: ((Int) -> Unit)? = null

    init {
        // 单例进程级注册一次：主题真源交给 ThemeBus，register 自带当前值回调
        ThemeBus.register(this)
    }

    /* ThemeBus 订阅入口：只换通知来源（原 NetOpsShell.setDark 属性写值不变）。 */
    override fun onThemeChanged(d: Boolean) { dark = d }
}
