package com.lifeboard.shared.ui.plugin.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeboard.plugin.api.AudioNode
import com.lifeboard.plugin.api.ButtonNode
import com.lifeboard.plugin.api.ChartNode
import com.lifeboard.plugin.api.CodeNode
import com.lifeboard.plugin.api.CollapsibleNode
import com.lifeboard.plugin.api.ColumnNode
import com.lifeboard.plugin.api.DividerNode
import com.lifeboard.plugin.api.ImageNode
import com.lifeboard.plugin.api.InputNode
import com.lifeboard.plugin.api.KeyValueNode
import com.lifeboard.plugin.api.ListNode
import com.lifeboard.plugin.api.PluginNode
import com.lifeboard.plugin.api.ProgressNode
import com.lifeboard.plugin.api.RowNode
import com.lifeboard.plugin.api.SelectNode
import com.lifeboard.plugin.api.SpacerNode
import com.lifeboard.plugin.api.StatusCardNode
import com.lifeboard.plugin.api.TableNode
import com.lifeboard.plugin.api.TextNode
import com.lifeboard.plugin.api.ToggleNode
import com.lifeboard.plugin.api.UnsupportedNode
import com.lifeboard.plugin.api.VideoNode
import com.lifeboard.shared.panel.CardDragState
import com.lifeboard.shared.panel.dragHandle

/**
 * 渲染一棵插件节点树。
 *
 * 所有颜色、字体、圆角都在宿主侧决定，插件只说语义（`Tone` / `Spacing` /
 * `Emphasis`）。这是「详情页看起来像同一个应用」的关键：插件拿到的是宿主的
 * 设计语言，而不是各自往宿主里塞的小程序。
 *
 * @param root 已求值（绑定全部解析完）的节点树
 * @param busy 有动作正在执行，此时禁用全部交互，防止连点并发调插件
 * @param onAction 交互回调；`sessionKey` 非空表示该输入应投给会话而非 `onAction`
 * @param orderMode 区块顺序策略；只有 CUSTOM 下才允许拖动
 * @param drag 拖动状态；为 null 表示当前页面不支持拖动
 */
@Composable
fun PluginNodeTree(
    root: PluginNode?,
    busy: Boolean,
    onAction: (actionId: String, value: String?) -> Unit,
    onSessionSubmit: (sessionKey: String, text: String) -> Unit = { _, _ -> },
    orderMode: BlockOrderMode = BlockOrderMode.DEFAULT,
    drag: CardDragState? = null,
) {
    if (root == null) return
    val context = RenderContext(busy = busy, onAction = onAction, onSessionSubmit = onSessionSubmit)

    // 只有根是 column 时才逐个子节点渲染 + 支持拖动。
    // 根是别的东西（单个文本、一个列表）时整体画一次即可。
    if (root is ColumnNode && drag != null && orderMode == BlockOrderMode.CUSTOM) {
        Column(verticalArrangement = spacingOf(root.spacing)) {
            root.children.forEachIndexed { index, child ->
                Column(
                    modifier = if (isReorderableNode(child)) {
                        Modifier.dragHandle(true, index, root.children.size, drag)
                    } else {
                        Modifier
                    },
                ) {
                    RenderNode(child, context)
                }
            }
        }
    } else {
        RenderNode(root, context)
    }
}

/**
 * 渲染上下文。
 *
 * 用参数对象而不是逐个传：递归时每一层都要往下传，参数一多必然出现
 * 「某层漏传了 busy」，而漏传的表现是「嵌套在卡片里的按钮没有禁用」，
 * 极难联想到是递归深度的问题。
 */
internal class RenderContext(
    val busy: Boolean,
    val onAction: (actionId: String, value: String?) -> Unit,
    val onSessionSubmit: (sessionKey: String, text: String) -> Unit,
)

/**
 * 递归渲染单个节点。
 *
 * 每次递归都新建一个 context 副本只改需要的字段（目前没有需要改的），
 * 而不是共享可变状态——Compose 的重组按读取点追踪，共享可变上下文会让
 * 「某个子树的 busy 变了」触发整棵树重组。
 */
@Composable
internal fun RenderNode(node: PluginNode, context: RenderContext) {
    when (node) {
        // 容器
        is ColumnNode -> RenderColumn(node, context)
        is RowNode -> RenderRow(node, context)
        is CollapsibleNode -> RenderCollapsible(node, context)
        is SpacerNode -> RenderSpacer(node)
        is DividerNode -> RenderDivider()

        // 内容
        is TextNode -> RenderText(node)
        is StatusCardNode -> RenderStatusCard(node)
        is ChartNode -> RenderChart(node)
        is ImageNode -> RenderImage(node)
        is ProgressNode -> RenderProgress(node)
        is CodeNode -> RenderCode(node)
        is TableNode -> RenderTable(node)
        is KeyValueNode -> RenderKeyValue(node)
        is UnsupportedNode -> RenderUnsupported(node)

        // 集合
        is ListNode -> RenderList(node, context)

        // 交互
        is ButtonNode -> RenderButton(node, context)
        is InputNode -> RenderInput(node, context)
        is SelectNode -> RenderSelect(node, context)
        is ToggleNode -> RenderToggle(node, context)

        // 媒体（本期只降级）
        is AudioNode -> RenderMediaUnsupported("音频")
        is VideoNode -> RenderMediaUnsupported("视频")
    }
}

/** 间距档位 → 具体 dp。插件不能写像素，映射集中在这里 */
internal fun spacingOf(spacing: com.lifeboard.plugin.api.Spacing) = when (spacing) {
    com.lifeboard.plugin.api.Spacing.NONE -> Arrangement.spacedBy(0.dp)
    com.lifeboard.plugin.api.Spacing.COMPACT -> Arrangement.spacedBy(6.dp)
    com.lifeboard.plugin.api.Spacing.NORMAL -> Arrangement.spacedBy(12.dp)
    com.lifeboard.plugin.api.Spacing.LOOSE -> Arrangement.spacedBy(20.dp)
}

/** 间距档位 → 单个 dp 值（用于 Spacer） */
internal fun spacingDp(spacing: com.lifeboard.plugin.api.Spacing) = when (spacing) {
    com.lifeboard.plugin.api.Spacing.NONE -> 0.dp
    com.lifeboard.plugin.api.Spacing.COMPACT -> 6.dp
    com.lifeboard.plugin.api.Spacing.NORMAL -> 12.dp
    com.lifeboard.plugin.api.Spacing.LOOSE -> 20.dp
}

/** 语义色调 → 主题色。插件只说语义，颜色由宿主决定 */
@Composable
internal fun toneColor(tone: com.lifeboard.plugin.api.Tone) = when (tone) {
    com.lifeboard.plugin.api.Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    com.lifeboard.plugin.api.Tone.OK -> MaterialTheme.colorScheme.primary
    com.lifeboard.plugin.api.Tone.WARN -> MaterialTheme.colorScheme.tertiary
    com.lifeboard.plugin.api.Tone.ERROR -> MaterialTheme.colorScheme.error
}

/** 未支持内容的统一占位：让用户知道「这里有东西，只是这个版本画不出来」 */
@Composable
internal fun RenderMediaUnsupported(kind: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RenderUnsupportedText("此版本暂不支持$kind")
    }
}

@Composable
internal fun RenderUnsupportedText(text: String) {
    com.lifeboard.shared.ui.theme.GlassCard(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.material3.Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
