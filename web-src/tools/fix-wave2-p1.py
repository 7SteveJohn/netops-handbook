#!/usr/bin/env python3
"""Wave 2: P1 概念修正 + 高频知识补充 — 修改 .cache/phases.json"""
import json

P = '.cache/phases.json'
d = json.load(open(P, encoding='utf-8'))

def find(mid):
    for ph in d:
        for m in ph['modules']:
            if m['id'] == mid:
                return m
    raise KeyError(mid)

# ========== P1 概念修正 ==========

# A1. VLAN Trunk: 补 VLAN 1 安全隐患
m = find('A1')
m['w'] = ['Trunk两端allow-pass不一致导致不通',
          '默认 VLAN 1 在所有 Trunk 自动放行，是 VLAN 跳跃攻击入口；' +
          '无关端口建议 `undo port trunk allow-pass vlan 1` 移除']

# J2. Calico IPIP: 修正描述
m = find('J2')
m['y'] = ('纯BGP模式(No Encap)：Node当路由器，原生IP转发无损耗性能极高。\n'
          'IPIP模式：公有云 Underlay 禁止 BGP 路由交换时（如 AWS/GCP 不允许跑 BGP），'
          '启用 IPIP 作为 Overlay 隧道(内耗MTU)。')

# X1. 可观测性: X-Request-ID → W3C Trace Context
m = find('X1')
m['c'] = ("curl -H 'traceparent: 00-<trace-id>-<span-id>-01' http://api.com/checkout")
m['j'] = ['traceparent: W3C Trace Context 标准头，00=版本, trace-id 全局唯一, span-id 本段',
          '贯穿全链路的 TraceID 是关联网络与应用的纽带']

# B1. MSTP: 补与非 MSTP 混用退化
m = find('B1')
m['w'] = ['边缘端口收到BPDU会Error-Down保护全网',
          'MSTP 域与非 MSTP 交换机（RSTP/STP）对接时，全部实例退化为单棵 RSTP 树，负载分担失效']

# H2. OSPFv3: 补 v2/v3 独立进程
m = find('H2')
m['y'] = ('基于链路而不是子网运行。使用IPv6链路本地地址(fe80)建邻居，必须手工指定IPv4格式的Router-ID。\n'
          'OSPFv2(v4)与OSPFv3(v6)是**两个独立进程**，需分别配置，路由表各自维护，不可混用。')

# ========== P1 高频知识补充 ==========

# A3. OSPF 网络类型
m = find('A3')
m['y'] += ('\n网络类型：Broadcast(以太网，需选 DR/BDR)；P2P(串口/子接口，不选 DR，收敛快)；'
           'NBMA(Frame-Relay 等，需手工指邻居)；P2MP(模拟 NBMA 为多点，不选 DR)。')
m['w'] = ['MTU不一致卡ExStart', '网段宣告错反掩码',
          'NBMA 必须手工 neighbor 指邻居，P2P 无 DR 选举']

# A4. BGP 路由反射器 RR + 联盟
m = find('A4')
m['y'] += ('\nIBGP 全网状防环但不可扩展：路由反射器(RR) 允许客户机反射路由，'
           '规模大时配 RR 或联盟(Confederation，把大 AS 拆成子 AS 配成联盟)缓解。')
m['c'] += ('\n[Huawei-bgp] reflector cluster-id 1.1.1.1\n'
           '[Huawei-bgp] peer 10.0.0.2 reflect-client')
m['j'] += ['reflect-client: 将对端设为 RR 客户，RR 反射其路由']

# B3. VRRP + Track 联动
m = find('B3')
m['c'] += ('\n[Huawei-Vlanif10] vrrp vrid 1 track interface GigabitEthernet0/0/2 reduced 40')
m['j'] += ['track interface: 监控上行链路，断开自动降优先级触发切换(生产主备切换必备)']
m['w'] += ['只配 priority 不配 track，上行断连时备机不会接管']

# J1. K8s Service 类型对比 + hostNetwork 副作用
m = find('J1')
m['y'] += ('\nService 类型：ClusterIP(集群内虚拟IP，默认)；NodePort(节点端口暴露，'
           '每 Node 监听高端口)；LoadBalancer(云 LB 分发到 NodePort)；'
           'ExternalName(CNAME 到外部域名，无 ClusterIP)。')
m['j'] += ['hostNetwork:true 副作用：绕过 CNI(直接复用宿主机网络栈)、NetworkPolicy 失效、端口易冲突']

json.dump(d, open(P, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print('Wave 2 修正完成: A1/J2/X1/B1/H2/A3/A4/B3/J1')
