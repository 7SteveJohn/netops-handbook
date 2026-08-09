/* ============================================================
 * NetOps 2.0 · 视图渲染
 * ============================================================ */
(function (w, d) {
  'use strict';

  var U = w.NetUI, A = w.NetApp, T = w.NetTopo;
  var CORE = A.CORE, EXT = A.EXT, QUIZ = A.QUIZ;
  var esc = U.esc, icon = U.icon, sec = A.sec, badge = A.badge, empty = A.empty;

  var V = {};

  /* 页面外壳 */
  function page(inner) { return '<div class="page">' + inner + '</div>'; }

  /* 网格底纹（纯 SVG，无外链） */
  var GRID = '<svg class="hero__grid" aria-hidden="true" width="100%" height="100%">' +
    '<defs><pattern id="hg" width="22" height="22" patternUnits="userSpaceOnUse">' +
    '<path d="M22 0H0V22" fill="none" stroke="rgba(255,255,255,.4)" stroke-width=".7"/>' +
    '</pattern></defs><rect width="100%" height="100%" fill="url(#hg)"/></svg>';

  /* ================= 学习首页 ================= */
  V.learn = function () {
    var doneMods = A.countDone(A.MODS);
    var pct = Math.round(doneMods / A.MODS.length * 100);
    var h = '';

    h += '<div class="hero">' + GRID + '<div class="hero__glow"></div><div class="hero__inner">' +
      '<div class="hero__eyebrow">' + icon('i-zap', 'icon--xs') + 'OFFLINE READY</div>' +
      '<div class="hero__title">全栈网络运维<br>技能导航 2.0</div>' +
      '<div class="hero__desc">从零基础数通到云原生架构 · 排障字典 · 面试题库<br>纯离线运行，无需任何网络连接</div>' +
      '<div class="hero__stats">' +
        stat(A.MODS.length, '知识模块') +
        stat(CORE.faults.length, '排障案例') +
        stat(CORE.dict.rows.length, '命令对照') +
        stat(CORE.interview.length, '面试真题') +
      '</div></div></div>';

    /* 进度 + 继续学习 */
    var next = A.MODS.filter(function (m) { return !A.isDone(m.id); })[0];
    h += '<div class="card" style="margin-top:12px" data-accent="teal"><div style="padding:13px">' +
      '<div class="row gap-3">' +
        '<div class="ring" style="--p:' + pct + '"><div class="ring__val">' +
          '<div class="ring__num">' + pct + '<span style="font-size:12px">%</span></div>' +
          '<div class="ring__lbl">已掌握</div></div></div>' +
        '<div class="grow" style="min-width:0">' +
          '<div class="t-sm bold">学习进度</div>' +
          '<div class="t-xs t-mute" style="margin-top:2px">' + doneMods + ' / ' + A.MODS.length + ' 个模块 · 连续打卡 ' + A.S.streak.n + ' 天</div>' +
          (next
            ? '<button class="btn btn--primary btn--sm" style="margin-top:9px" type="button" data-go="phase" data-arg="' + next.pid + '" data-focus="' + next.id + '">' +
                icon('i-play', 'icon--sm') + '继续：' + esc(A.cleanTitle(next.t).slice(0, 12)) + '…</button>'
            : '<div class="badge badge--ok" style="margin-top:9px">' + icon('i-award', 'icon--xs') + '全部模块已掌握</div>') +
        '</div>' +
      '</div></div></div>';

    /* 阶段列表 */
    h += sec('i-layers', '学习路径');
    CORE.phases.forEach(function (p, i) {
      var dn = A.countDone(p.modules), pc = Math.round(dn / p.modules.length * 100);
      h += '<button class="phase" type="button" data-accent="' + p.color + '" data-go="phase" data-arg="' + p.id + '" style="margin-bottom:10px">' +
        '<div class="phase__top">' +
          '<div class="phase__idx">' + (i + 1) + '</div>' +
          '<div class="grow" style="min-width:0">' +
            '<div class="phase__name">' + esc(p.title.replace(/^阶段[一二三四五]：/, '')) + '</div>' +
            '<div class="row gap-1" style="margin-top:3px">' +
              badge(p.short, 'phase') + '<span class="t-xs t-mute">' + p.modules.length + ' 模块</span>' +
              A.lvlDots(p.lvl) +
            '</div>' +
          '</div>' +
          '<svg class="icon icon--sm t-mute" aria-hidden="true"><use href="#i-chev-right"/></svg>' +
        '</div>' +
        '<div class="phase__desc">' + esc(p.desc) + '</div>' +
        '<div class="phase__foot">' +
          '<div class="bar grow"><div class="bar__fill" style="width:' + pc + '%"></div></div>' +
          '<span class="t-xs mono t-mute">' + dn + '/' + p.modules.length + '</span>' +
        '</div>' +
      '</button>';
    });

    /* 快捷入口 */
    h += sec('i-compass', '工具箱');
    h += '<div class="list">' +
      li('i-book', '速查表', EXT.refs.length + ' 张 · 端口 / 掩码 / OSPF / BGP', 'refs') +
      li('i-flag', '学习路线图', '0-12 个月成长节奏与目标', 'roadmap') +
      li('i-help', '术语词典', EXT.glossary.length + ' 条云原生与数通黑话', 'glossary') +
      li('i-award', '模拟测验', QUIZ.length + ' 道选择题 · 历史最佳 ' + A.S.quizBest + '%', 'quiz') +
      li('i-terminal', 'CLI 模拟器', '离线练习 VRP / Linux 常用命令', 'cli') +
    '</div>';

    return page(h);
  };

  function stat(n, l) {
    return '<div class="hero__stat"><div class="hero__num">' + n + '</div><div class="hero__lbl">' + esc(l) + '</div></div>';
  }
  function li(ico, t, dsc, go, arg) {
    return '<button class="list__item" type="button" data-go="' + go + '"' + (arg ? ' data-arg="' + esc(arg) + '"' : '') + '>' +
      '<span class="list__ico">' + icon(ico, 'icon--sm') + '</span>' +
      '<span class="grow" style="min-width:0"><span class="list__t">' + esc(t) + '</span>' +
      '<span class="list__d ellipsis">' + esc(dsc) + '</span></span>' +
      '<svg class="icon icon--sm t-mute" aria-hidden="true"><use href="#i-chev-right"/></svg></button>';
  }
  V._li = li;

  /* ================= 阶段详情 ================= */
  V.phase = function (pid) {
    var p = A.PH[pid];
    if (!p) return page(empty('阶段不存在', ''));
    var dn = A.countDone(p.modules), pc = Math.round(dn / p.modules.length * 100);
    var h = '<div data-accent="' + p.color + '">';
    h += '<div class="card" style="margin-bottom:12px"><div style="padding:13px">' +
      '<div class="row gap-3">' +
        '<div class="phase__idx">' + icon(p.icon, 'icon--sm') + '</div>' +
        '<div class="grow" style="min-width:0">' +
          '<div class="t-md bold">' + esc(p.title) + '</div>' +
          '<div class="t-xs t-mute" style="margin-top:3px;line-height:1.55">' + esc(p.desc) + '</div>' +
        '</div>' +
      '</div>' +
      '<div class="row gap-1" style="margin-top:11px">' +
        '<div class="bar grow"><div class="bar__fill" style="width:' + pc + '%"></div></div>' +
        '<span class="t-xs mono t-mute">' + dn + '/' + p.modules.length + '</span>' +
        '<button class="btn btn--soft btn--sm" type="button" data-mdphase="' + p.id + '">' +
          icon('i-download', 'icon--xs') + '导出</button>' +
      '</div>' +
    '</div></div>';
    h += p.modules.map(function (m, i) {
      return '<div class="anim-in" style="--i:' + Math.min(i, 12) + '">' + A.modCard(m) + '</div>';
    }).join('');
    h += '</div>';
    return page(h);
  };

  /* ================= 排障字典 ================= */
  V.fault = function (state) {
    var cat = (state && state.cat) || '全部';
    var list = CORE.faults.filter(function (f) { return cat === '全部' || A.normCat(f.cat) === cat; });
    var h = '';
    h += '<div class="note note--danger row gap-1" style="align-items:flex-start">' +
      icon('i-wrench', 'icon--sm') +
      '<span style="font-size:12.5px">' + CORE.faults.length + ' 个真实故障案例，含现象 / 根因 / 命令 / 验证闭环。按分类筛选，或用顶部搜索直达。</span></div>';
    h += '<div class="chips" data-chipgroup="faultCat">' +
      chip('全部', cat === '全部', CORE.faults.length) +
      A.FAULT_CATS.map(function (c) {
        var n = CORE.faults.filter(function (f) { return A.normCat(f.cat) === c; }).length;
        return chip(c, cat === c, n);
      }).join('') + '</div>';
    h += list.length
      ? list.map(function (f, i) { return '<div class="anim-in" style="--i:' + Math.min(i, 12) + '">' + A.faultCard(f) + '</div>'; }).join('')
      : empty('该分类暂无案例', '换个分类试试', 'i-wrench');
    return page(h);
  };

  function chip(label, on, n) {
    return '<button class="chip' + (on ? ' is-active' : '') + '" type="button" data-chip="' + esc(label) + '">' +
      esc(label) + (n != null ? '<span class="count t-xs" style="opacity:.7">' + n + '</span>' : '') + '</button>';
  }
  V._chip = chip;

  /* ================= 命令字典 ================= */
  V.dict = function (state) {
    var cat = (state && state.cat) || '全部';
    var q = (state && state.q) || '';
    var rows = CORE.dict.rows.filter(function (r) {
      if (cat !== '全部' && A.normCat(r.cat) !== cat) return false;
      if (!q) return true;
      var hay = [r.fn, r.hw, r.cs, r.zte, r.lx].join(' ').toLowerCase();
      return hay.indexOf(q.toLowerCase()) >= 0;
    });
    var h = '';
    h += '<div class="dict__hint">' + icon('i-info', 'icon--sm') +
      '<span>四家平台命令横向对照 · 点击任意一行可复制该命令。共 ' + CORE.dict.rows.length + ' 条。</span></div>';
    h += '<div class="chips" data-chipgroup="dictCat" style="margin-top:10px">' +
      chip('全部', cat === '全部', CORE.dict.rows.length) +
      A.DICT_CATS.map(function (c) {
        var n = CORE.dict.rows.filter(function (r) { return A.normCat(r.cat) === c; }).length;
        return chip(c, cat === c, n);
      }).join('') + '</div>';

    if (!rows.length) return page(h + empty('没有匹配的命令', '试试其它关键词', 'i-terminal'));

    h += '<div class="list">' + rows.map(function (r) {
      var cells = CORE.dict.cols.map(function (c) {
        var v = r[c.k];
        return '<div class="dict__kv"><div class="dict__k">' + esc(c.n.split(' ')[0]) + '</div>' +
          '<div class="dict__v' + (v && v !== '-' ? '' : ' is-na') + '" data-cmd="' + esc(v || '') + '">' +
          esc(v && v !== '-' ? v : '不适用') + '</div></div>';
      }).join('');
      return '<div class="dict__row">' +
        '<div class="dict__fn">' + icon('i-chev-right', 'icon--xs') + esc(r.fn) +
        '<span class="grow"></span>' + badge(A.normCat(r.cat)) + '</div>' + cells + '</div>';
    }).join('') + '</div>';
    return page(h);
  };

  /* ================= 面试题库 ================= */
  V.iv = function (state) {
    var cat = (state && state.cat) || '全部';
    var list = CORE.interview.filter(function (x) { return cat === '全部' || A.normCat(x.cat) === cat; });
    var dn = A.countDone(CORE.interview);
    var h = '';
    h += '<div class="card" data-accent="amber" style="margin-bottom:11px"><div style="padding:13px">' +
      '<div class="row gap-3">' +
        '<div class="phase__idx">' + icon('i-briefcase', 'icon--sm') + '</div>' +
        '<div class="grow"><div class="t-sm bold">面试冲刺</div>' +
        '<div class="t-xs t-mute">' + CORE.interview.length + ' 道高频真题 · 已准备 ' + dn + ' 道</div></div>' +
        '<button class="btn btn--soft btn--sm" type="button" data-go="quiz">' + icon('i-award', 'icon--xs') + '测验</button>' +
      '</div>' +
      '<div class="bar" style="margin-top:11px"><div class="bar__fill" style="width:' +
        Math.round(dn / CORE.interview.length * 100) + '%"></div></div>' +
    '</div></div>';
    h += '<div class="chips" data-chipgroup="ivCat">' +
      chip('全部', cat === '全部', CORE.interview.length) +
      A.IV_CATS.map(function (c) {
        var n = CORE.interview.filter(function (x) { return A.normCat(x.cat) === c; }).length;
        return chip(c, cat === c, n);
      }).join('') + '</div>';
    h += list.length
      ? list.map(function (x, i) { return '<div class="anim-in" style="--i:' + Math.min(i, 12) + '">' + A.ivCard(x) + '</div>'; }).join('')
      : empty('该分类暂无题目', '', 'i-briefcase');
    return page(h);
  };

  /* ================= 我的 ================= */
  V.me = function () {
    var dm = A.countDone(A.MODS), df = A.countDone(CORE.faults), di = A.countDone(CORE.interview);
    var all = dm + df + di, pct = Math.round(all / A.TOTAL * 100);
    var favN = Object.keys(A.S.fav).length;
    var h = '';

    h += '<div class="card" data-accent="teal"><div style="padding:15px 13px">' +
      '<div class="center" style="display:flex;flex-direction:column;align-items:center">' +
        '<div class="ring" style="--p:' + pct + ';width:112px;height:112px"><div class="ring__val">' +
          '<div class="ring__num">' + pct + '<span style="font-size:13px">%</span></div>' +
          '<div class="ring__lbl">总进度</div></div></div>' +
        '<div class="t-xs t-mute" style="margin-top:10px">' + all + ' / ' + A.TOTAL + ' 项已完成 · 连续打卡 ' + A.S.streak.n + ' 天</div>' +
      '</div></div></div>';

    h += '<div class="stat-grid" style="margin-top:11px">' +
      stCell(dm, '知识模块', A.MODS.length) +
      stCell(df, '排障案例', CORE.faults.length) +
      stCell(di, '面试真题', CORE.interview.length) +
      stCell(A.S.quizBest + '%', '测验最佳', null) +
    '</div>';

    h += sec('i-bookmark', '我的收藏');
    h += '<div class="list">' + li('i-star', '收藏夹', favN ? favN + ' 条已收藏' : '还没有收藏任何内容', 'fav') + '</div>';

    h += sec('i-compass', '资源');
    h += '<div class="list">' +
      li('i-book', '速查表', EXT.refs.length + ' 张高频对照表', 'refs') +
      li('i-help', '术语词典', EXT.glossary.length + ' 条', 'glossary') +
      li('i-flag', '学习路线图', '0-12 个月成长节奏', 'roadmap') +
      li('i-award', '模拟测验', QUIZ.length + ' 题 · 已测 ' + A.S.quizRuns + ' 次', 'quiz') +
      li('i-terminal', 'CLI 模拟器', '离线命令沙盒', 'cli') +
    '</div>';

    h += sec('i-settings', '设置');
    h += '<div class="list">' +
      '<div class="list__item" data-noripple><span class="list__ico">' + icon('i-moon', 'icon--sm') + '</span>' +
        '<span class="grow"><span class="list__t">外观主题</span><span class="list__d" id="themeLbl"></span></span>' +
        '<span class="row gap-1" id="themeSeg"></span></div>' +
      '<div class="list__item" data-toggle-motion><span class="list__ico">' + icon('i-activity', 'icon--sm') + '</span>' +
        '<span class="grow"><span class="list__t">动画效果</span><span class="list__d">关闭可提升低端机流畅度</span></span>' +
        '<span class="switch' + (U.motion.get() === 'on' ? ' is-on' : '') + '" id="swMotion"></span></div>' +
      '<button class="list__item" type="button" data-export="all"><span class="list__ico">' + icon('i-download', 'icon--sm') + '</span>' +
        '<span class="grow"><span class="list__t">导出全部笔记</span><span class="list__d">生成 Markdown 文件</span></span>' +
        '<svg class="icon icon--sm t-mute" aria-hidden="true"><use href="#i-chev-right"/></svg></button>' +
      '<button class="list__item" type="button" data-reset><span class="list__ico" style="color:var(--danger)">' + icon('i-trash', 'icon--sm') + '</span>' +
        '<span class="grow"><span class="list__t" style="color:var(--danger)">清空学习记录</span><span class="list__d">进度、收藏、测验成绩</span></span>' +
        '<svg class="icon icon--sm t-mute" aria-hidden="true"><use href="#i-chev-right"/></svg></button>' +
    '</div>';

    h += '<div class="center t-xs t-mute" style="padding:22px 0 6px;line-height:1.7">' +
      'NetOps 2.0 · 全栈网络运维技能导航<br>完全离线运行 · 无网络权限 · 无第三方依赖</div>';
    return page(h);
  };

  function stCell(n, l, total) {
    return '<div class="stat"><div class="stat__n">' + n +
      (total != null ? '<span class="t-xs t-mute" style="font-weight:600"> /' + total + '</span>' : '') +
      '</div><div class="stat__l">' + esc(l) + '</div></div>';
  }

  /* ================= 收藏夹 ================= */
  V.fav = function () {
    var ids = Object.keys(A.S.fav);
    if (!ids.length) return page(empty('收藏夹是空的', '在任意卡片底部点「收藏」，内容会出现在这里', 'i-star'));
    var h = '', groups = { mod: [], fault: [], iv: [] };
    ids.forEach(function (id) { var it = A.BY_ID[id]; if (it) groups[it.kind].push(it); });
    if (groups.mod.length) { h += sec('i-layers', '知识模块'); h += groups.mod.map(function (m) { return A.modCard(m); }).join(''); }
    if (groups.fault.length) { h += sec('i-wrench', '排障案例'); h += groups.fault.map(function (f) { return A.faultCard(f); }).join(''); }
    if (groups.iv.length) { h += sec('i-briefcase', '面试真题'); h += groups.iv.map(function (x) { return A.ivCard(x); }).join(''); }
    return page(h);
  };

  /* ================= 速查表 ================= */
  V.refs = function () {
    var h = '<div class="note note--info" style="margin-bottom:11px">' +
      '高频速查表，面试前 10 分钟过一遍最有效。表格可左右滑动。</div>';
    h += '<div class="list">' + EXT.refs.map(function (r) {
      return li(r.icon || 'i-book', r.t, r.d, 'ref', r.id);
    }).join('') + '</div>';
    return page(h);
  };

  V.ref = function (id) {
    var r = null;
    EXT.refs.forEach(function (x) { if (x.id === id) r = x; });
    if (!r) return page(empty('速查表不存在', ''));
    var h = '<div class="t-lg bold">' + esc(r.t) + '</div>' +
      '<div class="t-xs t-mute" style="margin:4px 0 12px">' + esc(r.d) + '</div>';
    h += table(r.cols, r.rows);
    return page(h);
  };

  function table(cols, rows) {
    return '<div class="tablewrap"><div class="tablescroll"><table class="tbl"><thead><tr>' +
      cols.map(function (c) { return '<th>' + esc(c) + '</th>'; }).join('') +
      '</tr></thead><tbody>' +
      rows.map(function (r) {
        return '<tr>' + r.map(function (c) { return '<td>' + esc(c) + '</td>'; }).join('') + '</tr>';
      }).join('') + '</tbody></table></div></div>';
  }
  V._table = table;

  /* ================= 术语词典 ================= */
  V.glossary = function () {
    var h = '<div class="note note--plain" style="margin-bottom:11px">' +
      EXT.glossary.length + ' 条网络与云原生黑话，面试听不懂就完了。</div>';
    h += '<div class="list">' + EXT.glossary.map(function (g) {
      return '<div class="list__item" data-noripple style="align-items:flex-start">' +
        '<span class="list__ico" style="background:var(--accent-soft);color:var(--accent)">' + icon('i-help', 'icon--sm') + '</span>' +
        '<span class="grow" style="min-width:0"><span class="list__t mono">' + esc(g.t) + '</span>' +
        '<span class="list__d" style="white-space:normal">' + esc(g.d) + '</span></span></div>';
    }).join('') + '</div>';
    return page(h);
  };

  /* ================= 路线图 ================= */
  V.roadmap = function () {
    var colors = ['teal', 'blue', 'purple', 'rose', 'amber'];
    var h = '<div class="note note--info" style="margin-bottom:14px">' +
      '按这个节奏走，一年内可以从零基础打到能独立扛云网融合项目。</div>';
    h += '<div class="rm">' + EXT.roadmap.map(function (n, i) {
      return '<div class="rm__node" data-accent="' + (n.c || colors[i % 5]) + '">' +
        '<div class="rm__t">' + esc(n.t) + '</div><div class="rm__d">' + esc(n.d) + '</div></div>';
    }).join('') + '</div>';
    return page(h);
  };

  /* ================= 模拟测验 ================= */
  V.quiz = function () {
    var h = '<div class="card" data-accent="purple"><div style="padding:13px">' +
      '<div class="t-md bold">模拟测验</div>' +
      '<div class="t-xs t-mute" style="margin-top:4px;line-height:1.6">' +
        '从 ' + QUIZ.length + ' 道题中随机抽取 10 题，答完自动判分。历史最佳 ' +
        A.S.quizBest + '% · 已测 ' + A.S.quizRuns + ' 次。</div>' +
      '<div class="row gap-1" style="margin-top:12px">' +
        '<button class="btn btn--primary grow" type="button" data-quiz="start" data-n="10">' +
          icon('i-play', 'icon--sm') + '开始 10 题</button>' +
        '<button class="btn btn--soft" type="button" data-quiz="start" data-n="' + QUIZ.length + '">全量</button>' +
      '</div>' +
    '</div></div>';
    h += '<div id="quizBox" style="margin-top:12px"></div>';
    return page(h);
  };

  V.quizRender = function (items, answers, submitted) {
    var h = '';
    items.forEach(function (it, i) {
      var picked = answers[i];
      h += '<div class="qz__item anim-in" style="--i:' + Math.min(i, 10) + '">' +
        '<div class="qz__q">' + (i + 1) + '. ' + esc(it.q) + '</div>';
      it.o.forEach(function (op, oi) {
        var cls = 'qz__opt';
        if (submitted) {
          if (oi === it.a) cls += ' is-right';
          else if (oi === picked) cls += ' is-wrong';
        }
        h += '<label class="' + cls + '" style="position:relative">' +
          '<input type="radio" name="qz' + i + '" value="' + oi + '"' +
            (picked === oi ? ' checked' : '') + (submitted ? ' disabled' : '') + '>' +
          '<span class="qz__mark"></span><span>' + esc(op) + '</span></label>';
      });
      if (submitted) {
        var right = picked === it.a;
        h += '<div class="qz__fb ' + (right ? 'is-right' : 'is-wrong') + '">' +
          (right ? '✓ 回答正确 · ' : '✗ 正确答案：' + esc(it.o[it.a]) + ' · ') + esc(it.e) + '</div>';
      }
      h += '</div>';
    });
    h += submitted
      ? '<button class="btn btn--soft btn--block" style="margin-top:14px" type="button" data-quiz="again">' +
          icon('i-refresh', 'icon--sm') + '再来一组</button>'
      : '<button class="btn btn--primary btn--block" style="margin-top:14px" type="button" data-quiz="submit">' +
          icon('i-check', 'icon--sm') + '提交答卷</button>';
    return h;
  };

  /* ================= CLI 模拟器 ================= */
  V.cli = function () {
    var h = '<div class="note note--plain" style="margin-bottom:11px;font-size:12.3px">' +
      '离线命令沙盒：内置 VRP / Linux 常用命令的模拟回显。输入 <b>help</b> 查看支持列表，<b>clear</b> 清屏。</div>';
    h += '<div class="cli">' +
      '<div class="cli__out" id="cliOut"></div>' +
      '<div class="cli__in">' +
        '<span class="cli__prompt" id="cliPrompt">&lt;Huawei&gt;</span>' +
        '<input class="cli__field" id="cliIn" autocomplete="off" autocapitalize="off" spellcheck="false" ' +
          'enterkeyhint="send" placeholder="输入命令…">' +
        '<button class="ibtn" type="button" id="cliRun" style="width:34px;height:34px">' +
          icon('i-arrow-right', 'icon--sm') + '</button>' +
      '</div></div>';
    h += '<div class="chips" style="margin-top:10px" id="cliQuick"></div>';
    return page(h);
  };

  /* ================= 搜索结果 ================= */
  V.search = function (q, res) {
    if (!res || !res.n) return page(empty('没有找到「' + q + '」', '试试 vlan / ospf / bgp / k8s / 丢包'));
    var h = '<div class="t-xs t-mute" style="margin-bottom:10px">找到 ' + res.n + ' 条与「' + esc(q) + '」相关的内容</div>';
    if (res.mods.length) {
      h += sec('i-layers', '知识模块 · ' + res.mods.length);
      h += res.mods.slice(0, 40).map(function (m) { return A.modCard(m, q); }).join('');
    }
    if (res.faults.length) {
      h += sec('i-wrench', '排障案例 · ' + res.faults.length);
      h += res.faults.slice(0, 30).map(function (f) { return A.faultCard(f, q); }).join('');
    }
    if (res.iv.length) {
      h += sec('i-briefcase', '面试真题 · ' + res.iv.length);
      h += res.iv.slice(0, 30).map(function (x) { return A.ivCard(x, q); }).join('');
    }
    if (res.dict.length) {
      h += sec('i-terminal', '命令字典 · ' + res.dict.length);
      h += '<div class="list">' + res.dict.slice(0, 30).map(function (r) {
        return '<div class="dict__row">' +
          '<div class="dict__fn">' + icon('i-chev-right', 'icon--xs') + '<span>' + U.hl(r.fn, q) + '</span></div>' +
          CORE.dict.cols.map(function (c) {
            var v = r[c.k];
            if (!v || v === '-') return '';
            return '<div class="dict__kv"><div class="dict__k">' + esc(c.n.split(' ')[0]) + '</div>' +
              '<div class="dict__v" data-cmd="' + esc(v) + '">' + U.hl(v, q) + '</div></div>';
          }).join('') + '</div>';
      }).join('') + '</div>';
    }
    if (res.gloss.length) {
      h += sec('i-help', '术语 · ' + res.gloss.length);
      h += '<div class="list">' + res.gloss.map(function (g) {
        return '<div class="list__item" data-noripple style="align-items:flex-start">' +
          '<span class="list__ico">' + icon('i-help', 'icon--sm') + '</span>' +
          '<span class="grow" style="min-width:0"><span class="list__t mono">' + U.hl(g.t, q) + '</span>' +
          '<span class="list__d" style="white-space:normal">' + U.hl(g.d, q) + '</span></span></div>';
      }).join('') + '</div>';
    }
    return page(h);
  };

  w.NetViews = V;
})(window, document);
