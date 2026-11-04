package com.lifeboard.server.notice

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.util.Dates
import com.lifeboard.server.db.CaptureRuleEntity
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.MetricEntity
import com.lifeboard.server.db.RawNotificationEntity
import com.lifeboard.server.panel.toConfigWithRule
import com.lifeboard.server.remind.ConditionEvaluator
import com.lifeboard.server.remind.DoneReminder
import com.lifeboard.server.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/**
 * 读屏采集通道（无障碍）。
 *
 * 为什么需要它：有些行为（典型如飞书打卡成功）只在**应用界面内**给提示，
 * 不进通知栏，通知监听与 NMS hook 都覆盖不到，这是那两条通道共同的盲区。
 *
 * 为什么不用 hook：目标应用若是企业考勤/金融类，hook 进程属于中高风险做法
 * （对方有安全 SDK，需要专门写代码对抗检测）。而这类需求通常只要一个布尔值，
 * 不值得为此付出账号风控的代价。
 *
 * 匹配逻辑全部来自用户在界面上配置的 [com.lifeboard.server.db.CaptureRuleEntity]：
 * 应用包名、必含词、否定词、时段、时间格式、每日幂等，都可由用户调整，
 * 不把任何具体应用的文案写死在代码里。
 *
 * 只读取事件里的可见文本，不做任何点击、输入或修改操作。
 */
class ScreenCaptureAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 有规则的应用包名 → 规则列表，启动时载入一次，规则变更时刷新。
     *
     * 为什么放内存里：每次无障碍事件都查一次 Room，在快速滚动的页面上
     * 每秒会有几十个事件，SQL 查询本身就成了可观的开销。
     * 这张表只在规则改动时变，缓存住即可，代价几乎为零。
     */
    @Volatile
    private var rulesByPackage: Map<String, List<CaptureRuleEntity>> = emptyMap()

    /** 读节点树与写库串行化，避免多个事件同时读同一棵树、同时改计数 */
    private val readMutex = Mutex()

    /** 每个包名待读的内容变化事件，只保留最后一条 */
    private val pendingContent = HashMap<String, PendingContent>()

    /** 合并读取任务，保证同一时刻只有一个在跑 */
    private var flushJob: Job? = null

    /**
     * 待合并的内容变化事件。
     *
     * [firstAt] 是这一轮合并的起点，用于限制最长等待；
     * [lastAt] 是最近一条事件的时间，用于判断页面是否已经安静下来。
     */
    private class PendingContent(
        val event: AccessibilityEvent,
        val firstAt: Long,
        val lastAt: Long,
    )

    /** 灭屏判断用；isInteractive 读的是系统缓存状态，调用本身很便宜 */
    private val powerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    /** 灭屏时是否暂停读屏，设置页可关；默认开启（灭屏开销全是白费） */
    @Volatile
    private var pauseOnScreenOff: Boolean = true

    /** 从设置里读开关，服务连接与设置变更时调用 */
    private fun loadCapturePrefs() {
        pauseOnScreenOff = CapturePrefs.isPauseOnScreenOff(this)
    }

    /**
     * 当前默认输入法的包名，启动与规则刷新时更新。
     *
     * 缓存而不每次读 Settings：输入法切换是低频操作，
     * 而这里每秒可能要判断几十次事件。
     */
    @Volatile
    private var imePackage: String? = null

    /** 从系统设置读当前默认输入法包名，解析失败返回 null（只过滤，不影响采集） */
    private fun loadImePackage() {
        imePackage = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 只订阅「有规则的应用」的事件：系统会据此在投递前就过滤掉无关应用，
        // 这是最省资源的一步——比收到事件后再判断要早，连唤醒都省了。
        applyPackageFilter()
        loadImePackage()
        loadCapturePrefs()
        Log.i(TAG, "service connected, pauseOnScreenOff=$pauseOnScreenOff, ime=$imePackage")
        scope.launch { reloadRules() }
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        synchronized(pendingContent) {
            pendingContent.values.forEach { runCatching { it.event.recycle() } }
            pendingContent.clear()
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        Log.d(TAG, "event ${e.eventType} from $pkg")

        // 闸 0：自身应用与输入法的事件直接丢（对齐 GKD 的 OwnAppEvent / InputMethodEvent）。
        // 正常情况下 packageNames 过滤已把无关包挡在外面，
        // 这两道是防用户误配——把 LifeBoard 或输入法配进规则后，
        // 自己的界面滚动会反复触发事件甚至自己命中自己。
        if (pkg == packageName || pkg == imePackage) return

        val isStateChange = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        // 只关心内容/窗口变化，其余事件量大且无文本
        if (!isStateChange && e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        // 闸 1：灭屏时内容变化直接丢（对齐 GKD 的 ScreenOffContent）。
        // 灭屏后没有「人在看」的界面，读屏毫无意义，白白唤醒进程遍历节点树；
        // 窗口切换保留——亮屏瞬间的那次 STATE_CHANGED 要靠它建立首屏上下文。
        // 这条闸可在设置页关掉：个别机型灭屏时仍会刷新界面（如投屏、息屏显示）。
        if (!isStateChange && pauseOnScreenOff && !powerManager.isInteractive) return

        // 闸 2：没配规则的应用直接返回，不遍历节点树、不查库。
        // 放在最前面是因为节点遍历（深度 12 的整棵树）才是真正的大头开销。
        val pkgRules = rulesByPackage[pkg] ?: return
        if (pkgRules.isEmpty()) return

        // 闸 3：规则时段（读屏窗口）外不读屏。
        // 规则里填的时段决定「什么时候允许在这个应用里计数」，
        // 留空表示全天可读。判断在遍历节点树之前，时段外连读都不读。
        if (!isInCaptureWindow(pkgRules, LocalDateTime.now())) return

        if (isStateChange) {
            // 窗口切换（含独立弹窗出现）单发且信息量最大，立即读
            dispatch(pkg, e)
        } else {
            mergeContentEvent(pkg, e)
        }
    }

    /**
     * 复制事件后异步读取一条。
     *
     * 为什么必须复制：`onAccessibilityEvent` 返回后系统会回收/清空原事件，
     * 之后再读到的数据可能已经不一致（GKD 同样用 `AccessibilityEvent.obtain(event)` 复制）。
     * 为什么异步：`event.source` 是一次跨进程读取，某些应用单次耗时 300ms，
     * 在主线程读会阻塞后续事件投递。
     */
    @Suppress("DEPRECATION")
    private fun dispatch(pkg: String, event: AccessibilityEvent) {
        val copy = runCatching { AccessibilityEvent.obtain(event) }.getOrNull() ?: return
        scope.launch {
            try {
                readMutex.withLock {
                    runCatching { readAndHandle(pkg, copy) }
                        .onFailure { Log.w(TAG, "handle accessibility event failed", it) }
                }
            } finally {
                runCatching { copy.recycle() }
            }
        }
    }

    /**
     * 内容变化事件合并（trailing-edge，合并到**最后一条**再读）。
     *
     * 这里是漏抓「更新打卡成功」弹窗的根因：旧实现放行时间窗内第一条、丢弃其余，
     * 而「点击后出现的提示」恰恰是后出现的，正好落在被丢弃的那一侧，
     * 于是弹窗文字永远进不来。合并到结尾则既省钱又读到最终画面。
     *
     * 与 GKD 对齐：其 `contentMergeMillis = 100`，并且同 key 事件合成批次后取最后一条。
     * [MAX_MERGE_WAIT_MS] 是兜底：页面持续变化（动画、滚动）时也保证至少读一次，
     * 不会因为一直在刷新而永远推迟。
     */
    @Suppress("DEPRECATION")
    private fun mergeContentEvent(pkg: String, event: AccessibilityEvent) {
        val copy = runCatching { AccessibilityEvent.obtain(event) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        synchronized(pendingContent) {
            val existing = pendingContent[pkg]
            existing?.let { runCatching { it.event.recycle() } }
            // 首条时间沿用，用于限制最长等待
            pendingContent[pkg] = PendingContent(copy, existing?.firstAt ?: now, now)
        }
        ensureFlush()
    }

    /** 保证有一个合并任务在跑；事件只由主线程投递，无需额外加锁 */
    private fun ensureFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            while (true) {
                val next = synchronized(pendingContent) {
                    pendingContent.entries.firstOrNull()
                } ?: break
                val now = System.currentTimeMillis()
                val waited = now - next.value.firstAt
                val quiet = now - next.value.lastAt
                if (waited < MAX_MERGE_WAIT_MS) {
                    val wait = MERGE_WINDOW_MS - quiet
                    if (wait > 0) delay(wait)
                }
                val ready = synchronized(pendingContent) { pendingContent.remove(next.key) }
                    ?: continue
                try {
                    readMutex.withLock {
                        runCatching { readAndHandle(next.key, ready.event) }
                            .onFailure { Log.w(TAG, "handle content change failed", it) }
                    }
                } finally {
                    runCatching { ready.event.recycle() }
                }
            }
        }
    }

    private suspend fun readAndHandle(pkg: String, event: AccessibilityEvent) {
        // 处理时再取规则：合并窗口期间规则可能已被修改
        val rules = rulesByPackage[pkg]
        if (rules.isNullOrEmpty()) return
        val texts = collectTexts(event)
        if (texts.isEmpty()) return
        handleTexts(pkg, rules, texts)
    }

    override fun onInterrupt() = Unit

    /** 从库里读规则到内存，并按包名重建系统订阅过滤 */
    private suspend fun reloadRules() {
        runCatching {
            val rules = LifeBoardDatabase.get(this).captureRuleDao().getEnabled()
            rulesByPackage = rules.groupBy { it.packageName }
        }.onFailure { Log.w(TAG, "load rules failed", it) }
        loadImePackage()
        applyPackageFilter()
    }

    /**
     * 该包的规则里是否有任意一条当前处于读屏窗口内。
     *
     * 一个应用可能配了多条规则（各自不同时段），只要有一条在窗口内就该读——
     * 具体哪条命中交给 [CaptureRuleMatcher] 再判一次时段，这里只做
     * 「整包都不可读时提前退出」的粗筛，避免白遍历节点树。
     */
    private fun isInCaptureWindow(rules: List<CaptureRuleEntity>, now: LocalDateTime): Boolean =
        rules.any { it.toRuleSchedule().isActive(now) }

    /**
     * 把规则里出现过的包名写进 `serviceInfo.packageNames`。
     *
     * 这一步是省电的关键：不设时系统会把**所有应用**的窗口事件都投递给本服务，
     * 哪怕立刻 return，进程已经被唤醒、Binder 已经跨进程传输过一次。
     * 设了之后无关应用的事件根本不会送到这里。
     *
     * 没有规则时退回 `null`（订阅全部）：此时反正是空转，
     * 而为空就取消订阅会让用户配好规则后必须手动重开无障碍才生效，体验更差。
     */
    private fun applyPackageFilter() {
        val packages = rulesByPackage.keys.filter { it.isNotBlank() }
        serviceInfo = (serviceInfo ?: return).apply {
            packageNames = if (packages.isEmpty()) null else packages.toTypedArray()
        }
    }
    private suspend fun handleTexts(
        pkg: String,
        rules: List<CaptureRuleEntity>,
        texts: List<String>,
    ) {
        val db = LifeBoardDatabase.get(this)
        val now = LocalDateTime.now()
        val date = Dates.todayString()
        val dao = db.metricDao()

        // 一次事件里常包含整棵子树的文本，同一条提示会出现多次；
        // 页面重绘还会在几百毫秒内反复触发。按内容去重，避免流水刷屏和重复计数。
        texts.distinct().forEach { raw ->
            val hits = CaptureRuleMatcher.match(rules, raw, now)
            if (isRecentDuplicate(pkg, raw)) {
                Log.i(TAG, "skip recent duplicate text: ${raw.take(30)}")
                return@forEach
            }

            // 原始流水照记，**命中与否都记**：
            // 这页的用途就是「规则调不准时看真正读到了什么」，
            // 只记命中的话，规则写错时这里永远空白，反而看不出问题出在读屏还是出在关键词。
            runCatching {
                db.rawNotificationDao().insert(
                    RawNotificationEntity(
                        packageName = pkg,
                        title = "读屏",
                        text = raw,
                        matchedSources = hits.joinToString(",") { it.rule.sourceName },
                    ),
                )
                db.rawNotificationDao().trim(200)
            }.onFailure { Log.w(TAG, "log raw failed", it) }

            if (hits.isEmpty()) return@forEach

            hits.forEach { hit ->
                val rule = hit.rule
                val existing = dao.getOne(rule.sourceName, date, rule.bucketName)
                val current = existing?.value ?: 0.0
                if (rule.dailyOnce && current >= 1.0) {
                    Log.i(TAG, "daily once reached for ${rule.sourceName}, skip")
                    return@forEach
                }
                val next = if (rule.dailyOnce) 1.0 else current + 1.0
                dao.upsert(
                    MetricEntity(
                        sourceType = rule.sourceName,
                        date = date,
                        bucket = rule.bucketName,
                        value = next,
                    ),
                )
                Log.i(
                    TAG,
                    "hit ${rule.sourceName} pkg=$pkg time=${hit.timeText} -> $next",
                )
                notifyTaskCompletion(rule, current, next, date)
            }
        }
    }

    /**
     * 短窗去重：同一条提示会因页面重绘、子树遍历被反复读到。
     *
     * 10 秒窗口足够覆盖一次操作的重绘期，又不会把「明天同一时间同样的文案」
     * 误判成重复。
     */
    private fun isRecentDuplicate(pkg: String, text: String): Boolean = synchronized(recent) {
        val now = System.currentTimeMillis()
        recent.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        val key = "$pkg|$text"
        if (recent.containsKey(key)) {
            true
        } else {
            recent[key] = now
            while (recent.size > 200) recent.remove(recent.keys.first())
            false
        }
    }

    /**
     * 命中后立即判定受影响的任务，完成时按「完成提醒」配置提示。
     *
     * 判定挂在命中链路上而不是面板刷新上：规则命中 → metric 记数 → 这里立刻判定，
     * 任务完成不需要用户打开应用点「重新判定」。
     *
     * 判定统一走 [ConditionEvaluator]（提醒链路的同一套规则），
     * 它会把完成状态与连击写进 daily_record，面板观察到的就是最新结果。
     *
     * 提示条件：
     * - 任务关联了本规则（量纲以规则为唯一事实来源）；
     *   未关联的任务只在量纲恰好一致时才算（数据本来就读不到）
     * - 本次写入刚好跨越阈值（`before < threshold ≤ after`）——
     *   同一天里的第二次、第三次命中不再提示，避免刷屏
     * - 档位与浮窗开关各自独立：档位管横幅/响铃，浮窗只管应用内 Toast
     */
    private suspend fun notifyTaskCompletion(
        rule: CaptureRuleEntity,
        before: Double,
        after: Double,
        date: String,
    ) {
        if (after <= before) return
        val tasks = LifeBoardDatabase.get(this).taskDao().getEnabled()
            .filter {
                it.ruleId == rule.id ||
                    (it.ruleId == 0L && it.sourceType == rule.sourceName && it.metricBucket == rule.bucketName)
            }
        if (tasks.isEmpty()) return
        val evaluator = ConditionEvaluator(this)
        tasks.forEach { entity ->
            runCatching { evaluator.evaluate(entity.id, date) }
                .onFailure { Log.w(TAG, "evaluate task ${entity.id} failed", it) }
            if (after < entity.threshold || before >= entity.threshold) return@forEach
            val task = entity.toConfigWithRule(this, SourceRegistry.resolve(this, entity.sourceType))
            Log.i(TAG, "task completed: ${task.title}")
            runCatching { DoneReminder.dispatch(this, task, after) }
                .onFailure { Log.w(TAG, "dispatch done remind failed", it) }
            if (task.doneToastEnabled) showToast("${task.title}已完成")
        }
    }

    /**
     * 弹「{任务名}已完成」提示，普通 Toast 时长。
     *
     * Toast 必须在主线程 show，而命中处理跑在 IO 线程，这里切一次。
     * 用 applicationContext：Toast 不持有 Activity 上下文，服务销毁后也能显示完。
     */
    private fun showToast(message: String) {
        scope.launch(Dispatchers.Main) {
            runCatching {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }.onFailure { Log.w(TAG, "show toast failed", it) }
        }
    }

    /**
     * 收集事件涉及的所有可见文本，含子树。
     * 只对已通过包名过滤的事件调用。
     *
     * 用 `event.source` 而不是 `rootInActiveWindow`：前者是「发生变化的那个节点」，
     * 范围小但精确，读到的必然是本应用界面的内容；
     * 后者在当前应用有浮层、悬浮窗、系统遮罩时会指向别的窗口，
     * 读进来的是状态栏电量、信号之类的无关文案。
     */
    private fun collectTexts(event: AccessibilityEvent): List<String> {
        val out = mutableListOf<String>()
        event.text?.forEach { it?.toString()?.takeIf(String::isNotBlank)?.let(out::add) }
        event.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)

        val root: AccessibilityNodeInfo? = runCatching { event.source }.getOrNull()
        if (root != null) {
            runCatching { walk(root, out, depth = 0) }
            runCatching { root.recycle() }
        }
        return out
    }

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            walk(child, out, depth + 1)
            runCatching { child.recycle() }
        }
    }

    companion object {
        private const val TAG = "LifeBoardScreen"

        private const val MAX_DEPTH = 12
        private const val DEDUP_WINDOW_MS = 10_000L

        /** 内容变化的合并窗口：页面安静这么久之后才读一次，读到的是最终画面 */
        private const val MERGE_WINDOW_MS = 150L

        /** 合并的最长等待：页面持续变化（动画、滚动）时也保证每秒至少读一次 */
        private const val MAX_MERGE_WAIT_MS = 1_000L

        /** 最近命中的文本，用于跨事件去重 */
        private val recent = LinkedHashMap<String, Long>()

        /** 当前存活的实例，供规则变更后立即刷新订阅 */
        @Volatile
        private var instance: ScreenCaptureAccessibilityService? = null

        /**
         * 规则变更后通知服务刷新。
         *
         * 服务不在内存里时静默跳过：它下次被系统拉起时会在 `onServiceConnected`
         * 里重新读库，不需要额外兜底。
         */
        fun notifyRulesChanged() {
            instance?.let { service ->
                service.scope.launch { service.reloadRules() }
            }
        }

        /** 采集偏好变更后通知服务立即生效，与服务存活与否的关系同上 */
        fun notifyPrefsChanged() {
            instance?.loadCapturePrefs()
        }

        /** 无障碍服务是否已启用 */
        fun isEnabled(context: Context): Boolean {
            // Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 不是公开常量，只能用字面量
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_accessibility_services",
            ) ?: return false
            val component = ComponentName(
                context,
                ScreenCaptureAccessibilityService::class.java,
            )
            return flat.split(':').any {
                ComponentName.unflattenFromString(it) == component
            }
        }

        /** 跳到系统无障碍设置页 */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}

/**
 * 采集偏好存储，服务与设置页共用。
 *
 * 放在 server 模块而不是 app：读屏服务在这里，依赖方向是 app → server，
 * 偏好跟着被读的一方走。只有一个布尔键，SharedPreferences 足够，
 * 走 Room 反而多一次协程往返。
 */
object CapturePrefs {

    private const val FILE = "lifeboard_capture"
    private const val KEY_PAUSE_ON_SCREEN_OFF = "pause_on_screen_off"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 灭屏时是否暂停读屏，默认开启 */
    fun isPauseOnScreenOff(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PAUSE_ON_SCREEN_OFF, true)

    fun setPauseOnScreenOff(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSE_ON_SCREEN_OFF, value).apply()
    }
}
