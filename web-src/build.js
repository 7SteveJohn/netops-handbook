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
  'css/01-tokens.css', 'css/02-base.css', 'css/03-layout.css',
  'css/04-components.css', 'css/05-views.css', 'css/06-anim.css'
];
const JS_FILES = [
  'js/data/10-core.js', 'js/data/20-extend.js', 'js/data/21-quiz.js',
  'js/10-topo.js', 'js/20-ui.js', 'js/30-core.js', 'js/31-views.js', 'js/32-boot.js', 'js/33-apple.js'
];

const read = f => fs.readFileSync(path.join(ROOT, f), 'utf8');
const kb = n => (n / 1024).toFixed(1) + ' KB';

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
function build() {
  const t0 = Date.now();
  const report = [];

  let css = '';
  CSS_FILES.forEach(f => {
    const raw = read(f);
    css += '\n/* ' + path.basename(f) + ' */\n' + raw;
    report.push(['CSS ' + path.basename(f), raw.length]);
  });
  const cssOut = MIN ? minCss(css) : css;

  let js = '';
  JS_FILES.forEach(f => {
    const raw = read(f);
    js += '\n;/* ===== ' + path.basename(f) + ' ===== */\n' + raw;
    report.push(['JS  ' + path.basename(f), raw.length]);
  });
  const jsOut = MIN ? minJs(js) : js;

  const spriteRaw = read('html/sprite.svg');
  report.push(['SVG sprite.svg', spriteRaw.length]);
  const sprite = MIN ? minSvg(spriteRaw) : spriteRaw;

  let html = read('index.html');
  html = html.replace('/*__CSS__*/', () => cssOut);
  html = html.replace('<!--__SPRITE__-->', () => sprite);
  html = html.replace('/*__JS__*/', () => jsOut);
  if (MIN) {
    html = html.replace(/\n\s*\n/g, '\n');
  }

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

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, html, 'utf8');

  /* ---------- 报告 ---------- */
  console.log('\n  NetOps 2.0 构建' + (MIN ? '（压缩）' : '（未压缩）'));
  console.log('  ' + '-'.repeat(46));
  report.forEach(([n, s]) => console.log('  ' + n.padEnd(30) + kb(s).padStart(12)));
  console.log('  ' + '-'.repeat(46));
  console.log('  ' + 'CSS 内联后'.padEnd(28) + kb(cssOut.length).padStart(12));
  console.log('  ' + 'JS  内联后'.padEnd(28) + kb(jsOut.length).padStart(12));
  console.log('  ' + 'SVG 内联后'.padEnd(28) + kb(sprite.length).padStart(12));
  console.log('  ' + '-'.repeat(46));
  console.log('  ' + '产物'.padEnd(30) + kb(html.length).padStart(12));
  console.log('  → ' + path.relative(path.resolve(ROOT, '..'), OUT).replace(/\\/g, '/'));

  if (problems.length) {
    console.log('\n  ✗ 离线校验未通过：');
    problems.forEach(p => console.log('    - ' + p));
    process.exitCode = 1;
  } else {
    console.log('\n  ✓ 离线校验通过：零外部请求 / 零 CDN / 全部资源内联');
  }
  console.log('  用时 ' + (Date.now() - t0) + 'ms\n');
}

build();
