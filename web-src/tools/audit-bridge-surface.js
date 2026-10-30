/* 桥面门禁 —— @JavascriptInterface 方法必须有消费者
 *
 * 为什么存在（2026-10-31 实测）：原生页分页开关收敛时做桥面审计，发现 6 个
 * `hideNativeXxx` 桥（fav/drawer/me/search/home/quiz）自原生页上线起从未被 JS
 * 调用过 —— JS 只调 `hideAllNativePages()`。死桥安静地躺在 Bridge 里，读代码的人
 * 会以为"单页可隐藏"，实际没有入口。这类债只能靠门禁挡，靠记性必漏。
 *
 * 判定：每个 @JavascriptInterface 方法名，在【web-src/js/*.js + web-src/index.html
 * + app/src/main/java 里的注入 JS 字符串】中至少出现一次 `.name(` 形式（真正的调用
 * 形态）。声明处不算，因为它没有前导点。
 *
 * 例外：`web-src/tools/data/bridge-allowlist.json`（可选）里列出的名字，需带理由，
 * 用于"保留但暂未接线"的桥（例如给下一阶段预留）。默认不存在即全部强制。
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const MAIN = path.join(ROOT, 'app', 'src', 'main', 'java',
  'com', 'netops', 'handbook', 'MainActivity.java');
const ALLOW = path.join(__dirname, 'data', 'bridge-allowlist.json');

function collectMethods() {
  if (!fs.existsSync(MAIN)) return null;          // 公开仓库也可能只有 web-src，跳过
  const src = fs.readFileSync(MAIN, 'utf8');
  const out = [];
  const re = /@JavascriptInterface\s+public\s+[\w\[\].<>]+\s+(\w+)\s*\(/g;
  let m;
  while ((m = re.exec(src)) !== null) out.push(m[1]);
  return out;
}

function corpus() {
  const parts = [];
  const jsDir = path.join(ROOT, 'web-src', 'js');
  if (fs.existsSync(jsDir)) {
    fs.readdirSync(jsDir).filter(f => f.endsWith('.js')).forEach(f => {
      parts.push(fs.readFileSync(path.join(jsDir, f), 'utf8'));
    });
  }
  const idx = path.join(ROOT, 'web-src', 'index.html');
  if (fs.existsSync(idx)) parts.push(fs.readFileSync(idx, 'utf8'));
  /* 原生侧注入 JS 也要算消费者：LensBarView/AppBarView 用 evaluateJavascript
     拼接字符串调桥，这些调用在 web-src 里看不到。 */
  const javaDir = path.join(ROOT, 'app', 'src', 'main', 'java');
  if (fs.existsSync(javaDir)) {
    const walk = d => fs.readdirSync(d, { withFileTypes: true }).forEach(e => {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.java')) parts.push(fs.readFileSync(p, 'utf8'));
    });
    walk(javaDir);
  }
  return parts.join('\n');
}

function analyze() {
  const methods = collectMethods();
  const errs = [];
  if (methods === null) return { errs, notes: ['未找到 MainActivity.java，跳过桥面门禁'], total: 0, dead: [] };
  const allow = fs.existsSync(ALLOW) ? JSON.parse(fs.readFileSync(ALLOW, 'utf8')) : {};
  const text = corpus();
  const dead = [];
  methods.forEach(name => {
    if (allow[name]) return;
    const re = new RegExp('\\.' + name + '\\s*\\(', 'g');
    if (!re.test(text)) dead.push(name);
  });
  dead.forEach(n => errs.push('桥方法无人调用：' + n + '（删掉它，或写进 tools/data/bridge-allowlist.json 并注明理由）'));
  return {
    errs,
    notes: ['桥方法 ' + methods.length + ' 个，死桥 ' + dead.length + ' 个'] +
      (Object.keys(allow).length ? '（豁免 ' + Object.keys(allow).length + ' 个）' : ''),
    total: methods.length,
    dead,
  };
}

function gate() {
  const r = analyze();
  const ok = r.errs.length === 0;
  if (!ok) {
    console.log('✗ 桥面门禁未通过（' + r.errs.length + ' 项）：');
    r.errs.forEach(e => console.log('    - ' + e));
  }
  return Object.assign({ ok }, r);
}

module.exports = { analyze, gate };

if (require.main === module) {
  const r = analyze();
  console.log(r.notes);
  if (!r.errs.length) console.log('✓ 桥面检查通过');
  else { console.log('\n' + r.errs.map(e => '✗ ' + e).join('\n')); if (process.argv.includes('--gate')) process.exit(1); }
}
