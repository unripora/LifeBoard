# 契约模块不混淆：插件编译时才见得到这些类型，
# 混淆会让插件的编译期符号与宿主运行时符号对不上。
-keep class com.lifeboard.plugin.api.** { *; }
