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
  }

  /* ---------------- 高光跟随手指（液态玻璃动态反馈） ----------------
     把手指/指针位置写入 --mx/--my（百分比），驱动所有玻璃表面
     的 --glass-specular-dyn 镜面高光随手指移动，模拟真实光线折射。
     仅在 glass-on 时生效；用 rAF 节流避免低端机掉帧。 */
  function initGlassLight() {
    var root = document.documentElement;
    var ticking = false, lx = 50, ly = -8;
    function apply() {
      ticking = false;
      root.style.setProperty('--mx', lx + '%');
      root.style.setProperty('--my', ly + '%');
    }
    function onMove(e) {
      /* 降级设备 / 低电量 / 省电动效偏好：不写入高光位置，保持静态 */
      if (deviceDegraded || batteryDegraded) return;
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
    document.documentElement.style.setProperty('--wallpaper', 'url(' + u + ')');
    d.body.style.background = 'url(' + u + ')';
    d.body.style.backgroundSize = 'cover';
    d.body.style.backgroundPosition = 'center';
    d.body.style.backgroundAttachment = 'fixed';
    d.body.classList.add('has-wallpaper');
  }

  function initWallpaper() {
    var lbl = $('#wallpaperLbl');
    var sheet = $('#wpSheet');
    if (!lbl || !sheet) return;

    var cur = localStorage.getItem(WP_KEY) || 'none';
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
      var curId = localStorage.getItem(WP_KEY) || 'none';
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
    localStorage.setItem(WP_KEY, dataUrl);
    applyWallpaper('custom', dataUrl);
    U.toast('壁纸已设置', 'ok');
  };

  function applyWallpaper(id, cssValue) {
    localStorage.setItem(WP_KEY, id || 'none');
    if (!cssValue || id === 'none') {
      document.documentElement.style.setProperty('--wallpaper', 'none');
      d.body.style.background = '';
      d.body.classList.remove('has-wallpaper');
    } else if (cssValue === 'wp') {
      /* 内置壁纸:按 id 查清单,设置相对路径背景 */
      var wp = findWp(id);
      if (wp) setWallpaperBg(wp);
    } else {
      var cssUrl = 'url(' + cssValue + ')';
      document.documentElement.style.setProperty('--wallpaper', cssUrl);
      d.body.style.background = cssUrl;
      d.body.style.backgroundSize = 'cover';
      d.body.style.backgroundPosition = 'center';
      d.body.style.backgroundAttachment = 'fixed';
      d.body.classList.add('has-wallpaper');
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
    var saved = localStorage.getItem(WP_KEY);
    if (!saved || saved === 'none') return;
    if (saved.indexOf('wp-') === 0) {
      var wp = findWp(saved);
      if (wp) { setWallpaperBg(wp); return; }
    }
    d.body.classList.add('has-wallpaper');
    if (saved.startsWith('data:')) {
      document.documentElement.style.setProperty('--wallpaper', 'url(' + saved + ')');
      d.body.style.background = 'url(' + saved + ')';
      d.body.style.backgroundSize = 'cover';
      d.body.style.backgroundPosition = 'center';
      d.body.style.backgroundAttachment = 'fixed';
    }
  })();

  /* ---------------- 涛态玻璃 / 毛玻璃效果（三模式：关闭/毛玻璃/液态玻璃） ---------------- */
  var GLASS_KEY = 'netops_glass';
  var glassDefaults = {
    on: true,           /* 总开关 */
    mode: 'liquid',     /* A=液态玻璃(默认,通透+高光)  B=标准毛玻璃(frosted,扁平省电)  gaussian=纯模糊(极致省电档) */
    blur: 14,           /* 模糊强度 8-20 px（业界甜区） */
    tint: 55            /* 液态玻璃通透度 20-90（% 不透明度，越低越通透） */
  };

  function getGlass() {
    try { var s = localStorage.getItem(GLASS_KEY); return s ? JSON.parse(s) : null; } catch(e) { return null; }
  }
  function saveGlass(g) { localStorage.setItem(GLASS_KEY, JSON.stringify(g)); }

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
    root.style.setProperty('--glass-blur', g.blur + 'px');
    root.style.setProperty('--glass-blur-strong', Math.round(g.blur * 1.4) + 'px');
    /* 液态玻璃通透度：由 g.tint(20-90%) 计算浮层渐变 alpha */
    var a = Math.max(.10, Math.min(.95, (g.tint || 55) / 100));
    root.style.setProperty('--glass-tint-top', 'rgba(255,255,255,' + a.toFixed(3) + ')');
    root.style.setProperty('--glass-tint-bot', 'rgba(250,250,252,' + Math.max(.05, a - .13).toFixed(3) + ')');
    d.body.classList.toggle('glass-on', g.on);
    d.body.classList.toggle('glass-liquid', g.on && em === 'liquid');
    d.body.classList.toggle('glass-gaussian', g.on && em === 'gaussian');
    d.body.classList.toggle('glass-frosted', g.on && em === 'frosted');
    /* 高斯模式：饱和度 100%（纯模糊，不额外饱和） */
    if (g.on) {
      root.style.setProperty('--glass-saturate', em === 'gaussian' ? '100%' : '180%');
    }
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
      h += '<div style="display:flex;gap:8px;background:var(--surface-2);border-radius:var(--r-md);padding:6px">';
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
        液态玻璃本身 0 模糊，通透度由 g.tint 控制；滑块不锁死，切模式即时换语义 */
      var isLiquid = (g.on && effectiveGlassMode(g) === 'liquid');
      var sKey = isLiquid ? 'tint' : 'blur';
      var sMin = isLiquid ? 10 : 8;
      var sMax = isLiquid ? 95 : 20;
      var sVal = isLiquid ? (g.tint || 55) : g.blur;
      var sLbl = isLiquid ? '通透度' : '模糊强度';
      var sSfx = isLiquid ? '%' : 'px';
      h += '<div style="margin-top:16px"><div class="row" style="justify-content:space-between;margin-bottom:8px">' +
        '<span class="t-xs bold" style="color:var(--text-2)">' + sLbl + '</span>' +
        '<span class="t-xs mono" style="color:var(--accent)" id="glassVal_' + sKey + '">' + sVal + sSfx + '</span></div>' +
        '<input type="range" class="glass-slider" min="' + sMin + '" max="' + sMax + '" step="' + (isLiquid ? 5 : 1) + '" value="' + sVal + '" data-glass="' + sKey + '" style="width:100%"></div>' +
      (isLiquid ?
        '<div class="t-xs" style="margin-top:8px;padding:8px 10px;border-radius:var(--r-sm);background:var(--accent-soft);color:var(--accent-text);line-height:1.55">' +
        '通透度 = 浮层不透明度（越低越透，壁纸越清晰可见）。液态玻璃为 0 模糊（通透感由本滑块控制）。</div>' :
        '');

      h += '<div class="t-xs t-mute" style="margin-top:14px;padding:10px;background:var(--surface-2);border-radius:var(--r-sm);line-height:1.65">' +
        (isLiquid ? '提示：设一张背景壁纸后，通透度变化更明显。推荐 40-60% 平衡通透与可读性。' :
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

    /* 绑定事件：滑块 */
    body.querySelectorAll('.glass-slider').forEach(function (input) {
      var key = input.getAttribute('data-glass');
      function apply() {
        var val = parseInt(input.value, 10);
        g[key] = val;
        $('#glassVal_' + key).textContent = val + (key === 'tint' ? '%' : 'px');
        saveGlass(g); applyGlass(g);
        updateGlassLabel(g);
      }
      /* input + change 双绑定：旧 WebView 拖动 range 可能只触发 change */
      input.addEventListener('input', apply);
      input.addEventListener('change', apply);
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
    localStorage.setItem(TABBAR_KEY, mode || 'float');
    d.body.classList.toggle('tabbar-regular', mode === 'regular');
    d.body.classList.toggle('tabbar-float', mode !== 'regular');
    updateTabbarLabel(mode);
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
      var i = parseInt(localStorage.getItem(SPEED_KEY), 10);
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
    if (!lbl || !sheet) return;

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

    /* 点击「个性化」行打开弹窗 */
    lbl.closest('.list__item').addEventListener('click', function (e) {
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
    lbl.textContent = m + (em === 'liquid' ? ' · 通透' + (g.tint || 55) + '%' : ' · 模糊' + g.blur + 'px');
  }
  (function restoreWallpaper() {
    var saved = localStorage.getItem(WP_KEY);
    if (!saved || saved === 'none') return;
    d.body.classList.add('has-wallpaper');
    if (saved.startsWith('data:')) {
      document.documentElement.style.setProperty('--wallpaper', 'url(' + saved + ')');
      d.body.style.background = 'url(' + saved + ')';
      d.body.style.backgroundSize = 'cover';
      d.body.style.backgroundPosition = 'center';
      d.body.style.backgroundAttachment = 'fixed';
    }
  })();

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
    var db = [], seen = {};
    function push(cmd, raw, fn, plat, out) {
      var k = String(cmd).toLowerCase().trim();
      if (!k || k.length < 2) return;
      if (seen[k]) { if (out && !seen[k].out) { seen[k].out = out; seen[k].raw = raw; } return; }
      var it = { cmd: k, raw: raw || cmd, fn: fn, plat: plat || 'hw', out: out };
      seen[k] = it; db.push(it);
    }
    /* 1. 字典全量：'-' 字段也用功能名建条目，保证关键字搜索有返回 */
    CORE.dict.rows.forEach(function (r) {
      ['hw', 'cs', 'zte', 'lx'].forEach(function (k) {
        var c = r[k];
        push(c && c !== '-' ? c : r.fn, c && c !== '-' ? c : r.fn, r.fn, k);
      });
    });
    /* 2. 知识模块全量命令（每行去提示符，不只第一行） */
    A.MODS.forEach(function (m) {
      if (!m.c) return;
      String(m.c).split('\n').forEach(function (line) {
        var clean = line.replace(/^[<\[][^>\]]*[>\]]\s*/, '').trim();
        if (clean) push(clean, line, A.cleanTitle(m.t), 'hw', m.o);
      });
    });
    /* 3. 故障案例命令 */
    CORE.faults.forEach(function (f) {
      if (!f.c) return;
      String(f.c).split('\n').forEach(function (line) {
        var clean = line.replace(/^[<\[][^>\]]*[>\]]\s*/, '').trim();
        if (clean) push(clean, line, f.t, 'hw', f.o);
      });
    });
    /* 4. 内置常用命令（带平台标记，回显贴近真实输出） */
    COMMON_CMDS.forEach(function (x) { push(x.cmd, x.cmd, x.fn, x.plat || 'lx', x.out); });
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
  function exportData() {
    var keys = U.store.keys(), data = {}, n = 0;
    keys.forEach(function (k) { data[k] = U.store.get(k, null); n++; });
    U.download('NetOps-数据备份-' + new Date().toISOString().slice(0, 10) + '.json', JSON.stringify(data, null, 2));
    U.toast('已导出 ' + n + ' 项数据', 'ok');
  }
  function importData(file) {
    var rd = new FileReader();
    rd.onload = function () {
      try {
        var data = JSON.parse(rd.result);
        if (!data || typeof data !== 'object') throw new Error('bad');
        var n = 0;
        Object.keys(data).forEach(function (k) { U.store.set(k, data[k]); n++; });
        U.toast('已恢复 ' + n + ' 项，即将刷新', 'ok');
        setTimeout(function () { try { location.reload(); } catch (e) {} }, 900);
      } catch (e) { U.toast('导入失败：文件格式不正确', 'danger'); }
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
  function bindLearnSwipeOpen(openFn) {
    var TH = 55;
    var sx = 0, sy = 0, active = false, fired = false;
    function start(e) {
      if (cur.r !== 'learn') return;                 /* 只学习页 */
      var t = e.touches ? e.touches[0] : e;
      sx = t.clientX; sy = t.clientY;
      active = true; fired = false;
      if (w.console) console.log('[swipe] start', e.type, cur.r, 'opened=' + (drawerCtl && drawerCtl.isOpen && drawerCtl.isOpen()));
    }
    function move(e) {
      if (!active || fired) return;
      var t = e.touches ? e.touches[0] : e;
      if (!t) return;
      var dx = t.clientX - sx, dy = t.clientY - sy;
      /* 垂直滚动为主：放行滚动、放弃手势（防滚动误触） */
      if (Math.abs(dy) * 1.2 > Math.abs(dx)) { active = false; return; }
      var opened = drawerCtl && drawerCtl.isOpen && drawerCtl.isOpen();
      /* 对称手势：未开 → 左滑(dx > TH)开；已开 → 右滑(dx < -TH)关 */
      if (dx > TH && !opened) {
        fired = true; active = false;
        if (e.cancelable) e.preventDefault();
        if (w.console) console.log('[swipe] FIRE 左滑开', dx);
        U.toast('呼出目录', 'ok');
        openFn();
      } else if (dx < -TH && opened) {
        fired = true; active = false;
        if (e.cancelable) e.preventDefault();
        if (w.console) console.log('[swipe] FIRE 右滑关', dx);
        openFn();
      }
    }
    function end() { active = false; fired = false; }
    w.addEventListener('touchstart', start, { passive: true });
    w.addEventListener('touchmove', move, { passive: false });
    w.addEventListener('touchend', end, { passive: true });
    w.addEventListener('touchcancel', end, { passive: true });
  }

  /* ---------------- 启动 ---------------- */
  function boot() {
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
    try {
      if (w.matchMedia) w.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
        if (U.theme.get() === 'auto') { U.theme.apply(); syncThemeIcon(); }
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
