package com.lifeboard.shared.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lifeboard.shared.dashboard.BuiltinTab
import com.lifeboard.shared.dashboard.BuiltinTabs
import com.lifeboard.shared.dashboard.NewTabDialog
import com.lifeboard.shared.dashboard.TabKind
import com.lifeboard.shared.dashboard.TabManagerDialog
import com.lifeboard.shared.dashboard.TabRowUi
import com.lifeboard.shared.platform.platform
import com.lifeboard.shared.repository.model.DashboardTab
import com.lifeboard.shared.task.TaskIndicatorButton
import com.lifeboard.shared.task.TaskListDialog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * 页签布局的观察结果。
 *
 * `ready` 必须与 [tabs] 放在一起：只靠 `tabs.isEmpty()` 分不出
 * 「还没读完」和「读完了但一个都没有」，而这两种情况的界面完全不同——
 * 前者要给默认布局让路，后者要给恢复入口。
 */
data class TabLayout(
    val tabs: List<DashboardTab> = emptyList(),
    val ready: Boolean = false,
)

/**
 * 页签行里的一项。
 *
 * 与 [DashboardTab] 分开是因为职责不同：那个是数据库里的行（名字、顺序），
 * 这里还要带上「这一端点进去画什么」。合成一个类会让读库和渲染耦合在一起。
 */
private data class TabItem(
    val tabId: String,
    /** 内置页签的路由名；用户页签为 null */
    val route: String?,
    val label: String,
    val kind: TabKind,
    /** 内置页签的原始定义；用户页签为 null。管理弹窗据此决定能不能删 */
    val builtin: BuiltinTab? = null,
    val enabled: Boolean,
)

/**
 * 数据库行转页签行项目。
 *
 * `isBuiltin` 为 true 时按 tabId 找回路由；认不出 tabId 的老内置页签
 * （被删过的、或更高版本才有的）会退化成用户页签，走卡片容器——
 * 那是最接近「它原来可能是什么」的兜底，比画空白页好。
 */
private fun DashboardTab.toItem(): TabItem {
    val builtin = BuiltinTabs.byTabId(tabId)
    return TabItem(
        tabId = tabId,
        // 用户页签没有路由。这里必须留 null 而不是编个假路由名：
        // 假路由名在 PageCatalog 里会落进「未注册」分支，被误报成不可用
        route = builtin?.route,
        label = name,
        kind = if (builtin != null) TabKind.BUILTIN else TabKind.USER,
        builtin = builtin,
        enabled = enabled,
    )
}

/**
 * 内存里的默认页签。
 *
 * 只用于「数据库还没读完」的那一两帧。冷启动第一帧如果是空列表，
 * `ScrollableTabRow` 会闪一下空白再出现页签。
 */
private val defaultTabItems: List<TabItem> = BuiltinTabs.ALL.map { tab ->
    TabItem(
        tabId = tab.tabId,
        route = tab.route,
        label = tab.defaultName,
        kind = TabKind.BUILTIN,
        builtin = tab,
        enabled = true,
    )
}

/**
 * 页签壳：顶部页签行 + 当前页内容。
 *
 * ## 这是 3.4 的目的
 *
 * 之前这段逻辑（选中态、越界回退、空布局兜底、按 route 分发）全在
 * `MainActivity` 里。桌面端要画出同样的页签行，就得把 `MainActivity` 抄一遍。
 * 搬到这里后，两端只需各交一份 [PageCatalog]。
 *
 * ## 为什么不把 [PageCatalog] 放进依赖容器
 *
 * 页面注册表是**界面装配**，不是平台能力：它只在画界面时用一次，
 * 且不同入口注册的内容不同（Android 八个页，桌面只有几个）。放进
 * `PlatformDependencies` 会让人误以为它是进程级能力，也会让依赖容器
 * 反过来依赖 Compose 的 `@Composable`。
 *
 * ## 平台专属页签怎么处理
 *
 * 桌面端库里可能有 `notice` / `debug` 两个页签（从手机同步来的），
 * 但这两个页面在桌面端注册不出来。这里**不删页签、不改数据**，
 * 由调用方在 [pageContent] 里按 [PageSlot.Missing] 画说明页——
 * 删掉会让手机端同步回去时少两个页签。
 *
 * ## 页签管理为什么在这里（M3-3.2c）
 *
 * 「+」与两个弹窗（[NewTabDialog] / [TabManagerDialog]）原本写在
 * `MainActivity` 的 Scaffold 里。它们需要的东西只有三样：全部页签列表
 * （含已隐藏的）、当前选中项、以及一个「切到某个页签」的动作——
 * 前两样只有这里知道，第三样是这里的 `selectTab`。留在各端就会变成
 * 「每个端各抄一遍 + 各端行为逐渐分叉」。
 *
 * 副作用（建/删/改/排序）不在这里做，仍由调用方通过 [tabActions] 提供：
 * 那是要写库的，属于业务层；`DashboardHost` 只管界面。
 *
 * @param layoutFlow 页签布局的观察流。`ready` 为 false 时用默认页签顶一帧
 * @param pageContent 画当前页。`route` 为 null 表示用户页签，走卡片容器
 * @param tabActions 页签增删改的落库入口。为 null 时「+」与弹窗入口都不出现
 *   （桌面端 3.2c 之前就是这种情况）
 * @param onShortcut 注册「快捷键处理器」的入口。页签壳把「怎么执行一条
 *   [Shortcut]」交出去，各端在窗口层把按键翻译成 [Shortcut] 再回调。
 *   传 null 表示撤销注册（页签壳卸载时）。默认空实现即「这一端不支持快捷键」
 */
