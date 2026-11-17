/* ============================================================
 * gen-textbook.js — 教材知识库 → App assets 管线（2026-11-18）
 * 输入: ../教材知识库/<NN-领域>/<MM-章>.md（workflow 生成的教材全文，
 *       本地私有、不入库；公开仓库无此目录 → 静默跳过）
 * 输出: ../app/src/main/assets/textbook/
 *   index.json   书架索引（12 领域 → 50 章，小节锚点/字数）
 *   ch/<id>.json 每章 blocks（单行 JSON，阅读页按章懒加载）
 *   search.json  全文倒排索引（中文 2-gram + 拉丁词，块级快照）
 * 对账门禁: 12 领域 / 50 章 / 分布 5-7-5-3-4-5-8-5-3-1-2-2
 *          （00-知识地图 §三 的全书约定，漂移即构建失败）
 * ============================================================ */
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const REPO = path.resolve(__dirname, '..', '..');
const SRC = path.join(REPO, '教材知识库');
const OUT = path.join(REPO, 'app', 'src', 'main', 'assets', 'textbook');
/* 00-知识地图 §三 的领域章数分布（12 领域），对账门禁依据 */
const EXPECT_DIST = [5, 7, 5, 3, 4, 5, 8, 5, 3, 1, 2, 2];

if (!fs.existsSync(SRC)) {
  console.log('  · 跳过 textbook 生成（此环境无教材源 教材知识库/）');
  return;
}

