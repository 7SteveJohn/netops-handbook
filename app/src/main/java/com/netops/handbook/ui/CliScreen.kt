package com.netops.handbook.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
 * CLI 模拟器页（2026-11-14 M1b / 2026-11-18 液态玻璃快捷胶囊与横屏挖孔屏安全区适配）。
 *
 * 终端流（五色行 + 600 行钳制）+ 平台提示符 + 输入行 + 快捷玻璃胶囊（QUICK_GROUPS × COMMON_CMDS）。
 * 安全区：补齐横屏左右 displayCutout 与导航栏避让（无左侧导轨），防止刘海/挖孔吃掉终端提示符，
 * 并内置紧凑玻璃返回头栏与横向可滚快捷胶囊条，保证横竖屏视口完整舒展。
 */

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

private val CLI_MODES = listOf(
    "hw" to "华为 VRP",
    "cs" to "Cisco IOS",
    "zte" to "中兴 ZXR10",
    "lx" to "Linux",
)

/** Java 侧挂载入口（Composeable lambda 无法在 Java 源里书写）。 */
fun androidx.compose.ui.platform.ComposeView.setCliContent(
    dataSupplier: () -> JSONObject?, dark: () -> Boolean,
) {
    setViewCompositionStrategy(
        androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    setContent {
        OpsTheme(dark(), false) {
            CliScreen(dataSupplier, dark())
        }
    }
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
                } catch (_: Throwable) {
                }
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
        print("脉络 NetCraft CLI · 完全离线 · 命令库就绪 · ${db.size} 条命令", "info")
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

    val compactLand = isOpsCompactLandscape()
    /* CLI 路由隐藏全局顶栏与底栏/导轨，使用 rememberOpsPagePadding 自动避让状态栏、底部手势栏与横屏左右挖孔 */
    val pagePad = rememberOpsPagePadding(
        extraTop = if (compactLand) 6.dp else 10.dp,
        extraBottom = if (compactLand) 6.dp else 10.dp,
        horizontal = 16.dp,
        hasDock = false,
        hasTopBar = false,
    )

    Column(
        Modifier
            .fillMaxSize()
            .padding(pagePad)
            .imePadding()
    ) {
        /* 紧凑玻璃控制头栏：返回胶囊 + 标题 + 平台快捷切换胶囊 */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (compactLand) 6.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "← 返回",
                    color = colors.textMain,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .glassChipSurface(colors, active = false, shape = RoundedCornerShape(10.dp))
                        .glassClickable { com.netops.handbook.lens.Router.back() }
                        .padding(horizontal = 10.dp, vertical = 5.5.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "CLI 模拟器",
                        color = colors.textMain,
                        fontSize = if (compactLand) 14.5.sp else 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (!compactLand) {
                        Text(
                            "离线沙盒 · 输入 help 查看支持列表，clear 清屏",
                            color = colors.textMute,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for ((mk, mLabel) in CLI_MODES) {
                    val active = mode == mk
                    Text(
                        mLabel,
                        color = if (active) Color.White else colors.textSub,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier
                            .glassChipSurface(colors, active = active, shape = RoundedCornerShape(9.dp))
                            .glassClickable {
                                if (!active) {
                                    mode = mk
                                    print("已切换终端平台：$mLabel", "info")
                                }
                            }
                            .padding(horizontal = 8.5.dp, vertical = 4.5.dp)
                    )
                }
            }
        }

        /* 终端主体（曜石深底 + 菲涅尔暗玻璃细边） */
        val termShape = RoundedCornerShape(14.dp)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .heightIn(min = if (compactLand) 120.dp else 220.dp)
                .clip(termShape)
                .background(tc.bg)
                .glassSpecularBorder(shape = termShape, width = 0.85.dp, intensity = 0.85f)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = if (compactLand) 8.dp else 11.dp)
            ) {
                items(lines) { l ->
                    Text(
                        l.text,
                        color = lineColor(tc, l.cls),
                        fontSize = 11.8.sp,
                        lineHeight = 11.8.sp * 1.65f,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tc.bg2)
                    .padding(horizontal = 12.dp, vertical = if (compactLand) 7.dp else 9.dp)
            ) {
                Text(
                    CliEngine.promptOf(mode),
                    color = tc.green,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = tc.text,
                        fontSize = 14.5.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(tc.green),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { run(input) }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) {
                                Text(
                                    "输入命令（如 display ip interface brief）…",
                                    color = tc.dim,
                                    fontSize = 13.5.sp,
                                    maxLines = 1
                                )
                            }
                            inner()
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "执行 →",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .glassChipSurface(colors, active = true, shape = RoundedCornerShape(10.dp))
                        .glassClickable { run(input) }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                )
            }
        }

        /* 快捷玻璃胶囊（分组行 + 常用命令行；均支持横向滚动防横竖屏截断） */
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = if (compactLand) 7.dp else 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for ((k, label) in CliEngine.QUICK_GROUPS) {
                    val active = k == quickGroup
                    Text(
                        label,
                        color = if (active) Color.White else colors.textSub,
                        fontSize = 11.5.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier
                            .glassChipSurface(colors, active = active, shape = RoundedCornerShape(9.dp))
                            .glassClickable { quickGroup = k }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            val commonCmds = remember(quickGroup) { CliEngine.commonFor(quickGroup).take(8) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (x in commonCmds) {
                    Text(
                        x.cmd,
                        color = colors.textSub,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .glassChipSurface(colors, active = false, shape = RoundedCornerShape(9.dp))
                            .glassClickable { run(x.cmd) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}
