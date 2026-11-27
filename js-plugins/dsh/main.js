// dsh/qjs-shim.ts
var QjsTextEncoder = class {
  constructor() {
    this.encoding = "utf-8";
  }
  encode(text = "") {
    return Uint8Array.from(ctx.codec.utf8Encode(text));
  }
};
var QjsTextDecoder = class {
  constructor() {
    this.encoding = "utf-8";
  }
  decode(bytes) {
    if (bytes === void 0) return "";
    return ctx.codec.utf8Decode(Array.from(bytes, (b) => b & 255));
  }
};
function qjsBtoa(binary) {
  const bytes = [];
  for (let i = 0; i < binary.length; i += 1) bytes.push(binary.charCodeAt(i) & 255);
  return ctx.codec.base64Encode(bytes);
}
function qjsAtob(base64) {
  const bytes = ctx.codec.base64Decode(base64);
  let out = "";
  for (let i = 0; i < bytes.length; i += 1) out += String.fromCharCode(bytes[i]);
  return out;
}
var qjsCrypto = {
  getRandomValues(target) {
    const view = target;
    if (view.length > 65536) throw new Error("getRandomValues: \u5355\u6B21\u6700\u591A 65536 \u5B57\u8282");
    const bytes = ctx.crypto.randomBytes(view.length);
    for (let i = 0; i < view.length; i += 1) {
      target[i] = bytes[i];
    }
    return target;
  }
};
var globals = globalThis;
if (typeof globals.TextEncoder !== "function") globals.TextEncoder = QjsTextEncoder;
if (typeof globals.TextDecoder !== "function") globals.TextDecoder = QjsTextDecoder;
if (typeof globals.btoa !== "function") globals.btoa = qjsBtoa;
if (typeof globals.atob !== "function") globals.atob = qjsAtob;
if (!globals.crypto) globals.crypto = qjsCrypto;

