# NetOps 2.0 — 全栈网络运维技能导航

> 完全离线、单文件自包含的 Android 应用，把从零基础到高级架构的网络运维知识体系装进口袋。

[![Platform](https://img.shields.io/badge/platform-Android%2024%2B-green.svg)](https://www.android.com/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](#许可)
[![Offline](https://img.shields.io/badge/network-100%25%20offline-orange.svg)](#特性)

---

## 简介

NetOps 2.0 是一个面向网络运维工程师的离线知识导航平台。它以 Android App 为载体，内部承载一个零外部依赖的单页应用（SPA），把传统数通、云原生、排障字典、面试题库、命令词典等内容整合在一个可离线运行、移动端深度优化的界面里。

**主打特性：纯离线。** 应用不声明任何 `INTERNET` 权限，所有内容、美术资源（内联 SVG / CSS）均打包进安装包，断网也能用——适合机房、割接现场、试题备考等无网或弱网场景。

---

## 特性

- 📚 **完整知识体系**：从零基础到高级架构，覆盖传统数通、云原生、排障、面试、命令字典。
- 📴 **100% 离线**：无网络权限、无外部请求，安装即用，断网无忧。
- 📱 **移动端深度优化**：UI 自适应、`safe-area` 安全区域适配、触控动效、边到边沉浸式界面。
- ⌨️ **原生桥接**：物理返回键正确回退网页导航栈、安全区域（dp）精确下发、导出 Markdown。
- 🎨 **零外部依赖**：所有图标 / 插画均为内联 SVG 或 CSS 绘制，无图片文件、无 CDN。
- 🔍 **命令字典**：华为 / Cisco / 中兴 / Linux 多厂商命令对照，支持高频巡检命令速查。

---

## 内容体系

| 模块 | 说明 |
|------|------|
| **传统数通** | VLAN / Trunk / STP / OSPF / BGP / Eth-Trunk 等基础与进阶 |
| **云原生网络** | 容器网络、Calico / Cilium、eBPF、Service Mesh、GitOps 渲染下发 |
| **排障字典** | 典型故障场景（链路、隧道 MSS、生成树、路由）与急救命令 |
| **面试题库** | 从概念到架构的多层次面试题与解析 |
| **命令词典** | 多厂商命令对照表，附高频巡检命令（邻居、路由表、接口状态） |

---

## 技术架构

```
┌─────────────────────────────────────────────┐
│  Android Shell (Kotlin/Java)                  │
│  - 边到边沉浸界面 / 安全区 dp 下发             │
│  - 物理返回键 ↔ 网页导航栈桥接                │
│  - @JavascriptInterface 原生桥 (NetBridge)     │
│  - 纯离线（无 INTERNET 权限）                 │
└───────────────────┬─────────────────────────┘
                    │  WebView 加载
┌───────────────────┴─────────────────────────┐
│  Web SPA (web-src/)                           │
│  - 单文件自包含 index.html（CSS/JS/SVG 内联）  │
│  - 自建导航栈（替代 history.pushState）        │
│  - 数据源：web-src/.cache/*.json              │
└─────────────────────────────────────────────┘
```

### 构建链路

```
web-src/.cache/phases.json  ┐
web-src/.cache/dic.json     ┼─► tools/gen-data.js ─► js/data/10-core.js
web-src/js/data/*.js (手写) ┘
        │
        ▼
   tools/build.js  ─►  app/src/main/assets/index.html  (离线单页)
        │
        ▼
   Android Studio 打包  ─►  app-release.apk (发布签名)
```

---

## 目录结构

```
D:\Android\
├── app/                      # Android 壳工程
│   ├── src/main/
│   │   ├── java/.../MainActivity.java   # 壳逻辑 + 原生桥
│   │   ├── res/                         # 主题 / 图标 / 布局
│   │   └── assets/index.html            # 构建产物（离线单页）
│   ├── build.gradle.kts
│   └── release-key.jks          # 发布签名密钥（不入库）
├── web-src/                 # 知识库网页源码
│   ├── index.html
│   ├── css/  js/  html/     # 内联样式 / 逻辑 / SVG sprite
│   ├── .cache/              # 数据源（phases.json / dic.json）
│   └── tools/               # 构建脚本（gen-data / build / smoke / verify-apk / gen-icon）
├── gradle/  gradlew*        # Gradle wrapper
├── build.gradle.kts  settings.gradle.kts
├── keystore.properties.example   # 密钥模板（无真实密码）
└── README.md
```

> **注意**：`app/release-key.jks`、`keystore.properties`、构建缓存（`app/build`、`build`、`.gradle`）均已被 `.gitignore` 排除，不会进入仓库。

---

## 构建与打包

### 前置

- Android Studio（自带 JDK / keytool / build-tools）
- Gradle 8.9（wrapper 已内置）
- Node.js（仅用于重新生成网页内容，可选）

### 重新生成网页内容（可选）

若修改了 `web-src/.cache/*.json` 或 `web-src/js/` 下的源文件：

```bash
cd web-src
node tools/gen-data.js   # 由数据源生成 js/data/10-core.js
node tools/build.js      # 内联打包为 app/src/main/assets/index.html
```

### 打包 APK

1. 用 Android Studio 打开本项目。
2. **Build → Generate Signed Bundle / APK → APK**
3. 选择 `app/release-key.jks`，填入密钥口令（alias `netops`）。
4. 选择 **release**，勾选 **V1 + V2** 签名 → Finish。
5. 产物：`app/release/app-release.apk`。

> 首次打包前需先生成签名密钥：双击项目根 `gen-release-key.bat`，按提示输入口令即可（自动写 `keystore.properties`）。

---

## 下载

最新发布版 APK 见 **[Releases](https://github.com/7SteveJohn/netops-handbook/releases/tag/v2.0.0)**。

下载 `app-release.apk`，在 Android 上允许「未知来源」安装即可使用。

---

## 许可

本项目以 [MIT License](#) 开源。内容版权归原作者所有，仅供学习交流。
