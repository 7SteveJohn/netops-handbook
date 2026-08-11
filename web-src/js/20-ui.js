/* ============================================================
 * NetOps 2.0 · UI 基础层
 * 工具函数 / 本地存储 / Toast / Sheet / 涟漪 / 手势 / 语法着色
 * ============================================================ */
(function (w, d) {
  'use strict';

  var UI = {};

  /* ---------------- DOM 工具 ---------------- */
  function $(s, r) { return (r || d).querySelector(s); }
  function $$(s, r) { return Array.prototype.slice.call((r || d).querySelectorAll(s)); }
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function icon(id, cls) {
    return '<svg class="icon ' + (cls || '') + '" aria-hidden="true"><use href="#' + id + '"/></svg>';
  }
  function debounce(fn, ms) {
    var t; return function () {
      var a = arguments, c = this;
      clearTimeout(t); t = setTimeout(function () { fn.apply(c, a); }, ms || 180);
    };
  }
  function raf(fn) { return w.requestAnimationFrame ? w.requestAnimationFrame(fn) : setTimeout(fn, 16); }

  UI.$ = $; UI.$$ = $$; UI.esc = esc; UI.icon = icon; UI.debounce = debounce; UI.raf = raf;

  /* ---------------- 触感反馈 ----------------
     优先走原生桥：performHapticFeedback 会尊重系统「触感反馈」开关，
     力度也和系统控件一致；浏览器里退回 navigator.vibrate。 */
  function buzz(ms) {
    var d0 = ms || 8;
    try {
      if (w.NetBridge && w.NetBridge.haptic) { w.NetBridge.haptic(d0); return; }
    } catch (e) {}
    try { if (w.navigator && navigator.vibrate) navigator.vibrate(d0); } catch (e) {}
  }
  UI.buzz = buzz;

  /* ---------------- 本地存储（带内存兜底） ---------------- */
  var MEM = {}, LS = null;
  try { LS = w.localStorage; LS.setItem('__t', '1'); LS.removeItem('__t'); } catch (e) { LS = null; }
  var PFX = 'netops2.';
  var store = {
    get: function (k, dft) {
      try {
        var v = LS ? LS.getItem(PFX + k) : MEM[k];
        return v == null ? dft : JSON.parse(v);
      } catch (e) { return dft; }
    },
    set: function (k, v) {
      var s = JSON.stringify(v);
      try { if (LS) LS.setItem(PFX + k, s); else MEM[k] = s; } catch (e) { MEM[k] = s; }
    },
    del: function (k) { try { if (LS) LS.removeItem(PFX + k); else delete MEM[k]; } catch (e) {} },
    keys: function () {
      var out = [], i, k;
      try {
        if (LS) { for (i = 0; i < LS.length; i++) { k = LS.key(i); if (k.indexOf(PFX) === 0) out.push(k.slice(PFX.length)); } }
        else out = Object.keys(MEM);
      } catch (e) {}
      return out;
    }
  };
  UI.store = store;

  /* ---------------- 主题 ---------------- */
  var THEMES = ['auto', 'light', 'dark'];
  function sysDark() {
    try { return w.matchMedia && w.matchMedia('(prefers-color-scheme: dark)').matches; } catch (e) { return false; }
  }
  function applyTheme(t) {
    var dark = t === 'dark' || (t === 'auto' && sysDark());
    d.documentElement.classList.toggle('dark', dark);
    var meta = $('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', dark ? '#0b1220' : '#ffffff');
    if (w.NetBridge && w.NetBridge.setTheme) { try { w.NetBridge.setTheme(dark ? 'dark' : 'light'); } catch (e) {} }
    return dark;
  }
  UI.theme = {
    list: THEMES,
    get: function () { return store.get('theme', 'auto'); },
    set: function (t) { store.set('theme', t); return applyTheme(t); },
    apply: function () { return applyTheme(store.get('theme', 'auto')); },
    isDark: function () { return d.documentElement.classList.contains('dark'); }
  };

  /* ---------------- 涟漪 ---------------- */
  function ripple(host, x, y) {
    if (!host || host.dataset.noripple != null) return;
    if (d.documentElement.dataset.motion === 'off') return;
    var r = host.getBoundingClientRect();
    var size = Math.max(r.width, r.height) * 1.05;
    var s = d.createElement('span');
    s.className = 'ripple';
    s.style.width = s.style.height = size + 'px';
    s.style.left = ((x == null ? r.width / 2 : x - r.left) - size / 2) + 'px';
    s.style.top = ((y == null ? r.height / 2 : y - r.top) - size / 2) + 'px';
    host.appendChild(s);
    setTimeout(function () { if (s.parentNode) s.parentNode.removeChild(s); }, 580);
  }
  UI.ripple = ripple;

  d.addEventListener('pointerdown', function (e) {
    var t = e.target.closest && e.target.closest('.btn, .ibtn, .chip, .card__head, .tab, .list__item, .term__copy');
    if (t) ripple(t, e.clientX, e.clientY);
  }, { passive: true });

  /* ---------------- Toast ---------------- */
  var toastEl, toastTm;
  function toast(msg, type, ms) {
    if (!toastEl) {
      toastEl = d.createElement('div');
      toastEl.className = 'toast';
      toastEl.setAttribute('role', 'status');
      toastEl.setAttribute('aria-live', 'polite');
      d.body.appendChild(toastEl);
    }
    toastEl.className = 'toast' + (type ? ' toast--' + type : '');
    var ico = type === 'ok' ? 'i-check' : type === 'danger' ? 'i-x' : type === 'warn' ? 'i-alert' : 'i-info';
    toastEl.innerHTML = '<span class="toast__ic">' + icon(ico, '') + '</span><span class="toast__txt">' + esc(msg) + '</span>';
    raf(function () { toastEl.classList.add('is-open'); });
    clearTimeout(toastTm);
    toastTm = setTimeout(function () { toastEl.classList.remove('is-open'); }, ms || 2400);
  }
  UI.toast = toast;

  /* ---------------- 剪贴板 ---------------- */
  function copy(text) {
    return new Promise(function (res) {
      var ok = false;
      try {
        if (w.navigator && navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(function () { res(true); }, function () { res(fallback(text)); });
          return;
        }
      } catch (e) {}
      ok = fallback(text); res(ok);
    });
    function fallback(t) {
      try {
        var ta = d.createElement('textarea');
        ta.value = t;
        ta.style.cssText = 'position:fixed;opacity:0;left:-9999px;top:0';
        d.body.appendChild(ta); ta.select(); ta.setSelectionRange(0, t.length);
        var r = d.execCommand('copy');
        d.body.removeChild(ta);
        return r;
      } catch (e) { return false; }
    }
  }
  UI.copy = copy;

  /* ---------------- 文件下载（Blob，离线可用） ---------------- */
  function download(name, text, mime) {
    try {
      var blob = new Blob(['\ufeff' + text], { type: (mime || 'text/markdown') + ';charset=utf-8' });
      if (w.NetBridge && w.NetBridge.saveFile) {
        w.NetBridge.saveFile(name, text);
        toast('已保存到设备下载目录', 'ok'); return;
      }
      var url = URL.createObjectURL(blob);
      var a = d.createElement('a');
      a.href = url; a.download = name;
      d.body.appendChild(a); a.click();
      setTimeout(function () { URL.revokeObjectURL(url); d.body.removeChild(a); }, 1200);
      toast('已导出 ' + name, 'ok');
    } catch (e) { toast('当前环境不支持导出', 'danger'); }
  }
  UI.download = download;

  /* ---------------- 终端语法着色 ---------------- */
  function hlCmd(src) {
    var s = esc(src);
    s = s.replace(/(^|\n)([ \t]*)(#[^\n]*)/g, '$1$2<span class="tk-cmt">$3</span>');
    s = s.replace(/(^|\n)([ \t]*)(&lt;[^&\n]*?&gt;|\[[^\]\n]*?\]|\$|~[^\n#]*?#)/g,
      '$1$2<span class="tk-kw">$3</span>');
    s = s.replace(/(&quot;[^&\n]*?&quot;|&#39;[^\n]*?&#39;)/g, '<span class="tk-str">$1</span>');
    s = s.replace(/\b(\d{1,3}(?:\.\d{1,3}){3}(?:\/\d{1,2})?)\b/g, '<span class="tk-num">$1</span>');
    /* 独立数字（避免 lookbehind，兼容旧 WebView） */
    s = s.replace(/(^|[\s=:,(\[])(\d+)(?=$|[\s,.)\]/])/g, '$1<span class="tk-num">$2</span>');
    return s;
  }
  function hlOut(src) {
    var s = esc(src);
    s = s.replace(/\b(up|UP|Up|success|Success|permit|Permit|Full|ESTABLISHED|active|Active|Master|OK)\b/g,
      '<span class="tk-cmd">$1</span>');
    s = s.replace(/\b(down|DOWN|Down|fail|Failed|deny|Deny|Error|error|timeout|unreachable|Init|Down)\b/g,
      '<span class="tk-warn">$1</span>');
    s = s.replace(/\b(\d{1,3}(?:\.\d{1,3}){3}(?:\/\d{1,2})?)\b/g, '<span class="tk-num">$1</span>');
    return s;
  }
  UI.hlCmd = hlCmd; UI.hlOut = hlOut;

  var termSeq = 0;
  /* 生成终端块 HTML */
  function term(text, opts) {
    opts = opts || {};
    var id = 't' + (++termSeq);
    var isOut = !!opts.out;
    TERM_BUF[id] = text;
    return '<div class="term' + (isOut ? ' term--out' : '') + '">' +
      '<div class="term__bar">' +
        '<span class="term__dots"><i class="term__dot"></i><i class="term__dot"></i><i class="term__dot"></i></span>' +
        '<span class="term__label">' + esc(opts.label || (isOut ? 'output' : 'command')) + '</span>' +
        (isOut ? '' : '<button class="term__copy" type="button" data-copy="' + id + '">' +
          icon('i-copy', 'icon--xs') + '复制</button>') +
      '</div>' +
      '<pre class="term__body">' + (isOut ? hlOut(text) : hlCmd(text)) + '</pre>' +
    '</div>';
  }
  var TERM_BUF = {};
  UI.term = term;

  d.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('[data-copy]');
    if (!b) return;
    var txt = TERM_BUF[b.getAttribute('data-copy')];
    if (txt == null) return;
    copy(txt).then(function (ok) {
      if (!ok) { toast('复制失败', 'danger'); return; }
      buzz(10);
      b.classList.add('is-done');
      b.innerHTML = icon('i-check', 'icon--xs') + '已复制';
      setTimeout(function () {
        b.classList.remove('is-done');
        b.innerHTML = icon('i-copy', 'icon--xs') + '复制';
      }, 1500);
    });
  });

  /* ---------------- 搜索高亮 ---------------- */
  function hl(text, q) {
    var s = esc(text);
    if (!q) return s;
    var toks = String(q).trim().split(/\s+/).filter(Boolean).slice(0, 4);
    toks.forEach(function (t) {
      var re = new RegExp('(' + t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'gi');
      s = s.replace(re, '\u0001$1\u0002');
    });
    return s.replace(/\u0001/g, '<mark class="hl">').replace(/\u0002/g, '</mark>');
  }
  UI.hl = hl;

  /* ---------------- Bottom Sheet（含下拉手势） ---------------- */
  var sheetEl, sheetScrim, sheetOnClose;
  function buildSheet() {
    sheetScrim = d.createElement('div');
    sheetScrim.className = 'scrim';
    sheetScrim.style.zIndex = '75';
    sheetEl = d.createElement('div');
    sheetEl.className = 'sheet';
    sheetEl.setAttribute('role', 'dialog');
    sheetEl.innerHTML =
      '<div class="sheet__grab" data-grab></div>' +
      '<div class="sheet__head"><div class="sheet__title" data-title></div>' +
      '<div class="grow"></div><button class="ibtn" type="button" data-close>' + icon('i-x') + '</button></div>' +
      '<div class="sheet__body" data-body></div>' +
      '<div class="sheet__foot hidden" data-foot></div>';
    d.body.appendChild(sheetScrim);
    d.body.appendChild(sheetEl);
    sheetScrim.addEventListener('click', closeSheet);
    /* 事件委托：所有 [data-close] 按钮（含 head ✕ 与 foot "完成"）都能关闭；
       querySelector 只匹配第一个，会漏掉调用方塞进 foot 的关闭按钮（2026-08-12 反馈） */
    sheetEl.addEventListener('click', function (e) {
      var t = e.target;
      while (t && t !== sheetEl) {
        if (t.nodeType === 1 && t.hasAttribute && t.hasAttribute('data-close')) { closeSheet(); return; }
        t = t.parentNode;
      }
    });
    dragSheet();
  }
  function openSheet(o) {
    if (!sheetEl) buildSheet();
    o = o || {};
    sheetEl.querySelector('[data-title]').innerHTML = o.title || '';
    var body = sheetEl.querySelector('[data-body]');
    body.innerHTML = o.body || '';
    body.scrollTop = 0;
    var foot = sheetEl.querySelector('[data-foot]');
    foot.innerHTML = o.foot || '';
    foot.classList.toggle('hidden', !o.foot);
    sheetOnClose = o.onClose || null;
    sheetEl.style.transform = '';
    raf(function () {
      sheetScrim.classList.add('is-open');
      sheetEl.classList.add('is-open');
      if (o.onMount) o.onMount(sheetEl);
    });
  }
  function closeSheet() {
    if (!sheetEl) return;
    sheetEl.classList.remove('is-open');
    sheetScrim.classList.remove('is-open');
    sheetEl.style.transform = '';
    if (sheetOnClose) { var f = sheetOnClose; sheetOnClose = null; f(); }
  }
  function sheetOpen() { return sheetEl && sheetEl.classList.contains('is-open'); }
  UI.sheet = { open: openSheet, close: closeSheet, isOpen: sheetOpen, el: function () { return sheetEl; } };

  /* Sheet 下拉手势 */
  function dragSheet() {
    var grab = sheetEl.querySelector('[data-grab]');
    var y0 = 0, dy = 0, on = false, h = 0;
    grab.addEventListener('pointerdown', function (e) {
      on = true; y0 = e.clientY; dy = 0;
      h = sheetEl.getBoundingClientRect().height;
      sheetEl.classList.add('is-dragging');
      grab.setPointerCapture(e.pointerId);
    });
    grab.addEventListener('pointermove', function (e) {
      if (!on) return;
      dy = Math.max(0, e.clientY - y0);
      sheetEl.style.transform = 'translate3d(0,' + dy + 'px,0)';
      sheetScrim.style.opacity = String(Math.max(0, 1 - dy / h));
    });
    function end() {
      if (!on) return;
      on = false;
      sheetEl.classList.remove('is-dragging');
      sheetScrim.style.opacity = '';
      if (dy > Math.min(120, h * 0.28)) { closeSheet(); }
      else { sheetEl.style.transform = ''; }
    }
    grab.addEventListener('pointerup', end);
    grab.addEventListener('pointercancel', end);
  }

  /* ---------------- 抽屉手势 ---------------- */
  function bindDrawer(drawer, scrim, edge) {
    var W = function () { return drawer.getBoundingClientRect().width || 300; };
    function open() { drawer.classList.add('is-open'); scrim.classList.add('is-open'); drawer.style.transform = ''; scrim.style.opacity = ''; }
    function close() { drawer.classList.remove('is-open'); scrim.classList.remove('is-open'); drawer.style.transform = ''; scrim.style.opacity = ''; }
    function isOpen() { return drawer.classList.contains('is-open'); }
    scrim.addEventListener('click', close);

    /* 边缘右滑打开 */
    var sx = 0, sy = 0, dx = 0, active = false, decided = false, dir = 0;
    function start(e) {
      sx = e.clientX; sy = e.clientY; dx = 0; active = true; decided = false; dir = 0;
      drawer.classList.add('is-dragging'); scrim.classList.add('is-dragging');
    }
    function move(e) {
      if (!active) return;
      var mx = e.clientX - sx, my = e.clientY - sy;
      if (!decided) {
        if (Math.abs(mx) < 8 && Math.abs(my) < 8) return;
        decided = true;
        if (Math.abs(my) > Math.abs(mx)) { cancel(); return; }
        dir = mx > 0 ? 1 : -1;
        if (!isOpen()) { drawer.classList.add('is-open'); scrim.classList.add('is-open'); }
      }
      dx = mx;
      var base = openAtStart ? 0 : -W();
      var pos = Math.max(-W(), Math.min(0, base + dx));
      drawer.style.transform = 'translate3d(' + pos + 'px,0,0)';
      scrim.style.opacity = String(1 + pos / W());
    }
    function cancel() {
      active = false; drawer.classList.remove('is-dragging'); scrim.classList.remove('is-dragging');
      drawer.style.transform = ''; scrim.style.opacity = '';
      if (!openAtStart) close();
    }
    function end() {
      if (!active) return;
      active = false;
      drawer.classList.remove('is-dragging'); scrim.classList.remove('is-dragging');
      drawer.style.transform = ''; scrim.style.opacity = '';
      var th = W() * 0.35;
      if (openAtStart) { if (dx < -th) close(); else open(); }
      else { if (dx > th) open(); else close(); }
    }
    var openAtStart = false;
    edge.addEventListener('pointerdown', function (e) { openAtStart = false; start(e); edge.setPointerCapture(e.pointerId); });
    edge.addEventListener('pointermove', move);
    edge.addEventListener('pointerup', end);
    edge.addEventListener('pointercancel', end);
    drawer.addEventListener('pointerdown', function (e) {
      if (e.target.closest('.drawer__body') && e.target.closest('button')) return;
      openAtStart = true; start(e);
    });
    drawer.addEventListener('pointermove', function (e) { if (openAtStart) move(e); });
    drawer.addEventListener('pointerup', function () { if (openAtStart) end(); });
    drawer.addEventListener('pointercancel', function () { if (openAtStart) end(); });

    return { open: open, close: close, isOpen: isOpen };
  }
  UI.bindDrawer = bindDrawer;

  /* ---------------- 安全区注入（WebView 无 env() 时兜底） ---------------- */
  function applyInsets(o) {
    if (!o) return;
    var r = d.documentElement.style;
    if (o.top != null) r.setProperty('--sa-top', o.top + 'px');
    if (o.bottom != null) r.setProperty('--sa-bottom', o.bottom + 'px');
    if (o.left != null) r.setProperty('--sa-left', o.left + 'px');
    if (o.right != null) r.setProperty('--sa-right', o.right + 'px');
  }
  UI.applyInsets = applyInsets;

  /* ---------------- 输入法状态 ----------------
     原生壳把键盘高度作为第五个参数下发。96px 的阈值用来避开手势条、
     悬浮工具条一类的小幅 inset 抖动，只有真正的键盘才会触发让位。 */
  var kbOpen = false;
  function setKeyboard(px) {
    var h = +px || 0;
    var on = h > 96;
    if (on === kbOpen) return;
    kbOpen = on;
    d.documentElement.setAttribute('data-kb', on ? '1' : '0');
    if (on) {
      /* 键盘完全展开后布局才稳定，延一拍再把焦点元素带回可视区 */
      setTimeout(function () {
        var el = d.activeElement;
        if (!el || el === d.body || !el.scrollIntoView) return;
        try { el.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
        catch (e) { el.scrollIntoView(); }
      }, 120);
    }
    try {
      w.dispatchEvent(new CustomEvent('netops:keyboard', { detail: { open: on, height: h } }));
    } catch (e) {}
  }
  UI.isKeyboardOpen = function () { return kbOpen; };

  w.NetOpsSetInsets = function (t, b, l, r, kb) {
    applyInsets({ top: t, bottom: b, left: l, right: r });
    setKeyboard(kb);
  };

  /* ---------------- 减弱动效 ---------------- */
  UI.motion = {
    get: function () { return store.get('motion', 'on'); },
    set: function (v) { store.set('motion', v); d.documentElement.dataset.motion = v; },
    apply: function () { d.documentElement.dataset.motion = store.get('motion', 'on'); }
  };

  w.NetUI = UI;
})(window, document);
