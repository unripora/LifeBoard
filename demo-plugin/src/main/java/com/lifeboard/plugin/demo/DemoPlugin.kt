package com.lifeboard.plugin.demo

import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.CredentialSpec
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.PluginApi
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.PluginMetric
import com.lifeboard.plugin.api.SourcePlugin

/**
 * 最小可用插件，用于验证宿主的完整链路。
 *
 * 它刻意做到「不需要任何外部条件」：
 * - 不联网
 * - 不需要登录
 * - 不读写凭据
 * - 每个日期产出固定值
 *
 * 这样一旦链路出问题，可以确定是宿主侧（加载、注册、采集、落库）的毛病，
 * 而不是网络、Cookie 或第三方接口变化。M5 的小米健康才引入真实复杂度。
 *
 * 两个指标 `DEMO_STEPS` / `DEMO_SCORE` 同时存在，是为了验证：
 * 1. 一个插件能声明多个量纲
 * 2. 不同量纲各自独立成败、各自落库
 */
class DemoPlugin : SourcePlugin {

    override val id: String = "com.lifeboard.plugin.demo"

    override val displayName: String = "演示插件"

    /**
     * 跟随契约版本常量，不写死数字。
     *
     * 写死过 1，M5 把契约升到 2 之后这个演示插件立刻被加载器正确拒绝，
     * 6 个真机用例一起变红——问题不在加载器，而在这里没跟上版本。
     */
    override val apiVersion: Int = PluginApi.VERSION

    /** demo 不需要登录，因此不声明任何凭据槽位 */
    override val credentialSpec: CredentialSpec = CredentialSpec(slots = emptyList())

    override val metrics: List<MetricSpec> = listOf(
        MetricSpec(
            metricKey = "DEMO_STEPS",
            bucket = MetricBucket.STEPS,
            displayName = "演示步数",
        ),
        MetricSpec(
            metricKey = "DEMO_SCORE",
            bucket = MetricBucket.COUNT,
            displayName = "演示次数",
        ),
    )

    override suspend fun login(
        context: PluginContext,
        inputs: Map<String, String>,
    ): LoginResult =
        LoginResult.Failure("演示插件无需登录")

    override suspend fun logout(context: PluginContext) = Unit

    override suspend fun authState(context: PluginContext): AuthState = AuthState.LoggedIn("演示账号")

    /**
     * 产出固定值。
     *
     * 步数取日期里的「日」乘以 100，这样不同日期数值不同，
     * 能一眼看出面板取的是不是当天数据，而不是所有格子都是同一个数。
     */
    override suspend fun fetch(context: PluginContext, date: String): List<PluginMetric> {
        val day = date.takeLast(2).toIntOrNull() ?: 1
        return listOf(
            PluginMetric(metricKey = "DEMO_STEPS", value = day * 100.0),
            PluginMetric(metricKey = "DEMO_SCORE", value = day.toDouble()),
        )
    }

    override suspend fun health(context: PluginContext): PluginHealth = PluginHealth.Healthy
}
