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
