package com.netops.handbook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 壳层状态单源（2026-11-14 M2-2，全 Compose 重构地基②）。
 *
 * 收编 NetOpsShell（壳层桥）与 DockBus 的公共字段：dark 单源化——此前
 * ThemeBus→NetOpsShell.dark 与 DockBus.dark 双份，漏通知风险两处；现在
 * ThemeBus 只写这里，两个旧 object 的字段改为委托（Java 侧调用点零改动）。
 *
 * 迁移期约定：Java/旧 View 消费者继续读 NetOpsShell/DockBus（委托透传），
 * 新 Compose 消费者一律读本对象；M6 拆除时旧 object 随 View 层退役。
 */
object AppShellState {

    /** 深色模式（真源 = 设置项 auto/light/dark，auto 才读系统；ThemeBus 写这里）。 */
    var isDark by mutableStateOf(false)

    /** 直铺模式（有壁纸：页底透明+卡片半透明，Palette direct 轴）。 */
    var direct by mutableStateOf(false)

    /** dock 选中 tab（0..4；与路由解耦——phase/refs 等子路由仍在 learn tab）。 */
    var selected by mutableIntStateOf(0)

    /** 抽屉/搜索打开 → dock/顶栏退位。 */
    var overlaid by mutableStateOf(false)

    /** 顶栏标题/副标题/返回态（Router.apply 直写）。 */
    var title by mutableStateOf("NetOps 2.0")
    var subtitle by mutableStateOf("网络学习辅助手册")
    var navBack by mutableStateOf(false)

    /** M3-1：SimpleListPage 当前路由（"glossary"/"roadmap"/null——共用一个
     *  Compose 页宿主，按此切内容）。 */
    var simpleRoute by mutableStateOf<String?>(null)

    /** M3-2：速查单表深链（ref 路由 arg；null=列表模式）。 */
    var refsDetail by mutableStateOf<String?>(null)

    /** M3-2⑦：阶段页当前 pid/focus（Router 动作表写入）。 */
    var phasePid by mutableStateOf("p-1")
    var phaseFocus by mutableStateOf<String?>(null)
}

/** native-data 装载版本源（M3：Compose 页据此重读数据；MainActivity 装载完成时 bump）。 */
object PageData {
    @Volatile var dataRoot: org.json.JSONObject? = null
    var version by androidx.compose.runtime.mutableIntStateOf(0)

    @JvmStatic fun setRoot(r: org.json.JSONObject) { dataRoot = r; version++ }
    @JvmStatic fun bumpVersion() { version++ }
}
