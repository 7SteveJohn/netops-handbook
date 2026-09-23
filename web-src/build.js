/* ============================================================
 * NetOps 2.0 · 构建脚本
 * 将 web-src 下的 CSS / JS / SVG 全部内联，产出单文件自包含 HTML
 *   node web-src/build.js  [--no-min]
 * ============================================================ */
const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const OUT = path.resolve(ROOT, '..', 'app', 'src', 'main', 'assets', 'index.html');
const MIN = !process.argv.includes('--no-min');

const CSS_FILES = [
  'css/00-recipe.css', 'css/01-tokens.css', 'css/02-base.css', 'css/03-layout.css',
  'css/04-components.css', 'css/05-views.css', 'css/06-anim.css'
];
const JS_FILES = [
  'js/data/10-core.js', 'js/data/20-extend.js', 'js/data/21-quiz.js',
  'js/data/22-cli-rules.js',
  'js/10-topo.js', 'js/20-ui.js', 'js/30-core.js', 'js/31-views.js', 'js/32-boot.js', 'js/33-apple.js'
];

const read = f => fs.readFileSync(path.join(ROOT, f), 'utf8');
const kb = v => (Buffer.byteLength(typeof v === 'string' ? v : String(v), 'utf8') / 1024).toFixed(1) + ' KB';

/* ---------- CSS 压缩（安全：仅去注释与冗余空白） ---------- */
function minCss(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\s*([{}:;,>~])\s*/g, '$1')
    .replace(/;}/g, '}')
    .replace(/\s+/g, ' ')
    .replace(/\( /g, '(').replace(/ \)/g, ')')
    .trim();
}

