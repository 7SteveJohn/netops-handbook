#!/usr/bin/env python3
"""Wave 1: 修正 P0 硬性错误(C3/F1/B2/I1/Z5) — 修改源文件 .cache/phases.json"""
import json, sys, io

P = '.cache/phases.json'
d = json.load(open(P, encoding='utf-8'))

def find(mid):
    for ph in d:
        for m in ph['modules']:
            if m['id'] == mid:
                return m
    raise KeyError(mid)

# ---------- C3. MPLS L3VPN: 补 route-target(vpn-target) ----------
m = find('C3')
m['c'] = ('[Huawei] ip vpn-instance VPN_A\n'
          '[Huawei-vpn-instance-VPN_A] route-distinguisher 100:1\n'
          '[Huawei-vpn-instance-VPN_A] vpn-target 100:1 export-extcommunity\n'
          '[Huawei-vpn-instance-VPN_A] vpn-target 100:1 import-extcommunity\n'
          '[Huawei-GE0/0/1] ip binding vpn-instance VPN_A')
m['j'] = ['route-distinguisher: 区分重叠路由',
          'vpn-target: VPN路由导入/导出标识，两端PE需匹配才能交换路由(export/import 成对)']
m['w'] = ['绑定VPN实例后接口原有IP会被清空',
          '忘配 vpn-target 会导致对端学不到路由，是 L3VPN 最常见的故障点']

# ---------- F1. VXLAN/BGP EVPN: 补 l2vpn-family evpn + peer enable 上下文 ----------
m = find('F1')
m['c'] = ('[Huawei] evpn vni 100\n'
          '[Huawei] bgp 100\n'
          '[Huawei-bgp] l2vpn-family evpn\n'
          '[Huawei-bgp-af-evpn] peer 2.2.2.2 enable\n'
          '[Huawei-bgp-af-evpn] peer 2.2.2.2 advertise encap-type vxlan')
m['j'] = ['vni: 全局唯一的24位虚拟网络标识',
          'advertise encap-type vxlan 必须在 l2vpn-family evpn 视图下配置，且先 peer enable 再下发']

# ---------- B2. Eth-Trunk LACP: 更正 H3C/华为命令归属 ----------
m = find('B2')
m['j'] = ['mode lacp: 华为 VRP 用 mode lacp + lacp mode active/passive 指定主动/被动',
          'H3C 用 mode lacp-static / mode lacp-dynamic，两厂商命令不同，勿混用']

# ---------- I1. SNMP: SNMPv3 补加密(privacy-mode) ----------
m = find('I1')
m['c'] = ('[Huawei] snmp-agent sys-info version v3\n'
          '[Huawei] snmp-agent usm-user v3 admin authentication-mode sha cipher <pwd>\n'
          '[Huawei] snmp-agent usm-user v3 admin privacy-mode aes128 cipher <pwd>')
m['y'] = ('网管拉取设备数据的协议。v1/v2c明文传输(只配团体名)，v3强制加密认证最安全。\n'
          '认证(authentication)与加密(privacy)分离：authentication-mode 只验身份，privacy-mode 才加密报文。')
m['j'] = ['cipher: 本地存储加密',
          '认证与加密是两个独立配置项，只配认证不配 privacy-mode 时报文仍明文']

# ---------- Z5. SSH 管理: 补 SSHv1 禁用与弱算法加固 ----------
m = find('Z5')
m['c'] = ('[Huawei] stelnet server enable\n'
          '[Huawei] ssh server compatible-ssh1x disable\n'
          '[Huawei] ssh server cipher aes256-gcm\n'
          '[Huawei] user-interface vty 0 4\n'
          '[Huawei-ui-vty0-4] authentication-mode aaa\n'
          '[Huawei-ui-vty0-4] protocol inbound ssh')
m['w'] = ['Console波特率不对会乱码',
          'Telnet严禁公网使用',
          'SSHv1 与弱算法(des/3des)存在已知漏洞，生产环境必须 disable + 仅留 aes256-gcm 等强套件']

json.dump(d, open(P, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print('Wave 1 修正完成: C3/F1/B2/I1/Z5')
