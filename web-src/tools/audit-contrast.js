/* ============================================================
   门禁：玻璃板上的文字可读性（构建期复算）
   ------------------------------------------------------------
   为什么要有它：通透度滑块 + 壁纸是用户自由组合，"字看不见"这种缺陷靠肉眼点检必然漏。
   上线前实测 14 张内置壁纸，三级文字 100% 面积低于 WCAG AA 的 4.5:1，最差 1.00:1
   （等于隐形），而且把通透度拉满也救不回来。

   模型（2026-09-24 重做）：亮度带取**穿过 .wall 预模糊 + 表面压缩链之后**的实测分位
   （tools/data/wall-lum.json，由 tools/serve-lum.js + lum-harness.html 在真浏览器里量），
   在配方表的三个档位之间按通透度线性内插，再解"需要多厚的板"。
   旧版这里用的是未压缩的原始分位，于是它要求 0.46–0.92 的板厚；而压缩曲线接进运行时之后，
   那个下限既不再必要、又把滑杆低段整段夹死 —— 门禁在保护一个已经不存在的模型。

   两条断言：
     1. 每一个整数档位的实际 alpha 下，二级/三级文字都要过目标对比度（硬）。
     2. 被下限夹住（滑杆拖了画面不动）的档位数不得超过棘轮 DEAD_HARD。
   ============================================================ */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '..');
const WALL_DIR = path.resolve(ROOT, '../app/src/main/assets/wallpapers');
const DATA = path.join(__dirname, 'data', 'wall-lum.json');
const AA = 4.5;               /* WCAG 2.1 AA，正文/小字 */
const MAX_A = 0.96;           /* 再厚就不是玻璃了 */

/* 棘轮（ratchet）。新压缩模型下，最差一张（卡提希娅2 · 浅色默认档）在 t13–38 仍需
   最多 0.14 的薄板，占 24/86 档 —— 对比旧模型要求的 0.46–0.92 全段厚板。
   钉在 30% 是为了让"改坏"立刻可见。要降到 0，该动的是文字色而不是曲线：
   增强档（--text-3 #2c2c30）在同一条带下 0 档被夹，瓶颈是默认档那个 #55555a。 */
const DEAD_HARD = 0.30;

