/* ============================================================
 * 全局错误守护（P8，2026-09-25）
 * ------------------------------------------------------------
 * 为什么必须是第一个文件：它要在任何业务脚本执行前装好钩子，
 * 30-core/32-boot 启动路径上的第一声异常才有下文。此前 JS 抛未捕获
 * 异常 = 界面停在半初始化状态，没有任何可见反馈（原生侧有白屏兜底，
 * JS 侧一直是零处理）。
 *
 * 行为：
 *   - window.__jsFaults 记录全部错误（message + file:line + 次数），
 *     供真机视觉探针/远程调试读取，不写 localStorage（存储门禁口径）。
 *   - 第一次错误：拉起一条自带内联样式的顶栏（不依赖任何 CSS ——
 *     能坏到需要它的场景，往往就是 CSS/JS 半残），提供「重载」。
 *   - 之后只计数，不重复弹。钩子本身出错绝不二次抛出（全程 try）。
 * ============================================================ */
(function () {
  var faults = [];
  var banner = null;

  function record(kind, msg, where) {
    try {
      var last = faults[faults.length - 1];
      /* 同一条重复错误只累计次数，不刷爆数组 */
      if (last && last.msg === msg && last.where === where) { last.n++; }
      else faults.push({ kind: kind, msg: String(msg).slice(0, 200), where: String(where || '').slice(0, 120), n: 1 });
      window.__jsFaults = faults;
      showBanner();
    } catch (e) { /* 守护自己不能成为新的错误源 */ }
  }

  function showBanner() {
    if (banner) { updateBanner(); return; }
    try {
      var d = document.createElement('div');
      d.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:9999;' +
        'display:flex;align-items:center;gap:10px;padding:calc(10px + var(--sa-top, 0px)) 14px 10px;' +
        'background:#b3261e;color:#fff;font:13px/1.4 sans-serif;';
      var t = document.createElement('span');
      t.style.cssText = 'flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
      d.appendChild(t);
      var b = document.createElement('button');
      b.textContent = '重载';
      b.style.cssText = 'flex:none;padding:4px 14px;border:1px solid rgba(255,255,255,.6);' +
        'border-radius:999px;background:transparent;color:#fff;font:inherit;';
      b.addEventListener('click', function () { location.reload(); });
      d.appendChild(b);
      document.body.appendChild(d);
      banner = { root: d, text: t };
      updateBanner();
    } catch (e) { /* body 尚未就绪等极端场景：计数仍有效（__jsFaults） */ }
  }

  function updateBanner() {
    try {
      var f = faults[faults.length - 1];
      if (banner && f) banner.text.textContent =
        '界面脚本出错 ×' + faults.reduce(function (s, x) { return s + x.n; }, 0) +
        '：' + f.msg + (f.where ? '（' + f.where + '）' : '');
    } catch (e) { /* 同上 */ }
  }

  window.onerror = function (msg, src, line, col) {
    var file = String(src || '');
    var at = file.substring(file.lastIndexOf('/') + 1);
    record('error', msg, at + (line ? ':' + line + (col ? ':' + col : '') : ''));
    return false;                                  /* 不吃掉默认的 console 报告 */
  };
  window.addEventListener('unhandledrejection', function (ev) {
    var r = ev && ev.reason;
    record('rejection', r && r.message ? r.message : r, '');
  });
})();
