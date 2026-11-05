package com.lifeboard.app.ui.mihealth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.server.mihealth.MiHealthOverview
import android.graphics.BitmapFactory

/**
 * 小米账号登录页。
 *
 * 无 root 方案的核心入口：登录一次拿到 serviceToken + passToken，
 * 之后靠 passToken 自动续期，日常不再需要输入密码。
 *
 * 三个阶段由 [MiHealthLoginViewModel] 驱动：
 * 账号密码 → （可能）图片验证码 → （可能）短信/邮箱验证码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiHealthLoginScreen(
    onDone: () -> Unit,
    vm: MiHealthLoginViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val overview by vm.overview.collectAsStateWithLifecycle()
    val overviewLoading by vm.overviewLoading.collectAsStateWithLifecycle()
    val diag by vm.diag.collectAsStateWithLifecycle()

    // 页面是压在主界面上的第二层，返回键回主界面而不是退到桌面
    BackHandler { onDone() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("小米账号") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (state.stage) {
                LoginStage.DONE -> {
                    LoggedInCard(
                        account = state.account,
                        onLogout = vm::logout,
                        onBack = onDone,
                    )
                    OverviewCard(
                        overview = overview,
                        loading = overviewLoading,
                        onRefresh = vm::refreshOverview,
                    )
                    if (diag.isNotBlank()) {
                        Text(
                            diag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = vm::runDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        Text("诊断接口")
                    }
                }
                LoginStage.CREDENTIALS -> CredentialsForm(vm, state)
                LoginStage.CAPTCHA -> CaptchaForm(vm, state)
                LoginStage.TWO_FACTOR -> TwoFactorForm(vm, state)
                LoginStage.IMPORT -> ImportForm(vm, state)
            }

            if (state.error.isNotBlank()) {
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                "说明：账号密码只用于换一次登录凭据，不会保存在本机明文里。" +
                    "登录成功后凭据由系统密钥库加密保存，后续自动续期，无需重复输入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CredentialsForm(vm: MiHealthLoginViewModel, state: MiHealthLoginState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("登录小米账号", style = MaterialTheme.typography.titleSmall)
            Text(
                "用于读取步数与睡眠数据。与「小米运动健康」App 用的是同一个账号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.account,
                onValueChange = vm::onAccountChange,
                label = { Text("小米账号 / 手机号 / 邮箱") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = vm::onPasswordChange,
                label = { Text("密码") },
                singleLine = true,
                enabled = !state.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = vm::submitCredentials,
                enabled = state.canSubmitCredentials,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) BusySpinner() else Text("登录")
            }

            // 小米对密码登录有风控，短信验证码还有每日上限（70022）。
            // 已经有可用凭据时不该被卡死在登录页，留一条导入通道。
            TextButton(
                onClick = vm::openImport,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("已有凭据？直接导入")
            }
        }
    }
}

@Composable
private fun ImportForm(vm: MiHealthLoginViewModel, state: MiHealthLoginState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("导入凭据", style = MaterialTheme.typography.titleSmall)
            Text(
                "粘贴包含 ssecurity、service_token、cuser_id 的 JSON，" +
                    "或按行依次填 ssecurity / serviceToken / cUserId / passToken。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 凭据里没有任何身份信息（cUserId 是随机串），
            // 想让界面显示「登的是谁」只能自己填一个
            OutlinedTextField(
                value = state.importName,
                onValueChange = vm::onImportNameChange,
                label = { Text("显示名称（可选，如手机号）") },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.importText,
                onValueChange = vm::onImportTextChange,
                label = { Text("凭据内容") },
                enabled = !state.busy,
                minLines = 5,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::submitImport,
                    enabled = state.importText.isNotBlank() && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.busy) BusySpinner() else Text("导入")
                }
                OutlinedButton(
                    onClick = vm::cancelImport,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("返回")
                }
            }
        }
    }
}

@Composable
private fun CaptchaForm(vm: MiHealthLoginViewModel, state: MiHealthLoginState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("需要验证码", style = MaterialTheme.typography.titleSmall)
            Text(
                "小米要求输入图片验证码，请照下图填写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CaptchaImage(state.captchaUrl)

            OutlinedTextField(
                value = state.captchaInput,
                onValueChange = vm::onCaptchaChange,
                label = { Text("验证码") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::submitCaptcha,
                    enabled = state.captchaInput.isNotBlank() && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.busy) BusySpinner() else Text("提交")
                }
                OutlinedButton(
                    onClick = vm::refreshCaptcha,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("换一张")
                }
            }
        }
    }
}

@Composable
private fun TwoFactorForm(vm: MiHealthLoginViewModel, state: MiHealthLoginState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("二次验证", style = MaterialTheme.typography.titleSmall)
            Text(
                "验证码已发送到 ${state.twoFactorHint}，请填入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.twoFactorInput,
                onValueChange = vm::onTwoFactorChange,
                label = { Text("验证码") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = vm::submitTwoFactor,
                enabled = state.twoFactorInput.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) BusySpinner() else Text("提交")
            }

            Text(
                "没收到？部分账号需要先在官方 App 里完成一次安全验证。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoggedInCard(account: String, onLogout: () -> Unit, onBack: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已登录", style = MaterialTheme.typography.titleSmall)
                if (account.isNotBlank()) {
                    Text(
                        "  ·  $account",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                if (account.isNotBlank()) {
                    "$account 的步数与睡眠数据会在每日聚合时自动同步。" +
                        "凭据会自动续期，一般不需要重新登录。"
                } else {
                    "步数与睡眠数据会在每日聚合时自动同步。" +
                        "凭据会自动续期，一般不需要重新登录。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("完成")
                }
                TextButton(onClick = onLogout, modifier = Modifier.weight(1f)) {
                    Text("退出登录", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 已登录时的数据概览。
 *
 * 登录只是手段，用户真正关心的是「能不能看到数据」。所以这里把
 * 步数、卡路里、心率、睡眠等直接摆出来，而不是只显示一句「已登录」。
 */
