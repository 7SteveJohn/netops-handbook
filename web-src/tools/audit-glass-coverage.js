/* 玻璃覆盖率审计 / 构建期防回归门禁
   ------------------------------------------------------------
   背景：壁纸与三种玻璃质感的覆盖一直是「逐组件手写选择器清单」，只写到容器层，
   内层底板（表头、芯片、图标底板、进度轨、开关…）漏在清单外 → 在壁纸上露成白色色块。
   2026-09-22 起改为 token 驱动：背景一律走 --surf-1/2/3（01-tokens.css），
   透明度由 --glass-content-a 统一给（32-boot.js applyGlass）。

   判据（任何「会画出不透明底色」的组件，三选一，否则门禁失败）：
     1. 该组件出现在 has-wallpaper / glass-* / android-webview 覆盖清单里 —— 已有专属处理；
     2. 背景走 --surf-1/2/3（跟随通透度）；
     3. 在 EXCEPTIONS 里登记了理由 —— 代码块、语义色、装饰件等故意保持实色。

   用法：
     node tools/audit-glass-coverage.js            # 人读报告，exit 0
     node tools/audit-glass-coverage.js --gate     # 构建期门禁，违规 exit 1
   可被 build.js require：require('./tools/audit-glass-coverage').gate() */
'use strict';
const fs = require('fs');
const path = require('path');

const CSS_DIR = path.resolve(__dirname, '../css');
const JS_DIR = path.resolve(__dirname, '../js');
const CSS_FILES = ['02-base.css', '03-layout.css', '04-components.css', '05-views.css', '06-anim.css'];
const JS_FILES = ['10-topo.js', '20-ui.js', '30-core.js', '31-views.js', '32-boot.js', '33-apple.js'];
const TEMPLATES = [path.resolve(__dirname, '../index.html')];

/* 故意保持实色的组件：新增条目必须写理由，不能只写类名 */
const EXCEPTIONS = {
  '.term': 'CLI/命令代码块要实底才看得清字符',
  '.term__dot': '终端红黄绿窗控点，品牌装饰色',
  '.cli': 'CLI 模拟器整机底色',
  '.cli__in': 'CLI 输入行跟随终端底色 --term-bg-2',
  '.hl': '搜索命中高亮（琥珀色），要跳出来',
  '.badge--accent': '品牌 accent-soft，不是表面色',
  '.ibtn__badge': '红点提醒，语义色',
  '.bullets--danger': '列表圆点语义色（危险）',
  '.bullets--warn': '列表圆点语义色（注意）',
  '.sec__line': '分隔线用 --border，不是底板',
  '.dtree__num': '树形连接线用 --border',
  '.dtree__step': '树形连接线用 --border',
  '.sheet__grab': '拖拽提示条要稳定对比度，不随通透度消失',
  '.wp-cat': '壁纸分组标题的 1px 收尾线，用 --border 不跟随通透度',
  '.qz__mark': '单选圆点，需与选项文字保持对比',
  '.switch': '开关旋钮（::after 白点）与已选态品牌色，保持实色才看得出圆点',
  '.tab': '底栏激活态为品牌色小圆点（视觉基线，禁止跟随通透度）',
  '.tabbar-regular': '常规底栏激活态同上',
  '.is-active': '激活态品牌色',
  '.is-on': '开关/chip 选中态品牌色',
  '.dark': 'html.dark 下的高亮特例',
};

/* 解析成「不透明实色」的 token / 字面量 */
const SOLID_TOKENS = ['--surface', '--surface-2', '--surface-3', '--bg-elevated'];
const OVERRIDE_MARK = /has-wallpaper|glass-on|glass-liquid|glass-frosted|glass-gaussian|android-webview/;
const TOKEN_DRIVEN = /--surf-[123]\b|--surf-rgb|--surf-a-|var\(--surf-\d\)/;

