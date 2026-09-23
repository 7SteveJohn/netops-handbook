/* ============================================================
 * NetOps 2.0 · 数据索引 / 状态 / 卡片渲染原语
 * ============================================================ */
(function (w, d) {
  'use strict';

  var U = w.NetUI, T = w.NetTopo;
  var CORE = w.NETOPS_CORE, EXT = w.NETOPS_EXT, QUIZ = w.NETOPS_QUIZ;
  var esc = U.esc, icon = U.icon;

  var A = {};   /* App 命名空间 */
  A.CORE = CORE; A.EXT = EXT; A.QUIZ = QUIZ;

  /* ---------------- 扁平索引 ---------------- */
  var MODS = [], BY_ID = {}, PH = {};
  CORE.phases.forEach(function (p) {
    PH[p.id] = p;
    p.modules.forEach(function (m, i) {
      m.pid = p.id; m.color = p.color; m.pshort = p.short; m.kind = 'mod';
      /* 序列位置：搜索、收藏、错题锚点都会绕过阶段列表直接落到某一张卡，
         卡片需要自己知道在这条路上排第几（阶段内 n/N） */
      m.pidx = i + 1; m.pcount = p.modules.length;
      MODS.push(m); BY_ID[m.id] = m;
    });
  });
  CORE.faults.forEach(function (f) { f.kind = 'fault'; f.color = 'rose'; BY_ID[f.id] = f; });
  CORE.interview.forEach(function (q) { q.kind = 'iv'; q.color = 'amber'; BY_ID[q.id] = q; });

  A.MODS = MODS; A.BY_ID = BY_ID; A.PH = PH;
  A.TOTAL = MODS.length + CORE.faults.length + CORE.interview.length;

  /* 分类去重（保持出现顺序） */
  function cats(arr) {
    var seen = {}, out = [];
    arr.forEach(function (x) {
      var c = normCat(x.cat);
      if (c && !seen[c]) { seen[c] = 1; out.push(c); }
    });
    return out;
  }
  function normCat(c) {
    c = String(c || '其他');
    if (c.toLowerCase() === 'overlay') return 'Overlay';
    return c;
  }
  A.cats = cats; A.normCat = normCat;
  A.FAULT_CATS = cats(CORE.faults);
  A.IV_CATS = cats(CORE.interview);
  A.DICT_CATS = cats(CORE.dict.rows);

  /* ---------------- 状态 ---------------- */
  var S = {
    done: U.store.get('done', {}),
    fav: U.store.get('fav', {}),
    vendor: U.store.get('vendor', 'hw'),
    quizBest: U.store.get('quizBest', 0),
    quizRuns: U.store.get('quizRuns', 0),
    /* 错题本：{ 题目指纹: { m: 模块锚点, n: 错次 } } */
    wrong: U.store.get('quizWrong', {}),
    /* 面试目标岗位画像：any | 数通 | 云原生 | SRE | 安全 | 自动化 */
    jobFocus: U.store.get('jobFocus', 'any'),
    streak: U.store.get('streak', { n: 0, last: '' })
  };
  A.S = S;

  /* ---------------- 面试个性化 ----------------
     目标岗位决定哪些维度该优先看；薄弱点来自错题本的模块锚点 + 还没标掌握的关联模块。
     相关性只由「你在错题本里错过的模块」和「目标岗位维度」两件事决定；
     「还没标掌握的关联模块」只做同分内的次级排序。
     只做排序与标注，不藏题：任何时候「全部」都能看到完整 72 题。 */
  var IV_FOCUS = {
    '数通':   ['数通架构', '园区与无线', '排障思路', '面试形式'],
    '云原生': ['云原生', '云网络与成本', '算力与AI', '面试形式'],
    'SRE':    ['稳定性工程', '云原生', '排障思路', '面试形式'],
    '安全':   ['安全与零信任', '排障思路', '面试形式'],
    '自动化': ['自动化', '云网络与成本', '面试形式']
  };
  A.IV_FOCUS = IV_FOCUS;

  function weakModuleSet() {
    var out = {};
    Object.keys(S.wrong || {}).forEach(function (k) {
      var m = (S.wrong[k] || {}).m; if (m) out[m] = 1;
    });
    return out;
  }
  /* 返回带 r（优先级分）与 why（给用户看的理由）的拷贝列表，按分数降序、同分保持原序 */
  A.ivRank = function (list) {
    var weak = weakModuleSet();
    var cats = IV_FOCUS[S.jobFocus] || null;
    var arr = list.map(function (q, i) {
      var rel = q.rel || [], why = [], s = 0, weakHit = 0, undone = 0, k;
      for (k = 0; k < rel.length; k++) {
        if (weak[rel[k]]) { weakHit++; }
        if (!isDone(rel[k])) { undone++; }
      }
      if (weakHit) { s += 6; why.push('这块你错过题'); }
      if (cats && cats.indexOf(A.normCat(q.cat)) >= 0) { s += 3; why.push('目标岗位相关'); }
      return { q: q, s: s, tie: undone, why: why, i: i };
    });
    /* 「还没标掌握」不参与相关性打分，只做同分内的次级排序：
       否则进度全空的新用户会看到 45/50 都「与你相关」，等于没有信息。 */
    arr.sort(function (a, b) {
      if (b.s !== a.s) return b.s - a.s;
      if (b.tie !== a.tie) return b.tie - a.tie;
      return a.i - b.i;
    });
    return arr;
  };

  function saveDone() { U.store.set('done', S.done); }
  function saveFav() { U.store.set('fav', S.fav); }
  A.saveDone = saveDone; A.saveFav = saveFav;

  function isDone(id) { return !!S.done[id]; }
  function isFav(id) { return !!S.fav[id]; }
  A.isDone = isDone; A.isFav = isFav;

  function countDone(list) {
    var n = 0; list.forEach(function (x) { if (S.done[x.id]) n++; }); return n;
  }
  A.countDone = countDone;

  function doneAll() { return Object.keys(S.done).length; }
  A.doneAll = doneAll;

  /* 连续打卡 */
  function touchStreak() {
    var t = new Date(), k = t.getFullYear() + '-' + (t.getMonth() + 1) + '-' + t.getDate();
    if (S.streak.last === k) return;
    var y = new Date(t.getTime() - 864e5);
    var yk = y.getFullYear() + '-' + (y.getMonth() + 1) + '-' + y.getDate();
    S.streak.n = (S.streak.last === yk) ? S.streak.n + 1 : 1;
    S.streak.last = k;
    U.store.set('streak', S.streak);
  }
  A.touchStreak = touchStreak;

  /* ---------------- 通用片段 ---------------- */
  function sec(ico, text) {
    return '<div class="sec"><span class="sec__t">' + icon(ico, 'icon--xs') + esc(text) +
           '</span><i class="sec__line"></i></div>';
  }
  function bullets(list, mod) {
    if (!list || !list.length) return '';
    return '<ul class="bullets' + (mod ? ' bullets--' + mod : '') + '">' +
      list.map(function (x) { return '<li><span>' + esc(x) + '</span></li>'; }).join('') + '</ul>';
  }
  function steps(list) {
    if (!list || !list.length) return '';
    return '<ol class="steps">' + list.map(function (x) { return '<li><span>' + esc(x) + '</span></li>'; }).join('') + '</ol>';
  }
  function lvlDots(n, max) {
    var s = '<span class="lvl">', i;
    for (i = 1; i <= (max || 4); i++) s += '<i class="' + (i <= n ? 'on' : '') + '"></i>';
    return s + '</span>';
  }
  function empty(t, dsc, ico) {
    return '<div class="empty">' + icon(ico || 'i-search', 'icon--lg') +
      '<div class="empty__t">' + esc(t) + '</div><div class="empty__d">' + esc(dsc || '') + '</div></div>';
  }
  A.sec = sec; A.bullets = bullets; A.steps = steps; A.lvlDots = lvlDots; A.empty = empty;

  /* ---------------- 卡片外壳 ---------------- */
  /* 折叠卡片，body 首次展开才渲染（58+ 卡片时显著提速） */
  function card(o) {
    return '<article class="card' + (o.done ? ' is-done' : '') + '" data-accent="' + (o.color || 'teal') +
      '" data-id="' + esc(o.id) + '" data-kind="' + esc(o.kind) + '">' +
      '<button class="card__head" type="button" data-toggle>' +
        '<span class="card__chip">' + esc(o.chip) + '</span>' +
        '<span class="grow" style="min-width:0">' +
          '<span class="card__title">' + (o.titleHtml || esc(o.title)) + '</span>' +
          (o.meta ? '<span class="card__meta">' + o.meta + '</span>' : '') +
        '</span>' +
        (o.done ? icon('i-check-circle', 'icon--sm') + '' : '') +
        '<svg class="icon icon--sm card__arrow" aria-hidden="true"><use href="#i-chev-down"/></svg>' +
      '</button>' +
      '<div class="card__body"><div><div class="card__inner" data-lazy="1"></div></div></div>' +
    '</article>';
  }
  A.card = card;

  function badge(text, mod) {
    return '<span class="badge badge--' + (mod || 'soft') + '">' + esc(text) + '</span>';
  }
  A.badge = badge;

  function actionBar(id, doneLabel) {
    var dn = isDone(id), fv = isFav(id);
    return '<div class="row gap-1" style="margin-top:14px">' +
      '<button class="btn ' + (dn ? 'btn--ok' : 'btn--soft') + ' btn--sm grow" type="button" data-done="' + esc(id) + '">' +
        icon(dn ? 'i-check-circle' : 'i-circle', 'icon--sm') + (dn ? '已' + (doneLabel || '掌握') : '标记' + (doneLabel || '掌握')) +
      '</button>' +
      '<button class="btn btn--soft btn--sm' + (fv ? ' is-fav' : '') + '" type="button" data-fav="' + esc(id) + '" ' +
        'style="' + (fv ? 'color:var(--warn)' : '') + '">' +
        '<svg class="icon icon--sm' + (fv ? ' icon--fill' : '') + '" aria-hidden="true"><use href="#i-star"/></svg>' +
        (fv ? '已收藏' : '收藏') +
      '</button>' +
    '</div>';
  }
  A.actionBar = actionBar;

  /* 序列导航：搜索/收藏/错题锚点都会绕过阶段列表落到单张卡，这里给一条可点的来路。
     跳转复用 data-jumpcard（跳到该卡所在阶段并展开）；跨阶段时把目标阶段名一起标出。 */
  function seqNav(m) {
    var i = MODS.indexOf(m);
    if (i < 0) return '';
    var pv = i > 0 ? MODS[i - 1] : null, nx = i < MODS.length - 1 ? MODS[i + 1] : null;
    if (!pv && !nx) return '';
    function short(x) {
      return String(x.t).replace(/^🎯\s*/, '').replace(/^[A-Za-z0-9_.]+\.\s*/, '').slice(0, 14);
    }
    function btn(x, isPrev) {
      if (!x) return '<span class="grow"></span>';
      return '<button class="btn btn--soft btn--sm grow" type="button" data-jumpcard="' + esc(x.id) + '" ' +
        'style="min-width:0;justify-content:' + (isPrev ? 'flex-start' : 'flex-end') + '">' +
        (isPrev ? icon('i-chev-left', 'icon--xs') : '') +
        '<span style="min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' +
          esc(x.pshort) + ' · ' + esc(short(x)) +
        '</span>' +
        (isPrev ? '' : icon('i-chev-right', 'icon--xs')) + '</button>';
    }
    return '<div class="row gap-1" style="margin-top:10px">' + btn(pv, true) + btn(nx, false) + '</div>';
  }

  /* ---------------- 卡片正文渲染 ---------------- */
  function modBody(m) {
    var h = '';
    if (m.y) h += sec('i-book', '原理速记') + '<div class="note note--info">' + esc(m.y) + '</div>';
    if (m.dg) { var t = T.render(m.dg); if (t) h += sec('i-network', '拓扑示意') + t; }
    if (m.c) h += sec('i-terminal', '核心命令') + U.term(m.c, { label: cmdLabel(m.c) });
    if (m.o) h += sec('i-activity', '回显参考（示意，非设备逐字输出）') + U.term(m.o, { out: true, label: 'output' });
    if (m.j && m.j.length) h += sec('i-eye', '关键解读') + bullets(m.j);
    if (m.u && m.u.length) h += sec('i-target', '应用场景') + bullets(m.u);
    if (m.w && m.w.length) h += sec('i-alert', '避坑指南') + bullets(m.w, 'danger');
    if (m.v) h += sec('i-check-circle', '验证方式') + '<div class="note note--ok">' + esc(m.v) + '</div>';
    if (m.l && m.l.length) h += sec('i-flask', '动手实验') + steps(m.l);
    h += actionBar(m.id);
    h += seqNav(m);
    return h;
  }

  function faultBody(f) {
    var h = '';
    if (f.sym) h += sec('i-alert', '现象描述') + '<div class="note note--danger">' + esc(f.sym) + '</div>';
    if (f.cause) h += sec('i-help', '根因分析') + '<div class="note note--warn">' + esc(f.cause) + '</div>';
    if (f.dg) { var t = T.render(f.dg); if (t) h += sec('i-network', '拓扑示意') + t; }
    if (f.c) h += sec('i-terminal', '处置命令') + U.term(f.c, { label: cmdLabel(f.c) });
    if (f.o) h += sec('i-activity', '回显参考（示意，非设备逐字输出）') + U.term(f.o, { out: true, label: 'output' });
    if (f.j && f.j.length) h += sec('i-eye', '关键解读') + bullets(f.j);
    if (f.l && f.l.length) h += sec('i-route', '排查路径') + dtree(f.l);
    if (f.u && f.u.length) h += sec('i-target', '适用场景') + bullets(f.u);
    if (f.w && f.w.length) h += sec('i-alert', '避坑指南') + bullets(f.w, 'danger');
    if (f.v) h += sec('i-check-circle', '验证闭环') + '<div class="note note--ok">' + esc(f.v) + '</div>';
    h += actionBar(f.id, '已解决');
    return h;
  }

  function ivBody(q) {
    var h = '';
    if (q.point) h += sec('i-target', '考察点') + '<div class="note note--info">' + esc(q.point) + '</div>';
    if (q.star) h += sec('i-zap', 'STAR 拆解') + '<div class="note note--warn">' + esc(q.star) + '</div>';
    if (q.answer) h += sec('i-award', '参考话术') + '<div class="answer">' + esc(q.answer) + '</div>';
    if (q.fb) h += sec('i-info', '面试官视角') + '<div class="note note--plain">' + esc(q.fb) + '</div>';
    if (q.j && q.j.length) h += sec('i-eye', '加分项') + bullets(q.j);
    if (q.u && q.u.length) h += sec('i-briefcase', '常见追问') + bullets(q.u);
    if (q.w && q.w.length) h += sec('i-alert', '雷区') + bullets(q.w, 'danger');
    h += actionBar(q.id, '已准备');
    return h;
  }

  function dtree(list) {
    return '<div>' + list.map(function (x, i) {
      var p = String(x).split(/：|:/);
      var t = p.length > 1 ? p.shift() : ('步骤 ' + (i + 1));
      var dsc = p.length ? p.join('：') : String(x);
      return '<div class="dtree__step"><div class="dtree__num">' + (i + 1) + '</div>' +
        '<div style="min-width:0"><div class="dtree__t">' + esc(t) + '</div>' +
        '<div class="dtree__d">' + esc(dsc) + '</div></div></div>';
    }).join('') + '</div>';
  }
  A.dtree = dtree;

  /* 猜命令块所属平台，用于终端标题栏 */
  function cmdLabel(c) {
    var s = String(c || '');
    if (/^\s*(&lt;|<)?Huawei|\[Huawei|\bdisplay\b|system-view/m.test(s)) return 'VRP · 华为';
    if (/\bshow run\b|\bconf t\b|Cisco|\bswitchport\b/i.test(s)) return 'IOS · Cisco';
    if (/kubectl|docker|helm/.test(s)) return 'kubectl / docker';
    if (/^\s*(sudo|ip |ping|tcpdump|curl|systemctl|cat |vi )/m.test(s)) return 'shell · linux';
    if (/python|import /.test(s)) return 'python';
    return 'command';
  }
  A.cmdLabel = cmdLabel;

  A.bodyOf = function (item) {
    if (item.kind === 'fault') return faultBody(item);
    if (item.kind === 'iv') return ivBody(item);
    return modBody(item);
  };

  /* ---------------- 卡片元信息 ---------------- */
  A.modCard = function (m, q) {
    var meta = badge(PH[m.pid].short, 'phase');
    meta += badge(m.pidx + '/' + m.pcount);      /* 序列位置：冷落到这张卡时给个坐标 */
    if (m.dg) meta += badge('拓扑图');
    if (m.lab) meta += badge('实验', 'ok');
    if (isFav(m.id)) meta += badge('★ 收藏', 'warn');
    return card({
      id: m.id, kind: 'mod', chip: m.id, color: m.color,
      titleHtml: U.hl(cleanTitle(m.t), q), meta: meta, done: isDone(m.id)
    });
  };
  A.faultCard = function (f, q) {
    var meta = badge(normCat(f.cat), 'danger');
    if (isFav(f.id)) meta += badge('★ 收藏', 'warn');
    return card({
      id: f.id, kind: 'fault', chip: f.id, color: 'rose',
      titleHtml: U.hl(cleanTitle(f.t), q), meta: meta, done: isDone(f.id)
    });
  };
  A.ivCard = function (x, q, note) {
    var meta = badge(normCat(x.cat), 'accent');
    if (note && note.length) meta += badge(note[0], 'ok');
    if (isFav(x.id)) meta += badge('★ 收藏', 'warn');
    return card({
      id: x.id, kind: 'iv', chip: x.id.replace('IVQ', 'Q'), color: 'amber',
      titleHtml: U.hl(cleanTitle(x.t), q), meta: meta, done: isDone(x.id)
    });
  };

  function cleanTitle(t) {
    return String(t || '').replace(/^\s*(Z\d+|G\d+|IVQ\d+)[.、]\s*/, '');
  }
  A.cleanTitle = cleanTitle;

  /* ---------------- 全局搜索 ---------------- */
  function searchAll(q) {
    q = String(q || '').trim().toLowerCase();
    if (q.length < 1) return null;
    var toks = q.split(/\s+/).filter(Boolean);
    function hit(hay) {
      hay = hay.toLowerCase();
      for (var i = 0; i < toks.length; i++) if (hay.indexOf(toks[i]) < 0) return false;
      return true;
    }
    var res = { mods: [], faults: [], iv: [], dict: [], gloss: [], refs: [] };
    /* 数据里同类字段可能是字符串、数组或嵌套数组（例如某些 j/w 只写了一行），
       统一拍平成文本再比对，避免 .join 抛错把整个搜索打挂。 */
    function txt(v) {
      if (v == null) return '';
      if (Array.isArray(v)) { var acc = []; for (var i = 0; i < v.length; i++) acc.push(txt(v[i])); return acc.join(' '); }
      return String(v);
    }
    MODS.forEach(function (m) {
      if (hit([m.t, m.y, m.c, m.o, m.v, txt(m.j), txt(m.u), txt(m.w), txt(m.l)].join(' '))) res.mods.push(m);
    });
    CORE.faults.forEach(function (f) {
      if (hit([f.t, f.sym, f.cause, f.c, f.o, f.v, txt(f.j), txt(f.u), txt(f.w), txt(f.l)].join(' '))) res.faults.push(f);
    });
    CORE.interview.forEach(function (x) {
      if (hit([x.t, x.point, x.answer, x.star, x.fb, txt(x.j), txt(x.u), txt(x.w)].join(' '))) res.iv.push(x);
    });
    CORE.dict.rows.forEach(function (r) {
      if (hit([r.fn, r.hw, r.cs, r.zte, r.lx].join(' '))) res.dict.push(r);
    });
    /* 速查表此前完全不在索引里：搜 443、/27、502、Init:Completed 都查不到 */
    (EXT.refs || []).forEach(function (s) {
      if (hit([s.t, s.d, s.tip, txt(s.cols), txt(s.rows)].join(' '))) res.refs.push(s);
    });
    (EXT.glossary || []).forEach(function (g) {
      if (hit(g.t + ' ' + g.d)) res.gloss.push(g);
    });
    res.n = res.mods.length + res.faults.length + res.iv.length + res.dict.length + res.gloss.length + res.refs.length;
    return res;
  }
  A.searchAll = searchAll;

  w.NetApp = A;
})(window, document);