@Composable
private fun OverviewCard(
    overview: MiHealthOverview?,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("今日数据", style = MaterialTheme.typography.titleSmall)
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            val data = overview
            if (data == null) {
                Text(
                    if (loading) "正在读取…" else "还没有读取数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (data.error.isNotBlank()) {
                Text(
                    data.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                // 分组展示，避免几十行平铺看不出结构
                OverviewGroup("今日活动")
                OverviewRow("步数", "${data.stepsToday} 步")
                if (data.distanceToday > 0) OverviewRow("距离", formatDistance(data.distanceToday))
                OverviewRow("卡路里", "%.0f 千卡".format(data.caloriesToday))
                if (data.standHoursToday > 0) OverviewRow("站立", "${data.standHoursToday} 小时")
                if (data.intensityMinutes > 0) {
                    OverviewRow("中高强度", "${data.intensityMinutes} 分钟")
                }

                data.goal?.let { g ->
                    OverviewGroup("今日目标")
                    if (g.stepsTarget > 0) {
                        OverviewRow("步数", "${g.stepsAchieved} / ${g.stepsTarget}（${g.stepsPercent}%）")
                    }
                    if (g.caloriesTarget > 0) {
                        OverviewRow("热量", "${g.caloriesAchieved} / ${g.caloriesTarget} 千卡（${g.caloriesPercent}%）")
                    }
                    if (g.activeTarget > 0) {
                        OverviewRow("活动", "${g.activeAchieved} / ${g.activeTarget} 分（${g.activePercent}%）")
                    }
                    if (g.standTarget > 0) {
                        OverviewRow("站立", "${g.standAchieved} / ${g.standTarget} 小时（${g.standPercent}%）")
                    }
                }

                OverviewGroup("心率")
                OverviewRow("最近心率", data.heartRateLast?.let { "$it 次/分" } ?: "无数据")
                data.heartRateMin24h?.let { OverviewRow("24 小时最低", "$it 次/分") }
                data.heartRateMax24h?.let { OverviewRow("24 小时最高", "$it 次/分") }
                data.heartRateAvg24h?.let { OverviewRow("24 小时平均", "$it 次/分") }
                if (data.heartRateSamples > 0) {
                    OverviewRow("采样点", "${data.heartRateSamples} 条")
                }

                data.sleepLast?.let { s ->
                    OverviewGroup("睡眠")
                    OverviewRow("最近一次", formatDuration(s.minutes))
                    OverviewRow("入睡", formatClock(s.startAt))
                    OverviewRow("起床", formatClock(s.endAt))
                    if (s.deepMinutes + s.lightMinutes + s.remMinutes > 0) {
                        OverviewRow(
                            "分期",
                            "深 ${s.deepMinutes} / 浅 ${s.lightMinutes} / REM ${s.remMinutes} 分",
                        )
                    }
                    if (s.awakeMinutes > 0) {
                        OverviewRow("清醒", "${s.awakeMinutes} 分（${s.awakeCount} 次）")
                    }
                    if (s.avgHeartRate > 0) {
                        OverviewRow(
                            "睡眠心率",
                            if (s.minHeartRate > 0 && s.maxHeartRate > 0) {
                                "均 ${s.avgHeartRate} / ${s.minHeartRate}~${s.maxHeartRate}"
                            } else {
                                "${s.avgHeartRate} 次/分"
                            },
                        )
                    }
                    if (s.avgBreath > 0) OverviewRow("呼吸频率", "${s.avgBreath} 次/分")
                    if (s.avgSpo2 > 0) OverviewRow("睡眠平均血氧", "${s.avgSpo2}%")
                    if (data.sleepRecent.size > 1) {
                        OverviewRow("近 ${data.sleepRecent.size} 天平均", formatDuration(data.sleepAvgMinutes))
                    }
                }

                if (data.sports.isNotEmpty()) {
                    OverviewGroup("运动记录（近 30 天 ${data.sports.size} 次 / ${data.sportDays30} 天）")
                    data.sports.take(5).forEach { r ->
                        OverviewRow(
                            "${sportName(r.key)} · ${formatDay(r.startAt * 1000)}",
                            buildString {
                                append(formatDuration(r.durationSeconds / 60))
                                if (r.distanceMeters > 0) append(" · ${formatDistance(r.distanceMeters)}")
                                if (r.calories > 0) append(" · ${r.calories} 千卡")
                                if (r.avgHeartRate > 0) append(" · 均心率 ${r.avgHeartRate}")
                            },
                        )
                    }
                }

                val hasBody = data.spo2Last != null || data.stressLast != null ||
                    data.weightLast != null || data.vo2MaxLast != null ||
                    data.bloodPressureLast != null || data.bloodSugarLast != null ||
                    data.paiLast != null || data.energyLast != null ||
                    data.menstruationLast != null
                if (hasBody) {
                    OverviewGroup("身体指标")
                    data.spo2Last?.let { OverviewRow("血氧", "$it%") }
                    data.stressLast?.let { OverviewRow("压力", "$it") }
                    data.weightLast?.let { OverviewRow("体重", "%.1f kg".format(it)) }
                    data.vo2MaxLast?.let { OverviewRow("最大摄氧量", "%.0f".format(it)) }
                    data.bloodPressureLast?.let { OverviewRow("血压", "$it mmHg") }
                    data.bloodSugarLast?.let { OverviewRow("血糖", "%.1f".format(it)) }
                    data.paiLast?.let { OverviewRow("PAI", "%.0f".format(it)) }
                    data.energyLast?.let { OverviewRow("能量", "%.0f".format(it)) }
                    data.menstruationLast?.let { OverviewRow("经期", it) }
                }

                if (data.headsetTotalMinutes > 0) {
                    OverviewGroup("环境")
                    OverviewRow("耳机使用", formatDuration(data.headsetTotalMinutes))
                    OverviewRow("高音量时长", formatDuration(data.headsetHighMinutes))
                }

                if (data.dietCountToday > 0) {
                    OverviewGroup("饮食")
                    OverviewRow("今日记录", "${data.dietCountToday} 餐")
                    OverviewRow("摄入", "${data.dietCaloriesToday} 千卡")
                }

                // 「我要看全部数据」：把各族实际拿到的条数摊开，
                // 空的就是这台设备没采集，不用猜接口是不是坏了
                if (data.counts.isNotEmpty()) {
                    OverviewGroup("数据种类明细")
                    data.counts.entries
                        .sortedWith(compareBy({ it.key.substringBefore(':') }, { it.key }))
                        .forEach { (k, n) ->
                            OverviewRow(keyLabel(k), if (n > 0) "$n 条" else "无数据")
                        }
                    if (data.sources.isNotEmpty()) {
                        OverviewRow("数据来源", data.sources.joinToString("、"))
                    }
                }

                if (!data.hasAny) {
                    Text(
                        "接口已连通但今天还没有数据。小米健康的数据来自手环/手表同步，" +
                            "如果设备没戴或没同步，这里就会是空的。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text("刷新数据")
            }
        }
    }
}

/** 分组标题：把十几行数据切成几块，扫读时不用逐行看 */
@Composable
private fun OverviewGroup(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun OverviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** 分钟数转「x 小时 y 分」 */
private fun formatDuration(minutes: Int): String {
    if (minutes <= 0) return "0 分钟"
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "$h 小时 $m 分" else "$m 分钟"
}

/** 距离：过千米换单位，否则看 812 米这种数字没概念 */
private fun formatDistance(meters: Int): String =
    if (meters >= 1000) "%.2f 公里".format(meters / 1000.0) else "$meters 米"

/** 时间戳（秒）转「14:35」 */
private fun formatClock(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))

/** 时间戳（毫秒）转「MM-dd」 */
private fun formatDay(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))

/** 运动 key 转中文：接口给的是 `indoor_running` 这种内部名 */
private fun sportName(key: String): String = when (key) {
    "indoor_running" -> "室内跑步"
    "outdoor_running" -> "户外跑步"
    "outdoor_walking" -> "户外步行"
    "indoor_walking" -> "室内步行"
    "outdoor_cycling" -> "户外骑行"
    "indoor_cycling" -> "室内骑行"
    "pool_swimming" -> "泳池游泳"
    "open_water_swimming" -> "公开水域游泳"
    "jump_rope" -> "跳绳"
    "elliptical" -> "椭圆机"
    "rowing_machine" -> "划船机"
    "strength_training" -> "力量训练"
    "yoga" -> "瑜伽"
    "hiking" -> "徒步"
    "climbing" -> "登山"
    else -> key.replace('_', ' ').ifBlank { "运动" }
}

/** `fitness:steps` 这类键转人能读的标签 */
private fun keyLabel(key: String): String {
    val parts = key.split(':', limit = 2)
    val family = parts[0]
    val name = parts.getOrElse(1) { family }
    val familyName = when (family) {
        "fitness" -> "健康"
        "aggregated" -> "聚合"
        "sport" -> "运动"
        "diet" -> "饮食"
        "medical" -> "医疗"
        "project" -> "项目"
        else -> family
    }
    val nameCn = when (name) {
        "steps" -> "步数"
        "calories" -> "卡路里"
        "heart_rate" -> "心率"
        "sleep" -> "睡眠"
        "spo2" -> "血氧"
        "stress" -> "压力"
        "weight" -> "体重"
        "blood_pressure" -> "血压"
        "blood_sugar" -> "血糖"
        "pai" -> "PAI 活力"
        "energy" -> "能量"
        "intensity" -> "中高强度"
        "valid_stand" -> "站立"
        "vo2_max" -> "最大摄氧量"
        "menstruation" -> "经期"
        "headset" -> "耳机噪声"
        "goal" -> "三环目标"
        "records" -> "记录"
        "ecg" -> "心电图"
        "sleep_wake_circle" -> "睡眠节律"
        else -> name
    }
    return "$familyName·$nameCn"
}

/**
 * 验证码图片。
 *
 * 传入的是 `data:image/jpeg;base64,...`，不是远程 URL——小米把验证码绑在会话
 * cookie 上，服务端已经用登录会话把它下载并转成了 base64。若这里再拿 URL 去下，
 * 会得到另一张图，填了必然报「验证码输入错误」。
 */
@Composable
private fun CaptchaImage(dataUri: String) {
    val bitmap = remember(dataUri) {
        runCatching {
            val comma = dataUri.indexOf(',')
            if (!dataUri.startsWith("data:") || comma < 0) return@runCatching null
            val bytes = android.util.Base64.decode(
                dataUri.substring(comma + 1),
                android.util.Base64.DEFAULT,
            )
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "验证码",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 180.dp, height = 60.dp),
            )
        } else {
            Text(
                "验证码加载中…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BusySpinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.onPrimary,
    )
}
