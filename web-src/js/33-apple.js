/* ============================================================
 * Apple Ultimate UI — Liquid Glass + Motion
 * 由构建内联进单文件 HTML：tabbar 真正悬浮、卡片手风琴、
 * 数字滚动、滚动变实、reduce-motion 兜底。
 * 同步自 app/src/main/assets/index.html
 * ============================================================ */

/* === APPLE ULTIMATE (mirrored from assets) === */

/* ==================================================================
 * 2026-09-29 凌晨定案（用户拍板，参考荣耀相册悬浮胶囊）：药丸回归 =
 * 全格宽清玻璃药丸 + 纯 CSS 弹簧滑动（机制详见 32-boot.js 药丸区顶注与
 * 06-anim 药丸主块——JS 只在激活 tab 变化时写 --pill-i，零 rAF）。
 * 透镜/挖孔停用（LENS_SURFACES 恒空，见下方注释）；果冻 Q弹由 spring
 * 曲线的过冲承担。若要再停用药丸：本开关改 false + 06-anim 恢复 float
 * 隐藏 + 删 float 禁点覆盖 + smoke 药丸两条用例同步翻转。
 * 挂 window 的原因：32-boot.js 拼接在本文件之前，它的 syncPillIndex 也读
 * 这个开关；所有读取点都在 DOMContentLoaded 之后，此处赋值先生效。
 * ================================================================== */
