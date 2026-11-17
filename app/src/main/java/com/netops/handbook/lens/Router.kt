package com.netops.handbook.lens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 原生路由器（2026-11-14 M2-1，全 Compose 重构地基）。
 *
 * 语义 1:1 移植自 JS 路由真源 32-boot.js:15-327（META/go/goTab/back），
 * 拆除「原生→JS→原生」导航环：nativeGo 不再 evaluateJavascript，
 * 由本对象直接驱动 switchNativeTo（Bridge 出口端映射见 NativeRoutes）。
 *
 * 过渡期（WebView 仍在树）：JS 降级为状态存储服务（localStorage 读写被
 * NetOpsNativeSet/NetOpsOnWallpaper 等继续使用），导航权归本 Router；
 * JS 自身 render 不再被触发（初始化时的一次派发与 Router 幂等短路）。
 *
 * 栈语义（照搬 JS）：go 入栈上限 40（超 shift）；goTab 清栈回根；back 弹栈、
 * 跳过僵尸 search 条目（搜索态已被 go 收掉的历史项）、栈空时非根回根 tab。
 */
object Router {

    /* ---- 路由元数据（32-boot.js META 14 条原样） ---- */
    data class Meta(
        val route: String,
        val title: String,
        val sub: String,
        val tab: String,      // 所属底部 tab（根路由 = 自身）
        val accent: String
    )

    val META: Map<String, Meta> = listOf(
        Meta("learn", "NetOps 2.0", "网络学习辅助手册", "learn", "teal"),
        Meta("fault", "排障字典", "现象 → 根因 → 命令 → 验证", "fault", "rose"),
        Meta("dict", "命令字典", "华为 / Cisco / 中兴 / Linux", "dict", "blue"),
        Meta("iv", "面试题库", "高频真题与 STAR 话术", "me", "amber"),
        Meta("me", "我的", "进度 · 收藏 · 设置", "me", "teal"),
        Meta("phase", "阶段详情", "", "learn", "teal"),
        Meta("phase-focus", "阶段详情", "", "learn", "teal"),
        Meta("refs", "速查表", "面试前十分钟", "learn", "blue"),
        Meta("ref", "速查表", "", "learn", "blue"),
        Meta("glossary", "术语词典", "网络与云原生黑话", "learn", "purple"),
        Meta("roadmap", "学习路线图", "0-12 个月成长节奏", "learn", "teal"),
        Meta("quiz", "模拟测验", "随机抽题 · 自动判分", "iv", "purple"),
        Meta("cli", "CLI 模拟器", "离线命令沙盒", "learn", "emerald"),
        Meta("fav", "收藏夹", "", "me", "amber"),
        Meta("search", "搜索结果", "", "learn", "teal"),
        Meta("settings", "设置", "主题 · 壁纸 · 备份", "me", "teal"),
        /* 教材接入（2026-11-18）：书架/阅读两路由（PagesOverlay + MainActivity 同步接线） */
        Meta("textbook", "运维教材全书", "12 领域 · 50 章 · 全文可搜", "learn", "amber"),
        Meta("tbread", "教材阅读", "", "learn", "amber")
    ).associateBy { it.route }

    /* ---- 当前状态（Compose State：顶栏/壳层直接订阅） ---- */
    /* M1d-3：@JvmField 供 Java(MainActivity) 字段式访问（route/arg 由 Java lambda 读）。 */
    data class State(@JvmField val route: String, @JvmField val arg: String?)

    var current by mutableStateOf(State("learn", null))
        private set
    var title by mutableStateOf(META["learn"]!!.title)
        private set
    var subtitle by mutableStateOf(META["learn"]!!.sub)
        private set
    var navBack by mutableStateOf(false)
        private set

    private val stack = ArrayDeque<State>()
    private val MAX_STACK = 40
    private val scrollMem = HashMap<String, Int>()   // keyOf → 滚动位置

    /** 宿主动作出口（MainActivity 接线：驱动 switchNativeTo/页面显隐）。 */
    @JvmField
    var onNavigate: ((State, restoreScroll: Boolean) -> Unit)? = null

    /** 滚动位置存取（页面侧提供；过渡期 View 页由 NativeScrollView 喂）。 */
    var scrollProvider: (() -> Int)? = null
    var scrollRestorer: ((Int) -> Unit)? = null

    /** 覆盖层收口钩子（搜索条/sheet/drawer——宿主按原优先级实现，Router 只问）。 */
    @JvmField var overlayCloser: (() -> Boolean)? = null        // 返回 true = 有覆盖层被收
    @JvmField var cardCloser: (() -> Boolean)? = null           // 展开卡片收起

    private fun keyOf(st: State) = st.route + ":" + (st.arg ?: "")
    private fun isRoot(r: String) = META[r]?.let { it.tab == it.route } ?: false

    private fun apply(st: State, restoreScroll: Boolean) {
        val m = META[st.route] ?: META["learn"]!!
        title = m.title
        subtitle = m.sub
        navBack = !isRoot(st.route)
        onNavigate?.invoke(st, restoreScroll)
    }

    private fun rememberScroll() {
        scrollProvider?.let { scrollMem[keyOf(current)] = it() }
    }

    /* ---- go（32-boot.js:257） ---- */
    @JvmStatic fun go(route: String, arg: String? = null, replace: Boolean = false) {
        val r = if (META.containsKey(route)) route else "learn"
        val st = State(r, arg)
        if (st == current) {
            apply(st, false)
            return
        }
        rememberScroll()
        if (!replace) {
            stack.addLast(current)
            if (stack.size > MAX_STACK) stack.removeFirst()
        }
        current = st
        apply(st, false)
    }

    /* ---- goTab（32-boot.js:274）：清栈回根，回顶不恢复历史位置 ---- */
    fun goTab(tab: String, restorePos: Boolean = false) {
        if (current.route == tab) return
        rememberScroll()
        stack.clear()
        current = State(tab, null)
        apply(current, restorePos)
    }

    /* ---- back（32-boot.js:298）。返回 true = 已消费 ---- */
    @JvmStatic fun back(): Boolean {
        overlayCloser?.let { if (it()) return true }
        cardCloser?.let { if (it()) return true }
        if (stack.isEmpty()) {
            if (!isRoot(current.route)) {
                goTab(META[current.route]?.tab ?: "learn", restorePos = true)
                return true
            }
            return false
        }
        rememberScroll()
        var st = stack.removeLast()
        /* 僵尸 search 条目跳穿（搜索态已被 go 收掉的历史项） */
        while (st.route == "search" && stack.isNotEmpty()) st = stack.removeLast()
        if (st.route == "search") {
            goTab(META[st.route]?.tab ?: "learn", restorePos = true)
            return true
        }
        current = st
        apply(st, true)
        return true
    }

    /* ---- 深链解析（#route/arg，32-boot.js:2841 同语义） ---- */
    fun resolveHash(hash: String): State? {
        val h = hash.removePrefix("#").trim()
        if (h.isEmpty()) return null
        val parts = h.split('/')
        val m = META[parts[0]] ?: return null
        return State(m.route, parts.getOrNull(1)?.takeIf { it.isNotEmpty() })
    }

    /** 当前路由 hash（syncHash 等价物，调试/恢复用）。 */
    fun hashOf(st: State = current) =
        "#" + st.route + (st.arg?.let { "/$it" } ?: "")

    /** 滚动恢复值（页面迁移期由 Compose rememberSaveable 接管后逐步退役）。 */
    fun restoreScrollFor(st: State): Int = scrollMem[keyOf(st)] ?: 0
}
