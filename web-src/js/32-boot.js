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
    learn:    { t: 'NetOps 2.0',  s: '网络学习辅助手册', tab: 'learn', accent: 'teal' },
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
    positionTabPill();
    /* 导航按钮：一级用菜单，二级用返回 */
    navUse.setAttribute('href', (isRoot(st.r) && !stack.length) ? '#i-menu' : '#i-chev-left');

    updateProgress();

    /* 视图专属初始化 */
    if (st.r === 'cli') initCli();
    if (st.r === 'me') initSettings();
    if (st.r === 'learn') showSwipeTipOnce();

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

  function goTab(tab, restorePos) {
    if (cur.r === tab) { scroll.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    /* 切主标签前关闭所有 sheet（避免 wpSheet / glassSheet DOM 被 V.*() innerHTML 覆盖后
       留下半开/错位状态——用户 2026-08-12 "我的页快速下滑触发 bug" 反馈的根因）。
       注意：不关闭 drawer（侧滑菜单）。 */
    try { if (U.sheet.isOpen && U.sheet.isOpen()) U.sheet.close(); } catch (e) {}
    closeAnyCustomSheet();
    scrollMem[keyOf(cur)] = scroll.scrollTop;
    /* 切主标签：回到根层级，清空返回栈，避免栈无限膨胀 */
    stack.length = 0;
    cur = { r: tab, a: null };
    syncHash(cur);
    /* 直接点底部导航栏：该专栏回到顶部（符合直觉），不恢复历史滚动位置；
       历史位置由返回键（back）按需恢复，避免“一切换就自动下滑” */
    render(cur, !!restorePos);
    U.buzz(6);
  }

  /* 返回一层。true = 已消费；false = 已在根，交还给宿主（Android 可退出） */
  function back() {
    if (U.sheet.isOpen()) { U.sheet.close(); return true; }
    if (closeAnyCustomSheet()) { return true; } /* 关闭壁纸/玻璃自定义弹窗 */
    if (drawerCtl && drawerCtl.isOpen && drawerCtl.isOpen()) { drawerCtl.close(); return true; }
    if (!stack.length) {
      if (!isRoot(cur.r)) { goTab(META[cur.r] ? META[cur.r].tab : 'learn', true); return true; }
      return false;
    }
    scrollMem[keyOf(cur)] = scroll.scrollTop;
    cur = stack.pop();
    syncHash(cur);
    render(cur, true);
    return true;
  }
  A.back = back;
  /* 暴露给 31-views.js 的 V.me() 调用（跨 IIFE 作用域） */
  A.getTabbarMode = getTabbarMode;
  /* ============================================================
     2026-08-12 19:30（按用户修复方案）：统一"返回 + 退出确认"逻辑
     问题根源：原生返回键(w.NetOpsBack)与网页手势的退出逻辑不统一——
     之前 w.NetOpsBack 只调 back()，首页时返回 false，Android 原生收到 false
     直接 finish() 退出，绕过网页的 Toast + 2 秒二次确认。
     修复：handleBackOrExit() 统一处理 —— 首次返回 true 拦截原生退出并弹
     Toast，2 秒内再次才返回 false 允许原生关闭。
     2026-09-22 再收一层：物理返回键不再走这里（改由原生壳自己双按，见下方 NetOpsBack），
     handleBackOrExit 只服务网页内手势退出，两套确认不再叠加。
     ============================================================ */
  var lastExitTap = 0;
  /* 切后台/重新获得焦点时重置退出时间戳（Home→重进不会误判二次点击） */
  d.addEventListener('visibilitychange', function () {
    if (d.visibilityState === 'visible') lastExitTap = 0;
  });
  w.addEventListener('focus', function () { lastExitTap = 0; });

  function handleBackOrExit() {
    /* 尝试响应内部返回（关闭弹窗、抽屉、展开卡片或返回上一级页面） */
    var handled = back();
    if (handled) return true; /* 已在应用内完成返回，不退出 */
    /* 处于首页且无二级菜单可退时，执行 2 秒二次确认逻辑 */
    var now = Date.now();
    if (now - lastExitTap < 2000) {
      lastExitTap = 0;
      if (w.NetBridge && w.NetBridge.exitApp) {
        try { w.NetBridge.exitApp(); } catch (e) {}
      }
      return false; /* 2 秒内再次触发，允许原生壳关闭应用 */
    } else {
      lastExitTap = now;
      U.toast('再按一次退出应用', null);
      return true; /* 首次触发：拦截返回事件，显示 Toast，阻止原生壳直接退出 */
    }
  }
  /* Android 物理返回键桥接：只回答「网页这一层消费了没有」（关抽屉 / 关 Sheet / 回退一页）。
     退出确认收回原生侧（MainActivity 的「再按一次退出」）：同一键不会再叠两个 Toast，
     网页逻辑卡死时返回键也仍然能退出。手势路径仍用下面的 handleBackOrExit 做网页侧确认。 */
  w.NetOpsBack = function () {
    try { return back(); } catch (e) { return false; }
  };
  /* 浏览器/桌面：手势或 Alt+← 返回时也走同一套栈 */
  w.addEventListener('popstate', function () { back(); });
  d.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' || (e.altKey && e.key === 'ArrowLeft')) { if (back()) e.preventDefault(); }
  });
  /* 暴露给手势：网页内部左滑/返回键统一走 handleBackOrExit */
  A.handleBackOrExit = handleBackOrExit;

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
        /* 宽屏两栏时展开的卡要占满整行，否则终端与拓扑被压进半列 */
        var wrap = cardEl.parentElement;
        if (wrap && wrap.classList.contains('anim-in')) {
          wrap.classList.toggle('is-wide', cardEl.classList.contains('is-open'));
        }
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

      /* 错题本/答题结果里的「回看这张卡」锚点 */
      var jc = t.closest('[data-jumpcard]');
      if (jc) { jumpToCard(jc.getAttribute('data-jumpcard')); return; }

      /* 面试目标岗位：只影响排序与标注，不删题 */
      var fv = t.closest('[data-ivfocus]');
      if (fv) {
        A.S.jobFocus = fv.getAttribute('data-ivfocus') || 'any';
        if (!U.store.set('jobFocus', A.S.jobFocus)) { /* 写不进去时 store 已经统一提示过 */ }
        var spv = scroll.scrollTop;
        render(cur, false);
        scroll.scrollTop = Math.min(spv, 120);
        U.toast(A.S.jobFocus === 'any' ? '已取消岗位偏好，按默认顺序展示' : '目标岗位：' + A.S.jobFocus, 'ok');
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

      /* 数据备份导出/导入(优化5) */
      if (t.closest('[data-exportdata]')) { exportData(); return; }
      if (t.closest('[data-importdata]')) {
        var imp = $('#impFile');
        if (imp) imp.click();
        return;
      }

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

  /* 跳到讲过它的卡片：知识模块回阶段页，排障/面试卡回各自列表页 */
  function jumpToCard(id) {
    var m = A.BY_ID[id];
    if (!m) { U.toast('找不到对应卡片：' + id, null); return; }
    if (drawerCtl.isOpen()) drawerCtl.close();
    if (/^G\d/.test(id)) go('fault');
    else if (/^IVQ\d/.test(id)) go('iv');
    else go('phase', m.pid);
    focusCard(id);
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
    /* 玻璃参数按主题分两套（暗色要压暗背景层、下限也不同），换主题必须重算 */
    applyGlass(getGlass() || glassDefaults);
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

    /* 壁纸设置 */
    initWallpaper();
    /* 玻璃效果设置 */
    initGlass();
    /* 悬浮底栏设置 */
    initTabbarMode();
    /* 动画速度设置 */
    initSpeed();
    /* 数据备份导入 file input(优化5) */
    var imp = $('#impFile');
    if (imp && !imp.__bound) {
      imp.__bound = true;
      imp.addEventListener('change', function () {
        if (imp.files && imp.files[0]) importData(imp.files[0]);
        imp.value = '';
      });
    }
    /* 高光跟随手指（液态玻璃动态折射）——降级 / 省电动效偏好下不启用 */
    if (!deviceDegraded) initGlassLight();
    initSquish();   /* 自带降级判断与重复绑定保护 */
  }

  /* ---------------- 按压形变（"按住能拉扯"的活玻璃） ----------------
     位移跟手、沿拖动方向拉伸（另一轴反向压缩，看着才像软体），松手用弹簧过冲回位。
     只碰 transform；浏览器一旦接管滚动会发 pointercancel，我们顺势回位，绝不和滚动抢。
     幅度/时长是手感参数，只能真机上手调，所以做成可实时改的 store 值：
     长按「全局质感」弹窗标题 0.6 秒开调参面板（隐藏入口，不占正式界面）。 */
  var SQUISH_SEL = '.tab, .chip, .btn, .ibtn, .list__item, .phase, .stat, .card__head, #fab';
  var SQT_DEF = { max: 7, press: 0.975, stretch: 0.06, squash: 0.035, back: 460 };
  function getSqt() {
    var v = null;
    try { v = U.store.get('sqtune', null); } catch (e) { v = null; }
    var out = {};
    for (var k in SQT_DEF) out[k] = SQT_DEF[k];
    if (v && typeof v === 'object') {
      for (var k2 in SQT_DEF) { if (typeof v[k2] === 'number' && isFinite(v[k2])) out[k2] = v[k2]; }
    }
    /* 夹在可用范围内：备份导入或手滑不该把形变调成看不见，也不该调成卡通 */
    out.max = Math.max(0, Math.min(20, out.max));
    out.press = Math.max(0.85, Math.min(1, out.press));
    out.stretch = Math.max(0, Math.min(0.2, out.stretch));
    out.squash = Math.max(0, Math.min(0.2, out.squash));
    out.back = Math.max(120, Math.min(1200, out.back));
    return out;
  }
  var SQT = getSqt();
  function applySqt() {
    SQT = getSqt();
    document.documentElement.style.setProperty('--sq-back', (SQT.back / 1000).toFixed(2) + 's');
    return SQT;
  }
  function squishBlocked() {
    try {
      if (U.motion.get() !== 'on') return true;
      if (deviceDegraded || batteryDegraded) return true;
      if (w.matchMedia && w.matchMedia('(prefers-reduced-motion: reduce)').matches) return true;
    } catch (e) { /* 探测失败就不做形变，别拖慢交互 */ return true; }
    return false;
  }
  function initSquish() {
    if (w.__netopsSquish) return;
    w.__netopsSquish = true;
    applySqt();
    var el = null, id = -1, x0 = 0, y0 = 0, nx = 0, ny = 0, timer = 0;
    function paint() {
      if (!el) return;
      var s = Math.min(1, Math.sqrt(nx * nx + ny * ny) / Math.max(1, SQT.max));
      var a = SQT.press + s * SQT.stretch, b = SQT.press - s * SQT.squash;
      if (Math.abs(ny) > Math.abs(nx)) { var t = a; a = b; b = t; }   /* 拉伸轴跟着拖动方向走 */
      el.style.setProperty('--sqx', nx.toFixed(2) + 'px');
      el.style.setProperty('--sqy', ny.toFixed(2) + 'px');
      el.style.setProperty('--sqsx', a.toFixed(3));
      el.style.setProperty('--sqsy', b.toFixed(3));
    }
    function move(e) {
      if (!el || (e.pointerId !== undefined && e.pointerId !== id)) return;
      if (typeof e.clientX !== 'number') return;
      var dx = e.clientX - x0, dy = e.clientY - y0, m = Math.sqrt(dx * dx + dy * dy);
      if (m > SQT.max) { dx *= SQT.max / m; dy *= SQT.max / m; }
      nx = dx; ny = dy; x0 = e.clientX; y0 = e.clientY;
      paint();   /* 只改一个元素的 transform，直接写比 rAF 更跟手 */
    }
    function release() {
      if (!el) return;
      var e0 = el;
      el = null; id = -1; nx = ny = 0;
      e0.classList.remove('sq-live');
      e0.classList.add('sq-back');
      e0.style.setProperty('--sqx', '0px'); e0.style.setProperty('--sqy', '0px');
      e0.style.setProperty('--sqsx', '1');  e0.style.setProperty('--sqsy', '1');
      /* 回位结束后摘掉类与变量，别把组件自己的 transition 永久接管 */
      clearTimeout(timer);
      timer = setTimeout(function () {
        e0.classList.remove('sq-back');
        ['--sqx', '--sqy', '--sqsx', '--sqsy'].forEach(function (k) { e0.style.removeProperty(k); });
      }, SQT.back + 80);
    }
    var opt = { passive: true };
    d.addEventListener('pointerdown', function (e) {
      if (el || squishBlocked()) return;
      var t = e.target && e.target.closest ? e.target.closest(SQUISH_SEL) : null;
      if (!t) return;
      el = t; id = e.pointerId; x0 = e.clientX; y0 = e.clientY;
      el.classList.remove('sq-back');
      el.classList.add('sq-live');
      paint();
    }, opt);
    d.addEventListener('pointermove', move, opt);
    d.addEventListener('pointerup', release, opt);
    d.addEventListener('pointercancel', release, opt);
    w.addEventListener('blur', release);
  }

  /* ---------------- 高光跟随手指（液态玻璃动态反馈） ----------------
     把手指/指针位置写入 --mx/--my（百分比），驱动所有玻璃表面
     的 --glass-specular-dyn 镜面高光随手指移动，模拟真实光线折射。
     仅在 glass-on 时生效；用 rAF 节流避免低端机掉帧。 */
  function initGlassLight() {
    /* 只绑一次：boot 与进「我的」都会走到这里 */
    if (w.__netopsGlassLight) return;
    w.__netopsGlassLight = true;
    var root = document.documentElement;
    var ticking = false, lx = 50, ly = -8;
    function apply() {
      ticking = false;
      root.style.setProperty('--mx', lx + '%');
      root.style.setProperty('--my', ly + '%');
    }
    function onMove(e) {
      /* 降级设备 / 低电量 / 省电动效偏好 / 用户关掉动画：不写入高光位置，保持静态 */
      if (deviceDegraded || batteryDegraded) return;
      if (U.motion.get() !== 'on') return;
      if (w.matchMedia && w.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
      if (!d.body.classList.contains('glass-on')) return;
      var t = e.touches ? e.touches[0] : e;
      if (!t || typeof t.clientX !== 'number') return;
      lx = Math.max(0, Math.min(100, (t.clientX / window.innerWidth) * 100));
      ly = Math.max(-20, Math.min(120, (t.clientY / window.innerHeight) * 100));
      if (!ticking) { ticking = true; requestAnimationFrame(apply); }
    }
    window.addEventListener('pointermove', onMove, { passive: true });
    window.addEventListener('touchmove', onMove, { passive: true });
    window.addEventListener('pointerdown', onMove, { passive: true });
    /* 指针/手指离开：把镜面高光复位到中性位置（顶部偏上），
       避免高光冻结在最后触点，松手即回落，更「活」。 */
    function reset() {
      if (deviceDegraded || batteryDegraded) return;
      if (U.motion.get() !== 'on') return;
      if (w.matchMedia && w.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
      lx = 50; ly = -8;
      if (!ticking) { ticking = true; requestAnimationFrame(apply); }
    }
    window.addEventListener('pointerup', reset, { passive: true });
    window.addEventListener('touchend', reset, { passive: true });
    window.addEventListener('mouseleave', reset, { passive: true });
  }

  /* ---------------- 背景壁纸（内置 7 类 x 2 张 + 相册自定义） ---------------- */
  var WP_KEY = 'netops_wallpaper';
  /* 相册自选图是 dataURL（可达数 MB），单独占一个键；
     WP_KEY 只存选择（'custom' / 'wp-x-y' / 'none'），不再被大图挤掉。 */
  var WP_CUSTOM_KEY = 'netops_wp_custom';
  function lsSet(key, val) {
    try { localStorage.setItem(key, val); return true; } catch (e) { return false; }
  }
  function lsGet(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }
  /* 内置壁纸清单:由 tools/gen-wallpapers.py 生成,文件在 assets/wallpapers/ */
  var WALLPAPERS = [{"id": "wp-1-1", "cat": "二次元", "name": "二次元1", "file": "二次元1.webp"}, {"id": "wp-1-2", "cat": "二次元", "name": "二次元2", "file": "二次元2.webp"}, {"id": "wp-2-1", "cat": "芙宁娜", "name": "芙宁娜1", "file": "芙宁娜1.webp"}, {"id": "wp-2-2", "cat": "芙宁娜", "name": "芙宁娜2", "file": "芙宁娜2.webp"}, {"id": "wp-3-1", "cat": "今汐", "name": "今汐1", "file": "今汐1.webp"}, {"id": "wp-3-2", "cat": "今汐", "name": "今汐2", "file": "今汐2.webp"}, {"id": "wp-4-1", "cat": "卡提希娅", "name": "卡提希娅1", "file": "卡提希娅1.webp"}, {"id": "wp-4-2", "cat": "卡提希娅", "name": "卡提希娅2", "file": "卡提希娅2.webp"}, {"id": "wp-5-1", "cat": "雷电将军", "name": "雷电将军1", "file": "雷电将军1.webp"}, {"id": "wp-5-2", "cat": "雷电将军", "name": "雷电将军2", "file": "雷电将军2.webp"}, {"id": "wp-6-1", "cat": "纳西妲", "name": "纳西妲1", "file": "纳西妲1.webp"}, {"id": "wp-6-2", "cat": "纳西妲", "name": "纳西妲2", "file": "纳西妲2.webp"}, {"id": "wp-7-1", "cat": "守岸人", "name": "守岸人1", "file": "守岸人1.webp"}, {"id": "wp-7-2", "cat": "守岸人", "name": "守岸人2", "file": "守岸人2.webp"}];

  function findWp(id) {
    for (var i = 0; i < WALLPAPERS.length; i++) if (WALLPAPERS[i].id === id) return WALLPAPERS[i];
    return null;
  }
  /* 壁纸相对路径 → URL:index.html 与 wallpapers/ 同目录(file:///android_asset/ 下相对解析,
     浏览器预览若静态服务器 serve 了 assets 目录同样可用;中文文件名需编码) */
  function wpUrl(rel) {
    return 'wallpapers/' + rel.split('/').map(function (s) { return encodeURIComponent(s); }).join('/');
  }
  function setWallpaperBg(wp) {
    var u = wpUrl(wp.file);
    paintWallpaper('url(' + u + ')');
  }
  /* 壁纸只写 --wallpaper 一个真源，由 .wall 层负责铺与模糊。
     以前这里还会往 body 上写一份 inline background（四处各写一遍，
     其中一份还漏了内置壁纸分支），现在统一收口到一个函数。 */
  function paintWallpaper(cssUrl) {
    var root = document.documentElement;
    if (!cssUrl || cssUrl === 'none') {
      root.style.setProperty('--wallpaper', 'none');
      d.body.classList.remove('has-wallpaper');
      return;
    }
    root.style.setProperty('--wallpaper', cssUrl);
    d.body.classList.add('has-wallpaper');
    measureWallFloor(cssUrl);
  }
  /* 按当前壁纸算玻璃板的最小不透明度 —— 但用的是**穿过滤镜链之后**的亮度带。
     ------------------------------------------------------------
     旧版把原图缩到 24px 宽取分位，那是 .wall 都没模糊过的原始动态范围，于是它算出
     0.46–0.92 的厚板下限；而表面早就在吃 saturate/contrast/brightness 压缩了，
     结果是一个已经不存在的模型在夹住通透度滑杆的低段。
     现在按真实链路量：pass1 = .wall（--bg-blur/--bg-sat/--bg-bri），
     pass2 = 表面的 --gr-tabbar-filter + 当前档位的 --gr-cmp-<档>。
     三个实测档位之间按通透度线性内插。所有数值都从 CSS 变量读，配方表仍是唯一真源。
     明暗两套一次算完存下来，切主题时不必重新解码图片。 */
  var wallFloor = { light: null, dark: null };
  var FLOOR_W = 390;                       /* 与 tools/lum-harness.html 同口径 */
  function floorBands(cssUrl, then) {
    var im = new Image();
    im.onload = function () {
      try {
        var rootCS = w.getComputedStyle(document.documentElement);
        var grab = function (n, dflt) { var v = (rootCS.getPropertyValue(n) || '').trim(); return v || dflt; };
        var wallBlur = grab('--bg-blur', '24px'), wallSat = grab('--bg-sat', '180%');
        /* 背景压暗系数必须与 applyGlass 按主题写的 --bg-bri 同值（门禁核对）。
           明暗两套曲线/表面模糊走 --gr-*-light/dark 这套主题无关的名字：一次
           getComputedStyle 快照只能拿到当前生效主题的 --gr-cmp-<t>。 */
        var DIM_LIGHT = 0.92, DIM_DARK = 0.55;
        var stops = (grab('--gr-cmp-stops', '') || '').split(/\s+/).filter(Boolean).map(Number);
        if (!stops.length) { then(null); return; }
        var cv = d.createElement('canvas');
        cv.width = FLOOR_W;
        cv.height = Math.max(1, Math.round(FLOOR_W * im.naturalHeight / im.naturalWidth));
        var ctx = cv.getContext('2d', { willReadFrequently: true });
        ctx.drawImage(im, 0, 0, cv.width, cv.height);
        var out = { stops: stops };
        [['light', Math.round(DIM_LIGHT * 100) + '%', 'surf-light', 'cmp-light-'],
         ['dark',  Math.round(DIM_DARK * 100) + '%', 'surf-dark',  'cmp-dark-']].forEach(function (pair) {
          var theme = pair[0];
          var c1 = d.createElement('canvas'); c1.width = cv.width; c1.height = cv.height;
          var x1 = c1.getContext('2d', { willReadFrequently: true });
          x1.filter = 'blur(' + wallBlur + ') saturate(' + wallSat + ') brightness(' + pair[1] + ')';
          x1.drawImage(cv, 0, 0); x1.filter = 'none';
          /* 高光取的是 .wall 输出上的底部条带 —— 也就是"玻璃背后是什么颜色"。
             不能从再往下的压缩结果里取：那层已经提亮过一次，再加 lift 会顶到 99%
             变成白色，"取背后色相"就白做了（实测参考片亮带 L≈0.76、彩度 37%）。
             c1 与档位无关，所以每主题只算一次。 */
          var p1 = x1.getImageData(0, 0, c1.width, c1.height).data;
          out[theme] = { strip: stripHue(p1, c1.width, c1.height) };
          stops.forEach(function (t) {
            var cmp = grab('--gr-' + pair[3] + t, '');
            if (!cmp) return;
            var c2 = d.createElement('canvas'); c2.width = cv.width; c2.height = cv.height;
            var x2 = c2.getContext('2d', { willReadFrequently: true });
            x2.filter = grab('--gr-' + pair[2], 'blur(28px)') + ' ' + cmp;
            x2.drawImage(c1, 0, 0); x2.filter = 'none';
            var px = x2.getImageData(0, 0, c2.width, c2.height).data, L = [];
            for (var i = 0; i < px.length; i += 4) L.push(relLum(px[i], px[i + 1], px[i + 2]));
            L.sort(function (a, b) { return a - b; });
            var q = function (p) { return L[Math.min(L.length - 1, Math.floor(L.length * p))]; };
            out[theme][t] = [q(0.05), q(0.95)];
          });
        });
        then(out);
      } catch (e) {
        /* 画布不可用（罕见）时留在 0、不阻塞启动 —— 但绝不能静默：
           这里曾经吞掉过一个 "root is not defined"，于是四道构建门禁全绿、
           暗色下限实际按浅色的带在算，而界面显示"一档没夹"。 */
        if (w.console && w.console.warn) w.console.warn('[glass] measureWallFloor 失败，下限退回 0：', e && e.message ? e.message : e);
        then(null);
      }
    };
    im.onerror = function () {
      if (w.console && w.console.warn) w.console.warn('[glass] 壁纸解码失败，无法量亮度带：' + cssUrl.slice(0, 48));
      then(null);
    };
    im.src = cssUrl.slice(4, -1).replace(/^["']|["']$/g, '');
  }
  function buildWallFloor(bands) {
    if (!bands) { wallFloor = { light: null, dark: null }; return; }
    /* 存**亮度带**而不是存解出来的板厚：门禁和运行时必须用同一个模型，而且要在档位之间
       内插带之后再解板厚。以前运行时内插的是已解出的 alpha（t10=0、t52=0.36 → t20 得
       0.086），门禁内插的是带（t20 带下沿 0.536 → 需 0.12），两者在低段差出一个
       baseA 的量级 —— 于是门禁说"夹住 24 档"而运行时说"一档没夹"。带→解才保物理。 */
    var AA_TARGET = 4.5, HI_TARGET = 7;
    var combos = {
      light: { plate: 1,                 text: relLum(85, 85, 90),   hiText: relLum(44, 44, 48) },
      dark:  { plate: relLum(44, 44, 46), text: relLum(185, 185, 194), hiText: relLum(230, 230, 234) }
    };
    wallFloor = { stops: bands.stops, bands: bands, combos: combos, AA: AA_TARGET, HI: HI_TARGET };
  }
  /* 取当前通透度档位下的下限：先在实测档位之间线性内插亮度带，再解板厚。tier = 'aa' | 'hi' */
  function floorAt(theme, t, tier) {
    var wf = wallFloor;
    if (!wf.stops || !wf.bands) return 0;
    var b = wf.bands[theme];
    if (!b) return 0;
    var s = wf.stops, lo = s[0], hi = s[s.length - 1], i;
    for (i = 0; i < s.length - 1; i++) if (t >= s[i] && t <= s[i + 1]) { lo = s[i]; hi = s[i + 1]; break; }
    var A = b[lo], B = b[hi];
    if (!A) return 0;
    var band = !B ? A : [0, 1].map(function (j) {
      var k = hi === lo ? 0 : (t - lo) / (hi - lo);
      return A[j] + (B[j] - A[j]) * k;
    });
    var c = wf.combos[theme];
    function solve(text, target) {
      for (var i2 = 0; i2 <= 48; i2++) {
        var a = i2 / 50;
        var ok = band.every(function (bg) {
          var lb = a * c.plate + (1 - a) * bg;
          return (Math.max(lb, text) + 0.05) / (Math.min(lb, text) + 0.05) >= target;
        });
        if (ok) return a;
      }
      return 0.96;
    }
    var aa = solve(c.text, wf.AA);
    return tier === 'hi' ? Math.max(aa, solve(c.hiText, wf.HI)) : aa;
  }
  /* 药丸背后的条带色：由 measureWallFloor 在 .wall 输出上量一次，与档位无关。 */
  function specAt(theme) {
    var wf = wallFloor;
    if (!wf.bands || !wf.bands[theme]) return null;
    return wf.bands[theme].strip || null;
  }
  function measureWallFloor(cssUrl) {
    floorBands(cssUrl, function (bands) { buildWallFloor(bands); applyGlass(getGlass() || glassDefaults); });
  }
  /* 取滤镜结果底部 8% 的平均色并转成 HSL。
     底栏贴在屏幕底部，所以这条带就是它背后大致是什么颜色 —— 高光据此取色相，
     不再写死白色。近似之处：只算壁纸（cover 裁切后的底部条带），滚动到药丸底下的
     文字与卡片不算进来。 */
  function stripHue(px, w, h) {
    var y0 = Math.floor(h * 0.92), n = 0, r = 0, g = 0, b = 0;
    for (var y = y0; y < h; y++) {
      for (var x = 0; x < w; x++) {
        var i = (y * w + x) * 4;
        r += px[i]; g += px[i + 1]; b += px[i + 2]; n++;
      }
    }
    if (!n) return null;
    r /= 255 * n; g /= 255 * n; b /= 255 * n;
    var mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn;
    var hh = 0;
    if (d > 1e-6) {
      if (mx === r) hh = (g - b) / d + (g < b ? 6 : 0);
      else if (mx === g) hh = (b - r) / d + 2;
      else hh = (r - g) / d + 4;
      hh *= 60;
    }
    var l = (mx + mn) / 2;
    var s = d <= 1e-6 ? 0 : Math.min(1, d / (1 - Math.abs(2 * l - 1)));
    return [Math.round(hh), +s.toFixed(3), +l.toFixed(3)];
  }
  function relLum(r, g, b) {
    var f = function (c) { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
  }

  /* 共享选中态药丸：把 .tab-pill 平移到当前 tab 上。
     用 getBoundingClientRect 而不是按 1/5 算宽度 —— 内边距、安全区、平板限宽
     （05-views.css 的 max-width:860px 与 justify-content:center）都会改变格宽，
     算出来的会和实际错位。 */
  function positionTabPill() {
    var bar = $('#tabbar'), pill = $('#tabPill');
    if (!bar || !pill) return;
    var act = bar.querySelector('.tab.is-active');
    if (!act) return;
    var rb = bar.getBoundingClientRect(), rt = act.getBoundingClientRect();
    if (!rt.width) return;                       /* 尚未布局（隐藏/首帧），别写 0 宽 */
    pill.style.setProperty('--pill-x', (rt.left - rb.left).toFixed(1) + 'px');
    pill.style.setProperty('--pill-w', rt.width.toFixed(1) + 'px');
  }

  function initWallpaper() {
    var lbl = $('#wallpaperLbl');
    var sheet = $('#wpSheet');
    if (!lbl || !sheet) return;

    var cur = lsGet(WP_KEY) || 'none';
    updateWallpaperLabel(cur);

    /* 点击「背景壁纸」行打开弹窗 */
    lbl.closest('.list__item').addEventListener('click', function () {
      if (sheet.classList.contains('is-open')) return;
      renderWpGrid();
      sheet.classList.add('is-open');
    });

    /* 内置壁纸选择区:7 类 x 2 张文字 chips(用户 2026-08-12:不要缩略图,只要名字) */
    function renderWpGrid() {
      var grid = $('#wpGrid');
      if (!grid) return;
      var curId = lsGet(WP_KEY) || 'none';
      var html = '', lastCat = null;
      for (var i = 0; i < WALLPAPERS.length; i++) {
        var wp = WALLPAPERS[i];
        if (wp.cat !== lastCat) {
          if (lastCat !== null) html += '</div>';
          html += '<div class="wp-cat">' + wp.cat + '</div><div class="wp-chips">';
          lastCat = wp.cat;
        }
        html += '<button type="button" class="chip' + (curId === wp.id ? ' is-active' : '') + '" data-wpid="' + wp.id + '">' + wp.name + '</button>';
      }
      html += '</div>';
      grid.innerHTML = html;
      /* onclick 属性赋值:天然覆盖旧绑定,防重复(每次开 sheet 都渲染新 grid) */
      grid.onclick = function (e) {
        var t = e.target;
        while (t && t !== grid && !(t.classList && t.classList.contains('chip'))) t = t.parentNode;
        if (!t || t === grid) return;
        var id = t.getAttribute('data-wpid');
        var wp = findWp(id);
        if (!wp) return;
        applyWallpaper(wp.id, 'wp');
        var cells = grid.querySelectorAll('.chip');
        for (var j = 0; j < cells.length; j++) cells[j].classList.remove('is-active');
        t.classList.add('is-active');
        U.toast('壁纸: ' + wp.name, 'ok');
        sheet.classList.remove('is-open');
      };
    }

    /* 从相册选图 */
    var customBtn = $('#wpCustomBtn');
    if (customBtn) customBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      sheet.classList.remove('is-open');
      if (w.NetBridge && w.NetBridge.pickImage) {
        w.NetBridge.pickImage();
        U.toast('请在相册中选择图片…', '');
      } else {
        U.toast('当前环境不支持相册选择', 'warn');
      }
    });

    /* 清除壁纸 */
    var clearBtn = $('#wpClearBtn');
    if (clearBtn) clearBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      applyWallpaper('none', null);
      sheet.classList.remove('is-open');
      U.toast('壁纸已清除', 'ok');
    });

    /* 关闭弹窗 */
    setupSheetBackdrop(sheet);
  }

  /** 原生桥接回调：收到 base64 图片 */
  w.NetOpsOnWallpaper = function (dataUrl) {
    if (!dataUrl || String(dataUrl).indexOf('data:image/') !== 0) {
      U.toast('壁纸未设置：读到的不是有效图片', 'danger');
      return;
    }
    /* 旧写法把 dataURL 写进 WP_KEY，紧接着又被 applyWallpaper('custom') 覆盖成 'custom'，
       而 restoreWallpaper 只认 data: 前缀 → 相册自选的壁纸重启必丢，只剩半透明内容层没底图。 */
    var saved = lsSet(WP_CUSTOM_KEY, dataUrl);
    applyWallpaper('custom', dataUrl);
    U.toast(saved ? '壁纸已设置' : '壁纸已应用，但本地容量不足，重启后不会保留（建议改用内置壁纸）',
      saved ? 'ok' : 'danger', saved ? 1800 : 4200);
  };

  function applyWallpaper(id, cssValue) {
    if (id === 'none' || !id) { try { localStorage.removeItem(WP_CUSTOM_KEY); } catch (e) {} }
    lsSet(WP_KEY, id || 'none');
    if (!cssValue || id === 'none') {
      paintWallpaper(null);
    } else if (cssValue === 'wp') {
      /* 内置壁纸:按 id 查清单,设置相对路径背景 */
      var wp = findWp(id);
      if (wp) setWallpaperBg(wp);
    } else {
      var cssUrl = 'url(' + cssValue + ')';
      paintWallpaper(cssUrl);
    }
    updateWallpaperLabel(id || 'none');
  }

  function updateWallpaperLabel(val) {
    var lbl = $('#wallpaperLbl');
    if (!lbl) return;
    if (!val || val === 'none') { lbl.textContent = '默认'; return; }
    if (val.indexOf('wp-') === 0) {
      var wp = findWp(val);
      lbl.textContent = wp ? wp.name : '已设置';
      return;
    }
    if (val.startsWith('data:') || val === 'custom') { lbl.textContent = '自定义图片'; return; }
    lbl.textContent = '已设置';
  }

  /* 页面启动时恢复已保存的壁纸 */
  (function restoreWallpaper() {
    var saved = lsGet(WP_KEY);
    if (!saved || saved === 'none') return;
    if (saved.indexOf('wp-') === 0) {
      var wp = findWp(saved);
      if (wp) { setWallpaperBg(wp); return; }
    }
    /* 'custom' 的图在 WP_CUSTOM_KEY 里；兼容更早版本直接把 dataURL 存在 WP_KEY 的情况 */
    if (saved === 'custom') saved = lsGet(WP_CUSTOM_KEY) || lsGet(WP_KEY);
    if (!saved || saved.indexOf('data:') !== 0) {
      /* 选择记着但图没了（容量不足被丢弃 / 旧版本遗留）：回到无壁纸，别留半透明空壳 */
      lsSet(WP_KEY, 'none');
      updateWallpaperLabel('none');
      return;
    }
    if (saved.startsWith('data:')) paintWallpaper('url(' + saved + ')');
  })();

  /* ---------------- 涛态玻璃 / 毛玻璃效果（三模式：关闭/毛玻璃/液态玻璃） ---------------- */
  var GLASS_KEY = 'netops_glass';
  var glassDefaults = {
    on: true,           /* 总开关 */
    mode: 'liquid',     /* A=液态玻璃(默认,通透+高光)  B=标准毛玻璃(frosted,扁平省电)  gaussian=纯模糊(极致省电档) */
    blur: 14,           /* 模糊强度 8-20 px（业界甜区） */
    tint: 55,           /* 液态玻璃通透度 20-90（% 不透明度，越低越通透） */
    hiContrast: false   /* 增强对比度：玻璃板下限从 AA 4.5:1 抬到长时间阅读的 7:1 */
  };

  function getGlass() {
    try { var s = localStorage.getItem(GLASS_KEY); return s ? JSON.parse(s) : null; } catch(e) { return null; }
  }
  function saveGlass(g) { lsSet(GLASS_KEY, JSON.stringify(g)); }

  /* 设备降级状态：低电/旧设备自动切到模式 B（标准毛玻璃）。
     deviceDegraded ：硬件层（内存/CPU/省电偏好），启动时判定一次，不自动恢复；
     batteryDegraded：电量层（≤20% 且未充电），充电恢复后自动解除；
     userOverrideGlass：用户手动切回液态玻璃后，本会话内覆盖降级。 */
  var deviceDegraded = false, batteryDegraded = false, userOverrideGlass = true;   /* 2026-08-12:默认 true，尊重用户当前选择(液态/任意)，仅当手动选 frosted/gaussian 才设 false 允许降级 */

  /** 硬件层降级判定：低内存 / 低 CPU / 系统级减少动态效果 */
  function shouldDegrade() {
    try {
      var nav = w.navigator || {};
      var mem = nav.deviceMemory;
      var cores = nav.hardwareConcurrency || 0;
      var reduce = !!(w.matchMedia && w.matchMedia('(prefers-reduced-motion: reduce)').matches);
      if (typeof mem === 'number' && mem <= 3) return true;        /* ≤3GB 视为低端机 */
      if (cores > 0 && cores <= 4 && reduce) return true;           /* 4 核以下且要求减少动效 */
      if (reduce && cores > 0 && cores <= 6) return true;           /* 6 核以下 + 省电动效偏好 */
      return false;
    } catch (e) { return false; }
  }

  /** 计算实际生效的模式：降级且用户未手动覆盖时，强制回退到标准毛玻璃（模式 B） */
  function effectiveGlassMode(g) {
    if (g.on && (deviceDegraded || batteryDegraded) && !userOverrideGlass) return 'frosted';
    return g.mode;
  }

  /** 将当前玻璃参数（含降级覆盖）应用到 CSS 变量与 body 类 */
  function applyGlass(g) {
    var root = document.documentElement;
    var em = effectiveGlassMode(g);
    /* 2026-08-12 17:43（按用户修复方案）：blur 独立于 mode ——
       liquid 模式下 blur 为 0（通透设计），frosted/gaussian 用用户设定值 g.blur，
       切换模式时正确恢复，不再出现"切换后模糊失效"。 */
    /* blur 不再按模式归零：以前 liquid 强制 0 模糊（注释写"通透设计"），
       于是"液态玻璃"在真机上就是一张平涂白纱，壁纸的高频细节直接压在字底下。
       通透感交给板的 alpha 表达，模糊是玻璃之所以是玻璃的那一味。 */
    var blurPx = (em === 'liquid') ? 18 : (g.blur || 14);
    root.style.setProperty('--glass-blur', blurPx + 'px');
    root.style.setProperty('--glass-blur-strong', Math.round(blurPx * 1.4) + 'px');
    /* 三模式统一 alpha 公式：
       - liquid：tint 10% → α≈0.04 (极透), 95% → α≈0.92 (近实色) —— 滑块响应范围大
       - frosted：在 liquid 基础上 +0.20 (略实,「苹果磨砂」感)
       - gaussian：在 liquid 基础上 +0.45 (近不透明,「iOS Reduce Transparency」)
       三模式统一用 var(--glass-tint-top/bot),但 alpha 范围不同——保持视觉差异同时响应滑块 */
    var t = Math.max(10, Math.min(95, g.tint || 55));
    /* 压缩曲线按通透度档位插值。--gr-cmp-<档位> 由配方表生成、明暗两版分别落在
       :root / html.dark，所以这里从 computed style 读到的已经是当前主题的串，
       JS 不再判断主题。以前表面只有 saturate+blur，没有 brightness/contrast 就
       没有动态范围压缩，可读性只能靠抬板厚换 —— 板厚一抬，滑杆行程就死了。 */
    root.style.setProperty('--glass-cmp', (function () {
      var cs = w.getComputedStyle(root);
      var stops = (cs.getPropertyValue('--gr-cmp-stops') || '').trim().split(/\s+/).filter(Boolean).map(Number);
      if (stops.length < 2) return '';
      /* 保序解析：浅色曲线是"先 contrast 压向中灰、再 brightness 抬向白"，顺序一反就等于
         把刚压平的亮度带重新撑开。所以按出现顺序记 [函数名, 数值]，插值后按同序输出。 */
      var parse = function (s) {
        var out = [], m, re = /(saturate|brightness|contrast)\(([\d.]+)(%?)\)/g;
        while ((m = re.exec(s))) out.push([m[1], parseFloat(m[2]) / (m[3] ? 100 : 1)]);
        return out;
      };
      var lo = stops[0], hi = stops[stops.length - 1], i, j;
      for (i = 0; i < stops.length - 1; i++) {
        if (t >= stops[i] && t <= stops[i + 1]) { lo = stops[i]; hi = stops[i + 1]; break; }
      }
      var A = parse(cs.getPropertyValue('--gr-cmp-' + lo)), B = parse(cs.getPropertyValue('--gr-cmp-' + hi));
      var k = hi === lo ? 0 : (t - lo) / (hi - lo);
      var parts = [];
      for (i = 0; i < A.length; i++) {
        var v = A[i][1];
        for (j = 0; j < B.length; j++) if (B[j][0] === A[i][0]) v = v + (B[j][1] - v) * k;
        parts.push(A[i][0] + '(' + v.toFixed(3) + ')');
      }
      return parts.join(' ');
    })());
    var baseA = 0.04 + Math.pow((t - 10) / 85, 1.2) * 0.90;  /* 非线性：低端稀薄、高端厚实 */
    var a;
    if (em === 'frosted') a = Math.min(0.85, baseA + 0.20);
    else if (em === 'gaussian') a = Math.min(0.94, baseA + 0.45);
    else a = baseA;
    /* 承载文字的玻璃板不能跟着通透度一路透下去：
       下限按当前壁纸的亮度带实测算出（measureWallFloor），无壁纸时为 0 不干预。
       「增强对比度」把目标从 AA 的 4.5:1 抬到长时间阅读的 7:1。 */
    var floor = (g.on && d.body.classList.contains('has-wallpaper'))
      ? floorAt(root.classList.contains('dark') ? 'dark' : 'light', t, g.hiContrast ? 'hi' : 'aa') : 0;
    a = Math.max(a, floor);
    /* 高光跟着背后的色相走（用户缺陷⑤"根据背景颜色产生真实的光线反射"的颜色维度）。
       亮带 = 同色相 + 提亮 lift + 彩度×satScale；顶缘暗肩 = 同色相 + 压暗 darkDrop。
       参数取自配方表 lens.specular，口径是对参考实现的逐像素取样。
       没壁纸或量不到时删掉这两个变量，CSS 自动退回表里的静态 specular 值。 */
    (function () {
      var rcs = w.getComputedStyle(root);
      var lift = parseFloat(rcs.getPropertyValue('--gr-spec-lift')),
        ssc = parseFloat(rcs.getPropertyValue('--gr-spec-sat')),
        sal = parseFloat(rcs.getPropertyValue('--gr-spec-alpha')),
        drp = parseFloat(rcs.getPropertyValue('--gr-rim-dark-drop')),
        dal = parseFloat(rcs.getPropertyValue('--gr-rim-dark-alpha'));
      if (!(lift >= 0) || !(ssc >= 0)) return;
      var th = root.classList.contains('dark') ? 'dark' : 'light';
      var c = (g.on && d.body.classList.contains('has-wallpaper')) ? specAt(th) : null;
      if (!c) { root.style.removeProperty('--glass-spec'); root.style.removeProperty('--glass-rim-dark'); return; }
      var h = Math.round(c[0]);
      var sa = Math.round(Math.min(1, c[1] * ssc) * 100);
      var lb = Math.round(Math.min(0.99, Math.max(0, c[2] + lift)) * 100);
      var ld = Math.round(Math.min(0.99, Math.max(0.02, c[2] - drp)) * 100);
      root.style.setProperty('--glass-spec', 'hsla(' + h + ',' + sa + '%,' + lb + '%,' + (isNaN(sal) ? .85 : sal) + ')');
      root.style.setProperty('--glass-rim-dark', 'hsla(' + h + ',' + sa + '%,' + ld + '%,' + (isNaN(dal) ? .34 : dal) + ')');
    })();
    /* 被地板抬过时，界面必须说出"实际生效的是多少"。
       反解 baseA：t_eff = 10 + 85·((a-0.04)/0.9)^(1/1.2)，板越厚等效通透度越高。 */
    var effT = Math.round(Math.max(t, Math.min(95,
      10 + 85 * Math.pow(Math.max(0, Math.min(1, (a - 0.04) / 0.90)), 1 / 1.2))));
    w.__glassTint = { want: t, eff: effT, clamped: effT > t + 1 };
    var b = Math.max(0.03, a - 0.13);
    root.style.setProperty('--glass-tint-top', 'rgba(255,255,255,' + a.toFixed(3) + ')');
    root.style.setProperty('--glass-tint-bot', 'rgba(250,250,252,' + b.toFixed(3) + ')');
    /* 暗色模式专用：纯 alpha 变量（暗色 CSS 用 rgba(44,44,46,var(--glass-tint-top-a)) 形式） */
    root.style.setProperty('--glass-tint-top-a', a.toFixed(3));
    root.style.setProperty('--glass-tint-bot-a', b.toFixed(3));
    /* 滚动区域"实色蒙版"alpha（frosted/gaussian 主卡/子段用，跟随 px 滑块，范围 .72-.92） */
    var maskA = Math.min(0.92, Math.max(0.72, 0.60 + (g.blur - 8) / 12 * 0.30));
    root.style.setProperty('--glass-mask-a', maskA.toFixed(3));
    /* 内容层统一 alpha（2026-09-22）：主卡、内层底板（表头/芯片/图标底板/进度轨/开关/环）、
       WebView 降级蒙版全部共用这一个值，不再各写各的：
       liquid 跟通透度滑块 a；frosted/gaussian 跟 px 滑块 maskA；关玻璃时回落到壁纸基线 .72。
       消费方见 01-tokens.css 的 --surf-* 与 06-anim.css 末尾的 body.has-wallpaper 段。 */
    var contentA = Math.max(g.on ? (em === 'liquid' ? a : maskA) : 0.72, floor);
    root.style.setProperty('--glass-content-a', contentA.toFixed(3));
    /* 预模糊背景层参数：玻璃开着才有模糊，关玻璃回到清晰壁纸（与历史行为一致）。
       层的模糊比元素 backdrop 略强，因为这层要替所有卡片承担"背后是虚的"。 */
    root.style.setProperty('--bg-blur', g.on ? (blurPx + 6) + 'px' : '0px');
    root.style.setProperty('--bg-sat', g.on && em !== 'gaussian' ? '180%' : '100%');
    /* 关键一味：只 saturate 不压亮度就是"难看的灰玻璃"——业界配方是
       饱和度提上去的同时把背后压暗（浅色轻压、深色重压），玻璃才读得出厚度。
       这里的百分比必须与 measureWallFloor 两套 combo 的 dim 同值，门禁会核对。 */
    root.style.setProperty('--bg-bri', !g.on ? '100%'
      : (root.classList.contains('dark') ? '55%' : '92%'));
    /* 把当前生效的下限暴露成可读变量：冒烟测试与门禁据此断言"兜住了"，
       也方便在真机上直接看出是哪一档在起作用。 */
    root.style.setProperty('--glass-min-a', floor.toFixed(2));
    /* 弹窗（.sheet）没有周围层次可借，不能跟着通透度一路透下去：
       实测 tint 10 时暗壁纸区文字对比度只剩 1.12:1（AA 要 4.5）。
       故给弹窗单独一档下限 .55（≈5.6:1），内联卡片仍完全跟随滑块。 */
    var sheetA = Math.max(contentA, 0.55);
    var sheetB = Math.max(0.03, sheetA - 0.13);
    root.style.setProperty('--glass-sheet-top', 'rgba(255,255,255,' + sheetA.toFixed(3) + ')');
    root.style.setProperty('--glass-sheet-bot', 'rgba(250,250,252,' + sheetB.toFixed(3) + ')');
    root.style.setProperty('--glass-sheet-top-k', 'rgba(44,44,46,' + sheetA.toFixed(3) + ')');
    root.style.setProperty('--glass-sheet-bot-k', 'rgba(32,32,36,' + sheetB.toFixed(3) + ')');
    d.body.classList.toggle('glass-on', g.on);
    /* 增强对比度：换文字 token（CSS 侧 html.hi-contrast）+ 用更厚的板（floor 走 hi） */
    root.classList.toggle('hi-contrast', !!(g.on && g.hiContrast));
    d.body.classList.toggle('glass-liquid', g.on && em === 'liquid');
    d.body.classList.toggle('glass-gaussian', g.on && em === 'gaussian');
    d.body.classList.toggle('glass-frosted', g.on && em === 'frosted');
    /* 高斯模式：饱和度 100%（纯模糊，不额外饱和） */
    if (g.on) {
      root.style.setProperty('--glass-saturate', em === 'gaussian' ? '100%' : '180%');
    }
  }

  /** 通透度读数：被可读性下限抬过时如实标出实际值，不再显示一个对不上界面的数字 */
  function tintLabel(g) {
    var ct = w.__glassTint;
    if (!ct) return (g && g.tint || 55) + '%';
    return ct.clamped ? ct.want + '% · 实际 ' + ct.eff + '%' : ct.want + '%';
  }

  /** 渲染玻璃设置面板（双模式段控：A 液态玻璃 / B 标准毛玻璃 + 高斯省电档） */
  function renderGlassPanel() {
    var body = $('#glassBody');
    if (!body) return;
    var g = getGlass() || glassDefaults;
    var em = effectiveGlassMode(g);          /* 实际生效模式（可能已被降级覆盖） */
    var degraded = (deviceDegraded || batteryDegraded) && !userOverrideGlass;

    var h = '';

    /* 总开关 + 模式 + 模糊强度（始终显示，无需壁纸） */
    h += '<div style="display:flex;align-items:center;justify-content:space-between;padding:8px 0">' +
      '<div><div class="t-sm bold">玻璃效果</div><div class="t-xs t-mute">为界面添加半透明模糊质感</div></div>' +
      '<span class="switch' + (g.on ? ' is-on' : '') + '" id="glassSw_on"></span></div>';

    if (g.on) {
      /* 双模式段控：A 液态玻璃（默认）/ B 标准毛玻璃 + 高斯模糊省电档 */
      var modes = [
        { k: 'liquid',   ico: '💧', name: '液态玻璃',   desc: '镜面高光 + 边缘光折射 · 苹果风格，观感最佳（默认）' },
        { k: 'frosted',  ico: '🌫️', name: '标准毛玻璃', desc: 'iOS 风哑光磨砂 · 柔和暖调，更轻量省电' },
        { k: 'gaussian', ico: '◯',  name: '高斯模糊',   desc: '近乎不透明 · 最省电，适合旧设备/户外护眼' }
      ];
      h += '<div style="margin-top:14px"><div class="t-xs bold" style="margin-bottom:8px;color:var(--text-2)">全局质感（单选）</div>';
      h += '<div style="display:flex;gap:8px;background:var(--surf-2);border-radius:var(--r-md);padding:6px">';
      modes.forEach(function (m) {
        var on = em === m.k;
        h += '<button type="button" class="chip' + (on ? ' is-active' : '') + '" data-glass="mode" data-gval="' + m.k + '"' +
          ' style="flex:1 1 0;flex-direction:column;height:auto;padding:9px 6px;gap:3px;font-size:11.5px;line-height:1.3">' +
          '<span style="font-size:18px">' + m.ico + '</span>' +
          '<span style="font-weight:700">' + m.name + '</span></button>';
      });
      h += '</div>';

      /* 降级说明条 */
      if (degraded) {
        h += '<div class="t-xs" style="margin-top:10px;padding:9px 10px;border-radius:var(--r-sm);' +
          'background:var(--warn-soft);color:var(--warn);line-height:1.55">' +
          '⚡ 已为' + (batteryDegraded && !deviceDegraded ? '低电量' : '当前设备') + '自动切换为「标准毛玻璃」以省电。' +
          (userOverrideGlass ? '（你已手动覆盖，本会话保持你的选择）' : '手动选择任意质感即可临时覆盖省电流式。') + '</div>';
      }

      /* 滑块语义随模式切换：液态玻璃 → 通透度(%不透明度)；毛玻璃/高斯 → 模糊强度(px)。
        三种模式都带模糊（由 .wall 背景层统一提供），通透度由 g.tint 控制；滑块不锁死，切模式即时换语义 */
      var isLiquid = (g.on && effectiveGlassMode(g) === 'liquid');
      var sKey = isLiquid ? 'tint' : 'blur';
      var sMin = isLiquid ? 10 : 8;
      var sMax = isLiquid ? 95 : 20;
      var sVal = isLiquid ? (g.tint || 55) : g.blur;
      var sLbl = isLiquid ? '通透度' : '模糊强度';
      var sSfx = isLiquid ? '%' : 'px';
      h += '<div style="margin-top:16px"><div class="row" style="justify-content:space-between;margin-bottom:8px">' +
        '<span class="t-xs bold" style="color:var(--text-2)">' + sLbl + '</span>' +
        '<span class="t-xs mono" style="color:var(--accent)" id="glassVal_' + sKey + '">' +
          (sKey === 'tint' ? tintLabel(g) : sVal + sSfx) + '</span></div>' +
        '<input type="range" class="glass-slider" min="' + sMin + '" max="' + sMax + '" step="' + (isLiquid ? 5 : 1) + '" value="' + sVal + '" data-glass="' + sKey + '" style="width:100%"></div>' +
      (isLiquid ?
        '<div class="t-xs" style="margin-top:8px;padding:8px 10px;border-radius:var(--r-sm);background:var(--accent-soft);color:var(--accent-text);line-height:1.55">' +
        '通透度 = 浮层不透明度（越低越透，壁纸越清晰）。背景已统一预模糊，且低于可读性下限时会自动兜住，再透也不会把字埋掉。</div>' :
        '');

      /* 增强对比度：玻璃板下限从 AA 的 4.5:1 抬到长时间阅读的 7:1 */
      h += '<div style="display:flex;align-items:center;justify-content:space-between;padding:14px 0 2px">' +
        '<div><div class="t-sm bold">增强对比度</div>' +
        '<div class="t-xs t-mute">把说明文字换成更强对比的一档，与背景对比从 4.5:1 提到 7:1（长时间阅读推荐值），必要时玻璃板同步加厚</div></div>' +
        '<span class="switch' + (g.hiContrast ? ' is-on' : '') + '" id="glassSw_hi"></span></div>';

      /* 按压形变手感调参。隐藏入口：长按本弹窗标题 0.6 秒 —— 这是给我自己
         调手感用的，不该占正式界面；数值存 store，所以自动进 JSON 备份。 */
      function sqRow(k, name, unit, min, max, step, val) {
        return '<div style="margin-top:10px"><div class="row" style="justify-content:space-between">' +
          '<span class="t-xs" style="color:var(--text-2)">' + name + '</span>' +
          '<span class="t-xs mono" style="color:var(--accent)" id="sqVal_' + k + '">' + val + unit + '</span></div>' +
          '<input type="range" class="glass-slider" min="' + min + '" max="' + max + '" step="' + step +
          '" value="' + val + '" data-sq="' + k + '" style="width:100%"></div>';
      }
      h += '<div id="sqTune" style="display:none;margin-top:14px;padding:12px;border-radius:var(--r-md);background:var(--surf-2)">' +
        '<div class="t-xs bold" style="color:var(--text-2)">按压形变手感（调试）</div>' +
        sqRow('max', '跟手位移上限', 'px', 0, 20, 1, SQT.max) +
        sqRow('press', '按下压缩', '%', 0, 15, 1, Math.round((1 - SQT.press) * 100)) +
        sqRow('stretch', '沿拖动方向拉伸', '%', 0, 20, 1, Math.round(SQT.stretch * 100)) +
        sqRow('squash', '垂直轴反向压缩', '%', 0, 20, 1, Math.round(SQT.squash * 100)) +
        sqRow('back', '回弹时长', 'ms', 120, 1200, 20, SQT.back) +
        '<div class="row" style="gap:10px;margin-top:12px;align-items:center">' +
        '<span class="chip" id="sqDemo">按住我试</span>' +
        '<button type="button" class="btn btn--soft t-xs" id="sqReset" style="padding:6px 10px">复位</button></div>' +
        '<div class="t-xs mono" id="sqOut" style="margin-top:10px;word-break:break-all"></div></div>';

      h += '<div class="t-xs t-mute" style="margin-top:14px;padding:10px;background:var(--surf-2);border-radius:var(--r-sm);line-height:1.65">' +
        (isLiquid ? '提示：设一张背景壁纸后，通透度变化更明显。滑块拉到很透时，为保证文字可读，板厚会停在按这张壁纸算出的下限上。' :
                   '提示：设一张背景壁纸后效果更明显。推荐 12-16px 平衡观感与流畅度。') + '</div>';
    }

    body.innerHTML = h;

    /* 绑定事件：开关 */
    var swOn = body.querySelector('#glassSw_on');
    if (swOn) swOn.parentElement.addEventListener('click', function () {
      g.on = !g.on; saveGlass(g); applyGlass(g);
      renderGlassPanel();
      updateGlassLabel(g);
      U.toast(g.on ? '玻璃效果已开启' : '玻璃效果已关闭', 'ok');
    });

    /* 绑定事件：增强对比度 */
    var swHi = body.querySelector('#glassSw_hi');
    if (swHi) swHi.parentElement.addEventListener('click', function () {
      g.hiContrast = !g.hiContrast; saveGlass(g); applyGlass(g);
      renderGlassPanel(); updateGlassLabel(g);
      U.toast(g.hiContrast ? '已增强对比度：玻璃板加厚' : '已恢复默认通透度', 'ok');
    });

    /* 绑定事件：形变手感调参（滑块即时生效，演示块当场可拉扯） */
    var tune = body.querySelector('#sqTune');
    if (tune) {
      var out = body.querySelector('#sqOut');
      if (out) { out.style.marginTop = '10px'; out.textContent = JSON.stringify(SQT); }
      body.querySelectorAll('[data-sq]').forEach(function (input) {
        var k = input.getAttribute('data-sq');
        input.addEventListener('input', function () {
          var raw = parseFloat(input.value), s2 = getSqt();
          if (k === 'max') s2.max = raw;
          else if (k === 'back') s2.back = raw;
          else if (k === 'press') s2.press = 1 - raw / 100;
          else s2[k] = raw / 100;
          U.store.set('sqtune', s2);
          var cur = applySqt();
          var lbl = body.querySelector('#sqVal_' + k);
          if (lbl) lbl.textContent = input.value + (k === 'max' ? 'px' : k === 'back' ? 'ms' : '%');
          if (out) out.textContent = JSON.stringify(cur);
        });
      });
      var rst = body.querySelector('#sqReset');
      if (rst) rst.addEventListener('click', function () {
        U.store.set('sqtune', SQT_DEF); applySqt(); renderGlassPanel();
        U.toast('形变参数已复位', 'ok');
      });
    }
    /* 隐藏入口：长按弹窗标题 0.6 秒 */
    var gTitle = d.querySelector('#glassSheet .sheet__title');
    if (gTitle && !gTitle.__sqBound) {
      gTitle.__sqBound = true;
      var lp = 0;
      gTitle.addEventListener('pointerdown', function () {
        clearTimeout(lp);
        lp = setTimeout(function () {
          var t2 = body.querySelector('#sqTune');
          if (!t2) return;
          var show = t2.style.display === 'none';
          t2.style.display = show ? 'block' : 'none';
          if (show) U.toast(squishBlocked()
            ? '调参面板已展开，但当前设备/设置停用了形变（检查「动画效果」与省电状态）'
            : '调参面板已展开：按住下面那块试手感', 'ok', 3200);
        }, 600);
      }, { passive: true });
      ['pointerup', 'pointermove', 'pointercancel', 'pointerleave'].forEach(function (ev) {
        gTitle.addEventListener(ev, function () { clearTimeout(lp); }, { passive: true });
      });
    }

    /* 绑定事件：段控 */
    body.querySelectorAll('[data-glass="mode"]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var val = this.getAttribute('data-gval');
        g.mode = val;
        /* 用户手动选择毛玻璃/高斯才允许省电降级；选液态 = 解除覆盖(尊重通透感) */
        userOverrideGlass = (val === 'liquid');
        saveGlass(g); applyGlass(g); renderGlassPanel(); updateGlassLabel(g);
      });
    });

    /* 绑定事件：滑块（requestAnimationFrame 节流，避免拖动时连续 input 事件
       频繁重绘导致 WebView 闪屏）。必须按 [data-glass] 收窄 —— 调参面板的滑块
       同样用 .glass-slider 复用外观，早期版本会把它们一起绑上，key 取到 null
       后 `$('#glassVal_null').textContent` 直接抛异常。 */
    body.querySelectorAll('.glass-slider[data-glass]').forEach(function (input) {
      var key = input.getAttribute('data-glass');
      var raf = 0, lastText = '';
      function applyFlush() {
        raf = 0;
        var val = parseInt(input.value, 10);
        g[key] = val;
        var txt = key === 'tint' ? tintLabel(g) : val + 'px';
        var out2 = $('#glassVal_' + key);
        if (out2 && txt !== lastText) { out2.textContent = txt; lastText = txt; }
        saveGlass(g); applyGlass(g);
      }
      function apply() {
        if (raf) return;
        raf = requestAnimationFrame(applyFlush);
      }
      /* 结束时强制 flush 一次,避免最后一帧没更新 */
      function applyNow() { if (raf) { cancelAnimationFrame(raf); raf = 0; } applyFlush(); }
      input.addEventListener('input', apply);
      input.addEventListener('change', applyNow);
    });
  }

  /* ---- 通用 Sheet 关闭增强（Fix 3: 遮罩点击 / 返回键 / 防重复打开） ---- */
  var openCustomSheet = null; /* 记录当前打开的自定义 sheet */
  function setupSheetBackdrop(sheet) {
    if (!sheet) return;
    /* 点击弹窗外区域（view 容器）关闭 */
    function closeIfOpen(e) {
      if (!sheet.classList.contains('is-open')) return;
      if (sheet.contains(e.target)) return;
      sheet.classList.remove('is-open');
      openCustomSheet = null;
    }
    /* 延迟绑定，避免立即触发 */
    setTimeout(function () {
      d.addEventListener('click', closeIfOpen, true); /* capture 阶段拦截 */
    }, 50);
    /* 拖拽条关闭 */
    var grab = sheet.querySelector('.sheet__grab');
    if (grab) grab.addEventListener('click', function (e) {
      e.stopPropagation();
      sheet.classList.remove('is-open');
      openCustomSheet = null;
    });
  }
  /** 检查是否有自定义 sheet 打开（供 back() 调用） */
  function closeAnyCustomSheet() {
    var wp = $('#wpSheet'), gl = $('#glassSheet');
    if (wp && wp.classList.contains('is-open')) { wp.classList.remove('is-open'); return true; }
    if (gl && gl.classList.contains('is-open')) { gl.classList.remove('is-open'); return true; }
    return false;
  }

  /* ---- 悬浮底栏模式（floating vs regular） ---- */
  var TABBAR_KEY = 'netops_tabbar';

  function getTabbarMode() {
    try { return localStorage.getItem(TABBAR_KEY) || 'float'; } catch(e) { return 'float'; }
  }
  function setTabbarMode(mode) {
    lsSet(TABBAR_KEY, mode || 'float');
    d.body.classList.toggle('tabbar-regular', mode === 'regular');
    d.body.classList.toggle('tabbar-float', mode !== 'regular');
    updateTabbarLabel(mode);
    positionTabPill();
  }

  function updateTabbarLabel(mode) {
    var lbl = $('#tabbarModeLbl');
    if (!lbl) return;
    lbl.textContent = mode === 'regular' ? '常规全宽' : '悬浮胶囊';
  }

  function initTabbarMode() {
    var sw = $('#swTabbar');
    var row = $('#tabbarModeRow');
    if (!sw || !row) return;

    var cur = getTabbarMode();
    setTabbarMode(cur); /* apply class on body */

    row.addEventListener('click', function (e) {
      e.stopPropagation();
      var next = getTabbarMode() === 'float' ? 'regular' : 'float';
      setTabbarMode(next);
      sw.classList.toggle('is-on', next === 'float');
      U.toast(next === 'float' ? '悬浮底栏已开启' : '常规底栏已开启', 'ok');
    });
  }

  /* ---------------- 动画速度（9 档：0.5x ~ 3x） ----------------
     覆写 --t-fast/base/slow 与硬编码收口的 --t-morph/--t-pill/--t-view/
     --t-enter/--t-toast，让全站过渡与动画时长统一缩放。 */
  var SPEED_STEPS = [
    { v: .5,  l: '0.5x'  }, { v: .75, l: '0.75x' }, { v: 1,   l: '1x'    },
    { v: 1.25, l: '1.25x' }, { v: 1.5, l: '1.5x'  }, { v: 1.75, l: '1.75x' },
    { v: 2,   l: '2x'    }
  ];
  var SPEED_KEY = 'netops_speed';
  var SPEED_BASE = {
    '--t-fast': .18, '--t-base': .30, '--t-slow': .46,
    '--t-morph': .36, '--t-pill': .42, '--t-view': .50,
    '--t-enter': .72, '--t-toast': .70
  };
  function getSpeedIdx() {
    try {
      var i = parseInt(lsGet(SPEED_KEY), 10);
      if (isFinite(i) && i >= 0) {
        /* 旧版存过 2.5x/3x（索引 7/8）被删除后，平滑收敛到最高档（2x） */
        return i < SPEED_STEPS.length ? i : SPEED_STEPS.length - 1;
      }
      return 2;
    } catch (e) { return 2; }
  }
  function applySpeed(idx) {
    var m = SPEED_STEPS[idx].v;
    var st = d.documentElement.style;
    for (var k in SPEED_BASE) {
      st.setProperty(k, (SPEED_BASE[k] * m).toFixed(3) + 's');
    }
    var lbl = $('#speedLbl'); if (lbl) lbl.textContent = SPEED_STEPS[idx].l;
  }
  /* 暴露给 sheet 内 chips 的 inline onclick 调用（必须挂在 window 上，
     sheet 的 innerHTML 里 onclick="speedPick(N)" 在全局作用域查找函数） */
  window.speedPick = function (idx) {
    idx = +idx;
    try { localStorage.setItem(SPEED_KEY, String(idx)); } catch (err) {}
    applySpeed(idx);
    /* 更新 sheet 内 chips 选中态；不关 sheet 让用户继续选/对比 */
    var chips = d.querySelectorAll('#speedSheetBody .chip');
    for (var j = 0; j < chips.length; j++) {
      chips[j].classList.toggle('is-active', j === idx);
    }
    U.toast('动画速度 ' + SPEED_STEPS[idx].l, 'ok');
  };
  /* 暴露档位常量，供 31-views.js 渲染使用 */
  w.SPEED_STEPS = SPEED_STEPS;
  /* sheet 弹出函数挂到 window，供 inline onclick / 手动调用 */
  window.openSpeedSheet = function openSpeedSheet() {
    var cur = getSpeedIdx();
    /* 注意：此处必须用 U.sheet.open（U = w.NetUI），
       UI.sheet 在 32-boot.js 作用域内不存在（此前 ReferenceError 导致打不开） */
    U.sheet.open({
      title: '动画速度',
      body: '<div id="speedSheetBody" class="chips" style="flex-wrap:wrap;gap:8px;padding:4px 4px 10px">' +
        SPEED_STEPS.map(function (s, i) {
          return '<button class="chip' + (i === cur ? ' is-active' : '') +
            '" type="button" onclick="speedPick(' + i + ')">' + s.l + '</button>';
        }).join('') + '</div>',
      foot: '<button class="btn btn--soft btn--block" type="button" data-close>完成</button>'
    });
  };
  function initSpeed() {
    var idx = getSpeedIdx();
    applySpeed(idx);
    /* onclick 属性赋值：天然覆盖旧监听（无重复绑定问题），
       且点击任何子元素（图标/文字/箭头）都会冒泡到 row 触发 */
    var row = d.getElementById('speedRow');
    if (row) {
      row.onclick = function (e) {
        e.preventDefault();
        window.openSpeedSheet();
      };
    }
  }

  function initGlass() {
    var lbl = $('#glassLbl');
    var sheet = $('#glassSheet');
    /* 门禁只认弹窗节点（静态 HTML 里就有）。#glassLbl 属于「我的」页，
       那页是首次进入才构建的——拿它当门禁会让冷启动整段玻璃初始化被跳过，
       表现为「开 App 时玻璃没生效，进一次我的才对」。 */
    if (!sheet) return;

    /* 硬件层降级：启动时判定一次（低端机自动回退到模式 B） */
    deviceDegraded = shouldDegrade();

    var g = getGlass() || glassDefaults;
    applyGlass(g);
    updateGlassLabel(g);

    /* 电量层降级：监听 DeviceBattery（不支持则跳过）；低电且未充电自动切 B */
    if (navigator.getBattery) {
      navigator.getBattery().then(function (b) {
        function sync() {
          batteryDegraded = (b.level <= 0.2 && !b.charging);
          applyGlass(getGlass() || glassDefaults);
          updateGlassLabel(getGlass() || glassDefaults);
          if (batteryDegraded && !userOverrideGlass) {
            U.toast('低电量：已切换为标准毛玻璃以省电', 'ok');
          }
        }
        b.addEventListener('levelchange', sync);
        b.addEventListener('chargingchange', sync);
        sync();
      }).catch(function () {});
    }

    /* 若硬件层降级，启动后轻提示一次（仅一次，避免刷屏） */
    if (deviceDegraded) {
      setTimeout(function () {
        if (!userOverrideGlass) U.toast('当前设备已自动采用标准毛玻璃', 'ok');
      }, 400);
    }

    /* 点击「个性化」行打开弹窗（该行只有「我的」页构建后才存在） */
    if (lbl) lbl.closest('.list__item').addEventListener('click', function (e) {
      if (sheet.classList.contains('is-open')) return;
      e.stopPropagation();
      sheet.classList.add('is-open');
      openCustomSheet = sheet;
      renderGlassPanel();
    });

    /* 关闭弹窗：拖拽条 + 遮罩 */
    setupSheetBackdrop(sheet);
  }

  function updateGlassLabel(g) {
    var lbl = $('#glassLbl');
    if (!lbl) return;
    if (!g || !g.on) { lbl.textContent = '关闭'; return; }
    var em = effectiveGlassMode(g);
    var m = em === 'liquid' ? '液态玻璃' : em === 'gaussian' ? '高斯模糊' : '标准毛玻璃';
    lbl.textContent = m + (em === 'liquid' ? ' · 通透' + tintLabel(g) : ' · 模糊' + g.blur + 'px') +
      (g.hiContrast ? ' · 增强对比' : '');
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
    var s = '# NetOps 2.0 · 网络学习辅助手册\n\n> 离线知识库导出 · ' + new Date().toLocaleString('zh-CN') + '\n\n';
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
          A.S.done = {}; A.S.fav = {}; A.S.quizBest = 0; A.S.quizRuns = 0; A.S.wrong = {};
          A.S.streak = { n: 0, last: '' };
          U.store.set('done', {}); U.store.set('fav', {});
          U.store.set('quizBest', 0); U.store.set('quizRuns', 0); U.store.del('quizWrong');
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
  /* 题目指纹：题干一改就换键，避免旧错题挂到被改过的题上 */
  function qKey(text) {
    var s = String(text), h = 2166136261, i;
    for (i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = (h * 16777619) >>> 0; }
    return h.toString(36);
  }
  function saveWrong() { U.store.set('quizWrong', A.S.wrong); }
  /* 判分后原地刷新错题本卡片：不刷新的话，页面上会留着已经过期的计数 */
  function refreshWrongCard() {
    var el = d.getElementById('wrongCard');
    if (!el || !V.wrongCard) return;
    var tmp = d.createElement('div');
    tmp.innerHTML = V.wrongCard();
    if (tmp.firstElementChild) el.parentNode.replaceChild(tmp.firstElementChild, el);
  }
  function handleQuiz(btn) {
    var act = btn.getAttribute('data-quiz');
    var box = $('#quizBox');
    if (act === 'wrongclear') {
      A.S.wrong = {}; saveWrong(); go('quiz');
      U.toast('错题本已清空', 'ok');
      return;
    }
    if (act === 'start' || act === 'again' || act === 'wrong') {
      var onlyWrong = act === 'wrong';
      var pool = onlyWrong ? QUIZ.filter(function (x) { return !!A.S.wrong[qKey(x.q)]; }) : QUIZ.slice();
      if (!pool.length) { U.toast('错题本是空的，先做一组题', null); return; }
      var n = onlyWrong ? pool.length : parseInt(btn.getAttribute('data-n') || (qzState ? qzState.n : 10), 10);
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
      var right = 0, fixed = 0, missed = 0;
      qzState.items.forEach(function (it, i) {
        var k = qKey(it.q), ok = (qzState.answers[i] === it.a);
        if (ok) { right++; if (A.S.wrong[k]) { delete A.S.wrong[k]; fixed++; } }
        else {
          A.S.wrong[k] = { m: it.m || '', n: ((A.S.wrong[k] && A.S.wrong[k].n) || 0) + 1 };
          missed++;
        }
      });
      saveWrong();
      var pct = Math.round(right / qzState.items.length * 100);
      qzState.sub = true;
      box.innerHTML = '<div class="card" data-accent="' + (pct >= 80 ? 'emerald' : pct >= 60 ? 'amber' : 'rose') + '">' +
        '<div style="padding:14px;display:flex;align-items:center;gap:14px">' +
        '<div class="ring" style="--p:' + pct + ';width:76px;height:76px"><div class="ring__val">' +
        '<div class="ring__num" style="font-size:19px">' + pct + '</div><div class="ring__lbl">分</div></div></div>' +
        '<div><div class="t-md bold">' + (pct >= 80 ? '优秀，稳了' : pct >= 60 ? '及格，还得练' : '基础不牢，回去看模块') + '</div>' +
        '<div class="t-xs t-mute" style="margin-top:3px">答对 ' + right + ' / ' + qzState.items.length + ' 题' +
        (missed || fixed ? ' · 错题本 +' + missed + (fixed ? ' / -' + fixed : '') : '') + '</div></div>' +
        '</div></div>' + V.quizRender(qzState.items, qzState.answers, true);
      refreshWrongCard();
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
  /* 2026-08-12 Wave 3 改造：
     - 数据源全量：字典(含'-'字段) + 知识模块全部命令行 + 故障案例命令 + COMMON_CMDS 内置
     - 匹配：精确 → 前缀联想列表 → 模糊近似 → 建议
     - help 分组；历史 localStorage 持久化；快速按钮动态生成 */
  var CLI_KEY = 'netops_cli_hist';
  var cliHist = [], cliPos = -1, cliMode = 'hw', cliKbBound = false;
  try { var _h = localStorage.getItem(CLI_KEY); if (_h) cliHist = JSON.parse(_h) || []; } catch (e) {}
  var CLI_DB = null;

  /* 内置常用命令（高频、贴近真实回显） */
  var COMMON_CMDS = [
    { cmd: 'ping', plat: 'all', fn: '网络连通性测试', out: 'PING 8.8.8.8 (8.8.8.8): 56 data bytes\n64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=12.3 ms\n--- 8.8.8.8 ping statistics ---\n1 packets transmitted, 1 received, 0% packet loss' },
    { cmd: 'tracert', plat: 'all', fn: '路由追踪', out: 'traceroute to 8.8.8.8 (8.8.8.8), 30 hops max\n 1  192.168.1.1   1.2 ms\n 2  10.0.0.1     3.4 ms\n 3  8.8.8.8     12.3 ms' },
    { cmd: 'nslookup', plat: 'all', fn: 'DNS 查询', out: 'Server:  192.168.1.1\nAddress: 192.168.1.1#53\n\nName:    www.example.com\nAddress: 93.184.216.34' },
    { cmd: 'curl', plat: 'all', fn: 'HTTP 请求', out: 'HTTP/1.1 200 OK\nContent-Type: text/html; charset=utf-8\n\n<!DOCTYPE html><html>...' },
    { cmd: 'tcpdump', plat: 'all', fn: '抓包', out: 'tcpdump: verbose output suppressed\n22:15:33.123456 IP 192.168.1.100.443 > 45.33.2.1.52344: Flags [P.], seq 1:517, ack 1, win 501' },
    { cmd: 'mtr', plat: 'all', fn: '网络诊断(ping+traceroute 结合)', out: 'HOST            Loss%  Snt  Last  Avg  Best  Wrst  StDev\n1. 192.168.1.1   0.0%   10   1.2   1.4  1.1   2.3   0.3\n2. 10.0.0.1      0.0%   10   3.4   3.6  3.1   4.2   0.4' },
    { cmd: 'ethtool', plat: 'all', fn: '网卡信息', out: 'Settings for eth0:\n    Speed: 1000Mb/s\n    Duplex: Full\n    Link detected: yes' },
    { cmd: 'ipconfig', plat: 'all', fn: 'Windows IP 配置', out: 'Windows IP 配置\n以太网适配器 以太网:\n   IPv4 地址: 192.168.1.100\n   子网掩码: 255.255.255.0\n   默认网关: 192.168.1.1' },
    { cmd: 'ifconfig', plat: 'all', fn: 'Linux 接口配置', out: 'eth0: flags=4163<UP,BROADCAST,RUNNING,MULTICAST>  mtu 1500\n        inet 192.168.1.100  netmask 255.255.255.0  broadcast 192.168.1.255\n        ether 00:0c:29:aa:bb:cc' },
    { cmd: 'netstat -an', plat: 'all', fn: '端口连接状态', out: 'Proto Recv-Q Send-Q Local Address    Foreign Address  State\ntcp   0      0      0.0.0.0:22       0.0.0.0:*         LISTEN\ntcp   0      0      192.168.1.100:443  45.33.2.1:52344  ESTABLISHED' },
    { cmd: 'ss -tulnp', plat: 'all', fn: '监听端口', out: 'Netid  State   Recv-Q Send-Q  Local Address:Port\ntcp    LISTEN  0      128      0.0.0.0:22\ntcp    LISTEN  0      128      0.0.0.0:80' },
    { cmd: 'route print', plat: 'all', fn: 'Windows 路由表', out: 'IPv4 路由表\n活动路由:\n网络目标 0.0.0.0  网关 192.168.1.1  接口 192.168.1.100  跃点数 25' },
    { cmd: 'ip addr', plat: 'lx', fn: 'Linux IP 地址', out: '1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536\n    inet 127.0.0.1/8 scope host lo\n2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500\n    inet 192.168.1.100/24 brd 192.168.1.255 scope global eth0' },
    { cmd: 'ip route', plat: 'lx', fn: 'Linux 路由表', out: 'default via 192.168.1.1 dev eth0\n192.168.1.0/24 dev eth0 proto kernel scope link src 192.168.1.100' },
    { cmd: 'display version', plat: 'hw', fn: '设备版本', out: 'Huawei Versatile Routing Platform Software\nVRP (R) software, Version 8.180 (CE6850 V200R005C10SPC607)\nUptime is 30 days, 4 hours, 12 minutes' },
    { cmd: 'display interface brief', plat: 'hw', fn: '接口状态', out: 'Interface         PHY   Protocol  InUti  OutUti  inErrors  outErrors\nGE0/0/0           up    up        0.01%  0.01%          0          0\nGE0/0/1           down  down      0%     0%            0          0' },
    { cmd: 'display current-configuration', plat: 'hw', fn: '查看当前配置', out: 'sysname CE6850-1\ninterface Vlanif10\n ip address 192.168.10.1 255.255.255.0\ninterface GigabitEthernet0/0/1\n port link-type trunk\n...' },
    { cmd: 'kubectl get pods', plat: 'k8s', fn: 'K8s 查看 Pod', out: 'NAME                     READY  STATUS   RESTARTS  AGE\nnginx-7c9bc7c7b7-abc12   1/1    Running  0         3d2h' },
    { cmd: 'kubectl get svc', plat: 'k8s', fn: 'K8s 查看 Service', out: 'NAME     TYPE        CLUSTER-IP  EXTERNAL-IP  PORT(S)  AGE\nnginx    ClusterIP   10.96.0.10  <none>       80/TCP   3d' },
    { cmd: 'kubectl get nodes', plat: 'k8s', fn: 'K8s 查看节点', out: 'NAME    STATUS  ROLES          AGE  VERSION\nnode-1  Ready   control-plane  30d  v1.28.2\nnode-2  Ready   <none>         30d  v1.28.2' }
  ];

  function buildCliDb() {
    if (CLI_DB) return CLI_DB;
    var R = w.NetOpsCliRules || { normalize: function (x) { return String(x || '').trim(); }, isCommand: function (x) { return !!x; }, detectPlat: function () { return null; } };
    var db = [], seen = {};
    function push(cmd, raw, fn, plat, out) {
      var k = String(cmd).toLowerCase().trim();
      if (!k || k.length < 2) return;
      if (seen[k]) { if (out && !seen[k].out) { seen[k].out = out; seen[k].raw = raw; } return; }
      var it = { cmd: k, raw: raw || cmd, fn: fn, plat: plat || 'hw', out: out };
      seen[k] = it; db.push(it);
    }
    /* 1. 字典：只收真正的命令。
       旧写法在某家为 '-' 时拿中文功能名顶上（37 处），会造出「接口描述」这种
       根本敲不出来的"命令"，比缺条目更坏；关键字检索由字典页自己负责。 */
    CORE.dict.rows.forEach(function (r) {
      ['hw', 'cs', 'zte', 'lx'].forEach(function (k) {
        var n = R.normalize(r[k]);
        if (!R.isCommand(n)) return;
        push(n, r[k], r.fn, k);
      });
    });
    /* 2/3. 知识模块与故障案例命令：逐行清洗后按命令自身判平台
       （以前整批硬编码 'hw'，kubectl / git / tc / uname 都被标成华为） */
    function collect(text, fnLabel, out) {
      String(text || '').split('\n').forEach(function (line) {
        var n = R.normalize(line);
        if (!R.isCommand(n)) return;
        push(n, line, fnLabel, R.detectPlat(n) || 'hw', out);
      });
    }
    A.MODS.forEach(function (m) { collect(m.c, A.cleanTitle(m.t), m.o); });
    CORE.faults.forEach(function (f) { collect(f.c, f.t, f.o); });
    /* 4. 内置常用命令（带平台标记，回显贴近真实输出） */
    COMMON_CMDS.forEach(function (x) {
      var n = R.normalize(x.cmd);
      if (!R.isCommand(n)) return;
      push(n, x.cmd, x.fn, x.plat || R.detectPlat(n) || 'lx', x.out);
    });
    CLI_DB = db;
    return db;
  }
  function saveHist() {
    try { localStorage.setItem(CLI_KEY, JSON.stringify(cliHist.slice(-50))); } catch (e) {}
  }
  /* 快速命令分组(优化4):按平台过滤展示 */
  var QUICK_GROUPS = [
    { k: 'all', l: '常用' }, { k: 'hw', l: 'VRP' }, { k: 'lx', l: 'Linux' }, { k: 'k8s', l: 'K8s' }
  ];
  var quickGroup = 'all';
  function renderQuick() {
    var quick = $('#cliQuick');
    if (!quick) return;
    var cmds = COMMON_CMDS.filter(function (x) { return quickGroup === 'all' || x.plat === quickGroup; });
    quick.innerHTML =
      '<div class="cli-qg">' + QUICK_GROUPS.map(function (g) {
        return '<button class="chip' + (g.k === quickGroup ? ' is-active' : '') + '" type="button" data-qg="' + g.k + '">' + g.l + '</button>';
      }).join('') + '</div>' +
      '<div class="cli-qb">' + cmds.slice(0, 8).map(function (x) {
        return '<button class="chip" type="button" data-cli="' + esc(x.cmd) + '">' + esc(x.cmd) + '</button>';
      }).join('') + '</div>';
  }
  function initCli() {
    var out = $('#cliOut'), inp = $('#cliIn'), btn = $('#cliRun'), quick = $('#cliQuick');
    if (!out) return;
    cliPrint(out, 'NetOps CLI 模拟器 v2.0 · 完全离线 · 加载命令库…', 'info');
    /* 懒加载(优化8):异步构建命令库,避免首屏卡顿 */
    var build = function () {
      var n = buildCliDb().length;
      renderQuick();
      cliPrint(out, '命令库就绪 · ' + n + ' 条命令', 'info');
    };
    if (w.requestIdleCallback) w.requestIdleCallback(build, { timeout: 300 });
    else setTimeout(build, 0);
    cliPrint(out, '输入 help 查看分组帮助，mode hw|cs|zte|lx 切换平台，前缀输入可联想命令。', 'dim');
    quick.onclick = function (e) {
      var g = e.target.closest('[data-qg]');
      if (g) { quickGroup = g.getAttribute('data-qg'); renderQuick(); return; }
      var b = e.target.closest('[data-cli]'); if (!b) return;
      inp.value = b.getAttribute('data-cli'); runCli();
    };
    if (!cliKbBound) {
      cliKbBound = true;
      var toBottom = function () {
        var o = $('#cliOut');
        if (o) o.scrollTop = o.scrollHeight;
      };
      w.addEventListener('netops:keyboard', function () { setTimeout(toBottom, 160); });
      w.addEventListener('resize', function () { setTimeout(toBottom, 60); });
    }
    btn.onclick = runCli;
    inp.onkeydown = function (e) {
      if (e.key === 'Enter') { runCli(); return; }
      if (e.key === 'ArrowUp') { if (cliPos > 0) inp.value = cliHist[--cliPos]; e.preventDefault(); }
      if (e.key === 'ArrowDown') { if (cliPos < cliHist.length - 1) inp.value = cliHist[++cliPos]; e.preventDefault(); }
    };
    function runCli() {
      var v = inp.value.trim(); if (!v) return;
      cliHist.push(v); cliPos = cliHist.length; saveHist();
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
    var low = v.toLowerCase().trim();
    if (low === 'clear' || low === 'cls') { out.innerHTML = ''; return; }
    if (low === 'help' || low === '?') {
      cliPrint(out, '可用指令：', 'info');
      cliPrint(out, '  help / ?            分组帮助', 'dim');
      cliPrint(out, '  clear               清屏', 'dim');
      cliPrint(out, '  mode hw|cs|zte|lx   切换平台（当前 ' + cliMode + '）', 'dim');
      cliPrint(out, '  find <关键词>       搜索命令库', 'dim');
      cliPrint(out, '  输入命令前缀可联想（如 display / kubectl / ip）', 'dim');
      cliPrint(out, '— 常用命令示例 —', 'dim');
      ['display version', 'display ip interface brief', 'display vlan', 'display ospf peer',
       'ping', 'tracert', 'ip addr', 'ip route', 'netstat -an', 'ss -tulnp',
       'kubectl get pods', 'kubectl get svc', 'curl', 'tcpdump'].forEach(function (c) {
        cliPrint(out, '  ' + c, 'info');
      });
      cliPrint(out, '（共 ' + buildCliDb().length + ' 条命令，精确匹配 → 前缀联想 → 模糊近似）', 'dim');
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
    var exact = null, prefix = [];
    db.forEach(function (x) {
      if (x.cmd === low) { if (!exact || x.out) exact = x; }
      else if (x.cmd.indexOf(low) === 0) prefix.push(x);
    });
    if (exact) {
      if (exact.out) { exact.out.split('\n').forEach(function (l) { cliPrint(out, l, 'ok'); }); }
      else { cliPrint(out, '[模拟] ' + exact.fn + ' —— 该命令在真机上执行后会返回对应配置/状态信息。', 'info'); }
      return;
    }
    if (prefix.length) {
      cliPrint(out, '匹配 ' + prefix.length + ' 条命令（输入完整命令查看回显）：', 'info');
      prefix.slice(0, 12).forEach(function (x) { cliPrint(out, '  ' + x.raw.split('\n')[0] + '   # ' + x.fn, 'ok'); });
      return;
    }
    var fuzzy = db.filter(function (x) { return x.cmd.indexOf(low) >= 0; }).slice(0, 8);
    if (fuzzy.length) {
      cliPrint(out, '未找到精确命令，近似匹配：', 'dim');
      fuzzy.forEach(function (x) { cliPrint(out, '  ' + x.raw.split('\n')[0] + '   # ' + x.fn, 'info'); });
      return;
    }
    cliPrint(out, "Error: Unrecognized command found at '^' position.", 'err');
    var near = db.filter(function (x) { return x.cmd.indexOf(low.split(' ')[0]) >= 0; }).slice(0, 3);
    if (near.length) { cliPrint(out, '你是不是想输入：', 'dim'); near.forEach(function (x) { cliPrint(out, '  ' + x.raw.split('\n')[0], 'info'); }); }
  }

  /* ---------------- 数据备份导出/导入(优化5) ---------------- */
  /* 个性化项历史上存在非前缀的裸键里，store 的 keys() 看不到它们 → 备份会静默漏掉玻璃参数、
     标签栏形态、速度档、壁纸选择与 CLI 历史。导出时一并取出，导入时逐项验形。
     唯一例外：自选壁纸图片本体（最大约 1.8MB 的 dataURL）不进文本备份，
     只记「当前用的是自定义壁纸」，恢复后由 restoreWallpaper 提示重选。 */
  var RAW_BACKUP = {
    'netops_glass': function (v) {
      if (typeof v !== 'string' || v.length > 4000) return false;
      if (!v) return true;
      try { var o = JSON.parse(v); return !!o && typeof o === 'object' && !Array.isArray(o); } catch (e) { return false; }
    },
    'netops_tabbar': function (v) { return v === 'float' || v === 'regular'; },
    'netops_speed':  function (v) { return typeof v === 'string' && /^\d{1,3}$/.test(v); },
    'netops_wallpaper': function (v) { return typeof v === 'string' && v.length < 400 && v.indexOf('data:') !== 0; },
    'netops_cli_hist': function (v) {
      if (typeof v !== 'string' || v.length > 200000) return false;
      try { var a = JSON.parse(v); return Array.isArray(a) && a.every(function (x) { return typeof x === 'string' && x.length < 400; }); } catch (e) { return false; }
    },
    'netops_swipe_tip': function (v) { return v === '1'; }
  };
  var isRawKey = function (k) { return Object.prototype.hasOwnProperty.call(RAW_BACKUP, k); };

  /* 备份内容的组装：prefixed store 全量 + 裸键（壁纸降级为标记） */
  function buildBackup() {
    var data = {}, n = 0;
    U.store.keys().forEach(function (k) { data[k] = U.store.get(k, null); n++; });
    Object.keys(RAW_BACKUP).forEach(function (k) {
      var v = lsGet(k);
      if (v == null || v === '') return;
      if (k === 'netops_wallpaper' && v.indexOf('data:') === 0) { data[k] = 'custom'; n++; return; }
      data[k] = v; n++;
    });
    /* 自定义壁纸可能只存在于 WP_CUSTOM_KEY：没有选择项时也补一个标记，恢复后才能给出提示 */
    if (data['netops_wallpaper'] === undefined && lsGet(WP_CUSTOM_KEY)) data['netops_wallpaper'] = 'custom';
    return { data: data, n: n };
  }
  A.buildBackup = buildBackup;

  function exportData() {
    var b = buildBackup();
    U.download('NetOps-数据备份-' + new Date().toISOString().slice(0, 10) + '.json', JSON.stringify(b.data, null, 2));
    U.toast('已导出 ' + b.n + ' 项数据', 'ok');
  }
  /* 备份里允许落地的键，以及各自的可信形状 */
  var IMPORT_OK = {
    done:     function (v) { return v && typeof v === 'object' && !Array.isArray(v); },
    fav:      function (v) { return v && typeof v === 'object' && !Array.isArray(v); },
    vendor:   function (v) { return ['hw', 'cs', 'zte', 'lx'].indexOf(v) >= 0; },
    quizBest: function (v) { return typeof v === 'number' && v >= 0 && v <= 100; },
    quizRuns: function (v) { return typeof v === 'number' && v >= 0 && v < 1e6; },
    /* 面试目标岗位画像 */
    jobFocus: function (v) { return v === 'any' || ['数通', '云原生', 'SRE', '安全', '自动化'].indexOf(v) >= 0; },
    /* 错题本：键是题目指纹，值必须是 { m: 字符串锚点, n: 正整数错次 } */
    quizWrong: function (v) {
      if (!v || typeof v !== 'object' || Array.isArray(v)) return false;
      return Object.keys(v).every(function (k) {
        var e = v[k];
        return e && typeof e === 'object' && typeof e.m === 'string' &&
          typeof e.n === 'number' && e.n > 0 && e.n < 1e4;
      });
    },
    streak:   function (v) { return v && typeof v === 'object' && !Array.isArray(v); },
    theme:    function (v) { return ['auto', 'light', 'dark'].indexOf(v) >= 0; },
    motion:   function (v) { return typeof v === 'number' || typeof v === 'string'; },
    /* 按压形变手感参数：只收数值字段，越界由 getSqt 夹紧后再用 */
    sqtune:   function (v) {
      return !!v && typeof v === 'object' && !Array.isArray(v) &&
        Object.keys(v).every(function (k) { return typeof v[k] === 'number' && isFinite(v[k]); });
    }
  };
  function importData(file) {
    var rd = new FileReader();
    rd.onload = function () {
      var data;
      try { data = JSON.parse(rd.result); }
      catch (e) { U.toast('导入失败：不是有效的 JSON 备份', 'danger'); return; }
      if (!data || typeof data !== 'object' || Array.isArray(data)) {
        U.toast('导入失败：备份内容格式不对', 'danger'); return;
      }
      var all = Object.keys(data);
      var keys = all.filter(function (k) { return IMPORT_OK[k] || isRawKey(k); });
      var ignored = all.length - keys.length;
      if (!keys.length) { U.toast('导入失败：备份里没有本应用可恢复的数据', 'danger'); return; }
      var readAny = function (k) { return isRawKey(k) ? lsGet(k) : U.store.get(k, null); };
      var over = keys.filter(function (k) { return readAny(k) != null; }).length;
      /* 旧实现是「选完文件直接覆盖 + reload」，挑错文件进度就没了，且没有任何确认。 */
      var q = '确认恢复 ' + keys.length + ' 项数据？' +
        (over ? '其中 ' + over + ' 项会覆盖当前内容。' : '') +
        (ignored ? ' 另有 ' + ignored + ' 项非本应用数据会被忽略。' : '');
      if (!w.confirm(q)) { U.toast('已取消导入，未改动任何数据', null); return; }
      /* 先留一份可回滚快照（放在 store 前缀之外，不会被导出/再导入污染） */
      var snap = {};
      keys.forEach(function (k) { snap[k] = readAny(k); });
      lsSet('netops_pre_import', JSON.stringify(snap));
      var n = 0, bad = 0;
      keys.forEach(function (k) {
        var okShape = isRawKey(k) ? RAW_BACKUP[k](data[k]) : IMPORT_OK[k](data[k]);
        if (!okShape) { bad++; return; }
        if (isRawKey(k)) { lsSet(k, data[k]); } else { U.store.set(k, data[k]); }
        n++;
      });
      if (!n) { U.toast('导入失败：备份里的每一项都不合法', 'danger'); return; }
      var wpBack = data['netops_wallpaper'] === 'custom';
      U.toast('已恢复 ' + n + ' 项' + (bad ? '，忽略 ' + bad + ' 项异常值' : '') +
        (wpBack ? '；自选壁纸图片本体不进文本备份，请重新选一次' : '') + '，即将刷新', 'ok');
      setTimeout(function () { try { location.reload(); } catch (e) {} }, 900);
    };
    rd.onerror = function () { U.toast('读取文件失败', 'danger'); };
    rd.readAsText(file);
  }
  w.exportData = exportData;
  w.importData = importData;

  /* ---------------- 学习页左滑引导(优化7):一次性提示 ---------------- */
  var SWIPE_TIP_KEY = 'netops_swipe_tip';
  function showSwipeTipOnce() {
    try { if (localStorage.getItem(SWIPE_TIP_KEY)) return; localStorage.setItem(SWIPE_TIP_KEY, '1'); } catch (e) {}
    var tip = d.createElement('div');
    tip.className = 'swipe-tip';
    tip.textContent = '左滑（从左侧向右滑）呼出目录';
    d.body.appendChild(tip);
    setTimeout(function () {
      tip.classList.add('is-hide');
      setTimeout(function () { try { tip.remove(); } catch (e) {} }, 450);
    }, 3600);
  }

  /* ---------------- 滚动联动 ---------------- */
  var lastTop = 0;
  function onScroll() {
    var t = scroll.scrollTop;
    appbar.classList.toggle('is-scrolled', t > 4);
    fab.classList.toggle('is-show', t > 620);
    lastTop = t;
    /* 2026-08-12 17:43：移除 is-scrolling 动态添加 —— 滚动时强行切 backdrop-filter
       反而造成"白雾闪烁"。滚动区域保留 backdrop-filter 稳定渲染。 */
  }

  /* ---------------- 学习页任意位置"左滑"（从左往右滑）呼出目录 ----------------
     用户 2026-08-12 01:20 明确：「左滑是从左往右滑，不是从右往左滑」
     —— 手指从左侧向右移动（dx > 0）触发，方向修正！
     1. 仅「学习」界面(cur.r === 'learn')生效，其他界面完全不参与
     2. 整个界面所有位置（window 级 touch 监听，不限于边缘——
        中间区域 swipe right 不触发系统返回，无冲突）
     3. 只用 touch 事件（Android WebView 兼容）+ touchmove passive:false
     4. 只在判定成功（dx > TH）时 preventDefault，垂直滚动放行
     5. 触发时 console.log + toast 调试反馈 */
  /* 2026-08-12 18:57 用户反馈：左滑手势在二级菜单/其他模块被屏蔽。
     期望：进入二级菜单或非学习页时，左滑 = 返回上一级（back），
     而不是只有学习页根界面才响应。
     逻辑：
     - 任何页面监听手势
     - 左滑(dx>TH)：目录开着→关目录；学习页根界面→呼出目录；其他(二级菜单/其他模块)→ back()
     - 右滑(dx<-TH)：目录开着→关目录（原行为保留） */
  function bindLearnSwipeOpen(openFn) {
    /* 2026-08-12 19:20：防重复绑定（WebView restoreState 后 boot 可能重复执行） */
    if (w.__swipeBound) return;
    w.__swipeBound = true;
    var TH = 55;
    var sx = 0, sy = 0, active = false, fired = false;
    function start(e) {
      var t = e.touches ? e.touches[0] : e;
      sx = t.clientX; sy = t.clientY;
      active = true; fired = false;
    }
    function move(e) {
      if (!active || fired) return;
      var t = e.touches ? e.touches[0] : e;
      if (!t) return;
      var dx = t.clientX - sx, dy = t.clientY - sy;
      /* 垂直滚动为主：放行滚动、放弃手势（防滚动误触） */
      if (Math.abs(dy) * 1.2 > Math.abs(dx)) { active = false; return; }
      var opened = drawerCtl && drawerCtl.isOpen && drawerCtl.isOpen();
      if (dx > TH) { /* 左滑（从左往右滑） */
        fired = true; active = false;
        if (e.cancelable) e.preventDefault();
        if (opened) {
          if (w.console) console.log('[swipe] 左滑关目录', dx);
          openFn();
        } else if (closeOpenCard()) {
          /* 页面有展开的卡片(二级菜单) → 收起卡片 */
          if (w.console) console.log('[swipe] 左滑收起卡片', dx);
        } else if (cur.r === 'learn' && !stack.length) {
          if (w.console) console.log('[swipe] 左滑呼出目录', dx);
          U.toast('呼出目录', 'ok');
          openFn();
        } else {
          /* 二级导航 / 其他模块 → 统一走 handleBackOrExit()
             （返回上一级；根界面 → Toast + 2 秒二次确认,与原生返回键一致） */
          if (w.console) console.log('[swipe] 左滑 handleBackOrExit', dx, cur.r, stack.length);
          handleBackOrExit();
        }
      } else if (dx < -TH && opened) {
        fired = true; active = false;
        if (e.cancelable) e.preventDefault();
        if (w.console) console.log('[swipe] 右滑关目录', dx);
        openFn();
      }
    }
    function end() { active = false; fired = false; }
    /* 当前页面是否有展开的折叠卡片（二级菜单为 DOM 展开，非 stack 导航） */
    function closeOpenCard() {
      var oc = d.querySelector('.card.is-open');
      if (oc) { oc.classList.remove('is-open'); return true; }
      return false;
    }
    w.addEventListener('touchstart', start, { passive: true });
    w.addEventListener('touchmove', move, { passive: false });
    w.addEventListener('touchend', end, { passive: true });
    w.addEventListener('touchcancel', end, { passive: true });
  }

  /* ---------------- 启动 ---------------- */
  function boot() {
    /* 2026-08-12 19:20：防重复执行 —— Android WebView 在 Activity 重建/restoreState
       后 DOM 已加载，else 分支会再执行一次 boot()，导致手势/tab/scroll 事件重复绑定，
       第二次进应用手势和 toast 失效。加一次性标志。 */
    if (w.__netopsBooted) return;
    w.__netopsBooted = true;
    view = $('#view'); scroll = $('#scroll'); appbar = $('#appbar');
    barName = $('#barName'); barSub = $('#barSub'); barFill = $('#barFill');
    navUse = $('#navUse'); themeUse = $('#themeUse');
    drawer = $('#drawer'); scrim = $('#scrim'); edge = $('#edge'); fab = $('#fab');

    U.theme.apply(); U.motion.apply(); syncThemeIcon();
    /* 恢复动画速度（--t-* 覆写，首屏即生效） */
    initSpeed();
    /* 壁纸/玻璃弹窗挂到 body（不在 view 内）：避免 view-spring-in 的 transform
       破坏 fixed 定位（用户 2026-08-12「快速下滑 sheet 错位」根因），且不随
       view 重渲染销毁节点。仅挂载一次。 */
    if (w.SHEET_MARKUP && !d.getElementById('wpSheet')) {
      var _sd = d.createElement('div');
      _sd.innerHTML = w.SHEET_MARKUP;
      while (_sd.firstChild) d.body.appendChild(_sd.firstChild);
    }
    /* 恢复底栏模式 */
    var tbm = getTabbarMode();
    d.body.classList.toggle('tabbar-regular', tbm === 'regular');
    d.body.classList.toggle('tabbar-float', tbm !== 'regular');
    /* 共享药丸要跟着视口重算：平板限宽 860px 会居中，格宽不等于五分之一 */
    positionTabPill();
    w.addEventListener('resize', positionTabPill);
    /* 液态玻璃：降级判定 + 立即应用。必须在这里跑——「我的」页是懒建的，
       原先只有进一次「我的」才会触发，导致冷启动时玻璃停在样式表默认值上。 */
    initGlass();
    /* 高光跟随手指（镜面折射）——降级 / 省电动效偏好下不启用 */
    if (!deviceDegraded) initGlassLight();
    initSquish();   /* 自带降级判断与重复绑定保护 */
    try {
      if (w.matchMedia) w.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
        if (U.theme.get() === 'auto') { U.theme.apply(); applyGlass(getGlass() || glassDefaults); syncThemeIcon(); }
      });
    } catch (e) {}

    drawerCtl = U.bindDrawer(drawer, scrim, edge);
    /* 2026-08-12:学习页任意位置左滑呼出目录（非边缘手势——
       边缘手势与 Android 系统返回冲突，用户反馈易误触退出软件）。
       对称手势：未开 → 左滑开；已开 → 右滑关（防止误触退出） */
    bindLearnSwipeOpen(function () {
      if (drawerCtl.isOpen()) drawerCtl.close();
      else drawerCtl.open();
    });
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
      /* 阻止点击时按钮抢焦点引发的“焦点自动滚动”（底部栏被顶起 / 内容下移） */
      b.addEventListener('mousedown', function (e) { e.preventDefault(); });
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