// dsh/gateway.ts
function parsePairingLink(raw) {
  const text = (raw ?? "").trim();
  if (text.length === 0) return null;
  const queryIndex = text.indexOf("?");
  const hashIndex = text.indexOf("#");
  let params = "";
  if (hashIndex >= 0) params = text.slice(hashIndex + 1);
  else if (queryIndex >= 0) params = text.slice(queryIndex + 1);
  if (params.length === 0) return null;
  let instance = "";
  let token = "";
  for (const pair of params.split("&")) {
    const eq = pair.indexOf("=");
    if (eq <= 0) continue;
    const key = pair.slice(0, eq);
    const value = pair.slice(eq + 1);
    if (key === "instance") instance = value.trim().toLowerCase();
    else if (key === "token") token = value.trim();
  }
  if (instance.length !== 64 || token.length === 0) return null;
  const origin = /^(https?:\/\/[^/?#\s]+)/i.exec(text);
  return {
    gateway: origin === null ? "" : origin[1],
    instance,
    token
  };
}
var CA_PATH = "/mobile-access/ca.cer";
var AUTH_PREFIX = "/mobile-access/auth";
var COOKIE_SESSION = "__Host-dsh_ma_session";
var COOKIE_DEVICE = "__Host-dsh_ma_device_id";
var CSRF_HEADER = "x-dsh-mobile-csrf";
var GATEWAY_SESSION = "gateway";
var GatewayError = class extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
};
function normalizeGatewayUrl(input) {
  const raw = (input ?? "").trim();
  if (raw.length === 0) throw new GatewayError(0, "GATEWAY_EMPTY", "\u7F51\u5173\u5730\u5740\u4E0D\u80FD\u4E3A\u7A7A");
  const match = /^(https?):\/\/([^/?#]+)([/?#].*)?$/i.exec(raw);
  if (match === null) throw new GatewayError(0, "GATEWAY_INVALID", `\u7F51\u5173\u5730\u5740\u4E0D\u5408\u6CD5\uFF1A${raw}`);
  if (match[1].toLowerCase() !== "https") {
    throw new GatewayError(0, "GATEWAY_INSECURE", "\u7F51\u5173\u5FC5\u987B\u4F7F\u7528 HTTPS");
  }
  const authority = match[2];
  if (authority.includes("@")) {
    throw new GatewayError(0, "GATEWAY_INVALID", "\u7F51\u5173\u5730\u5740\u4E0D\u80FD\u5305\u542B\u7528\u6237\u540D\u6216\u5BC6\u7801");
  }
  if (!/^[A-Za-z0-9._-]+(:\d{1,5})?$/.test(authority) && !/^\[[0-9A-Fa-f:.]+\](:\d{1,5})?$/.test(authority)) {
    throw new GatewayError(0, "GATEWAY_INVALID", `\u7F51\u5173\u4E3B\u673A\u540D\u4E0D\u5408\u6CD5\uFF1A${authority}`);
  }
  const rest = match[3];
  if (rest !== void 0 && rest !== "/" && rest.length > 0) {
    throw new GatewayError(0, "GATEWAY_INVALID", "\u7F51\u5173\u5730\u5740\u53EA\u80FD\u662F\u6E90\uFF0C\u4E0D\u8981\u5E26\u8DEF\u5F84");
  }
  return `https://${authority}`;
}
function hostOf(gateway) {
  const match = /^https:\/\/([^/]+)$/.exec(gateway);
  if (match === null) throw new GatewayError(0, "GATEWAY_INVALID", `\u7F51\u5173\u5730\u5740\u672A\u89C4\u8303\u5316\uFF1A${gateway}`);
  return match[1];
}
function cookieHostOf(gateway) {
  return hostOf(gateway).replace(/:\d+$/, "");
}
function deriveMuxUrl(gateway) {
  return `wss://${hostOf(gateway)}/api/remote.mux`;
}
async function gatewayFetch(gateway, req) {
  const host = hostOf(gateway);
  const headers = {
    accept: "application/json",
    // 必须精确等于网关源，且小写（`network.ts:224-228`）
    origin: gateway,
    host
  };
  if (req.csrf !== void 0 && req.method !== "GET") {
    headers[CSRF_HEADER] = req.csrf;
  }
  const response = await ctx.http.request(
    req.method,
    `${gateway}${req.path}`,
    req.body === void 0 ? null : JSON.stringify(req.body),
    {
      headers,
      contentType: "application/json; charset=utf-8",
      timeoutMs: req.timeoutMs ?? 15e3,
      // 配对会一次性消费配对码、续期会轮换 sessionToken，重发都不安全
      retry: false
    },
    req.session
  );
  const payload = response.json;
  if (response.status >= 200 && response.status < 300) {
    if (payload === null || typeof payload !== "object") {
      throw new GatewayError(response.status, "BAD_RESPONSE", "\u7F51\u5173\u8FD4\u56DE\u7684\u4E0D\u662F JSON \u5BF9\u8C61");
    }
    return payload;
  }
  const code = typeof payload?.error === "string" ? payload.error : typeof payload?.code === "string" ? payload.code : `HTTP_${response.status}`;
  const message = typeof payload?.message === "string" && payload.message.length > 0 ? payload.message : describeStatus(response.status, code);
  throw new GatewayError(response.status, code, message);
}
function describeStatus(status, code) {
  if (code === "source_network_untrusted") {
    return "\u5F53\u524D\u7F51\u7EDC\u4E0D\u5728\u7535\u8111\u5141\u8BB8\u7684\u8303\u56F4\u5185\uFF0C\u8BF7\u786E\u8BA4\u624B\u673A\u548C\u7535\u8111\u5728\u540C\u4E00\u4E2A\u5C40\u57DF\u7F51";
  }
  if (code === "authentication_failed") {
    return "\u914D\u5BF9\u7801\u65E0\u6548\u6216\u5DF2\u8FC7\u671F\uFF0C\u8BF7\u5728\u7535\u8111\u7AEF\u91CD\u65B0\u751F\u6210\u914D\u5BF9\u7801";
  }
  if (code === "rate_limited") {
    return "\u5C1D\u8BD5\u8FC7\u4E8E\u9891\u7E41\uFF0C\u8BF7\u7A0D\u5019\u518D\u8BD5";
  }
  if (code === "invalid_request") {
    return "\u8BF7\u6C42\u53C2\u6570\u88AB\u7F51\u5173\u62D2\u7EDD\uFF0C\u8BF7\u68C0\u67E5\u914D\u5BF9\u7801\u662F\u5426\u4E3A 43 \u4F4D";
  }
  if (status === 401) return "\u767B\u5F55\u72B6\u6001\u5DF2\u5931\u6548\uFF0C\u8BF7\u91CD\u65B0\u914D\u5BF9";
  if (status === 403) return "\u7F51\u5173\u62D2\u7EDD\u4E86\u672C\u6B21\u8BF7\u6C42\uFF08\u57DF\u540D\u6216\u6765\u6E90\u6821\u9A8C\u672A\u901A\u8FC7\uFF09";
  if (status === 404) return "\u7F51\u5173\u6CA1\u6709\u8FD9\u4E2A\u63A5\u53E3\uFF0C\u8BF7\u786E\u8BA4\u7535\u8111\u7AEF\u7684 dsh-mobile \u5DF2\u66F4\u65B0";
  if (status === 415) return "\u8BF7\u6C42\u683C\u5F0F\u4E0D\u88AB\u63A5\u53D7\uFF08\u9700\u8981 application/json\uFF09";
  return `\u8BF7\u6C42\u5931\u8D25\uFF08HTTP ${status}\uFF09`;
}
async function pinGatewayCa(gateway, expectedFingerprint) {
  const expected = (expectedFingerprint ?? "").trim().toLowerCase();
  if (expected.length !== 64) {
    throw new GatewayError(0, "INSTANCE_INVALID", "\u914D\u5BF9\u94FE\u63A5\u91CC\u7684\u5B9E\u4F8B\u6307\u7EB9\u683C\u5F0F\u4E0D\u5BF9\uFF0C\u8BF7\u6574\u6761\u590D\u5236");
  }
  const caBase64 = await ctx.http.caCertificate(`${gateway}${CA_PATH}`);
  ctx.http.trustCa(caBase64, expected);
  ctx.log.i("dsh", "\u5DF2\u4FE1\u4EFB\u7F51\u5173 CA");
}
function readPairResult(body) {
  const need = (key) => {
    const value = body[key];
    if (typeof value !== "string" || value.length === 0) {
      throw new GatewayError(0, "BAD_RESPONSE", `\u914D\u5BF9\u54CD\u5E94\u7F3A\u5C11 ${key}`);
    }
    return value;
  };
  return {
    instanceId: need("instanceId"),
    deviceId: need("deviceId"),
    deviceToken: need("deviceToken"),
    deviceExpiresAt: typeof body.deviceExpiresAt === "number" ? body.deviceExpiresAt : 0,
    sessionToken: need("sessionToken"),
    csrfToken: need("csrfToken"),
    sessionExpiresAt: typeof body.sessionExpiresAt === "number" ? body.sessionExpiresAt : 0
  };
}
async function pairDevice(gateway, pairingCode, label) {
  const code = (pairingCode ?? "").trim();
  if (code.length !== 43) {
    throw new GatewayError(0, "PAIRING_CODE_INVALID", "\u914D\u5BF9\u7801\u5E94\u4E3A 43 \u4F4D\uFF0C\u8BF7\u4ECE\u7535\u8111\u7AEF\u5B8C\u6574\u590D\u5236");
  }
  const body = await gatewayFetch(gateway, {
    method: "POST",
    path: `${AUTH_PREFIX}/native-pair`,
    body: { token: code, label }
  });
  return readPairResult(body);
}
async function renewSession(gateway, deviceToken) {
  const body = await gatewayFetch(gateway, {
    method: "POST",
    path: `${AUTH_PREFIX}/native-renew`,
    body: { deviceToken }
  });
  const need = (key) => {
    const value = body[key];
    if (typeof value !== "string" || value.length === 0) {
      throw new GatewayError(0, "BAD_RESPONSE", `\u7EED\u671F\u54CD\u5E94\u7F3A\u5C11 ${key}`);
    }
    return value;
  };
  return {
    instanceId: need("instanceId"),
    deviceId: need("deviceId"),
    sessionToken: need("sessionToken"),
    csrfToken: need("csrfToken"),
    sessionExpiresAt: typeof body.sessionExpiresAt === "number" ? body.sessionExpiresAt : 0
  };
}
function openGatewaySession(gateway, sessionToken, name = GATEWAY_SESSION) {
  const host = hostOf(gateway);
  const cookieHost = cookieHostOf(gateway);
  const session = ctx.http.session(name, true);
  ctx.http.cookieSet(session, COOKIE_SESSION, sessionToken, cookieHost);
  ctx.http.cookieSet(session, COOKIE_DEVICE, sessionToken.slice(0, 8), cookieHost);
  ctx.http.setHeader(session, "Origin", gateway);
  ctx.http.setHeader(session, "Host", host);
  return session;
}
function gatewayCookieHeader() {
  const all = ctx.http.cookieAll(GATEWAY_SESSION);
  const parts = [];
  for (const name of Object.keys(all)) {
    const value = all[name];
    if (typeof value === "string" && value.length > 0) parts.push(`${name}=${value}`);
  }
  return parts.join("; ");
}
function closeGatewaySession(name = "gateway") {
  ctx.http.cookieClear(name);
}
var SLOT_PAIRING = "gateway-pairing";
function loadPairing() {
  const raw = ctx.credentials.get(SLOT_PAIRING);
  if (typeof raw !== "string" || raw.length === 0) return null;
  try {
    const parsed = JSON.parse(raw);
    if (typeof parsed.gateway !== "string" || typeof parsed.deviceToken !== "string") return null;
    if (typeof parsed.sessionToken !== "string") return null;
    return parsed;
  } catch {
    return null;
  }
}
function savePairing(pairing) {
  ctx.credentials.set(SLOT_PAIRING, JSON.stringify(pairing));
}
function clearPairing() {
  ctx.credentials.set(SLOT_PAIRING, null);
}
function sessionNeedsRenew(expiresAt, now = Date.now(), skewMs = 5 * 6e4) {
  if (!Number.isFinite(expiresAt) || expiresAt <= 0) return true;
  return expiresAt - now <= skewMs;
}

// dsh/mux.ts
var MuxError = class extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.code = code;
    this.details = details;
  }
};
var MuxConnection = class _MuxConnection {
  constructor(connId) {
    /** streamId → 该流的帧队列与等待者 */
    this.streams = /* @__PURE__ */ new Map();
    this.closed = false;
    this.pumpError = null;
    this.connId = connId;
  }
  /**
   * 建立 mux 连接。
   *
   * 必须带 `Origin`（网关对 WS 的 Origin 是强制项，缺失直接 403），
   * 并带上会话 Cookie——由 `openGatewaySession` 种在会话里的那份。
   *
   * ## 为什么 Cookie 要自己拼
   *
   * WS 与 HTTP 是两套 `OkHttpClient`：`CookieJar` 挂在 HTTP 会话上，
   * WebSocket 握手不会自动带上它。网关的 `authorize()` 只读 Cookie，
   * 不带就是 401（`Expected HTTP 101 response but was '401 Unauthorized'`）。
   *
   * 而且手动种的 cookie 没有 `Secure` 属性，宿主按 URL 匹配时会被
   * OkHttp 排除掉——直接取会话全部 cookie 值拼头更可靠。
   */
  static async connect(gateway) {
    const host = hostOf(gateway);
    const cookie = gatewayCookieHeader();
    const headers = {
      origin: gateway,
      host
    };
    if (cookie.length > 0) headers["Cookie"] = cookie;
    const connId = await ctx.ws.connect(deriveMuxUrl(gateway), { headers });
    const mux = new _MuxConnection(connId);
    mux.startPump();
    return mux;
  }
  /**
   * 开一条逻辑流。
   *
   * `payload` 的字段由各 endpoint 自己规定，但 `open` 这一帧的四个字段
   * （`type`/`streamId`/`endpoint`/`payload`）一个都不能少——网关用
   * `exactKeys` 校验，少一个就是整条连接被拒。
   */
  open(endpoint, payload, streamId = nextStreamId()) {
    if (this.closed) throw new MuxError("closed", "mux \u8FDE\u63A5\u5DF2\u5173\u95ED");
    const state = { queue: [], waiter: null, ended: false };
    this.streams.set(streamId, state);
    this.sendFrame({ type: "open", streamId, endpoint, payload });
    return {
      streamId,
      next: () => this.nextItem(streamId, state),
      send: (value) => this.sendFrame({ type: "item", streamId, value }),
      end: () => this.sendFrame({ type: "end", streamId }),
      cancel: () => {
        this.sendFrame({ type: "cancel", streamId });
        this.finishStream(streamId, state, new MuxError("cancelled", "\u6D41\u5DF2\u53D6\u6D88"));
      }
    };
  }
  /** 关连接。所有未终结的流立刻以 [MuxError] 结束，不会有 Promise 悬着。 */
  close() {
    if (this.closed) return;
    this.closed = true;
    for (const [id, state] of this.streams) {
      this.finishStream(id, state, new MuxError("closed", "mux \u8FDE\u63A5\u5DF2\u5173\u95ED"));
    }
    ctx.ws.close(this.connId, 1e3, "");
  }
  // ---------- 内部 ----------
  sendFrame(frame) {
    if (this.closed) throw new MuxError("closed", "mux \u8FDE\u63A5\u5DF2\u5173\u95ED");
    const wire = frame.type === "item" ? { ...frame, value: frame.value === void 0 ? null : frame.value } : frame;
    ctx.ws.send(this.connId, JSON.stringify(wire));
  }
  startPump() {
    void (async () => {
      try {
        for (; ; ) {
          const frame = await ctx.ws.recv(this.connId);
          if (frame.type === "close") {
            this.pumpError = frame.error ?? frame.reason ?? "\u8FDE\u63A5\u5DF2\u5173\u95ED";
            break;
          }
          if (frame.type !== "text") continue;
          const parsed = parseServerFrame(frame.data);
          if (parsed !== null) this.deliver(parsed);
        }
      } catch (error) {
        this.pumpError = error instanceof Error ? error.message : String(error);
      } finally {
        this.closed = true;
        const reason = this.pumpError ?? "mux \u8FDE\u63A5\u5DF2\u65AD\u5F00";
        for (const [id, state] of this.streams) {
          this.finishStream(id, state, new MuxError("closed", reason));
        }
      }
    })();
  }
  deliver(frame) {
    const state = this.streams.get(frame.streamId);
    if (state === void 0 || state.ended) return;
    if (frame.type === "item") {
      this.push(state, { kind: "item", value: frame.value });
      return;
    }
    if (frame.type === "end") {
      this.push(state, { kind: "error", error: new MuxError("ended", "\u6D41\u5DF2\u7ED3\u675F") });
      this.finishStream(frame.streamId, state, new MuxError("ended", "\u6D41\u5DF2\u7ED3\u675F"));
      return;
    }
    const error = new MuxError(frame.error.code, frame.error.message, frame.error.details);
    this.push(state, { kind: "error", error });
    this.finishStream(frame.streamId, state, error);
  }
  push(state, entry) {
    if (state.waiter !== null) {
      const resolve = state.waiter;
      state.waiter = null;
      resolve(entry);
      return;
    }
    state.queue.push(entry);
  }
  nextItem(streamId, state) {
    if (this.closed) return Promise.reject(new MuxError("closed", "mux \u8FDE\u63A5\u5DF2\u5173\u95ED"));
    const pending = state.queue.shift();
    if (pending !== void 0) {
      return pending.kind === "item" ? Promise.resolve(pending.value) : Promise.reject(pending.error);
    }
    if (state.ended) return Promise.reject(new MuxError("ended", "\u6D41\u5DF2\u7ED3\u675F"));
    return new Promise((resolve, reject) => {
      state.waiter = (entry) => {
        if (entry.kind === "item") resolve(entry.value);
        else reject(entry.error);
      };
      void streamId;
    });
  }
  finishStream(streamId, state, error) {
    if (state.ended) return;
    state.ended = true;
    if (state.waiter !== null) {
      const resolve = state.waiter;
      state.waiter = null;
      resolve({ kind: "error", error });
    }
    this.streams.delete(streamId);
  }
};
function parseServerFrame(text) {
  let decoded;
  try {
    decoded = JSON.parse(text);
  } catch {
    return null;
  }
  if (typeof decoded !== "object" || decoded === null || Array.isArray(decoded)) {
    return null;
  }
  const frame = decoded;
  const streamId = frame.streamId;
  if (typeof streamId !== "string" || streamId.length === 0) return null;
  if (frame.type === "item") {
    const keys = Object.keys(frame);
    if (keys.some((k) => k !== "type" && k !== "streamId" && k !== "value")) return null;
    return { type: "item", streamId, value: frame.value };
  }
  if (frame.type === "end") {
    if (Object.keys(frame).length !== 2) return null;
    return { type: "end", streamId };
  }
  if (frame.type === "error") {
    const error = frame.error;
    if (typeof error !== "object" || error === null || Array.isArray(error)) return null;
    const e = error;
    if (typeof e.code !== "string" || typeof e.message !== "string") return null;
    const details = typeof e.details === "object" && e.details !== null && !Array.isArray(e.details) ? e.details : {};
    return { type: "error", streamId, error: { code: e.code, message: e.message, details } };
  }
  return null;
}
var streamSeq = 0;
function nextStreamId() {
  streamSeq += 1;
  return `lb-${Math.floor(Math.random() * 1e9).toString(36)}-${streamSeq}`;
}

// dsh/rpc.ts
var RpcError = class extends Error {
  constructor(code, message, details = {}, status = 0) {
    super(message);
    this.code = code;
    this.details = details;
    this.status = status;
  }
};
async function rpcCall(options, endpoint, args) {
  const rpcId = newRpcId();
  const response = await ctx.http.request(
    "POST",
    `${options.gateway}/api/${endpoint}`,
    JSON.stringify({
      type: "client-request",
      rpcId,
      method: endpoint,
      // 恰好一个键：多一个键网关就整条拒绝
      payload: { args }
    }),
    {
      headers: {
        accept: "application/json",
        ...options.csrf === void 0 ? {} : { [CSRF_HEADER]: options.csrf }
      },
      contentType: "application/json; charset=utf-8",
      timeoutMs: options.timeoutMs ?? 2e4,
      // prompt 会写入会话日志、create 会建 Agent，重发都可能产生重复副作用
      retry: false
    },
    options.session
  );
  if (!response.ok) {
    throw new RpcError(
      `http/${response.status}`,
      describeHttpFailure(endpoint, response.status),
      {},
      response.status
    );
  }
  const envelope = response.json;
  if (envelope === null || typeof envelope !== "object") {
    throw new RpcError("bad/envelope", `${endpoint}\uFF1A\u54CD\u5E94\u4E0D\u662F JSON \u5BF9\u8C61`, {}, response.status);
  }
  if (envelope.type !== "server-response") {
    throw new RpcError("bad/envelope", `${endpoint}\uFF1A\u54CD\u5E94\u4FE1\u5C01 type \u4E0D\u662F server-response`, {}, response.status);
  }
  if (envelope.rpcId !== rpcId) {
    throw new RpcError(
      "bad/rpcId",
      `${endpoint}\uFF1ArpcId \u4E0D\u5339\u914D\uFF08\u53D1\u51FA ${rpcId}\uFF0C\u6536\u5230 ${String(envelope.rpcId)}\uFF09`,
      {},
      response.status
    );
  }
  const result = envelope.result;
  if (typeof result !== "object" || result === null || Array.isArray(result)) {
    throw new RpcError("bad/result", `${endpoint}\uFF1A\u54CD\u5E94\u7F3A\u5C11 result`, {}, response.status);
  }
  const r = result;
  if (r.ok === true) return r.value;
  const error = r.error;
  if (typeof error === "object" && error !== null && !Array.isArray(error)) {
    const e = error;
    throw new RpcError(
      typeof e.code === "string" ? e.code : "unknown",
      typeof e.message === "string" ? e.message : `${endpoint} \u5931\u8D25`,
      typeof e.details === "object" && e.details !== null && !Array.isArray(e.details) ? e.details : {},
      response.status
    );
  }
  throw new RpcError("unknown", `${endpoint} \u5931\u8D25\uFF08\u65E0\u9519\u8BEF\u8BE6\u60C5\uFF09`, {}, response.status);
}
function describeHttpFailure(endpoint, status) {
  if (status === 401) return "\u4F1A\u8BDD\u5DF2\u5931\u6548\uFF0C\u8BF7\u91CD\u65B0\u914D\u5BF9";
  if (status === 403) return "\u7F51\u5173\u62D2\u7EDD\u4E86\u672C\u6B21\u8BF7\u6C42\uFF08\u6765\u6E90\u6216 Origin \u6821\u9A8C\u672A\u901A\u8FC7\uFF09";
  if (status === 404) return `\u7535\u8111\u4E0A\u6CA1\u6709 ${endpoint} \u8FD9\u4E2A\u63A5\u53E3\uFF0C\u8BF7\u786E\u8BA4 DSH \u7248\u672C`;
  if (status === 415) return "\u8BF7\u6C42\u683C\u5F0F\u4E0D\u88AB\u63A5\u53D7\uFF08\u9700\u8981 application/json\uFF09";
  if (status === 503) return "\u7535\u8111\u4E0A\u7684 DSH \u6682\u65F6\u4E0D\u53EF\u7528\uFF0C\u8BF7\u7A0D\u540E\u91CD\u8BD5";
  return `${endpoint} \u8BF7\u6C42\u5931\u8D25\uFF08HTTP ${status}\uFF09`;
}
var rpcSeq = 0;
function newRpcId() {
  rpcSeq += 1;
  return `lb-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e9).toString(36)}-${rpcSeq}`;
}

// dsh/sessionWire.ts
async function listSessions(options) {
  const value = await rpcCall(options, "session/list", { _request: {} });
  const items = Array.isArray(value.items) ? value.items : [];
  const out = [];
  for (const raw of items) {
    const row = readSummary(raw);
    if (row !== null && row.origin !== "subagent") out.push(row);
  }
  return out;
}
function readSummary(raw) {
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) return null;
  const r = raw;
  if (typeof r.sessionId !== "string" || r.sessionId.length === 0) return null;
  const row = {
    sessionId: r.sessionId,
    updatedAt: typeof r.updatedAt === "number" ? r.updatedAt : 0,
    running: r.running === true,
    blank: r.blank === true,
    agentAvailable: r.agentAvailable === true
  };
  if (typeof r.cwd === "string" && r.cwd.length > 0) row.cwd = r.cwd;
  if (typeof r.parentSessionId === "string") row.parentSessionId = r.parentSessionId;
  if (typeof r.origin === "string") row.origin = r.origin;
  return row;
}
async function promptSession(options, sessionId, text, requestId) {
  await rpcCall(options, "session/prompt", {
    request: {
      sessionId,
      mode: "queue",
      content: [{ type: "text", text }],
      requestId
    }
  });
}
async function followSession(gateway, sessionId, handlers) {
  const mux = await MuxConnection.connect(gateway);
  const stream = mux.open("session/follow", {
    args: {
      request: {
        address: { kind: "session", sessionId },
        activate: true,
        assistantStream: true
      }
    }
  });
  let closed = false;
  const state = { attemptId: null, nextIndex: 0, revision: -1 };
  void (async () => {
    try {
      for (; ; ) {
        const value = await stream.next();
        if (closed) return;
        handleFollowFrame(value, handlers, state);
      }
    } catch (error) {
      if (closed) return;
      const reason = error instanceof Error ? error.message : String(error);
      handlers.onClosed(reason);
    } finally {
      mux.close();
    }
  })();
  return {
    close() {
      if (closed) return;
      closed = true;
      stream.cancel();
      mux.close();
    }
  };
}
function handleFollowFrame(value, handlers, state) {
  if (typeof value !== "object" || value === null) return;
  const frame = value;
  if (frame.type === "snapshot") {
    const records = Array.isArray(frame.records) ? frame.records : [];
    const messages = [];
    for (const record of records) {
      const message = readEventMessage(record);
      if (message !== null) messages.push(message);
    }
    handlers.onSnapshot(messages);
    const baseline = asRecord(frame.assistantStream);
    state.revision = typeof baseline?.revision === "number" ? baseline.revision : -1;
    const attempt = asRecord(baseline?.activeAttempt);
    state.attemptId = typeof attempt?.attemptId === "string" ? attempt.attemptId : null;
    state.nextIndex = typeof attempt?.nextIndex === "number" ? attempt.nextIndex : 0;
    if (state.attemptId !== null) {
      handlers.onTurnStart();
      handlers.onAttemptReset();
      for (const raw of Array.isArray(attempt?.stream) ? attempt.stream : []) {
        const record = asRecord(raw);
        if (record?.type === "text-chunks" && Array.isArray(record.texts)) {
          for (const text of record.texts) if (typeof text === "string") handlers.onDelta(text);
        } else if (record?.type === "chunk") {
          const text = readChunkText(record.chunk);
          if (text.length > 0) handlers.onDelta(text);
        }
      }
    }
    return;
  }
  if (frame.type === "event") {
    const event = readEventObject(frame);
    if (event === null) return;
    if (event.type === "turn/start") {
      handlers.onTurnStart();
      return;
    }
    if (event.type === "turn/end") {
      state.attemptId = null;
      const failure = readTurnEndError(event);
      if (failure !== null) handlers.onError(failure);
      handlers.onTurnEnd();
      return;
    }
    const message = readEventMessage(frame);
    if (message !== null) handlers.onMessage(message);
    return;
  }
  if (frame.type === "assistant-stream") {
    const inner = frame.frame;
    if (typeof inner !== "object" || inner === null) return;
    const f = inner;
    if (typeof f.revision !== "number" || f.revision <= state.revision) return;
    if (typeof f.attemptId !== "string") return;
    if (f.type === "start") {
      state.revision = f.revision;
      state.attemptId = f.attemptId;
      state.nextIndex = 0;
      handlers.onTurnStart();
      handlers.onAttemptReset();
      return;
    }
    if (f.attemptId !== state.attemptId || f.index !== state.nextIndex) return;
    state.revision = f.revision;
    if (f.type === "chunk") {
      state.nextIndex += 1;
      const text = readChunkText(f.chunk);
      if (text.length > 0) handlers.onDelta(text);
    } else if (f.type === "end") {
      state.attemptId = null;
      const outcome = asRecord(f.outcome);
      if (outcome?.kind !== "committed" || outcome.eventType !== "assistant/message") {
        handlers.onAttemptReset();
      }
    }
  }
}
function readEventObject(raw) {
  if (typeof raw !== "object" || raw === null) return null;
  const frame = raw;
  if (frame.type !== "event") return null;
  const event = frame.event;
  if (typeof event !== "object" || event === null || Array.isArray(event)) return null;
  return event;
}
function asRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? value : null;
}
function readTurnEndError(event) {
  const data = event.data;
  if (typeof data !== "object" || data === null) return null;
  const reason = data.reason;
  if (typeof reason !== "object" || reason === null) return null;
  const r = reason;
  if (r.kind !== "error") return null;
  const err = r.error;
  if (typeof err === "object" && err !== null) {
    const message = err.message;
    if (typeof message === "string" && message.length > 0) return message;
  }
  return "\u672C\u8F6E\u6267\u884C\u5931\u8D25";
}
function readEventMessage(raw) {
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) return null;
  const record = raw;
  const event = record.type === "event" && typeof record.event === "object" && record.event !== null ? record.event : record;
  const type = event.type;
  if (typeof type !== "string") return null;
  const role = type === "user/message" ? "user" : type === "assistant/message" ? "assistant" : type === "system/message" ? "system" : null;
  if (role === null) return null;
  const data = typeof event.data === "object" && event.data !== null ? event.data : {};
  const text = readMessageText(data.message ?? data);
  if (text.length === 0) return null;
  const body = asRecord(data.message) ?? data;
  const source = asRecord(body.source);
  const rpcId = typeof source?.rpcId === "string" ? source.rpcId : void 0;
  return { role, text, rpcId };
}
function readMessageText(raw) {
  if (typeof raw !== "object" || raw === null) return "";
  const message = raw;
  const content = message.content;
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  const parts = [];
  let hasNonText = false;
  for (const block of content) {
    if (typeof block !== "object" || block === null) continue;
    const b = block;
    if (b.type === "text" && typeof b.text === "string") {
      parts.push(b.text);
    } else {
      hasNonText = true;
    }
  }
  const text = parts.join("");
  if (text.length === 0 && hasNonText) return "[\u975E\u6587\u672C\u5185\u5BB9\uFF0C\u5F53\u524D\u7248\u672C\u4E0D\u5C55\u793A]";
  return text;
}
function readChunkText(raw) {
  if (typeof raw !== "object" || raw === null) return "";
  const chunk = raw;
  if (chunk.type !== "text-delta") return "";
  return typeof chunk.text === "string" ? chunk.text : "";
}

