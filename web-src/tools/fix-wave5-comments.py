#!/usr/bin/env python3
"""Wave 5: 命令注释清理 — 移出代码块,确保命令可直接复制到设备"""
import json

P = '.cache/phases.json'
d = json.load(open(P, encoding='utf-8'))

def find(mid):
    for ph in d:
        for m in ph['modules']:
            if m['id'] == mid:
                return m
    raise KeyError(mid)

# A2: 移除注释行 "// PBR示例:",说明移入 j
m = find('A2')
m['c'] = ('[Huawei] interface Vlanif 10\n'
          '[Huawei-Vlanif10] ip address 192.168.10.1 24\n'
          '[Huawei] traffic classifier C1\n'
          '[Huawei] traffic behavior B1\n'
          '[Huawei-behavior-B1] redirect ip-nexthop 10.0.0.2')
m['j'] = ['redirect: 强制重定向下一跳', 'PBR 示例：classifier(匹配) → behavior(动作 redirect) 组合成策略路由']

# P3: 移除注释行 "// 加入 log 插件",说明移入 j
m = find('P3')
m['c'] = 'kubectl edit configmap coredns -n kube-system'
m['j'] = ['NXDOMAIN: 解析不到结果', '在 Corefile 中加 log 插件可观察 DNS 查询日志']

# G3: 截断行尾注释,保留命令本体;急救说明移入 w
m = find('G3')
m['c'] = ('[Huawei] interface GigabitEthernet 0/0/1\n'
          '[Huawei-GigabitEthernet0/0/1] shutdown')
m['w'] = list(m['w']) + ['shutdown 是急救断口(物理 down 等价拔线,立刻破环)；stp disable 只关 STP 计算不断链路,环路场景反而放大故障']

json.dump(d, open(P, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print('命令注释清理完成: A2/P3/G3')
