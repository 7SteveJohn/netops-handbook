/* 校验 APK 内 assets/index.html 是否为当前构建产物（纯 Node zip 读取，无依赖） */
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const crypto = require('crypto');

const APK = path.resolve(__dirname, '../../app/build/outputs/apk/debug/app-debug.apk');
const SRC = path.resolve(__dirname, '../../app/src/main/assets/index.html');

if (!fs.existsSync(APK)) { console.log('未找到 APK：' + APK); process.exit(1); }
const buf = fs.readFileSync(APK);

/* 定位 EOCD */
let eocd = -1;
for (let i = buf.length - 22; i >= 0 && i > buf.length - 66000; i--) {
  if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
}
if (eocd < 0) { console.log('不是有效的 zip'); process.exit(1); }

const count = buf.readUInt16LE(eocd + 10);
let off = buf.readUInt32LE(eocd + 16);

const entries = [];
for (let i = 0; i < count; i++) {
  if (buf.readUInt32LE(off) !== 0x02014b50) break;
  const method = buf.readUInt16LE(off + 10);
  const csize = buf.readUInt32LE(off + 20);
  const usize = buf.readUInt32LE(off + 24);
  const nlen = buf.readUInt16LE(off + 28);
  const elen = buf.readUInt16LE(off + 30);
  const clen = buf.readUInt16LE(off + 32);
  const lho = buf.readUInt32LE(off + 42);
  const name = buf.toString('utf8', off + 46, off + 46 + nlen);
  entries.push({ name, method, csize, usize, lho });
  off += 46 + nlen + elen + clen;
}

function read(e) {
  const n = buf.readUInt16LE(e.lho + 26);
  const x = buf.readUInt16LE(e.lho + 28);
  const start = e.lho + 30 + n + x;
  const raw = buf.slice(start, start + e.csize);
  return e.method === 0 ? raw : zlib.inflateRawSync(raw);
}

console.log('\n  APK 校验');
console.log('  ------------------------------------------------');
console.log('  文件      ' + (buf.length / 1024 / 1024).toFixed(2) + ' MB / ' + entries.length + ' 条目');

const idx = entries.find(e => e.name === 'assets/index.html');
if (!idx) { console.log('  ✗ APK 内没有 assets/index.html'); process.exit(1); }

const inApk = read(idx);
const onDisk = fs.readFileSync(SRC);
const h = b => crypto.createHash('sha1').update(b).digest('hex').slice(0, 12);

console.log('  index.html  APK 内 ' + (inApk.length / 1024).toFixed(1) + ' KB  /  磁盘 ' + (onDisk.length / 1024).toFixed(1) + ' KB');
console.log('  sha1        ' + h(inApk) + '  /  ' + h(onDisk));
console.log('  一致性      ' + (inApk.equals(onDisk) ? '✓ 完全一致（APK 是最新产物）' : '✗ 不一致（APK 为旧版本，需重新构建）'));

const t = inApk.toString('utf8');
console.log('  ------------------------------------------------');
[['NetOpsBack 返回栈桥接', 'NetOpsBack'],
 ['NetOpsSetInsets 安全区', 'NetOpsSetInsets'],
 ['NetBridge 原生桥',      'NetBridge'],
 ['SVG 图标 sprite',       'i-chev-left']
].forEach(p => console.log('  ' + (t.includes(p[1]) ? '✓' : '✗') + ' ' + p[0]));

const dex = entries.filter(e => e.name.endsWith('.dex'));
const mani = entries.find(e => e.name === 'AndroidManifest.xml');
if (mani) {
  const m = read(mani).toString('utf16le');
  console.log('  ' + (m.includes('permission.INTERNET') ? '✗ 仍含 INTERNET 权限' : '✓ 无 INTERNET 权限（纯离线）'));
}
console.log('  dex ' + dex.length + ' 个，合计 ' + (dex.reduce((s, e) => s + e.usize, 0) / 1024 / 1024).toFixed(2) + ' MB');
console.log('');
