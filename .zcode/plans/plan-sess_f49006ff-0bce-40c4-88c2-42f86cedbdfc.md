# 照搬 Kyant0/KernelSU 玻璃栈 —— LensBarView 材质整体换血

## Context

用户拍板：放弃自研玻璃参数反复调试，直接照搬 KernelSU v3.3.1 实证可用的开源实现。溯源已核：KernelSU 玻璃 = **Kyant0/AndroidLiquidGlass（Apache 2.0）** 经 **miuix-blur（SPDX Apache-2.0，`TextureEffect.kt:1-2` 文件头实证）**；其装机包 dex 里的 shader 与上游源码逐字一致（本会话已双向核验）。当前 `LensBarView.java`（2273 行）结构地图已备（管线/弹簧/门控均定位到行）。

## 第 0 步：许可硬门禁

- curl GitHub API 核验 `Kyant0/AndroidLiquidGlass` spdx_id == `Apache-2.0`；miuix（compose-miuix-ui/miuix）同查。**非 Apache 即停，回报用户。**
- 移植源文件从 compose-miuix-ui（miuix-blur 模块 + example 的 liquid 包）取，不从 KernelSU 仓库取（其仓库整体 GPL-3.0；文件头虽声明 Apache 来源，取上游更干净）。新文件头保留 Apache-2.0 版权与出处声明，仓库根加 `THIRD_PARTY_NOTICES.md`。

## 移植清单（照搬 = verbatim）

新增 `lens/glass/LiquidGlassShaders.java`（仅 verbatim AGSL 常量 + 许可头）：

| 件 | 照搬值（源） | 替换我们的 |
|---|---|---|
| 药丸折射 shader | `ROUNDED_RECT_REFRACTION(_WITH_DISPERSION)_SHADER`（Lens.kt；circleMap 剖面 + gradSdRoundedRect + 7 彩光谱色散） | 现 `AGSL` 常量（L151-258）里 FlClash 系 4.3/5.5/6.5/14/11.2/3.6 弯折段 |
| 药丸光学参数 | refractionHeight **10dp×lift**、refractionAmount **14dp×lift**（喂负值，Kotlin 侧同）、depthEffect=1、chromaticAberration **0.5**（FloatingBottomBar.kt:447-452） | ×uLift 的 FlClash 数值 |
| 药丸采样源 | **清晰内容**（= 我们已有的 `contentSharp`）+ tab 镜像层 合成 single `content` 输入——KernelSU combinedBackdrop 同构。**这是"照抄也不像"的关键修正：现状药丸折的是霜面** | uBar(barPane 霜面) 采样 |
| vibrancy | miuix `colorControls(brightness 0, contrast 1, saturation 1.5)`，线性空间 pow(2.2)/0.4545（BackdropEffects.kt，实施时取源） | buildBarPane 的 ColorMatrix sat 2.1/1.925 |
| 栏霜链 | downscale(D) → vibrancy → **blur 4dp**（D 与噪声系数取 miuix `BlurDefaults.kt`）→ 噪声抖动（三通道 dither，TextureEffect.kt:21 参数） | fw/40 两级降采样 + 28/72 合成 |
| 栏 tint | surfaceContainer 色 **α0.4**（浅暗同 α，FloatingBottomBar.kt:220） | 0x93F4F4F4 α0.576 / 暗 argb(122,…) |
| 栏缘弯折 | 栏体 lens **24dp/24dp**（bar 自己也有折射边，FloatingBottomBar.kt:364-367） | 现状栏体无折射 |
| 高光 | **BloomStroke** 双光源（主 0.5,-0.3 强 1；副 0.8 强 0.4；innerBlur 2dp；Bloom 0.12α 白）+ **重力传感器 3° 量化旋转**（DeviceTilt）；栏高光 α0.75、药丸 α=lift（FloatingBottomBar.kt:96-114,329-330） | barStrokeP/barHairlineP + 9 段 rim + 0.67dp 暗线（全撤，暗线语义改由 innerShadow+折射边承担——与"禁描绘线"定律一致） |
| 药丸面 | 静置 黑(浅)/白(暗) **0.1** → 按压淡至 0.03 黑（FloatingBottomBar.kt:462-468） | platterP #DEE5FC α217 |
| 药丸内阴影 | innerShadow **8dp×lift**，黑 0.15（InnerShadow.kt：outline 模糊+Clear 反挖，纯 Canvas 逻辑直译进 Paint 管线） | 无 |
| 阴影 | 10dp，浅 0.1 / 暗 0.2（FloatingBottomBar.kt:347-354） | pillShadowP 0.055+3dp |
| 抓取外扩 | backdrop padding **40dp**——PixelCopy 矩形外扩 40dp（clamp 窗口），修边缘 clamp 采样伪影 | 现抓精确栏矩形（L484-490） |
| tab 放大 | **1.2**×lift（lerp(1,1.2,press)，FloatingBottomBar.kt:399-401） | 1.16 |

## 两处刻意保留的偏差（照搬边界，如实声明）

1. **运动模型全保留**：TRAVEL/PRESS_S/jelly/rubber/FOLLOW 弹簧与拖拽手感不动——用户 2026-08-11「动效曲线属骨架不动」铁律；仅把 lift 值按新语义喂给照搬来的光学项（refractionHeight=10dp×lift 等）。
2. **亮度归一化 shim 保留**（frostMeanLuma 锚 236/40）：KernelSU 链无归一化，但其 App 内容动态范围窄；我们白卡近全幅，直接撤会复发 11-03 已修的「滑动时栏体灰白跳变」用户报障。保留为标记项，装机对比后可一键撤。

## 不动的部分

SDK≥33 门控与 try/catch 平绘降级、竖排 rail 无玻璃、HTML dock、抓取触发链（滚动停手 250ms/commit 400ms/SPA 补抓）、PANE_FADE_MS 交叉淡化（机制层）、MainActivity 接线（预计零改动）。

## 改动文件

1. **新增** `app/src/main/java/com/netops/handbook/lens/glass/LiquidGlassShaders.java`（verbatim 常量 ~250 行）
2. `LensBarView.java` 手术改：换 AGSL 引用、uniform 重接（size/offset/cornerRadii/refraction* 喂入）、buildBarPane/blurFrost 链重建、platter/tint/shadow 参数、BloomStroke 绘制 + DeviceTilt 传感器、innerShadow、抓取外扩 40dp、mag 1.2
3. 新增 `THIRD_PARTY_NOTICES.md`
4. `LensBarView.java` 现有 ~1237 行未提交改动沿用「终判后再提交」约定，本轮**不提交**

## 验证（逐项可核）

1. `./gradlew :app:compileDebugJavaWithJavac` ✓
2. 五道门禁全 PASS + `node web-src/tools/smoke*.js` 115/115（web-src 零改动，应恒绿——若红即回归信号）
3. serve-lum 本地视觉闭环 390×844 截图：药丸透出**清晰弯折内容**（非霜面）、栏缘折射边、无双影
4. `assembleRelease` 后 `unzip -p | grep` 特征核验：新串（如 `ROUNDED_RECT_REFRACTION_WITH_DISPERSION`）=1、旧 FlClash 光学串（`4.3` 弯折注释锚）=0 —— 防 gradle VFS 旧包
5. 交付 `dist/NetOps-2.1.0-液态玻璃Kyant0移植-20261104.apk`
6. 真机清单（deferred）：观感终判；**平板装 KernelSU 对照跑玻璃**——若其玻璃在平板上亮，追加 RenderNode 活体路径的后续评估；亮度归一化 shim 撤/留拍板