function stripCommentsKeepLines(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, function (m) {
    return m.replace(/[^\n]/g, ' ');
  });
}
function parseRules(text, out, prefix) {
  out = out || []; prefix = prefix || '';
  let i = 0;
  while (i < text.length) {
    const open = text.indexOf('{', i);
    if (open < 0) break;
    const sel = text.slice(i, open).trim().replace(/\s+/g, ' ');
    let depth = 1, j = open + 1;
    while (j < text.length && depth > 0) {
      if (text[j] === '{') depth++;
      else if (text[j] === '}') depth--;
      j++;
    }
    const body = text.slice(open + 1, j - 1);
    if (sel.startsWith('@')) parseRules(body, out, prefix);
    else if (sel) out.push({ sel: prefix + sel, body: body, where: prefix + sel });
    i = j;
  }
  return out;
}
function decls(body) {
  return body.split(';').map(function (s) { return s.trim(); }).filter(Boolean)
    .map(function (s) {
      const k = s.slice(0, s.indexOf(':')).trim();
      return { k: k, v: s.slice(s.indexOf(':') + 1).trim() };
    });
}
function classesOf(sel) {
  const out = [];
  const re = /\.([a-zA-Z_][\w-]*)/g; let m;
  while ((m = re.exec(sel))) out.push(m[1]);
  return out;
}
function targetClassOf(sel) {
  /* 覆盖规则里“被覆盖的目标”= 每个逗号分支的最后一段类名 */
  const out = [];
  sel.split(',').forEach(function (p) {
    const cs = classesOf(p.trim());
    if (cs.length) out.push(cs[cs.length - 1]);
  });
  return out;
}

