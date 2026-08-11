import re

SELECTORS = [
    r'card(?![-\w])', r'sec(?![-\w])',
    r'note--info(?![-\w])', r'note--plain(?![-\w])', r'note--warn(?![-\w])', r'note--danger(?![-\w])',
    r'btn--primary(?![-\w])', r'chips(?![-\w])', r'tablewrap(?![-\w])',
    r'btn--soft(?![-\w])', r'btn--ok(?![-\w])', r'btn--danger(?![-\w])',
    r'term(?![-\w])', r'cli(?![-\w])', r'fab(?![-\w])',
    r'phase(?![-\w])', r'stat(?![-\w])', r'list(?![-\w])', r'list__item(?![-\w])',
    r'dict__row(?![-\w])', r'qz__item(?![-\w])'
]

def remove_body_glass_on(content):
    out = []
    i = 0
    while i < len(content):
        m = re.search(r'(?:html\.dark\s+)?body\.glass-on\s+([^{};]+)\{', content[i:])
        if not m:
            out.append(content[i:])
            break
        abs_start = i + m.start()
        abs_brace = i + m.end() - 1
        sel = m.group(1).strip()
        depth = 1
        j = abs_brace + 1
        while j < len(content) and depth > 0:
            if content[j] == '{': depth += 1
            elif content[j] == '}': depth -= 1
            j += 1
        abs_end = j
        hit = any(re.search(r'(?<![\w-])\.' + s + r'(?![\w-])', sel) for s in SELECTORS)
        nl = content.find('\n', abs_end)
        skip = nl + 1 if nl != -1 else abs_end
        if hit:
            out.append(content[i:abs_start])
            i = skip
        else:
            out.append(content[i:abs_end])
            i = abs_end
    return ''.join(out)

for fp in ['css/04-components.css', 'css/05-views.css']:
    with open(fp, encoding='utf-8') as f:
        src = f.read()
    new = remove_body_glass_on(src)
    with open(fp, 'w', encoding='utf-8') as f:
        f.write(new)
    delta = len(src) - len(new)
    print(f'{fp}: - {delta} chars ({len(src)} -> {len(new)})')
