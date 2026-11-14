package com.netops.handbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 术语表 / 学习路线图页（2026-11-14 M3-1）：NativeSimpleList.java 的 Compose 版。
 *
 * 形态 = 单张大卡（19sp 标题 + 12.5sp 副标题 + 0.7dp 分割线 + 行卡 11dp 上下距），
 * 「一张大卡只套一层玻璃」的 2026-11-07 结论保留——glass2 时代由 dock 栏体玻璃
 * 承担折射，页面本体不再逐卡包裹（glassWrap 退役裁定见 M2-3）。
 *
 * 数据：native-data 根的 glossary/roadmap 数组（{t,d} 形，PageData.version 驱动
 * 重读）；SafeArea = View 版 setSafeArea 同款魔数（顶栏 56+28 / dock 84）；
 * 滚动恢复暂缺（View 版存 px，M3 收口统一 rememberSaveable + LazyListState）。
 */
enum class SimpleKind(val route: String, val title: String, val subHead: String, val unit: String) {
    Glossary("glossary", "术语表", "按字母/拼音排列", "个术语"),
    Roadmap("roadmap", "学习路线图", "从打地基到云原生", "个阶段"),
}

@Composable
fun SimpleListPage(kind: SimpleKind, dataSupplier: () -> org.json.JSONObject?, dark: Boolean) {
    val colors = LocalOpsColors.current
    val ver = PageData.version
    val adapted = remember(kind, ver) {
        val root = dataSupplier()
        val arr = root?.optJSONArray(kind.route) ?: JSONArray()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o.optString("t") to o.optString("d"))
        }
        out
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(
                top = statusTop + 84.dp,     // 56(顶栏)+28(呼吸) 魔数同 View 版
                bottom = navBottom + 84.dp   // dock 玻璃让位同款
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .imePadding()
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item {
                OpsCard {
                    Text(kind.title, color = colors.textMain, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "共 ${adapted.size} ${kind.unit} · ${kind.subHead}",
                        color = colors.textMute, fontSize = 12.5.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    for ((t, d) in adapted) {
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(0.7.dp)
                                .background(if (colors.dark) Color(0x1AFFFFFF) else Color(0x14000000))
                        )
                        Column(Modifier.padding(vertical = 11.dp)) {
                            Text(t, color = colors.textMain, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            if (d.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(d, color = colors.textSub, fontSize = 13.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Java 侧挂载入口（Composeable lambda 无法在 Java 源里书写）。 */
fun androidx.compose.ui.platform.ComposeView.setSimpleContent(
    data: () -> org.json.JSONObject?, dark: () -> Boolean, direct: () -> Boolean,
    route: () -> String?,
) {
    setViewCompositionStrategy(
        androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    setContent {
        val r = route()
        val k = when (r) {
            "glossary" -> SimpleKind.Glossary
            "roadmap" -> SimpleKind.Roadmap
            else -> null
        } ?: return@setContent
        OpsTheme(dark(), direct()) {
            SimpleListPage(k, data, dark())
        }
    }
}
