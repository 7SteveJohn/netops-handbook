package com.netops.handbook.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * CLI 模拟器引擎（2026-11-14 M1a）：1:1 移植 web-src/js/data/22-cli-rules.js
 * 与 32-boot.js buildCliDb/execCli——命令条数与门禁（audit-content-consistency
 * buildCount）同源对账（738）。
 *
 * 数据四源（native-data.json cli 字段 + dict.rows）：
 *   dict.rows 五平台列 → cli.mods(c 行清洗,o 回显) → cli.faults → cli.common。
 * 匹配瀑布：精确(带 out 者优先) → 前缀联想 12 → 模糊子串 8 → 未命中 Error + 3 条联想。
 */
object CliEngine {

    /* MagicOS 定制 java.util.regex 对个别语法 Pattern.compile 直接抛错
       （2026-11-14 手机真机实锤：CliEngine.<clinit> ExceptionInInitializerError
       → 打开即闪退）。防御式初始化：逐条编译 try-catch，失败条目跳过并记日志
       ——匹配能力降级，绝不崩类初始化。 */
    private fun safeRegex(pattern: String): Regex? = try {
        Regex(pattern)
    } catch (t: Throwable) {
        android.util.Log.w("CliEngine", "正则编译失败（平台 regex 差异，条目降级）：$pattern", t)
        null
    }

    /* ================= 判定规则（22-cli-rules.js 1:1） ================= */

    private val PLAT: List<Pair<Regex, String>> = listOf(
        safeRegex("""^(kubectl|helm|istioctl|cilium|calicoctl|crictl|docker|ncp)\b""") to "k8s",
        safeRegex("""^(show bgp|show ip|show version|show run|show log|show mac|show vlan|show spanning|show mpls|show interfaces|show interface|configure terminal|enable|write memory)\b""") to "cs",
        safeRegex("""^(display|system-view|undo|reset|screen-length)\b""") to "hw",
        /* win 要排在 lx 的 route/ping 组之前：route print/arp -a/tracert 是 Windows 语义 */
        safeRegex("""^(ipconfig|netsh|route print|route add|arp -a|pktmon|typeperf|wevtutil|get-net|systeminfo|w32tm|tracert|winver)\b""") to "win",
        safeRegex("""^(ip|ss|tc|iptables|ip6tables|nft|systemctl|journalctl|ethtool|nmcli|sysctl|bpftool|dig|nslookup|hostname|bridge|netns|nsenter|tcpdump|bird|birdc|ufw|firewall-cmd|nm)\b""") to "lx",
        safeRegex("""^(git|ansible|python|pip|curl|wget|ssh|scp|nc|ping|traceroute|tracepath|mtr|arping|route|netstat|ifconfig|uname|cat|grep|tee|mkdir|cp|mv|chmod|source|export|nmap|lsof)\b""") to "lx",
        safeRegex("""^(nmap|lsof)\b""") to "lx"
    ).filter { it.first != null }.map { it.first!! to it.second }

    fun detectPlat(cmd: String): String? {
        val c = cmd.trim()
        for ((re, p) in PLAT) if (re.containsMatchIn(c)) return p
        return null
    }

    /* 不是命令的东西：YAML 键、注释、提示符残留、纯中文功能名、路径、参数片段 */
    private val NOT_CMD: List<Regex> = listOf(
        safeRegex("""^#"""), safeRegex("""^//"""), safeRegex("""^--"""), safeRegex("""^\*"""), safeRegex("""^\d+\."""),
        safeRegex(""":$"""), safeRegex("""^- """), safeRegex("""^[{}[\]]+$"""),
        safeRegex("""^[a-z][a-z0-9_.-]*:\s"""),
        safeRegex("""^[<\[].*[>\]]\s*$"""),
        safeRegex("""^[一-龥]+$"""), safeRegex("""^[一-龥]"""),
        safeRegex("""^/[\w./-]+$"""),
        safeRegex("""^[x]+(\.[x]+)+$"""),
        safeRegex("""^\d+([/\s]|$)"""),
        safeRegex("""\s/\s"""),
        safeRegex("""^[(（]"""), safeRegex("""[)）]$"""),
        safeRegex("""^(vid|vlan-id|interface|option|sub-option)\s*[:=]""")
    ).filterNotNull()

    private val OK_CMD: Regex? = safeRegex("""^[a-zA-Z][a-zA-Z0-9_@:>.+/-]*(\s+[^\s]*)?""")
    private val HAN: Regex? = safeRegex("""[一-龥]""")
    private val WS = safeRegex("""\s""")