window.TAB_PILL = true;

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
  /* 应用内的「动画效果」开关：以前全站没有消费方，现在是真开关了（CSS 侧见
     06-anim.css 的 html[data-motion="off"]）。这里管 JS 驱动的那部分。 */
  function motionOff() {
    try { return !!(window.NetUI && window.NetUI.motion && window.NetUI.motion.get() !== 'on'); }
    catch (e) { return false; }
  }

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
    if (REDUCE || motionOff()) {
      /* 无障碍 / 用户关掉动画：直接显示最终值，不做滚动动画（保留单位后缀） */
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

  /* ==================================================================
   * 边缘折射透镜（方案 A：backdrop-filter: url(#lens)）
   * ------------------------------------------------------------------
   * 为什么单独一层：backdrop-filter 是整条声明一起生效或一起失效的。把 url(#lens)
   * 追加到 06-anim.css 那条曲线链后面，一旦某个 WebView 版本不认 url()，整条声明
   * 连同已经可用的 saturate/brightness/contrast 压缩一起被丢掉 —— 拿能用的东西去赌。
   * 独立子层则最坏就是它自己不生效，父层曲线照常渲染。降级是结构性的，不依赖探测。
   *
   * 贴图编码：R = 法线 x、G = 法线 y（128 表示零位移，配合 color-interpolation-filters
   * =sRGB 才不会整体偏移），B 留给边缘高光。位移只发生在 bezel 环形带内，中心保持
   * 平坦 —— 参考实现就是"边缘弯、中间平"，中间平了字才读得清。
   * ================================================================== */
  /* 2026-09-25 深夜（用户供片 WWDC25 解析，要求重构为同款）：恢复启用——
     底栏已改为边到边玻璃板（底角与屏幕圆角同心），折射发生在角落大曲率处；
     旧圆斑根因是形变带 band=min(hw,hh)=栏高一半（整栏都在形变区内），
     现改为固定 8px 边带 + 折射强度降到 14px，只在边缘/角落弯折。
   2026-09-26（用户指出选中态仍是平板，karpathy 准则：改眼睛看的东西）：
     把透镜装到**选中药丸本身**——参考实现全部是"透镜在交互元素上"，
     不是装在 390px 宽的栏周长上（一圈 8px 窄带肉眼不可见=等于没做）。 */
  /* 2026-09-28 药丸独立透镜（探针 G/H 盒验证，lens-probe-gh 截图）：
     底栏本体在 glass-on 时不再吃 backdrop-filter（blur+cmp 链移到 .tabbar-blur
     底板，药丸位置由 SVG mask 挖孔）——药丸透镜因此在孔内直接折射**原始背景**，
     不再隔着一层 blur(28) 看糊的。旧的全宽 .tabbar 透镜删除：历史上对着 blur
     输出位移就"等于没做"，新架构下更无意义。降级链：mask 不认→无孔全模糊
     （=旧观感）；backdrop url() 不认→透镜层惰性（=旧观感），两条都无回归。 */
  /* 2026-09-29 凌晨：LENS_SURFACES 恒为空数组。药丸挖孔已随 rAF 果冻引擎
     一并废除（纯 CSS 药丸无法逐帧同步孔几何——这正是半个月卡顿的共谋），
     而透镜没有孔就隔着 blur(28) 位移输出，"等于没做"（561e8f0 的结论）。
     药丸观感改为 veil+rim 玻璃片（06-anim），与荣耀相册参考一致：药丸内外
     模糊量接近，区分度来自 rim 与亮度。透镜代码保留 dormant，将来若以
     "静态药丸 + CSS transition 同步 mask"的思路复活挖孔，从这里接回。 */
  var LENS_SURFACES = [];

  function lensOptics() {
    var cs = getComputedStyle(document.documentElement);
    var num = function (n, dflt) { var v = parseFloat(cs.getPropertyValue(n)); return isFinite(v) ? v : dflt; };
    return {
      refraction: num('--gr-lens-refraction', 16),
      bezel: num('--gr-lens-bezel', 0.30)
      /* 2026-09-28：ior 已从配方表删除（建模改 smoothstep，无消费者）。
         curvature 故意不读：表里那个值还没接上真实建模，读回来只会是个装饰数字 */
    };
  }

  /* 圆角矩形的带符号距离场（p 相对中心）。返回 {d: 内部为正, nx, ny: 单位法线} */
  function roundedSdf(px, py, hw, hh, r) {
    var qx = Math.abs(px) - (hw - r), qy = Math.abs(py) - (hh - r);
    var outside = Math.sqrt(Math.max(qx, 0) * Math.max(qx, 0) + Math.max(qy, 0) * Math.max(qy, 0));
    var inside = Math.min(Math.max(qx, qy), 0);
    var dist = outside + inside - r;                 /* 负数=在内 */
    var e = 0.75;                                    /* 数值梯度步长（px） */
    var gx = (Math.abs(px + e) - Math.abs(px - e)) * 0.5;
    var gy = (Math.abs(py + e) - Math.abs(py - e)) * 0.5;
    /* 用矩形近似梯度：轴向主导，角部够用，避免每像素四次 SDF 求值 */
    var nx = gx, ny = gy, len = Math.sqrt(nx * nx + ny * ny);
    if (len < 1e-6) { nx = px < 0 ? -1 : (px > 0 ? 1 : 0); ny = py < 0 ? -1 : (py > 0 ? 1 : 0); len = Math.sqrt(nx * nx + ny * ny) || 1; }
    return { d: -dist, nx: nx / len, ny: ny / len };
  }

  function makeLensMap(w, h, r, o) {
    var S = 1;                                       /* 贴图按 CSS 像素 1:1 生成 */
    var cw = Math.max(2, Math.round(w * S)), ch = Math.max(2, Math.round(h * S));
    var cv = document.createElement('canvas'); cv.width = cw; cv.height = ch;
    var ctx = cv.getContext('2d');
    var img = ctx.createImageData(cw, ch), d = img.data;
    var hw = cw / 2, hh = ch / 2;
    var rr = Math.min(r, hw, hh);
    /* 2026-09-28 重建模（lens-probe 四盒截图裁决，screenshots/lens-probe-abcd-2x）：
       旧固定 8px 窄带 + pow(ior) 位移梯度 ~0.9px/px，边缘呈杂乱硬压缩条纹；
       改为 band = bezel·min(hw,hh) 比例带（下限 10px；bezel=0.30 → 84px 栏
       band≈13px，中央留 ~58px 平坦区不侵文字）+ smoothstep 剖面 —— 边缘平滑弯折。 */
    var band = Math.max(10, Math.round(Math.min(hw, hh) * o.bezel));
    for (var y = 0; y < ch; y++) {
      for (var x = 0; x < cw; x++) {
        var i = (y * cw + x) * 4;
        var s = roundedSdf(x + 0.5 - hw, y + 0.5 - hh, hw, hh, rr);
        var k = 0;
        if (s.d >= 0 && s.d < band) {                /* 只在边缘环带内弯 */
          var t = 1 - s.d / band;                    /* 越靠边越强 */
          k = t * t * (3 - 2 * t);                   /* smoothstep：带内沿 C¹ 归零 */
        }
        /* 128 = 零位移；法线取负是因为 feDisplacementMap 沿 +通道方向偏移采样点 */
        d[i] = 128 + Math.round(-s.nx * k * 127);
        d[i + 1] = 128 + Math.round(-s.ny * k * 127);
        d[i + 2] = 128;                              /* B：边缘高光，本轮不启用 */
        d[i + 3] = 255;
      }
    }
    ctx.putImageData(img, 0, 0);
    return { url: cv.toDataURL('image/png'), w: cw, h: ch };
  }

  function ensureLensSvg() {
    var svg = document.getElementById('glassLensSvg');
    if (!svg) {
      var NS = 'http://www.w3.org/2000/svg';
      svg = document.createElementNS(NS, 'svg');
      svg.setAttribute('id', 'glassLensSvg');
      svg.setAttribute('aria-hidden', 'true');
      svg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden';
      document.body.appendChild(svg);
    }
    /* 2026-09-29 凌晨：挖孔 mask（#pillHoleMask/#pillHoleRect）不再创建——
       纯 CSS 药丸无法逐帧同步孔几何，mask 随 rAF 果冻引擎一并废除；
       CSS 侧的 mask 引用规则也已删除（06-anim）。 */
    return svg;
  }

  function applyLensTo(surf) {
    var host = document.querySelector(surf.sel);
    if (!host) return;
    var rb = host.getBoundingClientRect();
    if (!rb.width || !rb.height) return;             /* 未布局，别拿 0 尺寸建贴图 */
    var o = lensOptics();
    var cs = getComputedStyle(host);
    var r = parseFloat(cs.borderTopLeftRadius) || (Math.min(rb.width, rb.height) / 2);
    var map = makeLensMap(rb.width, rb.height, r, o);
    var NS = 'http://www.w3.org/2000/svg';
    var svg = ensureLensSvg();
    var old = svg.querySelector('#' + surf.id);
    if (old) old.remove();
    var f = document.createElementNS(NS, 'filter');
    f.setAttribute('id', surf.id);
    /* objectBoundingBox：WebKit 对 userSpaceOnUse 会把整块元素渲染成空白；
       sRGB：否则 128 不再等于"零位移"，整张图会平移。两条都是踩过的坑。 */
    /* 2026-09-25（照 shuding/liquid-glass 源码重写滤镜几何，用户指出"你就没改"）：
       旧 objectBoundingBox + 滤镜区域 ±0.2 外扩 → feImage 贴图被拉伸 1.4 倍，
       折射环带大部分落在可见区外=几乎没折射。shuding 的做法：
       userSpaceOnUse + 滤镜区域精确等于元素尺寸 + feImage 1:1 对齐 + scale=像素。 */
    f.setAttribute('filterUnits', 'userSpaceOnUse');
    f.setAttribute('primitiveUnits', 'userSpaceOnUse');
    f.setAttribute('color-interpolation-filters', 'sRGB');
    f.setAttribute('x', '0'); f.setAttribute('y', '0');
    f.setAttribute('width', Math.round(rb.width)); f.setAttribute('height', Math.round(rb.height));
    var fe = document.createElementNS(NS, 'feImage');
    fe.setAttribute('result', 'map');
    fe.setAttribute('href', map.url);
    fe.setAttribute('preserveAspectRatio', 'none');
    fe.setAttribute('width', Math.round(rb.width)); fe.setAttribute('height', Math.round(rb.height));
    var dm = document.createElementNS(NS, 'feDisplacementMap');
    dm.setAttribute('in', 'SourceGraphic');
    dm.setAttribute('in2', 'map');
    /* userSpaceOnUse 下 scale 即像素位移：refraction px（贴图通道满偏 127/255≈半程） */
    dm.setAttribute('scale', o.refraction.toFixed(1));
    dm.setAttribute('xChannelSelector', 'R');
    dm.setAttribute('yChannelSelector', 'G');
    f.appendChild(fe); f.appendChild(dm);
    svg.appendChild(f);

    var layer = host.querySelector(':scope > .' + surf.cls);
    if (!layer) {
      layer = document.createElement('i');
      layer.className = 'glass-lens ' + surf.cls;
      layer.setAttribute('aria-hidden', 'true');
      /* 插在共享药丸之后、各 tab 之前：参考片里玻璃弯折的是"透过玻璃看到的背景"，
         图标和文字是画在玻璃之上的，不该被一起位移。放在药丸之后是为了让它弯到药丸，
         放在 tab 之前是为了不糊字。 */
      var pillEl = host.querySelector(':scope > .tab-pill');
      if (pillEl && pillEl.nextSibling) host.insertBefore(layer, pillEl.nextSibling);
      else if (pillEl) host.appendChild(layer);
      else host.insertBefore(layer, host.firstChild);
    }
    layer.style.setProperty('--lens-url', 'url(#' + surf.id + ')');
    layer.dataset.lensW = Math.round(rb.width) + 'x' + Math.round(rb.height);
  }

  /* blur 底板：glass-on 时承接原 tabbar 的 blur+cmp 链，药丸位置挖孔（药丸透镜
     在孔内吃原始背景）。z-index:-1 → 画在栏底色之上、edge-light(::before) 与
     药丸之下。非药丸区材质与旧 tabbar blur 数学等价（tint 在 blur 之下，线性
     滤镜下 blur(tint+content) ≡ tint+blur(content)）。 */
  function ensureBlurUnderlay(glassOn) {
    var bar = document.querySelector('.tabbar');
    if (!bar) return;
    var u = bar.querySelector(':scope > .tabbar-blur');
    if (glassOn && !u) {
      u = document.createElement('i');
      u.className = 'tabbar-blur';
      u.setAttribute('aria-hidden', 'true');
      bar.insertBefore(u, bar.firstChild);
    } else if (!glassOn && u) {
      u.remove();
    }
  }

  function initGlassLens() {
    if (!(window.CSS && CSS.supports && CSS.supports('backdrop-filter', 'url(#x)'))) {
      /* 引擎连 url() 声明都不认（WebKit 系）：底板方案整体不可用，加回滚类让
         06-anim 还原 tabbar 本体 blur，材质退回 2026-09-26 前形态 */
      document.body.classList.add('lens-unsupported');
      return;
    }
    var raf = 0;
    function run() {
      var glassOn = document.body.classList.contains('glass-on');
      ensureBlurUnderlay(glassOn);
      LENS_SURFACES.forEach(function (s) {
        var host = document.querySelector(s.sel);
        if (!host) return;
        var layer = host.querySelector(':scope > .' + s.cls);
        /* 陷阱①（P4）：关玻璃时 tabbar 本体是透明底（03-layout .tabbar{background:transparent}，
           表面由 ::before 提供）。位移贴图在先乘空间对各通道求和，透明底会把 alpha 重复算进
           采样（参考引擎规则：承载层要有不透明底）。透镜本来就是玻璃设计的一部分 ——
           关玻璃直接摘层，不存在"在透明表面上折射"。 */
        if (!glassOn) { if (layer) layer.remove(); return; }
        var rb = host.getBoundingClientRect();
        var key = Math.round(rb.width) + 'x' + Math.round(rb.height);
        /* 只在形状真的变了才重画贴图：切 tab 时药丸移动，底栏本身尺寸不变 */
        if (layer && layer.dataset.lensW === key) return;
        try { applyLensTo(s); } catch (e) { if (window.console) console.warn('[lens] 生成失败，退回无折射曲线层：', e && e.message); }
      });
    }
    var schedule = function () {
      if (raf) return;
      raf = requestAnimationFrame(function () { raf = 0; run(); });
    };
    /* 首次同步建，之后才走 rAF 合并：rAF 在隐藏标签页里根本不触发，
       把首帧挂在它上面会让"透镜从没建起来"和"产品坏了"长得一模一样。 */
    try { run(); } catch (e) { if (window.console) console.warn('[lens] 初始化失败：', e && e.message); }
    schedule();
    window.addEventListener('resize', schedule);
    /* 底栏模式切换、玻璃开关、通透度调档都可能改变形状 */
    document.body.addEventListener('click', function () { setTimeout(schedule, 220); }, true);
    window.__glassLensSchedule = schedule;
    window.__glassLensRun = run;
  }

  /* 药丸存在开关（window.TAB_PILL）：false 时把静态药丸（index.html 里的
     #tabPill）从 DOM 摘除 = 不挂载。tab-pill-on 类已无 CSS 消费方（挖孔 mask
     废除），仅保留语义标记。 */ 
  function applyTabPillSwitch() {
    var p = document.getElementById('tabPill');
    if (window.TAB_PILL) { document.body.classList.add('tab-pill-on'); return; }
    document.body.classList.remove('tab-pill-on');
    if (p) p.remove();
  }

  // ---- 5. init ----
  function init() {
    attachAppbarStuck();
    bindTapTargets();
    watchTapTargets();
    hookViewChange();
    attachTabbarScroll();
    applyTabPillSwitch();
    initGlassLens();
    setTimeout(function () { countUp(); bindTapTargets(); initGlassLens(); }, 60);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, { once: true });
  } else { init(); }
})();


