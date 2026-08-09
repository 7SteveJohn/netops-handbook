# NetOps 2.0 ProGuard 规则
# 应用主体为 WebView 壳，核心逻辑全在 assets/index.html 中，
# 此处仅保留 Android 框架类不被混淆即可。

-keep class com.netops.handbook.** { *; }
-keepattributes Exceptions,InnerClasses,Signature,SourceFile,LineNumberTable
-dontwarn **
