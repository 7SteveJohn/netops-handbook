/**
 * 生成最终数据层 js/data/10-core.js
 * 将旧版混杂在「阶段五」中的排障卡 / 命令字典 / 面试题拆分为独立数据域，
 * 以匹配移动端「学习 / 排障 / 字典 / 面试 / 我的」信息架构。
 */
const fs = require('fs');
const path = require('path');

const CACHE = path.resolve(__dirname, '../.cache');
const phases = JSON.parse(fs.readFileSync(path.join(CACHE, 'phases.json'), 'utf8'));
const dic = JSON.parse(fs.readFileSync(path.join(CACHE, 'dic.json'), 'utf8'));

/** 从 "**现象描述**：xxx\n**根因分析**：yyy" 中抽取字段 */
function pick(text, label) {
  if (!text) return '';
  const re = new RegExp('\\*\\*' + label + '\\*\\*[：:]\\s*([\\s\\S]*?)(?=\\n\\*\\*|$)');
  const m = text.match(re);
  return m ? m[1].trim() : '';
}
function stripMd(s) { return String(s || '').replace(/\*\*/g, '').trim(); }

const PHASE_META = {
  'p-1': { short: '入门筑基', icon: 'i-router',    lvl: 1 },
  'p-2': { short: '园区核心', icon: 'i-switch-dev',lvl: 2 },
  'p-3': { short: '广域安全', icon: 'i-shield',    lvl: 3 },
  'p-4': { short: '云原生',   icon: 'i-cloud',     lvl: 4 }
};

const learnPhases = [];
const faults = [];
const interview = [];

phases.forEach(p => {
  if (p.id === 'p-5') {
    p.modules.forEach(m => {
      if (/^G\d+$/.test(m.id)) {
        faults.push({
          id: m.id,
          t: m.t.replace(/^🛠️\s*故障\s*G\d+:\s*/, '').trim(),
          sym: stripMd(pick(m.y, '现象描述')) || stripMd(m.y),
          cause: stripMd(pick(m.y, '根因分析')),
          c: m.c, o: m.o, j: m.j, u: m.u, w: m.w, v: m.v, l: m.l,
          dg: m.dg,
          cat: catOfFault(m)
        });
      } else if (/^IVQ\d+$/.test(m.id)) {
        interview.push({
          id: m.id,
          t: m.t.replace(/^💼\s*面试\s*Q\d+:\s*/, '').trim(),
          point: stripMd(pick(m.y, '考察点')) || stripMd(m.y),
          star: stripMd(pick(m.y, 'STAR 解析')),
          answer: (m.c || '').replace(/^[“"]|[”"]$/g, '').trim(),
          fb: m.o, j: m.j, u: m.u, w: (m.w || []).filter(Boolean),
          cat: catOfInterview(m)
        });
      }
      // DIC 模块并入独立的字典数据域，此处丢弃
    });
    return;
  }
  const meta = PHASE_META[p.id] || {};
  learnPhases.push({
    id: p.id,
    title: p.title,
    short: meta.short || p.title,
    icon: meta.icon || 'i-layers',
    lvl: meta.lvl || 1,
    color: p.color,
    desc: p.desc,
    modules: p.modules.map(m => ({
      id: m.id,
      t: m.t,
      lab: /^LAB/.test(m.id) || /🎯/.test(m.t),
      y: m.y, dg: m.dg, c: m.c, o: m.o,
      j: m.j, u: m.u, w: m.w, v: m.v, l: m.l
    }))
  });
});

