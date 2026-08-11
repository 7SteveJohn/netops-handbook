/* ============================================================
 * Apple Ultimate UI — Liquid Glass + Motion
 * 由构建内联进单文件 HTML：tabbar 真正悬浮、卡片手风琴、
 * 数字滚动、滚动变实、reduce-motion 兜底。
 * 同步自 app/src/main/assets/index.html
 * ============================================================ */

/* === APPLE ULTIMATE (mirrored from assets) === */

/* ==================================================================
 * Apple Ultimate UI — Motion JS
 *  - 实时跟踪 scroll 让 appbar 进入 stuck 状态
 *  - 自动给所有可点击元素加 tap-target（毛玻璃 ripple）
 *  - 统一 focus 管理 & 键盘可达性
 *  - 数字滚动计数（count-up）
 *  - 尊重 reduce-motion
 * ================================================================== */
(function () {
  if (window.__appleUltimate) return;
  window.__appleUltimate = true;

  var REDUCE = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ---- 1. AppBar stuck 状态：滚动后透明度提升 + 出现细分割线 ----
  function attachAppbarStuck() {
    var appbar = document.querySelector('.appbar');
    if (!appbar) return;
    var page = document.querySelector('.page') || appbar.parentElement;
    var target = page || document.documentElement;
    var ticking = false;
    function update() {
      var sc = target.scrollTop || window.scrollY || 0;
      appbar.classList.toggle('is-stuck', sc > 8);
      ticking = false;
    }
    function onScroll() {
      if (!ticking) {
        requestAnimationFrame(update);
        ticking = true;
      }
    }
    target.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('scroll', onScroll, { passive: true });
    update();
  }

  // ---- 2. 给所有可点击元素加 .tap-target 类 ----
  function bindTapTargets() {
    var sels = '.btn, .chip, .tab, .phase, .card, .list__item, .phase__foot, .ibtn, [data-go]';
    var els = document.querySelectorAll(sels);
    els.forEach(function (el) {
      if (!el.classList.contains('tap-target')) el.classList.add('tap-target');
    });
  }
  // 监听 DOM 变化，自动补 tap-target
  function watchTapTargets() {
    if (!window.MutationObserver) return;
    var mo = new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        muts[i].addedNodes.forEach(function (n) {
          if (n.nodeType !== 1) return;
          if (n.matches && /(\.btn|\.chip|\.tab|\.phase|\.card|\.list__item)/.test('.' + (n.className || ''))) {
            if (!n.classList.contains('tap-target')) n.classList.add('tap-target');
          }
          var sub = n.querySelectorAll && n.querySelectorAll('.btn, .chip, .tab, .phase, .card, .list__item');
          if (sub) sub.forEach(function (e) {
            if (!e.classList.contains('tap-target')) e.classList.add('tap-target');
          });
        });
      }
    });
    mo.observe(document.body, { childList: true, subtree: true });
  }

  // ---- 3. count-up 数字滚动：在 stat/hero 内数字执行缓入 ----
  function countUp(root) {
    if (!root) root = document;
    if (REDUCE) {
      /* 无障碍：直接显示最终值，不做滚动动画（保留单位后缀） */
      root.querySelectorAll('.stat__n, .hero__num, .count').forEach(function (el) {
        if (el.__counted) return; el.__counted = true;
        var m = (el.textContent || '').trim().match(/^(\d+(?:\.\d+)?)/);
        if (m) { var suffix = (el.textContent || '').replace(m[1], ''); el.textContent = m[1] + suffix; }
      });
      return;
    }
    var els = (root || document).querySelectorAll ?
      (root || document).querySelectorAll('.stat__n, .hero__num, .count')
      : [];
    els.forEach(function (el) {
      if (el.__counted) return; el.__counted = true;
      var txt = (el.textContent || '').trim();
      var m = txt.match(/^(\d+(?:\.\d+)?)/);
      if (!m) return;
      var target = parseFloat(m[1]);
      if (!isFinite(target)) return;
      var raw = txt;
      var suffix = raw.replace(m[1], '');
      var start = performance.now();
      var dur = 700;
      function tick(now) {
        var t = Math.min(1, (now - start) / dur);
        var e = 1 - Math.pow(1 - t, 3); /* easeOutCubic */
        var v = Math.round(target * e * 10) / 10;
        if (target >= 1) v = Math.round(target * e);
        el.textContent = v + suffix;
        if (t < 1) requestAnimationFrame(tick);
      }
      requestAnimationFrame(tick);
    });
  }

  // ---- 4. 当视图切换时重新触发滚回顶部 & 计数 ----
  function hookViewChange() {
    /* 视口切到 .is-active 时，重置滚动 + 重新计数 */
    var target = document.querySelector('#pages') || document.body;
    if (!window.MutationObserver) return;
    var mo = new MutationObserver(function () {
      document.querySelectorAll('.view.is-active').forEach(function (v) {
        if (v.__hookedLast === v.innerHTML.length) return;
        v.__hookedLast = v.innerHTML.length;
        countUp(v);
        bindTapTargets();
      });
    });
    mo.observe(target, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
  }

  // ---- 6. tabbar 滚动变实（iOS 16+ Safari 同款）----
  function attachTabbarScroll() {
    var tb = document.querySelector('.tabbar');
    if (!tb) return;
    var scroller = document.querySelector('.scroll') || window;
    var ticking = false;
    function update() {
      var top = scroller === window
        ? (window.scrollY || document.documentElement.scrollTop || 0)
        : (scroller.scrollTop || 0);
      tb.classList.toggle('is-scrolled', top > 8);
      ticking = false;
    }
    function onScroll() {
      if (!ticking) { requestAnimationFrame(update); ticking = true; }
    }
    scroller.addEventListener('scroll', onScroll, { passive: true });
    update();
  }

  // ---- 5. init ----
  function init() {
    attachAppbarStuck();
    bindTapTargets();
    watchTapTargets();
    hookViewChange();
    attachTabbarScroll();
    setTimeout(function () { countUp(); bindTapTargets(); }, 60);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, { once: true });
  } else { init(); }
})();


