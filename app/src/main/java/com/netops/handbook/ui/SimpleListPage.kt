package com.netops.handbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 术语表 / 学习路线图页（iOS 26 + MagicOS 11 液态玻璃与横屏双列网格升级版）：
 * - 接入 `rememberOpsPagePadding()`：自动避让横屏左侧竖排玻璃导轨、紧凑 48dp 顶栏与挖孔安全区；
 * - 逐项惰性卡片化：竖屏单列空间玻璃卡、横屏双列等分卡片网格（每行 2 卡），彻底摆脱全量同步组合；
 * - 序号与分类统计标签统一挂载 `glassBadgeSurface`。
 */
enum class SimpleKind(val route: String, val title: String, val subHead: String, val unit: String) {
    Glossary("glossary", "术语表", "按字母/拼音排列", "个术语"),
    Roadmap("roadmap", "学习路线图", "从打地基到云原生", "个阶段"),
}

private val SimpleTagShape = RoundedCornerShape(9.dp)

@Composable
fun SimpleListPage(kind: SimpleKind, dataSupplier: () -> JSONObject?, dark: Boolean) {
    val colors = LocalOpsColors.current
    val ver = PageData.version
    val adapted = remember(kind, ver) {
        val root = dataSupplier()
        val arr = root?.optJSONArray(kind.route) ?: JSONArray()
        val out = ArrayList<Pair<String, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o.optString("t") to o.optString("d"))
        }
        out
    }

    val pagePadding = rememberOpsPagePadding()
    val landscape = isOpsLandscape()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header_${kind.route}") {
            OpsCard(colors = colors) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        kind.title,
                        color = colors.textMain,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "共 ${adapted.size} ${kind.unit}",
                        color = colors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .glassBadgeSurface(colors, SimpleTagShape)
                            .padding(horizontal = 8.5.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    kind.subHead,
                    color = colors.textMute,
                    fontSize = 12.5.sp
                )
            }
        }

        if (landscape) {
            val rowCount = (adapted.size + 1) / 2
            items(
                count = rowCount,
                key = { rowIdx -> "${kind.route}_row_$rowIdx" }
            ) { rowIdx ->
                val leftIdx = rowIdx * 2
                val rightIdx = leftIdx + 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val leftItem = adapted[leftIdx]
                    SimpleEntryCard(
                        kind = kind,
                        index = leftIdx,
                        title = leftItem.first,
                        desc = leftItem.second,
                        colors = colors,
                        modifier = Modifier.weight(1f)
                    )
                    if (rightIdx < adapted.size) {
                        val rightItem = adapted[rightIdx]
                        SimpleEntryCard(
                            kind = kind,
                            index = rightIdx,
                            title = rightItem.first,
                            desc = rightItem.second,
                            colors = colors,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            items(
                count = adapted.size,
                key = { idx -> "${kind.route}_item_$idx" }
            ) { idx ->
                val item = adapted[idx]
                SimpleEntryCard(
                    kind = kind,
                    index = idx,
                    title = item.first,
                    desc = item.second,
                    colors = colors,
                )
            }
        }
    }
}

@Composable
private fun SimpleEntryCard(
    kind: SimpleKind,
    index: Int,
    title: String,
    desc: String,
    colors: OpsColors,
    modifier: Modifier = Modifier,
) {
    val badgeLabel = if (kind == SimpleKind.Roadmap) {
        "阶段 %02d".format(index + 1)
    } else {
        "%02d".format(index + 1)
    }
    OpsCard(modifier = modifier, colors = colors) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                badgeLabel,
                color = colors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .glassBadgeSurface(colors, SimpleTagShape)
                    .padding(horizontal = 7.5.dp, vertical = 3.5.dp)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                title,
                color = colors.textMain,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
        if (desc.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            Text(
                desc,
                color = colors.textSub,
                fontSize = 13.sp,
                lineHeight = 17.5.sp
            )
        }
    }
}

/** Java 侧挂载入口（Composeable lambda 无法在 Java 源里书写）。 */
fun androidx.compose.ui.platform.ComposeView.setSimpleContent(
    data: () -> JSONObject?,
    dark: () -> Boolean,
    direct: () -> Boolean,
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