    fun isCommand(s: String?): Boolean {
        val c = (s ?: "").trim()
        if (c.length < 2) return false
        for (re in NOT_CMD) if (re.containsMatchIn(c)) return false
        val ok = OK_CMD ?: return false
        if (!ok.containsMatchIn(c)) return false
        val han = HAN ?: return true
        if (han.containsMatchIn(c.split(WS ?: Regex("\\s"))[0])) return false  /* 首词必须纯 ASCII */
        return true
    }

    /** 去掉提示符与行内注释，返回可直接敲的形式（逐条防御，失败跳过该清洗）。 */
    fun normalize(s: String?): String {
        var c = (s ?: "").trim()
        val r1 = safeRegex("""^[<\[][^>\]]*[>\]]\s*""")
        val r2 = safeRegex("""^[a-zA-Z][\w.-]*[>#]\s*""")
        val r3 = safeRegex("""\s+#+.*$""")
        val r4 = safeRegex("""^#+\s*""")
        if (r1 != null) c = c.replace(r1, "")
        if (r2 != null) c = c.replace(r2, "")
        if (r3 != null) c = c.replace(r3, "").trim()
        if (r4 != null) c = c.replace(r4, "")
        return c
    }

    /* ================= 命令库（buildCliDb 1:1） ================= */

    data class Item(val cmd: String, val raw: String, val fn: String, val plat: String, val out: String?)

    private var db: List<Item>? = null
    private var commonItems: List<Item> = emptyList()

    /** 快捷命令 chips（renderQuick：按分组过滤，plat=all 恒显示）。 */
    fun commonFor(group: String): List<Item> =
        commonItems.filter { group == "all" || it.plat == group }

