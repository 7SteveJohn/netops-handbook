/* ============================================================
 * CLI 命令库判定规则（浏览器与 Node 共用同一份，避免"数量只有跑起来才知道"）
 * 32-boot.js 的 buildCliDb() 和 tools/audit-content-consistency.js 都调这里，
 * 因此构建期就能复算出与 App 自报完全一致的命令条数。
 * ============================================================ */
(function (root, factory) {
  var api = factory();
  root.NetOpsCliRules = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this, function () {
  'use strict';

  /* 平台前缀：按命令自身判断，不再一律标 hw */
  var PLAT = [
    [/^(kubectl|helm|istioctl|cilium|calicoctl|crictl|docker|ncp)\b/, 'k8s'],
    [/^(show bgp|show ip|show version|show run|show log|show mac|show vlan|show spanning|show mpls|show interfaces|show interface|configure terminal|enable|write memory)\b/, 'cs'],
    [/^(display|system-view|undo|reset|screen-length)\b/, 'hw'],
    [/^(ip|ss|tc|iptables|ip6tables|nft|systemctl|journalctl|ethtool|nmcli|sysctl|bpftool|dig|nslookup|hostname|bridge|netns|nsenter|tcpdump|bird|birdc|ufw|firewall-cmd|nm)\b/, 'lx'],
    [/^(git|ansible|python|pip|curl|wget|ssh|scp|nc|ping|traceroute|tracepath|mtr|arping|route|netstat|ifconfig|uname|cat|grep|tee|mkdir|cp|mv|chmod|source|export|nmap|lsof)\b/, 'lx'],
    [/^(nmap|lsof)\b/, 'lx']
  ];

  function detectPlat(cmd) {
    var c = String(cmd).trim();
    for (var i = 0; i < PLAT.length; i++) if (PLAT[i][0].test(c)) return PLAT[i][1];
    return null;
  }

  /* 不是命令的东西：YAML 键、注释、提示符残留、纯中文功能名、路径、参数片段 */
  var NOT_CMD = [
    /^#/, /^\/\//, /^--/, /^\*/, /^\d+\./ ,            /* 注释 / 序号 */
    /:$/, /^- /, /^[\{\}\[\]]+$/,                       /* YAML / JSON 片段 */
    /* 小写键 + 冒号空格 + 值：YAML/JSON 映射行，不是可敲的命令（kubectl 等的真实命令都以动词开头） */
    /^[a-z][a-z0-9_.-]*:\s/,
    /^[<\[].*[>\]]\s*$/,                                /* 只剩提示符 */
    /^[一-龥]+$/, /^[一-龥]/,                            /* 中文开头的功能名，不是命令 */
    /^\/[\w./-]+$/,                                     /* 文件路径 */
    /^[x]+(\.[x]+)+$/,                                  /* x.x.x.x 占位 */
    /^\d+([\/\s]|$)/,                                   /* 以数字开头的参数片段 */
    /\s\/\s/,                                           /* "a / b" 这种并列写法 */
    /^[(（]/, /[)）]$/,                                  /* 括号注解 */
    /^(vid|vlan-id|interface|option|sub-option)\s*[:=]/  /* 参数名 */
  ];

  var OK_CMD = /^[a-zA-Z][a-zA-Z0-9_@:>.+\/-]*(\s+[^\s]*)?/;   /* 必须以字母开头的可执行形态 */

  function isCommand(s) {
    var c = String(s == null ? '' : s).trim();
    if (c.length < 2) return false;
    for (var i = 0; i < NOT_CMD.length; i++) if (NOT_CMD[i].test(c)) return false;
    if (!OK_CMD.test(c)) return false;
    if (/[一-龥]/.test(c.split(/\s/)[0])) return false;         /* 首词必须纯 ASCII */
    return true;
  }

  /* 去掉提示符与行内注释，返回可直接敲的形式 */
  function normalize(s) {
    var c = String(s == null ? '' : s).trim();
    c = c.replace(/^[<\[][^>\]]*[>\]]\s*/, '');                /* <Huawei> / [Huawei] */
    c = c.replace(/^[a-zA-Z][\w.-]*[>#]\s*/, '');              /* sw1# / R1> */
    c = c.replace(/\s+#+.*$/, '').trim();                       /* 行尾注释 */
    c = c.replace(/^#+\s*/, '');
    return c;
  }

  return {
    detectPlat: detectPlat,
    isCommand: isCommand,
    normalize: normalize,
    /* 供构建期复算用：与 buildCliDb 完全一致的推导 */
    buildCount: function (CORE, MODS, COMMON) {
      var seen = {};
      CORE.dict.rows.forEach(function (r) {
        ['hw', 'cs', 'zte', 'lx'].forEach(function (k) {
          var c = r[k];
          if (!c || c === '-') return;                          /* 缺该厂商命令就不再拿功能名凑 */
          var n = normalize(c), key = n.toLowerCase();
          if (n && isCommand(n) && !seen[key]) seen[key] = 1;
        });
      });
      MODS.forEach(function (m) {
        String(m.c || '').split('\n').forEach(function (line) {
          var n = normalize(line);
          if (!n || !isCommand(n)) return;
          var key = n.toLowerCase();
          if (!seen[key]) seen[key] = 1;
        });
      });
      (CORE.faults || []).forEach(function (f) {
        String(f.c || '').split('\n').forEach(function (line) {
          var n = normalize(line);
          if (!n || !isCommand(n)) return;
          var key = n.toLowerCase();
          if (!seen[key]) seen[key] = 1;
        });
      });
      (COMMON || []).forEach(function (x) {
        var n = normalize(x.cmd);
        if (!n) return;
        var key = n.toLowerCase();
        if (!seen[key]) seen[key] = 1;
      });
      return Object.keys(seen).length;
    }
  };
});
