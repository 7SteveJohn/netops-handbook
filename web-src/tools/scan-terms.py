#!/usr/bin/env python3
"""Wave 5: 术语统一扫描 — 输出 现网/生产环境/排障 出现统计"""
import json, glob

for f in glob.glob('.cache/*.json') + glob.glob('js/data/*.js'):
    try:
        text = open(f, encoding='utf-8').read()
    except Exception:
        continue
    xw = text.count('现网')
    sc = text.count('生产环境')
    pz = text.count('排障')
    gz = text.count('故障排查')
    if xw or sc or pz or gz:
        print(f'{f}: 现网={xw} 生产环境={sc} 排障={pz} 故障排查={gz}')
