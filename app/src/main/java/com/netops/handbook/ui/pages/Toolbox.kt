package com.netops.handbook.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netops.handbook.ui.LocalOpsColors
import com.netops.handbook.ui.OpsCard
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.OpsTheme
import com.netops.handbook.ui.PageData
import org.json.JSONObject

/**
 * 工具箱行卡（HomePage 与首页工具箱共用；M3-2④ 从 HomePage 抽出为公共件）。
 */
@Composable
fun ToolboxRow(title: String, sub: String, colors: OpsColors, onClick: () -> Unit) {
    OpsCard(Modifier.clickable { onClick() }) {
        Text(title, color = colors.textMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(sub, color = colors.textMute, fontSize = 11.5.sp)
    }
}

/**
 * 工具箱网格（M3-2④ HomePage 工具箱段抽出，供 pages 内复用）。
 */
@Composable
fun ToolboxGrid(
    nRefs: Int, nGloss: Int, nQuiz: Int,
    colors: OpsColors, onGo: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolboxRow("速查表", "$nRefs 张 · 端口 / 掩码 / OSPF / BGP", colors) { onGo("refs", "") }
        ToolboxRow("学习路线图", "0-12 个月成长节奏与目标", colors) { onGo("roadmap", "") }
        ToolboxRow("术语词典", "$nGloss 条云原生与数通黑话", colors) { onGo("glossary", "") }
        ToolboxRow("模拟测验", "$nQuiz 道选择题", colors) { onGo("quiz", "") }
        ToolboxRow("CLI 模拟器", "离线练习 VRP / Linux 常用命令", colors) { onGo("cli", "") }
    }
}

