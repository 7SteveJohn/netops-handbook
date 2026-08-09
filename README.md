# NetOps 2.0 — 全栈网络运维技能导航

> 从零基础数通到云原生架构，把整个网络运维知识体系装进口袋。**完全离线，无需任何网络连接。**

[![Platform](https://img.shields.io/badge/platform-Android%2024%2B-green.svg)](https://github.com/7SteveJohn/netops-handbook/releases/tag/v2.0.0)
[![Offline](https://img.shields.io/badge/network-100%25%20offline-orange.svg)](#)
[![Release](https://img.shields.io/badge/release-v2.0.0-blue.svg)](https://github.com/7SteveJohn/netops-handbook/releases/tag/v2.0.0)

---

## 它是什么

NetOps 2.0 是一个面向网络运维工程师的 **Android 离线知识导航 App**。

打开即用，不联网、不注册、不看广告。所有内容——58 个知识模块、25 个排障案例、58 条多厂商命令对照、30 道面试真题——全部打包在安装包里，断网也能学。

适合这些场景：

- 🏗 机房割接现场，没网也能查命令
- 📝 面试突击复习，通勤路上刷题
- 🎯 按路线图系统学习，从零基础到架构师
- 🔧 现场排障速查，秒找对应命令和思路

## 一图看懂

| | |
|---|---|
| **首页 — 学习路径与进度总览** | <img src="screenshots/home.jpg" width="280" alt="首页截图" /> |
| **学习路线图 — 0-12 月成长节奏** | <img src="screenshots/roadmap.jpg" width="280" alt="学习路线图截图" /> |
| **我的 — 进度 / 收藏 / 资源入口** | <img src="screenshots/profile.jpg" width="280" alt="我的页面截图" /> |

## 五大功能

### 📚 学习 — 58 个知识模块

按阶段组织的学习路径，从 OSI 七层模型一路走到云原生 SRE：

1. **零基础入门与基础设施**（8 模块）— OSI/TCP 分层、IPv4/掩码计算、网关原理、华为 VRP 视图操作
2. **园区网核心与高可用**（9 模块）— VLAN / Trunk / SVI、OSPF 多区域、BGP、MSTP / VRRP / iStack 堆叠
3. **广域网与安全** — NAT / PPPoE / MPLS、防火墙策略、802.1X、无线 AC-AP
4. **云原生网络转型** — VXLAN / EVPN、K8s CNI、Calico / Cilium / eBPF、可观测性三支柱
5. **架构与 SRE** — SLI / SLO / 错误预算、混沌工程、GitOps 闭环、跨数据中心高可用

每个模块点进去就是完整的内容页，支持收藏和进度标记。

### 🔧 排障 — 25 个真实场景

典型故障的「现象 → 分析 → 命令」三段式卡片：

- 链路不通怎么排查？从物理层到路由逐层定位
- 隧道 MSS 导致大包丢包？`tcp adjust-mss` 一行搞定
- STP 风暴 / MAC 地址漂移 / OSPF 邻居起不来……每个都有对应的急救命令

### 📖 字典 — 58 条多厂商命令对照

同一功能，华为 / Cisco / 中兴 / Linux 四家命令并排显示：

```
示例：Trunk 放行 VLAN
  华为: port trunk allow-pass vlan 10
  Cisco: switchport trunk allowed vlan 10
  中兴: port trunk permit vlan 10
  Linux: -
```

还收录了高频巡检命令（BGP 邻居汇总、路由表查看、接口简要状态等）。

### 📝 面试 — 30 道真题

从概念题到架构设计题，覆盖各层次：

- 「Hybrid 接口和 Access / Trunk 的区别？」
- 「OSPF 的 LSA 类型有哪些？Type 7 是什么？」
- 「iptables 规则多了性能会怎样？是 O(n) 还是指数级？」
- 「公有云里 Calico 为什么不能直接跑 BGP？」

每道题带解析，帮你在面试前快速过一遍关键知识点。

### 👤 我的 — 进度追踪与资源

- **总进度圆环**：已掌握 / 总项数百分比
- **分项统计**：知识模块、排障案例、面试真题各自完成度
- **收藏夹**：把常看的模块或命令一键收藏
- **资源入口**：速查表（8 张高频对照表）、术语词典（22 条）、学习路线图

## 使用方式

1. **下载安装** → [Releases 页面](https://github.com/7SteveJohn/netops-handbook/releases/tag/v2.0.0) 下载 `app-release.apk`，允许「未知来源」安装即可
2. **打开即用** → 底部五个 Tab 切换：**学习 / 排障 / 字典 / 面试 / 我的**
3. **搜索** → 右上角 🔍 图标全局搜索任意关键词
4. **深色模式** → 右上角 ☀️ 图标切换明暗主题
5. **返回导航** → Android 物理返回键逐层回退，到根页可退出

> **注意**：本应用声明了 `android.permission.VIBRATE`（触控反馈震动），无任何网络相关权限。首次安装时系统可能提示「未知的开发者」，这是正常的安全提示，选择「仍然安装」即可。

## 内容一览

| 类别 | 数量 | 示例 |
|------|------|------|
| 知识模块 | 58 | OSI 分层、VLAN/Trunk、OSPF 多区域、BGP 选路、VXLAN/EVPN、eBPF、GitOps… |
| 排障案例 | 25 | 链路不通、MSS 过小丢包、STP 风暴、MAC 漂移、OSPF 邻居异常… |
| 命令对照 | 58 | Trunk 放行、端口描述、VLAN 创建、OSPF 邻居、BGP 汇总、接口状态… |
| 面试真题 | 30 | Hybrid vs Trunk、LSA 类型、iptables 复杂度、Calico 云端限制… |
| 速查表 | 8 张 | 高频命令对照表（按场景分类） |
| 术语词典 | 22 条 | 核心网络运维术语的定义与辨析 |

## 技术细节

- **纯离线**：不声明 `INTERNET` 权限，零外部请求，所有内容与美术资源（SVG/CSS）内联打包
- **移动优化**：安全区域适配（刘海屏/挖孔屏）、边到边沉浸界面、触控动效
- **单文件 SPA**：全部前端代码打包为一个 `index.html`，通过 Android WebView 加载
- **开源协议**：MIT License

## 下载

📥 [**v2.0.0 Release（含 APK）**](https://github.com/7SteveJohn/netops-handbook/releases/tag/v2.0.0)

## 许可

本项目以 MIT License 开源。内容仅供学习交流。
