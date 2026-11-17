package com.lifeboard.shared.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * 管理弹窗用的页签视图。
 *
 * 刻意不复用 [com.lifeboard.shared.navigation.DashboardHost] 内部的 `TabItem`：
 * 那个类带 route 与分发语义，是主界面的内部模型；弹窗只需要
 * 「能显示的页签 + 能不能删」，两边的字段将来大概率各自演化，
 * 耦在一起反而要互相迁就。
 *
 * @param builtin 内置页签原始定义；用户页签为 null。弹窗据此决定要不要显示「删除」
 */
data class TabRowUi(
    val tabId: String,
    val label: String,
    /** 内置页签有值，用户页签为 null */
    val builtin: BuiltinTab?,
    /** 是否在顶部页签行里显示 */
    val enabled: Boolean = true,
)

/**
 * 页签管理弹窗（M6b1）。
 *
 * 只做页签本身：新增、改名、删除、排序、显示开关。卡片的管理在面板里的
 * 「调整卡片」中——两件事混在一个弹窗里，用户看不出「删页签」会连带删掉卡片。
 *
 * ## 内置页签为什么允许改名、允许关掉，但不允许删
 *
 * 改名只动 `dashboard_tab.name`，route 分发靠 `tabId` 反查，改名字不影响任何页面。
 * 删除则会让内置页面失去唯一入口（比如设置页），用户再也进不去。
 * 所以删除按钮只出现在用户页签上，这不是隐藏功能，是这类页签本就没有删除语义。
 *
 * 「关掉显示」是给内置页签留的那条退路：不想看到它，但也不该为此冒险删掉入口。
 *
 * ## 排序为什么是长按拖动
 *
 * 上下移动的箭头在页签有七八个时要连点十几次才能挪到最前，而且每点一次
 * 中间就重排一次列表、按钮位置跟着跳，手指很容易点到别的页签上。长按拖动
 * 一次到位，也符合「排序」这件事在手机上的通用手感。桌面端鼠标长按同样可用。
 *
 * ## 为什么搬进 `commonMain`（M3-3.2c）
 *
 * 这里一行平台代码都没有：`BuiltinTab` 早就在 `shared` 里（见 [BuiltinTabs]），
 * 校验与长度上限（[validateTabName] / [MAX_TAB_NAME_LENGTH]）也在同一模块。
 * 原先留在 `:app` 只是因为「还没轮到搬」。搬完桌面端才能自建页签——
 * 否则面板搬过去了却只有一个内置页签，用户没法给卡片分页。
 */