@Composable
fun DashboardHost(
    catalog: PageCatalog,
    layoutFlow: Flow<TabLayout>,
    pageContent: @Composable (route: String?, host: PageHost) -> Unit,
    onEditTask: (Long) -> Unit,
    onNewTask: () -> Unit,
    onRestoreDefaults: () -> Unit = {},
    tabActions: TabActions? = null,
    onShortcut: (handler: ((Shortcut) -> Unit)?) -> Unit = {},
    /**
     * 是否在页签行右侧画任务指示环。
     *
     * Android 端在自己的 `TopAppBar` 里已经画了一颗，这里再画就是两颗，
     * 所以默认关闭、由桌面端显式打开。
     */
    showTaskIndicator: Boolean = false,
) {
    // 选中态存 tabId 而不是下标：页签顺序可被用户调整，
    // 用下标会在「在最后一个页签时新增一个页签」之类的场景跳到别的页面
    val settings = platform.settings
    var selectedTabId by rememberSaveable {
        mutableStateOf(settings.getString(KEY_SELECTED_TAB, BuiltinTabs.TAB_ID_PANEL))
    }
    // 就绪标志与页签列表一起收：只靠「列表是否为空」分不出「还没读完」
    // 和「读完了但一个都没有」，而这两种情况的界面完全不同。
    // 用 StateFlow 而不是裸 Flow 也是为此——`collectAsState` 对 StateFlow
    // 会立刻取到当前值，首帧就能知道是否就绪，不会闪一下异常页。
    val scope = rememberCoroutineScope()
    val layout by remember(layoutFlow, scope) {
        layoutFlow.stateIn(scope, SharingStarted.Eagerly, TabLayout())
    }.collectAsState()

    val allItems = if (layout.ready) layout.tabs.map { it.toItem() } else defaultTabItems
    // 关掉的页签不进顶部页签行。过滤放在这里而不是渲染时：下面的
    // selectedIndex 回退逻辑要按「实际看得见的页签」算，否则选中一个
    // 关掉的页签会显示成第一个可见页签却没写回偏好
    val items = allItems.filter { it.enabled }

    fun selectTab(tabId: String) {
        selectedTabId = tabId
        settings.putString(KEY_SELECTED_TAB, tabId)
    }

    // 页签管理是压在顶栏上的一层弹窗；「+」是更轻的一层，只问名字。
    // 两者都是纯界面状态，不落库，所以不必提到调用方去
    var showTabManager by remember { mutableStateOf(false) }
    var showTabCreator by remember { mutableStateOf(false) }
    // 任务列表弹窗：与页签管理一样是纯界面状态，不必提到调用方
    var showTaskList by remember { mutableStateOf(false) }

    // 一个可见页签都没有。分两种情况：页签表真的空了（用户全删了），
    // 和页签还在、只是都被关掉了。后者不能走「恢复默认页签」——
    // ensureDefaults 只在页签表为空时才写库，点了不会有任何反应，
    // 用户会被困在这张兜底页里出不来。
    if (layout.ready && items.isEmpty()) {
        if (allItems.isEmpty()) {
            EmptyTabs(
                title = "页签列表为空",
                message = "可能是数据被清空了。恢复默认页签不会删除已有任务与记录。",
                actionLabel = "恢复默认页签",
                onRestore = onRestoreDefaults,
            )
        } else {
            EmptyTabs(
                title = "所有页签都已关闭",
                message = "页签还在，只是都被关掉了。点下面可以重新打开。",
                actionLabel = "重新打开页签",
                onRestore = {
                    val actions = tabActions
                    if (actions != null) {
                        allItems.forEach { actions.setEnabled(it.tabId, true) }
                    } else {
                        onRestoreDefaults()
                    }
                },
            )
        }
        return
    }

    // 存的 tabId 可能已经不存在（页签被删、或来自降级后的旧版本），
    // 一律回退到第一个可见页签，否则会停在一个空白页
    val selectedIndex = items.indexOfFirst { it.tabId == selectedTabId }.takeIf { it >= 0 } ?: 0
    val current = items.getOrNull(selectedIndex)

    // 快捷键的执行端（M4-4.7）。
    //
    // 这里把「怎么执行一条 Shortcut」注册给各端，各端在窗口层把按键翻译成
    // [Shortcut] 再回调进来。方向是「页签壳提供能力、各端接管按键」——只有
    // 这里知道当前有哪些可见页签、选中了哪一个。
    //
    // 必须写在 selectedIndex / items 之后：处理器闭包直接读它们，写在前面
    // 会变成引用还未声明的变量（编译不过，这一点是编译器帮忙挡住的）。
    //
    // 用 SideEffect 而不是 DisposableEffect 注册：闭包必须每帧刷新（items 和
    // selectedIndex 都可能变，旧闭包会让 Ctrl+Tab 总跳到同一处）；撤销单独
    // 交给下面的 DisposableEffect，两件事各自语义清晰。
    SideEffect {
        onShortcut { shortcut ->
            // 越界一律吞掉：Ctrl+9 而只有三个页签时，静默不动比跳到最后一个
            // 更符合预期——用户按错了键，不该被带到别的地方
            val target = when (shortcut) {
                Shortcut.NextTab -> items.getOrNull(selectedIndex + 1) ?: items.firstOrNull()
                Shortcut.PreviousTab -> items.getOrNull(selectedIndex - 1) ?: items.lastOrNull()
                is Shortcut.JumpTo -> items.getOrNull(shortcut.index - 1)
                // 刷新由各页面自己实现：页签壳没有「重新加载当前页」的通用手段，
                // 硬做一个全局重订阅反而会把页面内部状态（滚动位置、编辑中的草稿）冲掉
                Shortcut.Refresh -> null
            }
            if (target != null) selectTab(target.tabId)
        }
    }
    DisposableEffect(Unit) {
        onDispose { onShortcut(null) }
    }

    // 选中页被删掉后，持久化的 tabId 已经失效，把回退后的选择写回去，
    // 否则每次冷启动都要重新回退一次
    LaunchedEffect(selectedIndex, current?.tabId) {
        val id = current?.tabId ?: return@LaunchedEffect
        if (id != selectedTabId) selectTab(id)
    }

    // 页面拿到的壳句柄。key 里带 tabId 是有意的：换了页签就该换一个句柄，
    // 否则页面里 `remember` 住句柄的地方会一直往旧页签跳
    val host = remember(catalog, current?.tabId) {
        object : PageHost {
            // 页面靠这个 id 取「本页签有哪些卡片」。
            // 认不出当前页签时给空串：页面按空页签处理，不会误读别的页签的卡片
            override val tabId: String = current?.tabId.orEmpty()

            override fun navigateTo(route: String) {
                // 路由找不到对应页签时什么都不做：目标页在这一端可能
                // 根本没注册，跳过去只会得到一个空壳
                val target = allItems.firstOrNull { it.route == route } ?: return
                selectTab(target.tabId)
            }

            override fun editTask(taskId: Long) = onEditTask(taskId)

            override fun createTask() = onNewTask()
        }
    }

    // 横向还是纵向放页签，由可用宽度决定（问题 2）。
    //
    // 手机竖屏只有 360~430dp，一行八个页签必须横向滚动，翻找很累；
    // 但电脑与平板的宽度远大于高度，横向的页签行会把宝贵的高度浪费在一条
    // 只有十几个字的横条上，同时宽度空着一大片。所以宽屏下把这一行搬到左侧
    // 变成一列：标签能从两个字恢复到完整名称，页面区拿到的高度也多了一整行。
    //
    // 阈值 840dp 取自 Material 3 的 expanded 断点（≈ 平板横屏 / 桌面窗口）。
    // 不用 `WindowSizeClass` 是因为它只在 Material3-adaptive 里，
    // 而那个库目前只有 Android 构件；`BoxWithConstraints` 两端都有。
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val verticalTabs = maxWidth >= TAB_RAIL_BREAKPOINT

        if (verticalTabs) {
            Row(modifier = Modifier.fillMaxSize()) {
                TabRail(
                    items = items,
                    selectedTabId = current?.tabId,
                    onSelect = { selectTab(it) },
                    onAdd = if (tabActions != null) {
                        { showTabCreator = true }
                    } else {
                        null
                    },
                    showTaskIndicator = showTaskIndicator,
                    onOpenTasks = { showTaskList = true },
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    pageContent(current?.route, host)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 页签行 = 可横向滚动的页签 + 固定在右端的「+」。
                // 「+」不能放进 ScrollableTabRow 里面：那样它会跟着页签一起滚走，
                // 页签一多就找不到新增入口了
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 八个页签在窄屏上放不下，必须可横向滚动：
                    // 用 TabRow 会等分压缩，标签被截成两三个字就看不出是什么了
                    ScrollableTabRow(
                        selectedTabIndex = selectedIndex.coerceAtLeast(0),
                        containerColor = Color.Transparent,
                        edgePadding = 8.dp,
                        modifier = Modifier.weight(1f),
                    ) {
                        items.forEach { tab ->
                            Tab(
                                selected = current?.tabId == tab.tabId,
                                onClick = { selectTab(tab.tabId) },
                                text = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                    // 没有写入口就不画「+」：画出来点了没反应比不画更糟
                    if (tabActions != null) {
                        // 用文字「+」而不是 Icons.Default.Add：`material-icons-core`
                        // 只在 `:app` 里有依赖，为了一个加号把图标库拉进 `shared`
                        // 会给每个模块都多背一份资源。字号按标题放大才能当按钮点击
                        IconButton(onClick = { showTabCreator = true }) {
                            Text(
                                "+",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // 任务指示环。Android 端的顶栏在 `MainActivity` 里自己画了，
                    // 这一颗是给桌面端用的——桌面端没有顶栏，不同步渲染的话
                    // 插件同步那十几秒界面完全没动静，用户分不清在干活还是卡死
                    if (showTaskIndicator) {
                        TaskIndicatorButton(onClick = { showTaskList = true })
                    }
                }

                pageContent(current?.route, host)
            }
        }
    }

    if (showTaskList) {
        TaskListDialog(onDismiss = { showTaskList = false })
    }

    if (tabActions == null) return

    // 两个弹窗放在 Column 之外：它们是独立窗口层，塞进 Column 里会被
    // 页面的布局约束影响（窄屏上弹窗会被压到内容宽度）
    if (showTabCreator) {
        NewTabDialog(
            onDismiss = { showTabCreator = false },
            onManage = {
                showTabCreator = false
                showTabManager = true
            },
            onCreate = { name ->
                // 建完立刻切过去：新页签是空的，停在原页签会让人以为没建成
                tabActions.create(name) { newId -> selectTab(newId) }
            },
        )
    }

    if (showTabManager) {
        TabManagerDialog(
            // 管理弹窗列出**全部**页签（含已关掉的），否则关掉之后就再也
            // 没法打开。这里用 allItems 而不是过滤后的 items
            tabs = allItems.map {
                TabRowUi(
                    tabId = it.tabId,
                    label = it.label,
                    builtin = it.builtin,
                    enabled = it.enabled,
                )
            },
            onDismiss = { showTabManager = false },
            onCreate = { name -> tabActions.create(name) { newId -> selectTab(newId) } },
            onRename = { tabId, name -> tabActions.rename(tabId, name) },
            onDelete = { tab ->
                tabActions.delete(tab.tabId)
                // 删的是当前页时立刻回到面板，不等列表刷新再回退，
                // 否则中间那一帧会用已经删掉的 tabId 去找页面
                if (tab.tabId == selectedTabId) selectTab(BuiltinTabs.TAB_ID_PANEL)
            },
            onMove = { tabId, to -> tabActions.move(tabId, to) },
            onToggleEnabled = { tab, enabled ->
                tabActions.setEnabled(tab.tabId, enabled)
                // 关掉的正好是当前页：立刻切回面板。留在原页会变成
                // 「顶部没有选中项、下面却在画它的内容」的割裂状态
                if (!enabled && tab.tabId == selectedTabId) {
                    selectTab(BuiltinTabs.TAB_ID_PANEL)
                }
            },
        )
    }
}

/**
 * 宽屏下的左侧页签列。
 *
 * 与顶部的 `ScrollableTabRow` 表达同一件事（当前在哪一页、去别的页），
 * 只是方向换成了竖向：
 * - 宽度固定，标签左对齐，能用完整名称而不必缩成两个字
 * - 选中项用一个浅色底块标出，而不是底部指示条——竖向的指示条看不出来
 * - 「+」与任务指示环放在底部，位置固定，页签再多也不会把它们挤走
 *
 * 页签变多时整列可滚动：页签数量没有上限，不能指望永远放得下。
 *
 * @param onAdd 为 null 表示这一端不能新建页签，此时不画「+」
 */
@Composable
private fun TabRail(
    items: List<TabItem>,
    selectedTabId: String?,
    onSelect: (String) -> Unit,
    onAdd: (() -> Unit)?,
    showTaskIndicator: Boolean,
    onOpenTasks: () -> Unit,
) {
    // 玻璃面板的边缘：右侧一条淡描边就够把导航区和内容区分开，
    // 加阴影会在浅色主题下糊成一片
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Column(
        modifier = Modifier
            .width(TAB_RAIL_WIDTH)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
    ) {
        Text(
            "LifeBoard",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEach { tab ->
                val selected = tab.tabId == selectedTabId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { onSelect(tab.tabId) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(divider),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onAdd != null) {
                IconButton(onClick = onAdd) {
                    Text(
                        "+",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (showTaskIndicator) {
                TaskIndicatorButton(onClick = onOpenTasks)
            }
        }
    }
}

/**
 * 宽屏断点。
 *
 * 840dp ≈ Material 3 的 expanded 断点：平板横屏、桌面窗口都在它之上，
 * 手机竖屏（360~430dp）与手机横屏（约 800dp）都在它之下——
 * 手机横屏仍然用顶部的横向页签，因为那时高度本来就很紧张，
 * 抽出一条竖向导航会挤压内容宽度。
 */
private val TAB_RAIL_BREAKPOINT = 840.dp

/** 侧边页签列的宽度。够放四到六个汉字，再多就挤内容区了 */
private val TAB_RAIL_WIDTH = 168.dp

/**
 * 页签增删改的落库入口。
 *
 * 只声明「界面要做哪些写操作」，具体怎么写由各端交给
 * `DashboardViewModel`——那个类在 `commonMain`，两端其实交的是同一份；
 * 但这里仍然做成接口，是为了让 `DashboardHost` 不必持有一个 ViewModel，
 * 也就不会把「页签壳」和「页面状态」耦在一起（否则将来做页面级预览、
 * 或者在测试里单独画页签行时，都得先造一个 ViewModel）。
 *
 * @param onCreate 新建用户页签。实现方负责在成功后调 [onCreated]
 * @param onDelete 删除用户页签，连同它的卡片
 * @param onToggleEnabled 显示 / 隐藏。内置页签也能关，只是不能删
 */
interface TabActions {
    fun create(name: String, onCreated: (String) -> Unit)
    fun rename(tabId: String, name: String)
    fun delete(tabId: String)
    fun move(tabId: String, to: Int)
    fun setEnabled(tabId: String, enabled: Boolean)
}

/**
 * 画「这一端没有这个页面」的占位。
 *
 * 单独抽出来是因为三端都要用同一套措辞：桌面上的「通知源」页不可用、
 * 未来的 Linux 端可能又缺别的页。各端自己写一段字，用户会以为是不同的毛病。
 */
@Composable
fun MissingPageNotice(route: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "这一端没有「${pageTitleOf(route)}」页",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                "页签仍然保留，换到支持的设备上可以继续使用。",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

/** 页签兜底页：页签表为空（数据没了）和页签全被关掉，共用这一张，文案与按钮由调用方给 */
@Composable
private fun EmptyTabs(
    title: String,
    message: String,
    actionLabel: String,
    onRestore: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRestore) { Text(actionLabel) }
        }
    }
}

/** 路由名转中文标题。查不到时原样返回，至少能让用户看出是哪个路由 */
internal fun pageTitleOf(route: String): String =
    BuiltinTabs.byRoute(route)?.defaultName ?: route

/** 选中态在设置里的键。与 M6 之前的键名一致，升级后不会丢选中页 */
private const val KEY_SELECTED_TAB = "selected_tab_id"
