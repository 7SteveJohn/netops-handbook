/* ============================================================
   门禁：玻璃板上的文字可读性（构建期复算）
   ------------------------------------------------------------
   为什么要有它：通透度滑块 + 壁纸是用户自由组合，"字看不见"这种缺陷
   靠肉眼点检必然漏 —— 上线前实测 14 张内置壁纸，三级文字 100% 面积
   低于 WCAG AA 的 4.5:1，最差 1.00:1（等于隐形），而且把通透度拉满也救不回来。
   现在运行时按壁纸亮度带算下限兜住（32-boot.js measureWallFloor），
   本门禁把同一套算法在构建期再跑一遍：任何一张壁纸、明暗两套主题、
   二级与三级文字，任何一处低于 4.5:1 就构建失败。

   数据来源 tools/data/wall-lum.json：预模糊（24px 降采样）后的相对亮度分位。
   Node 解不了 webp，所以那份分位是浏览器画布采样出来的，用 sha1 当哨兵：
   换图 / 加图而没重新采样，这里会直接报错而不是悄悄用旧数据。
   ============================================================ */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '..');
const WALL_DIR = path.resolve(ROOT, '../app/src/main/assets/wallpapers');
const DATA = path.join(__dirname, 'data', 'wall-lum.json');
const AA = 4.5;               /* WCAG 2.1 AA，正文/小字 */
const MAX_A = 0.96;           /* 再厚就不是玻璃了 */

