package com.lifeboard.shared.repository.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow

/**
 * `Query<T>` → `Flow<List<T>>`。
 *
 * 为什么自己包一层而不是各处直接写 `query.toFlow().mapToList(ctx)`：
 * 那个 `CoroutineContext` 参数是必须给的，而两端该用的 dispatcher 不同——
 * 传错会把 SQLite 的读操作放到主线程上，表现为列表滚动掉帧，
 * 且在桌面端完全看不出来（桌面没有主线程限制）。
 *
 * 统一用 [Dispatchers.IO]：读库归 IO，与 Android 的既有习惯一致。
 * 这里不暴露 `scope` 参数是因为 Flow 是冷的——收集者自己的作用域决定生命周期，
 * 提前绑 scope 反而会在 ViewModel 被销毁后继续持有查询。
 */
fun <T : Any> Query<T>.asListFlow(): Flow<List<T>> =
    asFlow().mapToList(Dispatchers.IO)

