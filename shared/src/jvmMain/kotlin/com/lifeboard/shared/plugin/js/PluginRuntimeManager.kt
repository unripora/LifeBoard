package com.lifeboard.shared.plugin.js

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * 插件运行时的租约管理器。
 *
 * ## 它解决什么
 *
 * 原先 `scan()` 直接创建运行时并一直持有到进程退出。引入按需加载后必须回答：
 * 谁在用、什么时候可以关、关了之后调用方怎么拿到新实例。
 *
 * 这里的做法是把「运行时实例」与「使用方」解耦：
 * - 使用方拿 [Lease]，代表「我在用，别关」
 * - 引用归零后按策略空闲释放
 * - 释放后再次申请会创建新实例（代际 +1），旧调用者不会拿到已关闭的实例
 *
 * ## 并发约束（关键）
 *
 * - 状态转换全部在 [lock] 内完成，且**只做簿记**：不执行 JS、不等待 I/O、
 *   不调用钩子，避免把插件卡顿传导成全局串行。
 * - 创建实例可能很慢（解包 + QuickJS 初始化），所以创建发生在锁外；
 *   用 [Slot.creating] 保证同一插件同时只有一个创建者。
 * - [Lease.release] 幂等，且只归还自己那一份计数。
 *
 * ## 与扫描的关系
 *
 * 扫描只更新「包元数据与信任状态」，不创建运行时。元数据里标记
 * [revocable]（是否允许自动回收）；旧包或回收总开关关闭时为 false，
 * 此时即使空闲也不自动关闭。
 */
