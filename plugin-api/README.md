# :plugin-api 是宿主与第三方插件之间的契约。
#
# 依赖方向固定为：
#   :plugin-api  ←  :core  ←  :server  ←  :app
#                     ↑
#                 第三方插件（只依赖 :plugin-api）
#
# 因此 :plugin-api 必须保持零依赖、纯 Kotlin/JVM：
# 一旦它依赖了 :core 或 Android 框架，插件就会被动拖进整个宿主体积，
# 「宿主与插件解耦」这个前提就不成立了。
#
# 修改本模块的类型属于**破坏性契约变更**，需要同步升插件 API 版本
# （见 M2 计划中的 apiVersion）。
