/* ============================================================
 * gen-native-data.js — 原生化数据管道（N0）
 * 把 js/data/*（唯一真源）归一化为 app/src/main/assets/native-data.json，
 * 供原生视图直接读取，内容不再经过 WebView。
 *
 * 用法: node tools/gen-native-data.js
 * 输出: ../app/src/main/assets/native-data.json
 * ============================================================ */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '..');
const DATA_DIR = path.join(ROOT, 'js', 'data');
const OUT = path.join(ROOT, '..', 'app', 'src', 'main', 'assets', 'native-data.json');

/* ---- 1. 在沙箱里跑数据文件（它们只写 window.*） ---- */
const sandbox = { window: {} };
vm.createContext(sandbox);
['10-core.js', '20-extend.js', '21-quiz.js'].forEach(f => {
  const src = fs.readFileSync(path.join(DATA_DIR, f), 'utf8');
  vm.runInContext(src, sandbox, { filename: f });
});
const W = sandbox.window;
const CORE = W.NETOPS_CORE || {};
const EXT = W.NETOPS_EXT || {};
const QUIZ = W.NETOPS_QUIZ || [];

/* ---- 2. 归一化工具 ---- */
const cleanTitle = t => String(t || '').replace(/^\s*(Z\d+|G\d+|IVQ\d+)[.、]\s*/, '');
const str = v => (v == null ? '' : String(v));
const arr = v => (Array.isArray(v) ? v : []);

/* block 构造器 —— 与 Web 版 bodyOf/modBody/faultBody/ivBody 一一对应 */
const B = {
  h: (t, icon) => ({ k: 'h', t, icon: icon || '' }),
  note: (tone, t) => ({ k: 'note', tone, t: str(t) }),
  code: (t, label, out) => ({ k: 'code', t: str(t), label: label || '', out: !!out }),
  ul: (t, items, tone) => ({ k: 'ul', items: arr(items).map(str), tone: tone || '' }),
  steps: (t, items) => ({ k: 'steps', items: arr(items).map(str) }),
  tree: (t, items) => ({ k: 'tree', items: arr(items).map(str) }),
  topo: dg => ({ k: 'topo', dg: dg })
};