class PluginRuntimeManager<T : Any>(
    private val create: (key: String) -> T,
    private val close: (T) -> Unit,
    /** 允许自动回收（总开关）。关闭时只登记，不驱逐 */
    @Volatile var autoEvictEnabled: Boolean = false,
    /** 可注入的时钟，便于测试空闲判定 */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : AutoCloseable {

    /**
     * 空闲回收专用的关闭回调（可选）。
     *
     * 与 [close] 的区别只在「原因」：空闲回收允许插件做尽力清理，
     * 而强制停机（撤权/清除）不该再执行插件代码。早先两者共用一个回调，
     * 将来收紧钩子权限时就会出错，所以这里单独给一个入口。
     */
    @Volatile
    var idleClose: ((T) -> Unit)? = null

    /** 一个插件的运行时槽位 */
    private class Slot<T>(
        val key: String,
        var runtime: T?,
        var generation: Long,
        var leases: Int,
        var lastUsedAtMs: Long,
        var policy: PluginRuntimePolicy,
        /** 是否允许被自动回收；false 表示旧包兼容保活或总开关关闭 */
        var revocable: Boolean,
        /** 是否正在创建中；同一时刻只允许一个创建者 */
        var creating: Boolean = false,
        /** 已提交关闭，后续申请需要等待或重建 */
        var closing: Boolean = false,
    )

    private val lock = Any()
    private val slots = LinkedHashMap<String, Slot<T>>()
    // 槽位可被撤销后重建，但实例身份不能复用，否则旧租约会命中新实例。
    private var nextGeneration = 0L

    /** 定时清扫：按策略驱逐空闲实例。守护线程，宿主退出不影响 */
    private var sweeper: ScheduledExecutorService? = null

    /**
     * 申请结果。
     *
     * `Lease` 不是泛型内部类，因此这里不需要指定外层类型参数；
     * 实例访问统一走 [Lease.withRuntime]，由管理器校验代际。
     */
    sealed interface AcquireResult {
        /** 拿到可用租约 */
        data class Ready(val lease: Lease) : AcquireResult
        /** 信任已撤销、已停用或被预算拒绝 */
        data class Unavailable(val reason: String) : AcquireResult
    }

    /** 取当前代际的实例；代际不符返回 null，避免用旧实例执行 */
    internal fun <R> withLeasedRuntime(key: String, generation: Long, block: (T) -> R): R? =
        synchronized(lock) {
            val slot = slots[key] ?: return null
            if (slot.generation != generation || slot.runtime == null || slot.closing) return null
            @Suppress("UNCHECKED_CAST")
            block(slot.runtime as T)
        }

    /** 供顶层 [Lease] 使用的类型擦除访问入口 */
    internal fun <R> withLeasedRuntimeUnchecked(key: String, generation: Long, block: (Any) -> R): R? =
        withLeasedRuntime(key, generation, block)

    /** 供 [Lease] 以函数引用调用的访问入口：代际不符返回 null */
    internal fun accessLease(key: String, generation: Long, block: (Any) -> Any?): Any? =
        withLeasedRuntimeUnchecked(key, generation, block)

    /** 归还计数；只减同代际的计数（调用方已持锁） */
    private fun returnLease(key: String, generation: Long) {
        val slot = slots[key] ?: return
        if (slot.generation != generation) return
        slot.leases = max(0, slot.leases - 1)
        if (slot.leases == 0) {
            slot.lastUsedAtMs = nowMs()
        }
    }

    /** 内部实现：代际不符返回 null，避免用旧实例执行 */
    /** 暴露给 [Lease] 的归还入口 */
    internal fun releaseLease(key: String, generation: Long) {
        synchronized(lock) { returnLease(key, generation) }
    }

    /**
     * 申请一个租约；实例不存在或已释放时创建。
     *
     * 创建在锁外执行，但用 [Slot.creating] 合并并发申请：后到者等待先到者
     * 创建完成，不会重复执行入口脚本。
     */
    fun acquire(key: String, policy: PluginRuntimePolicy, revocable: Boolean): AcquireResult {
        val slot = synchronized(lock) {
            slots.getOrPut(key) {
                Slot(key, null, 0, 0, nowMs(), policy, revocable)
            }.also {
                it.policy = policy
                it.revocable = revocable
            }
        }
        // 等待预算使用真实单调时钟，不能依赖测试注入的空闲时钟是否向前走。
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CREATE_WAIT_MS)
        while (true) {
            val createNow = synchronized(lock) {
                if (slots[key] !== slot) return AcquireResult.Unavailable("插件实例已被撤销")
                if (slot.closing) return AcquireResult.Unavailable("运行时正在关闭，请稍后重试")
                if (slot.runtime != null) {
                    slot.leases++
                    slot.lastUsedAtMs = nowMs()
                    return AcquireResult.Ready(Lease(this::accessLease, this::releaseLease, key, slot.generation))
                }
                if (slot.creating) false else {
                    slot.creating = true
                    true
                }
            }
            if (createNow) break
            if (System.nanoTime() >= deadline) {
                return AcquireResult.Unavailable("插件正在初始化，请稍后重试")
            }
            Thread.sleep(CREATE_POLL_MS)
        }

        // 创建和关闭都在锁外；撤权可能在创建中删除此槽位，禁止迟到实例重新发布。
        val created = runCatching { create(key) }
        val result = synchronized(lock) {
            if (slots[key] !== slot) null else {
                slot.creating = false
                created.fold(
                    onSuccess = { runtime ->
                        slot.runtime = runtime
                        slot.generation = ++nextGeneration
                        slot.leases = 1
                        slot.lastUsedAtMs = nowMs()
                        AcquireResult.Ready(Lease(this::accessLease, this::releaseLease, key, slot.generation))
                    },
                    onFailure = { error ->
                        AcquireResult.Unavailable("插件加载失败：${error.message ?: error::class.simpleName}")
                    },
                )
            }
        }
        if (result != null) return result
        created.getOrNull()?.let { runtime -> runCatching { close(runtime) } }
        return AcquireResult.Unavailable("插件在初始化期间已被撤销")
    }

    /**
     * 更新某插件的生效策略。
     *
     * 用于常驻名额调整：被降级的实例立即改为按需回收，不必等下一次申请。
     */
    fun updatePolicy(key: String, policy: PluginRuntimePolicy) {
        synchronized(lock) {
            slots[key]?.policy = policy
        }
    }

    /** 当前占用常驻名额的插件 id（按生效策略判定） */
    fun residentKeys(): List<String> = synchronized(lock) {
        slots.values
            .filter { it.runtime != null && it.policy.effective == PluginLifecycle.RESIDENT }
            .map { it.key }
    }

    /** 某插件当前是否有活跃租约；用于判断常驻名额能否安全回收 */
    fun isLeaseFree(key: String): Boolean = synchronized(lock) {
        (slots[key]?.leases ?: 0) == 0
    }

    /** 常驻实例中可用作 LRU 降级候选的（无租约、最久未用） */
    fun residentIdleLru(): String? = synchronized(lock) {
        slots.values
            .filter {
                it.runtime != null &&
                    it.leases == 0 &&
                    !it.closing &&
                    it.policy.effective == PluginLifecycle.RESIDENT
            }
            .minByOrNull { it.lastUsedAtMs }
            ?.key
    }

    /** 空闲最久且无租约的实例；没有候选时返回 null */
    fun leastRecentlyUsedIdle(): String? = synchronized(lock) {
        slots.values
            .filter { it.runtime != null && it.leases == 0 && !it.closing }
            .minByOrNull { it.lastUsedAtMs }
            ?.key
    }

    /**
     * 按当前策略驱逐所有可回收且空闲超标的实例。
     *
     * @return 实际关闭的数量
     */
    fun evictIdle(): Int {
        if (!autoEvictEnabled) return 0
        val victims = synchronized(lock) {
            val now = nowMs()
            slots.values.filter { eligibleForEviction(it, now) }
        }
        return victims.count { closeIfIdle(it) }
    }

    private fun eligibleForEviction(slot: Slot<T>, now: Long): Boolean =
        slot.runtime != null && slot.leases == 0 && !slot.closing && slot.revocable &&
            when (slot.policy.effective) {
                PluginLifecycle.ON_DEMAND -> now - slot.lastUsedAtMs >= slot.policy.idleTimeoutSec * 1000L
                PluginLifecycle.SESSION_SCOPED -> true
                PluginLifecycle.RESIDENT -> false
            }

    /** 重新核对原槽位及超时，避免候选被更新、使用或替换后误关新实例。 */
    private fun closeIfIdle(slot: Slot<T>): Boolean {
        val runtime: T
        synchronized(lock) {
            if (!autoEvictEnabled || slots[slot.key] !== slot || !eligibleForEviction(slot, nowMs())) return false
            slot.closing = true
            runtime = checkNotNull(slot.runtime)
        }
        val ok = runCatching { (idleClose ?: close)(runtime) }
        synchronized(lock) {
            if (slots[slot.key] === slot) {
                slot.closing = false
                if (ok.isSuccess) slot.runtime = null
                // 关闭失败仍计作存活，不让新申请误以为资源已经释放。
            }
        }
        return ok.isSuccess
    }

    /**
     * 强制停机：撤销信任、卸载、清除数据或故障。
     *
     * 与 [evictIdle] 的区别是**不受租约保护**——这些场景下继续执行插件代码
     * 本身就是错误的。返回前会把该插件从管理表里移除。
     */
    fun shutdown(key: String, closer: (T) -> Unit = close): Boolean {
        val runtime: T
        synchronized(lock) {
            val slot = slots.remove(key) ?: return true
            // 空闲关闭已取得所有权，不能再关闭同一个 native 实例。
            if (slot.closing) return true
            runtime = slot.runtime ?: return true
        }
        return runCatching { closer(runtime) }.isSuccess
    }

    /** 关闭全部实例（宿主退出） */
    fun shutdownAll(closer: (T) -> Unit = close) {
        val all = synchronized(lock) {
            val snapshot = slots.values.filterNot { it.closing }.mapNotNull { it.runtime }
            slots.clear()
            snapshot
        }
        all.forEach { runCatching { close(it) } }
    }

    /** 当前仍然存活的实例数，供资源基线与诊断 */
    fun liveCount(): Int = synchronized(lock) { slots.values.count { it.runtime != null } }

    /** 当前存活实例的快照，供加载器列出已启动插件 */
    fun snapshot(): List<T> = synchronized(lock) {
        slots.values.mapNotNull { it.runtime }
    }

    /** 某插件的当前租约数，供测试与诊断 */
    fun leaseCount(key: String): Int = synchronized(lock) { slots[key]?.leases ?: 0 }

    /** 启动周期清扫；重复调用无副作用 */
    fun startSweeper(intervalSeconds: Long = 15) {
        if (sweeper != null) return
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "plugin-runtime-sweeper").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay({
            runCatching { evictIdle() }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS)
        sweeper = executor
    }

    override fun close() {
        stopSweeper()
        shutdownAll()
    }

    fun stopSweeper() {
        sweeper?.shutdownNow()
        sweeper = null
    }

    private companion object {
        /** 等待他人创建完成的兜底上限 */
        const val CREATE_WAIT_MS = 5_000L
        const val CREATE_POLL_MS = 20L
    }
}