@Composable
fun TabManagerDialog(
    tabs: List<TabRowUi>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (TabRowUi) -> Unit,
    onMove: (String, Int) -> Unit,
    onToggleEnabled: (TabRowUi, Boolean) -> Unit,
) {
    // null = 不在编辑；非 null = 正在改名的那一行
    var renaming by remember { mutableStateOf<TabRowUi?>(null) }
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<TabRowUi?>(null) }

    val drag = rememberTabDrag(
        count = tabs.size,
        onDrop = { from, to -> tabs.getOrNull(from)?.let { onMove(it.tabId, to) } },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("页签管理") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "长按一行可拖动排序；关掉开关只是不显示，不会删掉页签与卡片。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tabs.forEachIndexed { index, tab ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    TabRow(
                        tab = tab,
                        index = index,
                        drag = drag,
                        onRename = { renaming = tab; newName = tab.label },
                        onDelete = { pendingDelete = tab },
                        onToggleEnabled = { onToggleEnabled(tab, it) },
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "新增页签",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    isError = newName.length > MAX_TAB_NAME_LENGTH,
                    label = { Text("页签名") },
                    supportingText = { Text(tabNameHint(newName)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        onCreate(newName)
                        newName = ""
                    },
                    enabled = validateTabName(newName) != null,
                ) { Text("添加") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )

    renaming?.let { tab ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名页签") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    isError = newName.length > MAX_TAB_NAME_LENGTH,
                    label = { Text("页签名") },
                    supportingText = { Text(tabNameHint(newName)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(tab.tabId, newName)
                        renaming = null
                    },
                    enabled = validateTabName(newName) != null,
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            },
        )
    }

    pendingDelete?.let { tab ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除页签") },
            text = { Text("「${tab.label}」以及它上面的卡片都会被删除，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(tab)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 输入框下方的说明文字。
 *
 * 不做输入截断，只说明原因：直接吞掉超出的字符，用户会以为是自己没打上，
 * 或者以为输入法有问题。
 */
private fun tabNameHint(raw: String): String = when {
    raw.isBlank() -> "输入页签名称，最多 $MAX_TAB_NAME_LENGTH 个字符"
    raw.length > MAX_TAB_NAME_LENGTH ->
        "超出 $MAX_TAB_NAME_LENGTH 个字符，请缩短后再提交"
    else -> "${raw.trim().length}/$MAX_TAB_NAME_LENGTH"
}

/**
 * 管理弹窗里的单行：页签名 + 显示开关 + 改名 / 删除。
 *
 * 整行是拖拽手柄：这里没有卡片内部那种「点开待办」的手势要避让，
 * 整行都能长按反而更容易按到。
 */
@Composable
private fun TabRow(
    tab: TabRowUi,
    index: Int,
    drag: TabDragState,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tabDragHandle(index = index, count = drag.count, drag = drag)
            .clip(RoundedCornerShape(8.dp))
            // 被拖的那一行给个底色，否则它压住别的行时看不出是浮起来的
            .background(
                if (drag.isDragging(index)) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                },
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tab.label,
                style = MaterialTheme.typography.bodyLarge,
                // 关掉的页签在列表里要一眼看出来，否则用户会以为开关没生效
                color = if (tab.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                buildString {
                    append(if (tab.builtin != null) "内置页签" else "自定义页签")
                    if (!tab.enabled) append(" · 已隐藏")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
            )
        }
        Switch(checked = tab.enabled, onCheckedChange = onToggleEnabled)
        TextButton(onClick = onRename) { Text("改名") }
        // 内置页签没有删除语义：删掉之后内置页面就没有入口了
        if (tab.builtin == null) {
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

/**
 * 页签拖拽排序的状态。
 *
 * 与面板里的卡片拖拽是同一个套路（长按后跟手、被拖项原地不动、其余项实时让位、
 * 松手才落库），但没有复用同一份实现，原因有两条：
 *
 * 1. 面板 `PanelScreen` 里那份是 private，且绑着「卡片高度不固定」的测量逻辑；
 *    这里的行高基本一致，但弹窗里的行会被改名的对话框打断，多一层耦合没有收益
 * 2. 页签行的「让位」不需要考虑滚动冲突——弹窗内容一屏就能放完，
 *    外层 Column 自己滚，拖动时不让列表跟着滚是更安全的选择
 */
@Stable
private class TabDragState(
    private var onDrop: (from: Int, to: Int) -> Unit,
    /** 当前行数，拖动下标要按它夹紧 */
    var count: Int = 0,
) {
    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    var dragOffset by mutableFloatStateOf(0f)
        private set

    var targetIndex by mutableStateOf<Int?>(null)
        private set

    private var itemHeights = mutableListOf<Float>()
    private var itemTops: List<Float> = emptyList()

    fun update(onDrop: (from: Int, to: Int) -> Unit, count: Int) {
        this.onDrop = onDrop
        this.count = count
    }

    fun resetMeasurements() {
        itemHeights = mutableListOf()
        itemTops = emptyList()
    }

    fun recordItem(index: Int, height: Float) {
        while (itemHeights.size <= index) itemHeights.add(0f)
        itemHeights[index] = height
        var acc = 0f
        itemTops = itemHeights.map { h -> acc.also { acc += h } }
    }

    fun onDragStart(index: Int) {
        draggingIndex = index
        dragOffset = 0f
        targetIndex = index
    }

    fun onDrag(delta: Float) {
        dragOffset += delta
        val from = draggingIndex ?: return
        if (itemTops.isEmpty()) return
        // 被拖行的中心落在哪个槽就算哪个位置：用顶边要求用户拖过整整一行才认，
        // 中心更接近「这一行现在盖住了谁」
        val center = itemTops.getOrElse(from) { 0f } +
            itemHeights.getOrElse(from) { 0f } / 2f + dragOffset
        targetIndex = slotAt(center, from)
    }

    private fun slotAt(y: Float, fallback: Int): Int {
        if (itemTops.isEmpty()) return fallback
        itemTops.forEachIndexed { index, top ->
            val bottom = top + itemHeights.getOrElse(index) { 0f }
            if (y in top..bottom) return index
        }
        return if (y < 0f) 0 else itemHeights.lastIndex.coerceAtLeast(fallback)
    }

    fun offsetFor(index: Int): Float {
        val from = draggingIndex ?: return 0f
        val to = targetIndex ?: return 0f
        if (index == from) return dragOffset
        val h = itemHeights.getOrElse(index) { 0f }
        return when {
            from < to && index in (from + 1)..to -> -h
            to < from && index in to until from -> h
            else -> 0f
        }
    }

    fun onDragEnd() {
        val from = draggingIndex
        val to = targetIndex
        draggingIndex = null
        targetIndex = null
        dragOffset = 0f
        // 位置没变就不落库：一次误触不该产生一次写操作
        if (from != null && to != null && from != to) onDrop(from, to)
    }

    fun onDragCancel() {
        draggingIndex = null
        targetIndex = null
        dragOffset = 0f
    }

    fun isDragging(index: Int): Boolean = draggingIndex == index
}

@Composable
private fun rememberTabDrag(
    count: Int,
    onDrop: (from: Int, to: Int) -> Unit,
): TabDragState {
    val state = remember { TabDragState(onDrop, count) }
    // 回调闭包每帧都是新的，但状态对象是 remember 住的；不更新的话松手时
    // 调用的还是第一次组合的那个 lambda，它捕获的 tabs 可能已经过期
    state.update(onDrop, count)
    // 行数变了（删了一个页签）就丢掉旧的实测高度，否则槽位会算到不存在的位置
    LaunchedEffect(count) { state.resetMeasurements() }
    return state
}

/**
 * 给一行接上长按拖动。
 *
 * 用 `detectDragGesturesAfterLongPress` 而不是 `draggable`：需要在长按之后才跟手，
 * 长按之前的手势要留给行里的开关与按钮。
 */
private fun Modifier.tabDragHandle(
    index: Int,
    count: Int,
    drag: TabDragState,
): Modifier = this
    .zIndex(if (drag.isDragging(index)) 1f else 0f)
    .graphicsLayer { translationY = drag.offsetFor(index) }
    .onGloballyPositioned { coords ->
        drag.recordItem(index, coords.size.height.toFloat())
    }
    .pointerInput(index, count) {
        detectDragGesturesAfterLongPress(
            onDragStart = { drag.onDragStart(index) },
            onDrag = { change, amount ->
                change.consume()
                drag.onDrag(amount.y)
            },
            onDragEnd = { drag.onDragEnd() },
            onDragCancel = { drag.onDragCancel() },
        )
    }
