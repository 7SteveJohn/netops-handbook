/* ============================================================
 * NetOps 2.0 · 路由 / 事件 / 启动
 * ============================================================ */
(function (w, d) {
  'use strict';

  var U = w.NetUI, A = w.NetApp, V = w.NetViews;
  var CORE = A.CORE, EXT = A.EXT, QUIZ = A.QUIZ;
  var $ = U.$, $$ = U.$$, esc = U.esc, icon = U.icon;

  var view, scroll, appbar, barName, barSub, barFill, navUse, themeUse, drawer, scrim, edge, fab;
  var drawerCtl;

  /* 路由元数据 */
  var META = {
    learn:    { t: 'NetOps 2.0',  s: '全栈网络运维技能导航', tab: 'learn', accent: 'teal' },
    fault:    { t: '排障字典',     s: '现象 → 根因 → 命令 → 验证', tab: 'fault', accent: 'rose' },
    dict:     { t: '命令字典',     s: '华为 / Cisco / 中兴 / Linux', tab: 'dict', accent: 'blue' },
    iv:       { t: '面试题库',     s: '高频真题与 STAR 话术', tab: 'iv', accent: 'amber' },
    me:       { t: '我的',        s: '进度 · 收藏 · 设置', tab: 'me', accent: 'teal' },
    phase:    { t: '阶段详情',     s: '', tab: 'learn', accent: 'teal' },
    refs:     { t: '速查表',       s: '面试前十分钟', tab: 'learn', accent: 'blue' },
    ref:      { t: '速查表',       s: '', tab: 'learn', accent: 'blue' },
    glossary: { t: '术语词典',     s: '网络与云原生黑话', tab: 'learn', accent: 'purple' },
    roadmap:  { t: '学习路线图',   s: '0-12 个月成长节奏', tab: 'learn', accent: 'teal' },
    quiz:     { t: '模拟测验',     s: '随机抽题 · 自动判分', tab: 'iv', accent: 'purple' },
    cli:      { t: 'CLI 模拟器',   s: '离线命令沙盒', tab: 'learn', accent: 'emerald' },
    fav:      { t: '收藏夹',       s: '', tab: 'me', accent: 'amber' },
    search:   { t: '搜索结果',     s: '', tab: 'learn', accent: 'teal' }
  };

  /* file:// 源下 WebView 会拒绝带 URL 的 history.pushState，
     因此自建导航栈，并通过 NetOpsBack() 接管 Android 物理返回键。 */
  var cur = { r: 'learn', a: null };
  var stack = [];
  var MAX_STACK = 40;
  var filters = { faultCat: '全部', dictCat: '全部', ivCat: '全部' };
  var scrollMem = {};
  var lastQuery = '';

  function isRoot(r) { return !!(META[r] && META[r].tab === r); }

  /* ---------------- 渲染 ---------------- */
  function keyOf(st) { return st.r + ':' + (st.a || ''); }

  function render(st, restore) {
    var html = '';
    switch (st.r) {
      case 'learn':    html = V.learn(); break;
      case 'phase':    html = V.phase(st.a); break;
      case 'fault':    html = V.fault({ cat: filters.faultCat }); break;
      case 'dict':     html = V.dict({ cat: filters.dictCat }); break;
      case 'iv':       html = V.iv({ cat: filters.ivCat }); break;
      case 'me':       html = V.me(); break;
      case 'fav':      html = V.fav(); break;
      case 'refs':     html = V.refs(); break;
      case 'ref':      html = V.ref(st.a); break;
      case 'glossary': html = V.glossary(); break;
      case 'roadmap':  html = V.roadmap(); break;
      case 'quiz':     html = V.quiz(); break;
      case 'cli':      html = V.cli(); break;
      case 'search':   html = V.search(lastQuery, A.searchAll(lastQuery)); break;
      default:         html = V.learn();
    }
    view.innerHTML = html;
    view.classList.remove('is-active');
    void view.offsetWidth;
    view.classList.add('is-active');

    var m = META[st.r] || META.learn;
    var title = m.t, sub = m.s;
    if (st.r === 'phase' && A.PH[st.a]) { title = A.PH[st.a].short; sub = A.PH[st.a].title; }
    if (st.r === 'ref') { EXT.refs.forEach(function (x) { if (x.id === st.a) { title = x.t; sub = x.d; } }); }
    if (st.r === 'search') { sub = '关键词：' + lastQuery; }
    barName.textContent = title;
    barSub.textContent = sub;
    d.documentElement.setAttribute('data-accent', (st.r === 'phase' && A.PH[st.a]) ? A.PH[st.a].color : m.accent);

    /* 标签栏高亮 */
    $$('.tab').forEach(function (b) { b.classList.toggle('is-active', b.dataset.tab === m.tab); });
    /* 导航按钮：一级用菜单，二级用返回 */
    navUse.setAttribute('href', (isRoot(st.r) && !stack.length) ? '#i-menu' : '#i-chev-left');

    updateProgress();

    /* 视图专属初始化 */
    if (st.r === 'cli') initCli();
    if (st.r === 'me') initSettings();

    scroll.scrollTop = restore ? (scrollMem[keyOf(st)] || 0) : 0;
    onScroll();
  }

  /* 仅同步地址栏，不产生历史条目；file:// 源下 replaceState 会抛错，静默跳过。 */
  var canSyncUrl = true;
  function syncHash(st) {
    if (!canSyncUrl) return;
    try { history.replaceState(null, '', '#' + st.r + (st.a ? '/' + st.a : '')); }
    catch (e) { canSyncUrl = false; }
  }

  function go(r, a, replace) {
    if (!META[r]) r = 'learn';
    var st = { r: r, a: a || null };
    if (st.r === cur.r && st.a === cur.a) { render(st, false); return; }
    scrollMem[keyOf(cur)] = scroll.scrollTop;
    if (!replace) {
      stack.push(cur);
      if (stack.length > MAX_STACK) stack.shift();
    }
    cur = st;
    syncHash(st);
    render(st, false);
  }
  A.go = go;

  function goTab(tab) {
    if (cur.r === tab) { scroll.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    scrollMem[keyOf(cur)] = scroll.scrollTop;
    /* 切主标签：回到根层级，清空返回栈，避免栈无限膨胀 */
    stack.length = 0;
    cur = { r: tab, a: null };
    syncHash(cur);
    render(cur, true);
    U.buzz(6);
  }

  /* 返回一层。true = 已消费；false = 已在根，交还给宿主（Android 可退出） */
  function back() {
    if (U.sheet.isOpen()) { U.sheet.close(); return true; }
    if (drawerCtl && drawerCtl.isOpen && drawerCtl.isOpen()) { drawerCtl.close(); return true; }
    if (!stack.length) {
      if (!isRoot(cur.r)) { goTab(META[cur.r] ? META[cur.r].tab : 'learn'); return true; }
      return false;
    }
    scrollMem[keyOf(cur)] = scroll.scrollTop;
    cur = stack.pop();
    syncHash(cur);
    render(cur, true);
    return true;
  }
  A.back = back;
  /* Android 物理返回键桥接：返回 true 表示网页已处理，false 表示可退出应用 */
  w.NetOpsBack = function () { try { return back(); } catch (e) { return false; } };
  /* 浏览器/桌面：手势或 Alt+← 返回时也走同一套栈 */
  w.addEventListener('popstate', function () { back(); });
  d.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' || (e.altKey && e.key === 'ArrowLeft')) { if (back()) e.preventDefault(); }
  });

  /* ---------------- 进度条 ---------------- */
  function updateProgress() {
    var all = A.doneAll(), pct = Math.round(all / A.TOTAL * 100);
    barFill.style.width = pct + '%';
    var db = $('#drawerBar'), dp = $('#drawerPct');
    if (db) db.style.width = pct + '%';
    if (dp) dp.textContent = pct + '%';
  }

  /* ---------------- 抽屉目录 ---------------- */
  function buildDrawer() {
    var body = $('#drawerBody'), h = '';
    CORE.phases.forEach(function (p) {
      var dn = A.countDone(p.modules);
      h += '<div class="tree__group" data-accent="' + p.color + '" data-grp="' + p.id + '">' +
        '<button class="tree__head" type="button" data-treehead>' +
          '<span class="tree__dot"></span>' +
          '<span class="tree__name">' + esc(p.short) + '</span>' +
          '<span class="tree__cnt">' + dn + '/' + p.modules.length + '</span>' +
          '<svg class="icon icon--xs card__arrow" aria-hidden="true"><use href="#i-chev-down"/></svg>' +
        '</button>' +
        '<div class="tree__items"><div>' +
          p.modules.map(function (m) {
            return '<button class="tree__item' + (A.isDone(m.id) ? ' is-done' : '') + '" type="button" ' +
              'data-jump="' + m.id + '" data-pid="' + p.id + '">' +
              '<svg class="icon icon--xs" aria-hidden="true"><use href="#' +
                (A.isDone(m.id) ? 'i-check-circle' : 'i-circle') + '"/></svg>' +
              '<span class="ellipsis">' + esc(A.cleanTitle(m.t)) + '</span></button>';
          }).join('') +
        '</div></div></div>';
    });
    h += '<div class="tree__group" data-accent="rose"><button class="tree__head" type="button" data-go="fault">' +
      '<span class="tree__dot"></span><span class="tree__name">排障字典</span>' +
      '<span class="tree__cnt">' + CORE.faults.length + '</span></button></div>';
    h += '<div class="tree__group" data-accent="blue"><button class="tree__head" type="button" data-go="dict">' +
      '<span class="tree__dot"></span><span class="tree__name">命令字典</span>' +
      '<span class="tree__cnt">' + CORE.dict.rows.length + '</span></button></div>';
    h += '<div class="tree__group" data-accent="amber"><button class="tree__head" type="button" data-go="iv">' +
      '<span class="tree__dot"></span><span class="tree__name">面试题库</span>' +
      '<span class="tree__cnt">' + CORE.interview.length + '</span></button></div>';
    h += '<div class="tree__group" data-accent="purple"><button class="tree__head" type="button" data-go="refs">' +
      '<span class="tree__dot"></span><span class="tree__name">速查表</span>' +
      '<span class="tree__cnt">' + EXT.refs.length + '</span></button></div>';
    body.innerHTML = h;
  }

  /* ---------------- 全局委托 ---------------- */
  function bindGlobal() {
    d.addEventListener('click', function (e) {
      var t = e.target;

      /* 卡片折叠 */
      var head = t.closest('[data-toggle]');
      if (head) {
        var cardEl = head.closest('.card');
        var inner = cardEl.querySelector('[data-lazy]');
        if (inner && inner.dataset.lazy === '1') {
          var item = A.BY_ID[cardEl.dataset.id];
          if (item) inner.innerHTML = A.bodyOf(item);
          inner.dataset.lazy = '0';
        }
        cardEl.classList.toggle('is-open');
        if (cardEl.classList.contains('is-open')) {
          setTimeout(function () {
            var r = cardEl.getBoundingClientRect(), sr = scroll.getBoundingClientRect();
            if (r.top < sr.top + 8) scroll.scrollTop += r.top - sr.top - 8;
          }, 220);
        }
        return;
      }

      /* 打卡 */
      var dn = t.closest('[data-done]');
      if (dn) {
        var id = dn.getAttribute('data-done');
        if (A.S.done[id]) { delete A.S.done[id]; }
        else { A.S.done[id] = 1; A.touchStreak(); U.buzz(12); }
        A.saveDone();
        var on = !!A.S.done[id];
        dn.className = 'btn ' + (on ? 'btn--ok' : 'btn--soft') + ' btn--sm grow';
        dn.innerHTML = icon(on ? 'i-check-circle' : 'i-circle', 'icon--sm') +
          (on ? '已掌握' : '标记掌握');
        var cd = dn.closest('.card'); if (cd) cd.classList.toggle('is-done', on);
        U.toast(on ? '已标记掌握' : '已取消标记', on ? 'ok' : null);
        updateProgress();
        refreshTreeItem(id, on);
        return;
      }

      /* 收藏 */
      var fv = t.closest('[data-fav]');
      if (fv) {
        var fid = fv.getAttribute('data-fav');
        if (A.S.fav[fid]) delete A.S.fav[fid]; else { A.S.fav[fid] = 1; U.buzz(10); }
        A.saveFav();
        var f = !!A.S.fav[fid];
        fv.style.color = f ? 'var(--warn)' : '';
        fv.innerHTML = '<svg class="icon icon--sm' + (f ? ' icon--fill' : '') +
          '" aria-hidden="true"><use href="#i-star"/></svg>' + (f ? '已收藏' : '收藏');
        U.toast(f ? '已加入收藏' : '已取消收藏', f ? 'ok' : null);
        return;
      }

      /* 路由跳转 */
      var g = t.closest('[data-go]');
      if (g) {
        var r = g.getAttribute('data-go'), a = g.getAttribute('data-arg');
        var focus = g.getAttribute('data-focus');
        if (drawerCtl.isOpen()) drawerCtl.close();
        go(r, a);
        if (focus) focusCard(focus);
        return;
      }

      /* 抽屉树展开 */
      var th = t.closest('[data-treehead]');
      if (th) { th.parentNode.classList.toggle('is-open'); return; }

      /* 抽屉跳转到模块 */
      var jp = t.closest('[data-jump]');
      if (jp) {
        drawerCtl.close();
        go('phase', jp.getAttribute('data-pid'));
        focusCard(jp.getAttribute('data-jump'));
        return;
      }

      /* 分类筹码 */
      var ch = t.closest('[data-chip]');
      if (ch) {
        var grp = ch.closest('[data-chipgroup]').getAttribute('data-chipgroup');
        filters[grp] = ch.getAttribute('data-chip');
        var sp = scroll.scrollTop;
        render(cur, false);
        scroll.scrollTop = Math.min(sp, 120);
        return;
      }

      /* 字典命令复制 */
      var cm = t.closest('[data-cmd]');
      if (cm) {
        var txt = cm.getAttribute('data-cmd');
        if (txt && txt !== '-') U.copy(txt).then(function (ok) {
          U.buzz(10); U.toast(ok ? '已复制：' + txt.slice(0, 24) : '复制失败', ok ? 'ok' : 'danger');
        });
        return;
      }

      /* 导出 */
      var ex = t.closest('[data-export]');
      if (ex) { exportAll(); return; }
      var mp = t.closest('[data-mdphase]');
      if (mp) { exportPhase(mp.getAttribute('data-mdphase')); return; }

      /* 重置 */
      if (t.closest('[data-reset]')) { confirmReset(); return; }

      /* 动画开关 */
      var tm = t.closest('[data-toggle-motion]');
      if (tm) {
        var v = U.motion.get() === 'on' ? 'off' : 'on';
        U.motion.set(v);
        $('#swMotion').classList.toggle('is-on', v === 'on');
        U.toast(v === 'on' ? '动画已开启' : '动画已关闭', 'ok');
        return;
      }

      /* 主题分段 */
      var ts = t.closest('[data-theme]');
      if (ts) { setTheme(ts.getAttribute('data-theme')); return; }

      /* 测验 */
      var qz = t.closest('[data-quiz]');
      if (qz) { handleQuiz(qz); return; }
    });
  }

  function refreshTreeItem(id, on) {
    var el = $('[data-jump="' + id + '"]');
    if (!el) return;
    el.classList.toggle('is-done', on);
    var use = el.querySelector('use');
    if (use) use.setAttribute('href', on ? '#i-check-circle' : '#i-circle');
  }

  function focusCard(id) {
    setTimeout(function () {
      var el = view.querySelector('.card[data-id="' + id + '"]');
      if (!el) return;
      var head = el.querySelector('[data-toggle]');
      if (head && !el.classList.contains('is-open')) head.click();
      setTimeout(function () {
        var r = el.getBoundingClientRect(), sr = scroll.getBoundingClientRect();
        scroll.scrollTop += r.top - sr.top - 10;
        el.classList.add('anim-pop');
        setTimeout(function () { el.classList.remove('anim-pop'); }, 700);
      }, 60);
    }, 60);
  }

  /* ---------------- 主题 ---------------- */
  function setTheme(t) {
    U.theme.set(t);
    syncThemeIcon();
    if (cur.r === 'me') initSettings();
    U.toast('主题：' + ({ auto: '跟随系统', light: '浅色', dark: '深色' }[t]), 'ok');
  }
  function syncThemeIcon() {
    themeUse.setAttribute('href', U.theme.isDark() ? '#i-moon' : '#i-sun');
  }
  function initSettings() {
    var seg = $('#themeSeg'), lbl = $('#themeLbl');
    if (!seg) return;
    var curT = U.theme.get();
    var names = { auto: '自动', light: '浅色', dark: '深色' };
    seg.innerHTML = ['auto', 'light', 'dark'].map(function (k) {
      return '<button class="chip' + (curT === k ? ' is-active' : '') + '" type="button" data-theme="' + k + '" ' +
        'style="height:28px;padding:0 10px;font-size:11.5px">' + names[k] + '</button>';
    }).join('');
    if (lbl) lbl.textContent = curT === 'auto' ? '跟随系统（当前' + (U.theme.isDark() ? '深色' : '浅色') + '）' : names[curT];
  }

  /* ---------------- 搜索 ---------------- */
  function bindSearch() {
    var wrap = $('#searchWrap'), input = $('#q');
    $('#btnSearch').addEventListener('click', function () {
      var open = wrap.classList.toggle('is-open');
      $('#btnSearch').classList.toggle('is-on', open);
      if (open) setTimeout(function () { input.focus(); }, 220);
      else { input.value = ''; if (cur.r === 'search') back(); }
    });
    $('#btnClearQ').addEventListener('click', function () {
      input.value = ''; input.focus();
      if (cur.r === 'search') back();
    });
    var run = U.debounce(function () {
      var q = input.value.trim();
      if (q.length < 1) { if (cur.r === 'search') back(); return; }
      lastQuery = q;
      if (cur.r === 'search') { render(cur, false); }
      else go('search', null);
    }, 260);
    input.addEventListener('input', run);
    input.addEventListener('keydown', function (e) { if (e.key === 'Enter') { input.blur(); run(); } });
  }

  /* ---------------- 导出 ---------------- */
  function mdOfModule(m) {
    var s = '### ' + A.cleanTitle(m.t) + '\n\n';
    if (m.y) s += m.y + '\n\n';
    if (m.c) s += '```\n' + m.c + '\n```\n\n';
    if (m.o) s += '回显：\n```\n' + m.o + '\n```\n\n';
    if (m.j && m.j.length) s += '**关键解读**\n' + m.j.map(function (x) { return '- ' + x; }).join('\n') + '\n\n';
    if (m.u && m.u.length) s += '**应用场景**\n' + m.u.map(function (x) { return '- ' + x; }).join('\n') + '\n\n';
    if (m.w && m.w.length) s += '**避坑**\n' + m.w.map(function (x) { return '- ' + x; }).join('\n') + '\n\n';
    if (m.v) s += '**验证**：' + m.v + '\n\n';
    if (m.l && m.l.length) s += '**实验步骤**\n' + m.l.map(function (x, i) { return (i + 1) + '. ' + x; }).join('\n') + '\n\n';
    return s;
  }
  function exportPhase(pid) {
    var p = A.PH[pid]; if (!p) return;
    var s = '# ' + p.title + '\n\n> ' + p.desc + '\n\n';
    p.modules.forEach(function (m) { s += mdOfModule(m); });
    U.download('NetOps-' + p.short + '.md', s);
  }
  function exportAll() {
    var s = '# NetOps 2.0 · 全栈网络运维技能导航\n\n> 离线知识库导出 · ' + new Date().toLocaleString('zh-CN') + '\n\n';
    CORE.phases.forEach(function (p) {
      s += '\n## ' + p.title + '\n\n' + p.desc + '\n\n';
      p.modules.forEach(function (m) { s += mdOfModule(m); });
    });
    s += '\n## 排障字典\n\n';
    CORE.faults.forEach(function (f) {
      s += '### ' + f.t + '\n\n- 现象：' + (f.sym || '') + '\n- 根因：' + (f.cause || '') + '\n';
      if (f.c) s += '\n```\n' + f.c + '\n```\n';
      if (f.v) s += '\n验证：' + f.v + '\n';
      s += '\n';
    });
    s += '\n## 面试题库\n\n';
    CORE.interview.forEach(function (q) {
      s += '### ' + q.t + '\n\n- 考察点：' + (q.point || '') + '\n- STAR：' + (q.star || '') + '\n\n' + (q.answer || '') + '\n\n';
    });
    s += '\n## 命令对照字典\n\n| 功能 | ' + CORE.dict.cols.map(function (c) { return c.n; }).join(' | ') + ' |\n';
    s += '| --- |' + CORE.dict.cols.map(function () { return ' --- |'; }).join('') + '\n';
    CORE.dict.rows.forEach(function (r) {
      s += '| ' + r.fn + ' | ' + CORE.dict.cols.map(function (c) { return (r[c.k] || '-'); }).join(' | ') + ' |\n';
    });
    U.download('NetOps2-全量知识库.md', s);
  }

  function confirmReset() {
    U.sheet.open({
      title: '清空学习记录',
      body: '<div class="note note--danger">此操作会清除全部打卡进度、收藏与测验成绩，且无法撤销。知识库内容不受影响。</div>',
      foot: '<div class="row gap-1"><button class="btn btn--soft grow" type="button" data-sheet-cancel>取消</button>' +
            '<button class="btn btn--danger grow" type="button" data-sheet-ok>确认清空</button></div>',
      onMount: function (el) {
        el.querySelector('[data-sheet-cancel]').onclick = function () { U.sheet.close(); };
        el.querySelector('[data-sheet-ok]').onclick = function () {
          A.S.done = {}; A.S.fav = {}; A.S.quizBest = 0; A.S.quizRuns = 0;
          A.S.streak = { n: 0, last: '' };
          U.store.set('done', {}); U.store.set('fav', {});
          U.store.set('quizBest', 0); U.store.set('quizRuns', 0);
          U.store.set('streak', A.S.streak);
          U.sheet.close();
          buildDrawer();
          render(cur, false);
          U.toast('学习记录已清空', 'ok');
        };
      }
    });
  }

  /* ---------------- 测验 ---------------- */
  var qzState = null;
  function handleQuiz(btn) {
    var act = btn.getAttribute('data-quiz');
    var box = $('#quizBox');
    if (act === 'start' || act === 'again') {
      var n = parseInt(btn.getAttribute('data-n') || (qzState ? qzState.n : 10), 10);
      var pool = QUIZ.slice();
      for (var i = pool.length - 1; i > 0; i--) {
        var j = Math.floor(Math.random() * (i + 1)), t = pool[i]; pool[i] = pool[j]; pool[j] = t;
      }
      qzState = { n: n, items: pool.slice(0, Math.min(n, pool.length)), answers: [], sub: false };
      box.innerHTML = V.quizRender(qzState.items, qzState.answers, false);
      bindQuizInputs(box);
      box.scrollIntoView({ behavior: 'smooth', block: 'start' });
      return;
    }
    if (act === 'submit' && qzState) {
      var right = 0;
      qzState.items.forEach(function (it, i) { if (qzState.answers[i] === it.a) right++; });
      var pct = Math.round(right / qzState.items.length * 100);
      qzState.sub = true;
      box.innerHTML = '<div class="card" data-accent="' + (pct >= 80 ? 'emerald' : pct >= 60 ? 'amber' : 'rose') + '">' +
        '<div style="padding:14px;display:flex;align-items:center;gap:14px">' +
        '<div class="ring" style="--p:' + pct + ';width:76px;height:76px"><div class="ring__val">' +
        '<div class="ring__num" style="font-size:19px">' + pct + '</div><div class="ring__lbl">分</div></div></div>' +
        '<div><div class="t-md bold">' + (pct >= 80 ? '优秀，稳了' : pct >= 60 ? '及格，还得练' : '基础不牢，回去看模块') + '</div>' +
        '<div class="t-xs t-mute" style="margin-top:3px">答对 ' + right + ' / ' + qzState.items.length + ' 题</div></div>' +
        '</div></div>' + V.quizRender(qzState.items, qzState.answers, true);
      A.S.quizRuns++; U.store.set('quizRuns', A.S.quizRuns);
      if (pct > A.S.quizBest) { A.S.quizBest = pct; U.store.set('quizBest', pct); U.toast('刷新最佳成绩 ' + pct + '%', 'ok'); }
      U.buzz(18);
      box.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }
  function bindQuizInputs(box) {
    box.addEventListener('change', function (e) {
      var inp = e.target;
      if (inp.type !== 'radio' || !qzState) return;
      var idx = parseInt(inp.name.replace('qz', ''), 10);
      qzState.answers[idx] = parseInt(inp.value, 10);
    });
  }

  /* ---------------- CLI 模拟器 ---------------- */
  var CLI_DB = null;
  function buildCliDb() {
    if (CLI_DB) return CLI_DB;
    CLI_DB = [];
    CORE.dict.rows.forEach(function (r) {
      ['hw', 'cs', 'zte', 'lx'].forEach(function (k) {
        var c = r[k];
        if (c && c !== '-') CLI_DB.push({ cmd: c.toLowerCase(), raw: c, fn: r.fn, plat: k });
      });
    });
    A.MODS.forEach(function (m) {
      if (m.c && m.o) CLI_DB.push({ cmd: String(m.c).split('\n')[0].replace(/^[<\[][^>\]]*[>\]]\s*/, '').toLowerCase(), raw: m.c, fn: A.cleanTitle(m.t), out: m.o });
    });
    return CLI_DB;
  }
  var cliHist = [], cliPos = -1, cliMode = 'hw';
  function initCli() {
    var out = $('#cliOut'), inp = $('#cliIn'), btn = $('#cliRun'), quick = $('#cliQuick');
    if (!out) return;
    buildCliDb();
    cliPrint(out, 'NetOps CLI 模拟器 v2.0 · 完全离线', 'info');
    cliPrint(out, '输入 help 查看可用命令，mode hw|cs|zte|lx 切换平台。', 'dim');
    quick.innerHTML = ['help', 'display version', 'display ip interface brief', 'display vlan',
      'display ospf peer', 'ip addr', 'kubectl get pods', 'clear']
      .map(function (c) { return '<button class="chip" type="button" data-cli="' + esc(c) + '">' + esc(c) + '</button>'; }).join('');
    quick.onclick = function (e) {
      var b = e.target.closest('[data-cli]'); if (!b) return;
      inp.value = b.getAttribute('data-cli'); runCli();
    };
    btn.onclick = runCli;
    inp.onkeydown = function (e) {
      if (e.key === 'Enter') { runCli(); return; }
      if (e.key === 'ArrowUp') { if (cliPos > 0) inp.value = cliHist[--cliPos]; e.preventDefault(); }
      if (e.key === 'ArrowDown') { if (cliPos < cliHist.length - 1) inp.value = cliHist[++cliPos]; e.preventDefault(); }
    };
    function runCli() {
      var v = inp.value.trim(); if (!v) return;
      cliHist.push(v); cliPos = cliHist.length;
      inp.value = '';
      cliPrint(out, promptOf() + ' ' + v, 'cmd');
      execCli(out, v);
      out.scrollTop = out.scrollHeight;
    }
  }
  function promptOf() {
    return { hw: '<Huawei>', cs: 'Switch#', zte: 'ZXR10#', lx: 'root@netops:~#' }[cliMode];
  }
  function cliPrint(out, text, cls) {
    var div = d.createElement('div');
    div.className = 'cli__line--' + (cls || 'dim');
    div.textContent = text;
    out.appendChild(div);
  }
  function execCli(out, v) {
    var low = v.toLowerCase();
    if (low === 'clear' || low === 'cls') { out.innerHTML = ''; return; }
    if (low === 'help' || low === '?') {
      cliPrint(out, '可用指令：', 'info');
      cliPrint(out, '  help                 显示帮助', 'dim');
      cliPrint(out, '  clear                清屏', 'dim');
      cliPrint(out, '  mode hw|cs|zte|lx    切换平台（当前 ' + cliMode + '）', 'dim');
      cliPrint(out, '  find <关键词>        搜索命令字典', 'dim');
      cliPrint(out, '  其余输入会在 ' + buildCliDb().length + ' 条真实命令库中模糊匹配并回显示例。', 'dim');
      return;
    }
    if (low.indexOf('mode ') === 0) {
      var m = low.slice(5).trim();
      if (['hw', 'cs', 'zte', 'lx'].indexOf(m) < 0) { cliPrint(out, '不支持的平台：' + m, 'err'); return; }
      cliMode = m; $('#cliPrompt').textContent = promptOf();
      cliPrint(out, '已切换到 ' + m + ' 平台', 'ok');
      return;
    }
    if (low.indexOf('find ') === 0) {
      var kw = low.slice(5).trim();
      var hits = buildCliDb().filter(function (x) { return x.cmd.indexOf(kw) >= 0 || x.fn.toLowerCase().indexOf(kw) >= 0; }).slice(0, 12);
      if (!hits.length) { cliPrint(out, '未找到与「' + kw + '」相关的命令', 'err'); return; }
      hits.forEach(function (x) { cliPrint(out, '  ' + x.raw.split('\n')[0] + '   # ' + x.fn, 'ok'); });
      return;
    }
    var db = buildCliDb();
    var exact = null, fuzzy = [];
    db.forEach(function (x) {
      if (x.cmd === low) exact = x;
      else if (x.cmd.indexOf(low) === 0 || low.indexOf(x.cmd) === 0) fuzzy.push(x);
    });
    var hit = exact || fuzzy[0];
    if (!hit) {
      cliPrint(out, 'Error: Unrecognized command found at \'^\' position.', 'err');
      var near = db.filter(function (x) { return x.cmd.indexOf(low.split(' ')[0]) >= 0; }).slice(0, 3);
      if (near.length) { cliPrint(out, '你是不是想输入：', 'dim'); near.forEach(function (x) { cliPrint(out, '  ' + x.raw.split('\n')[0], 'info'); }); }
      return;
    }
    if (hit.out) { hit.out.split('\n').forEach(function (l) { cliPrint(out, l, 'ok'); }); }
    else { cliPrint(out, '[模拟] ' + hit.fn + ' —— 该命令在真机上执行后会返回对应配置/状态信息。', 'info'); }
  }

  /* ---------------- 滚动联动 ---------------- */
  var lastTop = 0;
  function onScroll() {
    var t = scroll.scrollTop;
    appbar.classList.toggle('is-scrolled', t > 4);
    fab.classList.toggle('is-show', t > 620);
    lastTop = t;
  }

  /* ---------------- 启动 ---------------- */
  function boot() {
    view = $('#view'); scroll = $('#scroll'); appbar = $('#appbar');
    barName = $('#barName'); barSub = $('#barSub'); barFill = $('#barFill');
    navUse = $('#navUse'); themeUse = $('#themeUse');
    drawer = $('#drawer'); scrim = $('#scrim'); edge = $('#edge'); fab = $('#fab');

    U.theme.apply(); U.motion.apply(); syncThemeIcon();
    try {
      if (w.matchMedia) w.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
        if (U.theme.get() === 'auto') { U.theme.apply(); syncThemeIcon(); }
      });
    } catch (e) {}

    drawerCtl = U.bindDrawer(drawer, scrim, edge);
    buildDrawer();
    bindGlobal();
    bindSearch();

    $('#btnNav').addEventListener('click', function () {
      if (isRoot(cur.r) && !stack.length) drawerCtl.open();
      else back();
    });
    $('#btnTheme').addEventListener('click', function () {
      var order = ['auto', 'light', 'dark'];
      var next = order[(order.indexOf(U.theme.get()) + 1) % 3];
      setTheme(next);
    });
    $$('.tab').forEach(function (b) {
      b.addEventListener('click', function () { goTab(b.dataset.tab); });
    });
    fab.addEventListener('click', function () { scroll.scrollTo({ top: 0, behavior: 'smooth' }); });
    scroll.addEventListener('scroll', function () { U.raf(onScroll); }, { passive: true });

    /* 初始路由：支持 #phase/p2 形式的深链，非根路由自动垫一层根页便于返回 */
    var init = { r: 'learn', a: null };
    var hash = (location.hash || '').replace(/^#/, '').split('/');
    if (hash[0] && META[hash[0]]) init = { r: hash[0], a: hash[1] || null };
    stack.length = 0;
    if (!isRoot(init.r)) stack.push({ r: META[init.r].tab, a: null });
    cur = init;
    syncHash(init);
    render(init, false);

    d.body.classList.add('ready');
    if (w.NetBridge && w.NetBridge.ready) { try { w.NetBridge.ready(); } catch (e) {} }
  }

  if (d.readyState === 'loading') d.addEventListener('DOMContentLoaded', boot);
  else boot();
})(window, document);