/**
 * 一台租约：代表「有一个使用方在持有某代际的实例」。
 *
 * 放在顶层而不是作为内部类，是因为它要出现在 [PluginRuntimeManager.AcquireResult]
 * 里；内部类被泛型类型引用时 Kotlin 要求显式指定外层类型参数，会逼着调用方
 * 到处写 `PluginRuntimeManager<X>.Lease`。
 *
 * ## 用法
 *
 * 实例本身不持有运行时引用，只记录 `(key, generation)` 与两个回调。
 * 取实例走 [withRuntime]，由管理器校验代际——这样即使实例已被回收，
 * 调用方也拿不到已关闭的运行时。
 */
class Lease internal constructor(
    private val access: (key: String, generation: Long, block: (Any) -> Any?) -> Any?,
    private val returnTicket: (key: String, generation: Long) -> Unit,
    val key: String,
    val generation: Long,
) {
    private val released = AtomicBoolean(false)

    /** 是否已归还 */
    val isReleased: Boolean get() = released.get()

    /**
     * 在当前代际上执行一段操作。
     *
     * @return 实例已被回收或代际已变时返回 `null`，调用方应据此重建或提示
     */
    @Suppress("UNCHECKED_CAST")
    fun <R> withRuntime(block: (Any) -> R): R? {
        if (released.get()) return null
        return access(key, generation, block as (Any) -> Any?) as R?
    }

    /**
     * 归还租约。幂等；重复调用不产生副作用。
     *
     * 归还后若计数归零，刷新 lastUsed，让空闲计时从「最后一次使用结束」开始。
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        returnTicket(key, generation)
    }
}
