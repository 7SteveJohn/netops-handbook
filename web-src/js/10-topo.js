/* ============================================================
 * NetOps 2.0 · 拓扑渲染器
 * 把声明式描述符 {k:'stack'|'flow'|'ha'|'multi'} 渲染成内联 SVG
 * 零依赖 / 零外链 / 自动折行 / 主题变量驱动配色
 * ============================================================ */
(function (w) {
  'use strict';

  var CJK = /[\u2e80-\u9fff\u3000-\u303f\uff00-\uffef]/;

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  /* 文本宽度估算（无 DOM 测量，纯字符分类） */
  function tw(str, size) {
    var s = String(str || ''), n = 0, i, ch;
    for (i = 0; i < s.length; i++) {
      ch = s.charAt(i);
      if (CJK.test(ch)) n += size;
      else if (/[A-Z0-9]/.test(ch)) n += size * 0.60;
      else if (/[ .,:'|!\[\]()\/\\-]/.test(ch)) n += size * 0.34;
      else n += size * 0.53;
    }
    return Math.ceil(n);
  }

  /* ---------- 设备图元：按名称猜类型 ---------- */
  var KIND = [
    ['fw',     /防火墙|firewall|\bfw\b|安全组|waf|策略/i],
    ['cloud',  /云|cloud|internet|公网|外网|运营商|isp|vpc|overlay/i],
    ['router', /路由器|router|网关|gateway|\bar\d|出口|core|核心/i],
    ['switch', /交换机|switch|\bsw\b|接入|汇聚|leaf|spine|trunk|vlan/i],
    ['server', /服务器|server|主机|node|节点|pod|容器|docker|k8s|数据库|db|master|backup|nginx|后端/i],
    ['pc',     /pc|终端|客户端|client|用户|电脑|笔记本|手机/i]
  ];
  function kindOf(t) {
    var s = String(t || ''), i;
    for (i = 0; i < KIND.length; i++) if (KIND[i][1].test(s)) return KIND[i][0];
    return '';
  }

  /* 14×14 视觉图元，(x,y) 为左上角 */
  function glyph(kind, x, y) {
    var g = '<g class="g-ico" transform="translate(' + x + ',' + y + ')">';
    switch (kind) {
      case 'router':
        g += '<circle cx="7" cy="7" r="6"/><path d="M4 7h6M8 5l2 2-2 2M10 10H4M6 12 4 10l2-2"/>'; break;
      case 'switch':
        g += '<rect x="1" y="3" width="12" height="8" rx="1.5"/><path d="M4 5.5h6M4 8.5h6"/>'; break;
      case 'server':
        g += '<rect x="2" y="1" width="10" height="5" rx="1"/><rect x="2" y="8" width="10" height="5" rx="1"/><path d="M4 3.5h.01M4 10.5h.01"/>'; break;
      case 'cloud':
        g += '<path d="M4 11h6a3 3 0 0 0 .3-6A4 4 0 0 0 3 6.2 2.6 2.6 0 0 0 4 11Z"/>'; break;
      case 'fw':
        g += '<path d="M7 1 12 3v4c0 3-2.1 5.3-5 6-2.9-.7-5-3-5-6V3Z"/><path d="M4.8 7 6.4 8.6 9.4 5.6"/>'; break;
      case 'pc':
        g += '<rect x="1" y="2" width="12" height="8" rx="1"/><path d="M5 13h4M7 10v3"/>'; break;
      default:
        g += '<circle cx="7" cy="7" r="2.4"/><circle cx="7" cy="7" r="6"/>';
    }
    return g + '</g>';
  }

  /* 节点配色 */
  function clsOf(t, i, total) {
    var s = String(t || '');
    if (/丢包|不通|失败|故障|异常|拒绝|drop|deny|down|×|✗/i.test(s)) return 'n-bad';
    if (/防火墙|firewall|策略|acl|安全|nat/i.test(s)) return 'n-warn';
    if (/成功|通过|允许|permit|正常|up|✓/i.test(s)) return 'n-ok';
    if (i === 0 || i === total - 1) return 'n-accent';
    return 'n-box';
  }

  /* ---------- 节点盒 ---------- */
  var FS_T = 10, FS_S = 8.5, PADX = 9, GAP_ICO = 17;

  function measure(node) {
    var t = node.t || '', s = node.s || '';
    var k = kindOf(t);
    var wt = tw(t, FS_T), ws = tw(s, FS_S);
    var wd = Math.max(wt, ws) + PADX * 2 + (k ? GAP_ICO : 0);
    return {
      t: t, s: s, kind: k,
      w: Math.max(58, Math.min(190, wd)),
      h: s ? 38 : 28
    };
  }

  function box(m, x, y, cls) {
    var out = '<g>';
    out += '<rect x="' + x + '" y="' + y + '" width="' + m.w + '" height="' + m.h +
           '" rx="7" class="' + cls + '"/>';
    var tx = x + PADX;
    if (m.kind) { out += glyph(m.kind, x + 7, y + (m.h - 14) / 2); tx = x + 7 + GAP_ICO; }
    if (m.s) {
      out += '<text x="' + tx + '" y="' + (y + 15) + '" class="n-txt">' + esc(m.t) + '</text>';
      out += '<text x="' + tx + '" y="' + (y + 28) + '" class="n-sub">' + esc(m.s) + '</text>';
    } else {
      out += '<text x="' + tx + '" y="' + (y + m.h / 2 + 3.6) + '" class="n-txt">' + esc(m.t) + '</text>';
    }
    return out + '</g>';
  }

  /* ---------- 箭头 ---------- */
  function arrowDown(x, y1, y2, cls) {
    return '<path class="lnk ' + (cls || '') + '" d="M' + x + ' ' + y1 + 'V' + (y2 - 5) + '"/>' +
           '<path class="ar" d="M' + x + ' ' + y2 + 'l-3.6-5.4h7.2Z"/>';
  }
  function arrowRight(x1, x2, y, cls) {
    return '<path class="lnk ' + (cls || '') + '" d="M' + x1 + ' ' + y + 'H' + (x2 - 5) + '"/>' +
           '<path class="ar" d="M' + x2 + ' ' + y + 'l-5.4-3.6v7.2Z"/>';
  }
  function label(x, y, txt) {
    if (!txt) return '';
    return '<text x="' + x + '" y="' + y + '" class="lbl" text-anchor="middle">' + esc(txt) + '</text>';
  }

  var MAXW = 322;   /* 移动端内容宽度上限，超出即折行 */

  /* ============ stack：竖向分层 ============ */
  function renderStack(dg) {
    var ns = dg.n || [], ms = ns.map(measure);
    var wmax = 0, i;
    for (i = 0; i < ms.length; i++) wmax = Math.max(wmax, ms[i].w);
    wmax = Math.min(Math.max(wmax, 150), MAXW - 20);
    var x = 10, y = 10, gap = 15, out = '';
    for (i = 0; i < ms.length; i++) {
      ms[i].w = wmax;
      out += box(ms[i], x, y, clsOf(ms[i].t, i, ms.length));
      if (i < ms.length - 1) out += arrowDown(x + wmax / 2, y + ms[i].h, y + ms[i].h + gap, 'lnk-dash');
      y += ms[i].h + gap;
    }
    return svg(wmax + 20, y - gap + 10, out);
  }

  /* ============ flow：横向链路 + 自动折行 ============ */
  function flowRows(ns, ls, maxw) {
    var ms = ns.map(measure), rows = [], cur = [], curw = 0, i, cw;
    for (i = 0; i < ms.length; i++) {
      ms[i].lab = i > 0 ? (ls && ls[i - 1] ? String(ls[i - 1]) : '') : '';
      cw = i === 0 ? 0 : Math.max(30, tw(ms[i].lab, 8) + 12);
      ms[i].cw = cw;
      if (cur.length && curw + cw + ms[i].w > maxw) {
        rows.push(cur); ms[i].wrap = true; cur = [ms[i]]; curw = ms[i].w;
      } else {
        curw += (cur.length ? cw : 0) + ms[i].w; cur.push(ms[i]);
      }
    }
    if (cur.length) rows.push(cur);
    return rows;
  }

  function renderFlow(dg, ox, oy, forceW) {
    var rows = flowRows(dg.n || [], dg.l || [], (forceW || MAXW) - 20);
    var out = '', x, y = oy == null ? 10 : oy, r, i, m, prev = null, W = 0, rowH, idx = 0;
    var total = (dg.n || []).length;
    for (r = 0; r < rows.length; r++) {
      rowH = 0;
      for (i = 0; i < rows[r].length; i++) rowH = Math.max(rowH, rows[r][i].h);
      x = ox == null ? 10 : ox;
      for (i = 0; i < rows[r].length; i++) {
        m = rows[r][i];
        var by = y + (rowH - m.h) / 2;
        if (i > 0) {
          out += arrowRight(x, x + m.cw, y + rowH / 2, '');
          out += label(x + m.cw / 2, y + rowH / 2 - 6, m.lab);
          x += m.cw;
        } else if (m.wrap && prev) {
          /* 换行连接：从上一行末尾绕到本行行首 */
          var dy = y + rowH / 2;
          out += '<path class="lnk lnk-dash" d="M' + prev.x + ' ' + prev.y + 'h10 V' + dy + ' H' + (x - 6) + '"/>' +
                 '<path class="ar" d="M' + x + ' ' + dy + 'l-5.4-3.6v7.2Z"/>';
          out += label((prev.x + x) / 2 + 6, dy - 6, m.lab);
        }
        out += box(m, x, by, clsOf(m.t, idx, total));
        x += m.w; idx++;
        prev = { x: x, y: by + m.h / 2 };
      }
      W = Math.max(W, x);
      y += rowH + (r < rows.length - 1 ? 26 : 0);
    }
    return { s: out, w: W + 10, h: y + (oy == null ? 10 : 0), bottom: y };
  }

  /* ============ ha：双机热备 ============ */
  function renderHA(dg) {
    var ns = dg.n || [], a = measure(ns[0] || { t: 'Master' }), b = measure(ns[1] || { t: 'Backup' });
    var wmax = Math.max(a.w, b.w, 108); a.w = b.w = wmax;
    var hbW = 62, W = wmax * 2 + hbW + 20, y = 14, out = '';
    var xa = 10, xb = 10 + wmax + hbW;
    a.h = b.h = Math.max(a.h, b.h);

    out += box(a, xa, y, 'n-ok');
    out += box(b, xb, y, 'n-box');
    /* 心跳线 */
    var my = y + a.h / 2;
    out += '<path class="lnk lnk-a flow" d="M' + (xa + wmax) + ' ' + my + 'H' + xb + '"/>';
    out += label(xa + wmax + hbW / 2, my - 7, '心跳');

    /* VIP 承载 */
    var vipTxt = dg.mid || 'VIP';
    var vw = tw(vipTxt, 9.5) + 26, vx = (W - vw) / 2, vy = y + a.h + 30;
    out += '<path class="lnk lnk-a" d="M' + (xa + wmax / 2) + ' ' + (y + a.h) + 'V' + (vy - 9) + 'H' + (vx + 4) + '"/>';
    out += '<path class="lnk lnk-dash" d="M' + (xb + wmax / 2) + ' ' + (y + b.h) + 'V' + (vy - 9) + 'H' + (vx + vw - 4) + '"/>';
    out += '<rect x="' + vx + '" y="' + (vy - 9) + '" width="' + vw + '" height="24" rx="12" class="n-accent"/>';
    out += '<text x="' + (vx + vw / 2) + '" y="' + (vy + 6.6) + '" class="n-txt" text-anchor="middle">' + esc(vipTxt) + '</text>';
    /* 角标 */
    out += '<text x="' + (xa + 6) + '" y="' + (y - 4) + '" class="lbl">MASTER</text>';
    out += '<text x="' + (xb + 6) + '" y="' + (y - 4) + '" class="lbl">BACKUP</text>';
    return svg(W, vy + 25, out);
  }

  /* ============ multi：多行链路 ============ */
  function renderMulti(dg) {
    var rows = dg.rows || [], out = '', y = 10, W = 0, i, r;
    for (i = 0; i < rows.length; i++) {
      r = renderFlow({ n: rows[i].n || [], l: rows[i].l || [] }, 10, y);
      out += r.s; W = Math.max(W, r.w);
      y = r.bottom + 8;
      if (i < rows.length - 1) {
        out += '<path class="lnk lnk-dash" d="M14 ' + (y + 4) + 'H' + (Math.max(W, 120) - 14) + '" opacity=".55"/>';
        y += 16;
      }
    }
    return svg(W, y + 4, out);
  }

  /* ---------- SVG 外壳 ---------- */
  function svg(wd, ht, body) {
    wd = Math.ceil(wd); ht = Math.ceil(ht);
    var style = wd > MAXW ? ' style="width:' + wd + 'px;max-width:none"' : '';
    return '<div class="topo"><svg viewBox="0 0 ' + wd + ' ' + ht + '" width="' + wd +
      '" height="' + ht + '" role="img" aria-label="网络拓扑示意图"' + style + '>' + body + '</svg></div>';
  }

  /* ---------- 入口 ---------- */
  function render(dg) {
    if (!dg || !dg.k) return '';
    try {
      switch (dg.k) {
        case 'stack': return renderStack(dg);
        case 'flow':  var f = renderFlow(dg); return svg(f.w, f.h, f.s);
        case 'ha':    return renderHA(dg);
        case 'multi': return renderMulti(dg);
        default:      return '';
      }
    } catch (e) { return ''; }
  }

  w.NetTopo = { render: render, kindOf: kindOf, glyph: glyph };
})(window);