// dsh/conversation.ts
var Conversation = class {
  constructor(emit) {
    this.emit = emit;
    this.messages = [];
    this.localRequests = /* @__PURE__ */ new Set();
    this.buffer = "";
  }
  snapshot(messages) {
    this.messages = messages.slice(-500);
    this.buffer = "";
    this.replay();
  }
  submitted(requestId, text) {
    this.localRequests.add(requestId);
    if (this.localRequests.size > 500) {
      const oldest = this.localRequests.values().next().value;
      if (oldest !== void 0) this.localRequests.delete(oldest);
    }
    this.remember({ role: "user", text });
  }
  message(message) {
    if (message.role === "user" && message.rpcId !== void 0 && this.localRequests.has(message.rpcId)) return;
    this.remember(message);
    if (message.role === "assistant" && this.buffer.length > 0) {
      this.buffer = "";
      this.replay();
    } else {
      this.emit({ type: "message", role: message.role, text: message.text });
    }
  }
  delta(text) {
    this.buffer += text;
    if (text.length > 0) this.emit({ type: "delta", text });
  }
  resetAttempt() {
    if (this.buffer.length === 0) return;
    this.buffer = "";
    this.replay();
  }
  remember(message) {
    this.messages.push(message);
    if (this.messages.length > 500) this.messages.shift();
  }
  replay() {
    this.emit({ type: "clear" });
    for (const message of this.messages) {
      this.emit({ type: "message", role: message.role, text: message.text });
    }
  }
};

