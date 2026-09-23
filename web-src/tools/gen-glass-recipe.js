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
  put('card-tint-top', s.card.light.tintTop, s.card.dark.tintTop);
  put('card-tint-bot', s.card.light.tintBot, s.card.dark.tintBot);
  put('card-border', s.card.light.border, s.card.dark.border);

  put('lens-rim', r.lens.rim, r.lens.rimDark);
  put('lens-glow', r.lens.innerGlow, r.lens.innerGlowDark);
  put('lens-aberration', r.lens.aberration);

  put('spring', r.motion.spring);
  put('decel', r.motion.decel);
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
