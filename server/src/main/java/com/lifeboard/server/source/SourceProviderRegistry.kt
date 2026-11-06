package com.lifeboard.server.source

import android.content.Context
import android.util.Log
import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.core.model.SourceRef
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.source.DataSource

/**
 * 主动拉取源注册表（M1.5）。
 *
 * 存在的理由：以前 `DailyAggregateWorker` 里直接 `new ScreenTimeDataSource`、
 * 直接调 `collectMiHealth`，每加一个源就要改 Worker。插件是运行时才装进来的，
 * 更不可能靠改 Worker 来接——所以实例化入口必须先收敛成注册表。
 *
 * **只登记"主动拉取"的源**：Worker 按日期去问它要数据的那种。
 * 通知监听（飞书打卡、多邻国）与规则计数源不在这里——它们由事件驱动实时累加，
 * 没有 `collect(date)` 这种语义，硬塞进来会变成"每分钟重扫一遍通知"，
 * 既不对也白耗电（见 P_plan M1 约束）。
 *
 * **用工厂而不是单例**：`collect` 是 suspend 且实现可能持有状态（如睡眠分段配对），
 * 复用一个实例会把不同日期的数据串起来，所以每次取源都新建。
 */
object SourceProviderRegistry {

    /** 一个可被 Worker 主动拉取的源：标识 + 造实例的方法 */
    class Provider(
        val source: SourceRef,
        val factory: (Context) -> DataSource,
    )

    /**
     * 内置的主动拉取源。
     *
     * 这里**只登记不实例化**：工厂函数在整个进程生命周期内有效，
     * 但真正的对象在每次采集时才创建。
     */
    private val builtin: List<Provider> = listOf(
        Provider(SourceRef.Builtin(SourceType.SCREEN_TIME)) { ScreenTimeDataSource(it) },
        Provider(SourceRef.Builtin(SourceType.MI_HEALTH)) { MiHealthDataSource(it) },
    )

    /**
     * 插件源登记处。
     *
     * M1 阶段插件还没落地，留空；M3 加载器落地后把 `LoadedPlugin` 注册进来即可，
     * Worker 侧无需任何改动——这正是先把注册表做出来的意义。
     */
    @Volatile
    private var pluginProviders: List<Provider> = emptyList()

    /** 替换插件源列表，由插件加载器在扫描完成后调用 */
    fun setPluginProviders(providers: List<Provider>) {
        pluginProviders = providers
    }

    /** 当前的主动拉取源：内置 + 插件 */
    fun providers(): List<Provider> = builtin + pluginProviders

    /**
     * 按标识取一个采集器实例；未登记的源返回 null。
     *
     * 返回 null 而不是抛异常：调用方（Worker/健康检查）面对"源不存在"的正确反应
     * 是跳过它，而不是让整个聚合任务失败。
     */
    fun create(context: Context, source: SourceRef): DataSource? =
        providers().firstOrNull { it.source.key == source.key }?.factory?.invoke(context)

    /**
     * 对指定日期跑一遍全部主动拉取源。
     *
     * **失败隔离**：单源失败只影响它自己的健康度，不中断其他源——
     * 否则一个源登录过期，屏幕时长也一起停采。
     *
     * @return 按源分组的采集结果与健康度
     */
    suspend fun collectAll(
        context: Context,
        date: String,
    ): List<SourceCollection> = providers().map { provider ->
        val dataSource = provider.factory(context)
        val records = runCatching { dataSource.collect(date) }.getOrElse { error ->
            Log.w(TAG, "collect ${provider.source.key} failed for $date", error)
            emptyList()
        }
        val health = runCatching { dataSource.health() }.getOrElse { error ->
            Log.w(TAG, "health ${provider.source.key} failed", error)
            SourceHealth(
                source = provider.source,
                healthy = false,
                message = error.message ?: "采集异常",
            )
        }
        SourceCollection(provider.source, records, health)
    }

    private const val TAG = "LifeBoardSourceReg"

    /** 单个源的采集产物 */
    data class SourceCollection(
        val source: SourceRef,
        val records: List<MetricRecord>,
        val health: SourceHealth,
    )
}
