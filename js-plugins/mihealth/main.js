// mihealth/util.ts
function utf8Bytes(str) {
  const out = [];
  for (let i = 0; i < str.length; i++) {
    let code = str.charCodeAt(i);
    if (code >= 55296 && code <= 56319 && i + 1 < str.length) {
      const low = str.charCodeAt(i + 1);
      if (low >= 56320 && low <= 57343) {
        code = 65536 + (code - 55296 << 10) + (low - 56320);
        i++;
      }
    }
    if (code < 128) {
      out.push(code);
    } else if (code < 2048) {
      out.push(192 | code >> 6, 128 | code & 63);
    } else if (code < 65536) {
      out.push(224 | code >> 12, 128 | code >> 6 & 63, 128 | code & 63);
    } else {
      out.push(
        240 | code >> 18,
        128 | code >> 12 & 63,
        128 | code >> 6 & 63,
        128 | code & 63
      );
    }
  }
  return out;
}
function sha1Base64(bytes) {
  return ctx.crypto.sha1Base64(bytes);
}
function sha256BytesBase64(bytes) {
  return ctx.crypto.sha256Base64(bytes);
}
function b64encode(bytes) {
  return ctx.codec.base64Encode(bytes);
}
function b64decode(str) {
  return ctx.codec.base64Decode(str);
}
var randomCounter = 0;
function randomBytes(n) {
  const out = new Array(n);
  for (let i = 0; i < n; i++) {
    out[i] = Math.floor(Math.random() * 256);
  }
  randomCounter = (randomCounter + 1) % 256;
  if (n > 0) out[0] ^= randomCounter;
  return out;
}
function rc4(keyBytes) {
  const s = new Array(256);
  for (let i = 0; i < 256; i++) s[i] = i;
  let j = 0;
  for (let i = 0; i < 256; i++) {
    j = j + s[i] + keyBytes[i % keyBytes.length] & 255;
    const tmp = s[i];
    s[i] = s[j];
    s[j] = tmp;
  }
  let i2 = 0;
  let j2 = 0;
  for (let k = 0; k < 1024; k++) {
    i2 = i2 + 1 & 255;
    j2 = j2 + s[i2] & 255;
    const tmp = s[i2];
    s[i2] = s[j2];
    s[j2] = tmp;
  }
  return function transform(bytes) {
    const out = new Array(bytes.length);
    for (let n = 0; n < bytes.length; n++) {
      i2 = i2 + 1 & 255;
      j2 = j2 + s[i2] & 255;
      const tmp = s[i2];
      s[i2] = s[j2];
      s[j2] = tmp;
      out[n] = bytes[n] ^ s[s[i2] + s[j2] & 255];
    }
    return out;
  };
}
function asNumber(v) {
  if (typeof v === "number" && isFinite(v)) return v;
  if (typeof v === "string" && v.trim() !== "") {
    const n = Number(v);
    if (isFinite(n)) return n;
  }
  return null;
}
function asLong(v) {
  if (typeof v === "number" && isFinite(v)) return Math.trunc(v);
  if (typeof v === "string" && /^-?\d+$/.test(v.trim())) return parseInt(v.trim(), 10);
  return null;
}
function formEncode(params) {
  return Object.keys(params).map((k) => {
    const v = params[k];
    return encodeURIComponent(k) + "=" + encodeURIComponent(v == null ? "" : v);
  }).join("&");
}
function absoluteUrl(base, location) {
  const loc = String(location || "").trim();
  if (!loc) return base;
  if (/^https?:\/\//i.test(loc)) return loc;
  if (loc.startsWith("//")) return "https:" + loc;
  const m = String(base || "").match(/^https?:\/\/[^/?#]+/);
  const origin = m ? m[0] : "https://account.xiaomi.com";
  const basePath = String(base || "").slice(origin.length).replace(/[?#].*$/, "") || "/";
  if (loc.startsWith("/")) return origin + loc;
  if (loc.startsWith("?")) return origin + basePath + loc;
  const dir = basePath.slice(0, basePath.lastIndexOf("/") + 1) || "/";
  return origin + dir + loc;
}

// mihealth/crypto.ts
var ACCOUNT = "https://account.xiaomi.com";
var IDENTITY = "https://account.xiaomi.com/identity";
var SID = "miothealth";
var JSON_PREFIX = "&&&START&&&";
function nonce() {
  const bytes = randomBytes(8);
  const minutes = Math.floor(Date.now() / 6e4);
  bytes.push(minutes >>> 24 & 255);
  bytes.push(minutes >>> 16 & 255);
  bytes.push(minutes >>> 8 & 255);
  bytes.push(minutes & 255);
  return b64encode(bytes);
}
function sessionKey(ssecurity, nonceValue) {
  const a = b64decode(ssecurity);
  const b = b64decode(nonceValue);
  return sha256BytesBase64(a.concat(b));
}
function pathOf(url) {
  if (url.indexOf("://") >= 0) {
    const afterScheme = url.slice(url.indexOf("://") + 3);
    const slash = afterScheme.indexOf("/");
    const pathAndQuery = slash < 0 ? "/" : afterScheme.slice(slash);
    return pathAndQuery.split("?")[0];
  }
  return url.split("?")[0];
}
function signQuery(method, url, params, ssecurity) {
  const p = pathOf(url);
  const upper = method.toUpperCase();
  const nonceValue = nonce();
  const skey = sessionKey(ssecurity, nonceValue);
  const plain = {};
  Object.keys(params).forEach((k) => {
    const v = params[k];
    if (v == null) return;
    const s = String(v);
    if (s.trim() === "") return;
    plain[k] = s;
  });
  const sortedKeys = Object.keys(plain).sort();
  const hashSource = upper + "&" + p + "&" + sortedKeys.map((k) => k + "=" + plain[k]).join("&") + "&" + skey;
  const rc4Hash = sha1Base64(utf8Bytes(hashSource));
  plain["rc4_hash__"] = rc4Hash;
  const allKeys = Object.keys(plain).sort();
  const stream = rc4(b64decode(skey));
  const encrypted = {};
  allKeys.forEach((k) => {
    encrypted[k] = b64encode(stream(utf8Bytes(plain[k])));
  });
  const sigSource = upper + "&" + p + "&" + allKeys.map((k) => k + "=" + encrypted[k]).join("&") + "&" + skey;
  const signature = sha1Base64(utf8Bytes(sigSource));
  const parts = allKeys.map((k) => encodeURIComponent(k) + "=" + encodeURIComponent(encrypted[k]));
  parts.push("signature=" + encodeURIComponent(signature));
  parts.push("_nonce=" + encodeURIComponent(nonceValue));
  return { query: parts.join("&"), nonce: nonceValue };
}
function decryptBody(ssecurity, nonceValue, bodyB64) {
  const key = b64decode(sessionKey(ssecurity, nonceValue));
  const data = b64decode(bodyB64);
  const out = rc4(key)(data);
  return utf8Decode(out);
}
function utf8Decode(bytes) {
  let out = "";
  let i = 0;
  while (i < bytes.length) {
    const b0 = bytes[i++] & 255;
    let cp;
    if (b0 < 128) {
      cp = b0;
    } else if (b0 < 224) {
      cp = (b0 & 31) << 6 | bytes[i++] & 63;
    } else if (b0 < 240) {
      cp = (b0 & 15) << 12 | (bytes[i++] & 63) << 6 | bytes[i++] & 63;
    } else {
      cp = (b0 & 7) << 18 | (bytes[i++] & 63) << 12 | (bytes[i++] & 63) << 6 | bytes[i++] & 63;
    }
    out += String.fromCodePoint(cp);
  }
  return out;
}
function parseMiapi(body) {
  const text = String(body || "");
  const idx = text.indexOf(JSON_PREFIX);
  const clean = (idx >= 0 ? text.slice(idx + JSON_PREFIX.length) : text).trim();
  try {
    return JSON.parse(clean);
  } catch (e) {
    return null;
  }
}
function ua(deviceId2, userId) {
  return "MiFit/3.59.0 (Android 14; en_US) Resolution/1080x2400 Version/3.59.0 Stage/Production DeviceId/" + deviceId2 + " UserId/" + (userId || "0");
}
function identityUa(deviceId2, userId) {
  return "MiHome/11.3.203 (com.xiaomi.mihome; build:11.3.203; Android 9) APP/com.xiaomi.mihome APPV/11.3.203 DeviceId/" + deviceId2 + " UserId/" + (userId || "0") + " Platform/Android Region/CN L/zh_CN";
}

// mihealth/client.ts
var SID2 = "miothealth";
var MAX_WINDOW_MILLIS = 2 * 24 * 60 * 60 * 1e3;
function log(level, message) {
  try {
    ctx.log[level]("MiHealth", message);
  } catch (e) {
  }
}
function describeError(e) {
  if (e == null) return "\u672A\u77E5\u9519\u8BEF";
  if (typeof e === "string") return e || "\u672A\u77E5\u9519\u8BEF";
  if (typeof e === "object") {
    const msg = e.message;
    if (typeof msg === "string" && msg) return msg;
  }
  try {
    const s = String(e);
    if (s && s !== "[object Object]") return s;
  } catch (ignore) {
  }
  return "\u672A\u77E5\u9519\u8BEF";
}
function shortUrl(url) {
  const s = String(url || "");
  const noQuery = s.split("?")[0];
  const keep = ["sid", "_flag", "flag", "icodeType"];
  const params = [];
  const query = s.includes("?") ? s.slice(s.indexOf("?") + 1) : "";
  for (const pair of query.split("&")) {
    const key = pair.split("=")[0];
    if (keep.includes(key)) params.push(pair);
  }
  const tail = params.length > 0 ? "?" + params.join("&") : "";
  const parts = noQuery.split("/");
  return parts.slice(-2).join("/") + tail;
}
function createSession(deviceId2, name, options) {
  const opts = options || {};
  const adoptAsDefault = opts.adoptAsDefault !== false;
  let sessionName = "default";
  try {
    sessionName = ctx.http.session(name || "default", adoptAsDefault);
  } catch (e) {
    log("w", "\u521B\u5EFA HTTP \u4F1A\u8BDD\u5931\u8D25\uFF0C\u56DE\u9000\u5230\u9ED8\u8BA4\u4F1A\u8BDD\uFF1A" + describeError(e));
  }
  const jar = /* @__PURE__ */ new Map();
  function jarKey(domain, path, cname) {
    return domain + "|" + path + "|" + cname;
  }
  function splitUrl(url) {
    const m = String(url || "").match(/^https?:\/\/([^/?#]+)([^?#]*)/i);
    if (!m) return { hostname: "", pathname: "/" };
    let host = m[1].toLowerCase();
    const at = host.indexOf("@");
    if (at >= 0) host = host.slice(at + 1);
    const colon = host.indexOf(":");
    if (colon >= 0) host = host.slice(0, colon);
    return { hostname: host, pathname: m[2] || "/" };
  }
  function saveSetCookie(url, raw) {
    const u = splitUrl(url);
    const parts = String(raw || "").split(";");
    const first = parts[0] || "";
    const eq = first.indexOf("=");
    if (eq <= 0) return;
    const cname = first.slice(0, eq).trim();
    const value = first.slice(eq + 1).trim();
    if (!cname) return;
    let domain = u.hostname;
    let cpath = "/";
    let expired = false;
    for (let i = 1; i < parts.length; i++) {
      const seg = parts[i].trim();
      const lower = seg.toLowerCase();
      if (lower.startsWith("domain=")) {
        domain = seg.slice(7).trim().replace(/^\./, "");
      } else if (lower.startsWith("path=")) {
        cpath = seg.slice(5).trim() || "/";
      } else if (lower.startsWith("max-age=")) {
        const n = parseInt(seg.slice(8).trim(), 10);
        if (!isNaN(n) && n <= 0) expired = true;
      } else if (lower.startsWith("expires=")) {
        const t = Date.parse(seg.slice(8).trim());
        if (!isNaN(t) && t <= Date.now()) expired = true;
      }
    }
    const key = jarKey(domain, cpath, cname);
    if (expired) {
      jar.delete(key);
    } else {
      jar.set(key, { name: cname, value, domain, path: cpath });
    }
  }
  function cookiesFor(url) {
    const u = splitUrl(url);
    const out = [];
    jar.forEach((c) => {
      const hostOk = u.hostname === c.domain || u.hostname.endsWith("." + c.domain);
      const pathOk = u.pathname.indexOf(c.path) === 0;
      if (hostOk && pathOk) out.push(c);
    });
    return out;
  }
  ["xiaomi.com", "mi.com"].forEach((domain) => {
    const seed = (n, v) => jar.set(jarKey(domain, "/", n), { name: n, value: v, domain, path: "/" });
    seed("sdkVersion", "accountsdk-18.8.15");
    seed("deviceId", deviceId2);
    seed("_locale", "zh_CN");
  });
  async function send(method, url, options2) {
    const opts2 = options2 || {};
    return await sendOnce(method, url, opts2);
  }
  async function sendOnce(method, url, opts2) {
    const follow = opts2.followRedirects !== false;
    const maxHops = follow ? 10 : 1;
    let current = url;
    let currentMethod = method;
    let currentBody = opts2.body;
    for (let hop = 0; hop < maxHops; hop++) {
      const res = await sendRaw(currentMethod, current, opts2, currentBody);
      const list = Array.isArray(res.setCookie) ? res.setCookie : [];
      list.forEach((raw) => saveSetCookie(current, raw));
      const next = res.headers && res.headers["location"] || "";
      const isRedirect = res.status >= 300 && res.status < 400 && next;
      if (isRedirect) {
        log(
          "d",
          "hop" + hop + " " + res.status + " setCookie=" + list.length + " jar=" + jar.size + " from=" + shortUrl(current) + " to=" + shortUrl(next)
        );
      }
      if (!isRedirect || !follow) return res;
      const resolved = absoluteUrl(current, next);
      if (resolved === current) {
        throw new Error("\u8DF3\u8F6C\u5730\u5740\u4E0E\u5F53\u524D\u76F8\u540C\uFF0C\u65E0\u6CD5\u524D\u8FDB\uFF1A" + shortUrl(current));
      }
      current = resolved;
      if (res.status === 303 || currentMethod !== "GET") {
        currentMethod = "GET";
        currentBody = void 0;
      }
    }
    throw new Error("\u91CD\u5B9A\u5411\u6B21\u6570\u8FC7\u591A\uFF1A" + shortUrl(url));
  }
  async function sendRaw(method, url, opts2, body) {
    const headers = Object.assign({}, opts2.headers);
    const argv = {
      headers,
      // 永远不让宿主跟随：它跟随会丢掉我们拼的 Cookie 头
      followRedirects: false
    };
    if (opts2.contentType) argv.contentType = opts2.contentType;
    if (!headers.Cookie && !headers.cookie) {
      const list = cookiesFor(url);
      if (list.length) headers.Cookie = list.map((c) => c.name + "=" + c.value).join("; ");
    }
    if (opts2.userAgent) argv.userAgent = opts2.userAgent;
    if (opts2.timeoutMs) argv.timeoutMs = opts2.timeoutMs;
    let res;
    try {
      if (method === "POST") {
        res = await ctx.http.post(url, body || "", argv, sessionName);
      } else {
        res = await ctx.http.get(url, argv, sessionName);
      }
    } catch (e) {
      log("e", method + " " + shortUrl(url) + " \u8BF7\u6C42\u5931\u8D25\uFF1A" + describeError(e));
      throw e;
    }
    log("d", method + " " + shortUrl(url) + " -> " + res.status);
    return res;
  }
  function cookie(cname, domain) {
    if (domain) {
      const d = String(domain).replace(/^\./, "");
      const hit = jar.get(jarKey(d, "/", cname));
      if (hit) return hit.value;
      let found2 = null;
      jar.forEach((c) => {
        if (c.name === cname && c.domain === d && found2 == null) found2 = c.value;
      });
      return found2;
    }
    let found = null;
    jar.forEach((c) => {
      if (c.name === cname && found == null) found = c.value;
    });
    return found;
  }
  function setCookie(name2, value, domain) {
    if (!name2 || value == null || value === "") return;
    const d = String(domain || "xiaomi.com").replace(/^\./, "");
    jar.set(jarKey(d, "/", name2), { name: name2, value, domain: d, path: "/" });
  }
  return {
    send,
    cookie,
    setCookie,
    deviceId: deviceId2,
    sessionName,
    names: () => {
      const seen = {};
      const out = [];
      jar.forEach((c) => {
        if (!seen[c.name]) {
          seen[c.name] = true;
          out.push(c.name);
        }
      });
      return out;
    }
  };
}
async function fetchCaptcha(session) {
  const res = await session.send(
    "GET",
    ACCOUNT + "/pass/getCode?icodeType=login&" + Math.random(),
    { headers: { Referer: ACCOUNT + "/" } }
  );
  const b64 = res.bodyBase64;
  if (!res.ok || !b64) return "";
  const bytes = ctx.codec.base64Decode(b64);
  if (!bytes || bytes.length < 4) return "";
  const isJpeg = bytes[0] === 255 && bytes[1] === 216;
  const isPng = bytes[0] === 137 && bytes[1] === 80 && bytes[2] === 78 && bytes[3] === 71;
  if (!isJpeg && !isPng) return "";
  return "data:image/jpeg;base64," + b64;
}
async function serviceLogin(session) {
  const res = await session.send(
    "GET",
    ACCOUNT + "/pass/serviceLogin?sid=miothealth&_json=true",
    { headers: { Referer: ACCOUNT + "/" } }
  );
  const json = parseMiapi(res.body);
  if (!json || !json._sign) {
    const raw = String(res && res.body || "");
    log("e", "serviceLogin \u672A\u62FF\u5230 _sign\uFF1Astatus=" + (res && res.status || "\u65E0") + " body\u957F\u5EA6=" + raw.length + " \u5F00\u5934=" + JSON.stringify(raw.slice(0, 160)));
  }
  return json;
}
async function serviceLoginRaw(session) {
  const res = await session.send(
    "GET",
    ACCOUNT + "/pass/serviceLogin?sid=miothealth&_json=true",
    { headers: { Referer: ACCOUNT + "/" } }
  );
  const json = parseMiapi(res.body);
  if (!json) {
    const raw = String(res && res.body || "");
    log("e", "serviceLogin \u54CD\u5E94\u65E0\u6CD5\u89E3\u6790\uFF1Astatus=" + (res && res.status || "\u65E0") + " body\u957F\u5EA6=" + raw.length + " \u5F00\u5934=" + JSON.stringify(raw.slice(0, 160)));
  }
  return { json, raw: String(res.body || ""), status: res.status };
}
async function serviceLoginAuth2(session, account, passwordHash, handshake, captCode) {
  const body = {
    user: account,
    hash: passwordHash,
    sid: "miothealth",
    jsondata: "",
    _json: "true",
    _sign: handshake._sign,
    qs: handshake.qs || "",
    callback: handshake.callback || "",
    locale: "zh_CN"
  };
  if (captCode) body.captCode = captCode;
  const res = await session.send("POST", ACCOUNT + "/pass/serviceLoginAuth2", {
    headers: {
      Referer: ACCOUNT + "/",
      "Content-Type": "application/x-www-form-urlencoded"
    },
    body: formEncode(body),
    contentType: "application/x-www-form-urlencoded",
    // 不能自动跟随：登录结果里的 location 是下一步要走的 STS 回调，
    // 跟着跳会让 serviceToken 下发在中间跳，插件再也拿不到
    followRedirects: false
  });
  return parseMiapi(res.body);
}
function isUsableServiceToken(value) {
  return !!value && value !== "EXPIRED" && value !== SID2;
}
async function fetchServiceToken(session, location, userId) {
  const res = await session.send("GET", absolute(ACCOUNT, location), {
    userAgent: identityUa(session.deviceId, userId),
    followRedirects: false
  });
  const setCookie = Array.isArray(res.setCookie) ? res.setCookie : [];
  for (let i = 0; i < setCookie.length; i++) {
    const m = String(setCookie[i] || "").match(/serviceToken=([^;]+)/);
    if (m && isUsableServiceToken(m[1])) return m[1];
  }
  const fromCookie = session.cookie("serviceToken", "sts-hlth.io.mi.com") || session.cookie("serviceToken", "sts.api.io.mi.com");
  if (isUsableServiceToken(fromCookie)) return fromCookie;
  const m2 = String(res.body || "").match(/serviceToken=([^;&"'\s]+)/);
  if (m2 && isUsableServiceToken(m2[1])) return m2[1];
  return null;
}
function absolute(base, location) {
  if (/^https?:\/\//i.test(location)) return location;
  if (location.startsWith("//")) return "https:" + location;
  const origin = base.match(/^https?:\/\/[^/]+/);
  const prefix = origin ? origin[0] : ACCOUNT;
  return location.startsWith("/") ? prefix + location : prefix + "/" + location;
}

// mihealth/main.ts
var SLOT_ACCOUNT = "account";
var SLOT_PASSWORD = "password";
var SLOT_SSECURITY = "ssecurity";
var SLOT_SERVICE_TOKEN = "service_token";
var SLOT_CUSER_ID = "cuser_id";
var SLOT_PASS_TOKEN = "pass_token";
var SLOT_CAPTCHA = "captcha";
var SLOT_OTP = "otp";
var DAY_MILLIS = 24 * 60 * 60 * 1e3;
var IDENTITY_TIMEOUT_MS = 12e3;
var DAYS = 7;
var BACKFILL_DAYS = 30;
var ACTION_SYNC = "sync_now";
var ACTION_BACKFILL = "backfill";
var AGGREGATED_PATH = "/app/v1/data/get_aggregated_fitness_data_by_time";
var STEPS_KEY = "STEPS";
var SLEEP_KEY = "SLEEP_MINUTES";
var SLEEP_DEEP_RATIO_KEY = "SLEEP_DEEP_RATIO";
var SLEEP_EFFICIENCY_KEY = "SLEEP_EFFICIENCY";
var SLEEP_AWAKE_COUNT_KEY = "SLEEP_AWAKE_COUNT";
var SLEEP_BEDTIME_KEY = "SLEEP_BEDTIME";
var SLEEP_REGULARITY_KEY = "SLEEP_REGULARITY";
var OBS_HEART_RATE = "OBS_HEART_RATE";
var OBS_RESTING_HR = "OBS_RESTING_HEART_RATE";
var OBS_SPO2 = "OBS_SPO2";
var OBS_STRESS = "OBS_STRESS";
var OBS_CALORIES = "OBS_CALORIES";
var OBS_DISTANCE = "OBS_DISTANCE";
var OBS_STAND = "OBS_STAND";
var OBS_INTENSITY = "OBS_INTENSITY";
var OBS_WEIGHT = "OBS_WEIGHT";
var OBS_BMI = "OBS_BMI";
var OBS_BODY_FAT = "OBS_BODY_FAT_RATE";
var OBS_MUSCLE = "OBS_MUSCLE_RATE";
var OBS_MOISTURE = "OBS_MOISTURE_RATE";
var OBS_BONE_MASS = "OBS_BONE_MASS";
var OBS_BASAL = "OBS_BASAL_METABOLISM";
var OBS_PROTEIN = "OBS_PROTEIN_RATE";
var OBS_VISCERAL_FAT = "OBS_VISCERAL_FAT";
var OBS_TRAINING_LOAD = "OBS_TRAINING_LOAD";
var OBS_TRAINING_LEVEL = "OBS_TRAINING_LEVEL";
var OBS_VO2_MAX = "OBS_VO2_MAX";
var OBS_PAI = "OBS_PAI";
var OBS_BLOOD_SYSTOLIC = "OBS_BLOOD_SYSTOLIC";
var OBS_BLOOD_DIASTOLIC = "OBS_BLOOD_DIASTOLIC";
var OBS_BLOOD_SUGAR = "OBS_BLOOD_SUGAR";
var OBS_MUSCLE_MASS = "OBS_MUSCLE_MASS";
var OBS_PROTEIN_MASS = "OBS_PROTEIN_MASS";
var OBS_MOISTURE_MASS = "OBS_BODY_MOISTURE_MASS";
var OBS_SKELETAL_MUSCLE = "OBS_SKELETAL_MUSCLE_MASS";
var OBS_BODY_AGE = "OBS_BODY_AGE";
var OBS_BODY_SCORE = "OBS_BODY_SCORE";
var OBS_STRESS_GRADE = "OBS_STRESS_GRADE";
var OBS_STAND_DURATION = "OBS_STAND_DURATION";
var OBS_ENERGY = "OBS_ENERGY";
var OBS_HEADSET_NOISE = "OBS_HEADSET_NOISE";
var OBS_MENSTRUATION = "OBS_MENSTRUATION";
var GOAL_STEPS = "GOAL_STEPS";
var GOAL_CALORIES = "GOAL_CALORIES";
var GOAL_ACTIVE_MINUTES = "GOAL_ACTIVE_MINUTES";
var GOAL_STAND_HOURS = "GOAL_STAND_HOURS";
var SAMPLED = [
  { cloudKey: "heart_rate", metricKey: OBS_HEART_RATE, fields: ["bpm", "heart_rate"], mean: true },
  // 静息心率是每天一条的「当日结论值」，用最新一条而不是均值
  { cloudKey: "resting_heart_rate", metricKey: OBS_RESTING_HR, fields: ["bpm"], mean: false },
  { cloudKey: "spo2", metricKey: OBS_SPO2, fields: ["spo2"], mean: true },
  { cloudKey: "stress", metricKey: OBS_STRESS, fields: ["stress_value", "stress"], mean: true },
  // 官方 com.xiaomi.stress.delta 的 grade：1 放松 / 2 轻度 / 3 中度 / 4 重度。
  // 明细里本来就有，按最新一条取，代表「当天最后测到的压力档位」
  { cloudKey: "stress", metricKey: OBS_STRESS_GRADE, fields: ["grade"], mean: false },
  { cloudKey: "valid_stand", metricKey: OBS_STAND, fields: [], mean: false },
  // 官方统计口径是「站立时长(分钟)」，用每小时区间长度换算出总分钟数
  { cloudKey: "valid_stand", metricKey: OBS_STAND_DURATION, fields: [], mean: false, duration: true },
  { cloudKey: "intensity", metricKey: OBS_INTENSITY, fields: [], mean: false },
  // 体重与身体成分：同一次称重记录里带全部字段
  { cloudKey: "weight", metricKey: OBS_WEIGHT, fields: ["weight"], mean: false },
  { cloudKey: "weight", metricKey: OBS_BMI, fields: ["bmi"], mean: false },
  { cloudKey: "weight", metricKey: OBS_BODY_FAT, fields: ["body_fat_rate"], mean: false },
  { cloudKey: "weight", metricKey: OBS_MUSCLE, fields: ["muscle_rate"], mean: false },
  { cloudKey: "weight", metricKey: OBS_MOISTURE, fields: ["moisture_rate"], mean: false },
  { cloudKey: "weight", metricKey: OBS_BONE_MASS, fields: ["bone_mass"], mean: false },
  { cloudKey: "weight", metricKey: OBS_BASAL, fields: ["basal_metabolism"], mean: false },
  { cloudKey: "weight", metricKey: OBS_PROTEIN, fields: ["protein_rate"], mean: false },
  { cloudKey: "weight", metricKey: OBS_VISCERAL_FAT, fields: ["visceral_fat"], mean: false },
  // 官方 com.xiaomi.weight.instantaneous 里还有这 6 项（质量类 + 身体年龄/得分）
  { cloudKey: "weight", metricKey: OBS_MUSCLE_MASS, fields: ["muscle_mass"], mean: false },
  { cloudKey: "weight", metricKey: OBS_PROTEIN_MASS, fields: ["protein_mass"], mean: false },
  { cloudKey: "weight", metricKey: OBS_MOISTURE_MASS, fields: ["body_moisture_mass"], mean: false },
  { cloudKey: "weight", metricKey: OBS_SKELETAL_MUSCLE, fields: ["skeletal_muscle_mass"], mean: false },
  { cloudKey: "weight", metricKey: OBS_BODY_AGE, fields: ["body_age"], mean: false },
  { cloudKey: "weight", metricKey: OBS_BODY_SCORE, fields: ["body_score"], mean: false },
  // 训练负荷：wtl_sum 是当日负荷量，level 是 1~5 的档位
  { cloudKey: "training_load", metricKey: OBS_TRAINING_LOAD, fields: ["wtl_sum"], mean: false },
  { cloudKey: "training_load", metricKey: OBS_TRAINING_LEVEL, fields: ["current_train_load_level"], mean: false },
  { cloudKey: "vo2_max", metricKey: OBS_VO2_MAX, fields: ["vo2_max"], mean: true },
  { cloudKey: "pai", metricKey: OBS_PAI, fields: ["pai", "value"], mean: true },
  { cloudKey: "blood_pressure", metricKey: OBS_BLOOD_SYSTOLIC, fields: ["systolic"], mean: true },
  { cloudKey: "blood_pressure", metricKey: OBS_BLOOD_DIASTOLIC, fields: ["diastolic"], mean: true },
  { cloudKey: "blood_sugar", metricKey: OBS_BLOOD_SUGAR, fields: ["value", "blood_sugar"], mean: true },
  // CloudKey 全集里剩下的三个数据源（小米官方 CloudKey.getCloudRequestKey 有对应项）
  { cloudKey: "energy", metricKey: OBS_ENERGY, fields: ["energy"], mean: false },
  { cloudKey: "headset", metricKey: OBS_HEADSET_NOISE, fields: ["noise", "noise_value", "db", "volume"], mean: true },
  { cloudKey: "menstruation", metricKey: OBS_MENSTRUATION, fields: [], mean: false }
];
var STEP_FIELDS = ["steps", "step", "count", "value"];
var STEP_DISTANCE_FIELDS = ["distance"];
var STEP_CALORIE_FIELDS = ["calories"];
var POINT_TIME_FIELDS = ["time", "timestamp", "start_time", "startTime"];
var SLEEP_START_FIELDS = ["bedtime", "device_bedtime", "start_time", "startTime"];
var SLEEP_END_FIELDS = ["device_wake_up_time", "wake_up_time", "end_time", "endTime"];
var pendingLogin = null;
var cachedDeviceId = "";
var DEVICE_ID_FILE = "device_id.txt";
function deviceId() {
  if (cachedDeviceId) return cachedDeviceId;
  try {
    const saved = ctx.files.read(DEVICE_ID_FILE);
    if (typeof saved === "string") {
      const trimmed = saved.trim();
      if (trimmed.length >= 8) {
        cachedDeviceId = trimmed;
        return cachedDeviceId;
      }
    }
  } catch (e) {
    log2("w", "\u8BFB\u53D6 deviceId \u5931\u8D25\uFF0C\u5C06\u91CD\u65B0\u751F\u6210\uFF1A" + describeError(e));
  }
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  let out = "";
  for (let i = 0; i < 16; i++) {
    out += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  cachedDeviceId = out;
  try {
    ctx.files.write(DEVICE_ID_FILE, out);
  } catch (e) {
    log2("w", "\u4FDD\u5B58 deviceId \u5931\u8D25\uFF1A" + describeError(e));
  }
  return cachedDeviceId;
}
var currentCreds = null;
var sessionEpoch = 0;
var credsRejectedByServer = false;
function log2(level, message) {
  try {
    ctx.log[level]("MiHealth", message);
  } catch (e) {
  }
}
function maskAccount(account) {
  const s = String(account || "");
  if (s.length <= 5) return "***";
  return s.slice(0, 3) + "***" + s.slice(-2);
}
function loadCredentials() {
  if (currentCreds) return currentCreds;
  const ssecurity = ctx.credentials.get(SLOT_SSECURITY);
  const serviceToken = ctx.credentials.get(SLOT_SERVICE_TOKEN);
  if (!ssecurity || !serviceToken) return null;
  currentCreds = {
    ssecurity,
    serviceToken,
    cUserId: ctx.credentials.get(SLOT_CUSER_ID) || "",
    // userId 不落盘：原实现没存这个槽位，认证链路里的 userId 都从会话
    // cookie 现取。加进来会多一个凭据槽位，属于超出原行为范围的改动
    userId: "",
    passToken: ctx.credentials.get(SLOT_PASS_TOKEN) || "",
    account: ctx.credentials.get(SLOT_ACCOUNT) || ""
  };
  return currentCreds;
}
function saveCredentials(creds) {
  ctx.credentials.put(SLOT_SSECURITY, creds.ssecurity || "");
  ctx.credentials.put(SLOT_SERVICE_TOKEN, creds.serviceToken || "");
  ctx.credentials.put(SLOT_CUSER_ID, creds.cUserId || "");
  ctx.credentials.put(SLOT_PASS_TOKEN, creds.passToken || "");
  if (creds.account) {
    ctx.credentials.put(SLOT_ACCOUNT, creds.account);
  }
  currentCreds = creds;
  credsRejectedByServer = false;
}
function captureEpoch() {
  return sessionEpoch;
}
function isValidCreds(creds) {
  return !!creds && !!creds.ssecurity && !!creds.serviceToken;
}
var AuthError = class extends Error {
  constructor(message) {
    super(message);
    this.name = "AuthError";
  }
};
function isAuthError(e) {
  if (e instanceof AuthError) return true;
  return e != null && e.name === "AuthError";
}
async function callApi(session, creds, method, path, params) {
  const signed = signQuery(method, path, params, creds.ssecurity);
  const url = "https://hlth.io.mi.com" + path + "?" + signed.query;
  const res = await session.send(method, url, {
    // 注意：不带 userId cookie，带了服务端稳定返回 401
    headers: {
      Cookie: cookieHeaderOf(creds),
      Accept: "application/json"
    },
    userAgent: ua(deviceId(), 0),
    contentType: "application/x-www-form-urlencoded",
    body: method === "POST" ? "" : void 0
  });
  if (!res || res.status === 401) {
    throw new AuthError("\u5C0F\u7C73\u63A5\u53E3\u8FD4\u56DE 401\uFF0C\u51ED\u636E\u5DF2\u5931\u6548");
  }
  if (!res.ok) {
    throw new Error("HTTP " + (res && res.status || "\u65E0\u54CD\u5E94"));
  }
  const body = String(res.body || "").trim();
  if (!body) {
    throw new Error("\u6570\u636E\u63A5\u53E3\u54CD\u5E94\u4E3A\u7A7A");
  }
  const plain = decryptBody(creds.ssecurity, signed.nonce, body);
  try {
    return JSON.parse(plain);
  } catch (e) {
    throw new Error("\u6570\u636E\u63A5\u53E3\u54CD\u5E94\u65E0\u6CD5\u89E3\u5BC6\u89E3\u6790\uFF08\u4F1A\u8BDD\u5BC6\u94A5\u53EF\u80FD\u4E0D\u914D\u5957\uFF09");
  }
}
function cookieHeaderOf(creds) {
  return "cUserId=" + creds.cUserId + "; serviceToken=" + creds.serviceToken + "; locale=zh_cn";
}
async function refreshWithPassToken(passToken, cUserId, userId, account) {
  if (!passToken) return null;
  const session = createSession(deviceId(), "refresh", { adoptAsDefault: false });
  session.setCookie("passToken", passToken, "xiaomi.com");
  if (cUserId) session.setCookie("cUserId", cUserId, "xiaomi.com");
  if (userId) session.setCookie("userId", userId, "xiaomi.com");
  const res = await session.send("GET", ACCOUNT + "/pass/serviceLogin?sid=" + SID + "&_json=true", {
    headers: { Referer: ACCOUNT + "/" }
  });
  const j = parseMiapi(res.body);
  if (!j || !j.ssecurity) {
    log2("i", "refreshWithPassToken rejected: " + JSON.stringify(j || {}).slice(0, 200));
    return null;
  }
  const loc = j.location || "";
  if (!loc) return null;
  const token = await fetchServiceToken(session, loc, userId || "");
  if (!token) return null;
  return {
    ssecurity: j.ssecurity,
    serviceToken: token,
    cUserId: j.cUserId || cUserId,
    userId: j.userId ? String(j.userId) : userId || "",
    passToken: j.passToken || passToken,
    account: account || ""
  };
}
function clientFor(creds) {
  const session = createSession(deviceId(), "collect", { adoptAsDefault: false });
  let current = creds;
  async function callWithRetry(method, path, params) {
    try {
      return await callApi(session, current, method, path, params);
    } catch (error) {
      if (!isAuthError(error) || !current.passToken) throw error;
      log2("i", "\u6570\u636E\u63A5\u53E3\u8FD4\u56DE 401\uFF0C\u6309\u53C2\u8003\u5B9E\u73B0\u7528 passToken \u7EED\u671F\u540E\u91CD\u8BD5\u4E00\u6B21");
      const refreshEpoch = captureEpoch();
      const fresh = await refreshWithPassToken(
        current.passToken,
        current.cUserId,
        current.userId || "",
        current.account
      );
      if (!fresh) throw error;
      current = fresh;
      if (refreshEpoch === sessionEpoch) {
        saveCredentials(fresh);
      }
      return await callApi(session, current, method, path, params);
    }
  }
  async function getFitnessDataByTime(cloudKey, startTimeMillis, endTimeMillis, maxPages) {
    const limit = maxPages || 10;
    const apiStart = Math.max(startTimeMillis, endTimeMillis - DAY_MILLIS);
    const out = [];
    const seen = /* @__PURE__ */ new Set();
    let nextKey = null;
    const accept = (p) => {
      if (!p || typeof p !== "object") return;
      const t = timestampAt(p, POINT_TIME_FIELDS);
      if (t === null || t < startTimeMillis || t > endTimeMillis) return;
      const sid = typeof p.sid === "string" ? String(p.sid) : null;
      const key = sid !== null ? sid : t + "|" + JSON.stringify(p);
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
        next_key: nextKey
      };
      const resp = await callWithRetry("GET", "/app/v1/data/get_fitness_data_by_time", {
        data: JSON.stringify(data)
      });
      if (!resp || typeof resp !== "object") {
        throw new Error("\u6570\u636E\u63A5\u53E3\u8FD4\u56DE\u4E0D\u662F JSON \u5BF9\u8C61");
      }
      if (typeof resp.code === "number" && resp.code !== 0 && resp.code !== 1) {
        throw new Error("\u6570\u636E\u63A5\u53E3 code=" + resp.code + " message=" + (resp.message || "\u65E0"));
      }
      const body = resp.result || resp;
      const items = body.data_list;
      if (!items) {
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
  async function getDailyGoals(startTimeMillis, endTimeMillis) {
    const resp = await callApi(session, current, "GET", AGGREGATED_PATH, {
      data: JSON.stringify({
        tag: "daily_fitness",
        key: "goal",
        startTime: startTimeMillis,
        endTime: endTimeMillis,
        reverse: true
      })
    });
    if (!resp || typeof resp !== "object") throw new Error("\u805A\u5408\u63A5\u53E3\u8FD4\u56DE\u4E0D\u662F JSON \u5BF9\u8C61");
    const body = resp.result || resp;
    const list = body.data_list || body.dataPoints || [];
    return list.slice().sort((a, b) => Number(a.time) - Number(b.time));
  }
  return { getFitnessDataByTime, getDailyGoals, currentCreds: () => current };
}
function payloadOf(point) {
  if (!point) return {};
  const raw = point.value;
  if (typeof raw !== "string" || raw.trim() === "") return point;
  try {
    const inner = JSON.parse(raw);
    return inner && typeof inner === "object" ? inner : point;
  } catch (e) {
    return point;
  }
}
function numberAt(point, fields) {
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
function timestampAt(point, fields) {
  const payload = payloadOf(point);
  for (let i = 0; i < fields.length; i++) {
    const name = fields[i];
    let raw;
    if (payload && Object.prototype.hasOwnProperty.call(payload, name)) {
      raw = payload[name];
    } else if (point && Object.prototype.hasOwnProperty.call(point, name)) {
      raw = point[name];
    } else {
      continue;
    }
    const v = asLong(raw);
    if (v === null || v <= 0) continue;
    return v < 1e11 ? v * 1e3 : v;
  }
  return null;
}
function dayStart(dateStr) {
  const parts = String(dateStr).split("-");
  if (parts.length !== 3) return null;
  const y = parseInt(parts[0], 10);
  const m = parseInt(parts[1], 10);
  const d = parseInt(parts[2], 10);
  if (!isFinite(y) || !isFinite(m) || !isFinite(d)) return null;
  return new Date(y, m - 1, d, 0, 0, 0, 0).getTime();
}
function dayRange(dateStr) {
  const start = dayStart(dateStr);
  if (start === null) return null;
  const nextStart = new Date(start);
  nextStart.setDate(nextStart.getDate() + 1);
  const end = Math.min(nextStart.getTime() - 1, Date.now());
  return [start, end];
}
function formatDate(d) {
  const y = d.getFullYear();
  const m = d.getMonth() + 1;
  const day = d.getDate();
  return y + "-" + (m < 10 ? "0" + m : m) + "-" + (day < 10 ? "0" + day : day);
}
function formatClock(millis) {
  const d = new Date(millis);
  const h = d.getHours();
  const mi = d.getMinutes();
  return (h < 10 ? "0" + h : h) + ":" + (mi < 10 ? "0" + mi : mi);
}
function reportQueried(metricKey, date) {
  try {
    ctx.reportMetricQueried(metricKey, date);
  } catch (e) {
    log2("w", "reportMetricQueried(" + metricKey + ") failed: " + describeError(e));
  }
}
function reportRaw(metricKey, records, date) {
  if (!records || records.length === 0) return;
  try {
    ctx.reportRawRecords(metricKey, records, date);
  } catch (e) {
    log2("w", "reportRawRecords(" + metricKey + ") failed: " + describeError(e));
  }
}
async function collectSteps(client, start, end, date) {
  const points = await client.getFitnessDataByTime("steps", start, end);
  if (!points || points.length === 0) {
    log2("i", "collectSteps(" + date + ") \u63A5\u53E3\u8FD4\u56DE 0 \u4E2A\u91C7\u6837\u70B9");
    return [];
  }
  const raw = [];
  const rawDistance = [];
  const rawCalories = [];
  let sum = 0;
  let distance = 0;
  let calories = 0;
  points.forEach((p) => {
    const at = timestampAt(p, ["time", "timestamp"]);
    const steps = numberAt(p, STEP_FIELDS);
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
  log2(
    "i",
    "collectSteps(" + date + ") \u91C7\u6837\u70B9 " + points.length + " \u4E2A\uFF0C\u53EF\u89E3\u6790 " + raw.length + " \u4E2A\uFF0C\u8DDD\u79BB " + Math.round(distance) + " \u7C73\uFF0C\u70ED\u91CF " + calories.toFixed(1) + " kcal"
  );
  raw.sort((a, b) => a.timestamp - b.timestamp);
  reportRaw(STEPS_KEY, raw, date);
  rawDistance.sort((a, b) => a.timestamp - b.timestamp);
  reportRaw(OBS_DISTANCE, rawDistance, date);
  rawCalories.sort((a, b) => a.timestamp - b.timestamp);
  reportRaw(OBS_CALORIES, rawCalories, date);
  const out = [];
  if (sum > 0) out.push({ metricKey: STEPS_KEY, value: sum });
  if (points.length > 0) {
    out.push({ metricKey: OBS_DISTANCE, value: Math.round(distance) });
    out.push({ metricKey: OBS_CALORIES, value: Math.round(calories * 10) / 10 });
  }
  return out;
}
function sameSleep(a, b) {
  const overlap = Math.min(a.endMs, b.endMs) - Math.max(a.startMs, b.startMs);
  if (overlap <= 0) return false;
  const shorter = Math.min(a.endMs - a.startMs, b.endMs - b.startMs);
  return shorter > 0 && overlap / shorter >= 0.5;
}
function richerSleep(a, b) {
  const score = (s) => (s.hasStages ? 2 : 0) + (s.awakeCount > 0 ? 1 : 0);
  return score(b) > score(a) ? b : a;
}
function bedtimeMinute(timestampMs) {
  const d = new Date(timestampMs);
  const minute = d.getHours() * 60 + d.getMinutes();
  return minute < 12 * 60 ? minute + 24 * 60 : minute;
}
function sleepRegularity(todayBedMinute) {
  const rows = recentValues(SLEEP_BEDTIME_KEY);
  const values = rows.map((r) => r.value).filter((v) => isFinite(v) && v > 0);
  values.push(todayBedMinute);
  if (values.length < 3) return null;
  const mean = values.reduce((a, b) => a + b, 0) / values.length;
  const variance = values.reduce((a, b) => a + (b - mean) * (b - mean), 0) / values.length;
  const std = Math.sqrt(variance);
  return Math.max(0, Math.round(100 - std / 3));
}
async function collectSleep(client, date) {
  const keys = [
    SLEEP_KEY,
    SLEEP_DEEP_RATIO_KEY,
    SLEEP_EFFICIENCY_KEY,
    SLEEP_AWAKE_COUNT_KEY,
    SLEEP_BEDTIME_KEY,
    SLEEP_REGULARITY_KEY
  ];
  keys.forEach((k) => reportQueried(k, date));
  const start = dayStart(date);
  if (start === null) return { metrics: [], hasData: false };
  const prevNoon = start - 12 * 60 * 60 * 1e3;
  const nextStart = new Date(start);
  nextStart.setDate(nextStart.getDate() + 1);
  const end = Math.min(nextStart.getTime() - 1, Date.now());
  const points = await client.getFitnessDataByTime("sleep", prevNoon, end);
  if (!points || points.length === 0) return { metrics: [], hasData: false };
  const sessions = [];
  points.forEach((p) => {
    const s = timestampAt(p, SLEEP_START_FIELDS);
    const e = timestampAt(p, SLEEP_END_FIELDS);
    if (s === null || e === null || e <= s) return;
    const rawDuration = numberAt(p, ["duration"]);
    const deep2 = numberAt(p, ["sleep_deep_duration"]) || 0;
    const light = numberAt(p, ["sleep_light_duration"]) || 0;
    const rem = numberAt(p, ["sleep_rem_duration"]) || 0;
    const duration = rawDuration !== null && rawDuration > 0 ? rawDuration : (e - s) / 6e4;
    const session = {
      startMs: s,
      endMs: e,
      duration,
      deep: deep2,
      light,
      rem,
      awakeCount: numberAt(p, ["awake_count"]) || 0,
      awakeDuration: numberAt(p, ["sleep_awake_duration"]) || 0,
      hasStages: deep2 + light + rem > 0
    };
    const idx = sessions.findIndex((k) => sameSleep(k, session));
    if (idx >= 0) {
      sessions[idx] = richerSleep(sessions[idx], session);
    } else {
      sessions.push(session);
    }
  });
  const nightly = sessions.filter((s) => formatDate(new Date(s.endMs)) === date);
  if (nightly.length === 0) return { metrics: [], hasData: false };
  nightly.sort((a, b) => a.startMs - b.startMs);
  const raw = nightly.map((s) => {
    const parts = [formatClock(s.startMs) + " \u2192 " + formatClock(s.endMs)];
    if (s.hasStages) {
      parts.push("\u6DF1" + Math.round(s.deep) + " \u6D45" + Math.round(s.light) + " REM" + Math.round(s.rem));
    }
    if (s.awakeDuration > 0) parts.push("\u6E05\u9192" + Math.round(s.awakeDuration) + "\u5206");
    return {
      timestamp: s.startMs,
      value: Math.round(s.duration * 10) / 10,
      detail: parts.join(" \xB7 ")
    };
  });
  reportRaw(SLEEP_KEY, raw, date);
  const total = nightly.reduce((a, s) => a + s.duration, 0);
  const deep = nightly.reduce((a, s) => a + s.deep, 0);
  const inBed = nightly.reduce((a, s) => a + (s.endMs - s.startMs) / 6e4, 0);
  const awakeCount = nightly.reduce((a, s) => a + s.awakeCount, 0);
  const bedtime = bedtimeMinute(nightly[0].startMs);
  const metrics = [{ metricKey: SLEEP_KEY, value: total }];
  if (deep > 0 && total > 0) {
    metrics.push({ metricKey: SLEEP_DEEP_RATIO_KEY, value: Math.round(deep / total * 1e3) / 10 });
  }
  if (inBed > 0 && total > 0) {
    metrics.push({
      metricKey: SLEEP_EFFICIENCY_KEY,
      value: Math.min(100, Math.round(total / inBed * 1e3) / 10)
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
  log2(
    "i",
    "\u7761\u7720 " + date + "\uFF1A" + nightly.length + " \u6BB5\uFF0C\u603B\u8BA1 " + Math.round(total) + " \u5206\u949F\uFF0C\u6DF1\u7761\u5360\u6BD4 " + (total > 0 ? Math.round(deep / total * 100) : 0) + "%"
  );
  return { metrics, hasData: true };
}
async function collectSampled(client, cloudKey, specs, start, end, date) {
  const points = await client.getFitnessDataByTime(cloudKey, start, end);
  specs.forEach((spec) => reportQueried(spec.metricKey, date));
  if (!points || points.length === 0) return [];
  const out = [];
  specs.forEach((spec) => {
    const raw = [];
    points.forEach((p) => {
      const at = timestampAt(p, ["time", "timestamp", "start_time"]);
      if (at === null) return;
      if (spec.duration) {
        const s = timestampAt(p, ["start_time", "startTime", "time"]);
        const e = timestampAt(p, ["end_time", "endTime"]);
        if (s === null || e === null || e <= s) return;
        raw.push({ timestamp: s, value: (e - s) / 6e4, detail: formatClock(s) + " \u2192 " + formatClock(e) });
        return;
      }
      const value = spec.fields.length === 0 ? 1 : numberAt(p, spec.fields);
      if (value === null) return;
      raw.push({ timestamp: at, value });
    });
    if (raw.length === 0) {
      log2("i", cloudKey + " \u7684 " + (spec.fields.join("/") || "(\u4E8B\u4EF6\u578B)") + " \u89E3\u6790\u4E0D\u51FA\u503C");
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
      if (avg > 0) out.push({ metricKey: spec.metricKey, value: avg });
    } else if (spec.duration || spec.fields.length === 0) {
      let sum = 0;
      raw.forEach((r) => {
        sum += r.value;
      });
      if (sum > 0) out.push({ metricKey: spec.metricKey, value: sum });
    } else {
      const latest = raw[raw.length - 1].value;
      out.push({ metricKey: spec.metricKey, value: latest });
    }
  });
  return out;
}
async function collectDailyGoals(client, date) {
  const keys = [GOAL_STEPS, GOAL_CALORIES, GOAL_ACTIVE_MINUTES, GOAL_STAND_HOURS];
  keys.forEach((k) => reportQueried(k, date));
  const range = dayRange(date);
  if (!range) return [];
  const [start, end] = range;
  let items;
  try {
    items = await client.getDailyGoals(start, end);
  } catch (e) {
    log2("i", "daily_fitness/goal \u53D6\u4E0D\u5230\uFF08" + date + "\uFF09\uFF1A" + describeError(e));
    return [];
  }
  if (items.length === 0) return [];
  const latest = items[items.length - 1];
  const payload = payloadOf(latest);
  const goals = {};
  const goalItems = payload.goal_items || [];
  goalItems.forEach((g) => {
    const field = Number(g && g.field);
    const achieved = Number(g && g.achieved_value);
    if (isFinite(field) && isFinite(achieved)) goals[field] = achieved;
  });
  const stand = payload.stand_goal;
  if (stand && isFinite(Number(stand.field))) {
    goals[Number(stand.field)] = Number(stand.achieved_value);
  }
  const map = [
    [1, GOAL_STEPS],
    [2, GOAL_CALORIES],
    [3, GOAL_STAND_HOURS],
    [4, GOAL_ACTIVE_MINUTES]
  ];
  const out = [];
  map.forEach(([field, key]) => {
    const v = goals[field];
    if (v === void 0 || !isFinite(v)) return;
    reportRaw(key, [{ timestamp: start, value: v }], date);
    out.push({ metricKey: key, value: v });
  });
  log2("i", "\u6BCF\u65E5\u76EE\u6807\uFF08" + date + "\uFF09\u89E3\u6790\u5230 " + out.length + " \u9879");
  return out;
}
async function login(inputs) {
  const form = inputs || {};
  try {
    if (!pendingLogin) {
      const account = form[SLOT_ACCOUNT];
      const password = form[SLOT_PASSWORD];
      if (!account || !password) {
        return needPassword(account || "");
      }
      return await startLogin(account, password);
    }
    const wantsRestart = !form[SLOT_ACCOUNT] && !form[SLOT_PASSWORD] && !form[SLOT_CAPTCHA] && !form[SLOT_OTP];
    if (wantsRestart) {
      pendingLogin = null;
      return needPassword("");
    }
    if (pendingLogin.stage === "captcha") {
      const code = form[SLOT_CAPTCHA];
      if (!code) return await needCaptcha(pendingLogin);
      return await submitCaptcha(code);
    }
    if (pendingLogin.stage === "twoFactor") {
      const code = form[SLOT_OTP];
      if (!code) return needOtp(pendingLogin.hint);
      return await submit2FA(code);
    }
    return await startLogin(
      form[SLOT_ACCOUNT] || pendingLogin.account,
      form[SLOT_PASSWORD] || pendingLogin.password
    );
  } catch (e) {
    log2("e", "login failed: " + describeError(e));
    pendingLogin = null;
    return { type: "failure", message: "\u767B\u5F55\u5F02\u5E38\uFF1A" + describeError(e) };
  }
}
function needPassword(account) {
  return {
    type: "needInput",
    prompt: "\u8BF7\u8F93\u5165\u5C0F\u7C73\u8D26\u53F7\u548C\u5BC6\u7801",
    // 密码必须掩码：登录时旁边可能有人，明文输入是实打实的泄露
    fields: [
      { name: SLOT_ACCOUNT, displayName: "\u5C0F\u7C73\u8D26\u53F7", secret: false, hint: account ? "\u4E0A\u6B21\u8F93\u5165\uFF1A" + account : void 0 },
      { name: SLOT_PASSWORD, displayName: "\u5BC6\u7801", secret: true }
    ]
  };
}
async function startLogin(account, password) {
  const session = createSession(deviceId(), "login");
  log2("i", "\u767B\u5F55\u5F00\u59CB\uFF0Caccount=" + maskAccount(account));
  const handshake = await serviceLogin(session);
  if (!handshake || !handshake._sign) {
    log2("e", "\u63E1\u624B\u5931\u8D25\uFF1A\u672A\u62FF\u5230 _sign\uFF0C\u54CD\u5E94=" + JSON.stringify(handshake || null).slice(0, 200));
    return { type: "failure", message: "\u767B\u5F55\u63E1\u624B\u5931\u8D25\uFF1A\u65E0\u6CD5\u8FDE\u63A5\u5C0F\u7C73\u767B\u5F55\u670D\u52A1\u6216\u672A\u62FF\u5230 _sign" };
  }
  log2("i", "\u63E1\u624B\u6210\u529F\uFF0C\u5DF2\u62FF\u5230 _sign");
  const hash = md5Upper(password);
  const auth2 = await serviceLoginAuth2(session, account, hash, handshake, "");
  log2(
    "i",
    "serviceLoginAuth2 \u8FD4\u56DE\uFF1Acode=" + (auth2 && auth2.code) + " \u6709 notificationUrl=" + !!(auth2 && auth2.notificationUrl) + " \u6709 captchaUrl=" + !!(auth2 && auth2.captchaUrl) + " desc=" + (auth2 && auth2.desc || "")
  );
  pendingLogin = {
    stage: "password",
    session,
    account,
    password,
    handshake,
    userId: ""
  };
  return await handleAuth2(auth2, account);
}
async function logout() {
  sessionEpoch++;
  pendingLogin = null;
  currentCreds = null;
  credsRejectedByServer = false;
  try {
    ctx.credentials.clear();
  } catch (e) {
    log2("w", "logout clear failed: " + describeError(e));
  }
}
async function authState() {
  try {
    const creds = loadCredentials();
    if (!isValidCreds(creds)) return { state: "loggedOut" };
    if (credsRejectedByServer) {
      return {
        state: "expired",
        message: "\u767B\u5F55\u5DF2\u5931\u6548\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55\u5C0F\u7C73\u8D26\u53F7"
      };
    }
    if (creds.passToken) {
      return creds.account ? { state: "loggedIn", accountName: creds.account } : { state: "loggedIn" };
    }
    return { state: "expired", message: "\u767B\u5F55\u5373\u5C06\u8FC7\u671F\uFF0C\u9700\u8981\u91CD\u65B0\u8F93\u5165\u5BC6\u7801" };
  } catch (e) {
    log2("e", "authState failed: " + describeError(e));
    return { state: "loggedOut" };
  }
}
async function fetch(date) {
  const out = [];
  try {
    const range = dayRange(date);
    if (!range) {
      log2("w", "fetch \u975E\u6CD5\u65E5\u671F\uFF1A" + date);
      return out;
    }
    const creds = loadCredentials();
    if (!isValidCreds(creds)) {
      return { status: "authRequired", message: "\u672A\u767B\u5F55\u5C0F\u7C73\u8D26\u53F7\uFF0C\u8BF7\u5148\u5728\u63D2\u4EF6\u9875\u767B\u5F55" };
    }
    const [start, end] = range;
    const client = clientFor(creds);
    let authFailure = null;
    const noteAuthFailure = (e) => {
      if (isAuthError(e)) authFailure = describeError(e);
    };
    try {
      const stepMetrics = await collectSteps(client, start, end, date);
      [STEPS_KEY, OBS_DISTANCE, OBS_CALORIES].forEach((k) => reportQueried(k, date));
      stepMetrics.forEach((m) => out.push(m));
    } catch (e) {
      noteAuthFailure(e);
      log2("w", "\u6B65\u6570\u91C7\u96C6\u5931\u8D25\uFF08" + date + "\uFF09\uFF1A" + describeError(e));
    }
    try {
      const sleep = await collectSleep(client, date);
      sleep.metrics.forEach((m) => out.push(m));
    } catch (e) {
      noteAuthFailure(e);
      log2("w", "\u7761\u7720\u91C7\u96C6\u5931\u8D25\uFF08" + date + "\uFF09\uFF1A" + describeError(e));
    }
    const groups = groupBy(SAMPLED, (s) => s.cloudKey);
    for (let i = 0; i < groups.length; i++) {
      const g = groups[i];
      try {
        const metrics = await collectSampled(client, g.key, g.items, start, end, date);
        metrics.forEach((m) => out.push(m));
      } catch (e) {
        noteAuthFailure(e);
        log2("w", g.key + " \u91C7\u96C6\u5931\u8D25\uFF08" + date + "\uFF09\uFF1A" + describeError(e));
      }
    }
    try {
      const goals = await collectDailyGoals(client, date);
      goals.forEach((m) => out.push(m));
    } catch (e) {
      noteAuthFailure(e);
      log2("w", "\u6BCF\u65E5\u76EE\u6807\u91C7\u96C6\u5931\u8D25\uFF08" + date + "\uFF09\uFF1A" + describeError(e));
    }
    if (authFailure) {
      credsRejectedByServer = true;
      log2("w", "\u51ED\u636E\u5DF2\u5931\u6548\uFF0C\u672C\u8F6E\u91C7\u96C6\u4E0D\u5B8C\u6574\uFF0C\u9700\u91CD\u65B0\u767B\u5F55\uFF1A" + authFailure);
      return {
        status: "authRequired",
        message: "\u767B\u5F55\u5DF2\u5931\u6548\uFF08\u5C0F\u7C73\u63A5\u53E3\u8FD4\u56DE 401 \u4E14\u65E0\u6CD5\u81EA\u52A8\u7EED\u671F\uFF09\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55\u5C0F\u7C73\u8D26\u53F7"
      };
    }
    credsRejectedByServer = false;
    return out;
  } catch (e) {
    log2("e", "fetch failed: " + describeError(e));
    return { status: "failure", message: "\u91C7\u96C6\u5931\u8D25\uFF1A" + describeError(e) };
  }
}
function groupBy(items, keyOf) {
  const order = [];
  const map = {};
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
async function health() {
  try {
    const creds = loadCredentials();
    if (!isValidCreds(creds)) {
      return { status: "broken", message: "\u672A\u767B\u5F55\u5C0F\u7C73\u8D26\u53F7" };
    }
    const client = clientFor(creds);
    const now = Date.now();
    try {
      const points = await client.getFitnessDataByTime("steps", now - DAY_MILLIS, now, 1);
      if (points && points.length >= 0) {
        credsRejectedByServer = false;
        return { status: "healthy" };
      }
    } catch (e) {
      log2("w", "health probe failed: " + describeError(e));
      if (isAuthError(e)) {
        credsRejectedByServer = true;
        return { status: "broken", message: "\u767B\u5F55\u5DF2\u5931\u6548\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55\u5C0F\u7C73\u8D26\u53F7" };
      }
    }
    if (!isValidCreds(loadCredentials())) {
      return { status: "broken", message: "\u767B\u5F55\u5DF2\u5931\u6548\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55" };
    }
    if (credsRejectedByServer) {
      return { status: "broken", message: "\u767B\u5F55\u5DF2\u5931\u6548\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55\u5C0F\u7C73\u8D26\u53F7" };
    }
    return { status: "degraded", message: "\u4E91\u7AEF\u63A5\u53E3\u6682\u65F6\u4E0D\u53EF\u7528" };
  } catch (e) {
    log2("e", "health failed: " + describeError(e));
    return { status: "degraded", message: "\u5065\u5EB7\u68C0\u67E5\u5F02\u5E38\uFF1A" + describeError(e) };
  }
}
async function detailUi() {
  const blocks = [];
  try {
    const auth = await authState();
    let label = "\u672A\u767B\u5F55";
    let tone = "error";
    let detail = "\u8BF7\u56DE\u63D2\u4EF6\u5217\u8868\u9875\u767B\u5F55";
    if (auth.state === "loggedIn") {
      label = "\u5DF2\u767B\u5F55";
      tone = "ok";
      detail = auth.accountName || "";
    } else if (auth.state === "expired") {
      label = "\u767B\u5F55\u5373\u5C06\u8FC7\u671F";
      tone = "warn";
      detail = auth.message || "";
    }
    blocks.push({ type: "statusCard", label: "\u767B\u5F55\u72B6\u6001", value: label, tone, detail: detail || null });
    const recentSteps = recentValues(STEPS_KEY);
    const recentSleep = recentValues(SLEEP_KEY);
    if (recentSteps.length > 0) {
      const last = recentSteps[recentSteps.length - 1];
      blocks.push({
        type: "statusCard",
        label: "\u6700\u8FD1\u4E00\u6B21\u6B65\u6570",
        value: Math.round(last.value) + " \u6B65",
        tone: "neutral",
        detail: last.date
      });
      blocks.push({
        type: "chart",
        title: "\u8FD1 " + recentSteps.length + " \u65E5\u6B65\u6570",
        chart: {
          kind: "bar",
          bars: recentSteps.map((r) => ({ label: r.date.slice(-5), value: r.value })),
          unit: " \u6B65",
          // 最后一根通常是今天，高亮它让用户一眼找到当前位置
          highlightIndex: recentSteps.length - 1
        }
      });
    } else {
      blocks.push({
        type: "text",
        title: "\u8FD8\u6CA1\u6709\u6B65\u6570\u6570\u636E",
        body: "\u70B9\u4E0B\u9762\u7684\u300C\u7ACB\u5373\u540C\u6B65\u300D\u62C9\u53D6\u4ECA\u5929\u7684\u6570\u636E\u3002"
      });
    }
    if (recentSleep.length > 0) {
      const last = recentSleep[recentSleep.length - 1];
      blocks.push({
        type: "statusCard",
        label: "\u6700\u8FD1\u4E00\u6B21\u7761\u7720",
        value: (last.value / 60).toFixed(1) + " \u5C0F\u65F6",
        tone: "neutral",
        detail: last.date
      });
      blocks.push({
        type: "chart",
        title: "\u8FD1 " + recentSleep.length + " \u65E5\u7761\u7720",
        chart: {
          kind: "line",
          // 库里存的是分钟，图上换成小时才符合日常表达
          points: recentSleep.map((r) => ({ label: r.date.slice(-5), value: r.value / 60 })),
          unit: " \u5C0F\u65F6"
        }
      });
      blocks.push({
        type: "text",
        title: "\u7EDF\u8BA1\u53E3\u5F84",
        body: "\u6309\u9192\u6765\u65E5\u7EDF\u8BA1\u6574\u591C\u7761\u7720\uFF1A\u6628\u665A 22:43 \u7761\u5230\u4ECA\u65E9 07:58 \u7B97\u4ECA\u5929\u8FD9\u4E00\u6761\uFF1B\u540C\u4E00\u591C\u7684\u591A\u6761\u8BB0\u5F55\uFF08\u624B\u673A\u7B97\u6CD5 + \u624B\u73AF\u6C47\u603B\uFF09\u5DF2\u5408\u5E76\uFF0C\u4E0D\u4F1A\u91CD\u590D\u7D2F\u52A0\u3002"
      });
      const deepRatio = recentValues(SLEEP_DEEP_RATIO_KEY);
      const efficiency = recentValues(SLEEP_EFFICIENCY_KEY);
      const regularity = recentValues(SLEEP_REGULARITY_KEY);
      const detailCards = [];
      if (deepRatio.length > 0) {
        detailCards.push({
          type: "statusCard",
          label: "\u6DF1\u7761\u5360\u6BD4",
          value: deepRatio[deepRatio.length - 1].value.toFixed(1) + " %",
          tone: "neutral",
          detail: deepRatio[deepRatio.length - 1].date
        });
      }
      if (efficiency.length > 0) {
        detailCards.push({
          type: "statusCard",
          label: "\u7761\u7720\u6548\u7387",
          value: efficiency[efficiency.length - 1].value.toFixed(1) + " %",
          tone: "neutral",
          detail: efficiency[efficiency.length - 1].date
        });
      }
      if (regularity.length > 0) {
        detailCards.push({
          type: "statusCard",
          label: "\u5165\u7761\u89C4\u5F8B\u6027",
          value: Math.round(regularity[regularity.length - 1].value) + " \u5206",
          tone: "neutral",
          detail: regularity[regularity.length - 1].date
        });
      }
      detailCards.forEach((b) => blocks.push(b));
    } else {
      blocks.push({
        type: "text",
        title: "\u8FD8\u6CA1\u6709\u7761\u7720\u6570\u636E",
        body: "\u6234\u624B\u73AF\u7761\u89C9\u540E\u70B9\u300C\u7ACB\u5373\u540C\u6B65\u300D\uFF0C\u7761\u7720\u624D\u4F1A\u88AB\u540C\u6B65\u8FC7\u6765\u3002"
      });
    }
    const loggedIn = auth.state === "loggedIn";
    blocks.push({
      type: "buttonRow",
      buttons: [
        {
          label: "\u7ACB\u5373\u540C\u6B65",
          actionId: ACTION_SYNC,
          primary: true,
          // 没登录时同步必然失败，直接禁用比让用户点了再看报错好
          enabled: loggedIn
        },
        {
          label: "\u56DE\u8865\u8FD1 " + BACKFILL_DAYS + " \u5929",
          actionId: ACTION_BACKFILL,
          // 回补只补原始条目（供查看核对），不改历史聚合值与得分
          enabled: loggedIn
        }
      ]
    });
    return blocks;
  } catch (e) {
    log2("e", "detailUi failed: " + describeError(e));
    return blocks.concat([{ type: "text", title: "\u8BE6\u60C5\u9875\u6E32\u67D3\u5931\u8D25", body: describeError(e) }]);
  }
}
function recentValues(metricKey) {
  try {
    const rows = ctx.recentMetrics(metricKey, DAYS);
    if (!rows || rows.length === 0) return [];
    const arr = rows.map((r) => ({ date: String(r.date || ""), value: Number(r.value) || 0 }));
    return arr.slice(-DAYS);
  } catch (e) {
    log2("w", "recentMetrics(" + metricKey + ") failed: " + describeError(e));
    return [];
  }
}
async function onAction(actionId) {
  try {
    if (actionId === ACTION_SYNC) {
      return { type: "triggerSync" };
    }
    if (actionId === ACTION_BACKFILL) {
      return await backfillHistory();
    }
    return { type: "noop" };
  } catch (e) {
    log2("e", "onAction(" + actionId + ") failed: " + describeError(e));
    return { type: "showMessage", text: "\u64CD\u4F5C\u5931\u8D25\uFF1A" + describeError(e) };
  }
}
async function backfillHistory() {
  const creds = loadCredentials();
  if (!isValidCreds(creds)) {
    return { type: "showMessage", text: "\u672A\u767B\u5F55\u5C0F\u7C73\u8D26\u53F7\uFF0C\u8BF7\u5148\u5728\u63D2\u4EF6\u9875\u767B\u5F55" };
  }
  const client = clientFor(creds);
  let filled = 0;
  let empty = 0;
  let failed = 0;
  const today = /* @__PURE__ */ new Date();
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
      const steps = await collectSteps(client, start, end, date);
      const sleep = await collectSleep(client, date);
      let other = 0;
      const groups = groupBy(SAMPLED, (s) => s.cloudKey);
      for (let i = 0; i < groups.length; i++) {
        const g = groups[i];
        try {
          other += (await collectSampled(client, g.key, g.items, start, end, date)).length;
        } catch (e) {
          log2("w", "\u56DE\u8865 " + g.key + "\uFF08" + date + "\uFF09\u5931\u8D25\uFF1A" + describeError(e));
        }
      }
      let goalCount = 0;
      try {
        goalCount = (await collectDailyGoals(client, date)).length;
      } catch (e) {
        log2("w", "\u56DE\u8865\u6BCF\u65E5\u76EE\u6807\uFF08" + date + "\uFF09\u5931\u8D25\uFF1A" + describeError(e));
      }
      if (steps.length > 0 || sleep.hasData || other > 0 || goalCount > 0) {
        filled++;
      } else {
        empty++;
      }
    } catch (e) {
      log2("w", "\u56DE\u8865 " + date + " \u5931\u8D25\uFF1A" + describeError(e));
      failed++;
    }
  }
  if (failed === 0 && empty === 0) {
    return { type: "showMessage", text: "\u5DF2\u56DE\u8865\u6700\u8FD1 " + filled + " \u5929\u7684\u539F\u59CB\u6570\u636E" };
  }
  if (filled === 0 && failed === 0) {
    return {
      type: "showMessage",
      text: "\u6700\u8FD1 " + BACKFILL_DAYS + " \u5929\u90FD\u6CA1\u6709\u627E\u5230\u53EF\u56DE\u8865\u7684\u8BB0\u5F55"
    };
  }
  const parts = ["\u5DF2\u56DE\u8865 " + filled + " \u5929"];
  if (empty > 0) parts.push(empty + " \u5929\u65E0\u8BB0\u5F55");
  if (failed > 0) parts.push(failed + " \u5929\u53D6\u6570\u5931\u8D25");
  return { type: "showMessage", text: parts.join("\uFF0C") };
}
function md5Upper(text) {
  return String(ctx.crypto.md5Hex(toUtf8(text))).toUpperCase();
}
function toUtf8(str) {
  const out = [];
  for (let i = 0; i < str.length; i++) {
    let code = str.charCodeAt(i);
    if (code >= 55296 && code <= 56319 && i + 1 < str.length) {
      const low = str.charCodeAt(i + 1);
      if (low >= 56320 && low <= 57343) {
        code = 65536 + (code - 55296 << 10) + (low - 56320);
        i++;
      }
    }
    if (code < 128) out.push(code);
    else if (code < 2048) out.push(192 | code >> 6, 128 | code & 63);
    else if (code < 65536) out.push(224 | code >> 12, 128 | code >> 6 & 63, 128 | code & 63);
    else out.push(240 | code >> 18, 128 | code >> 12 & 63, 128 | code >> 6 & 63, 128 | code & 63);
  }
  return out;
}
function hasUsableSsecurity(auth2) {
  const v = auth2 && auth2.ssecurity;
  return typeof v === "string" && v.length > 4;
}
async function handleAuth2(auth2, account) {
  const epoch = captureEpoch();
  if (!auth2) {
    pendingLogin = null;
    return { type: "failure", message: "\u767B\u5F55\u8BF7\u6C42\u5931\u8D25\uFF0C\u8BF7\u68C0\u67E5\u7F51\u7EDC" };
  }
  const code = typeof auth2.code === "number" ? auth2.code : -1;
  log2(
    "i",
    "serviceLoginAuth2 \u89E3\u6790\uFF1Acode=" + code + " ssecurity=" + (hasUsableSsecurity(auth2) ? "len" + String(auth2.ssecurity).length : "(\u7A7A\u6216\u65E0\u6548)") + " cUserId=" + (auth2.cUserId ? "len" + String(auth2.cUserId).length : "(\u7A7A)") + " passToken=" + (auth2.passToken ? "len" + String(auth2.passToken).length : "(\u7A7A)") + " \u6709 location=" + !!auth2.location + " \u6709 notificationUrl=" + !!auth2.notificationUrl
  );
  if (hasUsableSsecurity(auth2)) {
    log2("i", "auth2 \u5DF2\u5E26 ssecurity\uFF0C\u8D70 STS \u56DE\u8C03");
    return await completeLogin(auth2, account, epoch);
  }
  const notification = auth2.notificationUrl || "";
  if (notification) {
    log2("i", "\u6CA1\u6709 ssecurity \u4E14\u6709 notificationUrl\uFF0C\u8FDB\u5165 2FA \u6D41\u7A0B");
    return await start2FA(notification, auth2.ssecurity || "", account);
  }
  if (code === 0) {
    log2("e", "auth2 code=0 \u4F46\u7F3A\u5C11 ssecurity\uFF0C\u4E14\u65E0 notificationUrl\uFF0C\u65E0\u6CD5\u7EE7\u7EED");
    pendingLogin = null;
    return {
      type: "failure",
      message: "\u767B\u5F55\u672A\u5B8C\u6210\uFF1A\u670D\u52A1\u7AEF\u8FD4\u56DE\u6210\u529F\u4F46\u6CA1\u6709\u4E0B\u53D1\u4F1A\u8BDD\u5BC6\u94A5\uFF08ssecurity\uFF09\u3002\u8BF7\u786E\u8BA4\u8BE5\u8D26\u53F7\u5DF2\u5728\u5C0F\u7C73\u8FD0\u52A8\u5065\u5EB7\u5B98\u65B9 App \u4E2D\u5B8C\u6210\u8FC7\u9A8C\u8BC1\uFF0C\u7136\u540E\u91CD\u8BD5\u3002"
    };
  }
  if (code === 87001 || code === 70016 || auth2.captchaUrl) {
    log2("i", "\u9700\u8981\u56FE\u7247\u9A8C\u8BC1\u7801\uFF0Ccode=" + code);
    pendingLogin.stage = "captcha";
    const image = await fetchCaptcha(pendingLogin.session);
    return needCaptcha(pendingLogin, image);
  }
  log2("w", "\u767B\u5F55\u88AB\u62D2\uFF1Acode=" + code + " desc=" + (auth2.desc || ""));
  pendingLogin = null;
  return { type: "failure", message: friendlyError(code, auth2.desc || "") };
}
async function needCaptcha(pending, imageOverride) {
  const image = imageOverride != null ? imageOverride : await fetchCaptcha(pending.session);
  return {
    type: "needInput",
    prompt: "\u9700\u8981\u8F93\u5165\u56FE\u7247\u9A8C\u8BC1\u7801",
    // 验证码不掩码：用户要照着图抄，掩码只会让他抄错。
    // 掩码该留给密码这种长期秘密
    fields: [{ name: SLOT_CAPTCHA, displayName: "\u9A8C\u8BC1\u7801", secret: false }],
    captchaImage: image || void 0
  };
}
function needOtp(hint) {
  return {
    type: "needInput",
    prompt: hint ? "\u9A8C\u8BC1\u7801\u5DF2\u53D1\u9001\u81F3 " + hint : "\u9700\u8981\u4E8C\u6B21\u9A8C\u8BC1\u7801\uFF0C\u8BF7\u67E5\u770B\u624B\u673A\u77ED\u4FE1\u6216\u90AE\u7BB1",
    fields: [{ name: SLOT_OTP, displayName: "\u9A8C\u8BC1\u7801", secret: false, hint: "6 \u4F4D\u6570\u5B57" }]
  };
}
async function submitCaptcha(code) {
  const pending = pendingLogin;
  log2("i", "\u63D0\u4EA4\u56FE\u7247\u9A8C\u8BC1\u7801");
  const auth2 = await serviceLoginAuth2(
    pending.session,
    pending.account,
    md5Upper(pending.password),
    pending.handshake,
    code
  );
  return await handleAuth2(auth2, pending.account);
}
async function completeLogin(auth2, account, epoch) {
  const loc = auth2.location || "";
  if (!loc) {
    log2("e", "passport \u8FD4\u56DE\u6210\u529F\u4F46\u6CA1\u6709 location\uFF0C\u65E0\u6CD5\u6362 serviceToken");
    pendingLogin = null;
    return { type: "failure", message: "\u767B\u5F55\u6210\u529F\u4F46\u7F3A\u5C11 STS \u56DE\u8C03\u5730\u5740" };
  }
  const pending = pendingLogin;
  const token = await fetchServiceToken(pending.session, loc, pending.userId || "");
  log2("i", "STS \u56DE\u8C03\u5B8C\u6210\uFF0C\u62FF\u5230 serviceToken=" + !!token);
  if (!token) {
    const fromJar = usableServiceToken(
      pending.session.cookie("serviceToken", "sts-hlth.io.mi.com") || pending.session.cookie("serviceToken", "sts.api.io.mi.com")
    );
    if (!fromJar) {
      log2("e", "STS \u56DE\u8C03\u672A\u62FF\u5230 serviceToken\uFF0C\u53EF\u80FD\u88AB\u98CE\u63A7\u62E6\u622A");
      pendingLogin = null;
      return { type: "failure", message: "\u672A\u62FF\u5230 serviceToken\uFF0C\u53EF\u80FD\u88AB\u98CE\u63A7\u62E6\u622A" };
    }
    return finishLogin(auth2, fromJar, account, epoch);
  }
  return finishLogin(auth2, token, account, epoch);
}
function usableServiceToken(value) {
  if (!value || value === "EXPIRED" || value === SID) return "";
  return value;
}
async function probeCredentials(creds) {
  const client = clientFor(creds);
  const now = Date.now();
  try {
    const points = await client.getFitnessDataByTime("steps", now - DAY_MILLIS, now, 1);
    log2("i", "\u51ED\u636E\u63A2\u6D4B\u901A\u8FC7\uFF0C\u91C7\u6837\u70B9 " + (points ? points.length : 0) + " \u4E2A");
    return "";
  } catch (e) {
    log2("w", "\u51ED\u636E\u63A2\u6D4B\u5F02\u5E38\uFF1A" + describeError(e));
    return describeError(e);
  }
}
async function finishLogin(auth2, serviceToken, account, epoch) {
  const session = pendingLogin && pendingLogin.session;
  const fromJar = (name) => {
    try {
      return session && session.cookie(name) || "";
    } catch (e) {
      return "";
    }
  };
  const creds = {
    ssecurity: auth2.ssecurity || "",
    serviceToken: serviceToken || usableServiceToken(fromJar("serviceToken")),
    cUserId: auth2.cUserId || fromJar("cUserId"),
    userId: auth2.userId ? String(auth2.userId) : fromJar("userId"),
    passToken: auth2.passToken || fromJar("passToken"),
    account
  };
  if (!creds.ssecurity) {
    log2("e", "\u7F3A\u5C11 ssecurity\uFF0C\u65E0\u6CD5\u6D3E\u751F sessionKey");
    pendingLogin = null;
    return { type: "failure", message: "\u767B\u5F55\u5DF2\u901A\u8FC7\u4F46\u7F3A\u5C11 ssecurity\uFF0C\u65E0\u6CD5\u89E3\u5BC6\u6570\u636E\u63A5\u53E3" };
  }
  if (!creds.cUserId) {
    log2("e", "\u7F3A\u5C11 cUserId\uFF1Aauth2 \u4E0E\u4F1A\u8BDD cookie \u90FD\u6CA1\u6709");
    pendingLogin = null;
    return {
      type: "failure",
      message: "\u767B\u5F55\u5DF2\u901A\u8FC7\u4F46\u6CA1\u6709\u62FF\u5230\u7528\u6237\u6807\u8BC6\uFF08cUserId\uFF09\uFF0C\u65E0\u6CD5\u8BBF\u95EE\u6570\u636E\u63A5\u53E3\u3002\u8BF7\u91CD\u8BD5\u767B\u5F55\u3002"
    };
  }
  if (epoch !== sessionEpoch) {
    log2("w", "\u767B\u5F55\u671F\u95F4\u7528\u6237\u5DF2\u767B\u51FA\uFF0C\u51ED\u636E\u4E0D\u843D\u76D8");
    pendingLogin = null;
    return { type: "failure", message: "\u767B\u5F55\u5DF2\u53D6\u6D88" };
  }
  log2(
    "i",
    "\u51C6\u5907\u843D\u76D8\uFF1Assecurity=len" + creds.ssecurity.length + " serviceToken=len" + creds.serviceToken.length + " cUserId=len" + creds.cUserId.length + " passToken=len" + creds.passToken.length
  );
  const probeError = await probeCredentials(creds);
  if (probeError) {
    pendingLogin = null;
    return {
      type: "failure",
      message: "\u767B\u5F55\u5DF2\u901A\u8FC7\uFF0C\u4F46\u7528\u8FD9\u5957\u51ED\u636E\u8BBF\u95EE\u5C0F\u7C73\u5065\u5EB7\u6570\u636E\u63A5\u53E3\u88AB\u62D2\u7EDD\uFF08" + probeError + "\uFF09\u3002\u8FD9\u901A\u5E38\u610F\u5473\u7740\u8D26\u53F7\u9700\u8981\u5728\u5C0F\u7C73\u8FD0\u52A8\u5065\u5EB7\u5B98\u65B9 App \u91CC\u91CD\u65B0\u6388\u6743\u4E00\u6B21\uFF0C\u7136\u540E\u56DE\u6765\u91CD\u8BD5\u767B\u5F55\u3002"
    };
  }
  saveCredentials(creds);
  pendingLogin = null;
  log2("i", "\u767B\u5F55\u6210\u529F\uFF0C\u51ED\u636E\u5DF2\u4FDD\u5B58\uFF0Caccount=" + maskAccount(account));
  return { type: "success" };
}
async function start2FA(notificationUrl, auth2Ssecurity, account) {
  const pending = pendingLogin;
  const abs = absoluteUrl(ACCOUNT, notificationUrl);
  const ctxParam = queryParamOf(abs, "context");
  const identityUserId = queryParamOf(abs, "userId");
  try {
    const startRes = await pending.session.send("GET", abs, {
      userAgent: identityUa(deviceId(), identityUserId),
      headers: { Referer: ACCOUNT + "/" }
    });
    log2("i", "authStart \u72B6\u6001=" + (startRes && startRes.status));
  } catch (e) {
    log2("w", "start2FA: authStart failed: " + describeError(e));
  }
  const listUrl = IDENTITY + "/list?sid=" + encodeURIComponent(SID) + "&supportedMask=0&_locale=zh_CN&context=" + encodeURIComponent(ctxParam);
  const listRes = await pending.session.send("GET", listUrl, {
    userAgent: identityUa(deviceId(), identityUserId),
    headers: { Referer: ACCOUNT + "/", "Content-Type": "application/x-www-form-urlencoded" }
  });
  const listJson = parseMiapi(listRes.body);
  if (!listJson) {
    log2("e", "2FA \u7684 /list \u8FD4\u56DE\u65E0\u6CD5\u89E3\u6790\uFF1A" + String(listRes.body || "").slice(0, 200));
    pendingLogin = null;
    return { type: "failure", message: "\u65E0\u6CD5\u83B7\u53D6\u4E8C\u6B21\u9A8C\u8BC1\u65B9\u5F0F" };
  }
  const flag = typeof listJson.flag === "number" ? listJson.flag : 4;
  const method = flag === 8 ? "Email" : "Phone";
  log2("i", "2FA \u65B9\u5F0F\uFF1Aflag=" + flag + " method=" + method + " context=" + (ctxParam || "(\u7A7A)"));
  const verifyUrl = IDENTITY + "/auth/verify" + method + "?_dc=" + Date.now() + "&_flag=" + flag + "&_json=true&sid=" + encodeURIComponent(SID) + "&context=" + encodeURIComponent(ctxParam || "");
  const verifyRes = await pending.session.send("GET", verifyUrl, {
    userAgent: identityUa(deviceId(), identityUserId),
    headers: { Referer: ACCOUNT + "/" },
    // identity 端点偶发挂住（日志里有过等 37 秒才返回超时的情况），
    // 默认 30s 叠加重试会拖到 60s 以上，直接吃掉宿主的单次调用预算
    timeoutMs: IDENTITY_TIMEOUT_MS
  });
  log2(
    "i",
    "verify" + method + " \u72B6\u6001=" + (verifyRes && verifyRes.status) + " body=" + String(verifyRes && verifyRes.body || "").replace(/^&&&START&&&/, "").slice(0, 200)
  );
  if (method === "Phone") {
    const sendUrl = IDENTITY + "/auth/sendPhoneTicket";
    const sendRes = await pending.session.send("POST", sendUrl, {
      userAgent: identityUa(deviceId(), identityUserId),
      headers: { Referer: ACCOUNT + "/", "Content-Type": "application/x-www-form-urlencoded" },
      body: "retry=0&icode=&_json=true",
      contentType: "application/x-www-form-urlencoded",
      timeoutMs: IDENTITY_TIMEOUT_MS
    });
    log2(
      "i",
      "sendPhoneTicket \u72B6\u6001=" + (sendRes && sendRes.status) + " body=" + String(sendRes && sendRes.body || "").replace(/^&&&START&&&/, "").slice(0, 300)
    );
  }
  const hint = ["mask", "notification", "email", "phone", "address"].map((k) => listJson[k]).find((v) => typeof v === "string" && v !== "") || "";
  pendingLogin = {
    stage: "twoFactor",
    session: pending.session,
    account: pending.account,
    password: pending.password,
    handshake: pending.handshake,
    userId: identityUserId,
    context: ctxParam,
    flag,
    method,
    hint,
    auth2Ssecurity
  };
  return needOtp(hint);
}
async function submit2FA(code) {
  const pending = pendingLogin;
  const identityUserId = pending.userId || "";
  const epoch = captureEpoch();
  const verifyUrl = IDENTITY + "/auth/verify" + pending.method + "?_dc=" + Date.now() + "&_flag=" + pending.flag + "&_json=true&sid=" + encodeURIComponent(SID) + "&context=" + encodeURIComponent(pending.context || "");
  const res = await pending.session.send("POST", verifyUrl, {
    userAgent: identityUa(deviceId(), identityUserId),
    headers: { Referer: ACCOUNT + "/", "Content-Type": "application/x-www-form-urlencoded" },
    body: "_flag=" + pending.flag + "&ticket=" + encodeURIComponent(String(code).trim()) + "&trust=false&_json=true",
    contentType: "application/x-www-form-urlencoded",
    followRedirects: false
  });
  const json = parseMiapi(res.body);
  const vcode = json && typeof json.code === "number" ? json.code : 0;
  let loc = json && json.location || res.headers && res.headers["location"] || "";
  if (!loc) {
    const m = String(res.body || "").match(/location\s*[:=]\s*["']([^"']+)/);
    if (m) loc = m[1];
  }
  log2("i", "\u63D0\u4EA4 2FA \u9A8C\u8BC1\u7801\uFF1Acode=" + vcode + " \u6709 location=" + !!loc);
  if (vcode !== 0 && !loc) {
    return {
      type: "failure",
      message: "\u9A8C\u8BC1\u7801\u6821\u9A8C\u672A\u901A\u8FC7\uFF08code=" + vcode + " " + (json && json.desc || "") + "\uFF09"
    };
  }
  if (!loc) {
    return { type: "failure", message: "\u9A8C\u8BC1\u7801\u901A\u8FC7\u4F46\u672A\u62FF\u5230\u8DF3\u8F6C\u5730\u5740" };
  }
  try {
    let current = absoluteUrl(ACCOUNT, loc);
    for (let hop = 0; hop < 10; hop++) {
      const r = await pending.session.send("GET", current, {
        userAgent: identityUa(deviceId(), identityUserId),
        followRedirects: false
      });
      const status = r && r.status || 0;
      const next = r && r.headers && r.headers["location"] || "";
      log2(
        "d",
        "\u8BA4\u8BC1\u8DF3\u8F6C hop" + hop + " http=" + status + " setCookie=" + (r && r.setCookie || []).length + " next=" + (next ? next.slice(0, 80) : "-")
      );
      if (status < 300 || status >= 400 || !next) break;
      const resolved = absoluteUrl(current, next);
      if (resolved === current) {
        log2("w", "\u8BA4\u8BC1\u8DF3\u8F6C\u539F\u5730\u8FD4\u56DE\uFF0C\u505C\u6B62\uFF1A" + shortUrl(current));
        break;
      }
      current = resolved;
    }
  } catch (e) {
    log2("w", "submit2FA: visit location failed: " + describeError(e));
  }
  log2("i", "2FA \u540E\u4F1A\u8BDD cookie=[" + pending.session.names().join(",") + "]");
  const jar = { value: (n, domain) => pending.session.cookie(n, domain) };
  let cUserId = jar.value("cUserId") || "";
  let passToken = jar.value("passToken") || "";
  let userId = jar.value("userId") || "";
  let serviceToken = jar.value("serviceToken", "sts-hlth.io.mi.com") || jar.value("serviceToken", "sts.api.io.mi.com") || "";
  if (serviceToken === SID || serviceToken === "EXPIRED") {
    log2("w", "\u4F1A\u8BDD\u91CC\u7684 serviceToken \u662F\u6807\u8BB0\u503C\uFF08" + serviceToken + "\uFF09\uFF0C\u6309\u7A7A\u5904\u7406");
    serviceToken = "";
  }
  let ssecurity = "";
  try {
    const again = await serviceLoginRaw(pending.session);
    const againJson = again && again.json;
    log2(
      "i",
      "\u91CD\u505A serviceLogin \u539F\u59CB\u54CD\u5E94\uFF1Astatus=" + (again && again.status) + " body=" + String(again && again.raw || "").replace(/^&&&START&&&/, "").slice(0, 1200)
    );
    if (againJson) {
      ssecurity = againJson.ssecurity || "";
      if (ssecurity) {
        log2("i", "\u91CD\u505A serviceLogin \u53D6\u5230 ssecurity\uFF0C\u957F\u5EA6=" + ssecurity.length);
      } else if (againJson.psecurity) {
        log2("i", "\u91CD\u505A\u54CD\u5E94\u53EA\u6709 psecurity\uFF08\u4E0D\u80FD\u5F53\u4F1A\u8BDD\u5BC6\u94A5\u7528\uFF09\uFF0C\u6539\u8D70 passToken \u7EED\u671F");
      }
      if (!cUserId) cUserId = againJson.cUserId || "";
      if (!passToken) passToken = againJson.passToken || "";
      if (!userId) userId = againJson.userId ? String(againJson.userId) : "";
      if (againJson.location) {
        const renewed = await fetchServiceToken(
          pending.session,
          againJson.location,
          identityUserId
        );
        if (renewed) {
          log2("i", "\u6309\u91CD\u505A\u54CD\u5E94\u7684 location \u91CD\u65B0\u6362\u53D6 serviceToken\uFF0C\u957F\u5EA6=" + renewed.length + (serviceToken ? "\uFF08\u8986\u76D6\u524D\u957F\u5EA6=" + serviceToken.length + "\uFF09" : ""));
          serviceToken = renewed;
        }
      }
    }
    const cookieNames = pending.session.names().join(",");
    log2(
      "i",
      "\u91CD\u505A serviceLogin\uFF1Acode=" + (againJson && againJson.code) + " \u6709 ssecurity=" + !!ssecurity + " \u6709 passToken=" + !!passToken + " passToken\u957F\u5EA6=" + passToken.length + " \u6709 serviceToken=" + !!serviceToken + " \u4F1A\u8BDD cookie=[" + cookieNames + "]"
    );
  } catch (e) {
    log2("w", "submit2FA: redo serviceLogin failed: " + describeError(e));
  }
  if (!ssecurity) {
    const passInfoRaw = jar.value("passInfo");
    ssecurity = extractSsecurity(passInfoRaw);
    log2(
      "i",
      "passInfo \u515C\u5E95\uFF1A" + (passInfoRaw ? "\u6709\u503C(\u957F\u5EA6" + String(passInfoRaw).length + ")" : "\u4E0D\u5B58\u5728") + " \u89E3\u6790\u51FA ssecurity=" + (ssecurity ? "len" + ssecurity.length : "\u5426")
    );
  }
  if (!ssecurity && passToken) {
    const refreshed = await refreshWithPassToken(passToken, cUserId, userId, pending.account);
    if (isValidCreds(refreshed)) {
      pendingLogin = null;
      if (epoch !== sessionEpoch) {
        return { type: "failure", message: "\u767B\u5F55\u5DF2\u53D6\u6D88" };
      }
      const refreshProbeError = await probeCredentials(refreshed);
      if (refreshProbeError) {
        return {
          type: "failure",
          message: "\u514D\u5BC6\u7EED\u671F\u62FF\u5230\u7684\u51ED\u636E\u65E0\u6CD5\u8BBF\u95EE\u6570\u636E\u63A5\u53E3\uFF08" + refreshProbeError + "\uFF09\uFF0C\u8BF7\u5728\u5C0F\u7C73\u8FD0\u52A8\u5065\u5EB7\u5B98\u65B9 App \u4E2D\u91CD\u65B0\u6388\u6743\u540E\u518D\u8BD5\u3002"
        };
      }
      saveCredentials(refreshed);
      log2("i", "\u7ECF passToken \u7EED\u671F\u767B\u5F55\u6210\u529F\uFF0C\u51ED\u636E\u5DF2\u4FDD\u5B58");
      return { type: "success" };
    }
  }
  if (!ssecurity || !serviceToken) {
    pendingLogin = null;
    log2(
      "e",
      "2FA \u672A\u80FD\u51D1\u9F50\u51ED\u636E\uFF1A\u7F3A" + (ssecurity ? "serviceToken" : "ssecurity") + "\uFF08passToken\u957F\u5EA6=" + passToken.length + " cUserId\u957F\u5EA6=" + cUserId.length + " userId\u957F\u5EA6=" + userId.length + "\uFF09"
    );
    return {
      type: "failure",
      message: "\u4E8C\u6B21\u9A8C\u8BC1\u5DF2\u901A\u8FC7\uFF0C\u4F46\u672A\u80FD\u6362\u5230\u53EF\u7528\u7684\u4F1A\u8BDD\u5BC6\u94A5\uFF08\u7F3A" + (ssecurity ? "serviceToken" : "ssecurity") + "\uFF09\u3002\u8BF7\u786E\u8BA4\u8BE5\u8D26\u53F7\u5DF2\u5728\u5C0F\u7C73\u8FD0\u52A8\u5065\u5EB7\u5B98\u65B9 App \u4E2D\u6B63\u5E38\u767B\u5F55\u8FC7\uFF0C\u7136\u540E\u91CD\u8BD5\u3002"
    };
  }
  pendingLogin = null;
  if (epoch !== sessionEpoch) {
    return { type: "failure", message: "\u767B\u5F55\u5DF2\u53D6\u6D88" };
  }
  const twoFactorCreds = {
    ssecurity,
    serviceToken,
    cUserId,
    userId,
    passToken,
    account: pending.account
  };
  const twoFactorProbeError = await probeCredentials(twoFactorCreds);
  if (twoFactorProbeError) {
    return {
      type: "failure",
      message: "\u4E8C\u6B21\u9A8C\u8BC1\u5DF2\u901A\u8FC7\uFF0C\u4F46\u8FD9\u5957\u51ED\u636E\u8BBF\u95EE\u6570\u636E\u63A5\u53E3\u88AB\u62D2\u7EDD\uFF08" + twoFactorProbeError + "\uFF09\uFF0C\u8BF7\u5728\u5C0F\u7C73\u8FD0\u52A8\u5065\u5EB7\u5B98\u65B9 App \u4E2D\u91CD\u65B0\u6388\u6743\u540E\u518D\u8BD5\u3002"
    };
  }
  saveCredentials(twoFactorCreds);
  log2("i", "2FA \u767B\u5F55\u6210\u529F\uFF0C\u51ED\u636E\u5DF2\u4FDD\u5B58\uFF0Caccount=" + maskAccount(pending.account));
  return { type: "success" };
}
function extractSsecurity(passInfo) {
  if (!passInfo) return "";
  const candidates = [passInfo];
  try {
    candidates.push(decodeURIComponent(passInfo));
  } catch (e) {
  }
  try {
    const bytes = b64decode(passInfo);
    if (bytes && bytes.length > 0) {
      let text = "";
      for (let i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i] & 255);
      if (/^[\x09\x0a\x0d\x20-\x7e]*$/.test(text)) candidates.push(text);
    }
  } catch (e) {
  }
  for (let i = 0; i < candidates.length; i++) {
    const text = candidates[i];
    try {
      const o = JSON.parse(text);
      if (o && typeof o.ssecurity === "string" && o.ssecurity) return o.ssecurity;
    } catch (e) {
    }
    const m1 = text.match(/"ssecurity"\s*:\s*"([^"]+)"/);
    if (m1) return m1[1];
    const m2 = text.match(/(?:^|[&;\s])ssecurity=([^&;\s"']+)/);
    if (m2) {
      try {
        return decodeURIComponent(m2[1]);
      } catch (e) {
        return m2[1];
      }
    }
  }
  return "";
}
function queryParamOf(url, name) {
  const idx = String(url).indexOf("?");
  if (idx < 0) return "";
  const pairs = String(url).slice(idx + 1).split("&");
  for (let i = 0; i < pairs.length; i++) {
    const eq = pairs[i].indexOf("=");
    const k = eq < 0 ? pairs[i] : pairs[i].slice(0, eq);
    if (k === name) {
      const v = eq < 0 ? "" : pairs[i].slice(eq + 1);
      try {
        return decodeURIComponent(v);
      } catch (e) {
        return v;
      }
    }
  }
  return "";
}
function friendlyError(code, desc) {
  if (code === 70016) {
    return "\u767B\u5F55\u9A8C\u8BC1\u5931\u8D25\uFF0870016\uFF09\uFF1A\u5C0F\u7C73\u62D2\u7EDD\u4E86\u672C\u6B21\u8BF7\u6C42\uFF0C\u901A\u5E38\u662F\u8D26\u53F7\u89E6\u53D1\u4E86\u98CE\u63A7\u6216\u9700\u8981\u5148\u5728\u5B98\u65B9 App \u5B8C\u6210\u4E00\u6B21\u9A8C\u8BC1";
  }
  if (code === 70002) return "\u8D26\u53F7\u6216\u5BC6\u7801\u9519\u8BEF";
  if (code === 70003) return "\u8D26\u53F7\u4E0D\u5B58\u5728";
  if (code === 87001) return "\u9A8C\u8BC1\u7801\u8F93\u5165\u9519\u8BEF\uFF0C\u8BF7\u91CD\u65B0\u586B\u5199";
  return "\u767B\u5F55\u5931\u8D25\uFF08" + code + "\uFF09\uFF1A" + (desc || "\u672A\u77E5\u9519\u8BEF");
}
export {
  authState,
  detailUi,
  fetch,
  health,
  login,
  logout,
  onAction
};
