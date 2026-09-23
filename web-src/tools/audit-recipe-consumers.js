/* ============================================================
   门禁：配方表的每个 --gr-* token 都必须有消费者
   ------------------------------------------------------------
   为什么要有它：glass-recipe.json 号称"全站玻璃表面的唯一参数来源"，但 2026-09-23
   实测 41 个 token 里 13 个零消费者 —— 改那些值屏幕上什么都不变，"唯一真源"对它们是
   一句空话。清完之后如果不立一道闸，死参数一定会长回来。

   怎么判"有消费者"：
     1. 字面 var(--gr-x) 或引号里的 '--gr-x' —— 静态可查。
     2. 运行时用字符串拼出来的名字（applyGlass 读 --gr-cmp-<档> 等）静态查不到，
        所以下面 DYNAMIC 显式登记，并且**每条都必须给出源码里真实存在的证据片段**；
        证据片段哪天消失（读取被重构掉），本门禁立刻失败。这样这份清单就没法悄悄腐烂。
     3. 一条动态规则若匹配不到任何 token，也算失败（防止清单里留着已废弃的条目）。

   注意：不要用"裸前缀 '--gr-'"当动态规则 —— 那会把所有 token 判成有用，
   正是我第一版扫描器报出"41 个全部在用"的原因。
   ============================================================ */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const RECIPE_CSS = path.join(ROOT, 'css', '00-recipe.css');

/* 动态读取登记：prefix = 被覆盖的 token 前缀；evidence = 源码里必须存在的字面片段 */
const DYNAMIC = [
  { prefix: '--gr-cmp-',        evidence: "'--gr-cmp-' + lo",        why: 'applyGlass 按通透度档位插值压缩曲线' },
  { prefix: '--gr-cmp-light-',  evidence: "'--gr-' + pair[3] + t",   why: 'measureWallFloor 量明暗两套带，需主题无关镜像' },
  { prefix: '--gr-cmp-dark-',   evidence: "'--gr-' + pair[3] + t",   why: '同上' },
  { prefix: '--gr-surf-light',  evidence: "'--gr-' + pair[2]",       why: '同上（表面 blur 也要主题无关镜像）' },
  { prefix: '--gr-surf-dark',   evidence: "'--gr-' + pair[2]",       why: '同上' },
  { prefix: '--gr-wall-blur-',  evidence: "'--gr-wall-blur-' + lo",  why: 'applyGlass 按档位插值 .wall 的全局预模糊' }
];

function sources() {
  const out = [];
  (function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      if (e.name === 'node_modules' || e.name === '.git') continue;
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.(css|js|html)$/.test(e.name) && p !== RECIPE_CSS && !/[\\/]assets[\\/]/.test(p)) out.push(p);
    }
  })(ROOT);
  return out.map(p => [path.relative(ROOT, p), fs.readFileSync(p, 'utf8')]);
}

function analyze() {
  const errs = [];
  if (!fs.existsSync(RECIPE_CSS)) return { errs: ['找不到 css/00-recipe.css，先跑 gen-glass-recipe.js'], total: 0, dead: [] };
  const names = [...new Set((fs.readFileSync(RECIPE_CSS, 'utf8').match(/--gr-[a-z0-9-]+/g) || []))];
  const src = sources();

  const consumed = new Set();
  for (const n of names) {
    if (src.some(([, s]) => s.includes('var(' + n) || s.includes("'" + n + "'") || s.includes('"' + n + '"'))) consumed.add(n);
  }
  /* 动态规则：证据必须在源码里存在，且必须至少覆盖一个 token */
  for (const rule of DYNAMIC) {
    const hasEvidence = src.some(([, s]) => s.includes(rule.evidence));
    if (!hasEvidence) {
      errs.push('动态登记失效：' + rule.prefix + '* 的证据片段 ' + rule.evidence +
        ' 在源码里已找不到 —— 该读取大概被重构掉了，登记要删或改');
      continue;
    }
    const covered = names.filter(n => n.startsWith(rule.prefix));
    if (!covered.length) {
      errs.push('动态登记空转：' + rule.prefix + '* 已匹配不到任何 token，清单里该条目要删');
      continue;
    }
    covered.forEach(n => consumed.add(n));
  }

  const dead = names.filter(n => !consumed.has(n));
  dead.forEach(n => errs.push('token 无人消费：' + n + ' —— 改它屏幕上不会变，"配方表是唯一真源"对它是假话。要么接上消费者，要么从表里删'));
  return { errs, total: names.length, dead };
}

function gate() {
  const r = analyze();
  if (r.errs.length) {
    console.log('✗ 配方 token 消费门禁未通过（' + r.errs.length + ' 项）：');
    r.errs.slice(0, 20).forEach(e => console.log('    - ' + e));
    if (r.errs.length > 20) console.log('    …另有 ' + (r.errs.length - 20) + ' 项');
  } else {
    console.log('✓ 配方 token 消费门禁通过：' + r.total + ' 个 --gr-* token 全部有消费者');
  }
  return r;
}

if (require.main === module) {
  const r = gate();
  process.exit(r.errs.length ? 1 : 0);
}
module.exports = { gate, analyze };
