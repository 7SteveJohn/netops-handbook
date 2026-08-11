const fs = require('fs');
const { JSDOM, VirtualConsole } = require('C:/Users/SevenJohn/.workbuddy/binaries/node/workspace/node_modules/jsdom');

const HTML = fs.readFileSync('D:/Android/app/src/main/assets/index.html', 'utf8');

const errors = [];
const vc = new VirtualConsole();
vc.on('jsdomError', e => errors.push('jsdomError: ' + (e.detail ? e.detail.stack || e.detail : e.message)));
vc.on('error', (...a) => errors.push('console.error: ' + a.join(' ')));

const dom = new JSDOM(HTML, {
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  url: 'http://localhost/index.html',
  virtualConsole: vc,
  beforeParse(window) {
    // polyfills jsdom lacks
    if (!window.matchMedia) window.matchMedia = () => ({ matches: false, addEventListener(){}, removeEventListener(){}, addListener(){}, removeListener(){} });
    window.Element.prototype.scrollTo = function(){};
    window.scrollTo = function(){};
    // capture runtime exceptions
    window.addEventListener('error', ev => errors.push('window.error: ' + (ev.error ? ev.error.stack : ev.message)));
  }
});

const { window } = dom;
// scripts run synchronously on parse (no external), but boot may be deferred via DOMContentLoaded
setTimeout(() => {
  const out = [];
  const $ = s => window.document.querySelector(s);
  const A = window.NetApp;
  out.push('NetApp present: ' + !!A);
  if (!A) { console.log(out.join('\n')); console.log('ERRORS:\n' + errors.join('\n')); process.exit(1); }

  // navigate to settings
  try { A.go('me'); } catch (e) { errors.push('go(me) threw: ' + e.stack); }

  // 1) tabbar switch
  const sw = $('#swTabbar');
  const row = $('#tabbarModeRow');
  out.push('tabbar row #tabbarModeRow present: ' + !!row);
  out.push('tabbar switch #swTabbar present: ' + !!sw);
  if (row) {
    const before = window.document.body.classList.contains('tabbar-regular');
    try { row.dispatchEvent(new window.MouseEvent('click', { bubbles: true })); } catch(e){ errors.push('tabbar click threw: ' + e.stack); }
    const after = window.document.body.classList.contains('tabbar-regular');
    out.push('tabbar-regular before=' + before + ' afterClick=' + after + ' (toggled=' + (before!==after) + ')');
  }

  // 2) glass three switches
  const glassLbl = $('#glassLbl');
  out.push('glass label #glassLbl present: ' + !!glassLbl);
  if (glassLbl) {
    const li = glassLbl.closest('.list__item');
    try { li.dispatchEvent(new window.MouseEvent('click', { bubbles: true })); } catch(e){ errors.push('glass row click threw: ' + e.stack); }
  }
  const sheetOpen = $('#glassSheet') && $('#glassSheet').classList.contains('is-open');
  out.push('glass sheet opened: ' + sheetOpen);
  const body = $('#glassBody');
  const rows = body ? body.querySelectorAll('.glass-mode-row') : [];
  out.push('glass-mode-row count in panel: ' + rows.length);
  if (body) out.push('glassBody snippet: ' + body.innerHTML.slice(0, 300).replace(/\s+/g,' '));

  // 3) click the "liquid" glass-mode-row, verify body gets glass-liquid
  if (rows.length) {
    const liquidRow = body.querySelector('[data-gval="liquid"]');
    if (liquidRow) {
      const beforeLiquid = window.document.body.classList.contains('glass-liquid');
      try { liquidRow.dispatchEvent(new window.MouseEvent('click', { bubbles: true })); } catch(e){ errors.push('glass liquid click threw: ' + e.stack); }
      const afterLiquid = window.document.body.classList.contains('glass-liquid');
      out.push('glass-liquid before=' + beforeLiquid + ' afterClick=' + afterLiquid + ' (switched=' + (afterLiquid && !beforeLiquid) + ')');
      // verify only one mode class active
      const active = ['glass-frosted','glass-liquid','glass-gaussian'].filter(c => window.document.body.classList.contains(c));
      out.push('active glass mode classes after switch: [' + active.join(', ') + ']');
    }
  }

  console.log('=== SMOKE TEST RESULT ===');
  console.log(out.join('\n'));
  if (errors.length) {
    console.log('\n=== RUNTIME ERRORS (' + errors.length + ') ===');
    console.log(errors.join('\n'));
  } else {
    console.log('\nNo runtime errors captured.');
  }
}, 300);