const errs = [];
const s2l = c => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
const relLum = (r, g, b) => 0.2126 * s2l(r) + 0.7152 * s2l(g) + 0.0722 * s2l(b);
const contrast = (l1, l2) => (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
const hex = h => { const x = h.replace('#', ''); return [parseInt(x.slice(0, 2), 16), parseInt(x.slice(2, 4), 16), parseInt(x.slice(4, 6), 16)]; };
const read = p => fs.readFileSync(p, 'utf8');

/* ---------- 1. 采样数据本身可信吗 ---------- */
const data = JSON.parse(read(DATA));
const onDisk = fs.readdirSync(WALL_DIR).filter(f => /\.webp$/i.test(f)).sort();
const known = Object.keys(data.wallpapers).sort();
if (JSON.stringify(onDisk) !== JSON.stringify(known)) {
  errs.push('壁纸清单与采样数据不一致：磁盘 ' + onDisk.length + ' 张 / 数据 ' + known.length + ' 条；缺 ' +
    onDisk.filter(f => !data.wallpapers[f]).join(',') + ' 多余 ' + known.filter(f => onDisk.indexOf(f) < 0).join(','));
}
for (const f of known) {
  if (!onDisk.includes(f)) continue;
  const buf = fs.readFileSync(path.join(WALL_DIR, f));
  const sha = crypto.createHash('sha1').update(buf).digest('hex').slice(0, 12);
  if (sha !== data.wallpapers[f].sha1)
    errs.push(f + ' 已改动但没重新采样（sha1 ' + data.wallpapers[f].sha1 + ' → ' + sha + '）；跑 tools/serve-lum.js + lum-harness.html + gen-wall-lum.js');
}

/* ---------- 2. 采样口径必须对应当前配方 ---------- */
const recipe = JSON.parse(read(path.join(ROOT, 'glass-recipe.json')));
const chainSig = lv => lv.chain.map(p => p[0] + p[1]).join('+');
const src = data._source || {};
const wantChain = chainSig(recipe.backdrop.light.levels[0]);
if (src.chain0 !== wantChain)
  errs.push('实测数据用的曲线 ' + src.chain0 + ' 与配方表 t' + recipe.backdrop.light.levels[0].t + ' 的 ' + wantChain + ' 不一致：曲线改过但没重采样');
if (src.surfBlur !== recipe.surfaces.tabbar.light.filter)
  errs.push('实测数据用的表面 blur ' + src.surfBlur + ' 与配方表 ' + recipe.surfaces.tabbar.light.filter + ' 不一致');
/* saturate 同样是采样口径的一部分：改了它而不重采样，亮度带就还是旧色空间的数 */
if (src.sat !== recipe.backdrop.saturate)
  errs.push('实测数据用的 saturate ' + src.sat + ' 与配方表 ' + recipe.backdrop.saturate + ' 不一致：改饱和度后要重跑 lum 工装');
const wantStops = recipe.backdrop.light.levels.map(l => l.t).join(',');
if ((data.stops || []).join(',') !== wantStops)
  errs.push('实测档位 ' + (data.stops || []).join(',') + ' 与配方表 ' + wantStops + ' 不一致');

/* ---------- 3. 文字 token 与运行时求解器必须一致 ---------- */
const tokens = read(path.join(ROOT, 'css', '01-tokens.css'));
const TOK = {
  light: { aa: tokens.match(/body\.glass-on\.has-wallpaper \{[^}]*--text-2:\s*(#[0-9a-f]{6})[^}]*--text-3:\s*(#[0-9a-f]{6})/i),
           hi: tokens.match(/html\.hi-contrast body\.glass-on\.has-wallpaper \{[^}]*--text-2:\s*(#[0-9a-f]{6})[^}]*--text-3:\s*(#[0-9a-f]{6})/i) },
  dark:  { aa: tokens.match(/html\.dark body\.glass-on\.has-wallpaper \{[^}]*--text-2:\s*(#[0-9a-f]{6})[^}]*--text-3:\s*(#[0-9a-f]{6})/i),
           hi: tokens.match(/html\.dark\.hi-contrast body\.glass-on\.has-wallpaper \{[^}]*--text-2:\s*(#[0-9a-f]{6})[^}]*--text-3:\s*(#[0-9a-f]{6})/i) }
};
['light', 'dark'].forEach(t => ['aa', 'hi'].forEach(k => {
  if (!TOK[t][k]) errs.push('01-tokens.css 里找不到 ' + t + '/' + k + ' 作用域的 --text-2/--text-3 覆盖');
}));
if (!TOK.light.hi || !TOK.dark.hi) errs.push('找不到 html.hi-contrast 的文字覆盖（增强档只加厚玻璃到不了 7:1，靠的是更强文字色）');

const boot = read(path.join(ROOT, 'js', '32-boot.js'));
const tgtM = boot.match(/var AA_TARGET = ([\d.]+), HI_TARGET = ([\d.]+);/);
const TARGETS = tgtM ? { aa: Number(tgtM[1]), hi: Number(tgtM[2]) } : null;
if (!TARGETS) errs.push('32-boot.js 里找不到 var AA_TARGET / HI_TARGET');
else if (TARGETS.aa !== AA) errs.push('门禁的 AA=' + AA + ' 与运行时的 AA_TARGET=' + TARGETS.aa + ' 不一致');

/* 运行时 combos 里的文字亮度必须等于 CSS 的 --text-3，否则两边各算各的 */
const comboRe = /(light|dark):\s*\{\s*plate: (1|relLum\([\d, ]+\)),\s*(?:dim: [A-Z_]+,\s*)?text: relLum\((\d+), (\d+), (\d+)\),\s*hiText: relLum\((\d+), (\d+), (\d+)\)\s*\}/g;
const combos = [...boot.matchAll(comboRe)].map(mm => ({
  k: mm[1], plate: mm[2] === '1' ? 1 : relLum(...mm[2].match(/\d+/g).map(Number)),
  text: relLum(+mm[3], +mm[4], +mm[5]), hiText: relLum(+mm[6], +mm[7], +mm[8])
}));
if (combos.length !== 2) errs.push('32-boot.js 里 measureWallFloor 的 light/dark combos 没解析到（写法改了就要同步改本门禁）');
combos.forEach(c => {
  ['aa', 'hi'].forEach(tier => {
    const tok = TOK[c.k] && TOK[c.k][tier];
    if (!tok) return;
    const want = tier === 'aa' ? c.text : c.hiText;
    const got = relLum(...hex(tok[2]));
    if (Math.abs(got - want) > 1e-6)
      errs.push(c.k + '/' + tier + ' 的 CSS --text-3 ' + tok[2] + ' 与 measureWallFloor 的文字亮度不一致：改了 token 没改求解器');
  });
});

/* ---------- 4. 逐壁纸 × 每一档复算 ---------- */
const baseA = t => 0.04 + Math.pow((t - 10) / 85, 1.2) * 0.90;   /* 与 32-boot.js 同式 */
const STOPS = data.stops || [];

/* 背景压暗系数：运行时求解用的 dim 必须等于 applyGlass 按主题写进 --bg-bri 的值。
   少这一条核对，改了一边另一边没跟上时算出来的下限就是错的（暗色曾因此被量成浅色）。 */
const dimM = boot.match(/var DIM_LIGHT = ([\d.]+), DIM_DARK = ([\d.]+);/);
const briM = boot.match(/--bg-bri'[\s\S]{0,200}?'(\d+)%'\s*:\s*'(\d+)%'\)/);
if (!dimM) errs.push('32-boot.js 里找不到 var DIM_LIGHT / DIM_DARK');
else if (!briM) errs.push('32-boot.js 里 applyGlass 没按主题写 --bg-bri，dim 模型与实际渲染脱节');
else if (Math.abs(dimM[2] * 100 - Number(briM[1])) > 0.01 || Math.abs(dimM[1] * 100 - Number(briM[2])) > 0.01)
  errs.push('DIM_LIGHT/DIM_DARK = ' + dimM[1] + '/' + dimM[2] + ' 与 --bg-bri 的 ' + briM[2] + '%/' + briM[1] + '% 不一致');

/* 明暗两套曲线都要有主题无关的镜像变量，否则一次 computed style 快照量不出两套带 */
const recCss = read(path.join(ROOT, 'css', '00-recipe.css'));
['light', 'dark'].forEach(theme => {
  recipe.backdrop[theme].levels.forEach(lv => {
    if (!recCss.includes('--gr-cmp-' + theme + '-' + lv.t + ':'))
      errs.push('00-recipe.css 缺 --gr-cmp-' + theme + '-' + lv.t + '（measureWallFloor 需要主题无关的镜像）');
  });
  if (!recCss.includes('--gr-surf-' + theme + ':')) errs.push('00-recipe.css 缺 --gr-surf-' + theme);
});

/* 在实测档位之间按通透度线性内插亮度带 */
function bandAt(bands, t) {
  let lo = STOPS[0], hi = STOPS[STOPS.length - 1];
  for (let i = 0; i < STOPS.length - 1; i++) if (t >= STOPS[i] && t <= STOPS[i + 1]) { lo = STOPS[i]; hi = STOPS[i + 1]; break; }
  const A = bands[lo], B = bands[hi];
  if (!A || !B) return null;
  const k = hi === lo ? 0 : (t - lo) / (hi - lo);
  return [0, 2].map(j => A[j] + (B[j] - A[j]) * k);
}
function solveFloor(band, plate, text, target) {
  for (let i = 0; i <= 48; i++) {
    const a = i / 50;
    if (band.every(bg => contrast(a * plate + (1 - a) * bg, text) >= target)) return a;
  }
  return null;
}

const report = [];
if (errs.length === 0) {
  for (const f of known) {
    if (!onDisk.includes(f)) continue;
    for (const theme of ['light', 'dark']) {
      const combo = combos.find(c => c.k === theme);
      if (!combo) continue;
      const bands = {};
      STOPS.forEach(t => { bands[t] = data.wallpapers[f][theme]['t' + t]; });
      const row = { f: f.replace('.webp', ''), theme, dead: { aa: 0, hi: 0 }, worst: { aa: 99, hi: 99 } };
      ['aa', 'hi'].forEach(tier => {
        const target = tier === 'aa' ? TARGETS.aa : TARGETS.hi;
        const text = tier === 'aa' ? combo.text : combo.hiText;
        const t2 = TOK[theme][tier] ? relLum(...hex(TOK[theme][tier][1])) : null;
        let prevA = null;
        for (let t = 10; t <= 95; t++) {
          const band = bandAt(bands, t);
          if (!band) { errs.push(f + ' ' + theme + ' 缺 t' + t + ' 的实测带'); break; }
          let floor = solveFloor(band, combo.plate, text, target);
          if (floor === null) floor = MAX_A;
          const a = Math.max(baseA(t), floor);
          if (floor > baseA(t) + 1e-9) row.dead[tier]++;
          for (const bg of band) {
            const lb = a * combo.plate + (1 - a) * bg;
            const c3 = contrast(lb, text);
            if (c3 < target) errs.push(f + ' · ' + theme + ' · ' + tier + ' · t' + t + '：alpha ' + a.toFixed(2) + ' 下三级文字只有 ' + c3.toFixed(2) + ':1（要求 ' + target + '）');
            row.worst[tier] = Math.min(row.worst[tier], c3);
            if (t2 !== null) {
              const c2 = contrast(lb, t2);
              if (c2 < target) errs.push(f + ' · ' + theme + ' · ' + tier + ' · t' + t + '：alpha ' + a.toFixed(2) + ' 下二级文字只有 ' + c2.toFixed(2) + ':1（要求 ' + target + '）');
            }
          }
        }
      });
      report.push(row);
    }
  }
  const worstDead = Math.max(...report.map(r => Math.max(r.dead.aa, r.dead.hi)));
  if (worstDead / 86 > DEAD_HARD)
    errs.push('最差情况下通透度有 ' + worstDead + '/86 档（' + Math.round(worstDead / 86 * 100) + '%）被下限钉死，棘轮上限 ' + Math.round(DEAD_HARD * 100) + '% —— 滑杆拖了画面不动');
}

if (errs.length) {
  console.error('✗ 可读性门禁未通过（' + errs.length + ' 项）：\n  ' + errs.slice(0, 20).join('\n  ') + (errs.length > 20 ? '\n  …另有 ' + (errs.length - 20) + ' 项' : ''));
  process.exit(1);
}
const maxDead = Math.max(...report.map(r => Math.max(r.dead.aa, r.dead.hi)));
console.log('✓ 可读性门禁通过：' + known.length + ' 张 × 明暗 = ' + report.length + ' 组，扫 ' + (95 - 10 + 1) + ' 个通透度档；' +
  '最低对比度 默认档 ' + Math.min(...report.map(r => r.worst.aa)).toFixed(2) + ':1（要求 ≥' + TARGETS.aa + '），' +
  '增强档 ' + Math.min(...report.map(r => r.worst.hi)).toFixed(2) + ':1（要求 ≥' + TARGETS.hi + '）；' +
  '被下限夹住的档位最多 ' + maxDead + '/86（棘轮 ' + Math.round(DEAD_HARD * 100) + '%）');
module.exports = { report };
