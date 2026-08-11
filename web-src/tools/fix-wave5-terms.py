#!/usr/bin/env python3
"""Wave 5: 术语统一 — 输出"现网"上下文 + "故障排查"→"排障"替换"""
import json

P = '.cache/phases.json'
d = json.load(open(P, encoding='utf-8'))
text = json.dumps(d, ensure_ascii=False)

# 1. 输出所有"现网"上下文
import re
print('=== "现网" 上下文 ===')
for m in re.finditer(r'.{18}现网.{18}', text):
    print(' ', m.group(0).replace('\n', '\\n'))

# 2. "故障排查" → "排障"
for phase in d:
    for mod in phase['modules']:
        for k, v in mod.items():
            if isinstance(v, str) and '故障排查' in v:
                mod[k] = v.replace('故障排查', '排障')
            elif isinstance(v, list):
                mod[k] = [x.replace('故障排查', '排障') if isinstance(x, str) else x for x in v]

json.dump(d, open(P, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print('\n"故障排查"→"排障" 替换完成')
