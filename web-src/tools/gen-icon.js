/* ------------------------------------------------------------------
 * gen-icon.js —— 生成 Android 5.0~7.1 使用的传统位图启动图标。
 *
 * Android 8.0+ 走 res/mipmap-anydpi-v26 的自适应图标（矢量），
 * 低版本设备需要 PNG 位图，这里用纯 Node（zlib 手写 PNG）离线生成，
 * 图形与自适应图标保持一致，避免引入任何图形库依赖。
 * ------------------------------------------------------------------ */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const OUT_ROOT = path.resolve(__dirname, '../../app/src/main/res');
const ATTIC = path.resolve(__dirname, '_replaced-template-icons'); // 模板旧图标存放处
const SS = 4;                       // 超采样倍数，用于抗锯齿
const VB = 108;                     // 与矢量图标一致的设计坐标系
const CONTENT_SCALE = 1.22;         // 传统图标没有 66dp 安全区，内容适当放大

const SIZES = [
  ['mipmap-mdpi', 48],
  ['mipmap-hdpi', 72],
  ['mipmap-xhdpi', 96],
  ['mipmap-xxhdpi', 144],
  ['mipmap-xxxhdpi', 192]
];

/* ---------------- 画布 ---------------- */
function canvas(n) {
  return { n: n, px: new Float64Array(n * n * 4) };
}

function blend(c, i, r, g, b, a) {
  if (a <= 0) return;
  const ia = 1 - a;
  c.px[i] = c.px[i] * ia + r * a;
  c.px[i + 1] = c.px[i + 1] * ia + g * a;
  c.px[i + 2] = c.px[i + 2] * ia + b * a;
  c.px[i + 3] = c.px[i + 3] * ia + a;
}

function hex(s) {
  const v = s.replace('#', '');
  const has8 = v.length === 8;
  const o = has8 ? 2 : 0;
  return {
    a: has8 ? parseInt(v.slice(0, 2), 16) / 255 : 1,
    r: parseInt(v.slice(o, o + 2), 16),
    g: parseInt(v.slice(o + 2, o + 4), 16),
    b: parseInt(v.slice(o + 4, o + 6), 16)
  };
}

function lerp(a, b, t) { return a + (b - a) * t; }

/* ---------------- 图元（坐标为 0..VB 设计单位） ---------------- */
function each(c, fn) {
  const n = c.n, k = n / VB;
  for (let y = 0; y < n; y++) {
    const uy = (y + 0.5) / k;
    for (let x = 0; x < n; x++) {
      const ux = (x + 0.5) / k;
      fn((y * n + x) * 4, ux, uy);
    }
  }
}

function linearGrad(c, stops) {
  each(c, (i, x, y) => {
    const t = Math.min(1, Math.max(0, (x + y) / (VB * 2)));
    let a = stops[0], b = stops[stops.length - 1];
    for (let s = 0; s < stops.length - 1; s++) {
      if (t >= stops[s].o && t <= stops[s + 1].o) { a = stops[s]; b = stops[s + 1]; break; }
    }
    const span = (b.o - a.o) || 1;
    const u = (t - a.o) / span;
    const ca = hex(a.c), cb = hex(b.c);
    blend(c, i, lerp(ca.r, cb.r, u), lerp(ca.g, cb.g, u), lerp(ca.b, cb.b, u), 1);
  });
}

function radialGlow(c, cx, cy, r, color, maxA) {
  const col = hex(color);
  each(c, (i, x, y) => {
    const d = Math.hypot(x - cx, y - cy);
    if (d >= r) return;
    const a = maxA * (1 - d / r) * (1 - d / r);
    blend(c, i, col.r, col.g, col.b, a);
  });
}

/* 内容坐标：绕中心放大 */
function tx(v) { return (v - VB / 2) * CONTENT_SCALE + VB / 2; }

function circle(c, cx0, cy0, r0, color) {
  const cx = tx(cx0), cy = tx(cy0), r = r0 * CONTENT_SCALE;
  const col = hex(color), aa = VB / c.n;   // 一个像素对应的设计单位
  each(c, (i, x, y) => {
    const d = Math.hypot(x - cx, y - cy) - r;
    if (d > aa) return;
    const a = col.a * Math.min(1, Math.max(0, 0.5 - d / aa));
    blend(c, i, col.r, col.g, col.b, a);
  });
}

function line(c, x10, y10, x20, y20, w0, color) {
  const x1 = tx(x10), y1 = tx(y10), x2 = tx(x20), y2 = tx(y20);
  const hw = (w0 * CONTENT_SCALE) / 2;
  const col = hex(color), aa = VB / c.n;
  const dx = x2 - x1, dy = y2 - y1, len2 = dx * dx + dy * dy || 1;
  each(c, (i, x, y) => {
    let t = ((x - x1) * dx + (y - y1) * dy) / len2;
    t = Math.min(1, Math.max(0, t));
    const d = Math.hypot(x - (x1 + t * dx), y - (y1 + t * dy)) - hw;
    if (d > aa) return;
    const a = col.a * Math.min(1, Math.max(0, 0.5 - d / aa));
    blend(c, i, col.r, col.g, col.b, a);
  });
}