/* ---- 1. 行内解析：**粗** / `码` → rich 段 ---- */
function parseInline(s) {
  const segs = [];
  const codeParts = String(s).split(/`([^`]*)`/);
  for (let i = 0; i < codeParts.length; i++) {
    if (i % 2 === 1) { if (codeParts[i]) segs.push({ t: 'c', s: codeParts[i] }); continue; }
    const boldParts = codeParts[i].split(/\*\*(.+?)\*\*/);
    for (let j = 0; j < boldParts.length; j++) {
      if (!boldParts[j]) continue;
      segs.push({ t: j % 2 === 1 ? 'b' : 'plain', s: boldParts[j] });
    }
  }
  return segs;
}
const stripInline = s =>
  String(s).replace(/`([^`]*)`/g, '$1').replace(/\*\*(.+?)\*\*/g, '$1');

/* ---- 2. 章解析：markdown 子集 → blocks[] ---- */
function parseChapter(md) {
  const lines = md.split(/\r?\n/);
  const blocks = [];
  let title = '';
  const push = b => { b.bi = blocks.length; blocks.push(b); return b; };

  let i = 0;
  while (i < lines.length) {
    const raw = lines[i];
    const line = raw.trim();

    if (!line) { i++; continue; }

    /* 围栏代码块 */
    const fence = raw.match(/^\s*(`{3,}|~{3,})\s*([A-Za-z0-9_.+-]*)\s*$/);
    if (fence) {
      const mark = fence[1][0];
      const label = fence[2] || '';
      const body = [];
      i++;
      while (i < lines.length && !new RegExp('^\\s*' + mark + '{3,}\\s*$').test(lines[i])) {
        body.push(lines[i]); i++;
      }
      i++; // 越过闭合围栏
      push({ k: 'code', t: body.join('\n'), label });
      continue;
    }

    /* 标题 */
    const h = line.match(/^(#{1,4})\s+(.*)$/);
    if (h) {
      const text = h[2].trim();
      if (h[1].length === 1 && !title) {
        /* 剥「第 N 章」/「NN ·」前缀：书架章卡已有编号 chip，避免重复 */
        title = text.replace(/^(第\s*\d+\s*章|\d{1,2}\s*[·.、:：-])\s*/, '').trim();
      }
      else if (h[1].length === 2) push({ k: 'h2', t: text });
      else push({ k: 'h', t: text });
      i++;
      continue;
    }

    /* 水平线 */
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(line)) { push({ k: 'hr' }); i++; continue; }

    /* GFM 表格：本行含 | 且下一行是分隔行 */
    if (line.startsWith('|') && i + 1 < lines.length &&
        /^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(lines[i + 1]) && /-/.test(lines[i + 1])) {
      const cells = l => l.trim().replace(/^\|/, '').replace(/\|\s*$/, '').split('|').map(c => c.trim());
      const head = cells(line).map(parseInline);
      i += 2;
      const rows = [];
      while (i < lines.length && /^\s*\|/.test(lines[i]) && lines[i].trim().startsWith('|')) {
        rows.push(cells(lines[i]).map(parseInline)); i++;
      }
      push({ k: 'table', head, rows });
      continue;
    }

    /* 引用块（章首导语等） */
    if (line.startsWith('>')) {
      const body = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) {
        body.push(lines[i].trim().replace(/^>\s?/, '')); i++;
      }
      push({ k: 'note', tone: 'plain', rich: parseInline(body.join('\n')) });
      continue;
    }

    /* 无序 / 有序列表（缩进续行并入上一条） */
    const ulM = raw.match(/^\s*[-*+]\s+(.*)$/);
    const olM = raw.match(/^\s*(\d{1,3})[.)]\s+(.*)$/);
    if (ulM || olM) {
      const isOl = !!olM;
      const items = [];
      while (i < lines.length) {
        const cur = lines[i];
        const m = cur.match(/^\s*[-*+]\s+(.*)$/) || (isOl ? cur.match(/^\s*\d{1,3}[.)]\s+(.*)$/) : null);
        if (m) { items.push(stripInline(m[1]).trim()); i++; continue; }
        if (/^\s{2,}\S/.test(cur) && items.length && !/^\s*(`{3,}|#{1,4}\s|\||>)/.test(cur)) {
          items[items.length - 1] += ' ' + stripInline(cur.trim()); i++; continue;
        }
        break;
      }
      push({ k: isOl ? 'ol' : 'ul', items });
      continue;
    }

    /* 段落：累积到空行/下一结构行 */
    if (!title) { title = line; i++; continue; }
    const para = [line];
    i++;
    while (i < lines.length) {
      const nx = lines[i];
      const t2 = nx.trim();
      if (!t2 || /^\s*(`{3,}|#{1,4}\s|\||>|-{3,}|\*{3,})/.test(nx) ||
          /^\s*[-*+]\s+/.test(nx) || /^\s*\d{1,3}[.)]\s+/.test(nx)) break;
      para.push(t2); i++;
    }
    push({ k: 'p', rich: parseInline(para.join('\n')) });
  }
  return { title, blocks };
}

/* ---- 3. 收目录 ---- */
const domainDirs = fs.readdirSync(SRC)
  .filter(d => /^\d{2}-/.test(d) && fs.statSync(path.join(SRC, d)).isDirectory())
  .sort();
const domains = [];
const flatChapters = [];
domainDirs.forEach((dir, di) => {
  const dTitle = dir.replace(/^\d{2}-/, '').replace(/-/g, ' ');
  const domainId = 'd' + String(di + 1).padStart(2, '0');
  const files = fs.readdirSync(path.join(SRC, dir))
    .filter(f => /^\d{2}-.*\.md$/.test(f)).sort();
  const chapters = files.map((f, ci) => {
    const md = fs.readFileSync(path.join(SRC, dir, f), 'utf8');
    const { title, blocks } = parseChapter(md);
    const id = domainId + 'c' + String(ci + 1).padStart(2, '0');
    const han = (md.match(/[\u4e00-\u9fff]/g) || []).length;
    const sections = blocks.filter(b => b.k === 'h2')
      .map(b => ({ bi: b.bi, t: b.t }));
    const chapter = {
      id, n: ci + 1, title: title || f.replace(/^\d{2}-/, '').replace(/\.md$/, ''),
      sub: `${sections.length} 小节 · 约 ${(han / 10000).toFixed(1)} 万字`,
      words: han, sections,
      blocksN: blocks.length, file: 'ch/' + id + '.json'
    };
    flatChapters.push({ chapter, domainId, domainTitle: dTitle, blocks, di });
    return chapter;
  });
  domains.push({ id: domainId, n: di + 1, title: dTitle, chapters });
});

/* ---- 4. 对账门禁（00-知识地图 §三 约定，漂移即失败） ---- */
const dist = domains.map(d => d.chapters.length);
const errs = [];
if (domains.length !== 12) errs.push(`领域数 ${domains.length} ≠ 12`);
const total = dist.reduce((a, b) => a + b, 0);
if (total !== 50) errs.push(`章数 ${total} ≠ 50`);
if (JSON.stringify(dist) !== JSON.stringify(EXPECT_DIST)) {
  errs.push(`分布 ${dist.join('-')} ≠ ${EXPECT_DIST.join('-')}`);
}
flatChapters.forEach(c => { if (!c.blocks.length) errs.push(`${c.chapter.id} 无 blocks`); });
/* 编号直连（G/LAB/IVQ）跨章出现统计：矩阵章（d11）与面试章（d12）合法引用
   全书编号，按章去重后单行摘要，不逐条刷屏 */
{
  const byTok = new Map();
  flatChapters.forEach(c => {
    const toks = new Set((c.chapter.title + ' ' + c.blocks.map(b => b.t || '').join(' '))
      .match(/\b(?:G|LAB|IVQ)\d+\b/g) || []);
    toks.forEach(tok => {
      if (!byTok.has(tok)) byTok.set(tok, new Set());
      byTok.get(tok).add(c.chapter.id);
    });
  });
  const cross = [...byTok.entries()].filter(([, s]) => s.size > 1);
  if (cross.length) {
    console.log(`  ! ${cross.length} 个编号跨多章出现（案例矩阵/面试章为合法交叉引用）`);
  }
}
if (errs.length) {
  console.error('\n  ✗ 教材对账门禁未通过：\n    - ' + errs.join('\n    - '));
  process.exit(1);
}

/* ---- 5. 全文倒排索引（小节粒度：中文 2-gram + 拉丁词，块级冗余塌缩为 h2 单元） ----
 * 一个 h2 小节 = 一个索引单元（章首到首个 h2 前 = 单元 0）：单元内 token 去重，
 * 命中粒度 = 小节（跳转到 h2 块），快照 = 单元首个有文本块的 80 字预览。 */
function tokensOf(text) {
  const out = new Set();
  const s = String(text).toLowerCase();
  for (const m of s.matchAll(/[a-z0-9][a-z0-9._+-]*/g)) if (m[0].length >= 2) out.add(m[0]);
  for (const m of s.matchAll(/[\u4e00-\u9fff]{2,}/g)) {
    const w = m[0];
    for (let k = 0; k + 2 <= w.length; k++) out.add(w.slice(k, k + 2));
  }
  return out;
}
const blockText = b => {
  switch (b.k) {
    case 'p': case 'note': return (b.rich || []).map(g => g.s).join('');
    case 'table': return (b.head || []).map(g => g.map(x => x.s).join('')).join(' ') + ' ' +
      (b.rows || []).map(r => r.map(c => c.map(x => x.s).join('')).join(' ')).join(' ');
    case 'ul': case 'ol': return (b.items || []).join(' ');
    case 'code': return b.t || '';
    default: return b.t || '';
  }
};
const snips = [];
const terms = new Map();
function indexUnit(ci, bi, unitTitle, blocks) {
  const toks = new Set();
  let preview = '';
  blocks.forEach(b => {
    const text = blockText(b);
    if (!text.trim()) return;
    if (!preview) preview = text.replace(/\s+/g, ' ').trim().slice(0, 80);
    tokensOf(text).forEach(w => toks.add(w));
  });
  tokensOf(unitTitle).forEach(w => toks.add(w));
  if (!toks.size) return;
  const si = snips.push([ci, bi, unitTitle.slice(0, 40), preview]) - 1;
  toks.forEach(w => {
    let arr = terms.get(w);
    if (!arr) { arr = []; terms.set(w, arr); }
    arr.push(si);
  });
}
flatChapters.forEach((c, idx) => {
  let unit = [], unitTitle = c.chapter.title, unitBi = 0;
  c.blocks.forEach(b => {
    if (b.k === 'h2') {
      indexUnit(idx, unitBi, unitTitle, unit);
      unit = []; unitTitle = b.t; unitBi = b.bi;
    } else unit.push(b);
  });
  indexUnit(idx, unitBi, unitTitle, unit);
});
const searchJson = {
  v: 1,
  ch: flatChapters.map(c => [c.chapter.id, c.chapter.title]),
  snips,
  terms: Object.fromEntries([...terms.entries()].sort())
};

/* ---- 6. 写出（全部单行 JSON：与 native-data.json 同款，diff 行数少） ---- */
/* 章内嵌 sections/prev/next：阅读页只读一个文件即自洽（书架仍走 index.json） */
const order = flatChapters.map(c => c.chapter.id);
flatChapters.forEach((c, k) => {
  c.chapter.prev = k > 0 ? order[k - 1] : '';
  c.chapter.next = k < order.length - 1 ? order[k + 1] : '';
});
fs.mkdirSync(path.join(OUT, 'ch'), { recursive: true });
for (const f of fs.readdirSync(path.join(OUT, 'ch'))) fs.unlinkSync(path.join(OUT, 'ch', f));
const index = {
  v: 1, gen: new Date().toISOString(),
  counts: { domains: domains.length, chapters: flatChapters.length,
            blocks: flatChapters.reduce((a, c) => a + c.blocks.length, 0) },
  domains
};
fs.writeFileSync(path.join(OUT, 'index.json'), JSON.stringify(index));
flatChapters.forEach(c => {
  const doc = {
    id: c.chapter.id, title: c.chapter.title,
    domain: c.domainId, domainTitle: c.domainTitle,
    words: c.chapter.words, sections: c.chapter.sections,
    prev: c.chapter.prev, next: c.chapter.next,
    blocks: c.blocks
  };
  fs.writeFileSync(path.join(OUT, 'ch', c.chapter.id + '.json'), JSON.stringify(doc));
});
fs.writeFileSync(path.join(OUT, 'search.json'), JSON.stringify(searchJson));

const kb = p => (fs.statSync(p).size / 1024).toFixed(1) + 'KB';
console.log('[textbook] 领域', domains.length, '/ 章', flatChapters.length,
            '/ blocks', index.counts.blocks, '/ 词条', Object.keys(searchJson.terms).length);
console.log('[textbook] index', kb(path.join(OUT, 'index.json')),
            '| search', kb(path.join(OUT, 'search.json')),
            '| hash', crypto.createHash('sha1')
              .update(JSON.stringify(searchJson.terms)).digest('hex').slice(0, 12));
