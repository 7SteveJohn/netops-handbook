package com.netops.handbook.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * CLI 模拟器页（2026-11-14 M1b，全 Compose）：31-views.js V.cli + 05-views.css
 * CLI 段的 Compose 版。终端流（五色行 + 600 行钳制）+ 平台提示符 + 输入行 +
 * 快捷 chips（QUICK_GROUPS 分组 ×COMMON_CMDS 过滤，renderQuick 同款）。
 *
 * 状态：历史 50 条持久化（SP netops_shell/cli_hist——备份裸键兼容，M1d 接入
 * 导出/导入）；模式 cliMode 会话内（与 JS 一致不持久化）；命令库 CliEngine 懒加载。
 */

/* 终端色板（01-tokens.css --term-*：text 系明暗同值，dark 换 bg 系） */
private data class TermColors(
    val bg: Color, val bg2: Color, val border: Color, val text: Color,
    val green: Color, val cyan: Color, val rose: Color, val dim: Color,
)

private fun termColors(dark: Boolean) = if (dark) TermColors(
    bg = Color(0xFF05090F), bg2 = Color(0xFF020509), border = Color(0xFF1C2942),
    text = Color(0xFFE2E8F0), green = Color(0xFF4ADE80), cyan = Color(0xFF22D3EE),
    rose = Color(0xFFFB7185), dim = Color(0xFF64748B),
) else TermColors(
    bg = Color(0xFF0B1120), bg2 = Color(0xFF060B16), border = Color(0xFF1E293B),
    text = Color(0xFFE2E8F0), green = Color(0xFF4ADE80), cyan = Color(0xFF22D3EE),
    rose = Color(0xFFFB7185), dim = Color(0xFF64748B),
)

private fun lineColor(c: TermColors, cls: String): Color = when (cls) {
    "cmd" -> c.text; "ok" -> c.green; "err" -> c.rose; "info" -> c.cyan; else -> c.dim
}

private const val CLI_SCROLLBACK = 600

/** Java 侧挂载入口（Composeable lambda 无法在 Java 源里书写）。 */
fun androidx.compose.ui.platform.ComposeView.setCliContent(
    dataSupplier: () -> JSONObject?, dark: () -> Boolean,
) {
    setViewCompositionStrategy(
        androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    setContent { CliScreen(dataSupplier, dark()) }
}

@Composable
fun CliScreen(dataSupplier: () -> JSONObject?, dark: Boolean) {
    val ctx = LocalContext.current
    val tc = termColors(dark)
    val colors = LocalOpsColors.current

    val db = remember { CliEngine.build(dataSupplier() ?: JSONObject()) }
    val lines = remember { mutableStateListOf<CliEngine.Line>() }
    var mode by remember { mutableStateOf("hw") }
    var input by remember { mutableStateOf("") }
    var quickGroup by remember { mutableStateOf("all") }
    val hist = remember {
        mutableStateListOf<String>().apply {
            val sp = ctx.getSharedPreferences("netops_shell", Context.MODE_PRIVATE)
            val raw = sp.getString("cli_hist", null)
            if (raw != null) {
                try {
                    val arr = org.json.JSONArray(raw)
                    for (i in 0 until arr.length()) add(arr.optString(i))
                } catch (_: Throwable) { }
            }
        }
    }
    val listState = rememberLazyListState()

    fun print(text: String, cls: String) {
        lines.add(CliEngine.Line(text, cls))
        while (lines.size > CLI_SCROLLBACK) lines.removeAt(0)
    }

    /* 开场（initCli 同款）+ M1a 承诺的对账断言：条数与门禁 738 一致 */
    LaunchedEffect(Unit) {
        if (db.size != 738) {
            android.util.Log.w("CliScreen", "命令库条数 ${db.size} 与门禁 738 不一致——数据管道漂移")
        }
        print("NetOps CLI 模拟器 v2.0 · 完全离线 · 命令库就绪 · ${db.size} 条命令", "info")
        print("输入 help 查看分组帮助，mode hw|cs|zte|lx 切换平台，前缀输入可联想命令。", "dim")
    }
    /* 新行自动滚底（键盘弹出/输出追加） */
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    fun run(raw: String) {
        val v = raw.trim()
        if (v.isEmpty()) return
        hist.add(v)
        while (hist.size > 50) hist.removeAt(0)
        ctx.getSharedPreferences("netops_shell", Context.MODE_PRIVATE)
            .edit().putString("cli_hist", org.json.JSONArray(hist).toString()).apply()
        input = ""
        print("${CliEngine.promptOf(mode)} $v", "cmd")
        val r = CliEngine.exec(db, v, mode)
        if (r.clear) lines.clear()
        for (l in r.lines) print(l.text, l.cls)
        r.mode?.let { if (it != mode) mode = it }
    }

    val common = remember(quickGroup) {
        val arr = JSONObject().let { o ->
            /* common 从 db 取不出 plat=all 原始清单——CliEngine 另存 common 表 */
            emptyList<CliEngine.Item>()
        }
        arr
    }

    Column(
        Modifier
            .fillMaxSize()
            /* ❗顶栏让位(2026-11-15 平板实证)：缺 statusTop+84dp 时整页画进顶栏
               下面，透过半透明顶栏 frost 看=花屏重影(其他页均有此让位)。 */
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 10.dp
            )
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        /* 说明条（V.cli note） */
        Text(
            "离线命令沙盒：内置 VRP / Linux 常用命令的模拟回显。输入 help 查看支持列表，clear 清屏。",
            color = colors.textSub, fontSize = 12.3.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(bottom = 11.dp)
        )
        /* 终端 */
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .heightIn(min = 260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tc.bg)
                .border(1.dp, tc.border, RoundedCornerShape(12.dp))
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(11.dp)
            ) {
                items(lines) { l ->
                    Text(
                        l.text,
                        color = lineColor(tc, l.cls),
                        fontSize = 11.8.sp,
                        lineHeight = 11.8.sp * 1.7f,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tc.bg2)
                    .padding(horizontal = 11.dp, vertical = 9.dp)
            ) {
                Text(
                    CliEngine.promptOf(mode),
                    color = tc.green, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(7.dp))
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = tc.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace
                    ),
                    placeholder = { Text("输入命令…", color = tc.dim, fontSize = 15.sp) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions { run(input) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = tc.green,
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(7.dp))
                Button(
                    onClick = { run(input) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    modifier = Modifier.widthIn(min = 46.dp)
                ) { Text("→", color = Color.White, fontSize = 15.sp) }
            }
        }
        /* 快捷 chips（分组行 + 命令行；命令点按 = 填入并执行） */
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, label) in CliEngine.QUICK_GROUPS) {
                    Text(
                        label,
                        color = if (k == quickGroup) colors.accent else colors.textMute,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (k == quickGroup) colors.chipBgOn else colors.chipBgOff)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .clickable { quickGroup = k }
                    )
                }
            }
            val commonCmds = remember(quickGroup) { CliEngine.commonFor(quickGroup).take(8) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (x in commonCmds) {
                    Text(
                        x.cmd,
                        color = colors.textSub,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.chipBgOff)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .clickable { run(x.cmd) }
                    )
                }
            }
        }
    }
}
