/* NetOps CLI 内置常用命令（M1 原生化拆出：gen-native-data 与 32-boot 共用同一份，
   避免"命令库条数只有跑起来才知道"——与 22-cli-rules 同理由）。
   24 条带贴近真实输出的回显；plat = all/hw/lx/k8s/win。 */
window.NETOPS_CLI_COMMON = [
  { cmd: 'ping', plat: 'all', fn: '网络连通性测试', out: 'PING 8.8.8.8 (8.8.8.8): 56 data bytes\n64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=12.3 ms\n--- 8.8.8.8 ping statistics ---\n1 packets transmitted, 1 received, 0% packet loss' },
  { cmd: 'tracert', plat: 'all', fn: '路由追踪', out: 'traceroute to 8.8.8.8 (8.8.8.8), 30 hops max\n 1  192.168.1.1   1.2 ms\n 2  10.0.0.1     3.4 ms\n 3  8.8.8.8     12.3 ms' },
  { cmd: 'nslookup', plat: 'all', fn: 'DNS 查询', out: 'Server:  192.168.1.1\nAddress: 192.168.1.1#53\n\nName:    www.example.com\nAddress: 93.184.216.34' },
  { cmd: 'curl', plat: 'all', fn: 'HTTP 请求', out: 'HTTP/1.1 200 OK\nContent-Type: text/html; charset=utf-8\n\n<!DOCTYPE html><html>...' },
  { cmd: 'tcpdump', plat: 'all', fn: '抓包', out: 'tcpdump: verbose output suppressed\n22:15:33.123456 IP 192.168.1.100.443 > 45.33.2.1.52344: Flags [P.], seq 1:517, ack 1, win 501' },
  { cmd: 'mtr', plat: 'all', fn: '网络诊断(ping+traceroute 结合)', out: 'HOST            Loss%  Snt  Last  Avg  Best  Wrst  StDev\n1. 192.168.1.1   0.0%   10   1.2   1.4  1.1   2.3   0.3\n2. 10.0.0.1      0.0%   10   3.4   3.6  3.1   4.2   0.4' },
  { cmd: 'ethtool', plat: 'all', fn: '网卡信息', out: 'Settings for eth0:\n    Speed: 1000Mb/s\n    Duplex: Full\n    Link detected: yes' },
  { cmd: 'ipconfig', plat: 'all', fn: 'Windows IP 配置', out: 'Windows IP 配置\n以太网适配器 以太网:\n   IPv4 地址: 192.168.1.100\n   子网掩码: 255.255.255.0\n   默认网关: 192.168.1.1' },
  { cmd: 'ifconfig', plat: 'all', fn: 'Linux 接口配置', out: 'eth0: flags=4163<UP,BROADCAST,RUNNING,MULTICAST>  mtu 1500\n        inet 192.168.1.100  netmask 255.255.255.0  broadcast 192.168.1.255\n        ether 00:0c:29:aa:bb:cc' },
  { cmd: 'netstat -an', plat: 'all', fn: '端口连接状态', out: 'Proto Recv-Q Send-Q Local Address    Foreign Address  State\ntcp   0      0      0.0.0.0:22       0.0.0.0:*         LISTEN\ntcp   0      0      192.168.1.100:443  45.33.2.1:52344  ESTABLISHED' },
  { cmd: 'ss -tulnp', plat: 'all', fn: '监听端口', out: 'Netid  State   Recv-Q Send-Q  Local Address:Port\ntcp    LISTEN  0      128      0.0.0.0:22\ntcp    LISTEN  0      128      0.0.0.0:80' },
  { cmd: 'route print', plat: 'all', fn: 'Windows 路由表', out: 'IPv4 路由表\n活动路由:\n网络目标 0.0.0.0  网关 192.168.1.1  接口 192.168.1.100  跃点数 25' },
  { cmd: 'ip addr', plat: 'lx', fn: 'Linux IP 地址', out: '1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536\n    inet 127.0.0.1/8 scope host lo\n2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500\n    inet 192.168.1.100/24 brd 192.168.1.255 scope global eth0' },
  { cmd: 'ip route', plat: 'lx', fn: 'Linux 路由表', out: 'default via 192.168.1.1 dev eth0\n192.168.1.0/24 dev eth0 proto kernel scope link src 192.168.1.100' },
  { cmd: 'display version', plat: 'hw', fn: '设备版本', out: 'Huawei Versatile Routing Platform Software\nVRP (R) software, Version 8.180 (CE6850 V200R005C10SPC607)\nUptime is 30 days, 4 hours, 12 minutes' },
  { cmd: 'display interface brief', plat: 'hw', fn: '接口状态', out: 'Interface         PHY   Protocol  InUti  OutUti  inErrors  outErrors\nGE0/0/0           up    up        0.01%  0.01%          0          0\nGE0/0/1           down  down      0%     0%            0          0' },
  { cmd: 'display current-configuration', plat: 'hw', fn: '查看当前配置', out: 'sysname CE6850-1\ninterface Vlanif10\n ip address 192.168.10.1 255.255.255.0\ninterface GigabitEthernet0/0/1\n port link-type trunk\n...' },
  { cmd: 'kubectl get pods', plat: 'k8s', fn: 'K8s 查看 Pod', out: 'NAME                     READY  STATUS   RESTARTS  AGE\nnginx-7c9bc7c7b7-abc12   1/1    Running  0         3d2h' },
  { cmd: 'kubectl get svc', plat: 'k8s', fn: 'K8s 查看 Service', out: 'NAME     TYPE        CLUSTER-IP  EXTERNAL-IP  PORT(S)  AGE\nnginx    ClusterIP   10.96.0.10  <none>       80/TCP   3d' },
  { cmd: 'kubectl get nodes', plat: 'k8s', fn: 'K8s 查看节点', out: 'NAME    STATUS  ROLES          AGE  VERSION\nnode-1  Ready   control-plane  30d  v1.28.2\nnode-2  Ready   <none>         30d  v1.28.2' },
  { cmd: 'arp -a', plat: 'win', fn: 'ARP 表', out: '接口: 192.168.1.100 --- 0x4\nInternet 地址         物理地址           类型\n192.168.1.1           aa-bb-cc-dd-ee-ff     动态' },
  { cmd: 'systeminfo', plat: 'win', fn: '系统信息', out: 'Host Name:                 NETOPS-PC\nOS Name:                   Microsoft Windows 11 专业版\nTotal Physical Memory:     16,384 MB' },
  { cmd: 'w32tm /query /status', plat: 'win', fn: 'NTP 状态', out: ' leap 指示符: 0（无警告）\n层级: 3（辅助引用）\n源: time.windows.com' }
];
