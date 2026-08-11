#!/usr/bin/env python3
"""Wave 3: CLI 模拟器改造 — 替换 js/32-boot.js 中 CLI 部分(1085-1191 行)"""
import re

P = 'js/32-boot.js'
src = open(P, encoding='utf-8').read()

NEW_CLI = r'''  /* ---------------- CLI 模拟器 ---------------- */
  /* 2026-08-12 Wave 3 改造：
     - 数据源全量：字典(含'-'字段) + 知识模块全部命令行 + 故障案例命令 + COMMON_CMDS 内置
     - 匹配：精确 → 前缀联想列表 → 模糊近似 → 建议
     - help 分组；历史 localStorage 持久化；快速按钮动态生成 */
  var CLI_KEY = 'netops_cli_hist';
  var cliHist = [], cliPos = -1, cliMode = 'hw', cliKbBound = false;
  try { var _h = localStorage.getItem(CLI_KEY); if (_h) cliHist = JSON.parse(_h) || []; } catch (e) {}
  var CLI_DB = null;

  /* 内置常用命令（高频、贴近真实回显） */
  var COMMON_CMDS = [
    { cmd: 'ping', fn: '网络连通性测试', out: 'PING 8.8.8.8 (8.8.8.8): 56 data bytes\n64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=12.3 ms\n--- 8.8.8.8 ping statistics ---\n1 packets transmitted, 1 received, 0% packet loss' },
    { cmd: 'tracert', fn: '路由追踪', out: 'traceroute to 8.8.8.8 (8.8.8.8), 30 hops max\n 1  192.168.1.1   1.2 ms\n 2  10.0.0.1     3.4 ms\n 3  8.8.8.8     12.3 ms' },
    { cmd: 'ip addr', fn: 'Linux IP 地址', out: '1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536\n    inet 127.0.0.1/8 scope host lo\n2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500\n    inet 192.168.1.100/24 brd 192.168.1.255 scope global eth0' },
    { cmd: 'ip route', fn: 'Linux 路由表', out: 'default via 192.168.1.1 dev eth0\n192.168.1.0/24 dev eth0 proto kernel scope link src 192.168.1.100' },
    { cmd: 'ipconfig', fn: 'Windows IP 配置', out: 'Windows IP 配置\n以太网适配器 以太网:\n   IPv4 地址: 192.168.1.100\n   子网掩码: 255.255.255.0\n   默认网关: 192.168.1.1' },
    { cmd: 'ifconfig', fn: 'Linux 接口配置', out: 'eth0: flags=4163<UP,BROADCAST,RUNNING,MULTICAST>  mtu 1500\n        inet 192.168.1.100  netmask 255.255.255.0  broadcast 192.168.1.255\n        ether 00:0c:29:aa:bb:cc' },
    { cmd: 'netstat -an', fn: '端口连接状态', out: 'Proto Recv-Q Send-Q Local Address    Foreign Address  State\ntcp   0      0      0.0.0.0:22       0.0.0.0:*         LISTEN\ntcp   0      0      192.168.1.100:443  45.33.2.1:52344  ESTABLISHED' },
    { cmd: 'ss -tulnp', fn: '监听端口', out: 'Netid  State   Recv-Q Send-Q  Local Address:Port\ntcp    LISTEN  0      128      0.0.0.0:22\ntcp    LISTEN  0      128      0.0.0.0:80' },
    { cmd: 'nslookup', fn: 'DNS 查询', out: 'Server:  192.168.1.1\nAddress: 192.168.1.1#53\n\nName:    www.example.com\nAddress: 93.184.216.34' },
    { cmd: 'curl', fn: 'HTTP 请求', out: 'HTTP/1.1 200 OK\nContent-Type: text/html; charset=utf-8\n\n<!DOCTYPE html><html>...' },
    { cmd: 'tcpdump', fn: '抓包', out: 'tcpdump: verbose output suppressed\n22:15:33.123456 IP 192.168.1.100.443 > 45.33.2.1.52344: Flags [P.], seq 1:517, ack 1, win 501' },
    { cmd: 'mtr', fn: '网络诊断(ping+traceroute 结合)', out: 'HOST            Loss%  Snt  Last  Avg  Best  Wrst  StDev\n1. 192.168.1.1   0.0%   10   1.2   1.4  1.1   2.3   0.3\n2. 10.0.0.1      0.0%   10   3.4   3.6  3.1   4.2   0.4' },
    { cmd: 'ethtool', fn: '网卡信息', out: 'Settings for eth0:\n    Speed: 1000Mb/s\n    Duplex: Full\n    Link detected: yes' },
    { cmd: 'route print', fn: 'Windows 路由表', out: 'IPv4 路由表\n活动路由:\n网络目标 0.0.0.0  网关 192.168.1.1  接口 192.168.1.100  跃点数 25' },
    { cmd: 'kubectl get pods', fn: 'K8s 查看 Pod', out: 'NAME                     READY  STATUS   RESTARTS  AGE\nnginx-7c9bc7c7b7-abc12   1/1    Running  0         3d2h' },
    { cmd: 'kubectl get svc', fn: 'K8s 查看 Service', out: 'NAME     TYPE        CLUSTER-IP  EXTERNAL-IP  PORT(S)  AGE\nnginx    ClusterIP   10.96.0.10  <none>       80/TCP   3d' },
    { cmd: 'kubectl get nodes', fn: 'K8s 查看节点', out: 'NAME    STATUS  ROLES          AGE  VERSION\nnode-1  Ready   control-plane  30d  v1.28.2\nnode-2  Ready   <none>         30d  v1.28.2' }
  ];

  function buildCliDb() {
    if (CLI_DB) return CLI_DB;
    var db = [], seen = {};
    function push(cmd, raw, fn, plat, out) {
      var k = String(cmd).toLowerCase().trim();
      if (!k || k.length < 2) return;
      if (seen[k]) { if (out && !seen[k].out) { seen[k].out = out; seen[k].raw = raw; } return; }
      var it = { cmd: k, raw: raw || cmd, fn: fn, plat: plat || 'hw', out: out };
      seen[k] = it; db.push(it);
    }
    /* 1. 字典全量：'-' 字段也用功能名建条目，保证关键字搜索有返回 */
    CORE.dict.rows.forEach(function (r) {
      ['hw', 'cs', 'zte', 'lx'].forEach(function (k) {
        var c = r[k];
        push(c && c !== '-' ? c : r.fn, c && c !== '-' ? c : r.fn, r.fn, k);
      });
    });
    /* 2. 知识模块全量命令（每行去提示符，不只第一行） */
    A.MODS.forEach(function (m) {
      if (!m.c) return;
      String(m.c).split('\n').forEach(function (line) {
        var clean = line.replace(/^[<\[][^>\]]*[>\]]\s*/, '').trim();
        if (clean) push(clean, line, A.cleanTitle(m.t), 'hw', m.o);
      });
    });
    /* 3. 故障案例命令 */
    CORE.faults.forEach(function (f) {
      if (!f.c) return;
      String(f.c).split('\n').forEach(function (line) {
        var clean = line.replace(/^[<\[][^>\]]*[>\]]\s*/, '').trim();
        if (clean) push(clean, line, f.t, 'hw', f.o);
      });
    });
    /* 4. 内置常用命令 */
    COMMON_CMDS.forEach(function (x) { push(x.cmd, x.cmd, x.fn, 'lx', x.out); });
    CLI_DB = db;
    return db;
  }
  function saveHist() {
    try { localStorage.setItem(CLI_KEY, JSON.stringify(cliHist.slice(-50))); } catch (e) {}
  }
  function initCli() {
    var out = $('#cliOut'), inp = $('#cliIn'), btn = $('#cliRun'), quick = $('#cliQuick');
    if (!out) return;
    var n = buildCliDb().length;
    cliPrint(out, 'NetOps CLI 模拟器 v2.0 · 完全离线 · ' + n + ' 条命令', 'info');
    cliPrint(out, '输入 help 查看分组帮助，mode hw|cs|zte|lx 切换平台，前缀输入可联想命令。', 'dim');
    /* 快速按钮动态生成（高频在前） */
    var qc = ['help', 'clear'].concat(COMMON_CMDS.slice(0, 7).map(function (x) { return x.cmd; }));
    quick.innerHTML = qc.map(function (c) {
      return '<button class="chip" type="button" data-cli="' + esc(c) + '">' + esc(c) + '</button>';
    }).join('');
    quick.onclick = function (e) {
      var b = e.target.closest('[data-cli]'); if (!b) return;
      inp.value = b.getAttribute('data-cli'); runCli();
    };
    if (!cliKbBound) {
      cliKbBound = true;
      var toBottom = function () {
        var o = $('#cliOut');
        if (o) o.scrollTop = o.scrollHeight;
      };
      w.addEventListener('netops:keyboard', function () { setTimeout(toBottom, 160); });
      w.addEventListener('resize', function () { setTimeout(toBottom, 60); });
    }
    btn.onclick = runCli;
    inp.onkeydown = function (e) {
      if (e.key === 'Enter') { runCli(); return; }
      if (e.key === 'ArrowUp') { if (cliPos > 0) inp.value = cliHist[--cliPos]; e.preventDefault(); }
      if (e.key === 'ArrowDown') { if (cliPos < cliHist.length - 1) inp.value = cliHist[++cliPos]; e.preventDefault(); }
    };
    function runCli() {
      var v = inp.value.trim(); if (!v) return;
      cliHist.push(v); cliPos = cliHist.length; saveHist();
      inp.value = '';
      cliPrint(out, promptOf() + ' ' + v, 'cmd');
      execCli(out, v);
      out.scrollTop = out.scrollHeight;
    }
  }
  function promptOf() {
    return { hw: '<Huawei>', cs: 'Switch#', zte: 'ZXR10#', lx: 'root@netops:~#' }[cliMode];
  }
  function cliPrint(out, text, cls) {
    var div = d.createElement('div');
    div.className = 'cli__line--' + (cls || 'dim');
    div.textContent = text;
    out.appendChild(div);
  }
  function execCli(out, v) {
    var low = v.toLowerCase().trim();
    if (low === 'clear' || low === 'cls') { out.innerHTML = ''; return; }
    if (low === 'help' || low === '?') {
      cliPrint(out, '可用指令：', 'info');
      cliPrint(out, '  help / ?            分组帮助', 'dim');
      cliPrint(out, '  clear               清屏', 'dim');
      cliPrint(out, '  mode hw|cs|zte|lx   切换平台（当前 ' + cliMode + '）', 'dim');
      cliPrint(out, '  find <关键词>       搜索命令库', 'dim');
      cliPrint(out, '  输入命令前缀可联想（如 display / kubectl / ip）', 'dim');
      cliPrint(out, '— 常用命令示例 —', 'dim');
      ['display version', 'display ip interface brief', 'display vlan', 'display ospf peer',
       'ping', 'tracert', 'ip addr', 'ip route', 'netstat -an', 'ss -tulnp',
       'kubectl get pods', 'kubectl get svc', 'curl', 'tcpdump'].forEach(function (c) {
        cliPrint(out, '  ' + c, 'info');
      });
      cliPrint(out, '（共 ' + buildCliDb().length + ' 条命令，精确匹配 → 前缀联想 → 模糊近似）', 'dim');
      return;
    }
    if (low.indexOf('mode ') === 0) {
      var m = low.slice(5).trim();
      if (['hw', 'cs', 'zte', 'lx'].indexOf(m) < 0) { cliPrint(out, '不支持的平台：' + m, 'err'); return; }
      cliMode = m; $('#cliPrompt').textContent = promptOf();
      cliPrint(out, '已切换到 ' + m + ' 平台', 'ok');
      return;
    }
    if (low.indexOf('find ') === 0) {
      var kw = low.slice(5).trim();
      var hits = buildCliDb().filter(function (x) { return x.cmd.indexOf(kw) >= 0 || x.fn.toLowerCase().indexOf(kw) >= 0; }).slice(0, 12);
      if (!hits.length) { cliPrint(out, '未找到与「' + kw + '」相关的命令', 'err'); return; }
      hits.forEach(function (x) { cliPrint(out, '  ' + x.raw.split('\n')[0] + '   # ' + x.fn, 'ok'); });
      return;
    }
    var db = buildCliDb();
    var exact = null, prefix = [];
    db.forEach(function (x) {
      if (x.cmd === low) { if (!exact || x.out) exact = x; }
      else if (x.cmd.indexOf(low) === 0) prefix.push(x);
    });
    if (exact) {
      if (exact.out) { exact.out.split('\n').forEach(function (l) { cliPrint(out, l, 'ok'); }); }
      else { cliPrint(out, '[模拟] ' + exact.fn + ' —— 该命令在真机上执行后会返回对应配置/状态信息。', 'info'); }
      return;
    }
    if (prefix.length) {
      cliPrint(out, '匹配 ' + prefix.length + ' 条命令（输入完整命令查看回显）：', 'info');
      prefix.slice(0, 12).forEach(function (x) { cliPrint(out, '  ' + x.raw.split('\n')[0] + '   # ' + x.fn, 'ok'); });
      return;
    }
    var fuzzy = db.filter(function (x) { return x.cmd.indexOf(low) >= 0; }).slice(0, 8);
    if (fuzzy.length) {
      cliPrint(out, '未找到精确命令，近似匹配：', 'dim');
      fuzzy.forEach(function (x) { cliPrint(out, '  ' + x.raw.split('\n')[0] + '   # ' + x.fn, 'info'); });
      return;
    }
    cliPrint(out, "Error: Unrecognized command found at '^' position.", 'err');
    var near = db.filter(function (x) { return x.cmd.indexOf(low.split(' ')[0]) >= 0; }).slice(0, 3);
    if (near.length) { cliPrint(out, '你是不是想输入：', 'dim'); near.forEach(function (x) { cliPrint(out, '  ' + x.raw.split('\n')[0], 'info'); }); }
  }
'''

# 定位旧 CLI 段:从 "/* ---------------- CLI 模拟器" 到 "/* ---------------- 滚动联动"
start_marker = '  /* ---------------- CLI 模拟器'
end_marker = '  /* ---------------- 滚动联动'
si = src.find(start_marker)
ei = src.find(end_marker)
if si < 0 or ei < 0:
    print('ERROR: markers not found', si, ei)
    raise SystemExit(1)
new = src[:si] + NEW_CLI + '\n' + src[ei:]
open(P, 'w', encoding='utf-8').write(new)
print('CLI 改造完成, 替换区间', si, '->', ei)
