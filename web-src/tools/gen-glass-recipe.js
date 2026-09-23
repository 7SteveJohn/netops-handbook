/* 由 glass-recipe.json 生成 css/00-recipe.css。
   表是唯一真源；CSS 只读 --gr-* 变量。build.js 每次构建都会重跑本脚本并做
   新鲜度校验（改了表忘了生成 = 构建失败），所以不会出现"表和代码不一致"。
   用法：node tools/gen-glass-recipe.js */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'glass-recipe.json');
const OUT = path.join(ROOT, 'css', '00-recipe.css');

function build() {
  const r = JSON.parse(fs.readFileSync(SRC, 'utf8'));
  const L = [], D = [];
  const put = (name, light, dark) => {
    if (light !== undefined) L.push('  --gr-' + name + ': ' + light + ';');
    if (dark !== undefined) D.push('  --gr-' + name + ': ' + dark + ';');
  };
  put('bg-sat', r.backdrop.saturate);

  /* 压缩曲线 → --gr-cmp-<档位>。这是 backdrop.levels 第一次真正被运行时读到：
     以前它只是记在表里没人用，于是表面滤镜只有 saturate+blur 两味、没有
     brightness/contrast，也就没有动态范围压缩 —— 可读性只能靠抬板厚换，
     通透度滑杆因此有一半行程是死的（2026-09-23 实测 54–68%）。
     曲线口径来自对参考实现逐像素取样：背后亮度摆 101 个单位时，玻璃内部只摆
     13 个（≈8:1 压缩），色相继承背景、彩度被夹到中间值。
     明暗分别写进 :root / html.dark，所以运行时读到的就是当前主题的字符串，
     JS 里不需要再判断主题。 */
  const sat = r.backdrop.saturate;
  /* chain 是有序序列，按表里的顺序原样拼 —— 浅色靠"先压后抬"，顺序反了就白压白 */
  const cmp = lv => 'saturate(' + sat + ') ' + lv.chain.map(p => p[0] + '(' + p[1] + ')').join(' ');
  const stops = [...new Set([].concat(
    r.backdrop.light.levels.map(l => l.t), r.backdrop.dark.levels.map(l => l.t)
  ))].sort((a, b) => a - b);
  for (const t of stops) {
    const l = r.backdrop.light.levels.find(x => x.t === t);
    const d = r.backdrop.dark.levels.find(x => x.t === t);
    put('cmp-' + t, l ? cmp(l) : undefined, d ? cmp(d) : undefined);
    /* 两套主题都再放一份主题无关的名字：measureWallFloor 要在一次快照里同时算明暗，
       而 --gr-cmp-<t> 会被 html.dark 覆盖，读一次只拿得到当前生效的那一套 —— 曾因此
       把暗色的亮度量成浅色的，下限算低。 */
    put('cmp-light-' + t, l ? cmp(l) : undefined);
    put('cmp-dark-' + t, d ? cmp(d) : undefined);
  }
  put('surf-light', r.surfaces.tabbar.light.filter);
  put('surf-dark', r.surfaces.tabbar.dark.filter);
  put('cmp-stops', stops.join(' '));

  const s = r.surfaces;
  put('tabbar-tint-top', s.tabbar.light.tintTop, s.tabbar.dark.tintTop);
  put('tabbar-tint-bot', s.tabbar.light.tintBot, s.tabbar.dark.tintBot);
  put('tabbar-filter', s.tabbar.light.filter, s.tabbar.dark.filter);
  put('tabbar-shadow', s.tabbar.light.shadow, s.tabbar.dark.shadow);
  put('tabbar-specular', s.tabbar.light.specular, s.tabbar.dark.specular);
  put('sheet-tint-top', s.sheet.light.tintTop, s.sheet.dark.tintTop);
  put('sheet-tint-bot', s.sheet.light.tintBot, s.sheet.dark.tintBot);
  put('sheet-filter', s.sheet.light.filter, s.sheet.dark.filter);
  put('sheet-shadow', s.sheet.light.shadow, s.sheet.dark.shadow);
  put('sheet-specular', s.sheet.light.specular, s.sheet.dark.specular);
  put('lens-rim', r.lens.rim, r.lens.rimDark);
  put('lens-glow', r.lens.innerGlow, r.lens.innerGlowDark);
  put('lens-aberration', r.lens.aberration);

  /* 边缘折射透镜：由 33-apple.js 的 lensOptics() 读取，用来生成位移贴图。
     刻意不生成 curvature —— 当前贴图数学还没建模"边缘轮廓曲率"，若照样输出，
     消费门禁会因为 JS 里读了这个名字而放行，变成空心消费。 */
  const op = r.lens.optics;
  put('lens-refraction', op.refraction + 'px');
  put('lens-bezel', op.bezel);
  put('lens-ior', op.ior);

  /* 高光推导参数：有壁纸时运行时用它们把"背后的色相"变成亮带与暗肩的配色，
     没有壁纸时表面退回上面的静态 rim/specular 值。
     lightAngle 暂不生成 —— 边缘方向目前固定按取样帧的光源（左下），
     要按角度算偏移得等几何折射那一轮，先别造一个没人读的死 token。 */
  const sp = r.lens.specular;
  put('spec-lift', sp.lift);
  put('spec-sat', sp.satScale);
  put('spec-alpha', sp.alpha);
  put('rim-dark-drop', sp.darkDrop);
  put('rim-dark-alpha', sp.darkAlpha);

  put('spring', r.motion.spring);
  const q = r.motion.squish;
  put('squish-max', q.max + 'px');
  put('squish-press', q.press);
  put('squish-stretch', q.stretch);
  put('squish-squash', q.squash);
  put('squish-back', (r.motion.pillBackMs / 1000) + 's');

  const head =
    '/* ============================================================\n' +
    '   玻璃配方表（生成物，勿手改）\n' +
    '   真源：web-src/glass-recipe.json，改完跑 node tools/gen-glass-recipe.js\n' +
    '   ============================================================ */\n' +
    ':root {\n' + L.join('\n') + '\n}\n';
  const dark = D.length ? '\nhtml.dark {\n' + D.join('\n') + '\n}\n' : '';
  return head + dark;
}

if (require.main === module) {
  const css = build();
  fs.writeFileSync(OUT, css, 'utf8');
  console.log('已生成 css/00-recipe.css（' + Buffer.byteLength(css, 'utf8') + ' 字节）');
}
module.exports = { build, OUT };
