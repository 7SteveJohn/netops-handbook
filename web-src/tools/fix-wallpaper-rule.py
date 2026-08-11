#!/usr/bin/env python3
# 解锁壁纸模式内容层半透明(不依赖 .glass-on)
import re, sys
P = 'css/06-anim.css'
s = open(P, encoding='utf-8').read()
before = s
s = s.replace('body.glass-on.has-wallpaper', 'body.has-wallpaper')

# 在浅色半透明块最后追加 .dict__hint / .dict__kv / .card__head
EXTRA = ',\nbody.has-wallpaper .dict__hint,\nbody.has-wallpaper .dict__kv,\nbody.has-wallpaper .card__head'

s = s.replace(
    'body.has-wallpaper .btn--soft,\nbody.has-wallpaper .fab {',
    'body.has-wallpaper .btn--soft,\nbody.has-wallpaper .fab' + EXTRA + ' {', 1
)
s = s.replace(
    'html.dark body.has-wallpaper .btn--soft,\nhtml.dark body.has-wallpaper .fab {',
    'html.dark body.has-wallpaper .btn--soft,\nhtml.dark body.has-wallpaper .fab' + EXTRA + ' {', 1
)

# 在块末尾追加 .dict__hint 配色(用 accent 半透明替代 accent-soft 实色)
hint = '''\n\n/* .dict__hint 壁纸模式:accent 半透明文字替代实色 accent-soft 底 */
body.has-wallpaper .dict__hint {
  background: rgba(99,102,241,.12) !important;
  border-color: rgba(99,102,241,.28) !important;
}
html.dark body.has-wallpaper .dict__hint {
  background: rgba(99,102,241,.18) !important;
  border-color: rgba(99,102,241,.35) !important;
}
'''
s = s.rstrip() + hint

open(P, 'w', encoding='utf-8').write(s)
print('glass-on refs removed:', before.count('body.glass-on.has-wallpaper'))
print('total lines:', len(s.splitlines()))
print('dict__hint refs:', s.count('dict__hint'))