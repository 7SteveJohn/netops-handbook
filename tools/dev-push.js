#!/usr/bin/env node
/* 热加载推送：把构建产物直推手机应用专属目录，配合 MainActivity 的 page() 钩子
   （读 files/devpage 决定加载源），改 web-src 后免重打包、免文件互传。
   ------------------------------------------------------------
   一次性前提：
     1. 手机装上含 page() 钩子的 APK（2026-09-24 之后的构建）
     2. 开发者选项 → USB 调试已开，插线并在手机上授权
   日常循环（改 web-src 后）：
     cd web-src && node build.js && node tools/dev-push.js
     脚本会自动杀进程重启应用，即见新效果
   壁纸文件变更时：node tools/dev-push.js --wall （平时不用，壁纸 5MB 推得慢）
   回退内置版：  node tools/dev-push.js --off
   若 adb push 报 Permission denied：该 ROM 拦 adb 写 Android/data，
   本方案在此设备不可用，需改走 http://127.0.0.1 + adb reverse（需 debug 包）。
   ------------------------------------------------------------ */
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const PKG = 'com.netops.handbook';
const EXT = `/sdcard/Android/data/${PKG}/files`;
const DEV_URL = `file:///storage/emulated/0/Android/data/${PKG}/files/www/index.html`;
const ASSETS = path.resolve(__dirname, '../../app/src/main/assets');

const adb = args => {
  try { return execFileSync('adb', args, { encoding: 'utf8' }); }
  catch (e) {
    console.error('adb 失败：' + ((e.stderr || '') + (e.message || '')).trim());
    process.exit(1);
  }
};

const mode = process.argv[2] || '';
const devs = adb(['devices']).trim().split('\n').filter(l => l.endsWith('\tdevice'));
if (devs.length === 0) {
  console.error('无在线设备：插线，并在手机上允许 USB 调试授权弹窗。');
  process.exit(1);
}

if (mode === '--off') {
  adb(['shell', `rm -f '${EXT}/devpage'`]);
  adb(['shell', `am force-stop ${PKG}`]);
  console.log('已清除 devpage 并重启应用，回退内置 assets 版。');
  process.exit(0);
}

if (!fs.existsSync(path.join(ASSETS, 'index.html'))) {
  console.error(`找不到构建产物 ${ASSETS}/index.html —— 先在 web-src 下跑 node build.js`);
  process.exit(1);
}

adb(['shell', `mkdir -p '${EXT}/www/wallpapers'`]);
adb(['push', path.join(ASSETS, 'index.html'), `${EXT}/www/index.html`]);

if (mode === '--wall') {
  const wDir = path.join(ASSETS, 'wallpapers');
  for (const f of fs.readdirSync(wDir).filter(f => f.endsWith('.webp'))) {
    adb(['push', path.join(wDir, f), `${EXT}/www/wallpapers/${f}`]);
  }
  console.log('壁纸已同步（' + fs.readdirSync(wDir).filter(f => f.endsWith('.webp')).length + ' 张）');
}

adb(['shell', `echo '${DEV_URL}' > '${EXT}/devpage'`]);
adb(['shell', `am force-stop ${PKG}`]);
adb(['shell', `monkey -p ${PKG} -c android.intent.category.LAUNCHER 1`]);
console.log('已推送并自动重启应用（加载推送页）。');
console.log('提示：file:// 新 origin 的 localStorage 独立，玻璃/壁纸设置需重设一次；');
console.log('      调 CSS 时电脑 Chrome 访问 chrome://inspect 可直连这个页面。');
