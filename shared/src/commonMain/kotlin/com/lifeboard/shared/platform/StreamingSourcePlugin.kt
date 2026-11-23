package com.lifeboard.shared.platform

import kotlinx.coroutines.flow.SharedFlow

/**
 * 能在**一次调用进行中**主动推送文本增量的插件。
 *
 * ## 为什么不放进 [com.lifeboard.plugin.api.SourcePlugin]
 *
 * 契约模块（`plugin-api`）刻意保持零依赖，`Flow` 不在其中；而且增量输出
 * 是「宿主怎么把中间结果送到界面」的问题，不是插件必须实现的能力——
 * 大多数采集插件一次就返回结果，没有中间状态可推。
 *
 * 于是做成独立能力接口：插件愿意推就实现，不实现的一律走原有返回值，
 * 调用方用 `as?` 判断有没有，不存在「支持 / 不支持」两条调用路径。
 *
 * ## 语义
 *
 * - 只在**调用进行中**推送：调用结束、流被取消后，之后的推送没有订阅者；
 * - 推送的是**文本片段**而不是完整消息，接收方负责拼接（见
 *   `appendStreamDelta`）。这样插件不必知道界面上已有多少内容；
 * - 增量与最终返回值**都要给**：增量用于即时展示，最终
 *   [com.lifeboard.plugin.api.ActionResult.RefreshUi] 才是权威结果。
 *   两者不一致时以最终结果为准（例如插件中途修正了措辞）。
 */
interface StreamingSourcePlugin {

    /**
     * 增量文本流。
     *
     * 热流、无重放：订阅之前的推送会丢失。调用方必须在**发起调用前**订阅，
     * 否则可能丢掉开头几个片段。
     */
    val streamDeltas: SharedFlow<String>
}