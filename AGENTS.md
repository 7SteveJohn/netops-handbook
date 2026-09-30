# AGENTS.md — 给 AI 编码助手的项目须知

> 本文件是各类 AI 编码助手（ZCode / Claude Code / Codex / Cursor 等）进入本仓库时
> 的**必读入口**。动手改代码之前，先读完这里列出的约定和硬约束。

## 私有边界（先读这个）

液态玻璃与 Apple 设计的**调研文档、参数手册、问题复盘、验证手册、探针页与截图证据**
（`液态玻璃*.md`、`Apple页面设计要点*.md`、`本地视觉闭环-验证手册.md`、
`web-src/tools/lens-probe.html`、`screenshots/`）为用户私有，**刻意不入库**
（.gitignore 已挡，勿提交、勿在公开渠道引用其内容）。需要改玻璃/视觉相关代码时，
向用户索要上述本地文档；现行透镜参数真源在 `web-src/glass-recipe.json`（随源码开源）。

## 工作约定（用户明确反馈过的）

- **打包前攒齐所有修复**：用户不接受"装一次修一个"的来回折返。APK 构建 = 当前
  最大修复集合 + 装机后步骤清单；构建完成后把 APK 复制一份桌面副本交付。
- **不拿"没连手机"当挡箭牌**：设备离线时用本地视觉闭环（web-src/tools/serve-lum.js
  默认 8931 端口 + 390×844 视口截图）定位并修复离线可修的缺陷、附渲染证据；
  只有真正无法离线验证的点（如透镜真机观感）才 deferred-to-device。

## 项目一句话

NetOps 2.0：Android 离线网络学习手册 App。WebView 壳 + `web-src/` 单文件构建
（build.js 内联进 app/src/main/assets/index.html），UI 走 Apple Liquid Glass 设计语言。