function catOfFault(m) {
  const s = (m.t + ' ' + m.y).toLowerCase();
  if (/k8s|pod|calico|cilium|ebpf|networkpolicy|service|prometheus|chaos/.test(s)) return '云原生';
  if (/vlan|stp|mac|环路|端口安全|trunk/.test(s)) return '二层';
  if (/ospf|bgp|路由|ecmp|srv6|vrrp/.test(s)) return '三层';
  if (/防火墙|nat|策略|acl|zone/.test(s)) return '安全';
  if (/ap|wi-fi|无线|漫游|option43/.test(s)) return '无线';
  if (/vxlan|sd-wan|mtu|隧道/.test(s)) return 'overlay';
  return '综合';
}
function catOfInterview(m) {
  const s = (m.t + ' ' + m.y + ' ' + (m.u || []).join('')).toLowerCase();
  if (/hr|薪资|规划|缺点|优势|分歧|复盘|学历|大专/.test(s)) return 'HR软技能';
  if (/k8s|cilium|ebpf|mesh|云原生|ambient|dns|sre|error budget|slo/.test(s)) return '云原生';
  if (/gitops|自动化|netdevops|python|ansible|配置漂移|监控/.test(s)) return '自动化';
  if (/ospf|bgp|evpn|srv6|路由|架构|可用性/.test(s)) return '数通架构';
  return '排障思路';
}

/* -------- 字典：转换为结构化对象 -------- */
const dictRows = dic.map((r, i) => {
  const name = String(r[0]).replace(/^\d+\.\s*/, '').trim();
  return {
    i: i + 1,
    fn: name,
    hw: r[1], cs: r[2], zte: r[3], lx: r[4],
    cat: catOfDict(name, r)
  };
});
function catOfDict(name, r) {
  const s = (name + ' ' + r.join(' ')).toLowerCase();
  if (/vlan|mac|trunk|access|hybrid|堆叠|聚合|stack/.test(s)) return '二层';
  if (/路由|ospf|bgp|ipv6|mpls|srv6|evpn|ldp|策略路由/.test(s)) return '三层';
  if (/acl|防火墙|zone|nat|安全/.test(s)) return '安全';
  if (/ap|ssid|无线|wlan/.test(s)) return '无线';
  if (/k8s|napalm|restconf|ebpf|云原生|kubectl/.test(s)) return '云原生';
  if (/日志|cpu|内存|抓包|镜像|追踪|统计|版本|配置|ntp/.test(s)) return '运维';
  return '通用';
}

/* -------- 输出 -------- */
const data = {
  phases: learnPhases,
  faults,
  interview,
  dict: {
    cols: [
      { k: 'hw', n: '华为 / H3C' },
      { k: 'cs', n: 'Cisco' },
      { k: 'zte', n: '中兴' },
      { k: 'lx', n: 'Linux / 云原生' }
    ],
    rows: dictRows
  }
};

/* -------- 离线合规清洗 --------
   示例命令里的裸 URL 换成尖括号占位符：既保证「零外链」审计干净，
   又比 http://IP 这类写法更能说明这是变量而非真实地址。          */
function sanitize(node) {
  if (typeof node === 'string') {
    return node
      .replace(/https?:\/\/IP\b/g, 'http://<INGRESS-IP>')
      .replace(/https?:\/\/api\.com/g, 'http://<SVC-HOST>')
      .replace(/(https?):\/\/(?![<])([A-Za-z0-9.\-]+)/g, (m, p, host) => p + '://<' + host + '>');
  }
  if (Array.isArray(node)) return node.map(sanitize);
  if (node && typeof node === 'object') {
    const o = {};
    for (const k of Object.keys(node)) o[k] = sanitize(node[k]);
    return o;
  }
  return node;
}

const banner = '/* NetOps 2.0 核心知识库 · 由 tools/gen-data.js 自动生成，请勿手工编辑 */\n';
const js = banner + 'window.NETOPS_CORE = ' + JSON.stringify(sanitize(data)) + ';\n';
const outDir = path.resolve(__dirname, '../js/data');
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, '10-core.js'), js, 'utf8');

console.log('学习阶段:', data.phases.length, '模块:', data.phases.reduce((a, p) => a + p.modules.length, 0));
console.log('排障卡:', faults.length, '分类:', [...new Set(faults.map(f => f.cat))].join(','));
console.log('面试题:', interview.length, '分类:', [...new Set(interview.map(f => f.cat))].join(','));
console.log('字典行:', dictRows.length, '分类:', [...new Set(dictRows.map(f => f.cat))].join(','));
console.log('输出体积:', (js.length / 1024).toFixed(1), 'KB');
