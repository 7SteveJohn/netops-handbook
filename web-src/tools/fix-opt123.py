#!/usr/bin/env python3
"""优化项 1/2/3: OSPFv3 Router-ID 对比 + RFC 补充 + PBR 补全 — .cache/phases.json"""
import json

P = '.cache/phases.json'
d = json.load(open(P, encoding='utf-8'))

def find(mid):
    for ph in d:
        for m in ph['modules']:
            if m['id'] == mid:
                return m
    raise KeyError(mid)

# 1. H2 OSPFv3 Router-ID 对比
m = find('H2')
m['j'] = ['router-id: 不手工配起不来',
          'OSPFv2 可自动选举 Router-ID；OSPFv3 必须手工指定 IPv4 格式 Router-ID，否则进程无法启动']

# 2. RFC 补充
for mid, ref in [('F1', '参考标准：RFC 7432 (BGP MPLS-based Ethernet VPN)'),
                 ('G22', '参考标准：RFC 8986 (SRv6 Network Programming)'),
                 ('X2', '参考标准：内核文档 Documentation/bpf/')]:
    m = find(mid)
    m['j'] = list(m['j']) + [ref]

# 3. A2 PBR 补全 classifier 匹配条件
m = find('A2')
m['c'] = ('[Huawei] interface Vlanif 10\n'
          '[Huawei-Vlanif10] ip address 192.168.10.1 24\n'
          '[Huawei] traffic classifier C1\n'
          '[Huawei-classifier-C1] if-match acl 3000\n'
          '[Huawei] traffic behavior B1\n'
          '[Huawei-behavior-B1] redirect ip-nexthop 10.0.0.2')
m['j'] = ['redirect: 强制重定向下一跳',
          'classifier 用 if-match acl 3000 匹配流量 → behavior 执行 redirect，缺匹配条件则策略不生效']

json.dump(d, open(P, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print('优化 1/2/3 完成')