// dsh/detailView.ts
function buildDetailUi(bridge) {
  if (!bridge.isPaired()) {
    return {
      type: "column",
      children: [
        {
          type: "column",
          card: true,
          title: "\u8FD8\u6CA1\u6709\u8FDE\u63A5\u7535\u8111",
          children: [
            {
              type: "text",
              card: false,
              body: "\u8BF7\u5148\u5728\u63D2\u4EF6\u5217\u8868\u91CC\u5B8C\u6210\u914D\u5BF9\uFF08\u9700\u8981\u7535\u8111\u7AEF dsh-mobile \u751F\u6210\u7684\u914D\u5BF9\u94FE\u63A5\uFF09\uFF0C\u518D\u56DE\u5230\u8FD9\u91CC\u5BF9\u8BDD\u3002"
            }
          ]
        }
      ]
    };
  }
  const pendingKey = bridge.pendingSwitchKey();
  const currentId = pendingKey.length > 0 ? bridge.sessionIdOf(pendingKey) : bridge.selectedSessionId();
  const currentKey = currentId.length > 0 ? bridge.sessionKeyOf(currentId) : "";
  const connected = bridge.isConnected();
  const header = [
    {
      type: "statusCard",
      id: "conn",
      label: bridge.hostLabel(),
      value: connected ? "\u5DF2\u8FDE\u63A5" : "\u672A\u8FDE\u63A5",
      tone: connected ? "ok" : "neutral",
      detail: currentId.length > 0 ? `\u4F1A\u8BDD ${currentId.slice(0, 8)}` : "\u5C1A\u672A\u9009\u62E9\u4F1A\u8BDD"
    }
  ];
  const failure = bridge.lastFailure();
  if (failure !== null) {
    header.push({
      type: "text",
      card: false,
      emphasis: "caption",
      tone: "warn",
      body: `\u4E0A\u6B21\u8FDE\u63A5\u5931\u8D25\uFF1A${failure}`
    });
  }
  const sessions = bridge.cachedSessions();
  const picker = sessions.length > 0 ? [{
    type: "picker",
    id: "sessionPicker",
    actionId: "dsh.selectSession",
    title: "\u9009\u62E9\u4F1A\u8BDD",
    mode: "modal",
    selectedIds: currentId.length > 0 ? [bridge.sessionKeyOf(currentId)] : [],
    emptyHint: "\u70B9\u300C\u5237\u65B0\u4F1A\u8BDD\u300D\u62C9\u53D6\u7535\u8111\u4E0A\u7684\u5BF9\u8BDD",
    candidates: sessions.map(toCandidate(bridge))
  }] : [{
    type: "text",
    card: false,
    emphasis: "caption",
    body: "\u8FD8\u6CA1\u6709\u4F1A\u8BDD\u6E05\u5355\uFF0C\u70B9\u4E0B\u65B9\u300C\u5237\u65B0\u4F1A\u8BDD\u300D\u3002"
  }];
  const conversation2 = currentKey.length > 0 ? [
    {
      type: "chat",
      id: "messages",
      // 固定高度让对话区自己滚，输入框稳定在下方
      maxHeightDp: 360,
      selfName: "\u6211",
      peerName: "\u7535\u8111",
      emptyHint: "\u8FDE\u63A5\u540E\u5728\u8FD9\u91CC\u5BF9\u8BDD",
      messages: { binding: "session:" + currentKey + ":messages" }
    },
    {
      type: "text",
      id: "sessionStatus",
      card: false,
      emphasis: "caption",
      body: { binding: "session:" + currentKey + ":status" }
    },
    {
      type: "input",
      id: "send",
      actionId: "dsh.send",
      placeholder: "\u7ED9\u7535\u8111\u53D1\u6307\u4EE4\u2026",
      submitLabel: "\u53D1\u9001",
      submitTo: "session:" + currentKey,
      enabled: { binding: "session:" + currentKey + ":inputEnabled" }
    }
  ] : [{
    type: "text",
    card: false,
    emphasis: "caption",
    body: currentId.length > 0 ? "\u6B63\u5728\u8FDE\u63A5\u4F1A\u8BDD\u2026" : "\u5148\u5728\u4E0A\u9762\u9009\u4E00\u4E2A\u4F1A\u8BDD\uFF0C\u6216\u70B9\u300C\u5237\u65B0\u4F1A\u8BDD\u300D\u62C9\u53D6\u6E05\u5355\u3002"
  }];
  return {
    type: "column",
    children: [
      { type: "column", card: true, id: "status", children: header },
      ...picker,
      ...conversation2,
      {
        type: "row",
        id: "actions",
        children: [
          { type: "button", actionId: "dsh.refreshSessions", label: "\u5237\u65B0\u4F1A\u8BDD" }
        ]
      }
    ]
  };
}
async function handleDetailAction(bridge, actionId, value) {
  if (actionId === "dsh.refreshSessions") {
    const ok = await refreshSessionList(bridge);
    if (!ok) {
      return { type: "showMessage", text: bridge.lastFailure() ?? "\u62C9\u53D6\u4F1A\u8BDD\u6E05\u5355\u5931\u8D25" };
    }
    return { type: "refreshUi", root: buildDetailUi(bridge) };
  }
  if (actionId === "dsh.selectSession") {
    const key = (value ?? "").trim();
    const nextId = bridge.sessionIdOf(key);
    if (nextId.length === 0) {
      return { type: "showMessage", text: "\u6CA1\u80FD\u8BC6\u522B\u6240\u9009\u4F1A\u8BDD\uFF0C\u8BF7\u5237\u65B0\u5217\u8868\u540E\u91CD\u8BD5" };
    }
    ctx.log.i("dsh", `\u5207\u6362\u5230\u4F1A\u8BDD ${nextId.slice(0, 8)}`);
    bridge.setPendingSwitchKey(key);
    bridge.setSelectedSessionId(nextId);
    return { type: "refreshUi", root: buildDetailUi(bridge) };
  }
  return {
    type: "showMessage",
    text: value === void 0 ? "\u672A\u77E5\u64CD\u4F5C" : `\u5DF2\u5FFD\u7565\uFF1A${actionId}`
  };
}
async function refreshSessionList(bridge) {
  try {
    const sessions = await bridge.fetchSessions();
    bridge.setCachedSessions(collectVisibleSessions(sessions));
    bridge.setLastFailure(null);
    return true;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    bridge.setLastFailure(message);
    ctx.log.w("dsh", `\u62C9\u53D6\u4F1A\u8BDD\u6E05\u5355\u5931\u8D25\uFF1A${message}`);
    return false;
  }
}
function collectVisibleSessions(sessions) {
  return sessions.filter((s) => s.origin === void 0 || s.origin !== "subagent").sort((a, b) => b.updatedAt - a.updatedAt);
}
function toCandidate(bridge) {
  return (session) => {
    const flags = [];
    if (session.running) flags.push("\u8FD0\u884C\u4E2D");
    if (!session.agentAvailable) flags.push("\u7535\u8111\u672A\u5728\u7EBF");
    if (session.blank) flags.push("\u7A7A\u4F1A\u8BDD");
    return {
      id: bridge.sessionKeyOf(session.sessionId),
      label: sessionLabel(session),
      detail: flags.join(" \xB7 "),
      tone: session.agentAvailable ? "neutral" : "warn",
      disabled: !session.agentAvailable
    };
  };
}
function sessionLabel(session) {
  const short = session.sessionId.slice(0, 8);
  return session.cwd !== void 0 && session.cwd.length > 0 ? cwdName(session.cwd) + " \xB7 " + short : short;
}
function cwdName(cwd) {
  const parts = cwd.split(/[\\/]+/).filter((p) => p.length > 0);
  return parts.length > 0 ? parts[parts.length - 1] : cwd;
}

