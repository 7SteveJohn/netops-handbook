#!/usr/bin/env python3
"""Wave 4: P2 深度内容补充 + RFC 引用 — 修改 .cache/phases.json"""
import json

P = '.cache/phases.json'
d = json.load(open(P, encoding='utf-8'))

def find(mid):
    for ph in d:
        for m in ph['modules']:
            if m['id'] == mid:
                return m
    raise KeyError(mid)

# ---------- H1. IPv6 RA/SLAAC ----------
m = find('H1')
m['y'] += ('\nSLAAC 流程：主机发 RS → 路由器回 RA(含前缀/前缀长度/默认网关=自身链路本地地址)；'
           'RA 中 M=1 表示用 DHCPv6 要地址、O=1 表示用 DHCPv6 要其他配置(如 DNS)。'
           '攻击防护：RA 可伪造劫持网关，需 RA Guard 或禁用不必要的 RA。')

# ---------- H3. 6to4 与 NAT64 适用场景 ----------
m = find('H3')
m['y'] += ('\n选型：6to4 用于 IPv6 孤岛间互联(需公网 IPv4 且协议号 41 可达)；'
           'NAT64 用于 IPv6-only 客户端访问 IPv4 服务(需配合 DNS64 合成 AAAA 记录)。')

# ---------- X2. eBPF CO-RE / BTF ----------
m = find('X2')
m['y'] += ('\nCO-RE(Compile Once, Run Everywhere)：eBPF 程序一次编译到处运行，'
           '依赖内核 BTF(BTF 提供内核结构体布局信息)，加载时用 libbpf 做字段偏移重定位，'
           '替代旧的一机一编译。BTF 是 CO-RE 的基石。')

# ---------- G13. SD-WAN 架构与 TCP 优化 ----------
m = find('G13')
m['y'] += ('\nSD-WAN 架构：Edge(CPE 接入) / Controller(集中控制面) / Orchestrator(业务编排)。'
           'TCP 优化：FEC(前向纠错，冗余包抗丢包) 与 ACK 压缩(本地代理回 ACK 提速)。')

# ---------- W1. Nornir 对比 ----------
m = find('W1')
m['y'] += ('\n自动化框架选型：Nornir(Python 原生，并发多线程/协程，适合复杂逻辑)；'
           'Netmiko(单设备驱动库，Nornir 底层传输)；Ansible(YAML 声明式，生态大但复杂逻辑受限)。'
           '大规模并行刷配置优先 Nornir。')

# ---------- N1. Gateway API 定位 ----------
m = find('N1')
m['y'] += ('\nGateway API 是 Ingress 的演进方向：跨 namespace 路由、按协议细分(GatewayClass/Gateway/HTTPRoute)、'
           '更细粒度流量控制；但 Ingress Controller 生态成熟仍广泛使用，两者会长期共存。')

# ---------- RFC 引用(加到各模块 w 末尾) ----------
rfc = {
    'A3': 'RFC 2328(OSPFv2)',
    'A4': 'RFC 4271(BGP-4)',
    'B3': 'RFC 3768(VRRP)',
    'H1': 'RFC 4861(NDP/RA) · RFC 4291(IPv6 地址架构)',
}
for mid, ref in rfc.items():
    m = find(mid)
    m['w'] = list(m['w']) + ['参考标准：' + ref]

json.dump(d, open(P, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print('Wave 4 完成: H1/H3/X2/G13/W1/N1 + RFC(A3/A4/B3/H1)')