function analyze() {
  /* ---- 读 token 表：把 var(--x) 解析到底，但 alpha 通道变量保持原样 ---- */
  const tokens = {};
  const rootRules = parseRules(stripCommentsKeepLines(
    fs.readFileSync(path.join(CSS_DIR, '01-tokens.css'), 'utf8')), []);
  rootRules.forEach(function (r) {
    if (!/(^|,)\s*(html|:root|body|html\.dark)\s*$/.test(r.sel)) return;
    decls(r.body).forEach(function (d) { if (d.k.indexOf('--') === 0) tokens[d.k] = d.v; });
  });
  const ALPHA_VAR = /^--(surf-a|glass-content-a|glass-tint-top-a|glass-tint-bot-a|glass-mask-a)/;
  function resolve(val, depth) {
    depth = depth || 0;
    if (depth > 8) return val;
    return val.replace(/var\((--[\w-]+)(?:,\s*([^()]*))?\)/g, function (whole, name, fb) {
      if (ALPHA_VAR.test(name)) return whole;           /* 透明度通道：保留 var，视为可跟随玻璃 */
      const t = tokens[name];
      if (t === undefined) return fb !== undefined ? fb : whole;
      return resolve(t, depth + 1);
    });
  }
  function isSolid(val) {
    const v = val.replace(/\s+/g, '').toLowerCase();
    if (/rgba?\([\d.]+,[\d.]+,[\d.]+,(1|1\.0)\)/.test(v)) return true;
    if (/^#[0-9a-f]{6}$/.test(v) || /^#[0-9a-f]{3}$/.test(v)) return true;
    if (/[,(](1|1\.0)\)/.test(v) && /rgba?\(/.test(v)) return true;
    return false;
  }

  /* ---- 覆盖清单里的类名 ---- */
  const cssRules = [];
  CSS_FILES.forEach(function (f) {
    parseRules(stripCommentsKeepLines(fs.readFileSync(path.join(CSS_DIR, f), 'utf8')), [], '').forEach(function (r) {
      cssRules.push({ file: f, sel: r.sel, body: r.body });
    });
  });
  const covered = {};
  cssRules.forEach(function (r) {
    if (!OVERRIDE_MARK.test(r.sel)) return;
    targetClassOf(r.sel).forEach(function (c) { covered[c] = true; });
  });

  /* ---- CSS 中画出实色背景的组件 ---- */
  const violations = [];
  cssRules.forEach(function (r) {
    if (OVERRIDE_MARK.test(r.sel)) return;                    /* 覆盖规则自身不算 */
    if (/^\.(icon|svg)|::selection|::-webkit|::-moz/.test(r.sel)) return;
    decls(r.body).forEach(function (d) {
      if (d.k !== 'background' && d.k !== 'background-color') return;
      const raw = d.v;
      if (TOKEN_DRIVEN.test(raw)) return;                     /* 走 token = 已收口 */
      if (/(transparent|none|inherit)\b/.test(raw) && !/rgba/.test(raw)) return;
      const res = resolve(raw);
      if (TOKEN_DRIVEN.test(res)) return;                     /* 间接引用 --surf-* */
      if (/var\(--(surf-a|glass-content-a|glass-tint)/.test(res)) return;
      if (!isSolid(res) && !SOLID_TOKENS.some(function (t) { return raw.indexOf(t) >= 0; })) return;
      const cls = classesOf(r.sel);
      const hit = cls.some(function (c) { return covered[c]; }) ||
        cls.some(function (c) { return EXCEPTIONS['.' + c]; });
      if (!hit) violations.push({ file: r.file, sel: r.sel, decl: d.k + ': ' + raw, res: res.slice(0, 40) });
    });
  });

  /* ---- JS 拼的内联样式与静态模板（这些不吃级联，最容易漏） ---- */
  const inline = [];
  function scanInline(text, where) {
    const re = /background(?:-color)?\s*:\s*var\((--surface(?:-[23])?)\)/g;
    let m;
    while ((m = re.exec(text))) inline.push({ where: where, token: m[1], at: text.slice(0, m.index).split('\n').length });
  }
  JS_FILES.forEach(function (f) {
    let p;
    try { p = path.join(JS_DIR, f); } catch (e) { return; }
    if (fs.existsSync(p)) scanInline(fs.readFileSync(p, 'utf8'), 'js/' + f);
  });
  TEMPLATES.forEach(function (p) {
    if (fs.existsSync(p)) scanInline(fs.readFileSync(p, 'utf8'), 'web-src/index.html');
  });

  return { violations: violations, inline: inline, coveredCount: Object.keys(covered).length };
}

function gate() {
  const a = analyze();
  let fail = false;
  if (a.violations.length) {
    fail = true;
    console.log('✗ 玻璃覆盖门禁：' + a.violations.length + ' 处不透明底色既没走 --surf-* token，也不在覆盖清单/例外白名单里');
    a.violations.forEach(function (v) {
      console.log('    ' + v.file + '  ' + v.sel + '  { ' + v.decl + ' }');
    });
    console.log('    修法：背景改 var(--surf-1/2/3)；确需实色则在 tools/audit-glass-coverage.js 的 EXCEPTIONS 写理由。');
  }
  if (a.inline.length) {
    fail = true;
    console.log('✗ 玻璃覆盖门禁：' + a.inline.length + ' 处内联样式直接吃 --surface*（内联不吃级联，壁纸/玻璃下必然露白）');
    a.inline.forEach(function (v) { console.log('    ' + v.where + ':' + v.at + '  var(' + v.token + ')'); });
    console.log('    修法：换成 var(--surf-1/2/3)。');
  }
  return { ok: !fail, violations: a.violations, inline: a.inline };
}

module.exports = { analyze: analyze, gate: gate, EXCEPTIONS: EXCEPTIONS };

if (require.main === module) {
  const a = analyze();
  console.log('覆盖清单收录类名: ' + a.coveredCount + ' 个');
  console.log('不透明底色违规: ' + a.violations.length + ' 处   内联直连 --surface*: ' + a.inline.length + ' 处');
  a.violations.forEach(function (v) { console.log('  ✗ ' + v.file + '  ' + v.sel + '  { ' + v.decl + ' }'); });
  a.inline.forEach(function (v) { console.log('  ✗ 内联 ' + v.where + ':' + v.at + ' var(' + v.token + ')'); });
  if (process.argv.indexOf('--gate') >= 0) {
    const g = gate();
    process.exit(g.ok ? 0 : 1);
  }
}
