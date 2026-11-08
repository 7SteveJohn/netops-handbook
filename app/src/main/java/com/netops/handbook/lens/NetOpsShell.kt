package com.netops.handbook.lens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Java(MainActivity) ↔ Compose(dock/顶栏) 的双向状态桥。
 * MainActivity 写 selected/title 等；Compose 点击通过 onCommit/onXxxClick 回 Java。
 */
object NetOpsShell {
    var selected by mutableIntStateOf(0)          // dock 选中 tab（0..4）
    var dark by mutableStateOf(false)             // 深色模式
    var overlaid by mutableStateOf(false)         // 抽屉/搜索打开 → dock/顶栏退位
    var nativeBar by mutableStateOf(true)         // 原生接管总开关
    var title by mutableStateOf("NetOps 2.0")
    var subtitle by mutableStateOf("")
    var onCommit: ((Int) -> Unit)? = null
    var onNavClick: (() -> Unit)? = null
    var onSearchClick: (() -> Unit)? = null
    var onThemeClick: (() -> Unit)? = null
    var onTopButton: ((Int) -> Unit)? = null
}