/* ---------- JS 注释剥离（词法感知，避免破坏字符串/正则） ---------- */
function stripJsComments(src) {
  let out = '', i = 0;
  const n = src.length;
  let prevSignificant = '';
  while (i < n) {
    const c = src[i], c2 = src[i + 1];
    /* 行注释 */
    if (c === '/' && c2 === '/') {
      while (i < n && src[i] !== '\n') i++;
      continue;
    }
    /* 块注释 */
    if (c === '/' && c2 === '*') {
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) i++;
      i += 2;
      out += ' ';
      continue;
    }
    /* 字符串 */
    if (c === '"' || c === "'" || c === '`') {
      const q = c; out += c; i++;
      while (i < n) {
        if (src[i] === '\\') { out += src[i] + src[i + 1]; i += 2; continue; }
        out += src[i];
        if (src[i] === q) { i++; break; }
        i++;
      }
      prevSignificant = q;
      continue;
    }
    /* 正则字面量：仅当前一个有意义字符允许正则出现时 */
    if (c === '/' && /[=(,:[!&|?{};+\-*%~^]|^$/.test(prevSignificant)) {
      let j = i + 1, inClass = false, ok = false;
      while (j < n) {
        const ch = src[j];
        if (ch === '\\') { j += 2; continue; }
        if (ch === '[') inClass = true;
        else if (ch === ']') inClass = false;
        else if (ch === '/' && !inClass) { ok = true; break; }
        else if (ch === '\n') break;
        j++;
      }
      if (ok) {
        j++;
        while (j < n && /[gimsuyd]/.test(src[j])) j++;
        out += src.slice(i, j); i = j; prevSignificant = '/';
        continue;
      }
    }
    out += c;
    if (!/\s/.test(c)) prevSignificant = c;
    i++;
  }
  return out;
}

function minJs(src) {
  let s = stripJsComments(src);
  /* 逐行去首尾空白并丢弃空行；不做跨行合并，规避 ASI 风险 */
  s = s.split('\n').map(l => l.replace(/[ \t]+$/, '').replace(/^[ \t]+/, '')).filter(l => l.length).join('\n');
  return s;
}

/* ---------- SVG 精灵压缩 ---------- */
function minSvg(src) {
  return src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/>\s+</g, '><')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

/* ---------- 组装 ---------- */
/* 语法与结构自检：以前只要文件能 readFileSync 就往下拼，一个语法错照样打印
   「✓ 离线校验通过」，然后真机上是白屏 —— 这是最难在设备上发现的一类错。
   vm.Script 只编译不执行，能抓语法错；CSS 用括号配平兜住「注释吃掉半个规则」。 */
const vm = require('vm');
function syntaxCheck(files, isCss) {
  const errs = [];
  files.forEach(f => {
    const raw = read(f);
    if (isCss) {
      const noCmt = raw.replace(/\/\*[\s\S]*?\*\//g, ' ');
      let bal = 0, line = 1, at = 0;
      for (let i = 0; i < noCmt.length; i++) {
        const c = noCmt[i];
        if (c === '\n') line++;
        else if (c === '{') bal++;
        else if (c === '}') { bal--; if (bal < 0 && !at) at = line; }
      }
      if (bal !== 0) errs.push(f + '：花括号不配平（' + (bal > 0 ? '缺 ' + bal + ' 个 }' : '第 ' + at + ' 行起多出 }') + '）');
      const un = (raw.match(/\/\*/g) || []).length - (raw.match(/\*\//g) || []).length;
      if (un !== 0) errs.push(f + '：块注释未闭合（' + un + ' 个 /* 没有对应的 */）');
    } else {
      try { new vm.Script(raw, { filename: f }); }
      catch (e) {
        const ln = (String(e.stack || '').match(new RegExp(f.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ':(\\d+)')) || [])[1];
        errs.push(f + '：JS 语法错误' + (ln ? '（第 ' + ln + ' 行）' : '') + ' — ' + String(e.message).slice(0, 90));
      }
    }
  });
  return errs;
}

function build() {
  const t0 = Date.now();
  /* 玻璃配方表 → css/00-recipe.css。构建期直接重生成：表是唯一真源，
     这样"改了 json 忘了生成"不可能发生（比报错更进一步）。 */
  try {
    const recipe = require('./tools/gen-glass-recipe');
    const css = recipe.build();
    if (!fs.existsSync(recipe.OUT) || fs.readFileSync(recipe.OUT, 'utf8') !== css) {
      fs.writeFileSync(recipe.OUT, css, 'utf8');
      console.log('  · 玻璃配方表已重新生成 css/00-recipe.css');
    }
  } catch (e) {
    console.error('\n  ✗ 玻璃配方表生成失败（glass-recipe.json 有问题？）：' + e.message);
    process.exit(1);
  }
  const report = [];

  const preErrs = syntaxCheck(CSS_FILES, true).concat(syntaxCheck(JS_FILES, false));
  if (preErrs.length) {
    console.log('\n  ✗ 源文件语法自检未通过（拒绝产出，避免「构建绿了、App 白屏」）：');
    preErrs.forEach(e => console.log('    - ' + e));
    process.exitCode = 1;
    return;
  }

  /* 存储兜底门禁：WebView 在 file:// 源或存储被禁用时读 localStorage 会抛 SecurityError，
     启动路径上任何一处未兜底的调用都让首帧永久空白（实测由 tools/smoke.js 复现）。
     要求：与 try 同行，或走 32-boot.js 的 lsGet/lsSet。 */
  const storeErrs = [];
  JS_FILES.forEach(f => {
    read(f).split(/\r?\n/).forEach((l, i) => {
      if (l.indexOf('localStorage.') < 0) return;
      if (/^\s*(\*|\/\/|\/\*)/.test(l)) return;
      if (l.indexOf('try') >= 0) return;
      storeErrs.push(path.basename(f) + ':' + (i + 1) + ' 未兜底的 localStorage 调用 → 改走 lsGet/lsSet，或写成单行 try');
    });
  });
  if (storeErrs.length) {
    console.log('\n  ✗ 存储兜底门禁未通过（这些位置会让 App 在存储不可用时白屏）：');
    storeErrs.forEach(e => console.log('    - ' + e));
    process.exitCode = 1;
    return;
  }

  let css = '';
  CSS_FILES.forEach(f => {
    const raw = read(f);
    css += '\n/* ' + path.basename(f) + ' */\n' + raw;
    report.push(['CSS ' + path.basename(f), raw]);
  });
  const cssOut = MIN ? minCss(css) : css;

  let js = '';
  JS_FILES.forEach(f => {
    const raw = read(f);
    js += '\n;/* ===== ' + path.basename(f) + ' ===== */\n' + raw;
    report.push(['JS  ' + path.basename(f), raw]);
  });
  const jsOut = MIN ? minJs(js) : js;

  const spriteRaw = read('html/sprite.svg');
  report.push(['SVG sprite.svg', spriteRaw]);
  const sprite = MIN ? minSvg(spriteRaw) : spriteRaw;

  let html = read('index.html');
  html = html.replace('/*__CSS__*/', () => cssOut);
  html = html.replace('<!--__SPRITE__-->', () => sprite);
  html = html.replace('/*__JS__*/', () => jsOut);
  if (MIN) {
    html = html.replace(/\n\s*\n/g, '\n');
  }
  /* 占位符若被改名/漏写，replace 会静默不生效 —— 产物照样“构建成功”但什么都没有 */
  const leftover = ['/*__CSS__*/', '<!--__SPRITE__-->', '/*__JS__*/'].filter(t => html.indexOf(t) >= 0);
  if (leftover.length) problems.push('模板占位符未被替换：' + leftover.join(' '));
  if (cssOut.length < 1024) problems.push('CSS 内联结果异常小（' + cssOut.length + ' 字节），疑似文件读取失败');
  if (jsOut.length < 4096) problems.push('JS 内联结果异常小（' + jsOut.length + ' 字节），疑似文件读取失败');
  if (sprite.length < 512) problems.push('SVG sprite 内联结果异常小（' + sprite.length + ' 字节）');
  try { new vm.Script(jsOut, { filename: 'inlined-js' }); }
  catch (e) { problems.push('内联后的 JS 无法编译（拼接把某处截断了？）：' + String(e.message).slice(0, 120)); }

  /* ---------- 离线合规校验 ---------- */
  const problems = [];
  const externals = html.match(/(?:src|href)\s*=\s*["'](?!#)[^"']*["']/gi) || [];
  externals.forEach(m => {
    if (/["'](https?:)?\/\//i.test(m)) problems.push('外部资源引用: ' + m);
  });
  if (/@import\s/i.test(html)) problems.push('CSS @import 未内联');
  /* 仅检查 <style> 块内的 url()（全部 CSS 都内联在 style 标签中），
     避免误伤 JS 字符串拼接 —— 如壁纸功能运行时拼 'url(' + saved + ')'，
     以及 URL.createObjectURL() 等大写调用 */
  const styleBlocks = html.match(/<style[^>]*>[\s\S]*?<\/style>/gi) || [];
  const urlRefs = (styleBlocks.join('') || '').match(/url\(\s*['"]?(?!data:|#)[^)'"]+['"]?\s*\)/gi) || [];
  urlRefs.forEach(m => problems.push('外部 url() 引用: ' + m));
  const httpText = html.match(/https?:\/\/[^\s"'<>)]+/gi) || [];
  const allowed = /w3\.org|schemas\.android\.com/i;
  httpText.filter(u => !allowed.test(u)).forEach(u => problems.push('残留外链文本: ' + u));

  /* ---------- 玻璃覆盖门禁 ----------
     壁纸/玻璃的覆盖过去靠逐组件手写清单，新增组件极易漏（表现为壁纸上的白色色块）。
     现由 --surf-* token 收口，违规即构建失败。详见 tools/audit-glass-coverage.js 头注。 */
  let glass = { ok: true, violations: [], inline: [] };
  try {
    glass = require('./tools/audit-glass-coverage').gate();
  } catch (e) {
    problems.push('玻璃覆盖门禁无法运行: ' + e.message);
  }

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, html, 'utf8');

  /* ---------- 内容一致性门禁 ----------
     守住本项目真出过的错法：字典行形状、模块字段缺失、题库答案下标、
     README 数字与实际数据对不上（虚标）、已否决的定位措辞回流、忘跑 gen-data.js。
     必须放在写盘之后：它要核对的是「本次刚产出的产物」，不是上一版。 */
  let content = { ok: true, errs: [], counts: {} };
  try {
    content = require('./tools/audit-content-consistency').gate();
  } catch (e) {
    problems.push('内容一致性门禁无法运行: ' + e.message);
  }

  /* ---------- 可读性门禁 ----------
     通透度 × 壁纸是用户自由组合，"字看不见"靠肉眼点检必漏（本项目真漏过：
     14 张内置壁纸 100% 面积不达 AA、最差 1.00:1）。这里把运行时的下限求解
     在构建期复算一遍。工具自带退出码与中文报告，用子进程跑，保持它能单独执行。 */
  let contrastGate = { ok: true, msg: '' };
  try {
    contrastGate.msg = require('child_process')
      .execFileSync(process.execPath, [path.join(__dirname, 'tools', 'audit-contrast.js')], { encoding: 'utf8' })
      .trim();
  } catch (e) {
    contrastGate.ok = false;
    contrastGate.msg = String(e.stdout || e.stderr || e.message).trim();
  }

  /* ---------- 配方 token 消费门禁 ----------
     glass-recipe.json 自称"唯一参数来源"，但实测曾有 13 个 token 零消费者 —— 改它们
     屏幕上什么都不变。清完必须立闸，否则死参数会长回来。详见 tools/audit-recipe-consumers.js。 */
  let tokensGate = { ok: true, msg: '' };
  try {
    tokensGate.msg = require('child_process')
      .execFileSync(process.execPath, [path.join(__dirname, 'tools', 'audit-recipe-consumers.js')], { encoding: 'utf8' })
      .trim();
    tokensGate.ok = /全部有消费者/.test(tokensGate.msg);
  } catch (e) {
    tokensGate.ok = false;
    tokensGate.msg = String(e.stdout || e.stderr || e.message).trim();
  }

  /* ---------- 报告 ---------- */
  console.log('\n  NetOps 2.0 构建' + (MIN ? '（压缩）' : '（未压缩）'));
  console.log('  ' + '-'.repeat(46));
  report.forEach(([n, s]) => console.log('  ' + n.padEnd(30) + kb(s).padStart(12)));
  console.log('  ' + '-'.repeat(46));
  console.log('  ' + 'CSS 内联后'.padEnd(28) + kb(cssOut).padStart(12));
  console.log('  ' + 'JS  内联后'.padEnd(28) + kb(jsOut).padStart(12));
  console.log('  ' + 'SVG 内联后'.padEnd(28) + kb(sprite).padStart(12));
  console.log('  ' + '-'.repeat(46));
  console.log('  ' + '产物'.padEnd(30) + kb(html).padStart(12));
  console.log('  → ' + path.relative(path.resolve(ROOT, '..'), OUT).replace(/\\/g, '/'));

  const failed = problems.length > 0 || !glass.ok || !content.ok || !contrastGate.ok || !tokensGate.ok;
  if (failed) {
    if (problems.length) {
      console.log('\n  ✗ 离线校验未通过：');
      problems.forEach(p => console.log('    - ' + p));
    }
    if (!glass.ok) {
      console.log('\n  ✗ 玻璃覆盖门禁未通过（壁纸上会露白色色块，见 tools/audit-glass-coverage.js）：');
      glass.violations.forEach(v => console.log('    - ' + v.file + '  ' + v.sel + '  { ' + v.decl + ' }'));
      glass.inline.forEach(v => console.log('    - 内联 ' + v.where + ':' + v.at + '  var(' + v.token + ')'));
    }
    if (!content.ok) {
      console.log('\n  ✗ 内容一致性门禁未通过（见 tools/audit-content-consistency.js）：');
      content.errs.forEach(v => console.log('    - ' + v));
    }
    if (!contrastGate.ok) {
      console.log('\n  ✗ 可读性门禁未通过（见 tools/audit-contrast.js）：');
      console.log('    ' + contrastGate.msg.replace(/\n/g, '\n    '));
    }
    if (!tokensGate.ok) {
      console.log('\n  ✗ 配方 token 消费门禁未通过（见 tools/audit-recipe-consumers.js）：');
      console.log('    ' + tokensGate.msg.replace(/\n/g, '\n    '));
    }
    process.exitCode = 1;
  } else {
    console.log('\n  ✓ 离线校验通过：零外部请求 / 零 CDN / 全部资源内联');
    console.log('  ✓ 玻璃覆盖门禁通过：不透明底色全部走 --surf-* token 或登记了例外理由');
    console.log('  ✓ 内容一致性门禁通过：' + JSON.stringify(content.counts) + '（README 数字与产物措辞已比对）');
    console.log('  ' + contrastGate.msg);
    console.log('  ' + tokensGate.msg);
  }
  console.log('  用时 ' + (Date.now() - t0) + 'ms\n');
}

build();