    /** 聚合四源（native-data root）。懒加载+缓存；线程安全=幂等纯函数。 */
    @JvmStatic fun build(root: JSONObject): List<Item> {
        db?.let { return it }
        val items = ArrayList<Item>()
        val seen = HashMap<String, Item>()
        fun push(cmd: String, raw: String, fn: String, plat: String, out: String?) {
            val k = cmd.lowercase().trim()
            if (k.length < 2) return
            val prev = seen[k]
            if (prev != null) {
                if (out != null && prev.out == null) {
                    val upd = prev.copy(out = out, raw = raw)
                    seen[k] = upd
                    items[items.indexOf(prev)] = upd
                }
                return
            }
            val it = Item(k, raw, fn, plat, out)
            seen[k] = it
            items.add(it)
        }
        /* 1. 字典五平台列（含 win——漏 win 少 20 条，2026-10-31 实测教训） */
        val rows = root.optJSONObject("dict")?.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            for (k in listOf("hw", "cs", "zte", "lx", "win")) {
                val n = normalize(r.optString(k))
                if (!isCommand(n)) continue
                push(n, r.optString(k), r.optString("fn"), k, null)
            }
        }
        /* 2/3. 知识模块与故障案例命令：逐行清洗，按命令自身判平台 */
        fun collect(text: String, fnLabel: String, out: String?) {
            for (line in text.split('\n')) {
                val n = normalize(line)
                if (!isCommand(n)) continue
                push(n, line, fnLabel, detectPlat(n) ?: "hw", out)
            }
        }
        val cli = root.optJSONObject("cli") ?: JSONObject()
        val mods = cli.optJSONArray("mods") ?: JSONArray()
        for (i in 0 until mods.length()) {
            val m = mods.optJSONObject(i) ?: continue
            collect(m.optString("c"), m.optString("t"), m.optString("o").ifEmpty { null })
        }
        val faults = cli.optJSONArray("faults") ?: JSONArray()
        for (i in 0 until faults.length()) {
            val f = faults.optJSONObject(i) ?: continue
            collect(f.optString("c"), f.optString("t"), f.optString("o").ifEmpty { null })
        }
        /* 4. 内置常用命令（带平台标记，回显贴近真实输出） */
        val common = cli.optJSONArray("common") ?: JSONArray()
        val commons = ArrayList<Item>()
        for (i in 0 until common.length()) {
            val x = common.optJSONObject(i) ?: continue
            val n = normalize(x.optString("cmd"))
            if (!isCommand(n)) continue
            push(n, x.optString("cmd"), x.optString("fn"),
                x.optString("plat").ifEmpty { detectPlat(n) ?: "lx" }, x.optString("out").ifEmpty { null })
            commons.add(Item(n.lowercase(), x.optString("cmd"), x.optString("fn"),
                x.optString("plat").ifEmpty { "lx" }, x.optString("out").ifEmpty { null }))
        }
        commonItems = commons
        db = items
        return items
    }

    /* ================= exec 瀑布（execCli 1:1） ================= */

    enum class Kind { CLEAR, TEXT }           // CLEAR=清屏；TEXT=追加行
    data class Line(val text: String, val cls: String)   // cls: cmd/ok/err/info/dim
    data class Result(val clear: Boolean, val lines: List<Line>, val mode: String?)

    private val PLATS = listOf("hw", "cs", "zte", "lx", "win")
    fun promptOf(mode: String) = when (mode) {
        "hw" -> "<Huawei>"; "cs" -> "Switch#"; "zte" -> "ZXR10#"
        "lx" -> "root@netops:~#"; else -> "C:\\Users\\NetOps>"
    }

    fun exec(db: List<Item>, v: String, mode: String): Result {
        val out = ArrayList<Line>()
        fun p(text: String, cls: String) { out.add(Line(text, cls)) }
        val low = v.lowercase().trim()
        if (low == "clear" || low == "cls") return Result(clear = true, lines = out, mode = mode)
        if (low == "help" || low == "?") {
            p("可用指令：", "info")
            p("  help / ?            分组帮助", "dim")
            p("  clear               清屏", "dim")
            p("  mode hw|cs|zte|lx   切换平台（当前 $mode）", "dim")
            p("  find <关键词>       搜索命令库", "dim")
            p("  输入命令前缀可联想（如 display / kubectl / ip）", "dim")
            p("— 常用命令示例 —", "dim")
            for (c in listOf("display version", "display ip interface brief", "display vlan",
                        "display ospf peer", "ping", "tracert", "ip addr", "ip route",
                        "netstat -an", "ss -tulnp", "kubectl get pods", "kubectl get svc",
                        "curl", "tcpdump")) p("  $c", "info")
            p("（共 ${db.size} 条命令，精确匹配 → 前缀联想 → 模糊近似）", "dim")
            return Result(false, out, mode)
        }
        if (low.startsWith("mode ")) {
            val m = low.substring(5).trim()
            if (m !in PLATS) { p("不支持的平台：$m", "err"); return Result(false, out, mode) }
            p("已切换到 $m 平台", "ok")
            return Result(false, out, m)
        }
        if (low.startsWith("find ")) {
            val kw = low.substring(5).trim()
            val hits = db.filter { it.cmd.contains(kw) || it.fn.lowercase().contains(kw) }.take(12)
            if (hits.isEmpty()) { p("未找到与「$kw」相关的命令", "err"); return Result(false, out, mode) }
            for (x in hits) p("  ${x.raw.split('\n')[0]}   # ${x.fn}", "ok")
            return Result(false, out, mode)
        }
        var exact: Item? = null
        val prefix = ArrayList<Item>()
        for (x in db) {
            if (x.cmd == low) { if (exact == null || x.out != null) exact = x }
            else if (x.cmd.startsWith(low)) prefix.add(x)
        }
        exact?.let { x ->
            if (x.out != null) for (l in x.out.split('\n')) p(l, "ok")
            else p("[模拟] ${x.fn} —— 该命令在真机上执行后会返回对应配置/状态信息。", "info")
            return Result(false, out, mode)
        }
        if (prefix.isNotEmpty()) {
            p("匹配 ${prefix.size} 条命令（输入完整命令查看回显）：", "info")
            for (x in prefix.take(12)) p("  ${x.raw.split('\n')[0]}   # ${x.fn}", "ok")
            return Result(false, out, mode)
        }
        val fuzzy = db.filter { it.cmd.contains(low) }.take(8)
        if (fuzzy.isNotEmpty()) {
            p("未找到精确命令，近似匹配：", "dim")
            for (x in fuzzy) p("  ${x.raw.split('\n')[0]}   # ${x.fn}", "info")
            return Result(false, out, mode)
        }
        p("Error: Unrecognized command found at '^' position.", "err")
        val first = low.split(' ')[0]
        val near = db.filter { it.cmd.contains(first) }.take(3)
        if (near.isNotEmpty()) {
            p("你是不是想输入：", "dim")
            for (x in near) p("  ${x.raw.split('\n')[0]}", "info")
        }
        return Result(false, out, mode)
    }

    /* 快捷分组（QUICK_GROUPS 原样） */
    val QUICK_GROUPS = listOf("all" to "常用", "hw" to "VRP", "lx" to "Linux", "k8s" to "K8s", "win" to "Win")
}
