// 小米运动健康 JS 插件 —— 契约实现
//
// 这是整个插件唯一的入口。宿主通过 callFunction 调用本模块的导出函数：
//   login(inputs) / logout() / authState() / fetch(date) / health() / detailUi() / onAction(actionId)
//
// ## 三条必须守住的线
//
// 1. **任何异常都不许抛给宿主**。宿主对抛出的异常只有一层通用兜底，
//    用户看到的是「插件未实现 login」这类误导信息。每个导出函数都要自己
//    try/catch 并翻译成契约允许的表达（failure / broken / 空数组）。
// 2. **reportMetricQueried 必须在任何 early return 之前**。它决定详情页
//    「查了但没数据」的指标能不能列出来，放晚了就会整批消失。
// 3. **中间态用模块级变量**。QuickJS 单线程，login 天然串行，
//    不需要 Kotlin 版的 Mutex；但也因此绝不能把中间态挂到 ctx 上
//    （它是每次调用传入的，跨轮次不保证同一个实例）。
//
// ## 与 Kotlin 版的结构对应
//
//   MiHealthPlugin.kt   → 本文件
//   MiHealthAuth.kt     → 本文件的登录轮次调度
//   MiHealthClient.kt   → client.ts
//   MiHealthCrypto.kt   → crypto.ts
//   MiHealthParser.kt   → 本文件的 payloadOf / numberAt / timestampAt

/// <reference path="../types/index.d.ts" />

// 具名类型从契约声明文件导入（它是模块）；裸 `ctx` 与其余类型由
// `../types/index.d.ts` 的全局声明提供，见该文件的说明。
import type {
    ActionResult,
    AuthState,
    FetchResult,
    LoginResult,
    PluginHealth,
    PluginMetric,
    Tone,
    UiNode,
} from '../types/plugin-contract.js';

import {
    ACCOUNT,
    IDENTITY,
    SID,
    decryptBody,
    identityUa,
    parseMiapi,
    signQuery,
    ua,
} from './crypto.js';
import type { CookieSnapshot, MiSession } from './client.js';
import {
    createSession,
    describeError,
    fetchCaptcha,
    fetchServiceToken,
    serviceLogin,
    serviceLoginAuth2,
    serviceLoginRaw,
    shortUrl,
} from './client.js';
import { absoluteUrl, asLong, asNumber, b64decode } from './util.js';

// ---------- 常量 ----------

const SLOT_ACCOUNT = 'account';
const SLOT_PASSWORD = 'password';
const SLOT_SSECURITY = 'ssecurity';
const SLOT_SERVICE_TOKEN = 'service_token';
const SLOT_CUSER_ID = 'cuser_id';
const SLOT_PASS_TOKEN = 'pass_token';
const SLOT_CAPTCHA = 'captcha';
const SLOT_OTP = 'otp';

const DAY_MILLIS = 24 * 60 * 60 * 1000;

/**
 * identity（二次验证）端点的单次请求超时。
 *
 * 宿主的单次插件调用上限是 60s，而 identity 端点偶发挂住（日志里有过
 * 等 37 秒才超时的情况）。默认 30s 再叠加重试直接顶到上限，用户最终看到的是
 * `login failed: undefined`——超时把整个登录轮次掐断，连错误码都没有。
 * 压到 12s 让失败能及时回到插件手里，由插件给出可读的失败原因。
 */
const IDENTITY_TIMEOUT_MS = 12000;

/** 详情页趋势图回看天数 */
const DAYS = 7;

/** 回补历史的天数，与 Kotlin 版 BACKFILL_DAYS 一致 */
const BACKFILL_DAYS = 30;

/** 详情页按钮标识 */
const ACTION_SYNC = 'sync_now';
const ACTION_BACKFILL = 'backfill';

/** 每日目标达成的聚合端点（与逐点查询的 by_time 不是同一个） */
const AGGREGATED_PATH = '/app/v1/data/get_aggregated_fitness_data_by_time';

/** 指标 key，与 manifest.json 声明保持一致 */
const STEPS_KEY = 'STEPS';
/**
 * 睡眠总时长（分钟）。口径是「**这一夜**睡了多久」：
 * 按醒来日期归属，昨晚 22:43 睡到今早 07:58 算今天这一条。
 * 早先按自然日裁剪会把一夜拆成两天、还会把同夜的两条记录各算一遍。
 */
const SLEEP_KEY = 'SLEEP_MINUTES';
const SLEEP_DEEP_RATIO_KEY = 'SLEEP_DEEP_RATIO';
const SLEEP_EFFICIENCY_KEY = 'SLEEP_EFFICIENCY';
const SLEEP_AWAKE_COUNT_KEY = 'SLEEP_AWAKE_COUNT';
const SLEEP_BEDTIME_KEY = 'SLEEP_BEDTIME';
const SLEEP_REGULARITY_KEY = 'SLEEP_REGULARITY';

const OBS_HEART_RATE = 'OBS_HEART_RATE';
const OBS_RESTING_HR = 'OBS_RESTING_HEART_RATE';
const OBS_SPO2 = 'OBS_SPO2';
const OBS_STRESS = 'OBS_STRESS';
const OBS_CALORIES = 'OBS_CALORIES';
const OBS_DISTANCE = 'OBS_DISTANCE';
const OBS_STAND = 'OBS_STAND';
const OBS_INTENSITY = 'OBS_INTENSITY';
const OBS_WEIGHT = 'OBS_WEIGHT';
const OBS_BMI = 'OBS_BMI';
const OBS_BODY_FAT = 'OBS_BODY_FAT_RATE';
const OBS_MUSCLE = 'OBS_MUSCLE_RATE';
const OBS_MOISTURE = 'OBS_MOISTURE_RATE';
const OBS_BONE_MASS = 'OBS_BONE_MASS';
const OBS_BASAL = 'OBS_BASAL_METABOLISM';
const OBS_PROTEIN = 'OBS_PROTEIN_RATE';
const OBS_VISCERAL_FAT = 'OBS_VISCERAL_FAT';
const OBS_TRAINING_LOAD = 'OBS_TRAINING_LOAD';
const OBS_TRAINING_LEVEL = 'OBS_TRAINING_LEVEL';
const OBS_VO2_MAX = 'OBS_VO2_MAX';
const OBS_PAI = 'OBS_PAI';
const OBS_BLOOD_SYSTOLIC = 'OBS_BLOOD_SYSTOLIC';
const OBS_BLOOD_DIASTOLIC = 'OBS_BLOOD_DIASTOLIC';
const OBS_BLOOD_SUGAR = 'OBS_BLOOD_SUGAR';

// 体重秤的其余官方字段（见小米开放平台「com.xiaomi.weight.instantaneous」）
const OBS_MUSCLE_MASS = 'OBS_MUSCLE_MASS';
const OBS_PROTEIN_MASS = 'OBS_PROTEIN_MASS';
const OBS_MOISTURE_MASS = 'OBS_BODY_MOISTURE_MASS';
const OBS_SKELETAL_MUSCLE = 'OBS_SKELETAL_MUSCLE_MASS';
const OBS_BODY_AGE = 'OBS_BODY_AGE';
const OBS_BODY_SCORE = 'OBS_BODY_SCORE';

// 压力等级（官方 com.xiaomi.stress.delta 的 grade 字段）
const OBS_STRESS_GRADE = 'OBS_STRESS_GRADE';
// 站立时长（官方 com.xiaomi.valid_stand.statistics 的 active_duration）
const OBS_STAND_DURATION = 'OBS_STAND_DURATION';
// 能量（CloudKey 全集里的 energy key）
const OBS_ENERGY = 'OBS_ENERGY';
// 耳机噪声（CloudKey 全集里的 headset key）
const OBS_HEADSET_NOISE = 'OBS_HEADSET_NOISE';
// 经期（CloudKey 全集里的 menstruation key）
const OBS_MENSTRUATION = 'OBS_MENSTRUATION';

// 每日目标达成（aggregated fitness · daily_fitness/goal 的 field 映射）
const GOAL_STEPS = 'GOAL_STEPS';
const GOAL_CALORIES = 'GOAL_CALORIES';
const GOAL_ACTIVE_MINUTES = 'GOAL_ACTIVE_MINUTES';
const GOAL_STAND_HOURS = 'GOAL_STAND_HOURS';

/** 采样类指标定义。 */
interface SampledSpec {
    /** 小米云端的数据键，同一 key 的多个指标共用一次请求 */
    cloudKey: string;
    metricKey: string;
    /** 候选数值字段；为空表示**事件型**，记录存在本身就按 1 计 */
    fields: string[];
    /**
     * 日值怎么折：
     * - true：取当天均值（心率、血氧、压力这类「典型水平」）
     * - false：取**时间上最新的一条**（静息心率、体重、身体成分、训练负荷、
     *   血压这类「一天一次结论」）。早先这里是累加，会把称重记录相加
     */
    mean: boolean;
    /**
     * 把每条记录折算成「分钟数」而不是计数。
     *
     * 官方站立统计口径是 `active_duration`（分钟），而明细里只有
     * `{start_time, end_time}` 区间，需要用区间长度换算出时长。
     */
    duration?: boolean;
}

/**
 * 采样类指标清单。
 *
 * `fields` 为空表示**事件型**：记录存在本身就是「发生过」，按 1 计。
 * 实测 `valid_stand` 只有 `{start_time,end_time}`、`intensity` 只有 `{time}`，
 * 硬找一个数值字段只会得到 null。
 *
 * 同一 cloudKey 允许多行（血压、体重组成）：一次请求就能同时供给多个指标，
 * 按指标各查一次等于翻倍请求。
 *
 * ## 为什么没有 `distance` / `calories`
 *
 * 实测 `distance` 作为独立 key 请求返回 **0 条**，`calories` 虽然能返回，
 * 但与 steps 采样点里的 `calories` 是**两套口径**（前者是活动热量，
 * 后者是同一段时间的步数热量）。两个都收会让同一个 metricKey 一天写两次、
 * 后写的覆盖前写的。统一取 steps 采样点里的那份，见 [collectSteps]。
 */
