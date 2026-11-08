# 第三方代码声明

本工程包含以下第三方开源代码。按各自许可证要求，版权与许可声明集中列于本文件。

> **说明**：本项目整体对外口径为「仅供学习交流、禁止商用」（见 [LICENSE](./LICENSE)）。
> 下列第三方组件的**原始许可条款不因此改变**——它们仍按各自许可证（如 MIT）提供，
> 其授权范围不受本项目限制，也不得被本项目改写。

---

## AndroidLiquidGlassView

- 来源：https://github.com/QmDeve/AndroidLiquidGlassView
- 位置：`app/src/main/java/com/qmdeve/liquidglass/**`、`app/src/main/res/raw/liquidglass_effect.agsl`
- 修改：包内 `R` 引用改为本项目 `com.netops.handbook.R`；`Math.clamp(double,double,double)`
  改为 `Math.max/min`（compileSdk 34 无该 API）；文件头注释移除，声明集中于本文件。
- 许可证：MIT

```
MIT License

Copyright (c) 2025-2026 Donny Yale

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## AndroidLiquidGlass（Kyant0）

- 来源：https://github.com/Kyant0/AndroidLiquidGlass
- 位置：`app/src/main/java/com/netops/handbook/lens/glass/LiquidGlassShaders.java`
  （AGSL：rounded-rect 折射、带色散折射、SDF 辅助）
- 修改：Kotlin 常量转为 Java 字符串常量，原文逐字未改（已与 KernelSU
  v3.3.1 装机包 dex 内嵌 shader 逐字核验一致）。
- 许可证：Apache-2.0

## miuix-blur（compose-miuix-ui/miuix）

- 来源：https://github.com/compose-miuix-ui/miuix（模块 `miuix-blur`，
  SPDX-License-Identifier: Apache-2.0）
- 位置：同上 `LiquidGlassShaders.java`（colorControls、噪声抖动、BloomStroke
  双光源高光 shader、模糊常数 BLUR_RADIUS_TO_SIGMA/BLUR_KERNEL_REACH/
  downScaleExpFor）；`LensBarView.java` 内 `drawBloomStroke`/`drawInnerShadow`/
  重力量化角为其 Kotlin 逻辑的 Java 直译。
- 修改：Compose GraphicsLayer 管线改为本项目的 Paint/RuntimeShader 直画
  管线；shader 源文本逐字未改。
- 许可证：Apache-2.0

```
Apache License, Version 2.0（全文见 https://www.apache.org/licenses/LICENSE-2.0）

Licensed under the Apache License, Version 2.0 (the "License"); you may not
use these files except in compliance with the License. You may obtain a copy
of the License at http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
```
