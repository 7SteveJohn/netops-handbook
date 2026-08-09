/* ============================================================
 * NetOps 2.0 · 冒烟测试
 * 在 jsdom 中加载构建产物，逐视图渲染并模拟交互，捕获运行时报错
 *   node web-src/tools/smoke.js
 * ============================================================ */
const fs = require('fs');
const path = require('path');
const Module = require('module');

/* 让 jsdom 从托管 workspace 解析 */
const WS = 'C:/Users/SevenJohn/.workbuddy/binaries/node/workspace/node_modules';
if (!Module.globalPaths.includes(WS)) Module.globalPaths.push(WS);
process.env.NODE_PATH = WS;
Module._initPaths();

const { JSDOM } = require('jsdom');

const FILE = path.resolve(__dirname, '../../app/src/main/assets/index.html');
const html = fs.readFileSync(FILE, 'utf8');

const errors = [];
const dom = new JSDOM(html, {
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  url: 'file:///android_asset/index.html',
  beforeParse(w) {
    w.matchMedia = q => ({
      matches: false, media: q,
      addEventListener() {}, removeEventListener() {},
      addListener() {}, removeListener() {}
    });
    w.scrollTo = () => {};
    w.navigator.vibrate = () => true;
    Object.defineProperty(w.Element.prototype, 'scrollIntoView', { value: () => {} });
    w.requestAnimationFrame = fn => setTimeout(() => fn(Date.now()), 0);
    /* 拦截任何网络请求 —— 离线应用不应发出任何请求 */
    const net = [];
    w.fetch = (...a) => { net.push(String(a[0])); return Promise.reject(new Error('offline')); };
    w.__net = net;
    w.addEventListener('error', e => errors.push('window.error: ' + (e.error ? e.error.stack : e.message)));
    w.addEventListener('unhandledrejection', e => errors.push('unhandled: ' + e.reason));
  }
});

const w = dom.window, d = w.document;
const origErr = w.console.error;
w.console.error = (...a) => { errors.push('console.error: ' + a.join(' ')); origErr(...a); };

function wait(ms) { return new Promise(r => setTimeout(r, ms)); }
const ok = [], bad = [];
function check(name, fn) {
  try {
    const r = fn();
    if (r === false) { bad.push(name); console.log('  ✗ ' + name); }
    else { ok.push(name); console.log('  ✓ ' + name + (typeof r === 'string' ? '  ' + r : '')); }
  } catch (e) { bad.push(name + ' → ' + e.message); console.log('  ✗ ' + name + ' → ' + e.message); }
}