const errs = [];
const s2l = c => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
const relLum = (r, g, b) => 0.2126 * s2l(r) + 0.7152 * s2l(g) + 0.0722 * s2l(b);
const contrast = (l1, l2) => (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
const hex = h => {
  const x = h.replace('#', '');
  return [parseInt(x.slice(0, 2), 16), parseInt(x.slice(2, 4), 16), parseInt(x.slice(4, 6), 16)];
};
const read = p => fs.readFileSync(p, 'utf8');

/* ---------- 1. 采样数据本身可信吗 ---------- */
const data = JSON.parse(read(DATA));
const onDisk = fs.readdirSync(WALL_DIR).filter(f => /\.webp$/i.test(f)).sort();
const known = Object.keys(data.wallpapers).sort();
if (JSON.stringify(onDisk) !== JSON.stringify(known)) {
  errs.push('壁纸清单与采样数据不一致：磁盘 ' + onDisk.length + ' 张 / 数据 ' + known.length +
    ' 条；缺 ' + onDisk.filter(f => !data.wallpapers[f]).join(',') +
    ' 多余 ' + known.filter(f => onDisk.indexOf(f) < 0).join(','));
}
for (const f of known) {
  if (!onDisk.includes(f)) continue;
  const buf = fs.readFileSync(path.join(WALL_DIR, f));
  const sha = crypto.createHash('sha1').update(buf).digest('hex').slice(0, 12);
  if (sha !== data.wallpapers[f].sha1)
    errs.push(f + ' 已改动但没重新采样（sha1 ' + data.wallpapers[f].sha1 + ' → ' + sha + '）；跑 tools/gen-wall-lum.js');
}

/* ---------- 2. 文字 token 与运行时求解器必须一致 ---------- */
const tokens = read(path.join(ROOT, 'css', '01-tokens.css'));
const lightTok = tokens.match(/body\.glass-on\.has-wallpaper \{[^}]*--text-2:\s*(#[0-9a-f]{6})[^}]*--text-3:\s*(#[0-9a-f]{6})/i);
const darkTok = tokens.match(/html\.dark body\.glass-on\.has-wallpaper \{[^}]*--text-2:\s*(#[0-9a-f]{6})[^}]*--text-3:\s*(#[0-9a-f]{6})/i);
const hiLightTok = tokens.match(/html\.hi-contrast body\.glass-on\.has-wallpaper \{[^}]*--text-2:\s*(#[0-9a-f]{6})[^}]*--text-3:\s*(#[0-9a-f]{6})/i);
const hiDarkTok = tokens.match(/html\.dark\.hi-contrast body\.glass-on\.has-wallpaper \{[^}]*--text-2:\s*(#[0-9a-f]{6})[^}]*--text-3:\s*(#[0-9a-f]{6})/i);
if (!lightTok) errs.push('01-tokens.css 里找不到玻璃作用域的 --text-2/--text-3 覆盖');
if (!darkTok) errs.push('01-tokens.css 里找不到暗色玻璃作用域的 --text-2/--text-3 覆盖');
if (!hiLightTok) errs.push('01-tokens.css 里找不到 html.hi-contrast 的浅色文字覆盖（增强档只加厚玻璃到不了 7:1）');
if (!hiDarkTok) errs.push('01-tokens.css 里找不到 html.dark.hi-contrast 的暗色文字覆盖');

const boot = read(path.join(ROOT, 'js', '32-boot.js'));
const dimM = boot.match(/var DIM_LIGHT = ([\d.]+), DIM_DARK = ([\d.]+);/);
const DIMS = dimM ? { light: Number(dimM[1]), dark: Number(dimM[2]) } : null;
/* 求解器用的 dim 必须和真正作用到 .wall 上的 --bg-bri 是同一对数 */
const briM = boot.match(/--bg-bri'[\s\S]{0,200}?'(\d+)%'\s*:\s*'(\d+)%'\)/);
if (!DIMS) errs.push('32-boot.js 里找不到 var DIM_LIGHT / DIM_DARK（背景压暗系数）');
else if (!briM) errs.push('32-boot.js 里 applyGlass 没按主题写 --bg-bri，dim 模型与实际渲染脱节');
else if (Math.abs(DIMS.dark * 100 - Number(briM[1])) > 0.01 || Math.abs(DIMS.light * 100 - Number(briM[2])) > 0.01)
  errs.push('DIM_LIGHT/DIM_DARK = ' + DIMS.light + '/' + DIMS.dark + ' 与 --bg-bri 的 ' +
    briM[2] + '%/' + briM[1] + '% 不一致：一边改了另一边没改，下限会算错');

const tgtM = boot.match(/var AA_TARGET = ([\d.]+), HI_TARGET = ([\d.]+);/);
const TARGETS = tgtM ? { aa: Number(tgtM[1]), hi: Number(tgtM[2]) } : null;
if (!TARGETS) errs.push('32-boot.js 里找不到 var AA_TARGET / HI_TARGET（两档对比度目标）');
else if (TARGETS.aa !== AA) errs.push('门禁的 AA=' + AA + ' 与运行时的 AA_TARGET=' + TARGETS.aa + ' 不一致');

const combos = [...boot.matchAll(/k:\s*'(light|dark)',\s*plate:\s*([\d.]+|relLum\(44, 44, 46\)),\s*dim:\s*(DIM_LIGHT|DIM_DARK|[\d.]+),\s*text:\s*relLum\((\d+), (\d+), (\d+)\),\s*hiText:\s*relLum\((\d+), (\d+), (\d+)\)/g)]
  .map(m => ({
    k: m[1],
    plate: m[2].startsWith('relLum') ? relLum(44, 44, 46) : Number(m[2]),
    dim: m[3] === 'DIM_LIGHT' ? (DIMS && DIMS.light) : m[3] === 'DIM_DARK' ? (DIMS && DIMS.dark) : Number(m[3]),
    text: relLum(+m[4], +m[5], +m[6]),
    hiText: relLum(+m[7], +m[8], +m[9])
  }));
if (combos.length !== 2) errs.push('32-boot.js 里 measureWallFloor 的 light/dark 两套参数没解析到（改了写法就要同步改本门禁）');

const solver = { light: {}, dark: {} };
combos.forEach(c => {
  solver[c.k].plate = c.plate; solver[c.k].dim = c.dim;
  solver[c.k].text = c.text; solver[c.k].hiText = c.hiText;
});
/* CSS 里的文字 token 与求解器假定的亮度必须一一对上：任何一边改了而另一边没跟上，下限就是算错的 */
[[lightTok, 'light', 'text'], [hiLightTok, 'light', 'hiText'],
 [darkTok, 'dark', 'text'], [hiDarkTok, 'dark', 'hiText']].forEach(([tok, mode, field]) => {
  if (!tok) return;
  const t3 = hex(tok[2]);
  if (Math.abs(relLum(t3[0], t3[1], t3[2]) - (solver[mode][field] || -1)) > 1e-6)
    errs.push(mode + ' 的 CSS --text-3 ' + tok[2] + ' 与 measureWallFloor 的 ' + field + ' 不一致：改了 token 没改求解器，下限会算错');
});

/* ---------- 3. 逐壁纸复算下限并验证 ---------- */
/* 与 32-boot.js 保持同一套：dim 压暗 → 线性空间按 alpha 插值 → 整数步进求最小 alpha */
function band(bgEnds, c) { return bgEnds.map(bg => bg * c.dim); }
function solveFloor(bgEnds, plate, dim, text, target) {
  let need = null;
  for (const bg of bgEnds) {
    const b = bg * dim;
    for (let i = 0; i <= 48; i++) {                    /* 不用 a += 0.02：浮点累加够不到 0.96 */
      const a = i / 50;
      if (contrast(a * plate + (1 - a) * b, text) >= target) { need = need === null ? a : Math.max(need, a); break; }
    }
  }
  return need;                                         /* null = 加厚到上限也救不回来 */
}
const report = [];
if (errs.length === 0) {
  for (const f of known) {
    const rec = data.wallpapers[f];
    const ends = [rec.p05, rec.p95];
    for (const mode of ['light', 'dark']) {
      const sv = solver[mode], dimEnds = band(ends, sv);
      const row = { f: f.replace('.webp', ''), mode: mode };
      let aaFloor = 0;
      [{ key: 'aa', target: TARGETS.aa, text: sv.text, tok: mode === 'light' ? lightTok : darkTok },
       { key: 'hi', target: TARGETS.hi, text: sv.hiText, tok: mode === 'light' ? hiLightTok : hiDarkTok }].forEach(t => {
        if (!t.tok) return;
        let floor = solveFloor(ends, sv.plate, sv.dim, t.text, t.target);
        if (floor === null) floor = MAX_A;               /* 顶到上限，下面如实报差多少 */
        /* 与运行时同式：增强档的板不会比默认档薄 */
        if (t.key === 'aa') aaFloor = floor; else floor = Math.max(aaFloor, floor);
        const t2 = hex(t.tok[1]);
        for (const bg of dimEnds) {
          const lb = floor * sv.plate + (1 - floor) * bg;
          const c3 = contrast(lb, t.text);
          row['at_' + t.key] = Math.min(row['at_' + t.key] || 99, c3);
          if (c3 < t.target)
            errs.push(f + ' · ' + mode + ' · ' + t.key + '档：下限 ' + floor + ' 下三级文字只有 ' + c3.toFixed(2) + ':1（要求 ' + t.target + ':1）');
          const c2 = contrast(lb, relLum(t2[0], t2[1], t2[2]));
          if (c2 < t.target)
            errs.push(f + ' · ' + mode + ' · ' + t.key + '档：下限 ' + floor + ' 下二级文字只有 ' + c2.toFixed(2) + ':1（要求 ' + t.target + ':1）');
        }
        row[t.key] = floor;
      });
      report.push(row);
    }
  }
}

if (errs.length) {
  console.error('✗ 可读性门禁未通过：\n  ' + errs.join('\n  '));
  process.exit(1);
}
const rng = k => Math.min(...report.map(r => r[k])) + '–' + Math.max(...report.map(r => r[k]));
const worstAt = k => Math.min(...report.map(r => r[k]));
console.log('✓ 可读性门禁通过：' + known.length + ' 张内置壁纸 × 明暗两套 = ' + report.length + ' 组；' +
  '默认档玻璃板下限 ' + rng('aa') + '（实测最低 ' + worstAt('at_aa').toFixed(2) + ':1，要求 ≥' + TARGETS.aa + '）；' +
  '增强档 ' + rng('hi') + '（实测最低 ' + worstAt('at_hi').toFixed(2) + ':1，要求 ≥' + TARGETS.hi + '）');
module.exports = { report };