/* 命令块平台标签：与 A.cmdLabel 同规则（原生侧不猜色，只要标签） */
function cmdLabel(c) {
  const s = str(c);
  if (/^\s*(&lt;|<)?Huawei|\[Huawei|\bdisplay\b|system-view/m.test(s)) return 'VRP · 华为';
  if (/\bshow run\b|\bconf t\b|Cisco|\bswitchport\b/i.test(s)) return 'IOS · Cisco';
  if (/kubectl|docker|helm/.test(s)) return 'kubectl / docker';
  if (/^\s*(sudo|ip |ping|tcpdump|curl|systemctl|cat |vi )/m.test(s)) return 'shell · linux';
  if (/python|import /.test(s)) return 'python';
  return 'command';
}

/* ---- 3. 模块 / 排障 / 面试 归一为 blocks[] ---- */
function moduleBlocks(m) {
  const b = [];
  if (m.y) b.push(B.h('原理速记', 'book'), B.note('info', m.y));
  if (m.dg) b.push(B.h('拓扑示意', 'network'), B.topo(m.dg));
  if (m.c) b.push(B.h('核心命令', 'terminal'), B.code(m.c, cmdLabel(m.c), false));
  if (m.o) b.push(B.h('回显参考（示意，非设备逐字输出）', 'activity'), B.code(m.o, 'output', true));
  if (arr(m.j).length) b.push(B.h('关键解读', 'eye'), B.ul('', m.j));
  if (arr(m.u).length) b.push(B.h('应用场景', 'target'), B.ul('', m.u));
  if (arr(m.w).length) b.push(B.h('避坑指南', 'alert'), B.ul('', m.w, 'danger'));
  if (m.v) b.push(B.h('验证方式', 'check-circle'), B.note('ok', m.v));
  if (arr(m.l).length) b.push(B.h('动手实验', 'flask'), B.steps('', m.l));
  return b;
}
function faultBlocks(f) {
  const b = [];
  if (f.sym) b.push(B.h('现象描述', 'alert'), B.note('danger', f.sym));
  if (f.cause) b.push(B.h('根因分析', 'help'), B.note('warn', f.cause));
  if (f.dg) b.push(B.h('拓扑示意', 'network'), B.topo(f.dg));
  if (f.c) b.push(B.h('处置命令', 'terminal'), B.code(f.c, cmdLabel(f.c), false));
  if (f.o) b.push(B.h('回显参考（示意，非设备逐字输出）', 'activity'), B.code(f.o, 'output', true));
  if (arr(f.j).length) b.push(B.h('关键解读', 'eye'), B.ul('', f.j));
  if (arr(f.l).length) b.push(B.h('排查路径', 'route'), B.tree('', f.l));
  if (arr(f.u).length) b.push(B.h('适用场景', 'target'), B.ul('', f.u));
  if (arr(f.w).length) b.push(B.h('避坑指南', 'alert'), B.ul('', f.w, 'danger'));
  if (f.v) b.push(B.h('验证闭环', 'check-circle'), B.note('ok', f.v));
  return b;
}
function ivBlocks(q) {
  const b = [];
  if (q.point) b.push(B.h('考察点', 'target'), B.note('info', q.point));
  if (q.star) b.push(B.h('STAR 拆解', 'zap'), B.note('warn', q.star));
  if (q.answer) b.push(B.h('参考话术', 'award'), B.note('plain', q.answer));
  if (q.fb) b.push(B.h('面试官视角', 'info'), B.note('plain', q.fb));
  if (arr(q.j).length) b.push(B.h('加分项', 'eye'), B.ul('', q.j));
  if (arr(q.u).length) b.push(B.h('常见追问', 'briefcase'), B.ul('', q.u));
  if (arr(q.w).length) b.push(B.h('雷区', 'alert'), B.ul('', q.w, 'danger'));
  return b;
}

/* ---- 4. 组装 ---- */
const phases = arr(CORE.phases).map(p => ({
  id: p.id, title: p.title, short: p.short || '', color: p.color || '', desc: p.desc || '',
  mods: arr(p.modules).map((m, i) => ({
    id: m.id, t: cleanTitle(m.t), raw: m.t, n: i + 1, tc: arr(p.modules).length,
    color: p.color || '', pshort: p.short || '',
    dg: !!m.dg, lab: arr(m.l).length > 0,
    blocks: moduleBlocks(m)
  }))
}));

const faults = arr(CORE.faults).map(f => ({
  id: f.id, t: cleanTitle(f.t), cat: f.cat || '', blocks: faultBlocks(f)
}));

const interview = arr(CORE.interview).map(q => ({
  id: q.id, t: cleanTitle(q.t), cat: q.cat || '', blocks: ivBlocks(q)
}));

/* 壁纸清单（32-boot.js 里的字面量；一行正则取走，不复制第二份） */
let wallpapers = [];
try {
  const boot = fs.readFileSync(path.join(ROOT, 'js', '32-boot.js'), 'utf8');
  const m = boot.match(/var WALLPAPERS = (\[[\s\S]*?\]);/);
  if (m) wallpapers = JSON.parse(m[1].replace(/,\s*\]/, ']'));
} catch (e) { console.warn('[native-data] WALLPAPERS 提取失败', e.message); }

const out = {
  meta: {
    v: 1,
    gen: new Date().toISOString(),
    counts: {
      phases: phases.length,
      modules: phases.reduce((n, p) => n + p.mods.length, 0),
      faults: faults.length,
      interview: interview.length,
      quiz: arr(QUIZ).length,
      refs: arr(EXT.refs).length,
      glossary: arr(EXT.glossary).length,
      dict: arr((CORE.dict || {}).rows).length
    }
  },
  phases, faults, interview,
  dict: CORE.dict || { cols: [], rows: [] },
  refs: arr(EXT.refs),
  glossary: arr(EXT.glossary),
  quiz: arr(QUIZ),
  roadmap: arr(EXT.roadmap),
  wallpapers
};

const json = JSON.stringify(out);
out.meta.hash = crypto.createHash('sha1').update(json).digest('hex').slice(0, 12);
const finalJson = JSON.stringify(out, null, 0);
fs.writeFileSync(OUT, finalJson);

console.log('[native-data] 写出', OUT);
console.log('[native-data] 大小', (Buffer.byteLength(finalJson) / 1024).toFixed(1) + 'KB');
console.log('[native-data] 计数', JSON.stringify(out.meta.counts));
console.log('[native-data] hash', out.meta.hash);