const SAMPLED: SampledSpec[] = [
    { cloudKey: 'heart_rate', metricKey: OBS_HEART_RATE, fields: ['bpm', 'heart_rate'], mean: true },
    // 静息心率是每天一条的「当日结论值」，用最新一条而不是均值
    { cloudKey: 'resting_heart_rate', metricKey: OBS_RESTING_HR, fields: ['bpm'], mean: false },
    { cloudKey: 'spo2', metricKey: OBS_SPO2, fields: ['spo2'], mean: true },
    { cloudKey: 'stress', metricKey: OBS_STRESS, fields: ['stress_value', 'stress'], mean: true },
    // 官方 com.xiaomi.stress.delta 的 grade：1 放松 / 2 轻度 / 3 中度 / 4 重度。
    // 明细里本来就有，按最新一条取，代表「当天最后测到的压力档位」
    { cloudKey: 'stress', metricKey: OBS_STRESS_GRADE, fields: ['grade'], mean: false },
    { cloudKey: 'valid_stand', metricKey: OBS_STAND, fields: [], mean: false },
    // 官方统计口径是「站立时长(分钟)」，用每小时区间长度换算出总分钟数
    { cloudKey: 'valid_stand', metricKey: OBS_STAND_DURATION, fields: [], mean: false, duration: true },
    { cloudKey: 'intensity', metricKey: OBS_INTENSITY, fields: [], mean: false },

    // 体重与身体成分：同一次称重记录里带全部字段
    { cloudKey: 'weight', metricKey: OBS_WEIGHT, fields: ['weight'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_BMI, fields: ['bmi'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_BODY_FAT, fields: ['body_fat_rate'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_MUSCLE, fields: ['muscle_rate'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_MOISTURE, fields: ['moisture_rate'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_BONE_MASS, fields: ['bone_mass'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_BASAL, fields: ['basal_metabolism'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_PROTEIN, fields: ['protein_rate'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_VISCERAL_FAT, fields: ['visceral_fat'], mean: false },
    // 官方 com.xiaomi.weight.instantaneous 里还有这 6 项（质量类 + 身体年龄/得分）
    { cloudKey: 'weight', metricKey: OBS_MUSCLE_MASS, fields: ['muscle_mass'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_PROTEIN_MASS, fields: ['protein_mass'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_MOISTURE_MASS, fields: ['body_moisture_mass'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_SKELETAL_MUSCLE, fields: ['skeletal_muscle_mass'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_BODY_AGE, fields: ['body_age'], mean: false },
    { cloudKey: 'weight', metricKey: OBS_BODY_SCORE, fields: ['body_score'], mean: false },

    // 训练负荷：wtl_sum 是当日负荷量，level 是 1~5 的档位
    { cloudKey: 'training_load', metricKey: OBS_TRAINING_LOAD, fields: ['wtl_sum'], mean: false },
    { cloudKey: 'training_load', metricKey: OBS_TRAINING_LEVEL, fields: ['current_train_load_level'], mean: false },

    { cloudKey: 'vo2_max', metricKey: OBS_VO2_MAX, fields: ['vo2_max'], mean: true },
    { cloudKey: 'pai', metricKey: OBS_PAI, fields: ['pai', 'value'], mean: true },
    { cloudKey: 'blood_pressure', metricKey: OBS_BLOOD_SYSTOLIC, fields: ['systolic'], mean: true },
    { cloudKey: 'blood_pressure', metricKey: OBS_BLOOD_DIASTOLIC, fields: ['diastolic'], mean: true },
    { cloudKey: 'blood_sugar', metricKey: OBS_BLOOD_SUGAR, fields: ['value', 'blood_sugar'], mean: true },

    // CloudKey 全集里剩下的三个数据源（小米官方 CloudKey.getCloudRequestKey 有对应项）
    { cloudKey: 'energy', metricKey: OBS_ENERGY, fields: ['energy'], mean: false },
    { cloudKey: 'headset', metricKey: OBS_HEADSET_NOISE, fields: ['noise', 'noise_value', 'db', 'volume'], mean: true },
    { cloudKey: 'menstruation', metricKey: OBS_MENSTRUATION, fields: [], mean: false },
];

const STEP_FIELDS = ['steps', 'step', 'count', 'value'];
const STEP_DISTANCE_FIELDS = ['distance'];
const STEP_CALORIE_FIELDS = ['calories'];
/**
 * 采样点的时间字段候选。
 *
 * 内层 JSON 里是 `time`（秒级），外层也有 `time`；
 * `start_time` 用于睡眠这类区间型数据。
 */
const POINT_TIME_FIELDS = ['time', 'timestamp', 'start_time', 'startTime'];
const SLEEP_START_FIELDS = ['bedtime', 'device_bedtime', 'start_time', 'startTime'];
const SLEEP_END_FIELDS = ['device_wake_up_time', 'wake_up_time', 'end_time', 'endTime'];

// ---------- 类型 ----------

/** 云端返回的采样点。字段名由服务端决定，只能运行时探测。 */
type CloudPoint = Record<string, unknown>;

/** 一组凭据。`ssecurity` 与 `serviceToken` 必须来自同一次会话。 */
interface Creds {
    ssecurity: string;
    serviceToken: string;
    cUserId: string;
    userId: string;
    passToken: string;
    account: string;
}

/** 登录流程的中间态。 */
interface PendingLogin {
    stage: 'password' | 'captcha' | 'twoFactor';
    session: MiSession;
    account: string;
    /**
     * 密码的**单次 MD5 大写**，不是明文。
     *
     * 提交验证码/二次验证时服务端要的就是这个值，所以恢复流程只需要它，
     * 不需要明文密码。存哈希已经降低了泄露面：它本来就会发给服务端，
     * 而明文密码不会。
     */
    passwordHash: string;
    handshake: any;
    userId: string;
    // 仅 twoFactor 阶段使用
    context?: string;
    flag?: number;
    method?: string;
    hint?: string;
    auth2Ssecurity?: string;
}

/** 带 401 自愈的采集客户端。 */
interface CollectClient {
    getFitnessDataByTime(
        cloudKey: string,
        startTimeMillis: number,
        endTimeMillis: number,
        maxPages?: number,
    ): Promise<CloudPoint[]>;
    /** 每日目标达成：走聚合端点 `get_aggregated_fitness_data_by_time` */
    getDailyGoals(startTimeMillis: number, endTimeMillis: number): Promise<any[]>;
    currentCreds(): Creds;
}

// ---------- 模块级状态 ----------

/**
 * 当前登录流程的中间态。
 *
 * 为什么不用 ctx 存：ctx 是每次调用传进来的，跨轮次不保证是同一个实例；
 * 而验证码与那次登录的 cookie 绑定，换会话取到的图必然对不上，
 * 所以必须把 session 整个留在内存里。
 */
let pendingLogin: PendingLogin | null = null;

// ---------- 登录中间态的持久化 ----------
//
// 宿主可能在两次登录调用之间卸载插件（按需加载的空闲回收）。
// 只在内存里留着 `pendingLogin` 的话，用户填完密码、页面停在验证码，
// 过几分钟回来就"凭空回到第一轮"——他既没取消也没失败，纯粹是被回收了。
//
// 因此把**可恢复的最小集合**写进加密凭据存储：
// - 阶段、账号、密码哈希（不是明文）、握手、
// - 会话 cookie（含值，等价于密码，所以必须进凭据存储而非普通文件）
// - 服务端给的 2FA 参数、过期时间
//
// 关键约束：正确性不能依赖 onUnload 钩子。宿主明确"尽力调用、不保证执行"，
// 所以这里在**阶段变化时**就落盘，而不是等卸载。

/** 存恢复快照的凭据槽位 */
const SLOT_PENDING_LOGIN = 'pending_login';
const SLOT_LOGIN_GENERATION = 'pending_login_generation';

/** 持久代数只用于跨运行时恢复；sessionEpoch 只负责当前运行时的在途取消。 */
function loginGeneration(): string {
    const saved = ctx.credentials.get(SLOT_LOGIN_GENERATION);
    if (saved) return saved;
    const generation = Date.now().toString(36) + '-' + ctx.codec.base64Encode(ctx.crypto.randomBytes(16));
    ctx.credentials.put(SLOT_LOGIN_GENERATION, generation);
    return generation;
}

function invalidateLogin(): void {
    sessionEpoch++;
    resetPendingLogin();
    ctx.credentials.remove(SLOT_LOGIN_GENERATION);
}

function loginCancelled(epoch: number): boolean {
    return epoch !== sessionEpoch;
}

function cancelledLogin(): LoginResult {
    return { type: 'failure', message: '登录已取消' };
}

/**
 * 快照有效期：10 分钟。
 *
 * 比服务端的验证码/二次验证有效期更保守——过期后继续用旧会话只会拿到
 * 一个语焉不详的失败，不如直接重新走登录。
 */
const PENDING_LOGIN_TTL_MS = 10 * 60 * 1000;

/** 可恢复的登录快照 */
interface PendingLoginSnapshot {
    v: 1 | 2;
    stage: 'captcha' | 'twoFactor';
    account: string;
    /** 密码的单次 MD5 大写；不含明文 */
    passwordHash: string;
    handshake: any;
    userId: string;
    context?: string;
    flag?: number;
    method?: string;
    hint?: string;
    auth2Ssecurity?: string;
    cookies: CookieSnapshot[];
    expiresAt: number;
    /** v1 的进程内代数仅作兼容读取，不能与重建后归零的 sessionEpoch 比较。 */
    epoch?: number;
    /** v2 的跨运行时稳定身份，保存在独立加密槽位。 */
    generation?: string;
}

/** 把当前中间态落盘；密码阶段没有恢复价值，直接跳过 */
function savePendingLoginSnapshot(): void {
    const pending = pendingLogin;
    if (!pending || pending.stage === 'password') return;
    const snapshot: PendingLoginSnapshot = {
        v: 2,
        stage: pending.stage,
        account: pending.account,
        passwordHash: pending.passwordHash,
        handshake: pending.handshake,
        userId: pending.userId,
        context: pending.context,
        flag: pending.flag,
        method: pending.method,
        hint: pending.hint,
        auth2Ssecurity: pending.auth2Ssecurity,
        cookies: pending.session.exportCookies(),
        expiresAt: Date.now() + PENDING_LOGIN_TTL_MS,
        generation: loginGeneration(),
    };
    try {
        ctx.credentials.put(SLOT_PENDING_LOGIN, JSON.stringify(snapshot));
        log('i', '已保存登录中间态，阶段=' + snapshot.stage);
    } catch (e) {
        // 存不下不影响本轮登录，但要留痕：否则用户会困惑"为什么又回到第一轮"
        log('w', '保存登录中间态失败：' + describeError(e));
    }
}

function clearPendingLoginSnapshot(): void {
    try {
        ctx.credentials.remove(SLOT_PENDING_LOGIN);
    } catch (e) {
        log('w', '清除登录中间态失败：' + describeError(e));
    }
}

/**
 * 结束当前登录轮次：内存与持久化**一起**清掉。
 *
 * 只清内存会让"用户已重开"的旧快照在下一次启动时复活；
 * 只清快照则内存里还留着上一轮的 session，两边都不完整。
 */
function resetPendingLogin(): void {
    pendingLogin = null;
    clearPendingLoginSnapshot();
}

/**
 * 尝试从凭据存储恢复中间态。
 *
 * @return 是否恢复出了可继续的流程
 */
function restorePendingLogin(): boolean {
    if (pendingLogin) return true;
    let raw: string | null = null;
    try {
        raw = ctx.credentials.get(SLOT_PENDING_LOGIN);
    } catch (e) {
        log('w', '读取登录中间态失败：' + describeError(e));
        return false;
    }
    if (!raw) return false;

    let snapshot: PendingLoginSnapshot;
    try {
        snapshot = JSON.parse(raw);
    } catch (e) {
        log('w', '登录中间态损坏，已丢弃：' + describeError(e));
        clearPendingLoginSnapshot();
        return false;
    }
    if (!snapshot || (snapshot.v !== 1 && snapshot.v !== 2) ||
        !Number.isFinite(snapshot.expiresAt) ||
        (snapshot.stage !== 'captcha' && snapshot.stage !== 'twoFactor') ||
        typeof snapshot.account !== 'string' || !/^[A-F0-9]{32}$/.test(snapshot.passwordHash) ||
        !Array.isArray(snapshot.cookies)) {
        clearPendingLoginSnapshot();
        return false;
    }
    if (Date.now() > snapshot.expiresAt) {
        log('i', '登录中间态已过期，丢弃');
        clearPendingLoginSnapshot();
        return false;
    }
    if (snapshot.v === 2 && (!snapshot.generation ||
        snapshot.generation !== ctx.credentials.get(SLOT_LOGIN_GENERATION))) {
        log('i', '登录中间态属于已作废的流程，丢弃');
        clearPendingLoginSnapshot();
        return false;
    }

    const session = createSession(deviceId(), 'login', { adoptAsDefault: false });
    session.importCookies(snapshot.cookies || []);
    pendingLogin = {
        stage: snapshot.stage,
        session,
        account: snapshot.account,
        passwordHash: snapshot.passwordHash,
        handshake: snapshot.handshake,
        userId: snapshot.userId || '',
        context: snapshot.context,
        flag: snapshot.flag,
        method: snapshot.method,
        hint: snapshot.hint,
        auth2Ssecurity: snapshot.auth2Ssecurity,
    };
    // 旧版 epoch 不具有跨进程含义；通过 TTL/结构校验后迁移到稳定代数。
    if (snapshot.v === 1) savePendingLoginSnapshot();
    log('i', '已恢复登录中间态，阶段=' + snapshot.stage);
    return true;
}

/**
 * deviceId 要跨轮次、跨进程稳定。
 *
 * 服务端按 UA 里的 deviceId 认设备，登录用 A、取数用 B 会触发风控。
 * 只放内存变量是不够的：插件被宿主重新加载（升级、进程重启）后模块状态全没了，
 * 会生成一个新的 deviceId，在服务端看来就是「换了一台设备登录」。
 * 参考实现把它落在 config，Kotlin 版靠同一个实例存活，
 * 这里用 `ctx.files` 落到插件私有目录，效果等价。
 */
let cachedDeviceId = '';

const DEVICE_ID_FILE = 'device_id.txt';

function deviceId(): string {
    if (cachedDeviceId) return cachedDeviceId;

    // 先读上次存的
    try {
        const saved = ctx.files.read(DEVICE_ID_FILE);
        if (typeof saved === 'string') {
            const trimmed = saved.trim();
            if (trimmed.length >= 8) {
                cachedDeviceId = trimmed;
                return cachedDeviceId;
            }
        }
    } catch (e) {
        log('w', '读取 deviceId 失败，将重新生成：' + describeError(e));
    }

    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    let out = '';
    for (let i = 0; i < 16; i++) {
        out += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    cachedDeviceId = out;
    try {
        ctx.files.write(DEVICE_ID_FILE, out);
    } catch (e) {
        log('w', '保存 deviceId 失败：' + describeError(e));
    }
    return cachedDeviceId;
}

/** 当前会话的凭据（内存缓存，落盘在 ctx.credentials） */
let currentCreds: Creds | null = null;

/**
 * 会话代数：每次登出 +1。
 *
 * 异步流程（登录、续期）在起步时记下当时的代数，写回凭据前比对：
 * 不一致说明这期间用户登出过，本次结果必须作废。
 *
 * 为什么不能只靠 `pendingLogin = null`：那只能挡住「同一轮登录的后续轮次」，
 * 挡不住已经发出去的 await。典型场景是用户点了登录、等了两秒觉得不对又点登出，
 * 两秒后登录成功回来，凭据又被写回去了——用户看到的是「登出了但还在采数」。
 */
let sessionEpoch = 0;

/**
 * 最近一次采集是否被服务端判定为凭据失效。
 *
 * ## 为什么需要这个标记
 *
 * `authState` 只看「本地有没有 passToken」是不够的：passToken 存在
 * 不代表它还有效。实测过 passToken 已过期、但本地仍有值的场景，
 * 此时 `authState` 报「已登录」，而所有采集都 401——用户看到的是
 * 「显示已登录、同步却是 0 条」，没有任何线索指向「去重新登录」。
 *
 * 但也不能每次 `authState` 都发一次网络请求去验证：详情页可能被频繁打开，
 * 那样既慢又浪费流量。所以用「采集时发现的失效」作为信号：
 * `fetch` 收到 401 且续期失败时置位，`authState` 据此报 expired。
 *
 * 重置时机：登录成功（新凭据）、logout、以及任何一次请求成功
 * （说明服务端又认这套凭据了）。
 */
let credsRejectedByServer = false;

/**
 * 失效标志必须**跨运行时重建**保留。
 *
 * 它决定 `authState` 报「已登录」还是「已失效」。只放内存的话，插件被卸载
 * 再拉起后标志归零，用户会看到「已登录」却仍然同步失败——这正是本任务
 * 要消灭的那种误导状态。
 *
 * 存在凭据存储里（加密），值只是一个 '1'，不含任何秘密。
 */
const SLOT_CREDS_REJECTED = 'creds_rejected';

function setCredsRejected(rejected: boolean): void {
    credsRejectedByServer = rejected;
    try {
        if (rejected) {
            ctx.credentials.put(SLOT_CREDS_REJECTED, '1');
        } else {
            ctx.credentials.remove(SLOT_CREDS_REJECTED);
        }
    } catch (e) {
        log('w', '保存凭据失效标志失败：' + describeError(e));
    }
}

/** 读一次持久化标志并合并到内存（惰性、只读一槽位） */
function isCredsRejected(): boolean {
    if (credsRejectedByServer) return true;
    try {
        if (ctx.credentials.get(SLOT_CREDS_REJECTED) === '1') {
            credsRejectedByServer = true;
        }
    } catch (e) {
        log('w', '读取凭据失效标志失败：' + describeError(e));
    }
    return credsRejectedByServer;
}

function log(level: 'd' | 'i' | 'w' | 'e', message: string): void {
    try {
        ctx.log[level]('MiHealth', message);
    } catch (e) {
        // 日志失败绝不能让业务中断
    }
}

/**
 * 账号打码后再进日志。
 *
 * 日志页是给用户看、也会被整份导出发给别人的，账号属于个人信息，
 * 完整写进去等于把「谁在用这台手机」一并带出去了。
 * 保留前 3 后 2 位，足够用户确认「是不是我这个号」。
 */
function maskAccount(account: string): string {
    const s = String(account || '');
    if (s.length <= 5) return '***';
    return s.slice(0, 3) + '***' + s.slice(-2);
}

// ---------- 凭据存取 ----------

function loadCredentials(): Creds | null {
    if (currentCreds) return currentCreds;
    const ssecurity = ctx.credentials.get(SLOT_SSECURITY);
    const serviceToken = ctx.credentials.get(SLOT_SERVICE_TOKEN);
    if (!ssecurity || !serviceToken) return null;
    currentCreds = {
        ssecurity,
        serviceToken,
        cUserId: ctx.credentials.get(SLOT_CUSER_ID) || '',
        // userId 不落盘：原实现没存这个槽位，认证链路里的 userId 都从会话
        // cookie 现取。加进来会多一个凭据槽位，属于超出原行为范围的改动
        userId: '',
        passToken: ctx.credentials.get(SLOT_PASS_TOKEN) || '',
        account: ctx.credentials.get(SLOT_ACCOUNT) || '',
    };
    return currentCreds;
}

/**
 * 落盘凭据。
 *
 * **不写 password**：密码只在登录那一次用到，存下来只是风险没有收益。
 */
function saveCredentials(creds: Creds): void {
    ctx.credentials.put(SLOT_SSECURITY, creds.ssecurity || '');
    ctx.credentials.put(SLOT_SERVICE_TOKEN, creds.serviceToken || '');
    ctx.credentials.put(SLOT_CUSER_ID, creds.cUserId || '');
    ctx.credentials.put(SLOT_PASS_TOKEN, creds.passToken || '');
    if (creds.account) {
        ctx.credentials.put(SLOT_ACCOUNT, creds.account);
    }
    currentCreds = creds;
    // 新凭据已落盘，之前记录的「服务端拒绝」必然针对旧凭据，必须清掉，
    // 否则用户刚登录成功，authState 还在报「登录已失效」
    setCredsRejected(false);
}

/**
 * 在异步流程开头记下当前代数，用于写回前判断「这期间用户是否登出过」。
 *
 * 用法：
 * ```ts
 * const epoch = captureEpoch();
 * const creds = await doNetworkLogin();
 * if (epoch !== sessionEpoch) return;   // 用户已登出，丢弃本次结果
 * saveCredentials(creds);
 * ```
 */
function captureEpoch(): number {
    return sessionEpoch;
}

function isValidCreds(creds: Creds | null): creds is Creds {
    // cUserId 不算必需：服务端靠 serviceToken + ssecurity 认人，
    // 把它算成必需会让「cookie 名大小写对不上」被误判成未登录
    return !!creds && !!creds.ssecurity && !!creds.serviceToken;
}

// ---------- 数据接口 ----------

/**
 * 数据接口返回 401 时抛出的错误。
 *
 * 单独一种错误类型而不是靠 message 字符串判断：调用方需要区分
 * 「凭据失效（要用户重新登录）」与「其它失败（重试可能有用）」，
 * 而字符串匹配会随文案改动失效。
 */
class AuthError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'AuthError';
    }
}

/** 判断一个错误是否为凭据失效。 */
function isAuthError(e: unknown): boolean {
    if (e instanceof AuthError) return true;
    // 兜底：跨模块实例化或 Error 被重新包装时 instanceof 可能失效
    return e != null && (e as { name?: string }).name === 'AuthError';
}

/**
 * 调一次云端接口并返回解密后的对象。
 *
 * @throws [AuthError] 凭据失效（401）
 * @throws 其它错误：网络失败 / 非 2xx / 空响应 / 解密失败
 */
async function callApi(
    session: MiSession,
    creds: Creds,
    method: string,
    path: string,
    params: Record<string, string | number | null>,
): Promise<any> {
    const signed = signQuery(method, path, params, creds.ssecurity);
    const url = 'https://hlth.io.mi.com' + path + '?' + signed.query;

    const res = await session.send(method, url, {
        // 注意：不带 userId cookie，带了服务端稳定返回 401
        headers: {
            Cookie: cookieHeaderOf(creds),
            Accept: 'application/json',
        },
        userAgent: ua(deviceId(), 0),
        contentType: 'application/x-www-form-urlencoded',
        body: method === 'POST' ? '' : undefined,
    });

    // HTTP 失败、空响应、解密失败都必须向上抛出，不能被调用方当成「无数据」。
    //
    // 401 单独区分：它是唯一一种用户自己能修好的失败（重新登录）。
    // 而 401 的响应体是**明文**错误对象，若继续往下走解密 + JSON.parse，
    // 报出来会是一句「Unexpected end of JSON input」——把「凭据过期」
    // 伪装成「数据格式错误」，用户根本不知道该去登录。
    if (!res || res.status === 401) {
        throw new AuthError('小米接口返回 401，凭据已失效');
    }
    if (!res.ok) {
        throw new Error('HTTP ' + ((res && res.status) || '无响应'));
    }
    const body = String(res.body || '').trim();
    if (!body) {
        throw new Error('数据接口响应为空');
    }

    const plain = decryptBody(creds.ssecurity, signed.nonce, body);
    try {
        return JSON.parse(plain);
    } catch (e) {
        // 解密成功但解析失败：会话密钥不配套（ssecurity 与 serviceToken
        // 来自不同会话）时正是这个症状，同样只能靠重新登录解决
        throw new Error('数据接口响应无法解密解析（会话密钥可能不配套）');
    }
}

/** 数据接口只认这三件 cookie */
function cookieHeaderOf(creds: Creds): string {
    return 'cUserId=' + creds.cUserId + '; serviceToken=' + creds.serviceToken + '; locale=zh_cn';
}

/**
 * 用 passToken 换一套新凭据。
 *
 * 免密续期：passToken 有效期约 1~2 个月。返回 null 说明连它都过期了，
 * 必须让用户重新输密码。
 */
async function refreshWithPassToken(
    passToken: string,
    cUserId: string,
    userId: string,
    account: string,
): Promise<Creds | null> {
    if (!passToken) return null;
    // 临时会话，不抢默认会话的位置：续期可能在登录流程中途被触发，
    // 让它成为默认会把正在进行的那轮登录换到另一个空会话上。
    const session = createSession(deviceId(), 'refresh', { adoptAsDefault: false });
    // 换票时必须带 cUserId 与 userId，只带 passToken 服务端会当作未认证。
    //
    // cookie 名是大小写敏感的：写成小写 `userid` 不会报错，只是 value() 静默
    // 返回 null，于是换票请求只带 passToken，服务端按未认证处理，稳定返回 70016。
    session.setCookie('passToken', passToken, 'xiaomi.com');
    if (cUserId) session.setCookie('cUserId', cUserId, 'xiaomi.com');
    if (userId) session.setCookie('userId', userId, 'xiaomi.com');

    const res = await session.send('GET', ACCOUNT + '/pass/serviceLogin?sid=' + SID + '&_json=true', {
        headers: { Referer: ACCOUNT + '/' },
    });
    const j = parseMiapi(res.body);
    if (!j || !j.ssecurity) {
        log('i', 'refreshWithPassToken rejected: ' + JSON.stringify(j || {}).slice(0, 200));
        return null;
    }
    const loc = j.location || '';
    if (!loc) return null;

    const token = await fetchServiceToken(session, loc, userId || '');
    if (!token) return null;

    return {
        ssecurity: j.ssecurity,
        serviceToken: token,
        cUserId: j.cUserId || cUserId,
        userId: j.userId ? String(j.userId) : (userId || ''),
        passToken: j.passToken || passToken,
        account: account || '',
    };
}

/**
 * 带 401 自愈的采集客户端。
 *
 * 返回一个 `getFitnessDataByTime(cloudKey, start, end)` 闭包，内部在收到 401 时
 * 用 passToken 续期再重试一次。续期成功后立刻写回凭据，
 * 下一次采集就不用再续了。
 */
function clientFor(creds: Creds): CollectClient {
    // 采集用独立会话，与登录流程分开：采集随时可能在后台跑，
    // 复用登录会话会让正在填验证码的那一轮突然换掉 cookie。
    const session = createSession(deviceId(), 'collect', { adoptAsDefault: false });
    let current = creds;

    async function callWithRetry(
        method: string,
        path: string,
        params: Record<string, string | number | null>,
    ): Promise<any> {
        try {
            return await callApi(session, current, method, path, params);
        } catch (error) {
            // 只有凭据失效才值得尝试续期；网络错误、5xx 重发只会重复失败。
            // 用 isAuthError 而不是匹配 message 字符串：文案会改，类型不会。
            if (!isAuthError(error) || !current.passToken) throw error;
            log('i', '数据接口返回 401，按参考实现用 passToken 续期后重试一次');
            const refreshEpoch = captureEpoch();
            const fresh = await refreshWithPassToken(
                current.passToken,
                current.cUserId,
                current.userId || '',
                current.account,
            );
            if (!fresh) throw error;
            current = fresh;
            // 续期期间用户可能已登出：不把旧凭据写回去。
            if (refreshEpoch === sessionEpoch) {
                saveCredentials(fresh);
            }
            return await callApi(session, current, method, path, params);
        }
    }

    /**
     * 取某类数据在时间窗内的采样点。
     *
     * ## 分页陷阱
     * - 游标参数名必须是 snake_case `next_key`，camelCase 会被忽略
     * - 游标会向后遍历全部历史，与 startTime 无关；startTime 传太早会卡死
     * - 所以这里永远把 startTime 钳到「不早于 end - 24h」
     *
     * ## 必须自己按时间窗过滤（真实踩过的坑）
     *
     * 实测确认：`startTime` / `endTime` **并不真的约束返回内容**。
     * 请求 11-16 那天的窗口，第 1 页返回的是 11-17 12:10 ~ 11-19 09:30 的点，
     * 该日的数据反而在第 2 页；而请求 11-19 时第 1 页又是同一批。
     *
     * 也就是说翻页拿到的是「按时间倒序的历史切片」，与请求窗口无关。
     * 若把每页的点无差别收下，采集 11-16 会把 11-17~11-19 的步数一起累加，
     * 表现为多个不同日期的日值完全相同（历史 bug：11-16/17/18 均 45176）。
     *
     * 因此这里在收集阶段就丢弃落在 [start, end] 之外的点。
     * 注意过滤用的是**调用方传进来的真实窗口**，不是钳过的 `apiStart`：
     * `apiStart` 只是为了别让游标回溯太远，不是「要哪些数据」的定义。
     */
    async function getFitnessDataByTime(
        cloudKey: string,
        startTimeMillis: number,
        endTimeMillis: number,
        maxPages?: number,
    ): Promise<CloudPoint[]> {
        const limit = maxPages || 10;
        const apiStart = Math.max(startTimeMillis, endTimeMillis - DAY_MILLIS);
        const out: CloudPoint[] = [];
        // 去重：翻页偶尔会在页边界重复给出同一个点（sid 唯一），
        // 累加型指标重复一次就直接翻倍，表现为 SPO2 96→192
        const seen = new Set<string>();
        let nextKey: string | null = null;

        /** 收下一个点，只留窗口内、未重复的 */
        const accept = (p: CloudPoint | null | undefined): void => {
            if (!p || typeof p !== 'object') return;
            const t = timestampAt(p, POINT_TIME_FIELDS);
            // 取不到时间的点一律丢弃：无法判断它属不属于这一天，
            // 留着就是给累加型指标注入未知量
            if (t === null || t < startTimeMillis || t > endTimeMillis) return;
            const sid = typeof (p as Record<string, unknown>).sid === 'string'
                ? String((p as Record<string, unknown>).sid)
                : null;
            const key = sid !== null ? sid : t + '|' + JSON.stringify(p);
            if (seen.has(key)) return;
            seen.add(key);
            out.push(p);
        };

        for (let page = 0; page < limit; page++) {
            const data = {
                key: cloudKey,
                startTime: apiStart,
                endTime: endTimeMillis,
                reverse: true,
                next_key: nextKey,
            };
            const resp = await callWithRetry('GET', '/app/v1/data/get_fitness_data_by_time', {
                data: JSON.stringify(data),
            });
            if (!resp || typeof resp !== 'object') {
                throw new Error('数据接口返回不是 JSON 对象');
            }
            if (typeof resp.code === 'number' && resp.code !== 0 && resp.code !== 1) {
                throw new Error('数据接口 code=' + resp.code + ' message=' + (resp.message || '无'));
            }

            // 外层统一是 {code, message, result}，数据和分页字段都在 result 里。
            // 早期直接读顶层 data_list，结果永远拿不到列表
            const body = resp.result || resp;
            const items = body.data_list;
            if (!items) {
                // 服务端偶尔用 dataPoints 另一套命名
                (body.dataPoints || []).forEach(accept);
                break;
            }
            items.forEach(accept);

            const more = !!(body.has_more || body.hasMore);
            nextKey = body.next_key || null;
            if (!more || !nextKey) break;
        }
        return out;
    }

    /**
     * 每日目标达成。
     *
     * 直接走 [callApi] 而不是 [callWithRetry]：后者的 401 分支会去走
     * passToken 续期流程，而这里失败属于「这个账号没有目标统计」的常见情况，
     * 不该被当成凭据失效去续期（续期成功后依然拿不到，于是被误报成 401）。
     */
    async function getDailyGoals(startTimeMillis: number, endTimeMillis: number): Promise<any[]> {
        const resp = await callApi(session, current, 'GET', AGGREGATED_PATH, {
            data: JSON.stringify({
                tag: 'daily_fitness',
                key: 'goal',
                startTime: startTimeMillis,
                endTime: endTimeMillis,
                reverse: true,
            }),
        });
        if (!resp || typeof resp !== 'object') throw new Error('聚合接口返回不是 JSON 对象');
        const body = resp.result || resp;
        const list = body.data_list || body.dataPoints || [];
        // 服务端给的是倒序，转成时间升序，方便取「最新一天」
        return list.slice().sort((a: any, b: any) => Number(a.time) - Number(b.time));
    }

    return { getFitnessDataByTime, getDailyGoals, currentCreds: () => current };
}

// ---------- 采样点解析 ----------

/**
 * 取出真正承载字段的对象。
 *
 * 真实接口的数值不在外层，而在字符串字段 `value` 里的**内层 JSON** 中。
 * 直接对外层取 value 只会拿到一串数字字符，表现为「登录成功但全 0 条」。
 * `value` 为空或不是合法 JSON 时退回原对象（sport_records 这类本就把
 * 字段放在外层）。
 */
function payloadOf(point: CloudPoint | null | undefined): Record<string, unknown> {
    if (!point) return {};
    const raw = point.value;
    if (typeof raw !== 'string' || raw.trim() === '') return point;
    try {
        const inner = JSON.parse(raw);
        return inner && typeof inner === 'object' ? inner : point;
    } catch (e) {
        return point;
    }
}

/** 按候选字段顺序取第一个有效数值。内层优先，外层兜底 */
function numberAt(point: CloudPoint, fields: string[]): number | null {
    const payload = payloadOf(point);
    for (let i = 0; i < fields.length; i++) {
        const name = fields[i];
        if (payload && Object.prototype.hasOwnProperty.call(payload, name)) {
            const v = asNumber(payload[name]);
            if (v !== null) return v;
        }
        if (point && Object.prototype.hasOwnProperty.call(point, name)) {
            const v = asNumber(point[name]);
            if (v !== null) return v;
        }
    }
    return null;
}

/**
 * 取时间戳并统一成毫秒。
 *
 * 阈值 1e11：毫秒时间戳 1973 年就超过它，秒级要到公元 5138 年，
 * 正常健康数据不可能落错区间。
 */
function timestampAt(point: CloudPoint, fields: string[]): number | null {
    const payload = payloadOf(point);
    for (let i = 0; i < fields.length; i++) {
        const name = fields[i];
        let raw: unknown;
        if (payload && Object.prototype.hasOwnProperty.call(payload, name)) {
            raw = payload[name];
        } else if (point && Object.prototype.hasOwnProperty.call(point, name)) {
            raw = point[name];
        } else {
            continue;
        }
        const v = asLong(raw);
        if (v === null || v <= 0) continue;
        return v < 100000000000 ? v * 1000 : v;
    }
    return null;
}

// ---------- 日期工具 ----------
//
// QuickJS 没有 Intl，也拿不到系统时区名，但 Date 的本地时间方法
// （getFullYear / getMonth 等）是可用的，它们按宿主进程的默认时区计算。
// 这与 Kotlin 版的 ZoneId.systemDefault() 语义一致。

/** `YYYY-MM-DD` → 本地零点毫秒 */
function dayStart(dateStr: string): number | null {
    const parts = String(dateStr).split('-');
    if (parts.length !== 3) return null;
    const y = parseInt(parts[0], 10);
    const m = parseInt(parts[1], 10);
    const d = parseInt(parts[2], 10);
    if (!isFinite(y) || !isFinite(m) || !isFinite(d)) return null;
    return new Date(y, m - 1, d, 0, 0, 0, 0).getTime();
}

/** 目标自然日的 [start, end] 毫秒窗口；当天未结束时截止到现在 */
function dayRange(dateStr: string): [number, number] | null {
    const start = dayStart(dateStr);
    if (start === null) return null;
    const nextStart = new Date(start);
    nextStart.setDate(nextStart.getDate() + 1);
    const end = Math.min(nextStart.getTime() - 1, Date.now());
    return [start, end];
}

/** 本地日期 → `YYYY-MM-DD` */
function formatDate(d: Date): string {
    const y = d.getFullYear();
    const m = d.getMonth() + 1;
    const day = d.getDate();
    return y + '-' + (m < 10 ? '0' + m : m) + '-' + (day < 10 ? '0' + day : day);
}

/** 毫秒 → 本地 `HH:mm`，用于睡眠区间附注 */
function formatClock(millis: number): string {
    const d = new Date(millis);
    const h = d.getHours();
    const mi = d.getMinutes();
    return (h < 10 ? '0' + h : h) + ':' + (mi < 10 ? '0' + mi : mi);
}

// ---------- 采集 ----------

function reportQueried(metricKey: string, date: string): void {
    try {
        ctx.reportMetricQueried(metricKey, date);
    } catch (e) {
        log('w', 'reportMetricQueried(' + metricKey + ') failed: ' + describeError(e));
    }
}

function reportRaw(metricKey: string, records: RawRecord[], date: string): void {
    if (!records || records.length === 0) return;
    try {
        ctx.reportRawRecords(metricKey, records, date);
    } catch (e) {
        log('w', 'reportRawRecords(' + metricKey + ') failed: ' + describeError(e));
    }
}

/**
 * 当日步数，顺带取同一条采样点里的距离与热量。
 *
 * 步数接口返回的是采样点（通常按分钟级），需要累加。
 * 聚合前把每个采样点原样上报：用户想核对「1253 步是怎么来的」时
 * 需要的正是这些点，聚合之后就再也拿不到了。
 *
 * ## 距离与热量为什么在这里取
 *
 * 实测 `distance` 作为独立 key 请求返回 **0 条**：它根本不是独立数据源，
 * 而是每个 steps 采样点里自带的字段（`{time, steps, distance, calories}`）。
 * 早先按独立 key 请求既拿不到数据，还白多一次网络往返。
 *
 * @return 当天有数据的指标列表（步数为 0 也会返回，代表「今天确实没动」）
 */
async function collectSteps(
    client: CollectClient,
    start: number,
    end: number,
    date: string,
): Promise<PluginMetric[]> {
    const points = await client.getFitnessDataByTime('steps', start, end);
    if (!points || points.length === 0) {
        log('i', 'collectSteps(' + date + ') 接口返回 0 个采样点');
        return [];
    }

    const raw: RawRecord[] = [];
    const rawDistance: RawRecord[] = [];
    const rawCalories: RawRecord[] = [];
    let sum = 0;
    let distance = 0;
    let calories = 0;
    points.forEach((p) => {
        const at = timestampAt(p, ['time', 'timestamp']);
        const steps = numberAt(p, STEP_FIELDS);
        // 没有有效数值的点按 0 处理：它们代表「这个时刻没记步」，不是数据缺失
        if (steps !== null) sum += steps;
        if (at !== null && steps !== null) {
            raw.push({ timestamp: at, value: steps });
        }
        const d = numberAt(p, STEP_DISTANCE_FIELDS);
        if (d !== null) {
            distance += d;
            if (at !== null) rawDistance.push({ timestamp: at, value: d });
        }
        const c = numberAt(p, STEP_CALORIE_FIELDS);
        if (c !== null) {
            calories += c;
            if (at !== null) rawCalories.push({ timestamp: at, value: c });
        }
    });

    log(
        'i',
        'collectSteps(' + date + ') 采样点 ' + points.length + ' 个，可解析 ' + raw.length +
            ' 个，距离 ' + Math.round(distance) + ' 米，热量 ' + calories.toFixed(1) + ' kcal',
    );
    raw.sort((a, b) => a.timestamp - b.timestamp);
    reportRaw(STEPS_KEY, raw, date);
    rawDistance.sort((a, b) => a.timestamp - b.timestamp);
    reportRaw(OBS_DISTANCE, rawDistance, date);
    rawCalories.sort((a, b) => a.timestamp - b.timestamp);
    reportRaw(OBS_CALORIES, rawCalories, date);

    // 步数用 >0 判断（真的没走路时不该写一条 0），距离与热量只要有采样点就是有效值
    const out: PluginMetric[] = [];
    if (sum > 0) out.push({ metricKey: STEPS_KEY, value: sum });
    if (points.length > 0) {
        out.push({ metricKey: OBS_DISTANCE, value: Math.round(distance) });
        out.push({ metricKey: OBS_CALORIES, value: Math.round(calories * 10) / 10 });
    }
    return out;
}

/**
 * 一次睡眠会话（醒来后由云端合并成一条）。
 *
 * 同一次睡眠在接口里可能出现**两条记录**：手机算法版本（带 `items` 分期、
 * `awake_count`）与手环版本的汇总（只有总时长）。早先两条都算，睡眠翻倍。
 */
interface SleepSession {
    startMs: number;
    endMs: number;
    /** 实际睡着分钟数（接口的 duration，已扣掉清醒段） */
    duration: number;
    deep: number;
    light: number;
    rem: number;
    awakeCount: number;
    awakeDuration: number;
    /** 有没有分期明细；没有时只有总时长 */
    hasStages: boolean;
}

/** 两次会话是否属于同一夜：重叠时长占较短一次的一半以上 */
function sameSleep(a: SleepSession, b: SleepSession): boolean {
    const overlap = Math.min(a.endMs, b.endMs) - Math.max(a.startMs, b.startMs);
    if (overlap <= 0) return false;
    const shorter = Math.min(a.endMs - a.startMs, b.endMs - b.startMs);
    return shorter > 0 && overlap / shorter >= 0.5;
}

/** 同一夜里留哪条：优先带分期明细的，其次清醒次数更完整的 */
function richerSleep(a: SleepSession, b: SleepSession): SleepSession {
    const score = (s: SleepSession) => (s.hasStages ? 2 : 0) + (s.awakeCount > 0 ? 1 : 0);
    return score(b) > score(a) ? b : a;
}

/** 本地时刻 → 分钟数。凌晨 0~12 点算作「前一天的延续」（+24h），跨零点仍单调 */
function bedtimeMinute(timestampMs: number): number {
    const d = new Date(timestampMs);
    const minute = d.getHours() * 60 + d.getMinutes();
    return minute < 12 * 60 ? minute + 24 * 60 : minute;
}

/**
 * 入睡规律性（0~100，插件按近 7 天入睡时间计算）。
 *
 * 小米云端没有直接给出这个分数，这里用「入睡时间标准差」折算：
 * 每偏离 3 分钟扣 1 分，波动一小时约 80 分。样本少于 3 天不计算——
 * 两三天的波动没有参考意义。
 */
function sleepRegularity(todayBedMinute: number): number | null {
    const rows = recentValues(SLEEP_BEDTIME_KEY);
    const values = rows.map((r) => r.value).filter((v) => isFinite(v) && v > 0);
    values.push(todayBedMinute);
    if (values.length < 3) return null;
    const mean = values.reduce((a, b) => a + b, 0) / values.length;
    const variance = values.reduce((a, b) => a + (b - mean) * (b - mean), 0) / values.length;
    const std = Math.sqrt(variance);
    return Math.max(0, Math.round(100 - std / 3));
}

/**
 * 采集「某一夜」的睡眠。
 *
 * ## 归属口径
 *
 * 按**醒来日期**归属：昨晚 22:43 → 今早 07:58 算今天的睡眠。
 * 早先按自然日裁剪，一夜会被拆成 60 分钟 + 420 分钟两天，
 * 用户看到「昨天只睡了 1 小时」。
 *
 * ## 窗口为什么从「昨天中午」开始
 *
 * 接口按 `bedtime` 过滤，22、23 点入睡的会话落在目标日之前；
 * 从目标日零点开始取会把整夜漏掉。取前一天 12:00 起，
 * 覆盖所有「当晚入睡、次日醒来」的组合，再按醒来日期筛。
 *
 * @return 日值列表 + 是否真的有会话（用于回补时区分「没数据」与「失败」）
 */
async function collectSleep(
    client: CollectClient,
    date: string,
): Promise<{ metrics: PluginMetric[]; hasData: boolean }> {
    // 先登记「查过」：即使后面一条都没拿到，详情页也要能列出这几个指标并显示「暂无」
    const keys = [
        SLEEP_KEY,
        SLEEP_DEEP_RATIO_KEY,
        SLEEP_EFFICIENCY_KEY,
        SLEEP_AWAKE_COUNT_KEY,
        SLEEP_BEDTIME_KEY,
        SLEEP_REGULARITY_KEY,
    ];
    keys.forEach((k) => reportQueried(k, date));

    const start = dayStart(date);
    if (start === null) return { metrics: [], hasData: false };
    const prevNoon = start - 12 * 60 * 60 * 1000;
    const nextStart = new Date(start);
    nextStart.setDate(nextStart.getDate() + 1);
    const end = Math.min(nextStart.getTime() - 1, Date.now());

    const points = await client.getFitnessDataByTime('sleep', prevNoon, end);
    if (!points || points.length === 0) return { metrics: [], hasData: false };

    // 解析 + 合并同一夜的重复记录
    const sessions: SleepSession[] = [];
    points.forEach((p) => {
        const s = timestampAt(p, SLEEP_START_FIELDS);
        const e = timestampAt(p, SLEEP_END_FIELDS);
        if (s === null || e === null || e <= s) return;

        const rawDuration = numberAt(p, ['duration']);
        const deep = numberAt(p, ['sleep_deep_duration']) || 0;
        const light = numberAt(p, ['sleep_light_duration']) || 0;
        const rem = numberAt(p, ['sleep_rem_duration']) || 0;
        const duration =
            rawDuration !== null && rawDuration > 0 ? rawDuration : (e - s) / 60000;

        const session: SleepSession = {
            startMs: s,
            endMs: e,
            duration,
            deep,
            light,
            rem,
            awakeCount: numberAt(p, ['awake_count']) || 0,
            awakeDuration: numberAt(p, ['sleep_awake_duration']) || 0,
            hasStages: deep + light + rem > 0,
        };

        const idx = sessions.findIndex((k) => sameSleep(k, session));
        if (idx >= 0) {
            sessions[idx] = richerSleep(sessions[idx], session);
        } else {
            sessions.push(session);
        }
    });

    // 只留「醒来日 == 目标日」的会话：跨零点的睡眠归到醒来的那一天
    const nightly = sessions.filter((s) => formatDate(new Date(s.endMs)) === date);
    if (nightly.length === 0) return { metrics: [], hasData: false };

    nightly.sort((a, b) => a.startMs - b.startMs);

    // 原始条目：一次会话一条，附注里带上起止与分期，供详情页核对
    const raw: RawRecord[] = nightly.map((s) => {
        const parts = [formatClock(s.startMs) + ' → ' + formatClock(s.endMs)];
        if (s.hasStages) {
            parts.push('深' + Math.round(s.deep) + ' 浅' + Math.round(s.light) + ' REM' + Math.round(s.rem));
        }
        if (s.awakeDuration > 0) parts.push('清醒' + Math.round(s.awakeDuration) + '分');
        return {
            timestamp: s.startMs,
            value: Math.round(s.duration * 10) / 10,
            detail: parts.join(' · '),
        };
    });
    reportRaw(SLEEP_KEY, raw, date);

    const total = nightly.reduce((a, s) => a + s.duration, 0);
    const deep = nightly.reduce((a, s) => a + s.deep, 0);
    const inBed = nightly.reduce((a, s) => a + (s.endMs - s.startMs) / 60000, 0);
    const awakeCount = nightly.reduce((a, s) => a + s.awakeCount, 0);
    const bedtime = bedtimeMinute(nightly[0].startMs);

    const metrics: PluginMetric[] = [{ metricKey: SLEEP_KEY, value: total }];
    if (deep > 0 && total > 0) {
        metrics.push({ metricKey: SLEEP_DEEP_RATIO_KEY, value: Math.round((deep / total) * 1000) / 10 });
    }
    if (inBed > 0 && total > 0) {
        metrics.push({
            metricKey: SLEEP_EFFICIENCY_KEY,
            value: Math.min(100, Math.round((total / inBed) * 1000) / 10),
        });
    }
    if (awakeCount > 0) {
        metrics.push({ metricKey: SLEEP_AWAKE_COUNT_KEY, value: awakeCount });
    }
    metrics.push({ metricKey: SLEEP_BEDTIME_KEY, value: bedtime });
    const regularity = sleepRegularity(bedtime);
    if (regularity !== null) {
        metrics.push({ metricKey: SLEEP_REGULARITY_KEY, value: regularity });
    }

    log(
        'i',
        '睡眠 ' + date + '：' + nightly.length + ' 段，总计 ' + Math.round(total) + ' 分钟，' +
            '深睡占比 ' + (total > 0 ? Math.round((deep / total) * 100) : 0) + '%',
    );
    return { metrics, hasData: true };
}

/**
 * 采集一组共用同一个小米 key 的采样类指标。
 *
 * 原始条目全部上报，日值有三种折法：
 * - `mean`：当天均值（心率、血氧、压力这类「典型水平」）
 * - `duration`：区间长度换算成分钟并累加（站立时长）
 * - 其余：取时间上最新的一条（静息心率、体重、训练负荷这类「一天一次结论」）；
 *   事件型（`fields` 为空）没有数值可取，按**次数**累加
 * 都由插件算好交给宿主，宿主按 manifest 的 `aggregate` 再做一次同样的归约，
 * 两边口径一致因此不会改变结果。
 */
async function collectSampled(
    client: CollectClient,
    cloudKey: string,
    specs: SampledSpec[],
    start: number,
    end: number,
    date: string,
): Promise<PluginMetric[]> {
    const points = await client.getFitnessDataByTime(cloudKey, start, end);

    // 先统一登记「这些指标查过了」：即使 points 为空也要登记，
    // 详情页才能把「查了但没数据」的指标列出来显示「暂无」。
    // 放在任何 return 之前是必须的——一 return 就会漏掉这一批
    specs.forEach((spec) => reportQueried(spec.metricKey, date));

    if (!points || points.length === 0) return [];

    const out: PluginMetric[] = [];
    specs.forEach((spec) => {
        const raw: RawRecord[] = [];
        points.forEach((p) => {
            const at = timestampAt(p, ['time', 'timestamp', 'start_time']);
            if (at === null) return;
            if (spec.duration) {
                // 时长型：接口给的是 {start_time, end_time} 区间，换成分钟
                const s = timestampAt(p, ['start_time', 'startTime', 'time']);
                const e = timestampAt(p, ['end_time', 'endTime']);
                if (s === null || e === null || e <= s) return;
                raw.push({ timestamp: s, value: (e - s) / 60000, detail: formatClock(s) + ' → ' + formatClock(e) });
                return;
            }
            // 事件型（fields 为空）：记录存在本身就是「发生过」，按 1 计
            const value = spec.fields.length === 0 ? 1 : numberAt(p, spec.fields);
            if (value === null) return;
            raw.push({ timestamp: at, value });
        });

        if (raw.length === 0) {
            log('i', cloudKey + ' 的 ' + (spec.fields.join('/') || '(事件型)') + ' 解析不出值');
            return;
        }

        raw.sort((a, b) => a.timestamp - b.timestamp);
        reportRaw(spec.metricKey, raw, date);

        if (spec.mean) {
            let sum = 0;
            raw.forEach((r) => {
                sum += r.value;
            });
            const avg = sum / raw.length;
            // 0 不算「有效日值」：接口对没有测量的日子会返回 0 值点，
            // 当成真实的 0 会把均值拉到地上，卡片的趋势图也会跟着失真
            if (avg > 0) out.push({ metricKey: spec.metricKey, value: avg });
        } else if (spec.duration || spec.fields.length === 0) {
            // 时长型与事件型都在于「这些记录一共多少」，必须累加：
            // 取最新一条只会得到「最后一小时站了 60 分钟」
            let sum = 0;
            raw.forEach((r) => {
                sum += r.value;
            });
            if (sum > 0) out.push({ metricKey: spec.metricKey, value: sum });
        } else {
            // 「一天一次结论」型：取最新一条原值。这里**不能**用 0 过滤——
            // 体脂率、训练负荷这些指标的真实值可以是 0，滤掉就等于丢数据
            const latest = raw[raw.length - 1].value;
            out.push({ metricKey: spec.metricKey, value: latest });
        }
    });

    return out;
}

/**
 * 采集「每日目标达成」。
 *
 * 走**聚合端点** `data/get_aggregated_fitness_data_by_time`：
 * `tag=daily_fitness`（实测，不是 `daily`）、`key=goal`。
 * 返回的 value 里是四个环：
 * ```
 * { goal_items: [{achieved_value, target_value, field}], stand_goal: {...} }
 * ```
 * `field` 映射（官方实测）：1=步数、2=热量、3=站立小时、4=活动分钟。
 */
async function collectDailyGoals(
    client: CollectClient,
    date: string,
): Promise<PluginMetric[]> {
    const keys = [GOAL_STEPS, GOAL_CALORIES, GOAL_ACTIVE_MINUTES, GOAL_STAND_HOURS];
    keys.forEach((k) => reportQueried(k, date));

    const range = dayRange(date);
    if (!range) return [];
    const [start, end] = range;

    let items: any[];
    try {
        items = await client.getDailyGoals(start, end);
    } catch (e) {
        // 该端点在某些账号上直接返回空集（没有统计），那是「没有」而非「失败」，
        // 不该让整轮采集跟着报错
        log('i', 'daily_fitness/goal 取不到（' + date + '）：' + describeError(e));
        return [];
    }
    if (items.length === 0) return [];

    // 同一天可能有多条（多设备/重复写入），取时间最新的一条
    const latest = items[items.length - 1];
    const payload = payloadOf(latest);
    const goals: Record<number, number> = {};

    const goalItems = (payload.goal_items as any[]) || [];
    goalItems.forEach((g: any) => {
        const field = Number(g && g.field);
        const achieved = Number(g && g.achieved_value);
        if (isFinite(field) && isFinite(achieved)) goals[field] = achieved;
    });
    const stand = payload.stand_goal as any;
    if (stand && isFinite(Number(stand.field))) {
        goals[Number(stand.field)] = Number(stand.achieved_value);
    }

    const map: Array<[number, string]> = [
        [1, GOAL_STEPS],
        [2, GOAL_CALORIES],
        [3, GOAL_STAND_HOURS],
        [4, GOAL_ACTIVE_MINUTES],
    ];
    const out: PluginMetric[] = [];
    map.forEach(([field, key]) => {
        const v = goals[field];
        if (v === undefined || !isFinite(v)) return;
        reportRaw(key, [{ timestamp: start, value: v }], date);
        out.push({ metricKey: key, value: v });
    });
    log('i', '每日目标（' + date + '）解析到 ' + out.length + ' 项');
    return out;
}


// ---------- 契约：登录 ----------

/**
 * 多轮登录。
 *
 * 小米 passport 的流程是**递进**的：提交密码后才知道要不要验证码，
 * 验证码过了才知道要不要二次验证。所以用「轮到哪一步」决定读哪些输入，
 * 而不是一开始就要齐所有字段。
 */
export async function login(
    inputs?: Record<string, string>,
    options?: LoginOptions,
): Promise<LoginResult> {
    const form = inputs || {};
    const intent = options && options.intent;
    const epoch = captureEpoch();
    try {
        // ---------- 意图优先 ----------
        // 宿主传空表单时不再靠"猜"：OPEN / RESTART / ABANDON 各有明确语义。
        // 旧宿主不传 options，intent 为 undefined，下面的逻辑完全保持原样。
        if (intent === 'abandon') {
            // 放弃：清理中间态即可。宿主对 ABANDON 只确认清理，不会当成登录成功
            invalidateLogin();
            return { type: 'success' };
        }
        if (intent === 'restart') {
            invalidateLogin();
            return needPassword('');
        }
        if (intent === 'open') {
            // 只读：能恢复就展示恢复后的阶段，不能恢复就问账号密码。
            // **不提交、不清空**——用户可能只是重新打开了页面
            if (!restorePendingLogin()) {
                return needPassword('');
            }
            if (pendingLogin!.stage === 'captcha') {
                return await needCaptcha(pendingLogin!);
            }
            return needOtp(pendingLogin!.hint);
        }

        if (!pendingLogin) {
            // 第一轮：要账号密码。调用方第一次调用通常不带 inputs，
            // 这时先问，不该把空账号密码拿去请求。
            //
            // 但先给恢复一次机会：插件可能刚被宿主卸载又拉起，
            // 用户其实已经走到验证码那一步了
            if (restorePendingLogin()) {
                if (pendingLogin!.stage === 'captcha') {
                    return await needCaptcha(pendingLogin!);
                }
                return needOtp(pendingLogin!.hint);
            }
            const account = form[SLOT_ACCOUNT];
            const password = form[SLOT_PASSWORD];
            if (!account || !password) {
                return needPassword(account || '');
            }
            return await startLogin(account, password);
        }

        // 当前停在某个中间步骤，用户却提交了一个**完全空**的表单：
        // 这表示他不想继续这一步，要退回去重填账号密码。
        //
        // 宿主没法替插件决定「退回」意味着什么，只能把空的意图传下来。
        // 不清 pendingLogin 的话会死循环：空表单 → 仍返回同一个 OTP 界面，
        // 用户既发不出短信也换不了账号。
        const wantsRestart =
            !form[SLOT_ACCOUNT] && !form[SLOT_PASSWORD] &&
            !form[SLOT_CAPTCHA] && !form[SLOT_OTP];
        if (wantsRestart) {
            invalidateLogin();
            return needPassword('');
        }

        if (pendingLogin.stage === 'captcha') {
            const code = form[SLOT_CAPTCHA];
            if (!code) return await needCaptcha(pendingLogin);
            return await submitCaptcha(code);
        }

        if (pendingLogin.stage === 'twoFactor') {
            const code = form[SLOT_OTP];
            if (!code) return needOtp(pendingLogin.hint);
            return await submit2FA(code);
        }

        // stage === 'password'：重新走一遍密码登录。
        // 这里不再复用中间态里的密码——它已经只存哈希，存不了明文。
        // 用户没填密码就再问一次，比"用哈希当密码"更诚实。
        if (!form[SLOT_PASSWORD]) {
            return needPassword(form[SLOT_ACCOUNT] || pendingLogin.account);
        }
        return await startLogin(
            form[SLOT_ACCOUNT] || pendingLogin.account,
            form[SLOT_PASSWORD],
        );
    } catch (e) {
        log('e', 'login failed: ' + describeError(e));
        // 旧调用迟到失败时，不得清除用户刚重开的新流程。
        if (loginCancelled(epoch)) return cancelledLogin();
        resetPendingLogin();
        return { type: 'failure', message: '登录异常：' + describeError(e) };
    }
}

function needPassword(account: string): LoginResult {
    return {
        type: 'needInput',
        prompt: '请输入小米账号和密码',
        // 密码必须掩码：登录时旁边可能有人，明文输入是实打实的泄露
        fields: [
            { name: SLOT_ACCOUNT, displayName: '小米账号', secret: false, hint: account ? '上次输入：' + account : undefined },
            { name: SLOT_PASSWORD, displayName: '密码', secret: true },
        ],
    };
}

/** 第一轮：拿账号密码去小米 passport 换结果 */
async function startLogin(account: string, password: string): Promise<LoginResult> {
    const epoch = captureEpoch();
    const session = createSession(deviceId(), 'login');
    log('i', '登录开始，account=' + maskAccount(account));

    // ① 握手。服务端响应带 &&&START&&& 前缀，只能用插件自己的 parseMiapi 解析
    // （serviceLogin 内部就是这么做的），不能读宿主给的 json 字段：
    // 宿主是严格 JSON 解析，遇到前缀必然失败并返回 null。
    // 必须传 account：服务端靠 `userId=<账号>` cookie 定位账号，
    // 缺了它不下发 _sign（回一个 code:0 + psecurity 的「已登录」型响应），
    // 表现为「未拿到 _sign」，换任何密码都无效。
    const handshake = await serviceLogin(session, account);
    if (loginCancelled(epoch)) return cancelledLogin();
    if (!handshake || !handshake._sign) {
        log('e', '握手失败：未拿到 _sign，响应=' + JSON.stringify(handshake || null).slice(0, 200));
        return { type: 'failure', message: '登录握手失败：无法连接小米登录服务或未拿到 _sign' };
    }
    log('i', '握手成功，已拿到 _sign');
    // ② 提交密码。密码哈希必须是**单次** MD5 转大写，
    // 双重 MD5 会稳定返回 70016，且错误信息看起来像风控
    const hash = md5Upper(password);
    const auth2 = await serviceLoginAuth2(session, account, hash, handshake, '');
    if (loginCancelled(epoch)) return cancelledLogin();
    log(
        'i',
        'serviceLoginAuth2 返回：code=' + (auth2 && auth2.code) +
            ' 有 notificationUrl=' + !!(auth2 && auth2.notificationUrl) +
            ' 有 captchaUrl=' + !!(auth2 && auth2.captchaUrl) +
            ' desc=' + ((auth2 && auth2.desc) || ''),
    );

    pendingLogin = {
        stage: 'password',
        session,
        account,
        passwordHash: hash,
        handshake,
        userId: '',
    };

    return await handleAuth2(auth2, account, epoch);
}

// ---------- 契约：登出 / 登录态 ----------

export async function logout(): Promise<void> {
    // 不排队：登出是用户点了就要立刻生效的动作。
    // 代数递增让所有在途的异步流程作废——它们回来时会发现代数变了，不再写凭据
    invalidateLogin();
    currentCreds = null;
    setCredsRejected(false);
    try {
        ctx.credentials.clear();
    } catch (e) {
        log('w', 'logout clear failed: ' + describeError(e));
    }
}

export async function authState(): Promise<AuthState> {
    try {
        const creds = loadCredentials();
        if (!isValidCreds(creds)) return { state: 'loggedOut' };

        // 上一次采集已被服务端判为凭据失效（401 且续期失败）。
        // 必须先于「有 passToken 就算已登录」判断，否则用户永远看到「已登录」，
        // 而实际每次同步都是 0 条——这正是最误导的状态。
        if (isCredsRejected()) {
            return {
                state: 'expired',
                message: '登录已失效，请重新登录小米账号',
            };
        }

        // 有 passToken 说明凭据长期有效，但需注意：passToken 存在
        // 不代表服务端仍认可它（本函数不发网络请求验证）。
        // 真实的失效会由采集时的 401 反映出来，见 credsRejectedByServer。
        if (creds.passToken) {
            return creds.account
                ? { state: 'loggedIn', accountName: creds.account }
                : { state: 'loggedIn' };
        }
        return { state: 'expired', message: '登录即将过期，需要重新输入密码' };
    } catch (e) {
        log('e', 'authState failed: ' + describeError(e));
        return { state: 'loggedOut' };
    }
}

// ---------- 契约：拉数 ----------

export async function fetch(date: string): Promise<FetchResult> {
    const out: PluginMetric[] = [];
    try {
        const range = dayRange(date);
        if (!range) {
            log('w', 'fetch 非法日期：' + date);
            return out;
        }
        const creds = loadCredentials();
        if (!isValidCreds(creds)) {
            // 没有凭据要用 authRequired 而不是空数组：空数组会让宿主显示
            // 「今日无数据」，用户会去怀疑手环没戴，实际只是没登录
            return { status: 'authRequired', message: '未登录小米账号，请先在插件页登录' };
        }

        const [start, end] = range;
        const client = clientFor(creds);

        // 凭据失效（401 且 passToken 续期也失败）要在**第一次**遇到时
        // 立刻终止整轮采集并明确上报 authRequired。
        //
        // 否则每个指标各自 catch 掉自己的 401，最终返回空数组，
        // 宿主显示「同步完成，写入 0 条」——用户完全看不出是凭据过期。
        let authFailure: string | null = null;
        const noteAuthFailure = (e: unknown) => {
            if (isAuthError(e)) authFailure = describeError(e);
        };

        // 两个量纲各自独立：一个失败不该让另一个也丢
        //
        // 注意 reportQueried 的登记位置：必须放在「请求成功返回之后」。
        // 它的语义是「这一天确实查过、结果就是没有数据」，详情页据此显示「暂无」。
        // 若在请求前就登记，网络失败或未登录时也会显示「暂无」，
        // 用户会误判为「这天真的没走步」，而不是「采集失败」。
        try {
            // 步数、距离、热量同源：一次 steps 请求就能全拿到
            const stepMetrics = await collectSteps(client, start, end, date);
            [STEPS_KEY, OBS_DISTANCE, OBS_CALORIES].forEach((k) => reportQueried(k, date));
            stepMetrics.forEach((m) => out.push(m));
        } catch (e) {
            noteAuthFailure(e);
            log('w', '步数采集失败（' + date + '）：' + describeError(e));
        }

        try {
            const sleep = await collectSleep(client, date);
            sleep.metrics.forEach((m) => out.push(m));
        } catch (e) {
            noteAuthFailure(e);
            log('w', '睡眠采集失败（' + date + '）：' + describeError(e));
        }

        // 按 cloudKey 分组复用网络请求：血压的收缩压/舒张压来自同一份数据
        const groups = groupBy(SAMPLED, (s) => s.cloudKey);
        for (let i = 0; i < groups.length; i++) {
            const g = groups[i];
            try {
                const metrics = await collectSampled(client, g.key, g.items, start, end, date);
                metrics.forEach((m) => out.push(m));
            } catch (e) {
                noteAuthFailure(e);
                log('w', g.key + ' 采集失败（' + date + '）：' + describeError(e));
            }
        }

        // 每日目标达成（四个环）：走聚合端点，失败只记日志不影响其它指标
        try {
            const goals = await collectDailyGoals(client, date);
            goals.forEach((m) => out.push(m));
        } catch (e) {
            noteAuthFailure(e);
            log('w', '每日目标采集失败（' + date + '）：' + describeError(e));
        }

        // 只要有一个通道报凭据失效，就整轮按 authRequired 上报。
        // 此时已取到的数据仍然带回去（部分成功总比全丢好），
        // 但必须让用户知道需要重新登录，否则 0 条数据无从解释。
        if (authFailure) {
            setCredsRejected(true);
            log('w', '凭据已失效，本轮采集不完整，需重新登录：' + authFailure);
            return {
                status: 'authRequired',
                message: '登录已失效（小米接口返回 401 且无法自动续期），请重新登录小米账号',
            };
        }

        // 整轮都成功：服务端认可这套凭据，清掉之前记录的失效标记
        setCredsRejected(false);
        return out;
    } catch (e) {
        // 兜底：fetch 抛异常会被宿主当成「插件未实现」，宁可返回结构化失败
        log('e', 'fetch failed: ' + describeError(e));
        return { status: 'failure', message: '采集失败：' + describeError(e) };
    }
}

/** 按 key 分组并保持首次出现顺序 */
function groupBy<T>(
    items: T[],
    keyOf: (item: T) => string,
): Array<{ key: string; items: T[] }> {
    const order: string[] = [];
    const map: Record<string, T[]> = {};
    items.forEach((it) => {
        const k = keyOf(it);
        if (!map[k]) {
            map[k] = [];
            order.push(k);
        }
        map[k].push(it);
    });
    return order.map((k) => ({ key: k, items: map[k] }));
}

// ---------- 契约：健康度 ----------

export async function health(): Promise<PluginHealth> {
    try {
        const creds = loadCredentials();
        if (!isValidCreds(creds)) {
            return { status: 'broken', message: '未登录小米账号' };
        }

        // 用最近一天的数据探一次，能拿到就算健康
        const client = clientFor(creds);
        const now = Date.now();
        try {
            const points = await client.getFitnessDataByTime('steps', now - DAY_MILLIS, now, 1);
            if (points && points.length >= 0) {
                setCredsRejected(false);
                return { status: 'healthy' };
            }
        } catch (e) {
            log('w', 'health probe failed: ' + describeError(e));
            // 探活本身就是一次真实请求：它得到的 401 比「本地还有 passToken」
            // 更能说明凭据状态，直接据此给出「需要重新登录」
            if (isAuthError(e)) {
                setCredsRejected(true);
                return { status: 'broken', message: '登录已失效，请重新登录小米账号' };
            }
        }

        // 凭据过期且续期失败：这不是网络抖动，用户必须重新登录
        if (!isValidCreds(loadCredentials())) {
            return { status: 'broken', message: '登录已失效，请重新登录' };
        }
        if (isCredsRejected()) {
            return { status: 'broken', message: '登录已失效，请重新登录小米账号' };
        }
        return { status: 'degraded', message: '云端接口暂时不可用' };
    } catch (e) {
        log('e', 'health failed: ' + describeError(e));
        return { status: 'degraded', message: '健康检查异常：' + describeError(e) };
    }
}

// ---------- 契约：详情页 ----------

/**
 * 详情页内容：登录状态 + 步数 + 睡眠 + 两个按钮。
 *
 * 这里**不发网络请求**：详情页可能被频繁打开，每次进来都拉一次接口
 * 既慢又浪费。要更新数据由用户点「立即同步」，走 triggerSync。
 */
export async function detailUi(): Promise<UiNode[]> {
    const blocks: UiNode[] = [];
    try {
        const auth = await authState();

        let label = '未登录';
        let tone: Tone = 'error';
        let detail = '请回插件列表页登录';
        if (auth.state === 'loggedIn') {
            label = '已登录';
            tone = 'ok';
            detail = auth.accountName || '';
        } else if (auth.state === 'expired') {
            label = '登录即将过期';
            tone = 'warn';
            detail = auth.message || '';
        }

        blocks.push({ type: 'statusCard', label: '登录状态', value: label, tone, detail: detail });

        const recentSteps = recentValues(STEPS_KEY);
        const recentSleep = recentValues(SLEEP_KEY);

        if (recentSteps.length > 0) {
            const last = recentSteps[recentSteps.length - 1];
            blocks.push({
                type: 'statusCard',
                label: '最近一次步数',
                value: Math.round(last.value) + ' 步',
                tone: 'neutral',
                detail: last.date,
            });
            blocks.push({
                type: 'chart',
                title: '近 ' + recentSteps.length + ' 日步数',
                chart: {
                    kind: 'bar',
                    bars: recentSteps.map((r) => ({ label: r.date.slice(-5), value: r.value })),
                    unit: ' 步',
                    // 最后一根通常是今天，高亮它让用户一眼找到当前位置
                    highlightIndex: recentSteps.length - 1,
                },
            });
        } else {
            blocks.push({
                type: 'text',
                title: '还没有步数数据',
                body: '点下面的「立即同步」拉取今天的数据。',
            });
        }

        if (recentSleep.length > 0) {
            const last = recentSleep[recentSleep.length - 1];
            blocks.push({
                type: 'statusCard',
                label: '最近一次睡眠',
                value: (last.value / 60).toFixed(1) + ' 小时',
                tone: 'neutral',
                detail: last.date,
            });
            blocks.push({
                type: 'chart',
                title: '近 ' + recentSleep.length + ' 日睡眠',
                chart: {
                    kind: 'line',
                    // 库里存的是分钟，图上换成小时才符合日常表达
                    points: recentSleep.map((r) => ({ label: r.date.slice(-5), value: r.value / 60 })),
                    unit: ' 小时',
                },
            });
            blocks.push({
                type: 'text',
                title: '统计口径',
                body:
                    '按醒来日统计整夜睡眠：昨晚 22:43 睡到今早 07:58 算今天这一条；' +
                    '同一夜的多条记录（手机算法 + 手环汇总）已合并，不会重复累加。',
            });

            // 睡眠细节：有数据才展示，没数据不占版面
            const deepRatio = recentValues(SLEEP_DEEP_RATIO_KEY);
            const efficiency = recentValues(SLEEP_EFFICIENCY_KEY);
            const regularity = recentValues(SLEEP_REGULARITY_KEY);
            const detailCards: UiNode[] = [];
            if (deepRatio.length > 0) {
                detailCards.push({
                    type: 'statusCard',
                    label: '深睡占比',
                    value: deepRatio[deepRatio.length - 1].value.toFixed(1) + ' %',
                    tone: 'neutral',
                    detail: deepRatio[deepRatio.length - 1].date,
                });
            }
            if (efficiency.length > 0) {
                detailCards.push({
                    type: 'statusCard',
                    label: '睡眠效率',
                    value: efficiency[efficiency.length - 1].value.toFixed(1) + ' %',
                    tone: 'neutral',
                    detail: efficiency[efficiency.length - 1].date,
                });
            }
            if (regularity.length > 0) {
                detailCards.push({
                    type: 'statusCard',
                    label: '入睡规律性',
                    value: Math.round(regularity[regularity.length - 1].value) + ' 分',
                    tone: 'neutral',
                    detail: regularity[regularity.length - 1].date,
                });
            }
            detailCards.forEach((b) => blocks.push(b));
        } else {
            blocks.push({
                type: 'text',
                title: '还没有睡眠数据',
                body: '戴手环睡觉后点「立即同步」，睡眠才会被同步过来。',
            });
        }

        const loggedIn = auth.state === 'loggedIn';
        // 一排按钮：不套卡片（row 本来就不套），与旧 buttonRow 的视觉一致
        blocks.push({
            type: 'row',
            children: [
                {
                    type: 'button',
                    label: '立即同步',
                    actionId: ACTION_SYNC,
                    primary: true,
                    // 没登录时同步必然失败，直接禁用比让用户点了再看报错好
                    enabled: loggedIn,
                },
                {
                    type: 'button',
                    label: '回补近 ' + BACKFILL_DAYS + ' 天',
                    actionId: ACTION_BACKFILL,
                    // 回补只补原始条目（供查看核对），不改历史聚合值与得分
                    enabled: loggedIn,
                },
            ],
        });

        return blocks;
    } catch (e) {
        log('e', 'detailUi failed: ' + describeError(e));
        // 详情页崩掉会让整个插件页空白，退化成一条说明更好
        return blocks.concat([{ type: 'text', title: '详情页渲染失败', body: describeError(e) }]);
    }
}

/** 读近 N 天的已落库指标 */
function recentValues(metricKey: string): Array<{ date: string; value: number }> {
    try {
        const rows = ctx.recentMetrics(metricKey, DAYS);
        if (!rows || rows.length === 0) return [];
        const arr = rows.map((r) => ({ date: String(r.date || ''), value: Number(r.value) || 0 }));
        return arr.slice(-DAYS);
    } catch (e) {
        log('w', 'recentMetrics(' + metricKey + ') failed: ' + describeError(e));
        return [];
    }
}

// ---------- 契约：按钮动作 ----------

export async function onAction(actionId: string): Promise<ActionResult> {
    try {
        if (actionId === ACTION_SYNC) {
            // 交给宿主跑采集链路：插件不碰数据库，否则健康度与量纲校验都会被绕过
            return { type: 'triggerSync' };
        }
        if (actionId === ACTION_BACKFILL) {
            return await backfillHistory();
        }
        return { type: 'noop' };
    } catch (e) {
        log('e', 'onAction(' + actionId + ') failed: ' + describeError(e));
        return { type: 'showMessage', text: '操作失败：' + describeError(e) };
    }
}

/**
 * 回补最近 BACKFILL_DAYS 天的**原始条目**。
 *
 * 常规采集只跑「昨天 + 今天」，更早的日期从来没有原始条目。
 * 只补 raw 不补 metric：metric 是判定与计分用的聚合值，
 * 补历史会改动已定稿的历史得分，那不是用户点这个按钮的本意。
 *
 * 按天逐段拉而不是一次拉个大窗口：窗口越宽游标越容易退化成遍历全部历史。
 * 失败的那一天跳过并继续，一天拉不到不该让整轮失败。
 */
async function backfillHistory(): Promise<ActionResult> {
    const creds = loadCredentials();
    if (!isValidCreds(creds)) {
        return { type: 'showMessage', text: '未登录小米账号，请先在插件页登录' };
    }

    const client = clientFor(creds);
    let filled = 0;
    let empty = 0;
    let failed = 0;
    const today = new Date();

    for (let offset = 0; offset < BACKFILL_DAYS; offset++) {
        const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() - offset);
        const date = formatDate(d);
        const range = dayRange(date);
        if (!range) {
            failed++;
            continue;
        }
        try {
            const [start, end] = range;
            // 返回值是「有数据」的列表，「没数据」是空。
            // 不能用「没抛异常」当成功：服务端对没记录的日子正常返回空列表，
            // 那样填出来的天数会把「真的补到了」和「这天本来就没数据」混在一起，
            // 用户看到「已回补 30 天」但列表还是空的。
            const steps = await collectSteps(client, start, end, date);
            const sleep = await collectSleep(client, date);
            // 其余采样类指标也一并回补：只补步数/睡眠会让详情页的
            // 心率、静息心率、身体成分等指标在历史日期永远是空的
            let other = 0;
            const groups = groupBy(SAMPLED, (s) => s.cloudKey);
            for (let i = 0; i < groups.length; i++) {
                const g = groups[i];
                try {
                    other += (await collectSampled(client, g.key, g.items, start, end, date)).length;
                } catch (e) {
                    log('w', '回补 ' + g.key + '（' + date + '）失败：' + describeError(e));
                }
            }
            // 每日目标达成也一并回补
            let goalCount = 0;
            try {
                goalCount = (await collectDailyGoals(client, date)).length;
            } catch (e) {
                log('w', '回补每日目标（' + date + '）失败：' + describeError(e));
            }
            if (steps.length > 0 || sleep.hasData || other > 0 || goalCount > 0) {
                filled++;
            } else {
                empty++;
            }
        } catch (e) {
            log('w', '回补 ' + date + ' 失败：' + describeError(e));
            failed++;
        }
    }

    // 三种结果分开说：全成功 / 部分失败 / 一天都没取到。
    // 「没数据」也要明确告知，否则用户会以为回补失败
    if (failed === 0 && empty === 0) {
        return { type: 'showMessage', text: '已回补最近 ' + filled + ' 天的原始数据' };
    }
    if (filled === 0 && failed === 0) {
        return {
            type: 'showMessage',
            text: '最近 ' + BACKFILL_DAYS + ' 天都没有找到可回补的记录',
        };
    }
    const parts = ['已回补 ' + filled + ' 天'];
    if (empty > 0) parts.push(empty + ' 天无记录');
    if (failed > 0) parts.push(failed + ' 天取数失败');
    return { type: 'showMessage', text: parts.join('，') };
}

// ---------- 登录后续步骤 ----------

function md5Upper(text: string): string {
    // 单次 MD5 转大写。ctx.crypto.md5Hex 已返回小写十六进制
    return String(ctx.crypto.md5Hex(toUtf8(text))).toUpperCase();
}

/** 复用 util.ts 的编码，但这里只为一处服务，就地内联避免多一层 import */
function toUtf8(str: string): number[] {
    const out: number[] = [];
    for (let i = 0; i < str.length; i++) {
        let code = str.charCodeAt(i);
        if (code >= 0xd800 && code <= 0xdbff && i + 1 < str.length) {
            const low = str.charCodeAt(i + 1);
            if (low >= 0xdc00 && low <= 0xdfff) {
                code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00);
                i++;
            }
        }
        if (code < 0x80) out.push(code);
        else if (code < 0x800) out.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
        else if (code < 0x10000) out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
        else out.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 0x3f), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    }
    return out;
}

/**
 * 是否拿到了一套**会话自己**的 ssecurity。
 *
 * 参考实现（external/mihealth-api/login.py）的判据就是这个：
 * ```python
 * if not (isinstance(j2.get("ssecurity"), str) and len(j2["ssecurity"]) > 4):
 *     if j2.get("notificationUrl"): raise Need2FA(...)
 *     raise LoginError(...)
 * ```
 * 长度门槛 >4 是为了挡掉 `null`/`""`/占位值。
 *
 * 这是判断「密码登录这一步到底成没成」的唯一依据，**不能用 code 代替**：
 * auth2 返回 code=0 但没有 ssecurity 是常态（参考实现实测记录在 out.txt：
 * `auth2 code: 0 ssecurity: False`）。用 code 判定会把这种响应当成登录成功，
 * 落盘一个空或过期的 ssecurity，然后所有数据接口 401——
 * 用户看到「已登录但没数据」，而根因在登录这一步就已经埋下了。
 */
function hasUsableSsecurity(auth2: any): boolean {
    const v = auth2 && auth2.ssecurity;
    return typeof v === 'string' && v.length > 4;
}

/** 把 auth2 的响应翻译成契约结果 */
async function handleAuth2(auth2: any, account: string, epoch: number): Promise<LoginResult> {
    if (loginCancelled(epoch)) return cancelledLogin();
    if (!auth2) {
        resetPendingLogin();
        return { type: 'failure', message: '登录请求失败，请检查网络' };
    }

    const code = typeof auth2.code === 'number' ? auth2.code : -1;

    // 把 auth2 的关键字段一次打全。此前只打 code/notificationUrl/captchaUrl，
    // 恰好漏掉了决定成败的 ssecurity 与 location，导致「登录成功却 401」无从定位
    log(
        'i',
        'serviceLoginAuth2 解析：code=' + code +
            ' ssecurity=' + (hasUsableSsecurity(auth2) ? 'len' + String(auth2.ssecurity).length : '(空或无效)') +
            ' cUserId=' + (auth2.cUserId ? 'len' + String(auth2.cUserId).length : '(空)') +
            ' passToken=' + (auth2.passToken ? 'len' + String(auth2.passToken).length : '(空)') +
            ' 有 location=' + !!auth2.location +
            ' 有 notificationUrl=' + !!auth2.notificationUrl,
    );

    // 拿到会话自己的 ssecurity 才算密码登录这一步真的成了，
    // 此时 notificationUrl 即便有也不该再走 2FA（参考实现同序）
    if (hasUsableSsecurity(auth2)) {
        log('i', 'auth2 已带 ssecurity，走 STS 回调');
        return await completeLogin(auth2, account, epoch);
    }

    // 没有 ssecurity：要么需要二次验证，要么就是登录没成
    const notification = auth2.notificationUrl || '';
    if (notification) {
        log('i', '没有 ssecurity 且有 notificationUrl，进入 2FA 流程');
        return await start2FA(notification, auth2.ssecurity || '', account, epoch);
    }

    if (code === 0) {
        // 走到这里说明：服务端说成功，却没给可用的 ssecurity，
        // 也没告诉我们要二次验证。继续往下走必然得到一个用不了的凭据，
        // 不如在这里就明确失败——这正是「显示已登录、数据全空」的来源
        log('e', 'auth2 code=0 但缺少 ssecurity，且无 notificationUrl，无法继续');
        resetPendingLogin();
        return {
            type: 'failure',
            message: '登录未完成：服务端返回成功但没有下发会话密钥（ssecurity）。' +
                '请确认该账号已在小米运动健康官方 App 中完成过验证，然后重试。',
        };
    }

    // 需要图片验证码：87001 = 验证码错误/缺失，70016 = 参数被拒
    //（小米在要求验证码时也用这个码）
    if (code === 87001 || code === 70016 || auth2.captchaUrl) {
        log('i', '需要图片验证码，code=' + code);
        pendingLogin!.stage = 'captcha';
        // 阶段已变化：立刻落盘。正确性不能依赖 onUnload（宿主明确不保证调用）
        savePendingLoginSnapshot();
        // 复用同一个 session：验证码与那次会话的 cookie（ick）绑定
        const pending = pendingLogin!;
        const image = await fetchCaptcha(pending.session);
        if (loginCancelled(epoch)) return cancelledLogin();
        return needCaptcha(pending, image);
    }

    log('w', '登录被拒：code=' + code + ' desc=' + (auth2.desc || ''));
    resetPendingLogin();
    return { type: 'failure', message: friendlyError(code, auth2.desc || '') };
}

async function needCaptcha(pending: PendingLogin, imageOverride?: string): Promise<LoginResult> {
    const epoch = captureEpoch();
    const image = imageOverride != null ? imageOverride : await fetchCaptcha(pending.session);
    if (loginCancelled(epoch)) return cancelledLogin();
    return {
        type: 'needInput',
        prompt: '需要输入图片验证码',
        // 验证码不掩码：用户要照着图抄，掩码只会让他抄错。
        // 掩码该留给密码这种长期秘密
        fields: [{ name: SLOT_CAPTCHA, displayName: '验证码', secret: false }],
        captchaImage: image || undefined,
    };
}

function needOtp(hint?: string): LoginResult {
    return {
        type: 'needInput',
        prompt: hint ? '验证码已发送至 ' + hint : '需要二次验证码，请查看手机短信或邮箱',
        fields: [{ name: SLOT_OTP, displayName: '验证码', secret: false, hint: '6 位数字' }],
    };
}

/** 提交图片验证码：往那份已算好的表单里补一个 captCode，不重走握手 */
async function submitCaptcha(code: string): Promise<LoginResult> {
    const epoch = captureEpoch();
    const pending = pendingLogin!;
    log('i', '提交图片验证码');
    const auth2 = await serviceLoginAuth2(
        pending.session,
        pending.account,
        pending.passwordHash,
        pending.handshake,
        code,
    );
    return await handleAuth2(auth2, pending.account, epoch);
}

/**
 * ③ 走 STS 回调拿 serviceToken
 *
 * @param epoch 进入本次登录轮次时的会话代数，透传给 [finishLogin] 做落盘前校验。
 *   必须显式传入：它是 [handleAuth2] 的局部变量，本函数看不到外层作用域。
 */
async function completeLogin(auth2: any, account: string, epoch: number): Promise<LoginResult> {
    if (loginCancelled(epoch)) return cancelledLogin();
    const loc = auth2.location || '';
    if (!loc) {
        log('e', 'passport 返回成功但没有 location，无法换 serviceToken');
        resetPendingLogin();
        return { type: 'failure', message: '登录成功但缺少 STS 回调地址' };
    }

    const pending = pendingLogin!;
    // STS 这一步不能跟随跳转：serviceToken 是在该跳的 Set-Cookie 里下发的
    const token = await fetchServiceToken(pending.session, loc, pending.userId || '');
    if (loginCancelled(epoch)) return cancelledLogin();
    log('i', 'STS 回调完成，拿到 serviceToken=' + !!token);
    if (!token) {
        // STS 的 Set-Cookie 已经落在 session.jar 里了，再兜一次
        const fromJar = usableServiceToken(
            pending.session.cookie('serviceToken', 'sts-hlth.io.mi.com') ||
            pending.session.cookie('serviceToken', 'sts.api.io.mi.com'),
        );
        if (!fromJar) {
            log('e', 'STS 回调未拿到 serviceToken，可能被风控拦截');
            resetPendingLogin();
            return { type: 'failure', message: '未拿到 serviceToken，可能被风控拦截' };
        }
        return finishLogin(auth2, fromJar, account, epoch);
    }
    return finishLogin(auth2, token, account, epoch);
}

/**
 * 只保留可用的 serviceToken 值。
 *
 * `EXPIRED` 是过期标记；`miothealth` 是 `.hlth.io.mi.com` 上名为 serviceToken
 * 的 sid 标记（参考实现 API.md 明确警告它不是令牌）。按 cookie 名字取值时
 * 很容易先命中这两个，带上去请求必然 401。
 */
function usableServiceToken(value: string | null): string {
    if (!value || value === 'EXPIRED' || value === SID) return '';
    return value;
}

/**
 * 落盘前的可用性探测。
 *
 * 「能换到 serviceToken」不等于「这对凭据能用」：ssecurity 每次会话都会变，
 * 必须与同次会话的 token 配套，拿错配的一对去请求数据接口会稳定 401。
 * 只校验字段非空就报「登录成功」，用户看到的就是「显示已登录、数据全空」。
 *
 * 所以落盘前真的打一次数据接口。数据接口在 HTTP 失败、空响应或解密失败时
 * 会抛出异常，避免把 401 误判为无数据；0 条记录则属于探测通过。
 *
 * @returns 可用返回空串；不可用返回给用户看的原因
 */
async function probeCredentials(creds: Creds): Promise<string> {
    const client = clientFor(creds);
    const now = Date.now();
    try {
        // 只取一页，窗口很短：探测要快、失败要早。
        // 只要请求没被拒，0 条也算通过——那说明鉴权与解密都是通的
        const points = await client.getFitnessDataByTime('steps', now - DAY_MILLIS, now, 1);
        log('i', '凭据探测通过，采样点 ' + (points ? points.length : 0) + ' 个');
        return '';
    } catch (e) {
        log('w', '凭据探测异常：' + describeError(e));
        return describeError(e);
    }
}

async function finishLogin(
    auth2: any,
    serviceToken: string,
    account: string,
    epoch: number,
): Promise<LoginResult> {
    // cUserId / serviceToken 都要能从会话 cookie 兜底。
    //
    // 为什么不能只信 auth2：首次密码登录时 auth2 往往只给 ssecurity 与 location，
    // cUserId 是 STS 那一跳的 Set-Cookie 里才下发的（实测 auth2.cUserId 为 null）。
    // 而数据接口要求 `Cookie: cUserId=...; serviceToken=...`，
    // cUserId 为空会稳定 401——这正是「登录显示成功、数据全空」的另一种成因。
    if (loginCancelled(epoch)) return cancelledLogin();
    const session = pendingLogin && pendingLogin.session;
    const fromJar = (name: string): string => {
        try {
            return (session && session.cookie(name)) || '';
        } catch (e) {
            return '';
        }
    };

    const creds: Creds = {
        ssecurity: auth2.ssecurity || '',
        serviceToken: serviceToken || usableServiceToken(fromJar('serviceToken')),
        cUserId: auth2.cUserId || fromJar('cUserId'),
        userId: auth2.userId ? String(auth2.userId) : fromJar('userId'),
        passToken: auth2.passToken || fromJar('passToken'),
        account,
    };
    if (!creds.ssecurity) {
        log('e', '缺少 ssecurity，无法派生 sessionKey');
        resetPendingLogin();
        return { type: 'failure', message: '登录已通过但缺少 ssecurity，无法解密数据接口' };
    }
    if (!creds.cUserId) {
        // 宁可在这一步明确失败，也不要拿着空 cUserId 去请求、再让用户看一堆 401
        log('e', '缺少 cUserId：auth2 与会话 cookie 都没有');
        resetPendingLogin();
        return {
            type: 'failure',
            message: '登录已通过但没有拿到用户标识（cUserId），无法访问数据接口。请重试登录。',
        };
    }
    if (epoch !== sessionEpoch) {
        // 期间用户登出：不落盘，也不报成功
        log('w', '登录期间流程已取消，凭据不落盘');
        return cancelledLogin();
    }

    log(
        'i',
        '准备落盘：ssecurity=len' + creds.ssecurity.length +
            ' serviceToken=len' + creds.serviceToken.length +
            ' cUserId=len' + creds.cUserId.length +
            ' passToken=len' + creds.passToken.length,
    );

    // 落盘前先验证这对凭据真的能取数，避免「登录成功但永远没数据」
    const probeError = await probeCredentials(creds);
    if (loginCancelled(epoch)) return cancelledLogin();
    if (probeError) {
        resetPendingLogin();
        return {
            type: 'failure',
            message: '登录已通过，但用这套凭据访问小米健康数据接口被拒绝（' + probeError + '）。' +
                '这通常意味着账号需要在小米运动健康官方 App 里重新授权一次，然后回来重试登录。',
        };
    }

    saveCredentials(creds);
    resetPendingLogin();
    log('i', '登录成功，凭据已保存，account=' + maskAccount(account));
    return { type: 'success' };
}

/**
 * 触发二次验证码下发。
 *
 * `flag` 语义：**4 = 手机短信，8 = 邮箱**。必须按 flag 选端点，
 * 写死邮箱会得到 `{code:2, flag:4}`（要求改用手机）。
 */
async function start2FA(
    notificationUrl: string,
    auth2Ssecurity: string,
    account: string,
    epoch: number,
): Promise<LoginResult> {
    const pending = pendingLogin!;
    const abs = absoluteUrl(ACCOUNT, notificationUrl);

    const ctxParam = queryParamOf(abs, 'context');
    // identity 的 UA 要带真实 userId，参考实现从这里取
    const identityUserId = queryParamOf(abs, 'userId');

    // authStart 是 identity 会话的起点：它返回 302 跳到 identity 域，
    // identity 会话 cookie 是在那些跳转的 Set-Cookie 里种下的。
    //
    // 交给宿主的会话自动跟随即可：会话挂了 CookieJar，重定向每一跳都会
    // 自动带上并接收 cookie。这一步曾经必须手写逐跳，是因为当时宿主没有
    // CookieJar，OkHttp 跟随重定向会把 Cookie 头剔除，会话因此建不起来。
    try {
        const startRes = await pending.session.send('GET', abs, {
            userAgent: identityUa(deviceId(), identityUserId),
            headers: { Referer: ACCOUNT + '/' },
        });
        log('i', 'authStart 状态=' + (startRes && startRes.status));
    } catch (e) {
        log('w', 'start2FA: authStart failed: ' + describeError(e));
    }
    // authStart 也是网络往返：已取消就不必继续打 /list、发短信
    if (loginCancelled(epoch)) return cancelledLogin();

    const listUrl =
        IDENTITY +
        '/list?sid=' + encodeURIComponent(SID) +
        '&supportedMask=0&_locale=zh_CN' +
        '&context=' + encodeURIComponent(ctxParam);
    const listRes = await pending.session.send('GET', listUrl, {
        userAgent: identityUa(deviceId(), identityUserId),
        headers: { Referer: ACCOUNT + '/', 'Content-Type': 'application/x-www-form-urlencoded' },
    });
    if (loginCancelled(epoch)) return cancelledLogin();
    const listJson = parseMiapi(listRes.body);
    if (!listJson) {
        log('e', '2FA 的 /list 返回无法解析：' + String(listRes.body || '').slice(0, 200));
        resetPendingLogin();
        return { type: 'failure', message: '无法获取二次验证方式' };
    }
    const flag = typeof listJson.flag === 'number' ? listJson.flag : 4;
    const method = flag === 8 ? 'Email' : 'Phone';
    log('i', '2FA 方式：flag=' + flag + ' method=' + method + ' context=' + (ctxParam || '(空)'));

    // 触发下发
    //
    // 这两步的分工在旧实现里就定死了，不能合并：
    // - verifyPhone/verifyEmail：GET，参数走 query，作用是让服务端把这次会话
    //   标记成「正在走某个验证方式」，不带上它 sendPhoneTicket 会因会话未就绪而失败
    // - sendPhoneTicket：POST，**参数必须在 body 里**（retry/icode/_json）
    //
    // 早先版本把 sendPhoneTicket 的参数全塞进 query、body 留空，
    // 服务端拿不到 _json=true 就不会真发短信，插件这边只看到一次 200，误以为发成功了。
    //
    // `_dc` 必须带且必须每次不同：GET 且路径完全相同，中间层会按 URL 缓存，
    // 缓存命中时会直接跳转到 `/auth/verifyPhone?...&userid=0` 这种缺参数的形式，
    // 下面是 `{"code":10012,"desc":"非法请求"}`，且不发短信。
    // 日志里 10:34 与 10:54 两次都是 10012，就是这个原因。
    const verifyUrl =
        IDENTITY + '/auth/verify' + method +
        '?_dc=' + Date.now() +
        '&_flag=' + flag + '&_json=true' +
        '&sid=' + encodeURIComponent(SID) +
        '&context=' + encodeURIComponent(ctxParam || '');
    const verifyRes = await pending.session.send('GET', verifyUrl, {
        userAgent: identityUa(deviceId(), identityUserId),
        headers: { Referer: ACCOUNT + '/' },
        // identity 端点偶发挂住（日志里有过等 37 秒才返回超时的情况），
        // 默认 30s 叠加重试会拖到 60s 以上，直接吃掉宿主的单次调用预算
        timeoutMs: IDENTITY_TIMEOUT_MS,
    });
    log(
        'i',
        'verify' + method + ' 状态=' + (verifyRes && verifyRes.status) +
            ' body=' + String((verifyRes && verifyRes.body) || '').replace(/^&&&START&&&/, '').slice(0, 200),
    );
    // 已取消就别再发短信：用户已经重开/登出，发出去的码只会让人困惑
    if (loginCancelled(epoch)) return cancelledLogin();

    if (method === 'Phone') {
        const sendUrl = IDENTITY + '/auth/sendPhoneTicket';
        const sendRes = await pending.session.send('POST', sendUrl, {
            userAgent: identityUa(deviceId(), identityUserId),
            headers: { Referer: ACCOUNT + '/', 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'retry=0&icode=&_json=true',
            contentType: 'application/x-www-form-urlencoded',
            timeoutMs: IDENTITY_TIMEOUT_MS,
        });
        // 这一步的返回值是判断「短信到底发没发」的唯一依据：
        // 200 不代表发出去了，必须看 body 里的 code 与 desc
        log(
            'i',
            'sendPhoneTicket 状态=' + (sendRes && sendRes.status) +
                ' body=' + String((sendRes && sendRes.body) || '').replace(/^&&&START&&&/, '').slice(0, 300),
        );
    }

    if (loginCancelled(epoch)) return cancelledLogin();
    const hint = ['mask', 'notification', 'email', 'phone', 'address']
        .map((k) => listJson[k])
        .find((v) => typeof v === 'string' && v !== '') || '';

    pendingLogin = {
        stage: 'twoFactor',
        session: pending.session,
        account: pending.account,
        passwordHash: pending.passwordHash,
        handshake: pending.handshake,
        userId: identityUserId,
        context: ctxParam,
        flag,
        method,
        hint,
        auth2Ssecurity,
    };

    // 进入二次验证：立刻落盘，保证被回收后还能接着填验证码
    savePendingLoginSnapshot();

    return needOtp(hint);
}

/** 提交二次验证码并在已认证会话里把凭据凑齐 */
async function submit2FA(code: string): Promise<LoginResult> {
    const pending = pendingLogin!;
    const identityUserId = pending.userId || '';

    // 记下进入时的会话代数：2FA 链路里有多轮网络往返，
    // 期间用户完全可能点了登出。写回凭据前必须比对代数，否则会把刚清掉的凭据装回去。
    const epoch = captureEpoch();

    // 提交验证码。location 有三个来源：JSON 字段 → 响应头 → 响应原文，
    // 只读 JSON 会把「验证码通过但没拿到跳转地址」误判成失败
    const verifyUrl =
        IDENTITY + '/auth/verify' + pending.method +
        '?_dc=' + Date.now() +
        '&_flag=' + pending.flag +
        '&_json=true&sid=' + encodeURIComponent(SID) +
        '&context=' + encodeURIComponent(pending.context || '');
    const res = await pending.session.send('POST', verifyUrl, {
        userAgent: identityUa(deviceId(), identityUserId),
        headers: { Referer: ACCOUNT + '/', 'Content-Type': 'application/x-www-form-urlencoded' },
        body:
            '_flag=' + pending.flag +
            '&ticket=' + encodeURIComponent(String(code).trim()) +
            '&trust=false&_json=true',
        contentType: 'application/x-www-form-urlencoded',
        followRedirects: false,
    });

    const json = parseMiapi(res.body);
    const vcode = json && typeof json.code === 'number' ? json.code : 0;
    let loc = (json && json.location) || (res.headers && res.headers['location']) || '';
    if (!loc) {
        const m = String(res.body || '').match(/location\s*[:=]\s*["']([^"']+)/);
        if (m) loc = m[1];
    }
    log('i', '提交 2FA 验证码：code=' + vcode + ' 有 location=' + !!loc);

    if (vcode !== 0 && !loc) {
        return {
            type: 'failure',
            message: '验证码校验未通过（code=' + vcode + ' ' + ((json && json.desc) || '') + '）',
        };
    }
    if (!loc) {
        return { type: 'failure', message: '验证码通过但未拿到跳转地址' };
    }
    if (loginCancelled(epoch)) return cancelledLogin();

    // 访问 location 建立已认证会话，**整条跳转链必须走完**。
    //
    // 认证 cookie（passToken / cUserId / userId / passInfo）是在中间某一跳的
    // Set-Cookie 里种下的，最后一跳才给出 serviceToken。少走一跳就少一份 cookie。
    //
    // 这里照搬旧 Kotlin 版 `followRedirectsManually` 的做法：关掉宿主自动跟随，
    // 自己逐跳 GET，每跳都用 identity UA。不要「聪明地」在 STS 前停下——
    // 那样会漏掉最后一跳下发的 serviceToken。
    //
    // 每跳必须把响应体读完再继续。宿主已经读完了（responseToJson 取的是字节），
    // 这一点和 OkHttp 手工调用时「不读完 body 会中断连接」的坑是同一件事。
    try {
        let current = absoluteUrl(ACCOUNT, loc);
        for (let hop = 0; hop < 10; hop++) {
            const r = await pending.session.send('GET', current, {
                userAgent: identityUa(deviceId(), identityUserId),
                followRedirects: false,
            });
            const status = (r && r.status) || 0;
            const next = (r && r.headers && r.headers['location']) || '';
            log(
                'd',
                '认证跳转 hop' + hop + ' http=' + status +
                    ' setCookie=' + ((r && r.setCookie) || []).length +
                    ' next=' + (next ? next.slice(0, 80) : '-'),
            );
            if (status < 300 || status >= 400 || !next) break;

            // 按 RFC 3986 以**当前这一跳**为基准解析相对地址。
            // 之前这里写成 absoluteUrl(ACCOUNT, next)，把站点根当基准，
            // 一旦服务端给的是 `serviceLoginAuth2/end` 这类相对路径就会跳错，
            // 跳错后服务端又回同一个相对值，于是打成死循环、一个 cookie 都收不到。
            const resolved = absoluteUrl(current, next);
            if (resolved === current) {
                log('w', '认证跳转原地返回，停止：' + shortUrl(current));
                break;
            }
            current = resolved;
        }
    } catch (e) {
        log('w', 'submit2FA: visit location failed: ' + describeError(e));
    }
    // 跳转链走完才发现已取消：后面的重做与续期都失去意义，立即返回
    if (loginCancelled(epoch)) return cancelledLogin();

    // 2FA 之后是否真的有登录态，全看这几个 cookie。
    // 缺 passToken 就只剩「重做 serviceLogin」一条路，而那条路实测常返回没有 ssecurity，
    // 于是最终报「缺少 ssecurity」。把 cookie 名字直接打出来，比事后猜要省事得多
    log('i', '2FA 后会话 cookie=[' + pending.session.names().join(',') + ']');

    const jar = { value: (n: string, domain?: string) => pending.session.cookie(n, domain) };
    let cUserId = jar.value('cUserId') || '';
    let passToken = jar.value('passToken') || '';
    let userId = jar.value('userId') || '';
    let serviceToken = jar.value('serviceToken', 'sts-hlth.io.mi.com') ||
        jar.value('serviceToken', 'sts.api.io.mi.com') || '';
    // `.hlth.io.mi.com` 上有个同名 cookie 存的是 sid 标记（miothealth），
    // 它不是真令牌；`EXPIRED` 同理。按名字取值很容易命中它们，
    // 带上去请求数据接口必然 401，所以在这里就判空
    if (serviceToken === SID || serviceToken === 'EXPIRED') {
        log('w', '会话里的 serviceToken 是标记值（' + serviceToken + '），按空处理');
        serviceToken = '';
    }

    // ── 第 1 条路：重做 serviceLogin ──
    // 2FA 通过后会话已认证，再问一次就能拿到 ssecurity。
    //
    // 前提是**请求要带上 jar 里的整组 cookie**（尤其 passToken），
    // 见 API.md 第 ②a 步：「带 cookie {passToken, cUserId, userId} 重发
    // serviceLogin → 返回 ssecurity」。早期这里手工塞了一个 userId 头，
    // 反而把整组 cookie 覆盖掉了，于是稳定拿到不带 ssecurity 的响应。
    let ssecurity = '';
    try {
        const again = await serviceLoginRaw(pending.session);
        const againJson = again && again.json;
        // 把原始响应打全：这里正是判断「为什么拿不到 ssecurity」的唯一证据。
        // 之前截断到 400 字符，location 一长就把关键字段挤出去了
        log(
            'i',
            '重做 serviceLogin 原始响应：status=' + (again && again.status) +
                ' body=' + String((again && again.raw) || '').replace(/^&&&START&&&/, '').slice(0, 1200),
        );
        if (againJson) {
            // 只认 ssecurity。
            //
            // 2FA 之后服务端返回的字段名是 `psecurity`（实测账号 19730365244：
            // "Qq218KjztVL8nCULl6LUmg=="），它和 ssecurity 一样是 base64 16 字节，
            // 但**不能当会话密钥用**：拿它派生 sessionKey 去打数据接口会稳定 401
            // （日志实测：`重做 serviceLogin 取到会话密钥，字段=psecurity` 之后
            // 紧接着 `GET data/... -> 401`）。
            //
            // 真正可用的是这次响应里同时下发的 passToken —— 它能换到配套的
            // ssecurity + serviceToken，所以下面走 passToken 续期这条路。
            ssecurity = againJson.ssecurity || '';
            if (ssecurity) {
                log('i', '重做 serviceLogin 取到 ssecurity，长度=' + ssecurity.length);
            } else if (againJson.psecurity) {
                log('i', '重做响应只有 psecurity（不能当会话密钥用），改走 passToken 续期');
            }
            if (!cUserId) cUserId = againJson.cUserId || '';
            if (!passToken) passToken = againJson.passToken || '';
            if (!userId) userId = againJson.userId ? String(againJson.userId) : '';
            // ⚠️ 必须**无条件**用这次重做响应的 location 重新换 serviceToken。
            //
            // ssecurity 与 serviceToken 必须来自**同一次会话**：
            // sessionKey = SHA256(ssecurity + nonce)，服务端拿这对值验签，
            // 混用两次会话的凭据会稳定 401。
            //
            // 而 2FA 通过时的跳转链已经下发过一个 serviceToken，如果这里写成
            // 「没有才去换」（`if (!serviceToken)`），就会保留那个旧令牌，
            // 配上重做才拿到的 ssecurity，两者不配套 → 数据接口全 401。
            // 参考实现 login.py:finish_2fa 正是无条件重换，这里必须对齐。
            if (againJson.location) {
                const renewed = await fetchServiceToken(
                    pending.session, againJson.location, identityUserId,
                );
                if (renewed) {
                    log('i', '按重做响应的 location 重新换取 serviceToken，长度=' + renewed.length +
                        (serviceToken ? '（覆盖前长度=' + serviceToken.length + '）' : ''));
                    serviceToken = renewed;
                }
            }
        }
        const cookieNames = pending.session.names().join(',');
        log(
            'i',
            '重做 serviceLogin：code=' + (againJson && againJson.code) +
                ' 有 ssecurity=' + !!ssecurity +
                ' 有 passToken=' + !!passToken +
                ' passToken长度=' + passToken.length +
                ' 有 serviceToken=' + !!serviceToken +
                ' 会话 cookie=[' + cookieNames + ']',
        );
    } catch (e) {
        log('w', 'submit2FA: redo serviceLogin failed: ' + describeError(e));
    }

    // ── 第 2 条路：从 passInfo cookie 解析 ──
    // 服务端有时只把 ssecurity 塞进这个 cookie，响应体里没有
    if (!ssecurity) {
        const passInfoRaw = jar.value('passInfo');
        ssecurity = extractSsecurity(passInfoRaw);
        // passInfo 是「有没有」与「解不解析得出来」两件事，必须分开记：
        // cookie 不存在说明服务端压根没下发，存在却解不出说明解析规则要补
        log(
            'i',
            'passInfo 兜底：' +
                (passInfoRaw ? '有值(长度' + String(passInfoRaw).length + ')' : '不存在') +
                ' 解析出 ssecurity=' + (ssecurity ? 'len' + ssecurity.length : '否'),
        );
    }

    // ── 第 3 条路：用 passToken 独立换一套完整凭据 ──
    // 这条路是给「日常续期」用的，逻辑最简单也最稳，
    // 不依赖当前这个已经被 2FA 搅过的会话
    if (!ssecurity && passToken) {
        const refreshed = await refreshWithPassToken(passToken, cUserId, userId, pending.account);
        if (isValidCreds(refreshed)) {
            // 先判取消再清中间态：迟到的调用若已经过期，
            // 它连「新一轮登录的 pendingLogin」都不该碰
            if (loginCancelled(epoch)) return cancelledLogin();
            resetPendingLogin();
            // 与 finishLogin 同一口径：落盘前必须真的能取到数，
            // 否则又是一次「显示已登录、数据全空」
            const refreshProbeError = await probeCredentials(refreshed);
            if (refreshProbeError) {
                return {
                    type: 'failure',
                    message: '免密续期拿到的凭据无法访问数据接口（' + refreshProbeError + '），' +
                        '请在小米运动健康官方 App 中重新授权后再试。',
                };
            }
            // 探测也是一次网络往返：期间用户可能又点了登出或重开，
            // 落盘前必须再校验一次，否则会把刚清掉的凭据装回去
            if (loginCancelled(epoch)) return cancelledLogin();
            saveCredentials(refreshed);
            log('i', '经 passToken 续期登录成功，凭据已保存');
            return { type: 'success' };
        }
    }

    if (!ssecurity || !serviceToken) {
        // 迟到结果不能清掉新一轮的中间态
        if (!loginCancelled(epoch)) resetPendingLogin();
        // 拿不到 ssecurity 就派生不出 sessionKey，任何数据接口都会失败，
        // 必须让用户看到原因而不是静默返回空数据
        log(
            'e',
            '2FA 未能凑齐凭据：缺' + (ssecurity ? 'serviceToken' : 'ssecurity') +
                '（passToken长度=' + passToken.length +
                ' cUserId长度=' + cUserId.length +
                ' userId长度=' + userId.length + '）',
        );
        return {
            type: 'failure',
            message: '二次验证已通过，但未能换到可用的会话密钥（缺' +
                (ssecurity ? 'serviceToken' : 'ssecurity') + '）。' +
                '请确认该账号已在小米运动健康官方 App 中正常登录过，然后重试。',
        };
    }

    if (loginCancelled(epoch)) return cancelledLogin();
    resetPendingLogin();

    const twoFactorCreds: Creds = {
        ssecurity,
        serviceToken,
        cUserId,
        userId,
        passToken,
        account: pending.account,
    };

    // 与 finishLogin 同一口径：ssecurity 与 serviceToken 分别来自重做 serviceLogin
    // 和会话 cookie，两者是否配套只有真打一次接口才知道
    const twoFactorProbeError = await probeCredentials(twoFactorCreds);
    if (twoFactorProbeError) {
        return {
            type: 'failure',
            message: '二次验证已通过，但这套凭据访问数据接口被拒绝（' + twoFactorProbeError + '），' +
                '请在小米运动健康官方 App 中重新授权后再试。',
        };
    }
    // 探测期间可能被登出/重启：迟到的成功不能覆盖刚发生的新状态
    if (loginCancelled(epoch)) return cancelledLogin();

    saveCredentials(twoFactorCreds);
    log('i', '2FA 登录成功，凭据已保存，account=' + maskAccount(pending.account));
    return { type: 'success' };
}

/**
 * 从 `passInfo` cookie 里解析 `ssecurity`。
 *
 * 它是一个 URL-encoded 的 JSON，也可能是 base64 过的，三种形态都试一遍。
 */
function extractSsecurity(passInfo: string | null): string {
    if (!passInfo) return '';
    const candidates: string[] = [passInfo];
    try {
        candidates.push(decodeURIComponent(passInfo));
    } catch (e) {
        // 非法 percent 编码，跳过
    }
    try {
        // base64 解出来可能是二进制，只有在能当文本用时才有意义
        const bytes = b64decode(passInfo);
        if (bytes && bytes.length > 0) {
            let text = '';
            for (let i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i] & 0xff);
            if (/^[\x09\x0a\x0d\x20-\x7e]*$/.test(text)) candidates.push(text);
        }
    } catch (e) {
        // 不是合法 base64
    }

    for (let i = 0; i < candidates.length; i++) {
        const text = candidates[i];
        try {
            const o = JSON.parse(text);
            if (o && typeof o.ssecurity === 'string' && o.ssecurity) return o.ssecurity;
        } catch (e) {
            // 不是 JSON，继续用正则试
        }
        const m1 = text.match(/"ssecurity"\s*:\s*"([^"]+)"/);
        if (m1) return m1[1];
        // passInfo 也可能是 "ssecurity=xxx&cUserId=yyy" 这种 query 串
        const m2 = text.match(/(?:^|[&;\s])ssecurity=([^&;\s"']+)/);
        if (m2) {
            try {
                return decodeURIComponent(m2[1]);
            } catch (e) {
                return m2[1];
            }
        }
    }
    return '';
}

/** 取 URL 查询参数（不依赖 URLSearchParams，QuickJS 里不一定有） */
function queryParamOf(url: string, name: string): string {
    const idx = String(url).indexOf('?');
    if (idx < 0) return '';
    const pairs = String(url).slice(idx + 1).split('&');
    for (let i = 0; i < pairs.length; i++) {
        const eq = pairs[i].indexOf('=');
        const k = eq < 0 ? pairs[i] : pairs[i].slice(0, eq);
        if (k === name) {
            const v = eq < 0 ? '' : pairs[i].slice(eq + 1);
            try {
                return decodeURIComponent(v);
            } catch (e) {
                return v;
            }
        }
    }
    return '';
}

/** 错误码到用户可读文案。70016 看起来像风控，实际多是账号侧需要先在官方 App 验证 */
function friendlyError(code: number, desc: string): string {
    if (code === 70016) {
        return '登录验证失败（70016）：小米拒绝了本次请求，通常是账号触发了风控或需要先在官方 App 完成一次验证';
    }
    if (code === 70002) return '账号或密码错误';
    if (code === 70003) return '账号不存在';
    if (code === 87001) return '验证码输入错误，请重新填写';
    return '登录失败（' + code + '）：' + (desc || '未知错误');
}
