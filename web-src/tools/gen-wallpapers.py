#!/usr/bin/env python3
"""生成 App 内置壁纸:原图 WebP(assets/wallpapers)
来源: D:/Android/背景图/<类>/<类><n>.png (7 类 x 2 张)
输出: app/src/main/assets/wallpapers/*.webp
"""
import os, json
from PIL import Image

SRC = 'D:/Android/背景图'
OUT = 'D:/Android/app/src/main/assets/wallpapers'
FULL_W = 1200   # 原图等比缩放宽度(手机全屏壁纸足够,大幅减小体积与 decode 内存)

CATS = ['二次元', '芙宁娜', '今汐', '卡提希娅', '雷电将军', '纳西妲', '守岸人']

os.makedirs(OUT, exist_ok=True)
# 清理旧的 thumbs 目录(2026-08-12 用户要求只要名字不要缩略图)
import shutil
thumbs = os.path.join(OUT, 'thumbs')
if os.path.isdir(thumbs):
    shutil.rmtree(thumbs)
    print('removed old thumbs dir')

manifest = []
total = 0
for ci, cat in enumerate(CATS):
    for n in (1, 2):
        src = os.path.join(SRC, cat, f'{cat}{n}.png')
        if not os.path.exists(src):
            print(f'MISSING: {src}')
            continue
        img = Image.open(src).convert('RGB')
        full = img
        if img.width > FULL_W:
            full = img.resize((FULL_W, int(img.height * FULL_W / img.width)), Image.LANCZOS)
        fn = f'{cat}{n}.webp'
        full.save(os.path.join(OUT, fn), 'WEBP', quality=88, method=6)
        fs = os.path.getsize(os.path.join(OUT, fn))
        total += fs
        manifest.append({
            'id': f'wp-{ci+1}-{n}',
            'cat': cat,
            'name': f'{cat}{n}',
            'file': fn,
        })
        print(f'{cat}{n}: {fs//1024}KB ({img.width}x{img.height})')

print(f'\n总大小: {total//1024//1024}MB')
print('\n=== JS 数据段 ===')
print('var WALLPAPERS = ' + json.dumps(manifest, ensure_ascii=False) + ';')