// dsh/contract.ts
var SESSION_KEY_PREFIX = "dsh-";
function sessionKeyOf(sessionId) {
  return SESSION_KEY_PREFIX + sessionId;
}
function sessionIdOf(key) {
  if (key.startsWith(SESSION_KEY_PREFIX)) return key.slice(SESSION_KEY_PREFIX.length);
  return "";
}
var activeFollow = null;
var lastFailure = null;
var pendingPairing = null;
var selectedSessionId = "";
var pendingSwitchKey = "";
var cachedSessions = [];
var conversation = null;
var running = false;
var caReadyFor = null;
var caRestore = null;
async function login(inputs) {
  const form = inputs ?? {};
  try {
    if (pendingPairing === null) {
      const rawCode = (form["pairingCode"] ?? "").trim();
      const rawGateway = (form["gateway"] ?? "").trim();
      if (rawGateway.length === 0 && rawCode.length === 0) {
        return {
          type: "needInput",
          prompt: "\u8FDE\u63A5\u7535\u8111\u4E0A\u7684 DSH",
          fields: [
            {
              name: "gateway",
              displayName: "\u7F51\u5173\u5730\u5740",
              secret: false,
              hint: "\u4E00\u822C\u7559\u7A7A\u5373\u53EF\uFF1A\u6574\u6761\u914D\u5BF9\u94FE\u63A5\u91CC\u5DF2\u5E26\u5730\u5740\uFF0C\u4F1A\u81EA\u52A8\u8BC6\u522B"
            },
            {
              name: "pairingCode",
              displayName: "\u914D\u5BF9\u94FE\u63A5",
              secret: true,
              hint: "\u628A\u7535\u8111\u7AEF\u590D\u5236\u7684**\u6574\u6761**\u94FE\u63A5\u7C98\u8FDB\u6765\uFF08\u5FC5\u987B\u542B instance= \u6BB5\uFF09\u3002\u53EA\u590D\u5236\u540E\u9762 43 \u4F4D\u7801\u4E0D\u591F\u2014\u2014\u9489\u624E\u8981\u7528\u7684 CA \u6307\u7EB9\u5C31\u5728\u94FE\u63A5\u91CC"
            }
          ]
        };
      }
      const link = parsePairingLink(rawCode);
      const gatewaySource = rawGateway.length > 0 ? rawGateway : link === null ? "" : link.gateway;
      if (gatewaySource.length === 0) {
        return { type: "failure", message: "\u6CA1\u8BC6\u522B\u51FA\u7F51\u5173\u5730\u5740\uFF0C\u8BF7\u6574\u6761\u590D\u5236\u914D\u5BF9\u94FE\u63A5\uFF0C\u6216\u624B\u52A8\u586B\u5730\u5740" };
      }
      if (link === null) {
        return {
          type: "failure",
          message: "\u914D\u5BF9\u94FE\u63A5\u4E0D\u5B8C\u6574\uFF1A\u7F3A\u5C11\u5B9E\u4F8B\u6307\u7EB9\uFF08instance\uFF09\u3002\u8BF7\u6574\u6761\u590D\u5236\u7535\u8111\u7AEF\u7ED9\u7684\u94FE\u63A5\uFF0C\u4E0D\u8981\u53EA\u590D\u5236\u540E\u9762\u90A3\u4E32\u7801"
        };
      }
      pendingPairing = {
        gateway: normalizeGatewayUrl(gatewaySource),
        label: "LifeBoard",
        code: link.token,
        instance: link.instance
      };
      return await completePairing(pendingPairing);
    }
    const pending = pendingPairing;
    pendingPairing = null;
    return await completePairing(pending);
  } catch (error) {
    pendingPairing = null;
    const message = error instanceof Error ? error.message : String(error);
    ctx.log.e("dsh", `\u914D\u5BF9\u5931\u8D25\uFF1A${message}`);
    return { type: "failure", message: `\u914D\u5BF9\u5931\u8D25\uFF1A${message}` };
  }
}
async function completePairing(input) {
  await pinGatewayCa(input.gateway, input.instance);
  const paired = await pairDevice(input.gateway, input.code, input.label);
  if (paired.instanceId.trim().toLowerCase() !== input.instance) {
    throw new GatewayError(
      0,
      "INSTANCE_MISMATCH",
      "\u7535\u8111\u8FD4\u56DE\u7684\u5B9E\u4F8B\u4E0E\u914D\u5BF9\u94FE\u63A5\u4E0D\u4E00\u81F4\uFF1A\u53EF\u80FD\u8FDE\u5230\u4E86\u53E6\u4E00\u53F0\u673A\u5668\uFF0C\u8BF7\u91CD\u65B0\u590D\u5236\u914D\u5BF9\u94FE\u63A5"
    );
  }
  const pairing = {
    gateway: input.gateway,
    label: input.label,
    instanceId: paired.instanceId,
    deviceId: paired.deviceId,
    deviceToken: paired.deviceToken,
    deviceExpiresAt: paired.deviceExpiresAt,
    sessionToken: paired.sessionToken,
    csrfToken: paired.csrfToken,
    sessionExpiresAt: paired.sessionExpiresAt
  };
  savePairing(pairing);
  openGatewaySession(pairing.gateway, pairing.sessionToken);
  caReadyFor = pairing.gateway;
  caRestore = null;
  ctx.log.i("dsh", `\u914D\u5BF9\u6210\u529F\uFF0C\u8BBE\u5907 ${paired.deviceId.slice(0, 8)}`);
  return { type: "success" };
}
function logout() {
  closeSession();
  closeGatewaySession();
  clearPairing();
  resetCaState();
  selectedSessionId = "";
  pendingPairing = null;
}
function authState() {
  const pairing = loadPairing();
  if (pairing === null) return { state: "loggedOut" };
  return { state: "loggedIn", accountName: hostLabel(pairing.gateway) };
}
function hostLabel(gateway) {
  return gateway.replace(/^https:\/\//, "");
}
var detailBridge = {
  hostLabel: () => hostLabel(loadPairingOrEmpty()),
  isPaired: () => loadPairing() !== null,
  isConnected: () => activeFollow !== null,
  lastFailure: () => lastFailure,
  setLastFailure: (message) => {
    lastFailure = message;
  },
  selectedSessionId: () => selectedSessionId,
  setSelectedSessionId: (id) => {
    selectedSessionId = id;
  },
  pendingSwitchKey: () => pendingSwitchKey,
  setPendingSwitchKey: (key) => {
    pendingSwitchKey = key;
  },
  cachedSessions: () => cachedSessions,
  setCachedSessions: applySessionList,
  fetchSessions: () => withSession((options) => listSessions(options)),
  sessionKeyOf,
  sessionIdOf
};
function detailUi() {
  return buildDetailUi(detailBridge);
}
async function onAction(actionId, value) {
  return handleDetailAction(detailBridge, actionId, value);
}
function loadPairingOrEmpty() {
  const pairing = loadPairing();
  return pairing === null ? "" : pairing.gateway;
}
function health() {
  if (lastFailure !== null) return { state: "degraded", message: lastFailure };
  return { state: "healthy" };
}
function fetch() {
  return [];
}
async function withSession(run) {
  const pairing = loadPairing();
  if (pairing === null) throw new Error("\u5C1A\u672A\u914D\u5BF9");
  await ensureGatewayCa(pairing.gateway, pairing.instanceId);
  let current = pairing;
  if (sessionNeedsRenew(pairing.sessionExpiresAt)) {
    ctx.log.i("dsh", "\u4F1A\u8BDD\u5373\u5C06\u8FC7\u671F\uFF0C\u6B63\u5728\u7EED\u671F");
    const renewed = await renewSession(pairing.gateway, pairing.deviceToken);
    current = {
      ...pairing,
      sessionToken: renewed.sessionToken,
      csrfToken: renewed.csrfToken,
      sessionExpiresAt: renewed.sessionExpiresAt
    };
    savePairing(current);
  }
  openGatewaySession(current.gateway, current.sessionToken);
  try {
    return await run({
      gateway: current.gateway,
      session: GATEWAY_SESSION,
      // 业务 RPC 全是 POST，网关要求写请求带 CSRF；漏了会被 403 挡在
      // 「来源或 Origin 校验未通过」这条文案后面，排查方向完全被带偏
      csrf: current.csrfToken
    });
  } catch (error) {
    if (error instanceof RpcError && (error.code === "http/401" || error.status === 401)) {
      throw new Error("\u914D\u5BF9\u5DF2\u5931\u6548\uFF0C\u8BF7\u5728\u7535\u8111\u7AEF\u91CD\u65B0\u751F\u6210\u914D\u5BF9\u7801\u540E\u91CD\u65B0\u8FDE\u63A5");
    }
    throw error;
  }
}
async function ensureGatewayCa(gateway, instanceId) {
  if (caReadyFor === gateway) return;
  if (caRestore !== null) return caRestore;
  const pending = (async () => {
    if (instanceId.trim().length !== 64) {
      throw new Error("\u914D\u5BF9\u7167\u5B58\u91CC\u7F3A\u5C11 CA \u6307\u7EB9\uFF0C\u8BF7\u91CD\u65B0\u914D\u5BF9");
    }
    try {
      await pinGatewayCa(gateway, instanceId);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (message.includes("CA \u6307\u7EB9\u4E0D\u7B26")) {
        throw new Error("\u7535\u8111\u7684\u8BC1\u4E66\u5DF2\u53D8\u5316\uFF08\u53EF\u80FD\u91CD\u88C5\u8FC7 dsh-mobile\uFF09\uFF0C\u8BF7\u91CD\u65B0\u914D\u5BF9");
      }
      throw error;
    }
    caReadyFor = gateway;
  })();
  caRestore = pending;
  try {
    await pending;
  } finally {
    if (caRestore === pending) caRestore = null;
  }
}
function resetCaState() {
  caReadyFor = null;
  caRestore = null;
}
async function sessionStart(options) {
  try {
    closeSession();
    lastFailure = null;
    if (loadPairing() === null) {
      lastFailure = "\u5C1A\u672A\u914D\u5BF9";
      ctx.session.emit(JSON.stringify({ type: "status", text: lastFailure, inputEnabled: false }));
      return false;
    }
    ctx.session.emit(JSON.stringify({ type: "status", text: "\u6B63\u5728\u8FDE\u63A5\u2026", inputEnabled: false }));
    const sessions = await withSession((c) => listSessions(c));
    applySessionList(sessions);
    const usable = sessions.filter((s) => s.agentAvailable && !s.blank);
    const requestedId = sessionIdOf((options?.sessionKey ?? "").trim());
    const target = pickSession(usable, requestedId);
    if (target === void 0) {
      lastFailure = usable.length === 0 ? "\u7535\u8111\u4E0A\u6CA1\u6709\u6B63\u5728\u8FD0\u884C\u7684\u4F1A\u8BDD\uFF0C\u8BF7\u5148\u5728\u7535\u8111\u4E0A\u6253\u5F00\u4E00\u4E2A\u5BF9\u8BDD" : "\u8BF7\u5148\u9009\u62E9\u4E00\u4E2A\u4F1A\u8BDD";
      ctx.session.emit(JSON.stringify({ type: "status", text: lastFailure, inputEnabled: false }));
      return false;
    }
    selectedSessionId = target.sessionId;
    pendingSwitchKey = sessionKeyOf(target.sessionId);
    const pairing = loadPairing();
    if (pairing === null) return false;
    running = target.running;
    const display = new Conversation((event) => ctx.session.emit(JSON.stringify(event)));
    conversation = display;
    activeFollow = await followSession(pairing.gateway, target.sessionId, {
      onSnapshot(messages) {
        display.snapshot(messages);
        emitRunningStatus();
      },
      onTurnStart() {
        running = true;
        emitRunningStatus();
      },
      onAttemptReset() {
        display.resetAttempt();
      },
      onMessage(message) {
        display.message(message);
      },
      onDelta(text) {
        display.delta(text);
      },
      onError(message) {
        display.resetAttempt();
        ctx.log.w("dsh", `\u672C\u8F6E\u5931\u8D25\uFF1A${message}`);
        reportTurn("failed");
        ctx.session.emit(JSON.stringify({
          type: "message",
          role: "system",
          text: `\u672C\u8F6E\u5931\u8D25\uFF1A${message}`
        }));
      },
      onTurnEnd() {
        display.resetAttempt();
        running = false;
        reportTurn("done");
        emitRunningStatus();
      },
      onClosed(reason) {
        closeSession();
        lastFailure = reason;
        ctx.session.emit(JSON.stringify({ type: "closed", reason }));
      }
    });
    emitRunningStatus();
    return true;
  } catch (error) {
    const message = describeError(error);
    lastFailure = message;
    ctx.log.e("dsh", `\u8FDE\u63A5\u5931\u8D25\uFF1A${message}`);
    ctx.session.emit(JSON.stringify({ type: "status", text: message, inputEnabled: false }));
    closeSession();
    return false;
  }
}
function pickSession(sessions, requestedId) {
  if (requestedId.length > 0) {
    const picked = sessions.find((s) => s.sessionId === requestedId);
    if (picked !== void 0) return picked;
    return void 0;
  }
  if (selectedSessionId.length > 0) {
    const kept = sessions.find((s) => s.sessionId === selectedSessionId);
    if (kept !== void 0) return kept;
  }
  return sessions[0];
}
function emitRunningStatus() {
  ctx.session.emit(JSON.stringify({
    type: "status",
    text: running ? "\u7535\u8111\u6B63\u5728\u56DE\u590D" : "\u5DF2\u8FDE\u63A5",
    inputEnabled: !running
  }));
}
function applySessionList(sessions) {
  cachedSessions = sessions.filter((s) => s.origin !== "subagent").sort((a, b) => b.updatedAt - a.updatedAt);
  const ids = new Set(cachedSessions.map((s) => s.sessionId));
  if (selectedSessionId.length > 0 && !ids.has(selectedSessionId)) {
    closeSession();
    selectedSessionId = "";
  }
  if (pendingSwitchKey.length > 0 && !ids.has(sessionIdOf(pendingSwitchKey))) pendingSwitchKey = "";
}
function emitMessage(message) {
  ctx.session.emit(JSON.stringify({ type: "message", role: message.role, text: message.text }));
}
async function sessionEvent(event) {
  if (activeFollow === null || selectedSessionId.length === 0) {
    return { action: "stop", reason: "\u8FDE\u63A5\u5DF2\u65AD\u5F00" };
  }
  const text = (event.payload ?? "").trim();
  if (text.length === 0) return { action: "continue" };
  const requestId = newRequestId();
  conversation?.submitted(requestId, text);
  running = true;
  emitRunningStatus();
  try {
    const sessionId = selectedSessionId;
    await withSession((options) => promptSession(options, sessionId, text, requestId));
    return { action: "continue" };
  } catch (error) {
    const message = describeError(error);
    ctx.log.e("dsh", `\u53D1\u9001\u5931\u8D25\uFF1A${message}`);
    emitMessage({ role: "system", text: `\u53D1\u9001\u5931\u8D25\uFF1A${message}` });
    running = false;
    emitRunningStatus();
    return { action: "continue" };
  }
}
function sessionStop() {
  closeSession();
}
function closeSession() {
  const follow = activeFollow;
  activeFollow = null;
  if ((conversation?.buffer.length ?? 0) > 0) reportTurn("interrupted");
  conversation = null;
  running = false;
  if (follow !== null) follow.close();
}
function reportTurn(outcome) {
  const key = outcome === "done" ? "TURN_DONE" : outcome === "interrupted" ? "TURN_INTERRUPTED" : "TURN_FAILED";
  try {
    ctx.report(key, 1);
  } catch (e) {
    ctx.log.w("dsh", `\u6307\u6807\u4E0A\u62A5\u5931\u8D25\uFF08${key}\uFF09\uFF1A${describeError(e)}`);
  }
}
var requestSeq = 0;
function newRequestId() {
  requestSeq += 1;
  return `lb-${Date.now().toString(36)}-${requestSeq}`;
}
function describeError(error) {
  if (error instanceof MuxError) {
    if (error.code === "closed") return "\u8FDE\u63A5\u5DF2\u65AD\u5F00\uFF0C\u8BF7\u91CD\u65B0\u8FDB\u5165\u4F1A\u8BDD";
    if (error.code === "ended") return "\u4F1A\u8BDD\u5DF2\u7ED3\u675F";
    return `\u4F1A\u8BDD\u51FA\u9519\uFF1A${error.message}`;
  }
  if (error instanceof RpcError) return error.message;
  return error instanceof Error ? error.message : String(error);
}

// dsh/main.ts
function gatewaySelfTest() {
  const checks = [];
  const failures = [];
  const expectOk = (input, expected) => {
    try {
      const actual = normalizeGatewayUrl(input);
      if (actual === expected) checks.push(`${input} \u2192 ${expected}`);
      else failures.push(`${input} \u5E94\u4E3A ${expected}\uFF0C\u5B9E\u9645 ${actual}`);
    } catch (e) {
      failures.push(`${input} \u672C\u5E94\u63A5\u53D7\uFF0C\u5374\u629B\u9519\uFF1A${String(e)}`);
    }
  };
  const expectFail = (input, why) => {
    try {
      const actual = normalizeGatewayUrl(input);
      failures.push(`${input} \u672C\u5E94\u62D2\u7EDD\uFF08${why}\uFF09\uFF0C\u5374\u8FD4\u56DE ${actual}`);
    } catch {
      checks.push(`${input} \u88AB\u62D2\u7EDD\uFF08${why}\uFF09`);
    }
  };
  expectOk("https://192.168.1.10:3443", "https://192.168.1.10:3443");
  expectOk("  https://192.168.1.10:3443/  ", "https://192.168.1.10:3443");
  expectOk("HTTPS://host.local:3443", "https://host.local:3443");
  expectFail("http://192.168.1.10:3443", "\u5FC5\u987B\u662F https");
  expectFail("https://192.168.1.10:3443/api", "\u4E0D\u80FD\u5E26\u8DEF\u5F84");
  expectFail("https://user:pass@host:3443", "\u4E0D\u80FD\u5E26\u51ED\u636E");
  expectFail("", "\u4E0D\u80FD\u4E3A\u7A7A");
  expectFail("192.168.1.10:3443", "\u7F3A scheme");
  try {
    const host = hostOf("https://192.168.1.10:3443");
    if (host === "192.168.1.10:3443") checks.push("hostOf \u62C6\u51FA host:port");
    else failures.push(`hostOf \u7ED3\u679C\u5F02\u5E38\uFF1A${host}`);
  } catch (e) {
    failures.push(`hostOf \u629B\u9519\uFF1A${String(e)}`);
  }
  const sampleInstance = "a".repeat(64);
  const sampleToken = "b".repeat(43);
  const full = parsePairingLink(
    `https://192.168.1.10:3443/mobile-access/pair#instance=${sampleInstance}&token=${sampleToken}`
  );
  if (full === null) failures.push("\u6574\u6761\u914D\u5BF9\u94FE\u63A5\u672C\u5E94\u89E3\u6790\u6210\u529F");
  else if (full.gateway !== "https://192.168.1.10:3443" || full.instance !== sampleInstance || full.token !== sampleToken) {
    failures.push(`\u914D\u5BF9\u94FE\u63A5\u89E3\u6790\u7ED3\u679C\u5F02\u5E38\uFF1A${JSON.stringify(full)}`);
  } else checks.push("\u6574\u6761\u914D\u5BF9\u94FE\u63A5\u89E3\u6790");
  const viaQuery = parsePairingLink(`https://host:3443/pair?instance=${sampleInstance}&token=${sampleToken}`);
  if (viaQuery !== null && viaQuery.instance === sampleInstance) checks.push("? \u5F62\u5F0F\u914D\u5BF9\u94FE\u63A5\u89E3\u6790");
  else failures.push("? \u5F62\u5F0F\u7684\u914D\u5BF9\u94FE\u63A5\u672A\u80FD\u89E3\u6790");
  const expectLinkRejected = (input, why) => {
    if (parsePairingLink(input) === null) checks.push(`${why} \u2192 \u62D2\u7EDD`);
    else failures.push(`${why} \u672C\u5E94\u62D2\u7EDD\uFF0C\u5374\u89E3\u6790\u6210\u529F`);
  };
  expectLinkRejected(sampleToken, "\u88F8\u914D\u5BF9\u7801\uFF08\u65E0 instance\uFF09");
  expectLinkRejected(`https://h:3443/pair#token=${sampleToken}`, "\u94FE\u63A5\u7F3A instance");
  expectLinkRejected(`https://h:3443/pair#instance=${sampleInstance}`, "\u94FE\u63A5\u7F3A token");
  expectLinkRejected(`https://h:3443/pair#instance=abc&token=${sampleToken}`, "instance \u957F\u5EA6\u4E0D\u5BF9");
  expectLinkRejected("", "\u7A7A\u4E32");
  return { ok: failures.length === 0, checks, failures };
}
function muxSelfTest() {
  const checks = [];
  const failures = [];
  const expectItem = (text, expectValue) => {
    const frame = parseServerFrame(text);
    if (frame === null) {
      failures.push(`${text} \u672C\u5E94\u89E3\u6790\u4E3A item\uFF0C\u5374\u8FD4\u56DE null`);
      return;
    }
    if (frame.type !== "item") {
      failures.push(`${text} \u5E94\u4E3A item\uFF0C\u5B9E\u9645 ${frame.type}`);
      return;
    }
    const actual = JSON.stringify(frame.value);
    const expected = JSON.stringify(expectValue);
    if (actual === expected) checks.push(`${text} \u2192 item`);
    else failures.push(`${text} value \u5E94\u4E3A ${expected}\uFF0C\u5B9E\u9645 ${actual}`);
  };
  const expectNull = (text, why) => {
    const frame = parseServerFrame(text);
    if (frame === null) checks.push(`${text} \u88AB\u62D2\u7EDD\uFF08${why}\uFF09`);
    else failures.push(`${text} \u672C\u5E94\u62D2\u7EDD\uFF08${why}\uFF09\uFF0C\u5374\u89E3\u6790\u4E3A ${frame.type}`);
  };
  expectItem('{"type":"item","streamId":"s1","value":{"a":1}}', { a: 1 });
  expectItem('{"type":"item","streamId":"s1"}', void 0);
  expectItem('{"type":"item","streamId":"s1","value":null}', null);
  const end = parseServerFrame('{"type":"end","streamId":"s1"}');
  if (end !== null && end.type === "end") checks.push("end \u5E27\u89E3\u6790");
  else failures.push("end \u5E27\u89E3\u6790\u5931\u8D25");
  const err = parseServerFrame('{"type":"error","streamId":"s1","error":{"code":"c","message":"m","details":{}}}');
  if (err !== null && err.type === "error" && err.error.code === "c") checks.push("error \u5E27\u89E3\u6790");
  else failures.push("error \u5E27\u89E3\u6790\u5931\u8D25");
  expectNull("not json", "\u4E0D\u662F JSON");
  expectNull("[]", "\u4E0D\u662F\u5BF9\u8C61");
  expectNull('{"type":"item"}', "\u7F3A streamId");
  expectNull('{"type":"item","streamId":""}', "streamId \u4E3A\u7A7A");
  expectNull('{"type":"item","streamId":"s1","extra":1}', "\u591A\u4F59\u5B57\u6BB5");
  expectNull('{"type":"end","streamId":"s1","x":1}', "end \u591A\u4F59\u5B57\u6BB5");
  expectNull('{"type":"error","streamId":"s1","error":{"code":"c"}}', "error \u7F3A message");
  expectNull('{"type":"unknown","streamId":"s1"}', "\u672A\u77E5 type");
  return { ok: failures.length === 0, checks, failures };
}
function contractSelfTest() {
  const checks = [];
  const failures = [];
  const actual = Object.keys(contractExports());
  for (const name of CONTRACT_EXPORTS) {
    if (actual.includes(name)) checks.push(`\u5BFC\u51FA ${name}`);
    else failures.push(`\u7F3A\u5C11\u5BFC\u51FA ${name}`);
  }
  return { ok: failures.length === 0, checks, failures };
}
var CONTRACT_EXPORTS = [
  "authState",
  "detailUi",
  "fetch",
  "health",
  "login",
  "logout",
  "onAction",
  "sessionEvent",
  "sessionStart",
  "sessionStop"
];
function contractExports() {
  return {
    authState,
    detailUi,
    fetch,
    health,
    login,
    logout,
    onAction,
    sessionEvent,
    sessionStart,
    sessionStop
  };
}
export {
  authState,
  contractSelfTest,
  detailUi,
  fetch,
  gatewaySelfTest,
  health,
  login,
  logout,
  muxSelfTest,
  onAction,
  sessionEvent,
  sessionStart,
  sessionStop
};
