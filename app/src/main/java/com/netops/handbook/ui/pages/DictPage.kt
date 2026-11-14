package com.netops.handbook.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.netops.handbook.ui.OpsChevron
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.PageData
import org.json.JSONArray
import org.json.JSONObject

/**
 * 命令字典页（2026-11-14 M3-2⑤）：NativeDictView 的 Compose 版。
 *
 * 形态 = 头卡（计数+免责）+ cat 分组折叠卡（标题行常驻 15.5sp bold + 计数，
 * 点开才组内渲染命令卡——2026-11-07「分组后 99th 65ms」结论保留，Compose 惰性
 * 组合同语义）；命令卡 = 功能名+cat 徽章+四/五平台对照行（label 92dp+值
 * 等宽字体，点行复制+Toast）。
 * 数据 = dict{cols[{k,n}], rows[{fn,cat,hw,cs,zte,lx,win}]}。
 */
@Composable
fun DictPage(dataSupplier: () -> org.json.JSONObject?, dark: Boolean) {
    val colors = LocalOpsColors.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ver = PageData.version
    val parsed = remember(ver) {
        val dict = dataSupplier()?.optJSONObject("dict") ?: JSONObject()
        val colsArr = dict.optJSONArray("cols") ?: JSONArray()
        val keys = ArrayList<Pair<String, String>>()   // k→n（键→显示名）
        for (i in 0 until colsArr.length()) {
            val c = colsArr.optJSONObject(i) ?: continue
            keys.add(c.optString("k") to c.optString("n"))
        }
        val rows = dict.optJSONArray("rows") ?: JSONArray()
        val groups = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            val cat = o.optString("cat").ifEmpty { "未分类" }
            groups.getOrPut(cat) { ArrayList() }.add(o)
        }
        keys to groups
    }
    val colKeys = parsed.first
    val groups = parsed.second
    val expanded = remember(ver) { mutableStateOf(setOf<String>()) }   // 展开的组名集合
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun copyCmd(text: String) {
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(ctx, "已复制：$text", android.widget.Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = statusTop + 84.dp, bottom = navBottom + 84.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OpsCard {
                    Text("命令字典", color = colors.textMain, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "四家平台命令横向对照 · 共 ${groups.values.sumOf { it.size }} 条。命令在不同版本/平台可能有差异，请以官方文档为准。",
                        color = colors.textMute, fontSize = 12.5.sp, lineHeight = 15.5.sp
                    )
                }
            }
            if (groups.isEmpty()) {
                item {
                    Text(
                        "没有匹配的命令", color = colors.textMute, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp)
                    )
                }
            }
            for ((cat, list) in groups) {
                item(key = cat) {
                    val open = expanded.value.contains(cat)
                    OpsCard(Modifier.clickable {
                        expanded.value = if (open) expanded.value - cat else expanded.value + cat
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cat, color = colors.textMain, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text("${list.size} 条", color = colors.textMute, fontSize = 12.sp)
                            }
                            OpsChevron(colors, rotation = if (open) 90f else 0f)
                        }
                        /* 组内命令卡：点开才组内渲染（Compose 惰性组合同语义） */
                        if (open) {
                            Spacer(Modifier.height(8.dp))
                            for (o in list) {
                                DictCmdCard(o, colKeys, colors, ::copyCmd)
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DictCmdCard(o: JSONObject, colKeys: List<Pair<String, String>>, colors: OpsColors, copyCmd: (String) -> Unit) {
    OpsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.optString("fn"), color = colors.textMain, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            val c = o.optString("cat")
            if (c.isNotEmpty()) {
                Text(
                    c, color = colors.accent, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.chipBgOn)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
        for ((k, n) in colKeys) {
            val v = o.optString(k, "")
            val na = v.isEmpty() || v == "-"
            Row(Modifier.padding(top = 7.dp)) {
                Text(
                    n, color = colors.textMute, fontSize = 11.sp,
                    modifier = Modifier.width(92.dp)
                )
                Text(
                    if (na) "不适用" else v,
                    color = if (na) colors.textMute else colors.textMain,
                    fontSize = 12.5.sp,
                    fontFamily = if (na) null else FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (na) Modifier else Modifier.clickable { copyCmd(v) })
                )
            }
        }
    }
}

/** Java 侧挂载入口（Composeable lambda 无法在 Java 源里书写）。 */
fun androidx.compose.ui.platform.ComposeView.setDictContent(
    data: () -> org.json.JSONObject?, dark: () -> Boolean, direct: () -> Boolean,
) {
    setViewCompositionStrategy(
        androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    setContent {
        OpsTheme(dark(), direct()) {
            DictPage(data, dark())
        }
    }
}