(async () => {
  await wait(300);
  console.log('\n  NetOps 2.0 冒烟测试');
  console.log('  ' + '-'.repeat(56));

  const A = w.NetApp, U = w.NetUI, V = w.NetViews;

  check('全局对象就绪', () => !!(A && U && V && w.NetTopo && w.NETOPS_CORE));
  check('数据规模', () => `${A.MODS.length} 模块 / ${A.CORE.faults.length} 排障 / ${A.CORE.interview.length} 面试 / ${A.CORE.dict.rows.length} 命令 / ${A.QUIZ.length} 测验题`);
  check('首屏已渲染', () => d.querySelector('#view').innerHTML.length > 2000);
  check('图标 sprite 已内联', () => d.querySelectorAll('svg symbol').length >= 50 && d.querySelectorAll('svg symbol').length + ' 个图标');

  /* 逐视图渲染 */
  const routes = ['learn', 'fault', 'dict', 'iv', 'me', 'refs', 'glossary', 'roadmap', 'quiz', 'cli', 'fav'];
  for (const r of routes) {
    A.go(r);
    await wait(30);
    const len = d.querySelector('#view').innerHTML.length;
    check('视图 ' + r.padEnd(9), () => len > 200 ? len + ' 字符' : false);
  }

  /* 阶段页 + 卡片展开 */
  A.go('phase', 'p-1'); await wait(40);
  check('阶段页卡片数', () => d.querySelectorAll('#view .card[data-id]').length === 8);
  const head = d.querySelector('#view .card[data-id] [data-toggle]');
  head.dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await wait(60);
  check('卡片展开渲染正文', () => {
    const inner = d.querySelector('#view .card [data-lazy]');
    return inner.innerHTML.length > 300 ? inner.innerHTML.length + ' 字符' : false;
  });
  check('卡片内含拓扑 SVG', () => !!d.querySelector('#view .topo svg'));
  check('卡片内含终端块', () => !!d.querySelector('#view .term__body'));

  /* 打卡 */
  const doneBtn = d.querySelector('#view [data-done]');
  doneBtn.dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await wait(30);
  check('打卡写入进度', () => A.doneAll() === 1);
  check('顶部进度条更新', () => d.querySelector('#barFill').style.width !== '' && d.querySelector('#barFill').style.width);

  /* 收藏 */
  const favBtn = d.querySelector('#view [data-fav]');
  favBtn.dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await wait(20);
  check('收藏写入', () => Object.keys(A.S.fav).length === 1);
  A.go('fav'); await wait(30);
  check('收藏夹展示', () => d.querySelectorAll('#view .card[data-id]').length === 1);

  /* 拓扑渲染器四种类型全覆盖 */
  const kinds = {};
  A.MODS.forEach(m => { if (m.dg) kinds[m.dg.k] = (kinds[m.dg.k] || 0) + 1; });
  Object.keys(kinds).forEach(k => {
    const sample = A.MODS.find(m => m.dg && m.dg.k === k);
    check('拓扑 ' + k.padEnd(6) + '(' + kinds[k] + ' 处)', () => {
      const svg = w.NetTopo.render(sample.dg);
      return svg.indexOf('<svg') === 0 || svg.indexOf('<div class="topo">') === 0 ? svg.length + ' 字符' : false;
    });
  });
  check('全部拓扑无渲染失败', () => {
    let fail = 0;
    A.MODS.forEach(m => { if (m.dg && !w.NetTopo.render(m.dg)) fail++; });
    A.CORE.faults.forEach(f => { if (f.dg && !w.NetTopo.render(f.dg)) fail++; });
    return fail === 0 ? '0 失败' : false;
  });

  /* 搜索 */
  check('搜索 ospf', () => { const r = A.searchAll('ospf'); return r.n > 0 ? r.n + ' 条命中' : false; });
  check('搜索 vlan', () => { const r = A.searchAll('vlan'); return r.n > 0 ? r.n + ' 条命中' : false; });
  check('搜索 k8s', () => { const r = A.searchAll('pod'); return r.n > 0 ? r.n + ' 条命中' : false; });
  const q = d.querySelector('#q');
  q.value = '丢包';
  q.dispatchEvent(new w.Event('input', { bubbles: true }));
  await wait(400);
  check('搜索视图切换', () => d.querySelector('#barName').textContent === '搜索结果');

  /* 测验 */
  A.go('quiz'); await wait(30);
  d.querySelector('[data-quiz="start"]').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await wait(40);
  check('测验出题', () => d.querySelectorAll('#quizBox .qz__item').length === 10);
  d.querySelectorAll('#quizBox .qz__item').forEach(it => {
    const inp = it.querySelector('input');
    inp.checked = true;
    inp.dispatchEvent(new w.Event('change', { bubbles: true }));
  });
  d.querySelector('[data-quiz="submit"]').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await wait(60);
  check('测验判分', () => d.querySelectorAll('#quizBox .qz__fb').length === 10);

  /* CLI */
  A.go('cli'); await wait(40);
  const cin = d.querySelector('#cliIn');
  ['help', 'display ip interface brief', 'find vlan', 'mode lx', 'ip addr', 'blabla'].forEach(cmd => {
    cin.value = cmd;
    d.querySelector('#cliRun').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  });
  await wait(40);
  check('CLI 回显', () => {
    const n = d.querySelectorAll('#cliOut > div').length;
    return n > 12 ? n + ' 行输出' : false;
  });
  check('CLI 未知命令报错', () => d.querySelector('#cliOut').textContent.indexOf('Unrecognized') >= 0);

  /* 抽屉 */
  check('抽屉目录已构建', () => d.querySelectorAll('#drawerBody .tree__item').length === A.MODS.length);
  const jump = d.querySelector('#drawerBody [data-jump]');
  jump.dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await wait(200);
  check('抽屉跳转到阶段页', () => d.querySelector('#barName').textContent === '入门筑基');

  /* 分类筛选 */
  A.go('fault'); await wait(30);
  const chips = d.querySelectorAll('#view [data-chip]');
  chips[2].dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await wait(40);
  check('排障分类筛选', () => {
    const n = d.querySelectorAll('#view .card[data-id]').length;
    return n > 0 && n < A.CORE.faults.length ? n + ' 条' : false;
  });

  /* 主题 */
  d.querySelector('#btnTheme').dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await wait(20);
  check('主题切换生效', () => U.theme.get() !== 'auto' && U.theme.get());

  /* 返回栈（自建栈 + NetOpsBack 桥接） */
  A.go('me'); await wait(20);
  A.go('refs'); await wait(20);
  const consumed = w.NetOpsBack(); await wait(60);
  check('返回栈可用', () => consumed === true && d.querySelector('#barName').textContent !== '速查表');
  check('返回栈逐层回退', () => d.querySelector('#barName').textContent === '我的');
  let guard = 0;
  while (w.NetOpsBack() === true && guard++ < 80) { /* 一路退到根 */ }
  await wait(60);
  check('退到根后交还宿主', () => guard < 80 && w.NetOpsBack() === false);
  check('根页导航图标为菜单', () => d.querySelector('#navUse').getAttribute('href') === '#i-menu');

  /* 网络请求审计 */
  check('运行期零网络请求', () => w.__net.length === 0 ? '0 次 fetch' : false);
  check('文档无外部资源节点', () => {
    const els = [...d.querySelectorAll('[src],link[href]')];
    const ext = els.filter(e => /^(https?:)?\/\//.test(e.getAttribute('src') || e.getAttribute('href') || ''));
    return ext.length === 0 ? '0 个外链' : false;
  });

  console.log('  ' + '-'.repeat(56));
  if (errors.length) {
    console.log('\n  运行期错误 ' + errors.length + ' 条：');
    [...new Set(errors)].slice(0, 12).forEach(e => console.log('    ! ' + e));
  }
  console.log('\n  通过 ' + ok.length + ' / ' + (ok.length + bad.length) +
    (bad.length ? '，失败 ' + bad.length : '') + (errors.length ? '，运行期错误 ' + errors.length : '') + '\n');
  process.exit(bad.length || errors.length ? 1 : 0);
})();
