/* 内容一致性审计 / 构建期门禁
   ------------------------------------------------------------
   这里守的都是本项目真出过的错法：
   · 字典写成对象导致 r.join is not a function
   · README 写「500+ 条命令」而实际 392（虚标）
   · 模块描述写「55 行」而实际 58
   · 宣称 30 个 STAR 卡而实际只有 1 题有 STAR
   · 已被否掉的定位措辞又溜回来（「全栈网络运维技能导航」「能独立学成」）
   用法：node tools/audit-content-consistency.js [--gate]
   build.js 会 require 本模块的 gate()，违规即构建失败。 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const read = p => fs.readFileSync(path.join(ROOT, p), 'utf8');
const OUT_README = path.resolve(ROOT, '..', 'README.md');

/* 学习模块必须齐备的 9 个内容字段 */
const MOD_FIELDS = ['y', 'c', 'o', 'j', 'u', 'w', 'v', 'l', 'dg'];
/* 明确允许的例外：模块 id → 可缺字段（新增例外必须写理由） */
const FIELD_EXEMPT = {};
/* 已被产品定案否决的措辞，出现即失败 */
const BANNED = [
  [/全栈网络运维技能导航/, '定位已改为「辅助手册」，此措辞 2026-09-21 已被否'],
  [/能独立学成|独立走完.*课程|单靠它.*学会/, '定位=辅助教学，不得承诺能独立学成'],
  [/500\s*\+?\s*条(命令|CLI)/, 'CLI 实际条数由数据推导，禁止写虚数']
];

function empty(v) {
  if (v == null) return true;
  if (Array.isArray(v)) return v.filter(x => !empty(x)).length === 0;
  if (typeof v === 'object') return Object.keys(v).length === 0;
  return String(v).trim() === '';
}

/* ---------- 载入数据 ---------- */
function loadData() {
  const g = {};
  global.window = g;
  const code = read('js/data/10-core.js') + '\n' + read('js/data/20-extend.js') + '\n' + read('js/data/21-quiz.js');
  new Function('window', code + '\nthis.__w=window;').call(g, g);
  return { CORE: g.NETOPS_CORE, EXT: g.NETOPS_EXT, QUIZ: g.NETOPS_QUIZ };
}