/* 圆角矩形 / 圆形遮罩（保留 alpha） */
function maskRounded(c, radius0) {
  const r = radius0, aa = VB / c.n;
  each(c, (i, x, y) => {
    const qx = Math.max(r - x, x - (VB - r), 0);
    const qy = Math.max(r - y, y - (VB - r), 0);
    const d = Math.hypot(qx, qy) - r;
    const keep = Math.min(1, Math.max(0, 0.5 - d / aa));
    c.px[i + 3] *= keep;
  });
}

function maskCircle(c) {
  const r = VB / 2, aa = VB / c.n;
  each(c, (i, x, y) => {
    const d = Math.hypot(x - VB / 2, y - VB / 2) - r;
    const keep = Math.min(1, Math.max(0, 0.5 - d / aa));
    c.px[i + 3] *= keep;
  });
}

/* ---------------- 图标绘制 ---------------- */
function drawIcon(n) {
  const c = canvas(n);
  linearGrad(c, [
    { o: 0, c: '#253349' },
    { o: 0.55, c: '#162032' },
    { o: 1, c: '#0A101C' }
  ]);
  radialGlow(c, VB / 2, VB / 2, 40, '#38BDF8', 0.22);

  const links = '#8CD8F5';
  line(c, 54, 54, 31, 35, 3.2, links);
  line(c, 54, 54, 77, 35, 3.2, links);
  line(c, 54, 54, 31, 73, 3.2, links);
  line(c, 54, 54, 77, 73, 3.2, links);

  circle(c, 31, 35, 6.5, '#E8F1F8');
  circle(c, 77, 35, 6.5, '#E8F1F8');
  circle(c, 31, 73, 6.5, '#E8F1F8');
  circle(c, 77, 73, 6.5, '#E8F1F8');

  circle(c, 54, 54, 10.5, '#38BDF8');
  circle(c, 54, 54, 4.8, '#0B1220');
  return c;
}

/* ---------------- 降采样 + PNG 编码 ---------------- */
function downsample(c, out) {
  const k = c.n / out;
  const buf = Buffer.alloc(out * out * 4);
  for (let y = 0; y < out; y++) {
    for (let x = 0; x < out; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let dy = 0; dy < k; dy++) {
        for (let dx = 0; dx < k; dx++) {
          const i = (((y * k + dy) | 0) * c.n + ((x * k + dx) | 0)) * 4;
          r += c.px[i] * c.px[i + 3];
          g += c.px[i + 1] * c.px[i + 3];
          b += c.px[i + 2] * c.px[i + 3];
          a += c.px[i + 3];
        }
      }
      const cnt = k * k;
      const o = (y * out + x) * 4;
      const av = a / cnt;
      buf[o] = av > 0 ? Math.round(r / a) : 0;
      buf[o + 1] = av > 0 ? Math.round(g / a) : 0;
      buf[o + 2] = av > 0 ? Math.round(b / a) : 0;
      buf[o + 3] = Math.round(av * 255);
    }
  }
  return buf;
}

function crc32(buf) {
  let c, table = crc32.t;
  if (!table) {
    table = crc32.t = [];
    for (let n = 0; n < 256; n++) {
      c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c >>> 0;
    }
  }
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) crc = table[(crc ^ buf[i]) & 0xFF] ^ (crc >>> 8);
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

function png(rgba, size) {
  const raw = Buffer.alloc((size * 4 + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    rgba.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ]);
}

/* ---------------- 主流程 ---------------- */
function main() {
  let total = 0;
  console.log('\n  生成传统启动图标（PNG）');
  console.log('  ----------------------------------------');
  SIZES.forEach(function (pair) {
    const dir = path.join(OUT_ROOT, pair[0]);
    const size = pair[1];
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });

    const sq = drawIcon(size * SS);
    maskRounded(sq, 22);
    const sqBuf = png(downsample(sq, size), size);
    fs.writeFileSync(path.join(dir, 'ic_launcher.png'), sqBuf);

    const rd = drawIcon(size * SS);
    maskCircle(rd);
    const rdBuf = png(downsample(rd, size), size);
    fs.writeFileSync(path.join(dir, 'ic_launcher_round.png'), rdBuf);

    // 移走 AS 模板遗留的绿色机器人（同名 webp 会与 png 冲突导致 aapt 报重复资源）
    ['ic_launcher.webp', 'ic_launcher_round.webp'].forEach(function (f) {
      const p = path.join(dir, f);
      if (!fs.existsSync(p)) return;
      if (!fs.existsSync(ATTIC)) fs.mkdirSync(ATTIC, { recursive: true });
      fs.renameSync(p, path.join(ATTIC, pair[0] + '_' + f));
    });

    total += sqBuf.length + rdBuf.length;
    console.log('  ' + pair[0].padEnd(18) + String(size + 'px').padEnd(7) +
      (((sqBuf.length + rdBuf.length) / 1024).toFixed(1) + ' KB'));
  });
  console.log('  ----------------------------------------');
  console.log('  合计 ' + (total / 1024).toFixed(1) + ' KB\n');
}

main();