function analyze() {
  const errs = [];
  const notes = [];
  let counts = {};

  const { CORE, EXT, QUIZ } = loadData();
  const mods = CORE.phases.flatMap(p => p.modules);
  counts = {
    模块: mods.length, 排障: CORE.faults.length, 面试: CORE.interview.length,
    字典: CORE.dict.rows.length, 速查: (EXT.refs || []).length,
    术语: (EXT.glossary || []).length, 题库: (QUIZ || []).length
  };

  /* A. 模块字段齐备 */
  mods.forEach(m => {
    const miss = MOD_FIELDS.filter(k => empty(m[k]) && !(FIELD_EXEMPT[m.id] || []).includes(k));
    if (miss.length) errs.push('模块 ' + m.id + '（' + String(m.t).slice(0, 18) + '）缺字段: ' + miss.join(','));
    if (!/^[\w.-]+\.\s|\S/.test(m.t || '')) errs.push('模块 ' + m.id + ' 标题异常');
  });

  /* A2. 原理正文长度下限：「知识点少」的观感几乎全来自正文只有三五十字。
     综合实验、字典表、面试卡、排障卡结构不同，不参与本项。 */
  const BODY_EXEMPT = /^(LAB\d*|DIC|IVQ\d+|G\d+)/;
  mods.forEach(m => {
    if (BODY_EXEMPT.test(m.id)) return;
    const n = String(m.y || '').length;
    if (n < 110) errs.push('模块 ' + m.id + ' 原理正文只有 ' + n + ' 字（下限 110）→ 补机制与判据，不要拿数值凑字');
  });

  /* A3. 字段类型：渲染器按数组消费 j/u/w/l，写成逗号串会让「展开卡片」当场崩（曾被冒烟漏掉） */
  const ARRF = ['j', 'u', 'w', 'l'];
  mods.concat(CORE.faults, CORE.interview).forEach(m => {
    ARRF.forEach(f => {
      const v = m[f];
      if (v != null && !Array.isArray(v)) {
        errs.push('模块 ' + m.id + ' 的 ' + f + ' 是 ' + typeof v + ' 不是数组 → 卡片展开时 list.map 崩，必须拆成条目数组');
      }
    });
  });

  /* A4. 分步操作要成路径：历史上 31/66 个模块只有一步，等于「看完仍不知道怎么做」。
     阈值按现状取：知识模块与综合实验 ≥3 步；任何一步 ≤60 字（步骤只写动作，解释放正文）。
     排障卡是「现象→根因→处置」三段式，步数天然少，不参与 ≥3 这项。 */
  mods.forEach(m => {
    const l = Array.isArray(m.l) ? m.l : [];
    l.forEach((s, i) => {
      if (String(s).length > 60) {
        errs.push('模块 ' + m.id + ' 第 ' + (i + 1) + ' 步 ' + String(s).length + ' 字（>60）→ 步骤只写动作，说明写进原理正文');
      }
    });
    if (BODY_EXEMPT.test(m.id) && !/^LAB\d*$/.test(m.id)) return;
    if (l.length < 3) errs.push('模块 ' + m.id + ' 分步操作只有 ' + l.length + ' 步（下限 3）→ 补成能照着做完的路径');
  });

  /* B. 字典行形状（数组的数组，5 列） */
  const dic = JSON.parse(read('.cache/dic.json'));
  dic.forEach((r, i) => {
    if (!Array.isArray(r)) { errs.push('字典第 ' + (i + 1) + ' 行不是数组（必须 [名称,华为,Cisco,中兴,Linux]，写成对象会让 r.join 崩）'); return; }
    if (r.length !== 5) errs.push('字典第 ' + (i + 1) + ' 行长度 ' + r.length + '（应为 5）');
    else if (r.slice(1).every(v => !String(v || '').trim() || String(v).trim() === '-'))
      errs.push('字典第 ' + (i + 1) + ' 行「' + r[0] + '」四家全为空/短横，等于没有对照');
  });
  if (dic.length !== CORE.dict.rows.length)
    errs.push('字典源 ' + dic.length + ' 行 与生成产物 ' + CORE.dict.rows.length + ' 行不一致（忘记跑 gen-data.js？）');

  /* C. 题库形状与答案下标 */
  const ALL_IDS = new Set(CORE.phases.flatMap(p => p.modules).concat(CORE.faults, CORE.interview).map(m => m.id));
  (QUIZ || []).forEach((q, i) => {
    const at = '题库第 ' + (i + 1) + ' 题';
    if (!Array.isArray(q.o) || q.o.length !== 4) { errs.push(at + ' 选项数 ' + (q.o || []).length + '（应为 4）'); return; }
    if (!Number.isInteger(q.a) || q.a < 0 || q.a > 3) errs.push(at + ' 答案下标非法: ' + q.a);
    if (empty(q.q)) errs.push(at + ' 题干为空');
    if (empty(q.e)) errs.push(at + ' 缺解析');
    /* 锚点：答错要能跳回真正讲过这件事的卡片，没有 m 或指向不存在的 id 都算断链 */
    if (empty(q.m)) errs.push(at + ' 缺模块锚点 m（错题本要靠它跳回原卡）');
    else if (!ALL_IDS.has(q.m)) errs.push(at + ' 的锚点 m 指向不存在的卡片: ' + q.m);
  });
  const dupQ = (QUIZ || []).map(q => String(q.q).replace(/\s+/g, '')).filter((x, i, a) => a.indexOf(x) !== i);
  if (dupQ.length) errs.push('题库有 ' + dupQ.length + ' 个重复题干');

  /* C2. 题库必须挂在真实阶段上，且每阶段题量不能塌
     （p-5 曾是生成器桶而非阶段，早期有 8 题把 p 写成 p-5 —— 那个标签没人读，等于失踪） */
  const phaseIds = CORE.phases.map(p => p.id);
  const perPhase = {};
  (QUIZ || []).forEach(q => {
    if (phaseIds.indexOf(q.p) < 0) errs.push('题库有题目阶段不属于任何学习阶段: ' + q.p + '（' + String(q.q).slice(0, 20) + '）');
    perPhase[q.p] = (perPhase[q.p] || 0) + 1;
  });
  phaseIds.forEach(pid => {
    const n = perPhase[pid] || 0;
    if (n < 8) errs.push('阶段 ' + pid + ' 只有 ' + n + ' 道测验题（下限 8）—— 新模块必须配套出题');
  });
  counts.阶段题量 = phaseIds.map(k => k + ':' + (perPhase[k] || 0)).join(' ');

  /* C3. 跨条目一致性：已按 RFC 9568 更正的事实，不允许别处还写「VRRP 心跳链路」 */
  const ivRaw = (() => { try { return read('.cache/phases.json'); } catch (e) { return ''; } })();
  const quizRaw = (() => { try { return read('js/data/21-quiz.js'); } catch (e) { return ''; } })();
  const extRaw = (() => { try { return read('js/data/20-extend.js'); } catch (e) { return ''; } })();
  const ALL = ivRaw + quizRaw + extRaw;
  /* 跨条目一致性：只有把旧标准/旧模型「当现行事实」陈述才算错；
     「RFC 9568 已废止 RFC 3768」「心跳链路是 VRRPv2 时代的心智模型」这类历史说明必须放过，
     但必须落在标记词附近，否则视为漏改。 */
  const MARK = ['废止', '已废', 'obsolete', '已被', '不再', '旧', '历史', '心智模型', '别再', 'VRRPv2', '现行',
    '没有独立', '无独立', '不是独立', '没有心跳'];
  const STALE = [
    [/RFC\s*3768/g, 'RFC 3768（VRRPv2）'],
    [/RFC\s*5549/g, 'RFC 5549'],
    [/RFC\s*793\b/g, 'RFC 793（TCP）'],
    [/心跳(链路|口)/g, '「VRRP 心跳链路/心跳口」模型']
    /* 不放 RFC 2131：它是 DHCPv4 的现行标准，被取代的是 DHCPv6 线（3315→8415→…），
       把两者混进同一条规则会误伤正确引用（本条曾写错，被自己的门禁当场抓出）。 */
  ];
  STALE.forEach(([re, name]) => {
    let m; re.lastIndex = 0;
    while ((m = re.exec(ALL))) {
      const win = ALL.slice(Math.max(0, m.index - 70), m.index + 70);
      if (!MARK.some(k => win.includes(k))) {
        errs.push('把 ' + name + ' 当现行事实陈述：…' + win.replace(/\s+/g, ' ').slice(0, 76) + '…');
      }
    }
  });

  /* D. 面试题必备件 + STAR 真实覆盖率 */
  /* 结构不变式：y 里 **考察点**：… 必须整段以「。」收尾。
     这条是被真实事故逼出来的——批量插 STAR 时按旧偏移插入，把 29 条考察点截断了，
     而当时的门禁只看「star 非空」，完全没发现。 */
  const pickSec = (text, label) => {
    const m = String(text || '').match(new RegExp('\\*\\*' + label + '\\*\\*[：:]([\\s\\S]*?)(?=\\n\\*\\*|$)'));
    return m ? m[1].trim() : '';
  };
  const srcIv = (() => {
    try { return JSON.parse(read('.cache/phases.json')).find(p => p.id === 'p-5').modules.filter(m => /^IVQ\d+$/.test(m.id)); }
    catch (e) { return []; }
  })();
  srcIv.forEach(m => {
    const pt = pickSec(m.y, '考察点');
    if (!pt) { errs.push('面试题 ' + m.id + ' 源里缺 **考察点** 段'); return; }
    if (!pt.endsWith('。')) errs.push('面试题 ' + m.id + ' 考察点疑似被截断（未以句号收尾）: ' + JSON.stringify(pt.slice(-14)));
    if (pt.length < 6) errs.push('面试题 ' + m.id + ' 考察点过短: ' + JSON.stringify(pt));
    const st = pickSec(m.y, 'STAR 解析');
    if (st && !st.endsWith('。')) errs.push('面试题 ' + m.id + ' STAR 解析未以句号收尾');
  });
  CORE.interview.forEach(q => {
    if (empty(q.point)) errs.push('面试题 ' + q.id + ' 缺「考察点」');
    if (empty(q.answer)) errs.push('面试题 ' + q.id + ' 缺「参考话术」');
    if (empty(q.cat)) errs.push('面试题 ' + q.id + ' 没有分类 cat（新维度请在数据里显式声明）');
    /* 关联模块：个性化推荐与「回看这题相关的卡」全靠它，HR 类可以没有技术锚点 */
    if (!Array.isArray(q.rel)) errs.push('面试题 ' + q.id + ' 的 rel 不是数组');
    else {
      if (!q.rel.length && q.cat !== 'HR软技能') {
        errs.push('面试题 ' + q.id + '（' + q.cat + '）没有 rel 关联模块 → 个性化和回看跳转都会失效');
      }
      q.rel.forEach(r => { if (!ALL_IDS.has(r)) errs.push('面试题 ' + q.id + ' 的 rel 指向不存在的卡片: ' + r); });
    }
  });
  const star = CORE.interview.filter(q => !empty(q.star)).length;
  counts.STAR = star + '/' + CORE.interview.length;
  const ivCat = {};
  CORE.interview.forEach(q => { ivCat[q.cat || '?'] = (ivCat[q.cat || '?'] || 0) + 1; });
  counts.面试分类 = ivCat;
  Object.keys(ivCat).forEach(k => { if (ivCat[k] < 2) notes.push('面试维度「' + k + '」只有 ' + ivCat[k] + ' 题，偏单薄'); });

  /* E. 阶段构成 */
  counts.阶段 = CORE.phases.map(p => p.id + ':' + p.modules.length).join(' ');
  CORE.phases.forEach(p => { if (p.modules.length < 3) notes.push('阶段 ' + p.id + ' 只有 ' + p.modules.length + ' 模块'); });

  /* F. 源文件与生成产物的时间关系（忘跑 gen-data 是高频事故） */
  const srcM = fs.statSync(path.join(ROOT, '.cache/phases.json')).mtimeMs;
  const genM = fs.statSync(path.join(ROOT, 'js/data/10-core.js')).mtimeMs;
  if (srcM > genM + 1000) errs.push('.cache/phases.json 比 js/data/10-core.js 新 → 忘记跑 node tools/gen-data.js');
  const genM2 = fs.statSync(path.join(ROOT, 'js/data/10-core.js')).mtimeMs;
  const dicM = fs.statSync(path.join(ROOT, '.cache/dic.json')).mtimeMs;
  if (dicM > genM2 + 1000) errs.push('.cache/dic.json 比 10-core.js 新 → 忘记跑 node tools/gen-data.js');

  /* G. README 数字必须等于真实数量 */
  /* CLI 条数以前只有跑起来才知道（App 自报 392，代理离线重算得 412，谁也无法核对）。
     现在推导规则收口在 js/data/22-cli-rules.js，构建期即可复算出同一数字。 */
  let cliCount = null;
  try {
    const R = require('../js/data/22-cli-rules.js');
    const boot = read('js/32-boot.js');
    const i = boot.indexOf('var COMMON_CMDS = [');
    const j = boot.indexOf('\n  ];', i);
    if (i < 0 || j < 0) throw new Error('找不到 COMMON_CMDS');
    const COMMON = new Function('return ' + boot.slice(i + 'var COMMON_CMDS = '.length, j + 4))();
    cliCount = R.buildCount(CORE, mods, COMMON);
    counts.CLI = cliCount;
  } catch (e) {
    notes.push('CLI 条数复算失败（' + e.message + '）→ 跳过该项核对');
  }

  if (fs.existsSync(OUT_README)) {
    const rm = fs.readFileSync(OUT_README, 'utf8');
    /* v2.0.0 更新日志是历史事实，不参与核对 */
    const rmNow = rm.split(/### v2\.0\.0/)[0];
    const want = {
      '(\\d+) 个知识模块': counts.模块,
      '(\\d+) 个排障案例': counts.排障,
      '(\\d+) 条多厂商命令对照': counts.字典,
      '(\\d+) 道面试真题': counts.面试,
      '\\| 知识模块 \\| (\\d+) \\|': counts.模块,
      '\\| 排障案例 \\| (\\d+) \\|': counts.排障,
      '\\| 命令对照 \\| (\\d+) \\|': counts.字典,
      '\\| 面试真题 \\| (\\d+) \\|': counts.面试,
      '\\| 速查表 \\| (\\d+) 张 \\|': counts.速查,
      '\\| 术语词典 \\| (\\d+) 条 \\|': counts.术语,
      '\\| 模拟测验 \\| (\\d+) 题 \\|': counts.题库,
      /* 小标题用的是另一套措辞（"25 个真实场景" / "30 道真题"），以前不在断言里，
         所以正文写对了、小标题写错了也能绿。补上，让它们和正文受同一条门禁约束。 */
      '(\\d+) 个真实场景': counts.排障,
      '(\\d+) 道真题': counts.面试
    };
    if (cliCount != null) {
      want['(\\d+) 条 CLI 模拟器命令'] = cliCount;
      want['CLI 终端模拟器 — (\\d+) 条命令'] = cliCount;
      want['\\*\\*(\\d+) 条命令\\*\\*'] = cliCount;
    }
    /* 全量核对，不再只取第一个命中。以前 README:17 写对就能让 :50/:82 的错误数字
       蒙混过关 —— 一个不可能失败的检查等于没有检查。 */
    Object.keys(want).forEach(re => {
      const g = new RegExp(re, 'g');
      let m, hits = 0;
      while ((m = g.exec(rmNow))) {
        hits++;
        if (Number(m[1]) !== want[re]) {
          const line = rmNow.slice(0, m.index).split('\n').length;
          errs.push('README:' + line + ' 写 ' + m[1] + '，实际 ' + want[re] + ' → ' + re);
        }
      }
      if (!hits) notes.push('README 未出现「' + re + '」（可能被改写，核对不到）');
    });
    /* H. 被否决的措辞 */
    BANNED.forEach(([re, why]) => {
      const m = rmNow.match(re);
      if (m) errs.push('README 出现禁用措辞「' + m[0] + '」：' + why);
    });
  } else {
    notes.push('未找到 README，跳过文案核对');
  }

  /* I. 产物里的禁用措辞（模板/JS 拼出来的那部分最容易漏） */
  const artifact = path.resolve(ROOT, '..', 'app/src/main/assets/index.html');
  if (fs.existsSync(artifact)) {
    const a = fs.readFileSync(artifact, 'utf8');
    BANNED.forEach(([re, why]) => {
      const m = a.match(re);
      if (m) errs.push('产物出现禁用措辞「' + m[0] + '」：' + why);
    });
  }

  return { errs, notes, counts };
}

function gate() {
  const r = analyze();
  if (r.errs.length) {
    console.log('✗ 内容一致性门禁未通过（' + r.errs.length + ' 项）：');
    r.errs.slice(0, 24).forEach(e => console.log('    - ' + e));
    if (r.errs.length > 24) console.log('    …另有 ' + (r.errs.length - 24) + ' 项');
  }
  return { ok: r.errs.length === 0, errs: r.errs, notes: r.notes, counts: r.counts };
}

module.exports = { analyze, gate, MOD_FIELDS };

if (require.main === module) {
  const r = analyze();
  console.log('真实数量: ' + JSON.stringify(r.counts));
  r.notes.forEach(n => console.log('· ' + n));
  if (!r.errs.length) console.log('✓ 内容一致性检查通过（' + r.counts.模块 + ' 模块字段全齐）');
  else { console.log('\n' + r.errs.map(e => '✗ ' + e).join('\n')); if (process.argv.includes('--gate')) process.exit(1); }
}
