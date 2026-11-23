var __defProp = Object.defineProperty;
var __defNormalProp = (obj, key, value) => key in obj ? __defProp(obj, key, { enumerable: true, configurable: true, writable: true, value }) : obj[key] = value;
var __publicField = (obj, key, value) => __defNormalProp(obj, typeof key !== "symbol" ? key + "" : key, value);

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

// node_modules/@noble/hashes/_u64.js
var U32_MASK64 = /* @__PURE__ */ (() => BigInt(2 ** 32 - 1))();
var _32n = /* @__PURE__ */ BigInt(32);
function fromBig(n, le = false) {
  if (le)
    return { h: Number(n & U32_MASK64), l: Number(n >> _32n & U32_MASK64) };
  return { h: Number(n >> _32n & U32_MASK64) | 0, l: Number(n & U32_MASK64) | 0 };
}
function split(lst, le = false) {
  const len = lst.length;
  let Ah = new Uint32Array(len);
  let Al = new Uint32Array(len);
  for (let i = 0; i < len; i++) {
    const { h, l } = fromBig(lst[i], le);
    [Ah[i], Al[i]] = [h, l];
  }
  return [Ah, Al];
}
var fromNumH = (n) => n / 2 ** 32 | 0;
var fromNumL = (n) => n >>> 0;
function setU64FromNum(view, byteOffset, n, isLE3) {
  const h = fromNumH(n);
  const l = fromNumL(n);
  view.setUint32(byteOffset, isLE3 ? l : h, isLE3);
  view.setUint32(byteOffset + 4, isLE3 ? h : l, isLE3);
}
var shrSH = (h, _l, s) => h >>> s;
var shrSL = (h, l, s) => h << 32 - s | l >>> s;
var rotrSH = (h, l, s) => h >>> s | l << 32 - s;
var rotrSL = (h, l, s) => h << 32 - s | l >>> s;
var rotrBH = (h, l, s) => h << 64 - s | l >>> s - 32;
var rotrBL = (h, l, s) => h >>> s - 32 | l << 64 - s;
var rotr32H = (_h, l) => l;
var rotr32L = (h, _l) => h;
function add(Ah, Al, Bh, Bl) {
  const l = (Al >>> 0) + (Bl >>> 0);
  return { h: Ah + Bh + (l / 2 ** 32 | 0) | 0, l: l | 0 };
}
var add3L = (Al, Bl, Cl) => (Al >>> 0) + (Bl >>> 0) + (Cl >>> 0);
var add3H = (low, Ah, Bh, Ch) => Ah + Bh + Ch + (low / 2 ** 32 | 0) | 0;
var add4L = (Al, Bl, Cl, Dl) => (Al >>> 0) + (Bl >>> 0) + (Cl >>> 0) + (Dl >>> 0);
var add4H = (low, Ah, Bh, Ch, Dh) => Ah + Bh + Ch + Dh + (low / 2 ** 32 | 0) | 0;
var add5L = (Al, Bl, Cl, Dl, El) => (Al >>> 0) + (Bl >>> 0) + (Cl >>> 0) + (Dl >>> 0) + (El >>> 0);
var add5H = (low, Ah, Bh, Ch, Dh, Eh) => Ah + Bh + Ch + Dh + Eh + (low / 2 ** 32 | 0) | 0;

// node_modules/@noble/hashes/utils.js
function isBytes(a) {
  return a instanceof Uint8Array || ArrayBuffer.isView(a) && a.constructor.name === "Uint8Array" && "BYTES_PER_ELEMENT" in a && a.BYTES_PER_ELEMENT === 1;
}
var atitle = (title) => title ? `"${title}" ` : "";
function anumber(n, title = "") {
  if (typeof n !== "number")
    throw new TypeError(atitle(title) + "expected number, got " + typeof n);
  if (!Number.isSafeInteger(n) || n < 0)
    throw new RangeError(atitle(title) + "expected integer >= 0, got " + n);
  return n;
}
function abytes(value, length, title = "") {
  if (isBytes(value) && (length === void 0 || value.length === length))
    return value;
  if (length !== void 0)
    anumber(length, "length");
  const bytes = isBytes(value);
  const ofLen = length !== void 0 ? ` of length ${length}` : "";
  const got = bytes ? `length=${value.length}` : `type=${typeof value}`;
  const message = atitle(title) + "expected Uint8Array" + ofLen + ", got " + got;
  if (!bytes)
    throw new TypeError(message);
  throw new RangeError(message);
}
function copyBytes(bytes) {
  return Uint8Array.from(abytes(bytes));
}
function ahash(h) {
  if (typeof h !== "function" || typeof h.create !== "function")
    throw new TypeError("expected hash wrapped by utils.createHasher");
  anumber(h.outputLen);
  anumber(h.blockLen);
  if (h.outputLen < 1 || h.blockLen < 1)
    throw new Error("hash blockLen / outputLen must be >= 1");
}
var aobject = (value, label) => {
  if (value === null || typeof value !== "object" || Array.isArray(value))
    throw new TypeError((label === "object" ? "" : `"${label}" `) + "expected object, got type=" + typeof value);
};
var aopts = (value, label) => {
  aobject(value, label);
  const proto = Object.getPrototypeOf(value);
  if (proto !== Object.prototype && proto !== null)
    throw new TypeError(`"${label}" expected plain object`);
  if (Object.hasOwn(value, "__proto__"))
    throw new TypeError(`"${label}.__proto__" is not allowed`);
};
function aexists(instance, checkFinished = true) {
  if (instance.destroyed)
    throw new Error("hash was destroyed");
  if (checkFinished && instance.finished)
    throw new Error("digest() was already called");
}
function aoutput(out, instance) {
  abytes(out, void 0, "output");
  const min = instance.outputLen;
  if (!(out.length >= min)) {
    throw new RangeError('"output" expected length >= ' + min);
  }
}
function u32(arr) {
  return new Uint32Array(arr.buffer, arr.byteOffset, Math.floor(arr.byteLength / 4));
}
function clean(...arrays) {
  for (let i = 0; i < arrays.length; i++) {
    arrays[i].fill(0);
  }
}
function createView(arr) {
  return new DataView(arr.buffer, arr.byteOffset, arr.byteLength);
}
function rotr(word, shift) {
  return word << 32 - shift | word >>> shift;
}
var isLE = /* @__PURE__ */ (() => new Uint8Array(new Uint32Array([287454020]).buffer)[0] === 68)();
function byteSwap(word) {
  return word << 24 & 4278190080 | word << 8 & 16711680 | word >>> 8 & 65280 | word >>> 24 & 255;
}
var swap8IfBE = isLE ? (n) => n : (n) => byteSwap(n) >>> 0;
function byteSwap32(arr) {
  for (let i = 0; i < arr.length; i++) {
    arr[i] = byteSwap(arr[i]);
  }
  return arr;
}
var swap32IfBE = isLE ? (u) => u : byteSwap32;
var hasHexBuiltin = /* @__PURE__ */ (() => (
  // @ts-ignore
  typeof Uint8Array.from([]).toHex === "function" && typeof Uint8Array.fromHex === "function"
))();
var hexes = /* @__PURE__ */ Array.from({ length: 256 }, (_, i) => i.toString(16).padStart(2, "0"));
function bytesToHex(bytes) {
  abytes(bytes);
  if (hasHexBuiltin)
    return bytes.toHex();
  let hex = "";
  for (let i = 0; i < bytes.length; i++) {
    hex += hexes[bytes[i]];
  }
  return hex;
}
function asciiToBase16(ch) {
  return ch >= 48 && ch <= 57 ? ch - 48 : ch >= 65 && ch <= 70 ? ch - (65 - 10) : ch >= 97 && ch <= 102 ? ch - (97 - 10) : void 0;
}
function hexToBytes(hex) {
  if (typeof hex !== "string")
    throw new TypeError("hex string expected, got " + typeof hex);
  if (hasHexBuiltin) {
    try {
      return Uint8Array.fromHex(hex);
    } catch (error) {
      if (error instanceof SyntaxError)
        throw new RangeError(error.message);
      throw error;
    }
  }
  const hl = hex.length;
  const al = hl / 2;
  if (hl % 2)
    throw new RangeError("hex string expected, got unpadded hex of length " + hl);
  const array = new Uint8Array(al);
  for (let ai = 0, hi = 0; ai < al; ai++, hi += 2) {
    const n1 = asciiToBase16(hex.charCodeAt(hi));
    const n2 = asciiToBase16(hex.charCodeAt(hi + 1));
    if (n1 === void 0 || n2 === void 0) {
      const char = hex[hi] + hex[hi + 1];
      throw new RangeError('hex string expected, got non-hex character "' + char + '" at index ' + hi);
    }
    array[ai] = n1 * 16 + n2;
  }
  return array;
}
function concatBytes(...arrays) {
  let sum = 0;
  for (let i = 0; i < arrays.length; i++) {
    const a = arrays[i];
    abytes(a);
    sum += a.length;
  }
  const res = new Uint8Array(sum);
  for (let i = 0, pad = 0; i < arrays.length; i++) {
    const a = arrays[i];
    res.set(a, pad);
    pad += a.length;
  }
  return res;
}
function checkOpts(defaults, opts, title = "opts") {
  aopts(defaults, "defaults");
  if (opts !== void 0)
    aopts(opts, title);
  const merged = Object.assign(/* @__PURE__ */ Object.create(null), defaults, opts);
  return merged;
}
function createHasher(hashCons, info = {}) {
  if (typeof hashCons !== "function")
    throw new TypeError('"hashCons" expected function, got type=' + typeof hashCons);
  info = checkOpts({}, info, "info");
  const hashC = (msg, opts) => hashCons(opts).update(msg).digest();
  const tmp = hashCons(void 0);
  hashC.outputLen = tmp.outputLen;
  hashC.blockLen = tmp.blockLen;
  hashC.canXOF = tmp.canXOF;
  hashC.create = (opts) => hashCons(opts);
  Object.assign(hashC, info);
  return Object.freeze(hashC);
}
function randomBytes(bytesLength = 32) {
  anumber(bytesLength, "bytesLength");
  const cr = typeof globalThis === "object" ? globalThis.crypto : null;
  if (typeof cr?.getRandomValues !== "function")
    throw new Error("crypto.getRandomValues must be defined");
  if (bytesLength > 65536)
    throw new RangeError(`"bytesLength" expected <= 65536, got ${bytesLength}`);
  return cr.getRandomValues(new Uint8Array(bytesLength));
}
var oidNist = (suffix) => ({
  // Current NIST hashAlgs suffixes used here fit in one DER subidentifier octet.
  // Larger suffix values would need base-128 OID encoding and a different length byte.
  oid: Uint8Array.from([6, 9, 96, 134, 72, 1, 101, 3, 4, 2, suffix])
});

// node_modules/@noble/hashes/_md.js
function Chi(a, b, c) {
  return a & b ^ ~a & c;
}
function Maj(a, b, c) {
  return a & b ^ a & c ^ b & c;
}
var HashMD = class {
  constructor(blockLen, outputLen, padOffset, isLE3) {
    __publicField(this, "blockLen");
    __publicField(this, "outputLen");
    __publicField(this, "canXOF", false);
    __publicField(this, "padOffset");
    __publicField(this, "isLE");
    // For partial updates less than block size
    __publicField(this, "buffer");
    __publicField(this, "view");
    __publicField(this, "finished", false);
    __publicField(this, "length", 0);
    __publicField(this, "pos", 0);
    __publicField(this, "destroyed", false);
    this.blockLen = blockLen;
    this.outputLen = outputLen;
    this.padOffset = padOffset;
    this.isLE = isLE3;
    this.buffer = new Uint8Array(blockLen);
    this.view = createView(this.buffer);
  }
  update(data) {
    aexists(this);
    abytes(data);
    const { view, buffer, blockLen } = this;
    const len = data.length;
    let processed = false;
    for (let pos = 0; pos < len; ) {
      const take = Math.min(blockLen - this.pos, len - pos);
      if (take === blockLen) {
        const dataView = createView(data);
        for (; blockLen <= len - pos; pos += blockLen)
          this.process(dataView, pos);
        processed = true;
        continue;
      }
      buffer.set(pos === 0 && take === len ? data : data.subarray(pos, pos + take), this.pos);
      this.pos += take;
      pos += take;
      if (this.pos === blockLen) {
        this.process(view, 0);
        this.pos = 0;
        processed = true;
      }
    }
    this.length += data.length;
    if (processed)
      this.roundClean();
    return this;
  }
  digestInto(out) {
    aexists(this);
    aoutput(out, this);
    this.finished = true;
    const { buffer, view, blockLen, isLE: isLE3 } = this;
    let { pos } = this;
    buffer[pos++] = 128;
    buffer.fill(0, pos);
    if (this.padOffset > blockLen - pos) {
      this.process(view, 0);
      buffer.fill(0);
    }
    setU64FromNum(view, blockLen - 8, this.length * 8, isLE3);
    this.process(view, 0);
    this.roundClean();
    const oview = out === buffer ? view : createView(out);
    const len = this.outputLen;
    const outLen = len / 4;
    const state = this.get();
    if (len % 4 || outLen > state.length)
      throw new Error("invalid outputLen");
    for (let i = 0; i < outLen; i++)
      oview.setUint32(4 * i, state[i], isLE3);
  }
  digest() {
    const { buffer, outputLen } = this;
    this.digestInto(buffer);
    const res = buffer.slice(0, outputLen);
    this.destroy();
    return res;
  }
  _cloneIntoMeta(to) {
    const { buffer, length, finished, destroyed, pos } = this;
    to.destroyed = destroyed;
    to.finished = finished;
    to.length = length;
    to.pos = pos;
    if (pos)
      to.buffer.set(buffer);
    return to;
  }
  clone() {
    return this._cloneInto();
  }
};
var SHA256_IV = /* @__PURE__ */ Uint32Array.from([
  1779033703,
  3144134277,
  1013904242,
  2773480762,
  1359893119,
  2600822924,
  528734635,
  1541459225
]);
var SHA512_IV = /* @__PURE__ */ Uint32Array.from([
  1779033703,
  4089235720,
  3144134277,
  2227873595,
  1013904242,
  4271175723,
  2773480762,
  1595750129,
  1359893119,
  2917565137,
  2600822924,
  725511199,
  528734635,
  4215389547,
  1541459225,
  327033209
]);

// node_modules/@noble/hashes/sha2.js
var SHA256_K = /* @__PURE__ */ Uint32Array.from([
  1116352408,
  1899447441,
  3049323471,
  3921009573,
  961987163,
  1508970993,
  2453635748,
  2870763221,
  3624381080,
  310598401,
  607225278,
  1426881987,
  1925078388,
  2162078206,
  2614888103,
  3248222580,
  3835390401,
  4022224774,
  264347078,
  604807628,
  770255983,
  1249150122,
  1555081692,
  1996064986,
  2554220882,
  2821834349,
  2952996808,
  3210313671,
  3336571891,
  3584528711,
  113926993,
  338241895,
  666307205,
  773529912,
  1294757372,
  1396182291,
  1695183700,
  1986661051,
  2177026350,
  2456956037,
  2730485921,
  2820302411,
  3259730800,
  3345764771,
  3516065817,
  3600352804,
  4094571909,
  275423344,
  430227734,
  506948616,
  659060556,
  883997877,
  958139571,
  1322822218,
  1537002063,
  1747873779,
  1955562222,
  2024104815,
  2227730452,
  2361852424,
  2428436474,
  2756734187,
  3204031479,
  3329325298
]);
var SHA256_W = /* @__PURE__ */ new Uint32Array(64);
var SHA2_32B = class extends HashMD {
  constructor(outputLen, IV) {
    super(64, outputLen, 8, false);
    // We cannot use array here since array allows indexing by variable
    // which means optimizer/compiler cannot use registers.
    // Numeric initializers matter: starting the fields as `undefined` changes
    // V8's field representation and makes sha256 3x slower (measured).
    __publicField(this, "A", 0);
    __publicField(this, "B", 0);
    __publicField(this, "C", 0);
    __publicField(this, "D", 0);
    __publicField(this, "E", 0);
    __publicField(this, "F", 0);
    __publicField(this, "G", 0);
    __publicField(this, "H", 0);
    this.A = IV[0] | 0;
    this.B = IV[1] | 0;
    this.C = IV[2] | 0;
    this.D = IV[3] | 0;
    this.E = IV[4] | 0;
    this.F = IV[5] | 0;
    this.G = IV[6] | 0;
    this.H = IV[7] | 0;
  }
  get() {
    const { A, B, C, D, E, F, G, H } = this;
    return [A, B, C, D, E, F, G, H];
  }
  // prettier-ignore
  set(A, B, C, D, E, F, G, H) {
    this.A = A | 0;
    this.B = B | 0;
    this.C = C | 0;
    this.D = D | 0;
    this.E = E | 0;
    this.F = F | 0;
    this.G = G | 0;
    this.H = H | 0;
  }
  _cloneInto(to) {
    (to || (to = new this.constructor())).set(...this.get());
    return this._cloneIntoMeta(to);
  }
  process(view, offset) {
    for (let i = 0; i < 16; i++, offset += 4)
      SHA256_W[i] = view.getUint32(offset, false);
    for (let i = 16; i < 64; i++) {
      const W15 = SHA256_W[i - 15];
      const W2 = SHA256_W[i - 2];
      const s0 = rotr(W15, 7) ^ rotr(W15, 18) ^ W15 >>> 3;
      const s1 = rotr(W2, 17) ^ rotr(W2, 19) ^ W2 >>> 10;
      SHA256_W[i] = s1 + SHA256_W[i - 7] + s0 + SHA256_W[i - 16] | 0;
    }
    let { A, B, C, D, E, F, G, H } = this;
    for (let i = 0; i < 64; i++) {
      const sigma1 = rotr(E, 6) ^ rotr(E, 11) ^ rotr(E, 25);
      const T1 = H + sigma1 + Chi(E, F, G) + SHA256_K[i] + SHA256_W[i] | 0;
      const sigma0 = rotr(A, 2) ^ rotr(A, 13) ^ rotr(A, 22);
      const T2 = sigma0 + Maj(A, B, C) | 0;
      H = G;
      G = F;
      F = E;
      E = D + T1 | 0;
      D = C;
      C = B;
      B = A;
      A = T1 + T2 | 0;
    }
    A = A + this.A | 0;
    B = B + this.B | 0;
    C = C + this.C | 0;
    D = D + this.D | 0;
    E = E + this.E | 0;
    F = F + this.F | 0;
    G = G + this.G | 0;
    H = H + this.H | 0;
    this.set(A, B, C, D, E, F, G, H);
  }
  roundClean() {
    clean(SHA256_W);
  }
  destroy() {
    this.destroyed = true;
    this.set(0, 0, 0, 0, 0, 0, 0, 0);
    clean(this.buffer);
  }
};
var _SHA256 = class extends SHA2_32B {
  constructor() {
    super(32, SHA256_IV);
  }
};
var K512 = /* @__PURE__ */ (() => split([
  "0x428a2f98d728ae22",
  "0x7137449123ef65cd",
  "0xb5c0fbcfec4d3b2f",
  "0xe9b5dba58189dbbc",
  "0x3956c25bf348b538",
  "0x59f111f1b605d019",
  "0x923f82a4af194f9b",
  "0xab1c5ed5da6d8118",
  "0xd807aa98a3030242",
  "0x12835b0145706fbe",
  "0x243185be4ee4b28c",
  "0x550c7dc3d5ffb4e2",
  "0x72be5d74f27b896f",
  "0x80deb1fe3b1696b1",
  "0x9bdc06a725c71235",
  "0xc19bf174cf692694",
  "0xe49b69c19ef14ad2",
  "0xefbe4786384f25e3",
  "0x0fc19dc68b8cd5b5",
  "0x240ca1cc77ac9c65",
  "0x2de92c6f592b0275",
  "0x4a7484aa6ea6e483",
  "0x5cb0a9dcbd41fbd4",
  "0x76f988da831153b5",
  "0x983e5152ee66dfab",
  "0xa831c66d2db43210",
  "0xb00327c898fb213f",
  "0xbf597fc7beef0ee4",
  "0xc6e00bf33da88fc2",
  "0xd5a79147930aa725",
  "0x06ca6351e003826f",
  "0x142929670a0e6e70",
  "0x27b70a8546d22ffc",
  "0x2e1b21385c26c926",
  "0x4d2c6dfc5ac42aed",
  "0x53380d139d95b3df",
  "0x650a73548baf63de",
  "0x766a0abb3c77b2a8",
  "0x81c2c92e47edaee6",
  "0x92722c851482353b",
  "0xa2bfe8a14cf10364",
  "0xa81a664bbc423001",
  "0xc24b8b70d0f89791",
  "0xc76c51a30654be30",
  "0xd192e819d6ef5218",
  "0xd69906245565a910",
  "0xf40e35855771202a",
  "0x106aa07032bbd1b8",
  "0x19a4c116b8d2d0c8",
  "0x1e376c085141ab53",
  "0x2748774cdf8eeb99",
  "0x34b0bcb5e19b48a8",
  "0x391c0cb3c5c95a63",
  "0x4ed8aa4ae3418acb",
  "0x5b9cca4f7763e373",
  "0x682e6ff3d6b2b8a3",
  "0x748f82ee5defb2fc",
  "0x78a5636f43172f60",
  "0x84c87814a1f0ab72",
  "0x8cc702081a6439ec",
  "0x90befffa23631e28",
  "0xa4506cebde82bde9",
  "0xbef9a3f7b2c67915",
  "0xc67178f2e372532b",
  "0xca273eceea26619c",
  "0xd186b8c721c0c207",
  "0xeada7dd6cde0eb1e",
  "0xf57d4f7fee6ed178",
  "0x06f067aa72176fba",
  "0x0a637dc5a2c898a6",
  "0x113f9804bef90dae",
  "0x1b710b35131c471b",
  "0x28db77f523047d84",
  "0x32caab7b40c72493",
  "0x3c9ebe0a15c9bebc",
  "0x431d67c49c100d4c",
  "0x4cc5d4becb3e42b6",
  "0x597f299cfc657e2a",
  "0x5fcb6fab3ad6faec",
  "0x6c44198c4a475817"
].map((n) => BigInt(n))))();
var SHA512_Kh = /* @__PURE__ */ (() => K512[0])();
var SHA512_Kl = /* @__PURE__ */ (() => K512[1])();
var SHA512_W_H = /* @__PURE__ */ new Uint32Array(80);
var SHA512_W_L = /* @__PURE__ */ new Uint32Array(80);
var SHA2_64B = class extends HashMD {
  constructor(outputLen, IV) {
    super(128, outputLen, 16, false);
    // We cannot use array here since array allows indexing by variable
    // which means optimizer/compiler cannot use registers.
    // h -- high 32 bits, l -- low 32 bits
    // Numeric initializers matter: starting the fields as `undefined` changes
    // V8's field representation and slows hashing down (measured on sha256).
    __publicField(this, "Ah", 0);
    __publicField(this, "Al", 0);
    __publicField(this, "Bh", 0);
    __publicField(this, "Bl", 0);
    __publicField(this, "Ch", 0);
    __publicField(this, "Cl", 0);
    __publicField(this, "Dh", 0);
    __publicField(this, "Dl", 0);
    __publicField(this, "Eh", 0);
    __publicField(this, "El", 0);
    __publicField(this, "Fh", 0);
    __publicField(this, "Fl", 0);
    __publicField(this, "Gh", 0);
    __publicField(this, "Gl", 0);
    __publicField(this, "Hh", 0);
    __publicField(this, "Hl", 0);
    this.Ah = IV[0] | 0;
    this.Al = IV[1] | 0;
    this.Bh = IV[2] | 0;
    this.Bl = IV[3] | 0;
    this.Ch = IV[4] | 0;
    this.Cl = IV[5] | 0;
    this.Dh = IV[6] | 0;
    this.Dl = IV[7] | 0;
    this.Eh = IV[8] | 0;
    this.El = IV[9] | 0;
    this.Fh = IV[10] | 0;
    this.Fl = IV[11] | 0;
    this.Gh = IV[12] | 0;
    this.Gl = IV[13] | 0;
    this.Hh = IV[14] | 0;
    this.Hl = IV[15] | 0;
  }
  // prettier-ignore
  get() {
    const { Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl } = this;
    return [Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl];
  }
  // prettier-ignore
  set(Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl) {
    this.Ah = Ah | 0;
    this.Al = Al | 0;
    this.Bh = Bh | 0;
    this.Bl = Bl | 0;
    this.Ch = Ch | 0;
    this.Cl = Cl | 0;
    this.Dh = Dh | 0;
    this.Dl = Dl | 0;
    this.Eh = Eh | 0;
    this.El = El | 0;
    this.Fh = Fh | 0;
    this.Fl = Fl | 0;
    this.Gh = Gh | 0;
    this.Gl = Gl | 0;
    this.Hh = Hh | 0;
    this.Hl = Hl | 0;
  }
  _cloneInto(to) {
    (to || (to = new this.constructor())).set(...this.get());
    return this._cloneIntoMeta(to);
  }
  process(view, offset) {
    for (let i = 0; i < 16; i++, offset += 4) {
      SHA512_W_H[i] = view.getUint32(offset);
      SHA512_W_L[i] = view.getUint32(offset += 4);
    }
    for (let i = 16; i < 80; i++) {
      const W15h = SHA512_W_H[i - 15] | 0;
      const W15l = SHA512_W_L[i - 15] | 0;
      const s0h = rotrSH(W15h, W15l, 1) ^ rotrSH(W15h, W15l, 8) ^ shrSH(W15h, W15l, 7);
      const s0l = rotrSL(W15h, W15l, 1) ^ rotrSL(W15h, W15l, 8) ^ shrSL(W15h, W15l, 7);
      const W2h = SHA512_W_H[i - 2] | 0;
      const W2l = SHA512_W_L[i - 2] | 0;
      const s1h = rotrSH(W2h, W2l, 19) ^ rotrBH(W2h, W2l, 61) ^ shrSH(W2h, W2l, 6);
      const s1l = rotrSL(W2h, W2l, 19) ^ rotrBL(W2h, W2l, 61) ^ shrSL(W2h, W2l, 6);
      const SUMl = add4L(s0l, s1l, SHA512_W_L[i - 7], SHA512_W_L[i - 16]);
      const SUMh = add4H(SUMl, s0h, s1h, SHA512_W_H[i - 7], SHA512_W_H[i - 16]);
      SHA512_W_H[i] = SUMh | 0;
      SHA512_W_L[i] = SUMl | 0;
    }
    let { Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl } = this;
    for (let i = 0; i < 80; i++) {
      const sigma1h = rotrSH(Eh, El, 14) ^ rotrSH(Eh, El, 18) ^ rotrBH(Eh, El, 41);
      const sigma1l = rotrSL(Eh, El, 14) ^ rotrSL(Eh, El, 18) ^ rotrBL(Eh, El, 41);
      const CHIh = Eh & Fh ^ ~Eh & Gh;
      const CHIl = El & Fl ^ ~El & Gl;
      const T1ll = add5L(Hl, sigma1l, CHIl, SHA512_Kl[i], SHA512_W_L[i]);
      const T1h = add5H(T1ll, Hh, sigma1h, CHIh, SHA512_Kh[i], SHA512_W_H[i]);
      const T1l = T1ll | 0;
      const sigma0h = rotrSH(Ah, Al, 28) ^ rotrBH(Ah, Al, 34) ^ rotrBH(Ah, Al, 39);
      const sigma0l = rotrSL(Ah, Al, 28) ^ rotrBL(Ah, Al, 34) ^ rotrBL(Ah, Al, 39);
      const MAJh = Ah & Bh ^ Ah & Ch ^ Bh & Ch;
      const MAJl = Al & Bl ^ Al & Cl ^ Bl & Cl;
      Hh = Gh | 0;
      Hl = Gl | 0;
      Gh = Fh | 0;
      Gl = Fl | 0;
      Fh = Eh | 0;
      Fl = El | 0;
      ({ h: Eh, l: El } = add(Dh | 0, Dl | 0, T1h | 0, T1l | 0));
      Dh = Ch | 0;
      Dl = Cl | 0;
      Ch = Bh | 0;
      Cl = Bl | 0;
      Bh = Ah | 0;
      Bl = Al | 0;
      const All = add3L(T1l, sigma0l, MAJl);
      Ah = add3H(All, T1h, sigma0h, MAJh);
      Al = All | 0;
    }
    ({ h: Ah, l: Al } = add(this.Ah | 0, this.Al | 0, Ah | 0, Al | 0));
    ({ h: Bh, l: Bl } = add(this.Bh | 0, this.Bl | 0, Bh | 0, Bl | 0));
    ({ h: Ch, l: Cl } = add(this.Ch | 0, this.Cl | 0, Ch | 0, Cl | 0));
    ({ h: Dh, l: Dl } = add(this.Dh | 0, this.Dl | 0, Dh | 0, Dl | 0));
    ({ h: Eh, l: El } = add(this.Eh | 0, this.El | 0, Eh | 0, El | 0));
    ({ h: Fh, l: Fl } = add(this.Fh | 0, this.Fl | 0, Fh | 0, Fl | 0));
    ({ h: Gh, l: Gl } = add(this.Gh | 0, this.Gl | 0, Gh | 0, Gl | 0));
    ({ h: Hh, l: Hl } = add(this.Hh | 0, this.Hl | 0, Hh | 0, Hl | 0));
    this.set(Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl);
  }
  roundClean() {
    clean(SHA512_W_H, SHA512_W_L);
  }
  destroy() {
    this.destroyed = true;
    clean(this.buffer);
    this.set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }
};
var _SHA512 = class extends SHA2_64B {
  constructor() {
    super(64, SHA512_IV);
  }
};
var sha256 = /* @__PURE__ */ createHasher(
  () => new _SHA256(),
  /* @__PURE__ */ oidNist(1)
);
var sha512 = /* @__PURE__ */ createHasher(
  () => new _SHA512(),
  /* @__PURE__ */ oidNist(3)
);

// node_modules/@noble/curves/utils.js
function aarray(item, title, inner = () => {
}) {
  if (!Array.isArray(item))
    throw new TypeError(`"${title}" expected array, got type=${typeof item}`);
  for (let i = 0; i < item.length; i++)
    inner(item[i], `${title}[${i}]`);
  return item;
}
var abytes2 = (value, length, title) => abytes(value, length, title);
var anumber2 = anumber;
function aobject2(value, title = "object") {
  if (value === null || typeof value !== "object" || Array.isArray(value))
    throw new TypeError(title === "object" ? "expected valid options object" : `"${title}" expected object, got type=${typeof value}`);
  return value;
}
function afunction(value, title) {
  if (typeof value !== "function")
    throw new TypeError(`"${title}" is invalid: expected function, got ${typeof value}`);
  return value;
}
var bytesToHex2 = bytesToHex;
var hexToBytes2 = (hex) => hexToBytes(hex);
var isBytes2 = isBytes;
var randomBytes2 = (bytesLength) => randomBytes(bytesLength);
var _0n = /* @__PURE__ */ BigInt(0);
var _1n = /* @__PURE__ */ BigInt(1);
var atitle2 = (title) => title ? `"${title}" ` : "";
function abool(value, title = "") {
  if (typeof value !== "boolean")
    throw new TypeError(atitle2(title) + "expected boolean, got type=" + typeof value);
  return value;
}
function abignumber(n) {
  if (typeof n === "bigint") {
    if (!isPosBig(n))
      throw new RangeError("positive bigint expected, got " + n);
  } else
    anumber2(n);
  return n;
}
function asafenumber(value, title = "") {
  if (typeof value !== "number") {
    const prefix = title && `"${title}" `;
    throw new TypeError(prefix + "expected number, got type=" + typeof value);
  }
  if (!Number.isSafeInteger(value)) {
    const prefix = title && `"${title}" `;
    throw new RangeError(prefix + "expected safe integer, got " + value);
  }
}
function hexToNumber(hex) {
  if (typeof hex !== "string")
    throw new TypeError("hex string expected, got " + typeof hex);
  return hex === "" ? _0n : BigInt("0x" + hex);
}
function bytesToNumberBE(bytes) {
  return hexToNumber(bytesToHex(bytes));
}
function bytesToNumberLE(bytes) {
  return hexToNumber(bytesToHex(copyBytes2(abytes(bytes)).reverse()));
}
function numberToBytesBE(n, len) {
  anumber(len);
  if (len === 0)
    throw new Error("zero output length is invalid");
  n = abignumber(n);
  const expectedLen = len * 2;
  const hex = n.toString(16);
  if (hex.length > expectedLen)
    throw new RangeError("number is too large");
  return hexToBytes(hex.padStart(expectedLen, "0"));
}
function numberToBytesLE(n, len) {
  return numberToBytesBE(n, len).reverse();
}
function copyBytes2(bytes) {
  return Uint8Array.from(abytes2(bytes));
}
function isPosBig(n) {
  return typeof n === "bigint" && _0n <= n;
}
function inRange(n, min, max) {
  return isPosBig(n) && isPosBig(min) && isPosBig(max) && min <= n && n < max;
}
function aInRange(title, n, min, max) {
  if (!inRange(n, min, max))
    throw new RangeError("expected valid " + title + ": " + min + " <= n < " + max + ", got " + n);
}
function bitLen(n) {
  if (n < _0n)
    throw new Error("expected non-negative bigint, got " + n);
  return n === _0n ? 0 : n.toString(2).length;
}
var bitMask = (n) => {
  asafenumber(n, "n");
  return (_1n << BigInt(n)) - _1n;
};
function validateObject(object, fields = {}, optFields = {}, title = "object") {
  aobject2(object, title);
  aobject2(fields, "fields");
  aobject2(optFields, "optFields");
  function checkField(fieldName, expectedType, isOpt) {
    const label = title === "object" ? `param "${String(fieldName)}"` : `"${title}.${String(fieldName)}"`;
    const val = object[fieldName];
    if (!Object.hasOwn(object, fieldName) && (isOpt ? val !== void 0 : expectedType !== "function")) {
      throw new TypeError(`${label} is invalid: expected own property`);
    }
    if (isOpt && val === void 0)
      return;
    const current = typeof val;
    if (current !== expectedType || val === null)
      throw new TypeError(`${label} is invalid: expected ${expectedType}, got ${current}`);
  }
  const iter = (f, isOpt) => Object.entries(f).forEach(([k, v]) => checkField(k, v, isOpt));
  iter(fields, false);
  iter(optFields, true);
}

// node_modules/@noble/curves/abstract/modular.js
var _0n2 = /* @__PURE__ */ BigInt(0);
var _1n2 = /* @__PURE__ */ BigInt(1);
var _2n = /* @__PURE__ */ BigInt(2);
var _3n = /* @__PURE__ */ BigInt(3);
var _4n = /* @__PURE__ */ BigInt(4);
var _5n = /* @__PURE__ */ BigInt(5);
var _7n = /* @__PURE__ */ BigInt(7);
var _8n = /* @__PURE__ */ BigInt(8);
var _9n = /* @__PURE__ */ BigInt(9);
var _15n = /* @__PURE__ */ BigInt(15);
var _16n = /* @__PURE__ */ BigInt(16);
var POW_WINDOWED_MIN = /* @__PURE__ */ BigInt("0x10000000000000000");
function mod(a, b) {
  if (b <= _0n2)
    throw new Error("mod: expected positive modulus, got " + b);
  const result = a % b;
  return result >= _0n2 ? result : b + result;
}
function pow(num, power, modulo) {
  if (modulo <= _1n2)
    throw new Error("pow: expected modulus > 1, got " + modulo);
  if (typeof power !== "bigint")
    throw new TypeError("invalid exponent: expected bigint, got " + typeof power);
  if (power < _0n2)
    throw new Error("invalid exponent, negatives unsupported");
  if (power === _0n2)
    return _1n2;
  if (power === _1n2)
    return num;
  let d = num % modulo;
  if (d < _0n2)
    d += modulo;
  if (power < POW_WINDOWED_MIN) {
    let p2 = _1n2;
    while (power > _0n2) {
      if (power & _1n2)
        p2 = p2 * d % modulo;
      d = d * d % modulo;
      power >>= _1n2;
    }
    return p2;
  }
  const digits = [];
  while (power > _0n2) {
    digits.push(Number(power & _15n));
    power >>= _4n;
  }
  const table = new Array(16);
  table[0] = _1n2;
  table[1] = d;
  for (let i = 2; i < 16; i++)
    table[i] = table[i - 1] * d % modulo;
  let p = table[digits[digits.length - 1]];
  for (let w = digits.length - 2; w >= 0; w--) {
    p = p * p % modulo;
    p = p * p % modulo;
    p = p * p % modulo;
    p = p * p % modulo;
    const digit = digits[w];
    if (digit !== 0)
      p = p * table[digit] % modulo;
  }
  return p;
}
function pow2(x, power, modulo) {
  if (modulo <= _1n2)
    throw new Error("pow2: expected modulus > 1, got " + modulo);
  if (power < _0n2)
    throw new Error("pow2: expected non-negative exponent, got " + power);
  let res = x;
  while (power-- > _0n2) {
    res *= res;
    res %= modulo;
  }
  return res;
}
function invert(number, modulo) {
  if (number === _0n2)
    throw new Error("invert: expected non-zero number");
  if (modulo <= _1n2)
    throw new Error("invert: expected modulus > 1, got " + modulo);
  let a = mod(number, modulo);
  let b = modulo;
  let x = _0n2, u = _1n2;
  while (a !== _0n2) {
    const q = b / a;
    const r = b - a * q;
    const m = x - u * q;
    b = a, a = r, x = u, u = m;
  }
  const gcd = b;
  if (gcd !== _1n2)
    throw new Error("invert: does not exist");
  return mod(x, modulo);
}
function assertIsSquare(Fp, root, n) {
  const F = Fp;
  if (!F.eql(F.sqr(root), n))
    throw new Error("Cannot find square root");
}
function aoddModulus(order, fnName) {
  if ((order & _1n2) === _0n2)
    throw new Error(fnName + ": expected odd modulus, got " + order);
}
function sqrt3mod4(Fp, n) {
  const F = Fp;
  const p1div4 = (F.ORDER + _1n2) / _4n;
  const root = F.pow(n, p1div4);
  assertIsSquare(F, root, n);
  return root;
}
function sqrt5mod8(Fp, n) {
  const F = Fp;
  const p5div8 = (F.ORDER - _5n) / _8n;
  const n2 = F.mul(n, _2n);
  const v = F.pow(n2, p5div8);
  const nv = F.mul(n, v);
  const i = F.mul(F.mul(nv, _2n), v);
  const root = F.mul(nv, F.sub(i, F.ONE));
  assertIsSquare(F, root, n);
  return root;
}
function sqrt9mod16(P) {
  const Fp_ = Field(P);
  const tn = tonelliShanks(P);
  const c1 = tn(Fp_, Fp_.neg(Fp_.ONE));
  const c2 = tn(Fp_, c1);
  const c3 = tn(Fp_, Fp_.neg(c1));
  const c4 = (P + _7n) / _16n;
  return ((Fp, n) => {
    const F = Fp;
    let tv1 = F.pow(n, c4);
    let tv2 = F.mul(tv1, c1);
    const tv3 = F.mul(tv1, c2);
    const tv4 = F.mul(tv1, c3);
    const e1 = F.eql(F.sqr(tv2), n);
    const e2 = F.eql(F.sqr(tv3), n);
    tv1 = F.cmov(tv1, tv2, e1);
    tv2 = F.cmov(tv4, tv3, e2);
    const e3 = F.eql(F.sqr(tv2), n);
    const root = F.cmov(tv1, tv2, e3);
    assertIsSquare(F, root, n);
    return root;
  });
}
function tonelliShanks(P) {
  if (P < _3n)
    throw new Error("sqrt is not defined for small field");
  aoddModulus(P, "tonelliShanks");
  let Q = P - _1n2;
  let S = 0;
  while (Q % _2n === _0n2) {
    Q /= _2n;
    S++;
  }
  let Z = _2n;
  const _Fp = Field(P);
  while (FpLegendre(_Fp, Z) === 1) {
    if (Z++ > 1e3)
      throw new Error("Cannot find square root: probably non-prime P");
  }
  if (S === 1)
    return sqrt3mod4;
  let cc = _Fp.pow(Z, Q);
  const Q1div2 = (Q + _1n2) / _2n;
  return function tonelliSlow(Fp, n) {
    const F = Fp;
    if (F.is0(n))
      return n;
    if (FpLegendre(F, n) !== 1)
      throw new Error("Cannot find square root");
    let M = S;
    let c = F.mul(F.ONE, cc);
    let t = F.pow(n, Q);
    let R = F.pow(n, Q1div2);
    while (!F.eql(t, F.ONE)) {
      if (F.is0(t))
        throw new Error("Cannot find square root: probably non-prime P");
      let i = 1;
      let t_tmp = F.sqr(t);
      while (!F.eql(t_tmp, F.ONE)) {
        i++;
        t_tmp = F.sqr(t_tmp);
        if (i === M)
          throw new Error("Cannot find square root");
      }
      const exponent = _1n2 << BigInt(M - i - 1);
      const b = F.pow(c, exponent);
      M = i;
      c = F.sqr(b);
      t = F.mul(t, c);
      R = F.mul(R, b);
    }
    return R;
  };
}
function FpSqrt(P) {
  aoddModulus(P, "Fp.sqrt");
  if (P % _4n === _3n)
    return sqrt3mod4;
  if (P % _8n === _5n)
    return sqrt5mod8;
  if (P % _16n === _9n)
    return sqrt9mod16(P);
  return tonelliShanks(P);
}
var isNegativeLE = (num, modulo) => (mod(num, modulo) & _1n2) === _1n2;
var FIELD_FIELDS = [
  "create",
  "isValid",
  "is0",
  "neg",
  "inv",
  "sqrt",
  "sqr",
  "eql",
  "add",
  "sub",
  "mul",
  "pow",
  "div",
  "addN",
  "subN",
  "mulN",
  "sqrN"
];
function validateField(field) {
  aobject2(field, "field");
  if (typeof field.ORDER !== "bigint")
    throw new TypeError('param "ORDER" is invalid: expected bigint, got ' + typeof field.ORDER);
  asafenumber(field.BYTES, "BYTES");
  asafenumber(field.BITS, "BITS");
  for (const name of FIELD_FIELDS)
    afunction(field[name], "field." + name);
  if (field.BYTES < 1 || field.BITS < 1)
    throw new Error("invalid field: expected BYTES/BITS > 0");
  if (field.ORDER <= _1n2)
    throw new Error("invalid field: expected ORDER > 1, got " + field.ORDER);
  return field;
}
function FpInvertBatch(Fp, nums, passZero = false) {
  validateField(Fp);
  aarray(nums, "nums");
  abool(passZero, "passZero");
  const F = Fp;
  const inverted = new Array(nums.length).fill(passZero ? F.ZERO : void 0);
  const multipliedAcc = nums.reduce((acc, num, i) => {
    if (F.is0(num))
      return acc;
    inverted[i] = acc;
    return F.mul(acc, num);
  }, F.ONE);
  const invertedAcc = F.inv(multipliedAcc);
  nums.reduceRight((acc, num, i) => {
    if (F.is0(num))
      return acc;
    inverted[i] = F.mul(acc, inverted[i]);
    return F.mul(acc, num);
  }, invertedAcc);
  return inverted;
}
function FpLegendre(Fp, n) {
  validateField(Fp);
  const F = Fp;
  aoddModulus(F.ORDER, "FpLegendre");
  const p1mod2 = (F.ORDER - _1n2) / _2n;
  const powered = F.pow(n, p1mod2);
  const yes = F.eql(powered, F.ONE);
  const zero = F.eql(powered, F.ZERO);
  const no = F.eql(powered, F.neg(F.ONE));
  if (!yes && !zero && !no)
    throw new Error("invalid Legendre symbol result");
  return yes ? 1 : zero ? 0 : -1;
}
function nLength(n, nBitLength) {
  if (nBitLength !== void 0)
    anumber2(nBitLength);
  if (n <= _0n2)
    throw new Error("invalid n length: expected positive n, got " + n);
  if (nBitLength !== void 0 && nBitLength < 1)
    throw new Error("invalid n length: expected positive bit length, got " + nBitLength);
  const bits = bitLen(n);
  if (nBitLength !== void 0 && nBitLength < bits)
    throw new Error(`invalid n length: expected nBitLength (${nBitLength}) >= bitLen(n) (${bits})`);
  const _nBitLength = nBitLength !== void 0 ? nBitLength : bits;
  const nByteLength = Math.ceil(_nBitLength / 8);
  return { nBitLength: _nBitLength, nByteLength };
}
var FIELD_SQRT = /* @__PURE__ */ new WeakMap();
var _Field = class {
  constructor(ORDER, opts = {}) {
    __publicField(this, "ORDER");
    __publicField(this, "BITS");
    __publicField(this, "BYTES");
    __publicField(this, "isLE");
    __publicField(this, "ZERO", _0n2);
    __publicField(this, "ONE", _1n2);
    __publicField(this, "_lengths");
    __publicField(this, "_mod");
    if (ORDER <= _1n2)
      throw new Error("invalid field: expected ORDER > 1, got " + ORDER);
    let _nbitLength = void 0;
    this.isLE = false;
    if (opts != null && typeof opts === "object") {
      if (typeof opts.BITS === "number")
        _nbitLength = opts.BITS;
      if (typeof opts.sqrt === "function")
        Object.defineProperty(this, "sqrt", { value: opts.sqrt, enumerable: true });
      if (typeof opts.isLE === "boolean")
        this.isLE = opts.isLE;
      if (opts.allowedLengths)
        this._lengths = Object.freeze(opts.allowedLengths.slice());
      if (typeof opts.modFromBytes === "boolean")
        this._mod = opts.modFromBytes;
    }
    const { nBitLength, nByteLength } = nLength(ORDER, _nbitLength);
    if (nByteLength > 2048)
      throw new Error("invalid field: expected ORDER of <= 2048 bytes");
    this.ORDER = ORDER;
    this.BITS = nBitLength;
    this.BYTES = nByteLength;
    Object.freeze(this);
  }
  create(num) {
    return mod(num, this.ORDER);
  }
  isValid(num) {
    if (typeof num !== "bigint")
      throw new TypeError("invalid field element: expected bigint, got " + typeof num);
    return _0n2 <= num && num < this.ORDER;
  }
  is0(num) {
    return num === _0n2;
  }
  // is valid and invertible
  isValidNot0(num) {
    return !this.is0(num) && this.isValid(num);
  }
  isOdd(num) {
    return (num & _1n2) === _1n2;
  }
  neg(num) {
    return mod(-num, this.ORDER);
  }
  eql(lhs, rhs) {
    return lhs === rhs;
  }
  sqr(num) {
    return mod(num * num, this.ORDER);
  }
  add(lhs, rhs) {
    return mod(lhs + rhs, this.ORDER);
  }
  sub(lhs, rhs) {
    return mod(lhs - rhs, this.ORDER);
  }
  mul(lhs, rhs) {
    return mod(lhs * rhs, this.ORDER);
  }
  pow(num, power) {
    return pow(num, power, this.ORDER);
  }
  div(lhs, rhs) {
    return mod(lhs * invert(rhs, this.ORDER), this.ORDER);
  }
  // Same as above, but doesn't normalize
  sqrN(num) {
    return num * num;
  }
  addN(lhs, rhs) {
    return lhs + rhs;
  }
  subN(lhs, rhs) {
    return lhs - rhs;
  }
  mulN(lhs, rhs) {
    return lhs * rhs;
  }
  inv(num) {
    return invert(num, this.ORDER);
  }
  sqrt(num) {
    let sqrt = FIELD_SQRT.get(this);
    if (!sqrt)
      FIELD_SQRT.set(this, sqrt = FpSqrt(this.ORDER));
    return sqrt(this, num);
  }
  toBytes(num) {
    return this.isLE ? numberToBytesLE(num, this.BYTES) : numberToBytesBE(num, this.BYTES);
  }
  fromBytes(bytes, skipValidation = false) {
    abytes2(bytes);
    const { _lengths: allowedLengths, BYTES, isLE: isLE3, ORDER, _mod: modFromBytes } = this;
    if (allowedLengths) {
      if (bytes.length < 1 || !allowedLengths.includes(bytes.length) || bytes.length > BYTES) {
        throw new Error("Field.fromBytes: expected " + allowedLengths + " bytes, got " + bytes.length);
      }
      const padded = new Uint8Array(BYTES);
      padded.set(bytes, isLE3 ? 0 : padded.length - bytes.length);
      bytes = padded;
    }
    if (bytes.length !== BYTES)
      throw new Error("Field.fromBytes: expected " + BYTES + " bytes, got " + bytes.length);
    let scalar = isLE3 ? bytesToNumberLE(bytes) : bytesToNumberBE(bytes);
    if (modFromBytes)
      scalar = mod(scalar, ORDER);
    if (!skipValidation) {
      if (!this.isValid(scalar))
        throw new Error("invalid field element: outside of range 0..ORDER");
    }
    return scalar;
  }
  // TODO: we don't need it here, move out to separate fn
  invertBatch(lst) {
    return FpInvertBatch(this, lst, true);
  }
  // We can't move this out because Fp6, Fp12 implement it
  // and it's unclear what to return in there.
  cmov(a, b, condition) {
    abool(condition, "condition");
    return condition ? b : a;
  }
};
function Field(ORDER, opts = {}) {
  Object.freeze(_Field.prototype);
  return new _Field(ORDER, opts);
}

// node_modules/@noble/curves/abstract/curve.js
var _0n3 = /* @__PURE__ */ BigInt(0);
var _1n3 = /* @__PURE__ */ BigInt(1);
var _4n2 = /* @__PURE__ */ BigInt(4);
var BLIND_BYTES = 16;
var BLIND_BITS = 128;
var FW_WINDOW = 5;
var TABLE_BYTES_MAX = /* @__PURE__ */ (() => 2 ** 31)();
function validatePointCons(Point) {
  const pc = Point;
  if (typeof pc !== "function")
    throw new TypeError('"Point" expected constructor, got type=' + typeof Point);
  afunction(pc.fromAffine, "Point.fromAffine");
  afunction(pc.fromBytes, "Point.fromBytes");
  afunction(pc.fromHex, "Point.fromHex");
  aobject2(pc.BASE, "Point.BASE");
  aobject2(pc.ZERO, "Point.ZERO");
  validateField(pc.Fp);
  validateField(pc.Fn);
}
function normalizeZ(c, points) {
  validatePointCons(c);
  validateMSMPoints(points, c);
  const invertedZs = FpInvertBatch(c.Fp, points.map((p) => p.Z));
  return points.map((p, i) => c.fromAffine(p.toAffine(invertedZs[i])));
}
function validateW(W, bits, min = 1) {
  if (!Number.isSafeInteger(W) || W < min || W > bits)
    throw new Error("invalid window size, expected [" + min + ".." + bits + "], got W=" + W);
}
function validateTableBytes(numPoints, fpBytes) {
  const bytes = numPoints * (4 * fpBytes + 128);
  if (bytes > TABLE_BYTES_MAX)
    throw new Error("invalid window size: table would need ~" + Math.ceil(bytes / 2 ** 20) + " MiB, max " + TABLE_BYTES_MAX / 2 ** 20 + " MiB");
}
function probeRandomBytes(randomBytes6, length) {
  if (randomBytes6 === void 0)
    return void 0;
  afunction(randomBytes6, "randomBytes");
  try {
    const probe = randomBytes6(length);
    if (!isBytes2(probe) || probe.length !== length)
      return void 0;
  } catch {
    return void 0;
  }
  return randomBytes6;
}
function validateMSMPoints(points, c) {
  aarray(points, "points");
  points.forEach((p, i) => {
    if (!(p instanceof c))
      throw new Error("invalid point at index " + i);
  });
}
function validateMSMScalars(scalars, field, maxScalar) {
  if (!Array.isArray(scalars))
    throw new Error("array of scalars expected");
  scalars.forEach((s, i) => {
    const ok = maxScalar === void 0 ? field.isValid(s) : isPosBig(s) && s < maxScalar;
    if (!ok)
      throw new Error("invalid scalar at index " + i);
  });
}
var pointWindowSizes = /* @__PURE__ */ new WeakMap();
function getWindowSize(P) {
  return pointWindowSizes.get(P) || 1;
}
function oddMultiples(p, size) {
  const dbl = p.double();
  const t = [p];
  for (let j = 1; j < size; j++)
    t.push(t[j - 1].add(dbl));
  return t;
}
function wnafDigits(n, W) {
  const size = 2 ** W;
  const half = size / 2;
  const mask = BigInt(size - 1);
  const d = [];
  while (n > _0n3) {
    let w = 0;
    if (n & _1n3) {
      w = Number(n & mask);
      if (w >= half)
        w -= size;
      n -= BigInt(w);
    }
    d.push(w);
    n >>= _1n3;
  }
  return d;
}
function signedWindowDigits(n, W, windows) {
  const size = 2 ** W;
  const half = size / 2;
  const mask = BigInt(size - 1);
  const shiftBy = BigInt(W);
  const d = [];
  for (let w = 0; w < windows; w++) {
    let v = Number(n & mask);
    n >>= shiftBy;
    if (v > half) {
      v -= size;
      n += _1n3;
    }
    d.push(v);
  }
  if (n !== _0n3)
    throw new Error("invalid wnaf");
  return d;
}
function wnafWalk(zero, tables, digits) {
  let max = 0;
  for (const d of digits)
    max = Math.max(max, d.length);
  let acc = zero;
  for (let bit = max - 1; bit >= 0; bit--) {
    if (bit !== max - 1)
      acc = acc.double();
    for (let i = 0; i < digits.length; i++) {
      const w = digits[i][bit];
      if (w) {
        const item = tables[i][Math.abs(w) - 1 >> 1];
        acc = acc.add(w < 0 ? item.negate() : item);
      }
    }
  }
  return acc;
}
var ScalarMultiplier = class {
  // Parametrized with a given Point class (not individual point)
  constructor(Point, randomBytes6) {
    __publicField(this, "Point");
    __publicField(this, "BASE");
    __publicField(this, "ZERO");
    __publicField(this, "randomBytes");
    __publicField(this, "wnafPrecomputes", /* @__PURE__ */ new WeakMap());
    __publicField(this, "baseCanBeBlinded");
    __publicField(this, "bits");
    validatePointCons(Point);
    this.randomBytes = probeRandomBytes(randomBytes6, BLIND_BYTES);
    this.Point = Point;
    this.BASE = Point.BASE;
    this.ZERO = Point.ZERO;
    this.bits = Point.Fn.BITS;
  }
  /**
   * Creates a signed fixed-window wNAF precomputation table: for every window w, the
   * multiples `[1..2^(W−1)]⋅2^(w⋅W)⋅P`, flattened. All doublings are baked into the table,
   * so cached multiplication is additions-only. `windows = ceil(bits/W) + 1`: the extra
   * window absorbs the final carry of signed-digit recoding.
   * For a 256-bit curve and W=6, the table is 44⋅32 = 1408 points.
   * @param point - Point instance
   * @param W - window size
   * @param bits - scalar bitlength the table must cover
   */
  buildWnafTable(point, W, bits) {
    const windows = Math.ceil(bits / W) + 1;
    const half = 2 ** (W - 1);
    const comp = [];
    let base = point;
    for (let w = 0; w < windows; w++) {
      let acc = base;
      for (let i = 0; i < half; i++) {
        comp.push(acc);
        acc = acc.add(base);
      }
      base = comp[comp.length - 1].double();
    }
    return { W, bits, windows, comp };
  }
  /**
   * Implements ec multiplication using precomputed signed fixed-window wNAF tables.
   * Constant-time: fixed window count with one table addition per window — zero digits feed
   * the fake accumulator — and no doublings; the lookup scans the whole window slice.
   * Scalar bounds are validated by the public entry points ({@link ScalarMultiplier.mulCT},
   * {@link ScalarMultiplier.mulCTBlinded}, {@link ScalarMultiplier.mulUnsafe});
   * signedWindowDigits throws if `n` exceeds the table.
   * @returns real and fake (for const-time) points
   */
  wnafCachedCT(precomputes, n) {
    const { W, windows, comp } = precomputes;
    const half = 2 ** (W - 1);
    const digits = signedWindowDigits(n, W, windows);
    let p = this.ZERO;
    let f = this.BASE;
    for (let w = 0; w < windows; w++) {
      const digit = digits[w];
      const start = w * half;
      const idx = Math.abs(digit) - 1;
      let sel = comp[start];
      for (let i = 1; i < half; i++)
        sel = i === idx ? comp[start + i] : sel;
      const neg = sel.negate();
      if (digit === 0)
        f = f.add(comp[start]);
      else
        p = p.add(digit < 0 ? neg : sel);
    }
    return { p, f };
  }
  // Cache key is point identity plus (W, bits); at most two entries exist per point (public-width
  // `Fn.BITS` and blinded `Fn.BITS + BLIND_BITS`). Callers must not reuse the same point with
  // incompatible `transform(...)` layouts and expect a separate cache entry.
  getWnafPrecomputes(W, point, bits, transform) {
    let entries = this.wnafPrecomputes.get(point);
    let comp = entries?.find((entry) => entry.W === W && entry.bits === bits);
    if (!comp) {
      comp = this.buildWnafTable(point, W, bits);
      if (typeof transform === "function")
        comp = { ...comp, comp: transform(comp.comp) };
      if (!entries) {
        entries = [];
        this.wnafPrecomputes.set(point, entries);
      }
      entries.push(comp);
    }
    return comp;
  }
  assertPoint(point) {
    if (!(point instanceof this.Point))
      throw new TypeError('"point" expected Point instance, got type=' + typeof point);
  }
  // Shared prologue of the constant-time entry points. Rejects scalar 0: in key/signature-style
  // callers a zero scalar means broken upstream plumbing, and concrete Points already reject it.
  // Uses inRange instead of Fn.isValidNot0: validateField() only certifies the arithmetic subset.
  validateMulInput(point, scalar) {
    this.assertPoint(point);
    if (!inRange(scalar, _1n3, this.Point.Fn.ORDER))
      throw new Error("invalid scalar");
  }
  // Constant-time dispatch shared by mulCT / mulCTBlinded. Un-precomputed points (W===1, e.g.
  // ECDH peer keys) skip building a throwaway cached table in favor of a small fixed-window
  // multiply. `n` must be < 2^bits.
  runCT(point, n, bits, transform) {
    const W = getWindowSize(point);
    if (W === 1)
      return this.fixedWindowCT(point, n, bits);
    return this.wnafCachedCT(this.getWnafPrecomputes(W, point, bits, transform), n);
  }
  mulCT(point, scalar, transform) {
    this.validateMulInput(point, scalar);
    return this.runCT(point, scalar, this.bits, transform);
  }
  mulCTBlinded(point, scalar, transform) {
    this.validateMulInput(point, scalar);
    if (this.randomBytes === void 0)
      throw new Error("randomBytes is required for scalar blinding");
    const bits = this.Point.Fn.BITS + BLIND_BITS;
    const blind = this.randomBytes(BLIND_BYTES);
    if (!isBytes2(blind) || blind.length !== BLIND_BYTES)
      throw new Error("randomBytes returned invalid byte array");
    blind[0] = blind[0] & 63 | 128;
    const n = scalar + bytesToNumberBE(blind) * this.Point.Fn.ORDER;
    return this.runCT(point, n, bits, transform);
  }
  /**
   * Constant-time multiplication `n*point` for an un-precomputed point, via a small fixed window.
   * A cached wNAF table only pays off when reused; a flat 2^FW_WINDOW table (`size-1` adds) is
   * far cheaper to build for a single use. The point-operation sequence is independent of `n`:
   * build the table, then per window exactly FW_WINDOW doublings, a data-oblivious scan over
   * every table entry, and one addition (adds the identity when the window digit is 0 — never
   * skipped).
   *
   * `n` must be `< 2^bits`. Assumes complete addition (adding the identity costs the same as any
   * add), which holds for the Weierstrass/Edwards point types used here. The table is left in
   * projective form (no normalizeZ): normalizing this small a table costs more than the
   * mixed-add savings it would buy for a single multiply.
   * @returns real point `p`; `f` duplicates it only to match {@link wnafCachedCT}'s return shape
   * (this path needs no fake accumulator — its op-count is already scalar-independent).
   */
  fixedWindowCT(point, n, bits) {
    const W = FW_WINDOW;
    const size = 1 << W;
    const mask = bitMask(W);
    const table = new Array(size);
    table[0] = this.ZERO;
    for (let i = 1; i < size; i++)
      table[i] = table[i - 1].add(point);
    const windows = Math.ceil(bits / W);
    let acc = this.ZERO;
    for (let window = windows - 1; window >= 0; window--) {
      if (window !== windows - 1)
        for (let d = 0; d < W; d++)
          acc = acc.double();
      const digit = Number(n >> BigInt(window * W) & mask);
      let sel = table[0];
      for (let i = 1; i < size; i++)
        sel = i === digit ? table[i] : sel;
      acc = acc.add(sel);
    }
    return { p: acc, f: acc };
  }
  shouldBlind(point, cofactor) {
    if (this.randomBytes === void 0)
      return false;
    if (cofactor === _1n3)
      return true;
    if (point !== this.BASE)
      return false;
    if (this.baseCanBeBlinded === void 0)
      this.baseCanBeBlinded = this.mulUnsafe(this.BASE, this.Point.Fn.ORDER).is0();
    return this.baseCanBeBlinded;
  }
  mulSecret(point, scalar, cofactor, transform) {
    return this.shouldBlind(point, cofactor) ? this.mulCTBlinded(point, scalar, transform) : this.mulCT(point, scalar, transform);
  }
  mulUnsafe(point, scalar, transform) {
    this.assertPoint(point);
    if (!isPosBig(scalar))
      throw new Error("invalid scalar");
    const W = getWindowSize(point);
    if (W === 1 || scalar >= this.Point.Fn.ORDER)
      return mulAddUnsafe(this.Point, [point], [scalar], true);
    const precomputes = this.getWnafPrecomputes(W, point, this.bits, transform);
    return this.wnafCachedCT(precomputes, scalar).p;
  }
  // Remembers the window size used for precomputed wNAF multiplication of the given point
  // and drops any previously built tables. Usually only the base point is precomputed.
  // W=1 resets the point to the un-precomputed (table-less) paths.
  // W is additionally capped so tables stay under ~2 GiB ({@link TABLE_BYTES_MAX}).
  setWindowSize(point, W) {
    this.assertPoint(point);
    validateW(W, this.bits);
    const windows = Math.ceil((this.bits + BLIND_BITS) / W) + 1;
    validateTableBytes(windows * 2 ** (W - 1), this.Point.Fp.BYTES);
    pointWindowSizes.set(point, W);
    this.wnafPrecomputes.delete(point);
  }
  // True when a window size is set: tables themselves are built lazily on first multiply.
  hasWindowSize(point) {
    return getWindowSize(point) !== 1;
  }
};
function mulAddUnsafe(c, points, scalars, allowOversized = false) {
  validatePointCons(c);
  validateMSMPoints(points, c);
  abool(allowOversized, "allowOversized");
  validateMSMScalars(scalars, c.Fn, allowOversized ? c.Fn.ORDER ** _4n2 : void 0);
  if (points.length !== scalars.length)
    throw new Error("arrays of points and scalars must have equal length");
  const tables = points.map((p) => oddMultiples(p, 4));
  const digits = scalars.map((n) => wnafDigits(n, 4));
  return wnafWalk(c.ZERO, tables, digits);
}
function createField(order, field, isLE3) {
  if (field) {
    if (field.ORDER !== order)
      throw new Error("Field.ORDER must match order: Fp == p, Fn == n");
    validateField(field);
    return field;
  } else {
    return Field(order, { isLE: isLE3 });
  }
}
function createCurveFields(type, CURVE, curveOpts = {}, FpFnLE) {
  if (type !== "weierstrass" && type !== "edwards")
    throw new Error('expected curve type "weierstrass" or "edwards"');
  if (FpFnLE === void 0)
    FpFnLE = type === "edwards";
  if (!CURVE || typeof CURVE !== "object")
    throw new Error(`expected valid ${type} CURVE object`);
  validateObject(curveOpts);
  for (const p of ["p", "n", "h"]) {
    const val = CURVE[p];
    if (!(isPosBig(val) && val !== _0n3))
      throw new Error(`CURVE.${p} must be positive bigint`);
  }
  const Fp = createField(CURVE.p, curveOpts.Fp, FpFnLE);
  const Fn = createField(CURVE.n, curveOpts.Fn, FpFnLE);
  const _b = type === "weierstrass" ? "b" : "d";
  const params = ["Gx", "Gy", "a", _b];
  for (const p of params) {
    if (!Fp.isValid(CURVE[p]))
      throw new Error(`CURVE.${p} must be valid field element of CURVE.Fp`);
  }
  CURVE = Object.freeze(Object.assign({}, CURVE));
  return { CURVE, Fp, Fn };
}
function createKeygen(randomSecretKey, getPublicKey) {
  return function keygen(seed) {
    const secretKey = randomSecretKey(seed);
    return { secretKey, publicKey: getPublicKey(secretKey) };
  };
}

// node_modules/@noble/curves/abstract/edwards.js
var _0n4 = /* @__PURE__ */ BigInt(0);
var _1n4 = /* @__PURE__ */ BigInt(1);
var _2n2 = /* @__PURE__ */ BigInt(2);
var _4n3 = /* @__PURE__ */ BigInt(4);
var _8n2 = /* @__PURE__ */ BigInt(8);
function isEdValidXY(Fp, CURVE, x, y) {
  const x2 = Fp.sqr(x);
  const y2 = Fp.sqr(y);
  const left = Fp.add(Fp.mul(CURVE.a, x2), y2);
  const right = Fp.add(Fp.ONE, Fp.mul(CURVE.d, Fp.mul(x2, y2)));
  return Fp.eql(left, right);
}
function edwards(params, extraOpts = {}) {
  validateObject(extraOpts, {}, {}, "extraOpts");
  const opts = extraOpts;
  const validated = createCurveFields("edwards", params, opts, opts.FpFnLE);
  const { Fp, Fn } = validated;
  let CURVE = validated.CURVE;
  const { h: cofactor } = CURVE;
  if (FpLegendre(Fp, CURVE.a) !== 1)
    throw new Error("edwards: CURVE.a must be a square in Fp for complete addition formulas");
  if (FpLegendre(Fp, CURVE.d) !== -1)
    throw new Error("edwards: CURVE.d must be a non-square in Fp for complete addition formulas");
  validateObject(opts, {}, { uvRatio: "function", randomBytes: "function" });
  const randomBytes6 = opts.randomBytes === void 0 ? randomBytes2 : opts.randomBytes;
  const MASK = _2n2 << BigInt(Fp.BYTES * 8) - _1n4;
  function isOdd(n) {
    if (!Fp.isOdd)
      throw new Error("Field does not have .isOdd()");
    return Fp.isOdd(n);
  }
  const uvRatio2 = opts.uvRatio === void 0 ? (u, v) => {
    try {
      return { isValid: true, value: Fp.sqrt(Fp.div(u, v)) };
    } catch (e) {
      return { isValid: false, value: _0n4 };
    }
  } : opts.uvRatio;
  if (!isEdValidXY(Fp, CURVE, CURVE.Gx, CURVE.Gy))
    throw new Error("bad curve params: generator point");
  const mulA = Fp.eql(CURVE.a, Fp.neg(Fp.ONE)) ? (x) => Fp.neg(x) : Fp.eql(CURVE.a, Fp.ONE) ? (x) => x : (x) => Fp.mul(CURVE.a, x);
  function acoord(title, n, banZero = false) {
    const min = banZero ? _1n4 : _0n4;
    aInRange("coordinate " + title, n, min, MASK);
    return n;
  }
  function aedpoint(other) {
    if (!(other instanceof Point))
      throw new Error("EdwardsPoint expected");
  }
  const _Point = class _Point {
    constructor(X, Y, Z, T) {
      __publicField(this, "X");
      __publicField(this, "Y");
      __publicField(this, "Z");
      __publicField(this, "T");
      this.X = acoord("x", X);
      this.Y = acoord("y", Y);
      this.Z = acoord("z", Z, true);
      this.T = acoord("t", T);
      Object.freeze(this);
    }
    static CURVE() {
      return CURVE;
    }
    /**
     * Create one extended Edwards point from affine coordinates.
     * Does NOT validate that the point is on-curve or torsion-free.
     * Use `.assertValidity()` on adversarial inputs.
     */
    static fromAffine(p) {
      if (p instanceof _Point)
        throw new Error("extended point not allowed");
      const { x, y } = p || {};
      acoord("x", x);
      acoord("y", y);
      return new _Point(x, y, Fp.ONE, Fp.mul(x, y));
    }
    // Uses algo from RFC8032 5.1.3.
    static fromBytes(bytes, zip215 = false) {
      const len = Fp.BYTES;
      const { a, d } = CURVE;
      bytes = copyBytes2(abytes2(bytes, len, "point"));
      abool(zip215, "zip215");
      const normed = copyBytes2(bytes);
      const lastByte = bytes[len - 1];
      normed[len - 1] = lastByte & ~128;
      const y = bytesToNumberLE(normed);
      const max = zip215 ? MASK : Fp.ORDER;
      aInRange("point.y", y, _0n4, max);
      const y2 = Fp.sqr(y);
      const u = Fp.sub(y2, Fp.ONE);
      const v = Fp.sub(Fp.mulN(d, y2), a);
      let { isValid, value: x } = uvRatio2(u, v);
      if (!isValid)
        throw new Error("bad point: invalid y coordinate");
      const isXOdd = isOdd(x);
      const isLastByteOdd = (lastByte & 128) !== 0;
      if (!zip215 && Fp.is0(x) && isLastByteOdd)
        throw new Error("bad point: x=0 and x_0=1");
      if (isLastByteOdd !== isXOdd)
        x = Fp.neg(x);
      return _Point.fromAffine({ x, y });
    }
    static fromHex(hex, zip215 = false) {
      return _Point.fromBytes(hexToBytes2(hex), zip215);
    }
    get x() {
      return this.toAffine().x;
    }
    get y() {
      return this.toAffine().y;
    }
    precompute(windowSize = 6, isLazy = true) {
      wnaf.setWindowSize(this, windowSize);
      if (!isLazy)
        this.multiply(_2n2);
      return this;
    }
    // Useful in fromAffine() - not for fromBytes(), which always created valid points.
    assertValidity() {
      const p = this;
      const { a, d } = CURVE;
      if (p.is0())
        throw new Error("bad point: ZERO");
      const { X, Y, Z, T } = p;
      const X2 = Fp.sqr(X);
      const Y2 = Fp.sqr(Y);
      const Z2 = Fp.sqr(Z);
      const Z4 = Fp.sqr(Z2);
      const aX2 = Fp.mul(X2, a);
      const left = Fp.mul(Fp.add(aX2, Y2), Z2);
      const right = Fp.add(Z4, Fp.mul(d, Fp.mul(X2, Y2)));
      if (!Fp.eql(left, right))
        throw new Error("bad point: equation left != right (1)");
      const XY = Fp.mul(X, Y);
      const ZT = Fp.mul(Z, T);
      if (!Fp.eql(XY, ZT))
        throw new Error("bad point: equation left != right (2)");
    }
    // Compare one point to another.
    equals(other) {
      aedpoint(other);
      const { X: X1, Y: Y1, Z: Z1 } = this;
      const { X: X2, Y: Y2, Z: Z2 } = other;
      const X1Z2 = Fp.mul(X1, Z2);
      const X2Z1 = Fp.mul(X2, Z1);
      const Y1Z2 = Fp.mul(Y1, Z2);
      const Y2Z1 = Fp.mul(Y2, Z1);
      return Fp.eql(X1Z2, X2Z1) && Fp.eql(Y1Z2, Y2Z1);
    }
    is0() {
      return this.equals(_Point.ZERO);
    }
    negate() {
      return new _Point(Fp.neg(this.X), this.Y, this.Z, Fp.neg(this.T));
    }
    // Fast algo for doubling Extended Point.
    // https://hyperelliptic.org/EFD/g1p/auto-twisted-extended.html#doubling-dbl-2008-hwcd
    // Cost: 4M + 4S + 1*a + 6add + 1*2.
    double() {
      const { X: X1, Y: Y1, Z: Z1 } = this;
      const A = Fp.sqr(X1);
      const B = Fp.sqr(Y1);
      const C = Fp.mul(Fp.sqr(Z1), _2n2);
      const D = mulA(A);
      const x1y1 = Fp.addN(X1, Y1);
      const E = Fp.sub(Fp.subN(Fp.sqr(x1y1), A), B);
      const G = Fp.addN(D, B);
      const F = Fp.subN(G, C);
      const H = Fp.subN(D, B);
      const X3 = Fp.mul(E, F);
      const Y3 = Fp.mul(G, H);
      const T3 = Fp.mul(E, H);
      const Z3 = Fp.mul(F, G);
      return new _Point(X3, Y3, Z3, T3);
    }
    // Fast algo for adding 2 Extended Points.
    // https://hyperelliptic.org/EFD/g1p/auto-twisted-extended.html#addition-add-2008-hwcd
    // Cost: 9M + 1*a + 1*d + 7add.
    add(other) {
      aedpoint(other);
      const { d } = CURVE;
      const { X: X1, Y: Y1, Z: Z1, T: T1 } = this;
      const { X: X2, Y: Y2, Z: Z2, T: T2 } = other;
      const A = Fp.mul(X1, X2);
      const B = Fp.mul(Y1, Y2);
      const C = Fp.mul(Fp.mulN(T1, d), T2);
      const D = Fp.mul(Z1, Z2);
      const E = Fp.sub(Fp.subN(Fp.mulN(Fp.addN(X1, Y1), Fp.addN(X2, Y2)), A), B);
      const F = Fp.subN(D, C);
      const G = Fp.addN(D, C);
      const H = Fp.sub(B, mulA(A));
      const X3 = Fp.mul(E, F);
      const Y3 = Fp.mul(G, H);
      const T3 = Fp.mul(E, H);
      const Z3 = Fp.mul(F, G);
      return new _Point(X3, Y3, Z3, T3);
    }
    subtract(other) {
      aedpoint(other);
      return this.add(other.negate());
    }
    // Constant-time multiplication.
    multiply(scalar) {
      if (!Fn.isValidNot0(scalar))
        throw new RangeError("invalid scalar: expected 1 <= sc < curve.n");
      const { p, f } = wnaf.mulSecret(this, scalar, cofactor, normalize);
      return normalize([p, f])[0];
    }
    // Non-constant-time multiplication. Uses double-and-add algorithm.
    // It's faster, but should only be used when you don't care about
    // an exposed private key e.g. sig verification.
    // Keeps the same subgroup-scalar contract: 0 is allowed for public-scalar callers, but
    // n and larger values are rejected instead of being reduced mod n to the identity point.
    multiplyUnsafe(scalar) {
      if (!Fn.isValid(scalar))
        throw new RangeError("invalid scalar: expected 0 <= sc < curve.n");
      if (scalar === _0n4)
        return _Point.ZERO;
      if (this.is0() || scalar === _1n4)
        return this;
      return wnaf.mulUnsafe(this, scalar, normalize);
    }
    // Checks if point is of small order.
    // If you add something to small order point, you will have "dirty"
    // point with torsion component.
    // Clears cofactor and checks if the result is 0.
    isSmallOrder() {
      return this.clearCofactor().is0();
    }
    // Multiplies point by curve order and checks if the result is 0.
    // Returns `false` is the point is dirty.
    isTorsionFree() {
      return wnaf.mulUnsafe(this, CURVE.n).is0();
    }
    // Converts Extended point to default (x, y) coordinates.
    // Can accept precomputed Z^-1 - for example, from invertBatch.
    toAffine(invertedZ) {
      const p = this;
      let iz = invertedZ;
      if (iz != null && typeof iz !== "bigint")
        throw new TypeError('"invertedZ" expected bigint, got type=' + typeof iz);
      const { X, Y, Z } = p;
      const is0 = p.is0();
      if (iz == null)
        iz = is0 ? Fp.create(_8n2) : Fp.inv(Z);
      const x = Fp.mul(X, iz);
      const y = Fp.mul(Y, iz);
      const zz = Fp.mul(Z, iz);
      if (is0)
        return { x: Fp.ZERO, y: Fp.ONE };
      if (!Fp.eql(zz, Fp.ONE))
        throw new Error("invZ was invalid");
      return { x, y };
    }
    clearCofactor() {
      if (cofactor === _1n4)
        return this;
      if (cofactor === _2n2)
        return this.double();
      if (cofactor === _4n3)
        return this.double().double();
      if (cofactor === _8n2)
        return this.double().double().double();
      return this.multiplyUnsafe(cofactor);
    }
    toBytes() {
      const { x, y } = this.toAffine();
      const bytes = Fp.toBytes(y);
      bytes[bytes.length - 1] |= isOdd(x) ? 128 : 0;
      return bytes;
    }
    toHex() {
      return bytesToHex2(this.toBytes());
    }
    toString() {
      return `<Point ${this.is0() ? "ZERO" : this.toHex()}>`;
    }
  };
  __publicField(_Point, "BASE", new _Point(CURVE.Gx, CURVE.Gy, Fp.ONE, Fp.mul(CURVE.Gx, CURVE.Gy)));
  __publicField(_Point, "ZERO", new _Point(Fp.ZERO, Fp.ONE, Fp.ONE, Fp.ZERO));
  __publicField(_Point, "Fp", Fp);
  __publicField(_Point, "Fn", Fn);
  let Point = _Point;
  const normalize = (points) => normalizeZ(Point, points);
  const wnaf = new ScalarMultiplier(Point, randomBytes6);
  if (wnaf.bits >= 6)
    Point.BASE.precompute(6);
  Object.freeze(Point.prototype);
  Object.freeze(Point);
  return Point;
}

// node_modules/@noble/curves/abstract/montgomery.js
var _0n5 = /* @__PURE__ */ BigInt(0);
var _1n5 = /* @__PURE__ */ BigInt(1);
var _2n3 = /* @__PURE__ */ BigInt(2);
function cmask(P, swap) {
  return P + swap - (swap >> _1n5 << _1n5);
}
function cswap(P) {
  const offset = BigInt(6) * P;
  return (mask, x_2, x_3) => {
    const sum = x_2 + x_3;
    const d = offset + x_3 - x_2;
    const a = (d * mask + x_2) % P;
    return { x_2: a, x_3: sum - a };
  };
}
function validateOpts(curve) {
  validateObject(curve, {
    P: "bigint",
    type: "string",
    adjustScalarBytes: "function",
    powPminus2: "function"
  }, {
    randomBytes: "function",
    scalarMultBase: "function"
  });
  return Object.freeze({ ...curve });
}
function montgomery(curveDef) {
  const CURVE = validateOpts(curveDef);
  const { P, type, adjustScalarBytes: adjustScalarBytes2, powPminus2, randomBytes: rand } = CURVE;
  const mulBaseHook = CURVE.scalarMultBase;
  const is25519 = type === "x25519";
  if (!is25519 && type !== "x448")
    throw new Error("invalid type");
  const randomBytes_ = rand === void 0 ? randomBytes2 : rand;
  const montgomeryBits = is25519 ? 255 : 448;
  const swap = cswap(P);
  const fieldLen = is25519 ? 32 : 56;
  const Gu = is25519 ? BigInt(9) : BigInt(5);
  const a24 = is25519 ? BigInt(121665) : BigInt(39081);
  const minScalar = is25519 ? _2n3 ** BigInt(254) : _2n3 ** BigInt(447);
  const maxAdded = is25519 ? BigInt(8) * (_2n3 ** BigInt(251) - _1n5) : BigInt(4) * (_2n3 ** BigInt(445) - _1n5);
  const maxScalar = minScalar + maxAdded + _1n5;
  const modP = (n) => mod(n, P);
  const GuBytes = encodeU(Gu);
  function encodeU(u) {
    return numberToBytesLE(modP(u), fieldLen);
  }
  function decodeU(u) {
    const _u = copyBytes2(abytes2(u, fieldLen, "uCoordinate"));
    if (is25519)
      _u[31] &= 127;
    return modP(bytesToNumberLE(_u));
  }
  function decodeScalar(scalar) {
    return bytesToNumberLE(adjustScalarBytes2(copyBytes2(abytes2(scalar, fieldLen, "scalar"))));
  }
  const lowOrderU = new Set(is25519 ? [
    _0n5,
    _1n5,
    P - _1n5,
    BigInt("325606250916557431795983626356110631294008115727848805560023387167927233504"),
    BigInt("39382357235489614581723060781553021112529911719440698176882885853963445705823")
  ] : [_0n5, _1n5, P - _1n5]);
  function scalarMult(scalar, u) {
    const pointU = decodeU(u);
    if (lowOrderU.has(pointU))
      throw new Error("invalid private or public key received");
    const pu = montgomeryLadder(pointU, decodeScalar(scalar));
    if (pu === _0n5)
      throw new Error("invalid private or public key received");
    return encodeU(pu);
  }
  function scalarMultBase(scalar) {
    if (mulBaseHook === void 0)
      return scalarMult(scalar, GuBytes);
    const k = decodeScalar(scalar);
    aInRange("scalar", k, minScalar, maxScalar);
    const pu = modP(mulBaseHook(k));
    if (pu === _0n5)
      throw new Error("invalid private or public key received");
    return encodeU(pu);
  }
  const getPublicKey = scalarMultBase;
  const getSharedSecret = scalarMult;
  function montgomeryLadder(u, scalar) {
    aInRange("u", u, _0n5, P);
    aInRange("scalar", scalar, minScalar, maxScalar);
    const k = scalar;
    const x_1 = u;
    let x_2 = _1n5;
    let z_2 = _0n5;
    let x_3 = u;
    let z_3 = _1n5;
    const kx = k ^ k >> _1n5;
    for (let t = BigInt(montgomeryBits - 1); t >= _0n5; t--) {
      const mask2 = cmask(P, kx >> t);
      ({ x_2, x_3 } = swap(mask2, x_2, x_3));
      ({ x_2: z_2, x_3: z_3 } = swap(mask2, z_2, z_3));
      const A = x_2 + z_2;
      const AA = modP(A * A);
      const B = x_2 - z_2;
      const BB = modP(B * B);
      const E = AA - BB;
      const C = x_3 + z_3;
      const D = x_3 - z_3;
      const DA = modP(D * A);
      const CB = modP(C * B);
      const dacb = DA + CB;
      const da_cb = DA - CB;
      x_3 = modP(dacb * dacb);
      z_3 = modP(x_1 * modP(da_cb * da_cb));
      x_2 = modP(AA * BB);
      z_2 = modP(E * (AA + modP(a24 * E)));
    }
    const mask = cmask(P, k);
    ({ x_2, x_3 } = swap(mask, x_2, x_3));
    ({ x_2: z_2, x_3: z_3 } = swap(mask, z_2, z_3));
    const z2 = powPminus2(z_2);
    return modP(x_2 * z2);
  }
  const lengths = {
    secretKey: fieldLen,
    publicKey: fieldLen,
    seed: fieldLen
  };
  const randomSecretKey = (seed) => {
    seed = seed === void 0 ? randomBytes_(fieldLen) : seed;
    abytes2(seed, lengths.seed, "seed");
    return seed;
  };
  const utils = { randomSecretKey };
  Object.freeze(lengths);
  Object.freeze(utils);
  return Object.freeze({
    keygen: createKeygen(randomSecretKey, getPublicKey),
    getSharedSecret,
    getPublicKey,
    scalarMult,
    scalarMultBase,
    utils,
    GuBytes: GuBytes.slice(),
    lengths
  });
}

// node_modules/@noble/curves/ed25519.js
var _0n6 = /* @__PURE__ */ BigInt(0);
var _1n6 = /* @__PURE__ */ BigInt(1);
var _2n4 = /* @__PURE__ */ BigInt(2);
var _3n2 = /* @__PURE__ */ BigInt(3);
var _5n2 = /* @__PURE__ */ BigInt(5);
var _8n3 = /* @__PURE__ */ BigInt(8);
var ed25519_CURVE_p = /* @__PURE__ */ BigInt("0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed");
var ed25519_CURVE = /* @__PURE__ */ (() => ({
  p: ed25519_CURVE_p,
  n: BigInt("0x1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed"),
  h: _8n3,
  a: BigInt("0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffec"),
  d: BigInt("0x52036cee2b6ffe738cc740797779e89800700a4d4141d8ab75eb4dca135978a3"),
  Gx: BigInt("0x216936d3cd6e53fec0a4e231fdd6dc5c692cc7609525a7b2c9562d608f25d51a"),
  Gy: BigInt("0x6666666666666666666666666666666666666666666666666666666666666658")
}))();
function ed25519_pow_2_252_3(x) {
  const _10n = BigInt(10), _20n = BigInt(20), _40n = BigInt(40), _80n = BigInt(80);
  const P = ed25519_CURVE_p;
  const x2 = x * x % P;
  const b2 = x2 * x % P;
  const b4 = pow2(b2, _2n4, P) * b2 % P;
  const b5 = pow2(b4, _1n6, P) * x % P;
  const b10 = pow2(b5, _5n2, P) * b5 % P;
  const b20 = pow2(b10, _10n, P) * b10 % P;
  const b40 = pow2(b20, _20n, P) * b20 % P;
  const b80 = pow2(b40, _40n, P) * b40 % P;
  const b160 = pow2(b80, _80n, P) * b80 % P;
  const b240 = pow2(b160, _80n, P) * b80 % P;
  const b250 = pow2(b240, _10n, P) * b10 % P;
  const pow_p_5_8 = pow2(b250, _2n4, P) * x % P;
  return { pow_p_5_8, b2 };
}
function adjustScalarBytes(bytes) {
  bytes[0] &= 248;
  bytes[31] &= 127;
  bytes[31] |= 64;
  return bytes;
}
var ED25519_SQRT_M1 = /* @__PURE__ */ BigInt("19681161376707505956807079304988542015446066515923890162744021073123829784752");
function uvRatio(u, v) {
  const P = ed25519_CURVE_p;
  const v3 = mod(v * v * v, P);
  const v7 = mod(v3 * v3 * v, P);
  const pow3 = ed25519_pow_2_252_3(u * v7).pow_p_5_8;
  let x = mod(u * v3 * pow3, P);
  const vx2 = mod(v * x * x, P);
  const root1 = x;
  const root2 = mod(x * ED25519_SQRT_M1, P);
  const useRoot1 = vx2 === u;
  const useRoot2 = vx2 === mod(-u, P);
  const noRoot = vx2 === mod(-u * ED25519_SQRT_M1, P);
  if (useRoot1)
    x = root1;
  if (useRoot2 || noRoot)
    x = root2;
  if (isNegativeLE(x, P))
    x = mod(-x, P);
  return { isValid: useRoot1 || useRoot2, value: x };
}
var ed25519_Point = /* @__PURE__ */ edwards(ed25519_CURVE, { uvRatio });
var x25519 = /* @__PURE__ */ (() => {
  const P = ed25519_CURVE_p;
  const powPminus2 = (x) => {
    const { pow_p_5_8, b2 } = ed25519_pow_2_252_3(x);
    return mod(pow2(pow_p_5_8, _3n2, P) * b2, P);
  };
  return montgomery({
    P,
    type: "x25519",
    powPminus2,
    adjustScalarBytes,
    // ~3x faster fixed-base: [k]B on the birationally-equivalent Edwards curve using cached
    // base tables, mapped back via u = (1+y)/(1-y) = (Z+Y)/(Z-Y) with one Fermat inversion.
    // Same construction as libsodium's crypto_scalarmult_curve25519_base.
    scalarMultBase: (k) => {
      const kn = mod(k, ed25519_Point.Fn.ORDER);
      if (kn === _0n6)
        return _0n6;
      const p = ed25519_Point.BASE.multiply(kn);
      return mod((p.Z + p.Y) * powPminus2(mod(p.Z - p.Y, P)), P);
    }
  });
})();

// dsh/keys.ts
function generateKeyPair(privateKeyBytes) {
  const privateKey = privateKeyBytes ?? randomBytes3(32);
  if (privateKey.byteLength !== 32) throw new Error("X25519 private keys must be 32 bytes");
  return {
    privateKey: toBase64Url(privateKey),
    publicKey: toBase64Url(x25519.getPublicKey(privateKey))
  };
}
function toBase64Url(bytes) {
  return ctx.codec.base64UrlEncode(Array.from(bytes));
}
function fromBase64Url(value) {
  return Uint8Array.from(ctx.codec.base64UrlDecode(value));
}
function identityFingerprint(publicKey) {
  const digest = ctx.crypto.sha256Bytes(Array.from(fromBase64Url(publicKey)));
  let out = "";
  for (let i = 0; i < 6; i += 1) out += digest[i].toString(16).padStart(2, "0");
  return out.toUpperCase();
}
function randomBytes3(length) {
  return Uint8Array.from(ctx.crypto.randomBytes(length));
}
function utf8(text) {
  return Uint8Array.from(ctx.codec.utf8Encode(text));
}
function fromUtf8(bytes) {
  return ctx.codec.utf8Decode(Array.from(bytes));
}

// node_modules/@lukeburns/clatterjs/dist/constants.js
var MAX_KEY_LEN = 32;
var MAX_TAG_LEN = 16;
var MAX_MESSAGE_LEN = 65535;
var PSK_LEN = 32;
var MAX_PSKS = 4;
var MAX_TOKENS_PER_HS_MESSAGE = 8;
var MAX_HS_MESSAGES_PER_ROLE = 8;
var HYBRID_DUAL_LAYER = new TextEncoder().encode("clatter.hybrid_dual_layer.outer");

// node_modules/@lukeburns/clatterjs/dist/errors.js
var CipherError = class extends Error {
  constructor(code, message) {
    super(message ?? code);
    __publicField(this, "code");
    this.code = code;
    this.name = "CipherError";
  }
};
var HandshakeError = class extends Error {
  constructor(code, message, causeErr) {
    super(message ?? code);
    __publicField(this, "code");
    __publicField(this, "causeErr");
    this.code = code;
    this.causeErr = causeErr;
    this.name = "HandshakeError";
  }
};
var PatternError = class extends Error {
  constructor(kind, message) {
    super(message ?? kind);
    __publicField(this, "kind");
    this.kind = kind;
    this.name = "PatternError";
  }
};
var TransportError = class extends Error {
  constructor(code, message) {
    super(message ?? code);
    __publicField(this, "code");
    this.code = code;
    this.name = "TransportError";
  }
};

// node_modules/@lukeburns/clatterjs/dist/handshakePattern.js
function checkMsgLens(initiator, responder) {
  for (const g of initiator) {
    if (g.length > MAX_TOKENS_PER_HS_MESSAGE) {
      throw new Error("Too many tokens in one handshake message");
    }
  }
  for (const g of responder) {
    if (g.length > MAX_TOKENS_PER_HS_MESSAGE) {
      throw new Error("Too many tokens in one handshake message");
    }
  }
  if (initiator.length > MAX_HS_MESSAGES_PER_ROLE || responder.length > MAX_HS_MESSAGES_PER_ROLE) {
    throw new Error("Too many handshake messages per role");
  }
}
function hasKemTokens(m) {
  const k = (tok) => tok === 6 || tok === 7;
  return m.initiator.some((g) => g.some(k)) || m.responder.some((g) => g.some(k));
}
function hasDhTokens(m) {
  const k = (tok) => tok === 2 || tok === 3 || tok === 4 || tok === 5;
  return m.initiator.some((g) => g.some(k)) || m.responder.some((g) => g.some(k));
}
function messagePatternHasPsk(m) {
  return m.initiator.some((g) => g.includes(
    8
    /* Token.Psk */
  )) || m.responder.some((g) => g.includes(
    8
    /* Token.Psk */
  ));
}
function validatePqTokenOrderRule(messages) {
  for (const message of messages) {
    let skemSeen = false;
    let publicKeySeen = false;
    for (const token of message) {
      if (token === 6) {
        if (publicKeySeen)
          throw new PatternError("PqTokenOrderViolation");
        if (skemSeen)
          throw new PatternError("PqTokenOrderViolation");
      } else if (token === 7) {
        skemSeen = true;
        if (publicKeySeen)
          throw new PatternError("PqTokenOrderViolation");
      } else if (token === 0 || token === 1) {
        publicKeySeen = true;
      }
    }
  }
}
function validatePskRule(messages) {
  let pskSent = false;
  for (const message of messages) {
    for (const token of message) {
      if (token === 8) {
        pskSent = true;
      } else if (token === 0 || token === 6) {
        return;
      } else if (token === 7) {
        if (pskSent)
          throw new PatternError("PskValidityViolation");
        return;
      } else if (token === 1) {
        if (pskSent)
          throw new PatternError("PskValidityViolation");
      }
    }
  }
}
var HandshakePattern = class _HandshakePattern {
  constructor(name, preInitiator, preResponder, initiator, responder) {
    __publicField(this, "name");
    __publicField(this, "hasPskFlag");
    __publicField(this, "hsType");
    __publicField(this, "preInitiator");
    __publicField(this, "preResponder");
    __publicField(this, "messagePattern");
    this.name = name;
    checkMsgLens(initiator, responder);
    this.preInitiator = [...preInitiator];
    this.preResponder = [...preResponder];
    this.messagePattern = {
      initiator: initiator.map((a) => [...a]),
      responder: responder.map((a) => [...a])
    };
    const hasKem = hasKemTokens(this.messagePattern);
    const hasDh = hasDhTokens(this.messagePattern);
    if (hasKem && hasDh) {
      this.hsType = 2;
    } else if (hasKem) {
      this.hsType = 1;
    } else if (hasDh) {
      this.hsType = 0;
    } else {
      throw new Error("Invalid handshake pattern");
    }
    this.hasPskFlag = messagePatternHasPsk(this.messagePattern);
    if (this.hasPskFlag) {
      validatePskRule(this.messagePattern.initiator);
      validatePskRule(this.messagePattern.responder);
    }
    if (hasKem) {
      validatePqTokenOrderRule(this.messagePattern.initiator);
      validatePqTokenOrderRule(this.messagePattern.responder);
    }
  }
  getMessagePattern() {
    return this.messagePattern;
  }
  getInitiatorPatternLen() {
    return this.messagePattern.initiator.length;
  }
  getResponderPatternLen() {
    return this.messagePattern.responder.length;
  }
  getInitiatorPreShared() {
    return this.preInitiator;
  }
  getResponderPreShared() {
    return this.preResponder;
  }
  getInitiatorPattern(i) {
    return this.messagePattern.initiator[i] ?? [];
  }
  getResponderPattern(i) {
    return this.messagePattern.responder[i] ?? [];
  }
  hasPsk() {
    return this.hasPskFlag;
  }
  getName() {
    return this.name;
  }
  isOneWay() {
    return this.messagePattern.responder.length === 0;
  }
  getType() {
    return this.hsType;
  }
  addPsks(positions, newName) {
    const initiator = this.messagePattern.initiator.map((a) => [...a]);
    const responder = this.messagePattern.responder.map((a) => [...a]);
    for (const pos of positions) {
      if (pos === 0) {
        (initiator[0] ?? (initiator[0] = [])).unshift(
          8
          /* Token.Psk */
        );
      } else if (pos % 2 === 0) {
        const r = pos / 2 - 1;
        (responder[r] ?? (responder[r] = [])).push(
          8
          /* Token.Psk */
        );
      } else {
        const ii = (pos - 1) / 2;
        (initiator[ii] ?? (initiator[ii] = [])).push(
          8
          /* Token.Psk */
        );
      }
    }
    return new _HandshakePattern(newName, this.preInitiator, this.preResponder, initiator, responder);
  }
};

// node_modules/@lukeburns/clatterjs/dist/cipherState.js
var U64_MAX = 0xfffffffffffffffn;
var CipherState = class {
  constructor(C, k, startNonce) {
    __publicField(this, "C");
    __publicField(this, "key");
    __publicField(this, "n", 0n);
    __publicField(this, "overflowed", false);
    this.C = C;
    this.key = Uint8Array.from(k);
    this.n = startNonce;
  }
  getNonce() {
    return this.n;
  }
  setNonce(n) {
    this.n = n;
  }
  /**
   * Return a copy of the underlying AEAD key. Intended for adapters that need to
   * hand the raw key material to a separate cipher implementation (e.g. the
   * classical noise-handshake `Cipher` class). Handle with care.
   */
  getKey() {
    return Uint8Array.from(this.key);
  }
  encryptWithAd(ad, plaintext, out) {
    if (this.overflowed)
      throw new CipherError("NonceOverflow");
    this.C.encrypt(this.key, this.n, ad, plaintext, out);
    this.bumpNonce();
  }
  encryptWithAdInPlace(ad, inOut, plaintextLen) {
    if (this.overflowed)
      throw new CipherError("NonceOverflow");
    const len = this.C.encryptInPlace(this.key, this.n, ad, inOut, plaintextLen);
    this.bumpNonce();
    return len;
  }
  decryptWithAd(ad, ciphertext, out) {
    if (this.overflowed)
      throw new CipherError("NonceOverflow");
    this.C.decrypt(this.key, this.n, ad, ciphertext, out);
    this.bumpNonce();
  }
  decryptWithAdInPlace(ad, inOut, ciphertextLen) {
    if (this.overflowed)
      throw new CipherError("NonceOverflow");
    const len = this.C.decryptInPlace(this.key, this.n, ad, inOut, ciphertextLen);
    this.bumpNonce();
    return len;
  }
  rekey() {
    this.key.set(this.C.rekey(this.key));
  }
  bumpNonce() {
    if (this.n === U64_MAX) {
      this.overflowed = true;
      return;
    }
    this.n += 1n;
  }
};
var CipherStates = class {
  constructor(initiatorToResponder, responderToInitiator) {
    __publicField(this, "initiatorToResponder");
    __publicField(this, "responderToInitiator");
    this.initiatorToResponder = initiatorToResponder;
    this.responderToInitiator = responderToInitiator;
  }
};

// node_modules/@lukeburns/clatterjs/dist/symmetricState.js
function deriveCipherKey(temp, C) {
  return temp.slice(0, C.keyLen);
}
var SymmetricState = class {
  constructor(C, H, noisePatternName) {
    __publicField(this, "C");
    __publicField(this, "H");
    __publicField(this, "cipherstate");
    __publicField(this, "h");
    __publicField(this, "ck");
    this.C = C;
    this.H = H;
    const nb = new TextEncoder().encode(noisePatternName);
    this.h = H.newOutput();
    this.ck = H.newOutput();
    this.h.fill(0);
    if (nb.length <= H.hashLen) {
      this.h.set(nb, 0);
    } else {
      this.h.set(H.hash(nb).subarray(0, H.hashLen));
    }
    this.ck.set(this.h);
  }
  mixHash(data) {
    this.h.set(this.H.hash(concat(this.h, data)).subarray(0, this.H.hashLen));
  }
  mixKey(inputKeyMaterial) {
    const [ck, temp] = this.H.hkdf(this.ck, inputKeyMaterial);
    this.ck.set(ck.subarray(0, this.H.hashLen));
    const k = deriveCipherKey(temp, this.C);
    this.cipherstate = new CipherState(this.C, k, 0n);
  }
  mixKeyAndHash(inputKeyMaterial) {
    const [ck, tempH, tempK] = this.H.hkdf3(this.ck, inputKeyMaterial);
    this.ck.set(ck.subarray(0, this.H.hashLen));
    this.mixHash(tempH);
    const k = deriveCipherKey(tempK, this.C);
    this.cipherstate = new CipherState(this.C, k, 0n);
  }
  encryptAndHash(plaintext, out) {
    if (this.cipherstate) {
      this.cipherstate.encryptWithAd(this.h, plaintext, out);
    } else {
      out.set(plaintext);
    }
    this.mixHash(out);
  }
  decryptAndHash(data, out) {
    if (this.cipherstate) {
      this.cipherstate.decryptWithAd(this.h, data, out);
    } else {
      out.set(data);
    }
    this.mixHash(data);
  }
  split() {
    if (!this.hasKey()) {
      throw new CipherError("MissingKeyMaterial");
    }
    const [t1, t2] = this.H.hkdf(this.ck, new Uint8Array(0));
    const k1 = deriveCipherKey(t1, this.C);
    const k2 = deriveCipherKey(t2, this.C);
    return new CipherStates(new CipherState(this.C, k1, 0n), new CipherState(this.C, k2, 0n));
  }
  getHash() {
    return Uint8Array.from(this.h);
  }
  getChainingKey() {
    return Uint8Array.from(this.ck);
  }
  hasKey() {
    return this.cipherstate !== void 0;
  }
  zeroize() {
    this.h.fill(0);
    this.ck.fill(0);
    this.cipherstate = void 0;
  }
};
function concat(a, b) {
  const o = new Uint8Array(a.length + b.length);
  o.set(a, 0);
  o.set(b, a.length);
  return o;
}

// node_modules/@lukeburns/clatterjs/dist/transportState.js
function mapCipher(e) {
  if (e instanceof TransportError)
    return e;
  if (e instanceof CipherError) {
    return new TransportError("Cipher", e.message);
  }
  throw e;
}
var TransportState = class {
  constructor(p) {
    __publicField(this, "pattern");
    __publicField(this, "cipherStates");
    __publicField(this, "h");
    __publicField(this, "initiator");
    __publicField(this, "tagLen");
    this.pattern = p.pattern;
    this.cipherStates = p.cipherStates;
    this.h = p.handshakeHash;
    this.initiator = p.initiator;
    this.tagLen = p.tagLen;
  }
  getHandshakeHash() {
    return this.h;
  }
  /** clatter: `get_handshake_hash` */
  getHandshakeHashValue() {
    return this.h;
  }
  send(msg, buf) {
    const t = this.tagLen;
    const n = msg.length + t;
    if (n > MAX_MESSAGE_LEN) {
      throw new Error("Maximum Noise message length exceeded");
    }
    if (buf.length < n) {
      throw new TransportError("BufferTooSmall");
    }
    if (this.pattern.isOneWay() && !this.initiator) {
      throw new TransportError("OneWayViolation");
    }
    const c = this.outCipher();
    try {
      c.encryptWithAd(new Uint8Array(0), msg, buf.subarray(0, n));
    } catch (e) {
      throw mapCipher(e);
    }
    return n;
  }
  sendInPlace(msg, msgLen) {
    const t = this.tagLen;
    const n = msgLen + t;
    if (n > MAX_MESSAGE_LEN) {
      throw new Error("Maximum Noise message length exceeded");
    }
    if (msg.length < n) {
      throw new TransportError("BufferTooSmall");
    }
    if (this.pattern.isOneWay() && !this.initiator) {
      throw new TransportError("OneWayViolation");
    }
    const c = this.outCipher();
    try {
      return c.encryptWithAdInPlace(new Uint8Array(0), msg, msgLen);
    } catch (e) {
      throw mapCipher(e);
    }
  }
  sendVec(msg) {
    const t = this.tagLen;
    const out = new Uint8Array(msg.length + t);
    this.send(msg, out);
    return out;
  }
  receive(msg, out) {
    const t = this.tagLen;
    if (msg.length < t) {
      throw new TransportError("TooShort");
    }
    if (msg.length > MAX_MESSAGE_LEN) {
      throw new Error("Maximum Noise message length exceeded");
    }
    if (this.pattern.isOneWay() && this.initiator) {
      throw new TransportError("OneWayViolation");
    }
    const c = this.inCipher();
    const pLen = msg.length - t;
    if (out.length < pLen) {
      throw new TransportError("BufferTooSmall");
    }
    try {
      c.decryptWithAd(new Uint8Array(0), msg, out.subarray(0, pLen));
    } catch (e) {
      throw mapCipher(e);
    }
    return pLen;
  }
  receiveInPlace(msg, msgLen) {
    const t = this.tagLen;
    if (msgLen < t) {
      throw new TransportError("TooShort");
    }
    if (msgLen > MAX_MESSAGE_LEN) {
      throw new Error("Maximum Noise message length exceeded");
    }
    if (msgLen > msg.length) {
      throw new TransportError("BufferTooSmall");
    }
    if (this.pattern.isOneWay() && this.initiator) {
      throw new TransportError("OneWayViolation");
    }
    const c = this.inCipher();
    try {
      return c.decryptWithAdInPlace(new Uint8Array(0), msg, msgLen);
    } catch (e) {
      throw mapCipher(e);
    }
  }
  receiveVec(msg) {
    const t = this.tagLen;
    const out = new Uint8Array(msg.length - t);
    this.receive(msg, out);
    return out;
  }
  sendingNonce() {
    return this.outCipher().getNonce();
  }
  receivingNonce() {
    return this.inCipher().getNonce();
  }
  setReceivingNonce(n) {
    this.inCipher().setNonce(n);
  }
  rekeySender() {
    try {
      this.outCipher().rekey();
    } catch (e) {
      throw mapCipher(e);
    }
  }
  rekeyReceiver() {
    try {
      this.inCipher().rekey();
    } catch (e) {
      throw mapCipher(e);
    }
  }
  /**
   * clatter: `TransportState::take` — returns the cipher states (same session keys; transport
   * methods remain usable, matching Rust move semantics in spirit).
   */
  takeCiphers() {
    return this.cipherStates;
  }
  outCipher() {
    return this.initiator ? this.cipherStates.initiatorToResponder : this.cipherStates.responderToInitiator;
  }
  inCipher() {
    return this.initiator ? this.cipherStates.responderToInitiator : this.cipherStates.initiatorToResponder;
  }
};

// node_modules/@lukeburns/clatterjs/dist/dhX25519.js
var PK = 32;
var X25519_NAME = "25519";
function x25519Keygen(rng) {
  const sk = rng(32);
  return { secretKey: sk, publicKey: x25519.getPublicKey(sk) };
}
function x25519Dh(ourSec, theirPub) {
  return x25519.getSharedSecret(ourSec, theirPub);
}
function dhPubKeyLen() {
  return PK;
}

// node_modules/@lukeburns/clatterjs/dist/protocolNames.js
function nqProtocolName(patternName, cipher, hash) {
  return `Noise_${patternName}_${X25519_NAME}_${cipher.name}_${hash.name}`;
}

// node_modules/@lukeburns/clatterjs/dist/nqHandshake.js
var PK2 = () => dhPubKeyLen();
var NqHandshake = class {
  constructor(pattern, config) {
    __publicField(this, "pattern");
    __publicField(this, "config");
    __publicField(this, "symmetric");
    __publicField(this, "s");
    __publicField(this, "e");
    __publicField(this, "rs");
    __publicField(this, "re");
    __publicField(this, "status");
    __publicField(this, "initiatorPatternIndex", 0);
    __publicField(this, "responderPatternIndex", 0);
    __publicField(this, "psks", []);
    __publicField(this, "pskApplied", false);
    __publicField(this, "ownRandomnessApplied", false);
    this.pattern = pattern;
    this.config = config;
    if (pattern.getType() !== 0) {
      throw new HandshakeError("InvalidPattern");
    }
    const name = nqProtocolName(pattern.getName(), config.cipher, config.hash);
    this.symmetric = new SymmetricState(config.cipher, config.hash, name);
    this.symmetric.mixHash(config.prologue);
    const I = config.initiator;
    for (const t of pattern.getInitiatorPreShared()) {
      if (t === 1) {
        this.symmetric.mixHash((I ? config.s?.publicKey : config.rs) ?? raiseMissing());
      } else {
        throw new Error("Invalid pre-shared token in pattern");
      }
    }
    for (const t of pattern.getResponderPreShared()) {
      if (t === 1) {
        this.symmetric.mixHash((I ? config.rs : config.s?.publicKey) ?? raiseMissing());
      } else if (t === 0) {
        if (I) {
          const reB = config.re ?? raiseMissing();
          this.symmetric.mixHash(reB);
          if (pattern.hasPsk()) {
            this.symmetric.mixKey(reB);
          }
        } else {
          const eB = config.e?.publicKey ?? raiseMissing();
          this.symmetric.mixHash(eB);
          if (pattern.hasPsk()) {
            this.symmetric.mixKey(eB);
          }
        }
      } else {
        throw new Error("Invalid pre-shared token in pattern");
      }
    }
    this.s = config.s;
    this.e = config.e;
    this.rs = config.rs;
    this.re = config.re;
    this.status = config.initiator ? 0 : 1;
  }
  getPattern() {
    return this.pattern;
  }
  isInitiator() {
    return this.config.initiator;
  }
  isWriteTurn() {
    return this.status === 0;
  }
  isFinished() {
    return this.status === 2;
  }
  mixHash(data) {
    this.symmetric.mixHash(data);
  }
  mixKeyAndHash(data) {
    this.symmetric.mixKeyAndHash(data);
  }
  pushPsk(psk) {
    if (psk.length !== PSK_LEN) {
      throw new Error(`PSK must be ${PSK_LEN} bytes`);
    }
    if (this.psks.length >= MAX_PSKS) {
      throw new Error("PSK queue full");
    }
    this.psks.push(Uint8Array.from(psk));
  }
  getName() {
    return nqProtocolName(this.pattern.getName(), this.config.cipher, this.config.hash);
  }
  getHash() {
    return this.symmetric.getHash();
  }
  getRemoteStatic() {
    return this.rs ? Uint8Array.from(this.rs) : void 0;
  }
  getRemoteEphemeral() {
    return this.re ? Uint8Array.from(this.re) : void 0;
  }
  getCiphers() {
    try {
      return this.symmetric.split();
    } catch (e) {
      if (e instanceof CipherError) {
        throw new HandshakeError("Cipher", void 0, e);
      }
      throw e;
    }
  }
  finalize() {
    if (!this.isFinished()) {
      throw new HandshakeError("InvalidState");
    }
    return new TransportState({
      pattern: this.pattern,
      cipherStates: this.getCiphers(),
      handshakeHash: this.getHash(),
      initiator: this.isInitiator(),
      tagLen: this.config.cipher.tagLen
    });
  }
  nextTokensForCurrentTurn() {
    const a = this.config.initiator;
    const st = this.status;
    if (st === 3 || st === 2) {
      throw new HandshakeError("InvalidState");
    }
    if (a && st === 0 || !a && st === 1) {
      return this.pattern.getInitiatorPattern(this.initiatorPatternIndex) ?? raiseInv();
    }
    return this.pattern.getResponderPattern(this.responderPatternIndex) ?? raiseInv();
  }
  getNextMessageOverhead() {
    const message = this.nextTokensForCurrentTurn();
    let overhead = 0;
    let hasKey = this.symmetric.hasKey();
    const hasPsk = this.pattern.hasPsk();
    const { cipher: C } = this.config;
    const pkl = PK2();
    for (const token of message) {
      if (token === 0) {
        overhead += pkl;
        if (hasPsk) {
          hasKey = true;
        }
      } else if (token === 1) {
        overhead += pkl;
        if (hasKey) {
          overhead += C.tagLen;
        }
      } else if (token === 2 || token === 3 || token === 4 || token === 5) {
        hasKey = true;
      } else if (token === 8) {
      } else {
        throw new Error("Incompatible pattern");
      }
    }
    if (hasKey) {
      overhead += C.tagLen;
    }
    return overhead;
  }
  writeMessage(payload, out) {
    if (this.status === 3) {
      throw new HandshakeError("ErrorState");
    }
    if (!this.isWriteTurn()) {
      throw new HandshakeError("InvalidState");
    }
    const oh = this.getNextMessageOverhead();
    const outLen = payload.length + oh;
    if (outLen > MAX_MESSAGE_LEN) {
      throw new Error("Maximum Noise message length exceeded");
    }
    if (out.length < outLen) {
      throw new HandshakeError("BufferTooSmall");
    }
    try {
      return this.writeMessageImpl(payload, out, outLen);
    } catch (e) {
      this.setError();
      throw e;
    }
  }
  readMessage(message, out) {
    if (message.length > MAX_MESSAGE_LEN) {
      throw new Error("Maximum Noise message length exceeded");
    }
    if (this.status === 3) {
      throw new HandshakeError("ErrorState");
    }
    if (this.isWriteTurn()) {
      throw new HandshakeError("InvalidState");
    }
    const oh = this.getNextMessageOverhead();
    if (message.length < oh) {
      throw new HandshakeError("InvalidMessage");
    }
    const outLen = message.length - oh;
    if (out.length < outLen) {
      throw new HandshakeError("BufferTooSmall");
    }
    try {
      return this.readMessageImpl(message, out, outLen);
    } catch (e) {
      this.setError();
      throw e;
    }
  }
  setError() {
    this.status = 3;
    this.symmetric.zeroize();
  }
  pskValidityCheck() {
    if (this.pskApplied && !this.ownRandomnessApplied) {
      throw new HandshakeError("Pattern", "PskValidityViolation", new PatternError("PskValidityViolation"));
    }
  }
  takePsk() {
    const p = this.psks.shift();
    if (!p) {
      throw new HandshakeError("PskMissing");
    }
    this.pskApplied = true;
    return p;
  }
  mapDh(t) {
    const needS = (kp) => {
      if (!kp) {
        throw new HandshakeError("MissingMaterial");
      }
      return kp;
    };
    const needB = (b) => {
      if (!b) {
        throw new HandshakeError("MissingMaterial");
      }
      return b;
    };
    let ss;
    try {
      if (t === 2) {
        ss = x25519Dh(needS(this.e).secretKey, needB(this.re));
      } else if (t === 3) {
        if (this.isInitiator()) {
          ss = x25519Dh(needS(this.e).secretKey, needB(this.rs));
        } else {
          ss = x25519Dh(needS(this.s).secretKey, needB(this.re));
        }
      } else if (t === 4) {
        if (this.isInitiator()) {
          ss = x25519Dh(needS(this.s).secretKey, needB(this.re));
        } else {
          ss = x25519Dh(needS(this.e).secretKey, needB(this.rs));
        }
      } else if (t === 5) {
        ss = x25519Dh(needS(this.s).secretKey, needB(this.rs));
      } else {
        throw new Error("mapDh");
      }
    } catch (e) {
      if (e instanceof HandshakeError) {
        throw e;
      }
      throw new HandshakeError("Dh", e instanceof Error ? e.message : String(e));
    }
    if (ss.length !== 32) {
      throw new HandshakeError("Dh", "Invalid shared secret length");
    }
    return ss;
  }
  updateHsStatus() {
    if (this.initiatorPatternIndex === this.pattern.getInitiatorPatternLen() && this.responderPatternIndex === this.pattern.getResponderPatternLen()) {
      this.status = 2;
    } else if (this.status === 1) {
      this.status = 0;
    } else {
      this.status = 1;
    }
  }
  writeMessageImpl(payload, out, outLen) {
    const message = this.isInitiator() ? this.pattern.getInitiatorPattern(this.initiatorPatternIndex++) : this.pattern.getResponderPattern(this.responderPatternIndex++);
    if (!message) {
      throw new HandshakeError("InvalidState");
    }
    const { cipher: C, rng } = this.config;
    const pkl = PK2();
    let cur = 0;
    for (const token of message) {
      if (token === 0) {
        if (!this.e) {
          this.e = x25519Keygen(rng);
        }
        const ePub = this.e.publicKey;
        this.symmetric.mixHash(ePub);
        if (this.pattern.hasPsk()) {
          this.symmetric.mixKey(ePub);
        }
        out.set(ePub, cur);
        cur += pkl;
        this.ownRandomnessApplied = true;
      } else if (token === 1) {
        if (!this.s) {
          throw new HandshakeError("MissingMaterial");
        }
        this.pskValidityCheck();
        const len = this.symmetric.hasKey() ? pkl + C.tagLen : pkl;
        this.symmetric.encryptAndHash(this.s.publicKey, out.subarray(cur, cur + len));
        cur += len;
      } else if (token === 8) {
        this.symmetric.mixKeyAndHash(this.takePsk());
      } else if (token === 2 || token === 3 || token === 4 || token === 5) {
        this.symmetric.mixKey(this.mapDh(token));
      } else {
        throw new Error("Incompatible pattern");
      }
    }
    if (payload.length > 0 && this.pskApplied && !this.ownRandomnessApplied) {
      throw new HandshakeError("Pattern", "PskValidityViolation", new PatternError("PskValidityViolation"));
    }
    this.symmetric.encryptAndHash(payload, out.subarray(cur, outLen));
    this.updateHsStatus();
    return outLen;
  }
  readMessageImpl(message, out, outLen) {
    let rest = message;
    const take = (n) => {
      if (rest.length < n) {
        throw new HandshakeError("InvalidMessage");
      }
      const s = rest.subarray(0, n);
      rest = rest.subarray(n);
      return s;
    };
    const pat = this.isInitiator() ? this.pattern.getResponderPattern(this.responderPatternIndex++) : this.pattern.getInitiatorPattern(this.initiatorPatternIndex++);
    if (!pat) {
      throw new HandshakeError("InvalidState");
    }
    const { cipher: C } = this.config;
    const pkl = PK2();
    for (const token of pat) {
      if (token === 0) {
        const re = Uint8Array.from(take(pkl));
        this.symmetric.mixHash(re);
        if (this.pattern.hasPsk()) {
          this.symmetric.mixKey(re);
        }
        this.re = re;
      } else if (token === 1) {
        const len = this.symmetric.hasKey() ? pkl + C.tagLen : pkl;
        const bu = new Uint8Array(pkl);
        this.symmetric.decryptAndHash(take(len), bu);
        this.rs = bu;
      } else if (token === 8) {
        this.symmetric.mixKeyAndHash(this.takePsk());
      } else if (token === 2 || token === 3 || token === 4 || token === 5) {
        this.symmetric.mixKey(this.mapDh(token));
      } else {
        throw new Error("Incompatible pattern");
      }
    }
    this.symmetric.decryptAndHash(rest, out.subarray(0, outLen));
    this.updateHsStatus();
    return outLen;
  }
};
function raiseMissing() {
  throw new HandshakeError("MissingMaterial");
}
function raiseInv() {
  throw new HandshakeError("InvalidState");
}

// node_modules/@noble/hashes/_blake.js
var BSIGMA = /* @__PURE__ */ Uint8Array.from([
  0,
  1,
  2,
  3,
  4,
  5,
  6,
  7,
  8,
  9,
  10,
  11,
  12,
  13,
  14,
  15,
  14,
  10,
  4,
  8,
  9,
  15,
  13,
  6,
  1,
  12,
  0,
  2,
  11,
  7,
  5,
  3,
  11,
  8,
  12,
  0,
  5,
  2,
  15,
  13,
  10,
  14,
  3,
  6,
  7,
  1,
  9,
  4,
  7,
  9,
  3,
  1,
  13,
  12,
  11,
  14,
  2,
  6,
  5,
  10,
  4,
  0,
  15,
  8,
  9,
  0,
  5,
  7,
  2,
  4,
  10,
  15,
  14,
  1,
  11,
  12,
  6,
  8,
  3,
  13,
  2,
  12,
  6,
  10,
  0,
  11,
  8,
  3,
  4,
  13,
  7,
  5,
  15,
  14,
  1,
  9,
  12,
  5,
  1,
  15,
  14,
  13,
  4,
  10,
  0,
  7,
  6,
  3,
  9,
  2,
  8,
  11,
  13,
  11,
  7,
  14,
  12,
  1,
  3,
  9,
  5,
  0,
  15,
  4,
  8,
  6,
  2,
  10,
  6,
  15,
  14,
  9,
  11,
  3,
  0,
  8,
  12,
  2,
  13,
  7,
  1,
  4,
  10,
  5,
  10,
  2,
  8,
  4,
  7,
  6,
  1,
  5,
  15,
  11,
  9,
  14,
  3,
  12,
  13,
  0,
  0,
  1,
  2,
  3,
  4,
  5,
  6,
  7,
  8,
  9,
  10,
  11,
  12,
  13,
  14,
  15,
  14,
  10,
  4,
  8,
  9,
  15,
  13,
  6,
  1,
  12,
  0,
  2,
  11,
  7,
  5,
  3,
  // Blake1, unused in others
  11,
  8,
  12,
  0,
  5,
  2,
  15,
  13,
  10,
  14,
  3,
  6,
  7,
  1,
  9,
  4,
  7,
  9,
  3,
  1,
  13,
  12,
  11,
  14,
  2,
  6,
  5,
  10,
  4,
  0,
  15,
  8,
  9,
  0,
  5,
  7,
  2,
  4,
  10,
  15,
  14,
  1,
  11,
  12,
  6,
  8,
  3,
  13,
  2,
  12,
  6,
  10,
  0,
  11,
  8,
  3,
  4,
  13,
  7,
  5,
  15,
  14,
  1,
  9
]);
function G1s(a, b, c, d, x) {
  a = a + b + x | 0;
  d = rotr(d ^ a, 16);
  c = c + d | 0;
  b = rotr(b ^ c, 12);
  return { a, b, c, d };
}
function G2s(a, b, c, d, x) {
  a = a + b + x | 0;
  d = rotr(d ^ a, 8);
  c = c + d | 0;
  b = rotr(b ^ c, 7);
  return { a, b, c, d };
}

// node_modules/@noble/hashes/blake2.js
var B2B_IV = /* @__PURE__ */ Uint32Array.from([
  4089235720,
  1779033703,
  2227873595,
  3144134277,
  4271175723,
  1013904242,
  1595750129,
  2773480762,
  2917565137,
  1359893119,
  725511199,
  2600822924,
  4215389547,
  528734635,
  327033209,
  1541459225
]);
var BBUF = /* @__PURE__ */ new Uint32Array(32);
function G1b(a, b, c, d, msg, x) {
  const Xl = msg[x], Xh = msg[x + 1];
  let Al = BBUF[2 * a], Ah = BBUF[2 * a + 1];
  let Bl = BBUF[2 * b], Bh = BBUF[2 * b + 1];
  let Cl = BBUF[2 * c], Ch = BBUF[2 * c + 1];
  let Dl = BBUF[2 * d], Dh = BBUF[2 * d + 1];
  const ll = add3L(Al, Bl, Xl);
  Ah = add3H(ll, Ah, Bh, Xh);
  Al = ll | 0;
  let xh = Dh ^ Ah, xl = Dl ^ Al;
  Dh = rotr32H(xh, xl);
  Dl = rotr32L(xh, xl);
  ({ h: Ch, l: Cl } = add(Ch, Cl, Dh, Dl));
  xh = Bh ^ Ch;
  xl = Bl ^ Cl;
  Bh = rotrSH(xh, xl, 24);
  Bl = rotrSL(xh, xl, 24);
  BBUF[2 * a] = Al;
  BBUF[2 * a + 1] = Ah;
  BBUF[2 * b] = Bl;
  BBUF[2 * b + 1] = Bh;
  BBUF[2 * c] = Cl;
  BBUF[2 * c + 1] = Ch;
  BBUF[2 * d] = Dl;
  BBUF[2 * d + 1] = Dh;
}
function G2b(a, b, c, d, msg, x) {
  const Xl = msg[x], Xh = msg[x + 1];
  let Al = BBUF[2 * a], Ah = BBUF[2 * a + 1];
  let Bl = BBUF[2 * b], Bh = BBUF[2 * b + 1];
  let Cl = BBUF[2 * c], Ch = BBUF[2 * c + 1];
  let Dl = BBUF[2 * d], Dh = BBUF[2 * d + 1];
  const ll = add3L(Al, Bl, Xl);
  Ah = add3H(ll, Ah, Bh, Xh);
  Al = ll | 0;
  let xh = Dh ^ Ah, xl = Dl ^ Al;
  Dh = rotrSH(xh, xl, 16);
  Dl = rotrSL(xh, xl, 16);
  ({ h: Ch, l: Cl } = add(Ch, Cl, Dh, Dl));
  xh = Bh ^ Ch;
  xl = Bl ^ Cl;
  Bh = rotrBH(xh, xl, 63);
  Bl = rotrBL(xh, xl, 63);
  BBUF[2 * a] = Al;
  BBUF[2 * a + 1] = Ah;
  BBUF[2 * b] = Bl;
  BBUF[2 * b + 1] = Bh;
  BBUF[2 * c] = Cl;
  BBUF[2 * c + 1] = Ch;
  BBUF[2 * d] = Dl;
  BBUF[2 * d + 1] = Dh;
}
function checkBlake2Opts(outputLen, opts = {}, keyLen, saltLen, persLen) {
  anumber(keyLen);
  if (outputLen <= 0 || outputLen > keyLen)
    throw new Error('"dkLen" must be 1..' + keyLen + ", got " + outputLen);
  const { key, salt, personalization } = opts;
  if (key !== void 0 && (key.length < 1 || key.length > keyLen))
    throw new Error('"key" expected to be undefined or of length=1..' + keyLen);
  if (salt !== void 0)
    abytes(salt, saltLen, "salt");
  if (personalization !== void 0)
    abytes(personalization, persLen, "personalization");
}
var _BLAKE2 = class {
  constructor(blockLen, outputLen) {
    __publicField(this, "buffer");
    __publicField(this, "buffer32");
    __publicField(this, "finished", false);
    __publicField(this, "destroyed", false);
    __publicField(this, "length", 0);
    __publicField(this, "pos", 0);
    __publicField(this, "blockLen");
    __publicField(this, "outputLen");
    __publicField(this, "canXOF", false);
    anumber(blockLen);
    anumber(outputLen);
    this.blockLen = blockLen;
    this.outputLen = outputLen;
    this.buffer = new Uint8Array(blockLen);
    this.buffer32 = u32(this.buffer);
  }
  update(data) {
    aexists(this);
    abytes(data);
    const { blockLen, buffer, buffer32 } = this;
    const len = data.length;
    const offset = data.byteOffset;
    const buf = data.buffer;
    for (let pos = 0; pos < len; ) {
      if (this.pos === blockLen) {
        swap32IfBE(buffer32);
        this.compress(buffer32, 0, false);
        swap32IfBE(buffer32);
        this.pos = 0;
      }
      const take = Math.min(blockLen - this.pos, len - pos);
      const dataOffset = offset + pos;
      if (take === blockLen && !(dataOffset % 4) && pos + take < len) {
        const data32 = new Uint32Array(buf, dataOffset, Math.floor((len - pos) / 4));
        swap32IfBE(data32);
        for (let pos32 = 0; pos + blockLen < len; pos32 += buffer32.length, pos += blockLen) {
          this.length += blockLen;
          this.compress(data32, pos32, false);
        }
        swap32IfBE(data32);
        continue;
      }
      buffer.set(pos === 0 && take === len ? data : data.subarray(pos, pos + take), this.pos);
      this.pos += take;
      this.length += take;
      pos += take;
    }
    return this;
  }
  digestInto(out) {
    aexists(this);
    aoutput(out, this);
    if (out.byteOffset & 3)
      throw new RangeError('"output" expected 4-byte aligned byteOffset, got ' + out.byteOffset);
    const { pos, buffer32 } = this;
    this.finished = true;
    this.buffer.fill(0, pos);
    swap32IfBE(buffer32);
    this.compress(buffer32, 0, true);
    swap32IfBE(buffer32);
    const state = this.get();
    const out32 = out === this.buffer ? buffer32 : u32(out);
    const full = Math.floor(this.outputLen / 4);
    for (let i = 0; i < full; i++)
      out32[i] = swap8IfBE(state[i]);
    const tail = this.outputLen % 4;
    if (!tail)
      return;
    const off = full * 4;
    const word = state[full];
    for (let i = 0; i < tail; i++)
      out[off + i] = word >>> 8 * i;
  }
  digest() {
    const { buffer, outputLen } = this;
    this.digestInto(buffer);
    const res = buffer.slice(0, outputLen);
    this.destroy();
    return res;
  }
  _cloneInto(to) {
    const { buffer, length, finished, destroyed, outputLen, pos } = this;
    to || (to = new this.constructor({ dkLen: outputLen }));
    to.set(...this.get());
    to.buffer.set(buffer);
    to.destroyed = destroyed;
    to.finished = finished;
    to.length = length;
    to.pos = pos;
    to.outputLen = outputLen;
    return to;
  }
  clone() {
    return this._cloneInto();
  }
};
var _BLAKE2b = class extends _BLAKE2 {
  constructor(opts = {}) {
    opts = checkOpts({}, opts);
    const olen = opts.dkLen === void 0 ? 64 : opts.dkLen;
    super(128, olen);
    // Same IV words as SHA-512 / BLAKE2b, encoded as LE u32 low/high halves.
    __publicField(this, "v0l", B2B_IV[0] | 0);
    __publicField(this, "v0h", B2B_IV[1] | 0);
    __publicField(this, "v1l", B2B_IV[2] | 0);
    __publicField(this, "v1h", B2B_IV[3] | 0);
    __publicField(this, "v2l", B2B_IV[4] | 0);
    __publicField(this, "v2h", B2B_IV[5] | 0);
    __publicField(this, "v3l", B2B_IV[6] | 0);
    __publicField(this, "v3h", B2B_IV[7] | 0);
    __publicField(this, "v4l", B2B_IV[8] | 0);
    __publicField(this, "v4h", B2B_IV[9] | 0);
    __publicField(this, "v5l", B2B_IV[10] | 0);
    __publicField(this, "v5h", B2B_IV[11] | 0);
    __publicField(this, "v6l", B2B_IV[12] | 0);
    __publicField(this, "v6h", B2B_IV[13] | 0);
    __publicField(this, "v7l", B2B_IV[14] | 0);
    __publicField(this, "v7h", B2B_IV[15] | 0);
    checkBlake2Opts(olen, opts, 64, 16, 16);
    let { key, personalization, salt } = opts;
    let keyLength = 0;
    if (key !== void 0) {
      abytes(key, void 0, "key");
      keyLength = key.length;
    }
    this.v0l ^= this.outputLen | keyLength << 8 | 1 << 16 | 1 << 24;
    if (salt !== void 0) {
      abytes(salt, void 0, "salt");
      const slt = u32(copyBytes(salt));
      this.v4l ^= swap8IfBE(slt[0]);
      this.v4h ^= swap8IfBE(slt[1]);
      this.v5l ^= swap8IfBE(slt[2]);
      this.v5h ^= swap8IfBE(slt[3]);
    }
    if (personalization !== void 0) {
      abytes(personalization, void 0, "personalization");
      const pers = u32(copyBytes(personalization));
      this.v6l ^= swap8IfBE(pers[0]);
      this.v6h ^= swap8IfBE(pers[1]);
      this.v7l ^= swap8IfBE(pers[2]);
      this.v7h ^= swap8IfBE(pers[3]);
    }
    if (key !== void 0) {
      const tmp = new Uint8Array(this.blockLen);
      tmp.set(key);
      this.update(tmp);
      clean(tmp);
    }
  }
  // prettier-ignore
  get() {
    let { v0l, v0h, v1l, v1h, v2l, v2h, v3l, v3h, v4l, v4h, v5l, v5h, v6l, v6h, v7l, v7h } = this;
    return [v0l, v0h, v1l, v1h, v2l, v2h, v3l, v3h, v4l, v4h, v5l, v5h, v6l, v6h, v7l, v7h];
  }
  // prettier-ignore
  set(v0l, v0h, v1l, v1h, v2l, v2h, v3l, v3h, v4l, v4h, v5l, v5h, v6l, v6h, v7l, v7h) {
    this.v0l = v0l | 0;
    this.v0h = v0h | 0;
    this.v1l = v1l | 0;
    this.v1h = v1h | 0;
    this.v2l = v2l | 0;
    this.v2h = v2h | 0;
    this.v3l = v3l | 0;
    this.v3h = v3h | 0;
    this.v4l = v4l | 0;
    this.v4h = v4h | 0;
    this.v5l = v5l | 0;
    this.v5h = v5h | 0;
    this.v6l = v6l | 0;
    this.v6h = v6h | 0;
    this.v7l = v7l | 0;
    this.v7h = v7h | 0;
  }
  compress(msg, offset, isLast) {
    const { v0l, v0h, v1l, v1h, v2l, v2h, v3l, v3h, v4l, v4h, v5l, v5h, v6l, v6h, v7l, v7h } = this;
    {
      BBUF[0] = v0l;
      BBUF[1] = v0h;
      BBUF[2] = v1l;
      BBUF[3] = v1h;
      BBUF[4] = v2l;
      BBUF[5] = v2h;
      BBUF[6] = v3l;
      BBUF[7] = v3h;
      BBUF[8] = v4l;
      BBUF[9] = v4h;
      BBUF[10] = v5l;
      BBUF[11] = v5h;
      BBUF[12] = v6l;
      BBUF[13] = v6h;
      BBUF[14] = v7l;
      BBUF[15] = v7h;
    }
    BBUF.set(B2B_IV, 16);
    const l = fromNumL(this.length);
    const h = fromNumH(this.length);
    BBUF[24] = B2B_IV[8] ^ l;
    BBUF[25] = B2B_IV[9] ^ h;
    if (isLast) {
      BBUF[28] = ~BBUF[28];
      BBUF[29] = ~BBUF[29];
    }
    let j = 0;
    const s = BSIGMA;
    for (let i = 0; i < 12; i++) {
      G1b(0, 4, 8, 12, msg, offset + 2 * s[j++]);
      G2b(0, 4, 8, 12, msg, offset + 2 * s[j++]);
      G1b(1, 5, 9, 13, msg, offset + 2 * s[j++]);
      G2b(1, 5, 9, 13, msg, offset + 2 * s[j++]);
      G1b(2, 6, 10, 14, msg, offset + 2 * s[j++]);
      G2b(2, 6, 10, 14, msg, offset + 2 * s[j++]);
      G1b(3, 7, 11, 15, msg, offset + 2 * s[j++]);
      G2b(3, 7, 11, 15, msg, offset + 2 * s[j++]);
      G1b(0, 5, 10, 15, msg, offset + 2 * s[j++]);
      G2b(0, 5, 10, 15, msg, offset + 2 * s[j++]);
      G1b(1, 6, 11, 12, msg, offset + 2 * s[j++]);
      G2b(1, 6, 11, 12, msg, offset + 2 * s[j++]);
      G1b(2, 7, 8, 13, msg, offset + 2 * s[j++]);
      G2b(2, 7, 8, 13, msg, offset + 2 * s[j++]);
      G1b(3, 4, 9, 14, msg, offset + 2 * s[j++]);
      G2b(3, 4, 9, 14, msg, offset + 2 * s[j++]);
    }
    this.v0l ^= BBUF[0] ^ BBUF[16];
    this.v0h ^= BBUF[1] ^ BBUF[17];
    this.v1l ^= BBUF[2] ^ BBUF[18];
    this.v1h ^= BBUF[3] ^ BBUF[19];
    this.v2l ^= BBUF[4] ^ BBUF[20];
    this.v2h ^= BBUF[5] ^ BBUF[21];
    this.v3l ^= BBUF[6] ^ BBUF[22];
    this.v3h ^= BBUF[7] ^ BBUF[23];
    this.v4l ^= BBUF[8] ^ BBUF[24];
    this.v4h ^= BBUF[9] ^ BBUF[25];
    this.v5l ^= BBUF[10] ^ BBUF[26];
    this.v5h ^= BBUF[11] ^ BBUF[27];
    this.v6l ^= BBUF[12] ^ BBUF[28];
    this.v6h ^= BBUF[13] ^ BBUF[29];
    this.v7l ^= BBUF[14] ^ BBUF[30];
    this.v7h ^= BBUF[15] ^ BBUF[31];
    clean(BBUF);
  }
  destroy() {
    this.destroyed = true;
    clean(this.buffer32);
    this.set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }
};
var blake2b = /* @__PURE__ */ createHasher((opts) => new _BLAKE2b(opts));
function _compress(s, offset, msg, rounds, v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15) {
  let j = 0;
  for (let i = 0; i < rounds; i++) {
    ({ a: v0, b: v4, c: v8, d: v12 } = G1s(v0, v4, v8, v12, msg[offset + s[j++]]));
    ({ a: v0, b: v4, c: v8, d: v12 } = G2s(v0, v4, v8, v12, msg[offset + s[j++]]));
    ({ a: v1, b: v5, c: v9, d: v13 } = G1s(v1, v5, v9, v13, msg[offset + s[j++]]));
    ({ a: v1, b: v5, c: v9, d: v13 } = G2s(v1, v5, v9, v13, msg[offset + s[j++]]));
    ({ a: v2, b: v6, c: v10, d: v14 } = G1s(v2, v6, v10, v14, msg[offset + s[j++]]));
    ({ a: v2, b: v6, c: v10, d: v14 } = G2s(v2, v6, v10, v14, msg[offset + s[j++]]));
    ({ a: v3, b: v7, c: v11, d: v15 } = G1s(v3, v7, v11, v15, msg[offset + s[j++]]));
    ({ a: v3, b: v7, c: v11, d: v15 } = G2s(v3, v7, v11, v15, msg[offset + s[j++]]));
    ({ a: v0, b: v5, c: v10, d: v15 } = G1s(v0, v5, v10, v15, msg[offset + s[j++]]));
    ({ a: v0, b: v5, c: v10, d: v15 } = G2s(v0, v5, v10, v15, msg[offset + s[j++]]));
    ({ a: v1, b: v6, c: v11, d: v12 } = G1s(v1, v6, v11, v12, msg[offset + s[j++]]));
    ({ a: v1, b: v6, c: v11, d: v12 } = G2s(v1, v6, v11, v12, msg[offset + s[j++]]));
    ({ a: v2, b: v7, c: v8, d: v13 } = G1s(v2, v7, v8, v13, msg[offset + s[j++]]));
    ({ a: v2, b: v7, c: v8, d: v13 } = G2s(v2, v7, v8, v13, msg[offset + s[j++]]));
    ({ a: v3, b: v4, c: v9, d: v14 } = G1s(v3, v4, v9, v14, msg[offset + s[j++]]));
    ({ a: v3, b: v4, c: v9, d: v14 } = G2s(v3, v4, v9, v14, msg[offset + s[j++]]));
  }
  return { v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 };
}
var B2S_IV = /* @__PURE__ */ SHA256_IV.slice();
var _BLAKE2s = class extends _BLAKE2 {
  constructor(opts = {}) {
    opts = checkOpts({}, opts);
    const olen = opts.dkLen === void 0 ? 32 : opts.dkLen;
    super(64, olen);
    // Internal state, same as SHA-256
    __publicField(this, "v0", B2S_IV[0] | 0);
    __publicField(this, "v1", B2S_IV[1] | 0);
    __publicField(this, "v2", B2S_IV[2] | 0);
    __publicField(this, "v3", B2S_IV[3] | 0);
    __publicField(this, "v4", B2S_IV[4] | 0);
    __publicField(this, "v5", B2S_IV[5] | 0);
    __publicField(this, "v6", B2S_IV[6] | 0);
    __publicField(this, "v7", B2S_IV[7] | 0);
    checkBlake2Opts(olen, opts, 32, 8, 8);
    let { key, personalization, salt } = opts;
    let keyLength = 0;
    if (key !== void 0) {
      abytes(key, void 0, "key");
      keyLength = key.length;
    }
    this.v0 ^= this.outputLen | keyLength << 8 | 1 << 16 | 1 << 24;
    if (salt !== void 0) {
      abytes(salt, void 0, "salt");
      const slt = u32(copyBytes(salt));
      this.v4 ^= swap8IfBE(slt[0]);
      this.v5 ^= swap8IfBE(slt[1]);
    }
    if (personalization !== void 0) {
      abytes(personalization, void 0, "personalization");
      const pers = u32(copyBytes(personalization));
      this.v6 ^= swap8IfBE(pers[0]);
      this.v7 ^= swap8IfBE(pers[1]);
    }
    if (key !== void 0) {
      const tmp = new Uint8Array(this.blockLen);
      tmp.set(key);
      this.update(tmp);
      clean(tmp);
    }
  }
  get() {
    const { v0, v1, v2, v3, v4, v5, v6, v7 } = this;
    return [v0, v1, v2, v3, v4, v5, v6, v7];
  }
  // prettier-ignore
  set(v0, v1, v2, v3, v4, v5, v6, v7) {
    this.v0 = v0 | 0;
    this.v1 = v1 | 0;
    this.v2 = v2 | 0;
    this.v3 = v3 | 0;
    this.v4 = v4 | 0;
    this.v5 = v5 | 0;
    this.v6 = v6 | 0;
    this.v7 = v7 | 0;
  }
  compress(msg, offset, isLast) {
    const l = fromNumL(this.length);
    const h = fromNumH(this.length);
    const { v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 } = _compress(BSIGMA, offset, msg, 10, this.v0, this.v1, this.v2, this.v3, this.v4, this.v5, this.v6, this.v7, B2S_IV[0], B2S_IV[1], B2S_IV[2], B2S_IV[3], l ^ B2S_IV[4], h ^ B2S_IV[5], isLast ? ~B2S_IV[6] : B2S_IV[6], B2S_IV[7]);
    this.v0 ^= v0 ^ v8;
    this.v1 ^= v1 ^ v9;
    this.v2 ^= v2 ^ v10;
    this.v3 ^= v3 ^ v11;
    this.v4 ^= v4 ^ v12;
    this.v5 ^= v5 ^ v13;
    this.v6 ^= v6 ^ v14;
    this.v7 ^= v7 ^ v15;
  }
  destroy() {
    this.destroyed = true;
    clean(this.buffer32);
    this.set(0, 0, 0, 0, 0, 0, 0, 0);
  }
};
var blake2s = /* @__PURE__ */ createHasher((opts) => new _BLAKE2s(opts));

// node_modules/@noble/hashes/hmac.js
var _HMAC = class {
  constructor(hash, key) {
    __publicField(this, "oHash");
    __publicField(this, "iHash");
    __publicField(this, "blockLen");
    __publicField(this, "outputLen");
    __publicField(this, "canXOF", false);
    __publicField(this, "finished", false);
    __publicField(this, "destroyed", false);
    ahash(hash);
    abytes(key, void 0, "key");
    this.iHash = hash.create();
    if (typeof this.iHash.update !== "function")
      throw new Error("expected Hash instance");
    this.blockLen = this.iHash.blockLen;
    this.outputLen = this.iHash.outputLen;
    const blockLen = this.blockLen;
    const pad = new Uint8Array(blockLen);
    pad.set(key.length > blockLen ? hash.create().update(key).digest() : key);
    for (let i = 0; i < pad.length; i++)
      pad[i] ^= 54;
    this.iHash.update(pad);
    this.oHash = hash.create();
    for (let i = 0; i < pad.length; i++)
      pad[i] ^= 54 ^ 92;
    this.oHash.update(pad);
    clean(pad);
  }
  update(buf) {
    aexists(this);
    this.iHash.update(buf);
    return this;
  }
  digestInto(out) {
    aexists(this);
    aoutput(out, this);
    this.finished = true;
    const buf = out.subarray(0, this.outputLen);
    this.iHash.digestInto(buf);
    this.oHash.update(buf);
    this.oHash.digestInto(buf);
    this.destroy();
  }
  digest() {
    const out = new Uint8Array(this.oHash.outputLen);
    this.digestInto(out);
    return out;
  }
  _cloneInto(to) {
    to || (to = Object.create(Object.getPrototypeOf(this), {}));
    const { oHash, iHash, finished, destroyed, blockLen, outputLen, canXOF } = this;
    to = to;
    to.finished = finished;
    to.destroyed = destroyed;
    to.blockLen = blockLen;
    to.outputLen = outputLen;
    to.canXOF = canXOF;
    to.oHash = oHash._cloneInto(to.oHash);
    to.iHash = iHash._cloneInto(to.iHash);
    return to;
  }
  clone() {
    return this._cloneInto();
  }
  destroy() {
    this.destroyed = true;
    this.oHash.destroy();
    this.iHash.destroy();
  }
};
var hmac = /* @__PURE__ */ (() => {
  const hmac_ = ((hash, key, message) => new _HMAC(hash, key).update(message).digest());
  hmac_.create = (hash, key) => new _HMAC(hash, key);
  return hmac_;
})();

// node_modules/@lukeburns/clatterjs/dist/crypto/hash.js
function makeHash(name, hash, hashLen, blockLen) {
  const doHash = (data) => hash.create().update(data).digest();
  const hmac1 = (key, data) => hmac(hash, key, data);
  const hmacMany = (key, parts) => hmac1(key, parts.length === 1 ? parts[0] : concatBytes(...parts));
  return {
    name,
    hashLen,
    blockLen,
    hash: doHash,
    hmac: hmac1,
    hmacMany,
    hkdf(ck, ikm) {
      const temp = hmac1(ck, ikm);
      const o1 = hmac1(temp, new Uint8Array([1]));
      const o2 = hmacMany(temp, [o1, new Uint8Array([2])]);
      return [o1, o2];
    },
    hkdf3(ck, ikm) {
      const temp = hmac1(ck, ikm);
      const o1 = hmac1(temp, new Uint8Array([1]));
      const o2 = hmacMany(temp, [o1, new Uint8Array([2])]);
      const o3 = hmacMany(temp, [o2, new Uint8Array([3])]);
      return [o1, o2, o3];
    },
    newOutput() {
      return new Uint8Array(hashLen);
    }
  };
}
var sha256H = makeHash("SHA256", sha256, 32, 64);
var sha512H = makeHash("SHA512", sha512, 64, 128);
var blake2bH = makeHash("BLAKE2b", blake2b, 64, 128);
var blake2sH = makeHash("BLAKE2s", blake2s, 32, 64);

// node_modules/@noble/ciphers/utils.js
function isBytes3(a) {
  return a instanceof Uint8Array || ArrayBuffer.isView(a) && a.constructor.name === "Uint8Array" && "BYTES_PER_ELEMENT" in a && a.BYTES_PER_ELEMENT === 1;
}
var atitle3 = (title) => title ? `"${title}" ` : "";
function abool2(value, title = "") {
  if (typeof value !== "boolean")
    throw new TypeError(atitle3(title) + "expected boolean, got type=" + typeof value);
  return value;
}
function anumber3(n, title = "") {
  if (typeof n !== "number")
    throw new TypeError(atitle3(title) + "expected number, got " + typeof n);
  if (!Number.isSafeInteger(n) || n < 0)
    throw new RangeError(atitle3(title) + "expected integer >= 0, got " + n);
  return n;
}
function abytes3(value, length, title = "") {
  if (isBytes3(value) && (length === void 0 || value.length === length))
    return value;
  if (length !== void 0)
    anumber3(length, "length");
  const bytes = isBytes3(value);
  const ofLen = length !== void 0 ? ` of length ${length}` : "";
  const got = bytes ? `length=${value.length}` : `type=${typeof value}`;
  const message = atitle3(title) + "expected Uint8Array" + ofLen + ", got " + got;
  if (!bytes)
    throw new TypeError(message);
  throw new RangeError(message);
}
var aobject3 = (value, label) => {
  if (value === null || typeof value !== "object" || Array.isArray(value))
    throw new TypeError(label === "object" ? "expected valid options object" : `"${label}" expected object, got type=${typeof value}`);
};
function aexists2(instance, checkFinished = true) {
  if (instance.destroyed)
    throw new Error("hash was destroyed");
  if (checkFinished && instance.finished)
    throw new Error("digest() was already called");
}
function aoutput2(out, instance) {
  abytes3(out, void 0, "output");
  const min = instance.outputLen;
  if (!(out.length >= min)) {
    throw new RangeError('"output" expected length >= ' + min);
  }
}
function u322(arr) {
  return new Uint32Array(arr.buffer, arr.byteOffset, Math.floor(arr.byteLength / 4));
}
function clean2(...arrays) {
  for (let i = 0; i < arrays.length; i++) {
    arrays[i].fill(0);
  }
}
function createView2(arr) {
  return new DataView(arr.buffer, arr.byteOffset, arr.byteLength);
}
var isLE2 = /* @__PURE__ */ (() => new Uint8Array(new Uint32Array([287454020]).buffer)[0] === 68)();
function byteSwap2(word) {
  return word << 24 & 4278190080 | word << 8 & 16711680 | word >>> 8 & 65280 | word >>> 24 & 255;
}
function byteSwap322(arr) {
  for (let i = 0; i < arr.length; i++) {
    arr[i] = byteSwap2(arr[i]);
  }
  return arr;
}
var swap32IfBE2 = isLE2 ? (u) => u : byteSwap322;
function overlapBytes(a, b) {
  if (!a.byteLength || !b.byteLength)
    return false;
  return a.buffer === b.buffer && // best we can do, may fail with an obscure Proxy
  a.byteOffset < b.byteOffset + b.byteLength && // a starts before b end
  b.byteOffset < a.byteOffset + a.byteLength;
}
function complexOverlapBytes(input, output) {
  if (overlapBytes(input, output) && input.byteOffset < output.byteOffset)
    throw new Error("complex overlap of input and output is not supported");
}
function checkOpts2(defaults, opts) {
  aobject3(defaults, "defaults");
  aobject3(opts, "opts");
  const merged = Object.assign(defaults, opts);
  return merged;
}
function equalBytes(a, b) {
  a = abytes3(a);
  b = abytes3(b);
  if (a.length !== b.length)
    return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++)
    diff |= a[i] ^ b[i];
  return diff === 0;
}
function wrapMacConstructor(keyLen, macCons, fromMsg) {
  const mac = macCons;
  const getArgs = fromMsg || (() => []);
  const macC = (msg, key) => mac(key, ...getArgs(msg)).update(msg).digest();
  const tmp = mac(new Uint8Array(keyLen), ...getArgs(new Uint8Array(0)));
  macC.outputLen = tmp.outputLen;
  macC.blockLen = tmp.blockLen;
  macC.create = (key, ...args) => mac(key, ...args);
  return macC;
}
var wrapCipher = /* @__NO_SIDE_EFFECTS__ */ (params, constructor) => {
  function wrappedCipher(key, ...args) {
    abytes3(key, void 0, "key");
    if (params.nonceLength !== void 0) {
      const nonce = args[0];
      abytes3(nonce, params.varSizeNonce ? void 0 : params.nonceLength, "nonce");
    }
    const tagl = params.tagLength;
    const aadStart = params.nonceLength !== void 0 ? 1 : 0;
    if (!params.withAAD) {
      for (let i = aadStart; i < args.length; i++)
        if (isBytes3(args[i]))
          throw new Error("AAD not supported");
    }
    if (params.withAAD && args[aadStart] !== void 0)
      abytes3(args[aadStart], void 0, "AAD");
    const cipher = constructor(key, ...args);
    const checkOutput = (fnLength, output) => {
      if (output !== void 0) {
        if (fnLength !== 2)
          throw new Error("cipher output not supported");
        abytes3(output, void 0, "output");
      }
    };
    let called = false;
    const wrCipher = {
      encrypt(data, output) {
        if (called)
          throw new Error("cannot encrypt() twice with same key + nonce");
        called = true;
        abytes3(data, void 0, "data");
        checkOutput(cipher.encrypt.length, output);
        return cipher.encrypt(data, output);
      },
      decrypt(data, output) {
        abytes3(data, void 0, "data");
        if (tagl && data.length < tagl)
          throw new Error('"ciphertext" expected length >= tagLength=' + tagl);
        checkOutput(cipher.decrypt.length, output);
        return cipher.decrypt(data, output);
      }
    };
    return wrCipher;
  }
  Object.assign(wrappedCipher, params);
  return wrappedCipher;
};
function getOutput(expectedLength, out, onlyAligned = true) {
  if (out === void 0)
    return new Uint8Array(expectedLength);
  abytes3(out, expectedLength, "output");
  if (onlyAligned && !isAligned32(out))
    throw new Error("invalid output, must be aligned");
  return out;
}
function u64Lengths(dataLength, aadLength, isLE3) {
  anumber3(dataLength);
  anumber3(aadLength);
  abool2(isLE3);
  const num = new Uint8Array(16);
  const view = createView2(num);
  view.setBigUint64(0, BigInt(aadLength), isLE3);
  view.setBigUint64(8, BigInt(dataLength), isLE3);
  return num;
}
function isAligned32(bytes) {
  return bytes.byteOffset % 4 === 0;
}
function copyBytes3(bytes) {
  return Uint8Array.from(abytes3(bytes));
}

// node_modules/@noble/ciphers/_arx.js
var encodeStr = (str) => Uint8Array.from(str.split(""), (c) => c.charCodeAt(0));
var sigma16_32 = /* @__PURE__ */ (() => swap32IfBE2(u322(encodeStr("expand 16-byte k"))))();
var sigma32_32 = /* @__PURE__ */ (() => swap32IfBE2(u322(encodeStr("expand 32-byte k"))))();
function rotl(a, b) {
  return a << b | a >>> 32 - b;
}
var BLOCK_LEN = 64;
var BLOCK_LEN32 = 16;
var MAX_COUNTER = /* @__PURE__ */ (() => 2 ** 32 - 1)();
var U32_EMPTY = /* @__PURE__ */ Uint32Array.of();
function runCipher(core, sigma, key, nonce, data, output, counter, rounds) {
  const len = data.length;
  const block = new Uint8Array(BLOCK_LEN);
  const b32 = u322(block);
  const isAligned = isLE2 && isAligned32(data) && isAligned32(output);
  const d32 = isAligned ? u322(data) : U32_EMPTY;
  const o32 = isAligned ? u322(output) : U32_EMPTY;
  if (!isLE2) {
    for (let pos = 0; pos < len; counter++) {
      core(sigma, key, nonce, b32, counter, rounds);
      swap32IfBE2(b32);
      if (counter >= MAX_COUNTER)
        throw new Error("arx: counter overflow");
      const take = Math.min(BLOCK_LEN, len - pos);
      for (let j = 0, posj; j < take; j++) {
        posj = pos + j;
        output[posj] = data[posj] ^ block[j];
      }
      pos += take;
    }
    return;
  }
  for (let pos = 0; pos < len; counter++) {
    core(sigma, key, nonce, b32, counter, rounds);
    if (counter >= MAX_COUNTER)
      throw new Error("arx: counter overflow");
    const take = Math.min(BLOCK_LEN, len - pos);
    if (isAligned && take === BLOCK_LEN) {
      const pos32 = pos / 4;
      if (pos % 4 !== 0)
        throw new Error("arx: invalid block position");
      for (let j = 0, posj; j < BLOCK_LEN32; j++) {
        posj = pos32 + j;
        o32[posj] = d32[posj] ^ b32[j];
      }
      pos += BLOCK_LEN;
      continue;
    }
    for (let j = 0, posj; j < take; j++) {
      posj = pos + j;
      output[posj] = data[posj] ^ block[j];
    }
    pos += take;
  }
}
function createCipher(core, opts) {
  const { allowShortKeys, extendNonceFn, counterLength, counterRight, rounds } = checkOpts2({ allowShortKeys: false, counterLength: 8, counterRight: false, rounds: 20 }, opts);
  if (typeof core !== "function")
    throw new Error("core must be a function");
  anumber3(counterLength);
  anumber3(rounds);
  abool2(counterRight);
  abool2(allowShortKeys);
  return (key, nonce, data, output, counter = 0) => {
    abytes3(key, void 0, "key");
    abytes3(nonce, void 0, "nonce");
    abytes3(data, void 0, "data");
    const len = data.length;
    const hasOutput = output !== void 0;
    output = getOutput(len, output, false);
    if (hasOutput)
      complexOverlapBytes(data, output);
    anumber3(counter);
    if (counter < 0 || counter >= MAX_COUNTER)
      throw new Error("arx: counter overflow");
    const toClean = [];
    let l = key.length;
    let k;
    let sigma;
    if (l === 32) {
      toClean.push(k = copyBytes3(key));
      sigma = sigma32_32;
    } else if (l === 16 && allowShortKeys) {
      k = new Uint8Array(32);
      k.set(key);
      k.set(key, 16);
      sigma = sigma16_32;
      toClean.push(k);
    } else {
      abytes3(key, 32, "arx key");
      throw new Error("invalid key size");
    }
    if (!isLE2 || !isAligned32(nonce))
      toClean.push(nonce = copyBytes3(nonce));
    let k32 = u322(k);
    if (extendNonceFn) {
      if (nonce.length !== 24)
        throw new Error("arx: extended nonce must be 24 bytes");
      const n16 = nonce.subarray(0, 16);
      if (isLE2)
        extendNonceFn(sigma, k32, u322(n16), k32);
      else {
        const sigmaRaw = swap32IfBE2(Uint32Array.from(sigma));
        extendNonceFn(sigmaRaw, k32, u322(n16), k32);
        clean2(sigmaRaw);
        swap32IfBE2(k32);
      }
      nonce = nonce.subarray(16);
    } else if (!isLE2)
      swap32IfBE2(k32);
    const nonceNcLen = 16 - counterLength;
    if (nonceNcLen !== nonce.length)
      throw new Error(`arx: nonce must be ${nonceNcLen} or 16 bytes`);
    if (nonceNcLen !== 12) {
      const nc = new Uint8Array(12);
      nc.set(nonce, counterRight ? 0 : 12 - nonce.length);
      nonce = nc;
      toClean.push(nonce);
    }
    const n32 = swap32IfBE2(u322(nonce));
    try {
      runCipher(core, sigma, k32, n32, data, output, counter, rounds);
      return output;
    } finally {
      clean2(...toClean);
    }
  };
}

// node_modules/@noble/ciphers/_poly1305.js
function u8to16(a, i) {
  return a[i++] & 255 | (a[i++] & 255) << 8;
}
var Poly1305 = class {
  // Can be speed-up using BigUint64Array, at the cost of complexity
  constructor(key) {
    __publicField(this, "blockLen", 16);
    __publicField(this, "outputLen", 16);
    __publicField(this, "buffer", new Uint8Array(16));
    __publicField(this, "r", new Uint16Array(10));
    // Allocating 1 array with .subarray() here is slower than 3
    __publicField(this, "h", new Uint16Array(10));
    __publicField(this, "pad", new Uint16Array(8));
    __publicField(this, "pos", 0);
    __publicField(this, "finished", false);
    __publicField(this, "destroyed", false);
    key = copyBytes3(abytes3(key, 32, "key"));
    const t0 = u8to16(key, 0);
    const t1 = u8to16(key, 2);
    const t2 = u8to16(key, 4);
    const t3 = u8to16(key, 6);
    const t4 = u8to16(key, 8);
    const t5 = u8to16(key, 10);
    const t6 = u8to16(key, 12);
    const t7 = u8to16(key, 14);
    this.r[0] = t0 & 8191;
    this.r[1] = (t0 >>> 13 | t1 << 3) & 8191;
    this.r[2] = (t1 >>> 10 | t2 << 6) & 7939;
    this.r[3] = (t2 >>> 7 | t3 << 9) & 8191;
    this.r[4] = (t3 >>> 4 | t4 << 12) & 255;
    this.r[5] = t4 >>> 1 & 8190;
    this.r[6] = (t4 >>> 14 | t5 << 2) & 8191;
    this.r[7] = (t5 >>> 11 | t6 << 5) & 8065;
    this.r[8] = (t6 >>> 8 | t7 << 8) & 8191;
    this.r[9] = t7 >>> 5 & 127;
    for (let i = 0; i < 8; i++)
      this.pad[i] = u8to16(key, 16 + 2 * i);
  }
  process(data, offset, isLast = false) {
    const hibit = isLast ? 0 : 1 << 11;
    const { h, r } = this;
    const r0 = r[0];
    const r1 = r[1];
    const r2 = r[2];
    const r3 = r[3];
    const r4 = r[4];
    const r5 = r[5];
    const r6 = r[6];
    const r7 = r[7];
    const r8 = r[8];
    const r9 = r[9];
    const t0 = u8to16(data, offset + 0);
    const t1 = u8to16(data, offset + 2);
    const t2 = u8to16(data, offset + 4);
    const t3 = u8to16(data, offset + 6);
    const t4 = u8to16(data, offset + 8);
    const t5 = u8to16(data, offset + 10);
    const t6 = u8to16(data, offset + 12);
    const t7 = u8to16(data, offset + 14);
    let h0 = h[0] + (t0 & 8191);
    let h1 = h[1] + ((t0 >>> 13 | t1 << 3) & 8191);
    let h2 = h[2] + ((t1 >>> 10 | t2 << 6) & 8191);
    let h3 = h[3] + ((t2 >>> 7 | t3 << 9) & 8191);
    let h4 = h[4] + ((t3 >>> 4 | t4 << 12) & 8191);
    let h5 = h[5] + (t4 >>> 1 & 8191);
    let h6 = h[6] + ((t4 >>> 14 | t5 << 2) & 8191);
    let h7 = h[7] + ((t5 >>> 11 | t6 << 5) & 8191);
    let h8 = h[8] + ((t6 >>> 8 | t7 << 8) & 8191);
    let h9 = h[9] + (t7 >>> 5 | hibit);
    let c = 0;
    let d0 = c + h0 * r0 + h1 * (5 * r9) + h2 * (5 * r8) + h3 * (5 * r7) + h4 * (5 * r6);
    c = d0 >>> 13;
    d0 &= 8191;
    d0 += h5 * (5 * r5) + h6 * (5 * r4) + h7 * (5 * r3) + h8 * (5 * r2) + h9 * (5 * r1);
    c += d0 >>> 13;
    d0 &= 8191;
    let d1 = c + h0 * r1 + h1 * r0 + h2 * (5 * r9) + h3 * (5 * r8) + h4 * (5 * r7);
    c = d1 >>> 13;
    d1 &= 8191;
    d1 += h5 * (5 * r6) + h6 * (5 * r5) + h7 * (5 * r4) + h8 * (5 * r3) + h9 * (5 * r2);
    c += d1 >>> 13;
    d1 &= 8191;
    let d2 = c + h0 * r2 + h1 * r1 + h2 * r0 + h3 * (5 * r9) + h4 * (5 * r8);
    c = d2 >>> 13;
    d2 &= 8191;
    d2 += h5 * (5 * r7) + h6 * (5 * r6) + h7 * (5 * r5) + h8 * (5 * r4) + h9 * (5 * r3);
    c += d2 >>> 13;
    d2 &= 8191;
    let d3 = c + h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * (5 * r9);
    c = d3 >>> 13;
    d3 &= 8191;
    d3 += h5 * (5 * r8) + h6 * (5 * r7) + h7 * (5 * r6) + h8 * (5 * r5) + h9 * (5 * r4);
    c += d3 >>> 13;
    d3 &= 8191;
    let d4 = c + h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;
    c = d4 >>> 13;
    d4 &= 8191;
    d4 += h5 * (5 * r9) + h6 * (5 * r8) + h7 * (5 * r7) + h8 * (5 * r6) + h9 * (5 * r5);
    c += d4 >>> 13;
    d4 &= 8191;
    let d5 = c + h0 * r5 + h1 * r4 + h2 * r3 + h3 * r2 + h4 * r1;
    c = d5 >>> 13;
    d5 &= 8191;
    d5 += h5 * r0 + h6 * (5 * r9) + h7 * (5 * r8) + h8 * (5 * r7) + h9 * (5 * r6);
    c += d5 >>> 13;
    d5 &= 8191;
    let d6 = c + h0 * r6 + h1 * r5 + h2 * r4 + h3 * r3 + h4 * r2;
    c = d6 >>> 13;
    d6 &= 8191;
    d6 += h5 * r1 + h6 * r0 + h7 * (5 * r9) + h8 * (5 * r8) + h9 * (5 * r7);
    c += d6 >>> 13;
    d6 &= 8191;
    let d7 = c + h0 * r7 + h1 * r6 + h2 * r5 + h3 * r4 + h4 * r3;
    c = d7 >>> 13;
    d7 &= 8191;
    d7 += h5 * r2 + h6 * r1 + h7 * r0 + h8 * (5 * r9) + h9 * (5 * r8);
    c += d7 >>> 13;
    d7 &= 8191;
    let d8 = c + h0 * r8 + h1 * r7 + h2 * r6 + h3 * r5 + h4 * r4;
    c = d8 >>> 13;
    d8 &= 8191;
    d8 += h5 * r3 + h6 * r2 + h7 * r1 + h8 * r0 + h9 * (5 * r9);
    c += d8 >>> 13;
    d8 &= 8191;
    let d9 = c + h0 * r9 + h1 * r8 + h2 * r7 + h3 * r6 + h4 * r5;
    c = d9 >>> 13;
    d9 &= 8191;
    d9 += h5 * r4 + h6 * r3 + h7 * r2 + h8 * r1 + h9 * r0;
    c += d9 >>> 13;
    d9 &= 8191;
    c = (c << 2) + c | 0;
    c = c + d0 | 0;
    d0 = c & 8191;
    c = c >>> 13;
    d1 += c;
    h[0] = d0;
    h[1] = d1;
    h[2] = d2;
    h[3] = d3;
    h[4] = d4;
    h[5] = d5;
    h[6] = d6;
    h[7] = d7;
    h[8] = d8;
    h[9] = d9;
  }
  finalize() {
    const { h, pad } = this;
    const g = new Uint16Array(10);
    let c = h[1] >>> 13;
    h[1] &= 8191;
    for (let i = 2; i < 10; i++) {
      h[i] += c;
      c = h[i] >>> 13;
      h[i] &= 8191;
    }
    h[0] += c * 5;
    c = h[0] >>> 13;
    h[0] &= 8191;
    h[1] += c;
    c = h[1] >>> 13;
    h[1] &= 8191;
    h[2] += c;
    g[0] = h[0] + 5;
    c = g[0] >>> 13;
    g[0] &= 8191;
    for (let i = 1; i < 10; i++) {
      g[i] = h[i] + c;
      c = g[i] >>> 13;
      g[i] &= 8191;
    }
    g[9] -= 1 << 13;
    let mask = (c ^ 1) - 1;
    for (let i = 0; i < 10; i++)
      g[i] &= mask;
    mask = ~mask;
    for (let i = 0; i < 10; i++)
      h[i] = h[i] & mask | g[i];
    h[0] = (h[0] | h[1] << 13) & 65535;
    h[1] = (h[1] >>> 3 | h[2] << 10) & 65535;
    h[2] = (h[2] >>> 6 | h[3] << 7) & 65535;
    h[3] = (h[3] >>> 9 | h[4] << 4) & 65535;
    h[4] = (h[4] >>> 12 | h[5] << 1 | h[6] << 14) & 65535;
    h[5] = (h[6] >>> 2 | h[7] << 11) & 65535;
    h[6] = (h[7] >>> 5 | h[8] << 8) & 65535;
    h[7] = (h[8] >>> 8 | h[9] << 5) & 65535;
    let f = h[0] + pad[0];
    h[0] = f & 65535;
    for (let i = 1; i < 8; i++) {
      f = (h[i] + pad[i] | 0) + (f >>> 16) | 0;
      h[i] = f & 65535;
    }
    clean2(g);
  }
  update(data) {
    aexists2(this);
    abytes3(data);
    data = copyBytes3(data);
    const { buffer, blockLen } = this;
    const len = data.length;
    for (let pos = 0; pos < len; ) {
      const take = Math.min(blockLen - this.pos, len - pos);
      if (take === blockLen) {
        for (; blockLen <= len - pos; pos += blockLen)
          this.process(data, pos);
        continue;
      }
      buffer.set(data.subarray(pos, pos + take), this.pos);
      this.pos += take;
      pos += take;
      if (this.pos === blockLen) {
        this.process(buffer, 0, false);
        this.pos = 0;
      }
    }
    return this;
  }
  destroy() {
    this.destroyed = true;
    clean2(this.h, this.r, this.buffer, this.pad);
  }
  digestInto(out) {
    aexists2(this);
    aoutput2(out, this);
    this.finished = true;
    const { buffer, h } = this;
    let { pos } = this;
    if (pos) {
      buffer[pos++] = 1;
      for (; pos < 16; pos++)
        buffer[pos] = 0;
      this.process(buffer, 0, true);
    }
    this.finalize();
    let opos = 0;
    for (let i = 0; i < 8; i++) {
      out[opos++] = h[i] >>> 0;
      out[opos++] = h[i] >>> 8;
    }
  }
  digest() {
    const { buffer, outputLen } = this;
    this.digestInto(buffer);
    const res = buffer.slice(0, outputLen);
    this.destroy();
    return res;
  }
};
var poly1305 = /* @__PURE__ */ wrapMacConstructor(32, (key) => new Poly1305(key));

// node_modules/@noble/ciphers/chacha.js
function chachaCore(s, k, n, out, cnt, rounds = 20) {
  let y00 = s[0], y01 = s[1], y02 = s[2], y03 = s[3], y04 = k[0], y05 = k[1], y06 = k[2], y07 = k[3], y08 = k[4], y09 = k[5], y10 = k[6], y11 = k[7], y12 = cnt, y13 = n[0], y14 = n[1], y15 = n[2];
  let x00 = y00, x01 = y01, x02 = y02, x03 = y03, x04 = y04, x05 = y05, x06 = y06, x07 = y07, x08 = y08, x09 = y09, x10 = y10, x11 = y11, x12 = y12, x13 = y13, x14 = y14, x15 = y15;
  for (let r = 0; r < rounds; r += 2) {
    x00 = x00 + x04 | 0;
    x12 = rotl(x12 ^ x00, 16);
    x08 = x08 + x12 | 0;
    x04 = rotl(x04 ^ x08, 12);
    x00 = x00 + x04 | 0;
    x12 = rotl(x12 ^ x00, 8);
    x08 = x08 + x12 | 0;
    x04 = rotl(x04 ^ x08, 7);
    x01 = x01 + x05 | 0;
    x13 = rotl(x13 ^ x01, 16);
    x09 = x09 + x13 | 0;
    x05 = rotl(x05 ^ x09, 12);
    x01 = x01 + x05 | 0;
    x13 = rotl(x13 ^ x01, 8);
    x09 = x09 + x13 | 0;
    x05 = rotl(x05 ^ x09, 7);
    x02 = x02 + x06 | 0;
    x14 = rotl(x14 ^ x02, 16);
    x10 = x10 + x14 | 0;
    x06 = rotl(x06 ^ x10, 12);
    x02 = x02 + x06 | 0;
    x14 = rotl(x14 ^ x02, 8);
    x10 = x10 + x14 | 0;
    x06 = rotl(x06 ^ x10, 7);
    x03 = x03 + x07 | 0;
    x15 = rotl(x15 ^ x03, 16);
    x11 = x11 + x15 | 0;
    x07 = rotl(x07 ^ x11, 12);
    x03 = x03 + x07 | 0;
    x15 = rotl(x15 ^ x03, 8);
    x11 = x11 + x15 | 0;
    x07 = rotl(x07 ^ x11, 7);
    x00 = x00 + x05 | 0;
    x15 = rotl(x15 ^ x00, 16);
    x10 = x10 + x15 | 0;
    x05 = rotl(x05 ^ x10, 12);
    x00 = x00 + x05 | 0;
    x15 = rotl(x15 ^ x00, 8);
    x10 = x10 + x15 | 0;
    x05 = rotl(x05 ^ x10, 7);
    x01 = x01 + x06 | 0;
    x12 = rotl(x12 ^ x01, 16);
    x11 = x11 + x12 | 0;
    x06 = rotl(x06 ^ x11, 12);
    x01 = x01 + x06 | 0;
    x12 = rotl(x12 ^ x01, 8);
    x11 = x11 + x12 | 0;
    x06 = rotl(x06 ^ x11, 7);
    x02 = x02 + x07 | 0;
    x13 = rotl(x13 ^ x02, 16);
    x08 = x08 + x13 | 0;
    x07 = rotl(x07 ^ x08, 12);
    x02 = x02 + x07 | 0;
    x13 = rotl(x13 ^ x02, 8);
    x08 = x08 + x13 | 0;
    x07 = rotl(x07 ^ x08, 7);
    x03 = x03 + x04 | 0;
    x14 = rotl(x14 ^ x03, 16);
    x09 = x09 + x14 | 0;
    x04 = rotl(x04 ^ x09, 12);
    x03 = x03 + x04 | 0;
    x14 = rotl(x14 ^ x03, 8);
    x09 = x09 + x14 | 0;
    x04 = rotl(x04 ^ x09, 7);
  }
  let oi = 0;
  out[oi++] = y00 + x00 | 0;
  out[oi++] = y01 + x01 | 0;
  out[oi++] = y02 + x02 | 0;
  out[oi++] = y03 + x03 | 0;
  out[oi++] = y04 + x04 | 0;
  out[oi++] = y05 + x05 | 0;
  out[oi++] = y06 + x06 | 0;
  out[oi++] = y07 + x07 | 0;
  out[oi++] = y08 + x08 | 0;
  out[oi++] = y09 + x09 | 0;
  out[oi++] = y10 + x10 | 0;
  out[oi++] = y11 + x11 | 0;
  out[oi++] = y12 + x12 | 0;
  out[oi++] = y13 + x13 | 0;
  out[oi++] = y14 + x14 | 0;
  out[oi++] = y15 + x15 | 0;
}
var chacha20 = /* @__PURE__ */ createCipher(chachaCore, {
  counterRight: false,
  counterLength: 4,
  allowShortKeys: false
});
var ZEROS16 = /* @__PURE__ */ new Uint8Array(16);
var updatePadded = (h, msg) => {
  h.update(msg);
  const leftover = msg.length % 16;
  if (leftover)
    h.update(ZEROS16.subarray(leftover));
};
var ZEROS32 = /* @__PURE__ */ new Uint8Array(32);
function computeTag(fn, key, nonce, ciphertext, AAD) {
  if (AAD !== void 0)
    abytes3(AAD, void 0, "AAD");
  const authKey = fn(key, nonce, ZEROS32);
  const lengths = u64Lengths(ciphertext.length, AAD ? AAD.length : 0, true);
  const h = poly1305.create(authKey);
  if (AAD)
    updatePadded(h, AAD);
  updatePadded(h, ciphertext);
  h.update(lengths);
  const res = h.digest();
  clean2(authKey, lengths);
  return res;
}
var _poly1305_aead = (xorStream) => (key, nonce, AAD) => {
  const tagLength = 16;
  return {
    encrypt(plaintext, output) {
      const plength = plaintext.length;
      output = getOutput(plength + tagLength, output, false);
      output.set(plaintext);
      const oPlain = output.subarray(0, -tagLength);
      xorStream(key, nonce, oPlain, oPlain, 1);
      const tag = computeTag(xorStream, key, nonce, oPlain, AAD);
      output.set(tag, plength);
      clean2(tag);
      return output;
    },
    decrypt(ciphertext, output) {
      output = getOutput(ciphertext.length - tagLength, output, false);
      const data = ciphertext.subarray(0, -tagLength);
      const passedTag = ciphertext.subarray(-tagLength);
      const tag = computeTag(xorStream, key, nonce, data, AAD);
      if (!equalBytes(passedTag, tag)) {
        clean2(tag);
        throw new Error("invalid tag");
      }
      output.set(ciphertext.subarray(0, -tagLength));
      xorStream(key, nonce, output, output, 1);
      clean2(tag);
      return output;
    }
  };
};
var chacha20poly1305 = /* @__PURE__ */ wrapCipher(
  { blockSize: 64, nonceLength: 12, tagLength: 16, withAAD: true },
  /* @__PURE__ */ _poly1305_aead(chacha20)
);

// node_modules/@lukeburns/clatterjs/dist/crypto/cipher.js
var U64_MAX2 = 0xfffffffffffffffn;
function chachaNonce(n) {
  const b = new Uint8Array(12);
  new DataView(b.buffer).setBigUint64(4, n, true);
  return b;
}
function asErr(e, op) {
  if (e instanceof CipherError)
    return e;
  return new CipherError(op, e instanceof Error ? e.message : String(e));
}
var chachaPoly = /* @__PURE__ */ (() => {
  const keyLen = 32;
  const tagLen = 16;
  return {
    name: "ChaChaPoly",
    keyLen,
    tagLen,
    encrypt(key, nonce, ad, plaintext, out) {
      const ct = chacha20poly1305(key, chachaNonce(nonce), ad).encrypt(plaintext);
      out.set(ct);
    },
    encryptInPlace(key, nonce, ad, inOut, plaintextLen) {
      const sub = inOut.subarray(0, plaintextLen + tagLen);
      const pt = inOut.subarray(0, plaintextLen);
      try {
        const ct = chacha20poly1305(key, chachaNonce(nonce), ad).encrypt(pt);
        sub.set(ct);
        return ct.length;
      } catch (e) {
        throw asErr(e, "Encrypt");
      }
    },
    decrypt(key, nonce, ad, ciphertext, out) {
      try {
        const pt = chacha20poly1305(key, chachaNonce(nonce), ad).decrypt(ciphertext);
        out.set(pt);
      } catch (e) {
        throw asErr(e, "Decrypt");
      }
    },
    decryptInPlace(key, nonce, ad, inOut, ciphertextLen) {
      const sub = inOut.subarray(0, ciphertextLen);
      try {
        const pt = chacha20poly1305(key, chachaNonce(nonce), ad).decrypt(sub);
        inOut.set(pt);
        return pt.length;
      } catch (e) {
        throw asErr(e, "Decrypt");
      }
    },
    rekey(key) {
      const kNew = new Uint8Array(MAX_KEY_LEN + MAX_TAG_LEN);
      const z = new Uint8Array(keyLen);
      const ct = chacha20poly1305(key, chachaNonce(U64_MAX2), new Uint8Array(0)).encrypt(z);
      kNew.set(ct);
      return kNew.subarray(0, keyLen);
    }
  };
})();

// node_modules/@lukeburns/clatterjs/dist/noiseNq.js
function noiseIk() {
  return new HandshakePattern("IK", [], [
    1
    /* Token.S */
  ], [
    [
      0,
      3,
      1,
      5
      /* Token.SS */
    ]
  ], [[
    0,
    2,
    4
    /* Token.SE */
  ]]);
}

// dsh/noise.ts
var NOISE_IK_PROTOCOL = "Noise_IK_25519_ChaChaPoly_SHA256";
var NoiseIkSession = class {
  constructor(options) {
    this.destroyed = false;
    const localPrivate = requireKey(options.localPrivateKey, "local private key");
    const localPublic = requireKey(options.localPublicKey, "local public key");
    this.expectedRemoteStatic = requireKey(options.remotePublicKey, "remote public key");
    const initiator = options.role === "initiator";
    this.handshake = new NqHandshake(noiseIk(), {
      prologue: Uint8Array.from(options.prologue),
      initiator,
      s: { secretKey: localPrivate, publicKey: localPublic },
      ...initiator ? { rs: Uint8Array.from(this.expectedRemoteStatic) } : {},
      cipher: options.encryptChaChaPoly ? {
        ...chachaPoly,
        encrypt(key, nonce, ad, plaintext, out) {
          const encrypted = options.encryptChaChaPoly(key, nonce, ad, plaintext);
          if (encrypted === void 0) return chachaPoly.encrypt(key, nonce, ad, plaintext, out);
          if (encrypted.byteLength !== plaintext.byteLength + chachaPoly.tagLen) {
            throw new NoiseSessionError("INVALID_ENCRYPTION_OUTPUT", "ChaChaPoly accelerator returned an invalid length.");
          }
          out.set(encrypted);
        }
      } : chachaPoly,
      hash: sha256H,
      rng: options.random ?? randomBytes5
    });
    if (this.handshake.getName() !== NOISE_IK_PROTOCOL) {
      throw new NoiseSessionError("NOISE_SUITE_MISMATCH", "The Noise provider selected an unexpected cipher suite.");
    }
  }
  get protocol() {
    return NOISE_IK_PROTOCOL;
  }
  get complete() {
    return this.transport !== void 0;
  }
  get canWriteHandshake() {
    this.assertLive();
    return !this.complete && this.handshake.isWriteTurn();
  }
  writeHandshake(payload = new Uint8Array()) {
    this.assertHandshake(false);
    const output = new Uint8Array(payload.byteLength + this.handshake.getNextMessageOverhead());
    const length = this.handshake.writeMessage(payload, output);
    this.finishIfReady();
    return output.slice(0, length);
  }
  readHandshake(message) {
    this.assertHandshake(true);
    const output = new Uint8Array(message.byteLength);
    const length = this.handshake.readMessage(message, output);
    this.verifyRemoteStatic();
    this.finishIfReady();
    return output.slice(0, length);
  }
  encrypt(plaintext) {
    this.assertLive();
    if (this.transport === void 0) throw new NoiseSessionError("NOISE_NOT_READY", "The Noise handshake is not complete.");
    return this.transport.sendVec(plaintext);
  }
  decrypt(ciphertext) {
    this.assertLive();
    if (this.transport === void 0) throw new NoiseSessionError("NOISE_NOT_READY", "The Noise handshake is not complete.");
    return this.transport.receiveVec(ciphertext);
  }
  sendingCounter() {
    if (this.transport === void 0) return 0n;
    return this.transport.sendingNonce();
  }
  receivingCounter() {
    if (this.transport === void 0) return 0n;
    return this.transport.receivingNonce();
  }
  destroy() {
    if (this.destroyed) return;
    this.destroyed = true;
    this.expectedRemoteStatic.fill(0);
    this.transport = void 0;
    this.handshake = void 0;
  }
  finishIfReady() {
    if (!this.handshake.isFinished()) return;
    this.verifyRemoteStatic();
    this.transport = this.handshake.finalize();
  }
  verifyRemoteStatic() {
    const actual = this.handshake.getRemoteStatic();
    if (actual === void 0 || !constantTimeEqual(actual, this.expectedRemoteStatic)) {
      this.destroy();
      throw new NoiseSessionError("PEER_IDENTITY_MISMATCH", "The Noise peer static key does not match local trust.");
    }
  }
  assertHandshake(reading) {
    this.assertLive();
    if (this.complete) throw new NoiseSessionError("NOISE_ALREADY_READY", "The Noise handshake is already complete.");
    if (this.handshake.isWriteTurn() === reading) {
      throw new NoiseSessionError("NOISE_HANDSHAKE_ORDER", "The Noise handshake message arrived out of order.");
    }
  }
  assertLive() {
    if (this.destroyed) throw new NoiseSessionError("NOISE_DESTROYED", "The Noise session is closed.");
  }
};
var NoiseSessionError = class extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
};
function createNoisePrologue(connectionId, hostDeviceId, clientDeviceId) {
  for (const [name, value] of Object.entries({ connectionId, hostDeviceId, clientDeviceId })) {
    if (value.length === 0 || value.includes("\0")) throw new TypeError(`${name} is not safe for a Noise prologue`);
  }
  return Uint8Array.from(ctx.codec.utf8Encode(
    `DSH-REMOTE\0v=1\0connection=${connectionId}\0host=${hostDeviceId}\0client=${clientDeviceId}`
  ));
}
function requireKey(value, label) {
  const key = fromBase64Url(value);
  if (key.byteLength !== 32) throw new NoiseSessionError("INVALID_KEY", `${label} must be a 32-byte X25519 key.`);
  return Uint8Array.from(key);
}
function constantTimeEqual(left, right) {
  if (left.byteLength !== right.byteLength) return false;
  let different = 0;
  for (let index = 0; index < left.byteLength; index += 1) different |= left[index] ^ right[index];
  return different === 0;
}
function randomBytes5(length) {
  return Uint8Array.from(ctx.crypto.randomBytes(length));
}

// dsh/api.ts
var DEVICE_ROLE = "client";
var ApiError = class extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
};
function normalizeServerUrl(input, allowInsecure = false) {
  const raw = (input ?? "").trim();
  if (raw.length === 0) throw new ApiError(0, "SERVER_URL_EMPTY", "Server \u5730\u5740\u4E0D\u80FD\u4E3A\u7A7A");
  const match = /^([A-Za-z][A-Za-z0-9+.-]*):\/\/([^/?#]*)([/?#].*)?$/.exec(raw);
  if (match === null) {
    throw new ApiError(0, "SERVER_URL_INVALID", `Server \u5730\u5740\u4E0D\u5408\u6CD5\uFF1A${raw}`);
  }
  const scheme = match[1].toLowerCase();
  const authority = match[2];
  const rest = match[3];
  if (scheme !== "https" && !(allowInsecure && scheme === "http")) {
    throw new ApiError(0, "SERVER_URL_INSECURE", "Server \u5FC5\u987B\u4F7F\u7528 HTTPS");
  }
  if (rest !== void 0 && rest !== "/" && rest.length > 0) {
    throw new ApiError(0, "SERVER_URL_INVALID", "Server \u5730\u5740\u53EA\u80FD\u662F\u6E90\uFF08scheme://host[:port]\uFF09\uFF0C\u4E0D\u8981\u5E26\u8DEF\u5F84");
  }
  if (authority.length === 0) {
    throw new ApiError(0, "SERVER_URL_INVALID", "Server \u5730\u5740\u7F3A\u5C11\u4E3B\u673A\u540D");
  }
  if (authority.includes("@")) {
    throw new ApiError(0, "SERVER_URL_INVALID", "Server \u5730\u5740\u4E0D\u80FD\u5305\u542B\u7528\u6237\u540D\u6216\u5BC6\u7801");
  }
  if (!/^[A-Za-z0-9._-]+(:\d{1,5})?$/.test(authority) && !/^\[[0-9A-Fa-f:.]+\](:\d{1,5})?$/.test(authority)) {
    throw new ApiError(0, "SERVER_URL_INVALID", `Server \u4E3B\u673A\u540D\u4E0D\u5408\u6CD5\uFF1A${authority}`);
  }
  return `${scheme}://${authority}`;
}
function deriveWsUrl(serverUrl) {
  const match = /^(https?):\/\/(.+)$/.exec(serverUrl);
  if (match === null) {
    throw new ApiError(0, "SERVER_URL_INVALID", `Server \u5730\u5740\u672A\u89C4\u8303\u5316\uFF1A${serverUrl}`);
  }
  const scheme = match[1] === "https" ? "wss" : "ws";
  return `${scheme}://${match[2]}/ws/v1/connect`;
}
async function requestJson(serverUrl, options) {
  const headers = { accept: "application/json" };
  if (options.bearer !== void 0) headers.authorization = `Bearer ${options.bearer}`;
  const response = await ctx.http.request(
    options.method,
    `${serverUrl}${options.path}`,
    options.body === void 0 ? null : JSON.stringify(options.body),
    {
      headers,
      contentType: "application/json; charset=utf-8",
      timeoutMs: options.timeoutMs ?? 2e4,
      // 这些接口都不是幂等语义（注册会建设备、刷新会轮换 token），禁止宿主自动重试
      retry: false
    }
  );
  const payload = response.json;
  if (response.status >= 200 && response.status < 300) {
    if (payload === null || typeof payload !== "object") {
      throw new ApiError(response.status, "BAD_RESPONSE", "Server \u8FD4\u56DE\u7684\u4E0D\u662F JSON \u5BF9\u8C61");
    }
    return payload;
  }
  const code = typeof payload?.code === "string" ? payload.code : `HTTP_${response.status}`;
  const message = typeof payload?.message === "string" && payload.message.length > 0 ? payload.message : `\u8BF7\u6C42\u5931\u8D25\uFF08HTTP ${response.status}\uFF09`;
  throw new ApiError(response.status, code, message);
}
async function loginWithPassword(serverUrl, email, password) {
  const body = await requestJson(serverUrl, {
    method: "POST",
    path: "/api/v1/auth/login",
    body: { email, password }
  });
  const token = body.token;
  if (typeof token !== "string" || token.length === 0) {
    throw new ApiError(0, "BAD_RESPONSE", "\u767B\u5F55\u54CD\u5E94\u7F3A\u5C11 token");
  }
  return {
    token,
    expiresAt: typeof body.expiresAt === "number" ? body.expiresAt : 0,
    account: typeof body.account === "string" ? body.account : email
  };
}
function readCredentials(body) {
  const access = body.accessToken;
  const refresh = body.refreshToken;
  if (typeof access !== "string" || access.length === 0) {
    throw new ApiError(0, "BAD_RESPONSE", "\u6CE8\u518C\u54CD\u5E94\u7F3A\u5C11 accessToken");
  }
  if (typeof refresh !== "string" || refresh.length === 0) {
    throw new ApiError(0, "BAD_RESPONSE", "\u6CE8\u518C\u54CD\u5E94\u7F3A\u5C11 refreshToken");
  }
  return {
    deviceAccessToken: access,
    deviceAccessTokenExpiresAt: typeof body.accessTokenExpiresAt === "number" ? body.accessTokenExpiresAt : 0,
    deviceRefreshToken: refresh,
    deviceRefreshTokenExpiresAt: typeof body.refreshTokenExpiresAt === "number" ? body.refreshTokenExpiresAt : 0
  };
}
async function registerDevice(serverUrl, accountToken, device) {
  const body = await requestJson(serverUrl, {
    method: "POST",
    path: "/api/v1/devices/register",
    bearer: accountToken,
    body: {
      v: 1,
      device: {
        deviceId: device.deviceId,
        name: device.name,
        role: DEVICE_ROLE,
        platform: device.platform,
        identityKey: device.identityKey,
        clientVersion: device.clientVersion
      }
    }
  });
  return readCredentials(body);
}
async function refreshDevice(serverUrl, deviceId, refreshToken) {
  const body = await requestJson(serverUrl, {
    method: "POST",
    path: "/api/v1/auth/refresh",
    body: { deviceId, refreshToken }
  });
  return readCredentials(body);
}
function needsRefresh(expiresAt, now = Date.now(), skewMs = 6e4) {
  if (!Number.isFinite(expiresAt) || expiresAt <= 0) return true;
  return expiresAt - now <= skewMs;
}
function isObject(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function readHostDevices(body) {
  if (!isObject(body) || !Array.isArray(body.items)) return [];
  const hosts = [];
  for (const item of body.items) {
    const host = readHostDevice(item);
    if (host !== void 0) hosts.push(host);
  }
  return hosts;
}
function readHostDevice(item) {
  if (!isObject(item) || item.role !== "host") return void 0;
  const { deviceId, name, platform, membershipId, identityKey } = item;
  if (typeof deviceId !== "string" || deviceId.length === 0) return void 0;
  if (typeof name !== "string" || name.length === 0) return void 0;
  if (typeof platform !== "string") return void 0;
  if (typeof membershipId !== "string" || membershipId.length === 0) return void 0;
  if (typeof identityKey !== "string" || identityKey.length === 0) return void 0;
  const host = { deviceId, name, platform, online: item.online === true, identityKey };
  if (typeof item.clientVersion === "string" && item.clientVersion.length > 0) {
    host.clientVersion = item.clientVersion;
  }
  if (typeof item.harnessVersion === "string" && item.harnessVersion.length > 0) {
    host.harnessVersion = item.harnessVersion;
  }
  if (typeof item.lastSeenAt === "number" && Number.isFinite(item.lastSeenAt)) {
    host.lastSeenAt = item.lastSeenAt;
  }
  return host;
}
async function listHostDevices(serverUrl, accessToken) {
  if (typeof accessToken !== "string" || accessToken.length === 0) {
    throw new ApiError(0, "AUTH_REQUIRED", "\u7F3A\u5C11\u8BBE\u5907 access token");
  }
  const body = await requestJson(serverUrl, {
    method: "GET",
    path: "/api/v1/devices",
    bearer: accessToken
  });
  return readHostDevices(body);
}
var SLOT_AUTH = "remote-auth";
var SLOT_DEVICE_TOKENS = "remote-device-tokens";
var SLOT_IDENTITY = "remote-identity";
function loadAuth() {
  return readSlot(SLOT_AUTH);
}
function saveAuth(auth) {
  ctx.credentials.set(SLOT_AUTH, JSON.stringify(auth));
}
function loadDeviceCredentials() {
  return readSlot(SLOT_DEVICE_TOKENS);
}
function saveDeviceCredentials(credentials) {
  ctx.credentials.set(SLOT_DEVICE_TOKENS, JSON.stringify(credentials));
}
function loadIdentity() {
  return readSlot(SLOT_IDENTITY);
}
function saveIdentity(pair) {
  ctx.credentials.set(SLOT_IDENTITY, JSON.stringify(pair));
}
function clearAllAuth() {
  for (const slot of [SLOT_AUTH, SLOT_DEVICE_TOKENS, SLOT_IDENTITY]) {
    ctx.credentials.set(slot, null);
  }
}
function readSlot(slot) {
  const raw = ctx.credentials.get(slot);
  if (typeof raw !== "string" || raw.length === 0) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

// dsh/protocol.ts
var PROTOCOL_VERSION = 1;
var MAX_CONTROL_FRAME_BYTES = 64 * 1024;
var CONTROL_FRAME_TYPES = [
  "hello",
  "hello.ack",
  "connect.request",
  "connect.incoming",
  "connect.accepted",
  "connect.rejected",
  "secure.handshake",
  "relay",
  "signal.offer",
  "signal.answer",
  "signal.ice",
  "transport.selected",
  "connection.lease.request",
  "connection.lease.ack",
  "connection.lease.close",
  "ping",
  "pong",
  "error"
];
var ProtocolError = class extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
};
var TYPE_SET = new Set(CONTROL_FRAME_TYPES);
function newFrameId() {
  const bytes = ctx.crypto.randomBytes(8);
  let suffix = "";
  for (let i = 0; i < bytes.length; i += 1) suffix += bytes[i].toString(16).padStart(2, "0");
  return `f_${Date.now().toString(36)}_${suffix}`;
}
function createFrame(type, payload) {
  return {
    v: PROTOCOL_VERSION,
    id: newFrameId(),
    type,
    timestamp: Date.now(),
    payload
  };
}
function encodeFrame(frame) {
  const text = JSON.stringify(frame);
  const bytes = ctx.codec.utf8Encode(text).length;
  if (bytes > MAX_CONTROL_FRAME_BYTES) {
    throw new ProtocolError("BAD_FRAME", `\u63A7\u5236\u5E27 ${bytes} \u5B57\u8282\uFF0C\u8D85\u8FC7\u4E0A\u9650 ${MAX_CONTROL_FRAME_BYTES}`);
  }
  return text;
}
function isObject2(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function decodeFrame(text) {
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    throw new ProtocolError("NOT_JSON", "\u6536\u5230\u975E JSON \u7684\u63A7\u5236\u5E27");
  }
  if (!isObject2(value)) throw new ProtocolError("BAD_FRAME", "\u63A7\u5236\u5E27\u4E0D\u662F\u5BF9\u8C61");
  const { v, id, type, timestamp, payload } = value;
  if (v !== PROTOCOL_VERSION) throw new ProtocolError("BAD_FRAME", `\u534F\u8BAE\u7248\u672C\u4E0D\u7B26\uFF1A${String(v)}`);
  if (typeof id !== "string" || id.length === 0) throw new ProtocolError("BAD_FRAME", "\u63A7\u5236\u5E27\u7F3A\u5C11 id");
  if (typeof type !== "string" || !TYPE_SET.has(type)) {
    throw new ProtocolError("UNKNOWN_TYPE", `\u672A\u77E5\u63A7\u5236\u5E27\u7C7B\u578B\uFF1A${String(type)}`);
  }
  if (typeof timestamp !== "number" || !Number.isInteger(timestamp) || timestamp <= 0) {
    throw new ProtocolError("BAD_FRAME", "\u63A7\u5236\u5E27\u65F6\u95F4\u6233\u975E\u6CD5");
  }
  if (!isObject2(payload)) throw new ProtocolError("BAD_FRAME", `\u63A7\u5236\u5E27 ${type} \u7684 payload \u4E0D\u662F\u5BF9\u8C61`);
  return { v, id, type, timestamp, payload };
}
function requireString(source, key, frame) {
  const value = source[key];
  if (typeof value !== "string" || value.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", `${frame} \u7F3A\u5C11\u5B57\u7B26\u4E32\u5B57\u6BB5 ${key}`);
  }
  return value;
}
function optionalString(source, key) {
  const value = source[key];
  return typeof value === "string" && value.length > 0 ? value : void 0;
}
function readHelloAck(payload) {
  const protocol = payload.protocol;
  if (protocol !== PROTOCOL_VERSION) {
    throw new ProtocolError("BAD_PAYLOAD", `\u670D\u52A1\u7AEF\u534F\u8BAE\u7248\u672C ${String(protocol)}\uFF0C\u672C\u63D2\u4EF6\u53EA\u652F\u6301 ${PROTOCOL_VERSION}`);
  }
  const heartbeat = payload.heartbeatIntervalMs;
  const maxControl = payload.maxControlFrameBytes;
  const maxRelay = payload.maxRelayFrameBytes;
  if (typeof heartbeat !== "number" || !Number.isFinite(heartbeat) || heartbeat <= 0) {
    throw new ProtocolError("BAD_PAYLOAD", "hello.ack \u7684 heartbeatIntervalMs \u975E\u6CD5");
  }
  return {
    serverVersion: requireString(payload, "serverVersion", "hello.ack"),
    connectionSessionId: requireString(payload, "connectionSessionId", "hello.ack"),
    heartbeatIntervalMs: heartbeat,
    // 服务端可能给出比本地常量更小的上限，取小值才安全
    maxControlFrameBytes: typeof maxControl === "number" && maxControl > 0 ? Math.min(maxControl, MAX_CONTROL_FRAME_BYTES) : MAX_CONTROL_FRAME_BYTES,
    maxRelayFrameBytes: typeof maxRelay === "number" && maxRelay > 0 ? maxRelay : 1024 * 1024,
    webrtcEnabled: payload.webrtcEnabled === true
  };
}
function readConnectIncoming(payload) {
  const authorization = payload.authorization;
  if (authorization !== "account") {
    throw new ProtocolError("UNSUPPORTED", `\u4E0D\u652F\u6301\u7684\u6388\u6743\u65B9\u5F0F\uFF1A${String(authorization)}`);
  }
  const epoch = payload.relayEpoch;
  return {
    connectionId: requireString(payload, "connectionId", "connect.incoming"),
    clientDeviceId: requireString(payload, "clientDeviceId", "connect.incoming"),
    clientIdentityKey: requireString(payload, "clientIdentityKey", "connect.incoming"),
    authorization,
    relayEndpoint: optionalString(payload, "relayEndpoint"),
    relayTicket: optionalString(payload, "relayTicket"),
    relayNodeId: optionalString(payload, "relayNodeId"),
    relayEpoch: typeof epoch === "number" && Number.isInteger(epoch) && epoch >= 0 ? epoch : void 0
  };
}
function readSecureHandshake(payload) {
  const step = payload.step;
  if (step !== 1 && step !== 2) {
    throw new ProtocolError("BAD_PAYLOAD", `secure.handshake \u7684 step \u53EA\u80FD\u662F 1 \u6216 2\uFF0C\u5B9E\u9645 ${String(step)}`);
  }
  return {
    connectionId: requireString(payload, "connectionId", "secure.handshake"),
    targetDeviceId: requireString(payload, "targetDeviceId", "secure.handshake"),
    step,
    data: requireString(payload, "data", "secure.handshake")
  };
}
function readRelay(payload) {
  const counter = payload.counter;
  if (typeof counter !== "number" || !Number.isInteger(counter) || counter < 0) {
    throw new ProtocolError("BAD_PAYLOAD", "relay \u7684 counter \u975E\u6CD5");
  }
  return {
    connectionId: requireString(payload, "connectionId", "relay"),
    targetDeviceId: requireString(payload, "targetDeviceId", "relay"),
    counter,
    ciphertext: requireString(payload, "ciphertext", "relay")
  };
}
function readPingPongNonce(payload) {
  return requireString(payload, "nonce", "ping/pong");
}
function readControlError(payload) {
  return {
    code: requireString(payload, "code", "error"),
    message: requireString(payload, "message", "error"),
    retryable: payload.retryable === true
  };
}

// dsh/controlChannel.ts
var ControlStateError = class extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
};
var UNSUPPORTED_TYPES = /* @__PURE__ */ new Set([
  "signal.offer",
  "signal.answer",
  "signal.ice",
  "connection.lease.request",
  "connection.lease.ack",
  "connection.lease.close"
]);
var ControlChannel = class {
  constructor(handlers = {}) {
    this.handlers = handlers;
    this.phase = "greeting";
  }
  get ready() {
    return this.phase === "ready";
  }
  /**
   * 构造 `hello` 帧文本。
   *
   * 必须在连接建立后**立刻**发出（§7：5 秒内），所以这里只产文本，
   * 由调用方紧接着 `send`——中间不插任何 await，避免被别的等待挤到后面。
   */
  createHello(payload) {
    if (this.phase === "closed") throw new ControlStateError("CLOSED", "\u8FDE\u63A5\u5DF2\u5173\u95ED");
    if (payload.deviceId.length === 0) throw new ControlStateError("NOT_READY", "hello \u7F3A\u5C11 deviceId");
    if (payload.accessToken.length === 0) throw new ControlStateError("NOT_READY", "hello \u7F3A\u5C11 accessToken");
    return encodeFrame(createFrame("hello", {
      role: payload.role,
      deviceId: payload.deviceId,
      accessToken: payload.accessToken,
      protocols: [1],
      capabilities: payload.capabilities ?? []
    }));
  }
  /** 构造任意出站帧（`connect.request`、`secure.handshake` 等） */
  send(type, payload) {
    if (this.phase === "closed") throw new ControlStateError("CLOSED", "\u8FDE\u63A5\u5DF2\u5173\u95ED");
    if (type === "hello") throw new ControlStateError("NOT_READY", "hello \u53EA\u80FD\u7531 createHello \u6784\u9020");
    return encodeFrame(createFrame(type, payload));
  }
  /**
   * 处理一条入站帧文本。
   *
   * 未知帧类型**忽略**而不是断开：协议会演进，服务端先上新类型不应把旧客户端踢下线。
   * 但畸形帧（非 JSON、版本不符、缺字段）必须抛错——那说明两端理解根本不一致，
   * 继续读下去只会错得更远。
   */
  handle(text) {
    if (this.phase === "closed") throw new ControlStateError("CLOSED", "\u8FDE\u63A5\u5DF2\u5173\u95ED");
    let frame;
    try {
      frame = decodeFrame(text);
    } catch (e) {
      if (e instanceof ProtocolError && e.code === "UNKNOWN_TYPE") {
        this.handlers.onUnsupported?.("<unknown>");
        return { sends: [] };
      }
      throw e;
    }
    if (this.phase === "greeting") {
      if (frame.type === "error") {
        const error = readControlError(frame.payload);
        this.phase = "closed";
        throw new ControlStateError("BAD_FIRST_FRAME", `\u5EFA\u8FDE\u88AB\u62D2\u7EDD\uFF1A${error.code} ${error.message}`);
      }
      if (frame.type !== "hello.ack") {
        throw new ControlStateError("BAD_FIRST_FRAME", `\u9996\u5E27\u5E94\u4E3A hello.ack\uFF0C\u5B9E\u9645 ${frame.type}`);
      }
      const ack = readHelloAck(frame.payload);
      this.phase = "ready";
      this.handlers.onReady?.(ack);
      return { sends: [], ack };
    }
    switch (frame.type) {
      case "ping":
        return { sends: [this.send("pong", { nonce: readPingPongNonce(frame.payload) })] };
      case "pong":
        return { sends: [] };
      case "error": {
        const error = readControlError(frame.payload);
        this.handlers.onServerError?.(error);
        return { sends: [], error };
      }
      case "connect.incoming":
        this.handlers.onConnectIncoming?.(readConnectIncoming(frame.payload));
        return { sends: [] };
      case "connect.accepted":
        this.handlers.onConnectAccepted?.(frame.payload);
        return { sends: [] };
      case "connect.rejected":
        this.handlers.onConnectRejected?.(frame.payload);
        return { sends: [] };
      case "secure.handshake":
        this.handlers.onSecureHandshake?.(readSecureHandshake(frame.payload));
        return { sends: [] };
      case "relay":
        this.handlers.onRelay?.(readRelay(frame.payload));
        return { sends: [] };
      case "hello.ack":
        return { sends: [] };
      default:
        if (UNSUPPORTED_TYPES.has(frame.type)) {
          this.handlers.onUnsupported?.(frame.type);
          return { sends: [] };
        }
        this.handlers.onUnsupported?.(frame.type);
        return { sends: [] };
    }
  }
  /** 标记关闭。之后 `send` / `handle` 都会抛错，避免对已死的连接写帧 */
  close() {
    this.phase = "closed";
  }
};

// dsh/messages.ts
var MAX_REMOTE_MESSAGE_BYTES = 1024 * 1024;
var MESSAGE_TYPES = [
  "rpc.request",
  "rpc.response",
  "rpc.error",
  "event"
];
var MESSAGE_TYPE_SET = new Set(MESSAGE_TYPES);
function createRpcRequest(method, params) {
  if (typeof method !== "string" || method.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", "RPC \u65B9\u6CD5\u540D\u4E0D\u80FD\u4E3A\u7A7A");
  }
  return {
    v: PROTOCOL_VERSION,
    id: newFrameId(),
    type: "rpc.request",
    timestamp: Date.now(),
    payload: { method, params }
  };
}
function encodeMessage(message, limitBytes = MAX_REMOTE_MESSAGE_BYTES) {
  const text = JSON.stringify(message);
  const bytes = ctx.codec.utf8Encode(text).length;
  if (bytes > limitBytes) {
    throw new ProtocolError("BAD_FRAME", `\u4E1A\u52A1\u6D88\u606F ${bytes} \u5B57\u8282\uFF0C\u8D85\u8FC7\u4E0A\u9650 ${limitBytes}`);
  }
  return text;
}
function isObject3(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
var RemoteCallError = class extends Error {
  constructor(code, message, details) {
    super(message);
    this.code = code;
    this.details = details;
  }
};
function unwrapRemoteResult(value) {
  if (!isObject3(value) || !("ok" in value)) return value;
  if (value.ok === true) return value.value;
  const error = value.error;
  if (value.ok !== false || !isObject3(error) || typeof error.code !== "string" || error.code.length === 0 || typeof error.message !== "string" || error.message.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", "\u8FDC\u7AEF\u8FD4\u56DE\u7684\u5931\u8D25\u7ED3\u679C\u683C\u5F0F\u975E\u6CD5");
  }
  throw new RemoteCallError(error.code, error.message, error.details);
}
function decodeMessage(text, limitBytes = MAX_REMOTE_MESSAGE_BYTES) {
  const bytes = ctx.codec.utf8Encode(text).length;
  if (bytes > limitBytes) {
    throw new ProtocolError("BAD_FRAME", `\u4E1A\u52A1\u6D88\u606F ${bytes} \u5B57\u8282\uFF0C\u8D85\u8FC7\u4E0A\u9650 ${limitBytes}`);
  }
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    throw new ProtocolError("NOT_JSON", "\u6536\u5230\u975E JSON \u7684\u4E1A\u52A1\u6D88\u606F");
  }
  if (!isObject3(value)) throw new ProtocolError("BAD_FRAME", "\u4E1A\u52A1\u6D88\u606F\u4E0D\u662F\u5BF9\u8C61");
  const { v, id, type, timestamp, payload } = value;
  if (v !== PROTOCOL_VERSION) throw new ProtocolError("BAD_FRAME", `\u534F\u8BAE\u7248\u672C\u4E0D\u7B26\uFF1A${String(v)}`);
  if (typeof id !== "string" || id.length === 0) throw new ProtocolError("BAD_FRAME", "\u4E1A\u52A1\u6D88\u606F\u7F3A\u5C11 id");
  if (typeof type !== "string" || !MESSAGE_TYPE_SET.has(type)) {
    throw new ProtocolError("UNKNOWN_TYPE", `\u672A\u77E5\u4E1A\u52A1\u6D88\u606F\u7C7B\u578B\uFF1A${String(type)}`);
  }
  if (typeof timestamp !== "number" || !Number.isInteger(timestamp) || timestamp <= 0) {
    throw new ProtocolError("BAD_FRAME", "\u4E1A\u52A1\u6D88\u606F\u65F6\u95F4\u6233\u975E\u6CD5");
  }
  if (!isObject3(payload)) throw new ProtocolError("BAD_FRAME", `\u4E1A\u52A1\u6D88\u606F ${type} \u7684 payload \u4E0D\u662F\u5BF9\u8C61`);
  return { v, id, type, timestamp, payload };
}
function requireString2(source, key, message) {
  const value = source[key];
  if (typeof value !== "string" || value.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", `${message} \u7F3A\u5C11\u5B57\u7B26\u4E32\u5B57\u6BB5 ${key}`);
  }
  return value;
}
function optionalInteger(source, key) {
  const value = source[key];
  return typeof value === "number" && Number.isInteger(value) && value >= 0 ? value : void 0;
}
function optionalString2(source, key) {
  const value = source[key];
  return typeof value === "string" && value.length > 0 ? value : void 0;
}
function readRpcRequest(payload) {
  return {
    method: requireString2(payload, "method", "rpc.request"),
    params: payload.params
  };
}
function readRpcResponse(payload) {
  return {
    requestId: requireString2(payload, "requestId", "rpc.response"),
    result: payload.result
  };
}
function readRpcError(payload) {
  return {
    requestId: requireString2(payload, "requestId", "rpc.error"),
    code: requireString2(payload, "code", "rpc.error"),
    message: requireString2(payload, "message", "rpc.error"),
    retryable: payload.retryable === true,
    details: payload.details
  };
}
function readEvent(payload) {
  return {
    event: requireString2(payload, "event", "event"),
    data: payload.data,
    seq: optionalInteger(payload, "seq"),
    sessionId: optionalString2(payload, "sessionId")
  };
}
function readInbound(text, limitBytes = MAX_REMOTE_MESSAGE_BYTES) {
  const message = decodeMessage(text, limitBytes);
  const payload = message.payload;
  switch (message.type) {
    case "rpc.response": {
      const response = readRpcResponse(payload);
      return { kind: "response", requestId: response.requestId, result: response.result };
    }
    case "rpc.error": {
      const error = readRpcError(payload);
      return {
        kind: "error",
        requestId: error.requestId,
        code: error.code,
        message: error.message,
        retryable: error.retryable,
        details: error.details
      };
    }
    case "event": {
      const event = readEvent(payload);
      return {
        kind: "event",
        event: event.event,
        data: event.data,
        seq: event.seq,
        sessionId: event.sessionId
      };
    }
    case "rpc.request": {
      const request = readRpcRequest(payload);
      return { kind: "request", method: request.method, params: request.params };
    }
  }
}
var RpcPending = class {
  constructor() {
    this.calls = /* @__PURE__ */ new Map();
  }
  get size() {
    return this.calls.size;
  }
  /** 登记一条请求。id 重复说明 id 生成出了问题，直接抛 */
  track(id, method) {
    if (this.calls.has(id)) {
      throw new ProtocolError("BAD_PAYLOAD", `RPC \u8BF7\u6C42 id \u91CD\u590D\uFF1A${id}`);
    }
    this.calls.set(id, { method, since: Date.now() });
  }
  has(id) {
    return this.calls.has(id);
  }
  /** 摘取并移除；对不认识的 id（已超时/已断开）返回 undefined */
  settle(id) {
    const call = this.calls.get(id);
    if (call === void 0) return void 0;
    this.calls.delete(id);
    return call;
  }
  /** 清空并返回被丢弃的条数。断线或关闭时调用，避免旧请求永远挂起 */
  dropAll() {
    const count = this.calls.size;
    this.calls.clear();
    return count;
  }
  /** 当前在途请求 id，断线恢复时用来逐条标记结果未知 */
  ids() {
    return Array.from(this.calls.keys());
  }
};

// dsh/sessions.ts
var REMOTE_CALL_METHOD = "harness.remote.call";
var SESSION_PROMPT_ENDPOINT = "session/prompt";
function isObject4(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function remoteCall(endpoint, args) {
  return createRpcRequest(REMOTE_CALL_METHOD, { endpoint, payload: { args } });
}
function createSessionListRequest() {
  return remoteCall("session/list", { _request: {} });
}
function createSessionCreateRequest(target = {}) {
  const request = {};
  if (target.workspaceId !== void 0) {
    request.workspaceId = target.workspaceId;
  } else if (target.cwd !== void 0) {
    request.cwd = target.cwd;
  }
  return remoteCall("session/create", { request });
}
function createSessionPromptRequest(sessionId, text, options = {}) {
  if (typeof sessionId !== "string" || sessionId.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", "sessionId \u4E0D\u80FD\u4E3A\u7A7A");
  }
  if (typeof text !== "string" || text.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", "prompt \u6587\u672C\u4E0D\u80FD\u4E3A\u7A7A");
  }
  const request = {
    requestId: options.requestId ?? newFrameId(),
    sessionId,
    mode: "queue",
    content: [{ type: "text", text }]
  };
  if (options.clientTimeZone !== void 0) {
    request.clientTimeZone = options.clientTimeZone;
  }
  return remoteCall(SESSION_PROMPT_ENDPOINT, { request });
}
function readSessionList(value) {
  if (!isObject4(value) || !Array.isArray(value.items)) return [];
  const sessions = [];
  for (const item of value.items) {
    const session = readSession(item);
    if (session !== void 0) sessions.push(session);
  }
  return sessions;
}
function readSession(value) {
  if (!isObject4(value)) return void 0;
  const { sessionId, updatedAt, running, blank } = value;
  if (typeof sessionId !== "string" || sessionId.length === 0) return void 0;
  if (typeof updatedAt !== "number" || !Number.isFinite(updatedAt)) return void 0;
  if (typeof running !== "boolean" || typeof blank !== "boolean") return void 0;
  const session = { sessionId, updatedAt, running, blank };
  const title = readTitle(value);
  if (title !== void 0) session.title = title;
  if (typeof value.parentSessionId === "string" && value.parentSessionId.length > 0) {
    session.parentSessionId = value.parentSessionId;
  }
  if (typeof value.cwd === "string" && value.cwd.length > 0) session.cwd = value.cwd;
  return session;
}
function readTitle(value) {
  if (typeof value.title === "string" && value.title.length > 0) return value.title;
  const projections = value.projections;
  if (!isObject4(projections) || !isObject4(projections.values)) return void 0;
  const projected = projections.values.title;
  return typeof projected === "string" && projected.length > 0 ? projected : void 0;
}
function readSessionCreateResult(value) {
  const sessionId = isObject4(value) ? value.sessionId : void 0;
  if (typeof sessionId !== "string" || sessionId.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", "session/create \u672A\u8FD4\u56DE sessionId");
  }
  return sessionId;
}

// dsh/stream.ts
var STREAM_OPEN_METHOD = "harness.remote.stream.open";
var STREAM_CLOSE_METHOD = "harness.remote.stream.close";
var SESSION_FOLLOW_ENDPOINT = "session/follow";
var EVENT_FRAME = "harness.remote.frame";
var EVENT_STREAM_CLOSED = "harness.remote.stream.closed";
var MAX_SESSION_FOLLOW_MESSAGES = 60;
var STREAM_CLOSE_REASONS = [
  "cancelled",
  "completed",
  "failed",
  "peer-disconnected"
];
var CLOSE_REASON_SET = new Set(STREAM_CLOSE_REASONS);
function isObject5(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function requireNonEmpty(value, name) {
  if (typeof value !== "string" || value.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", `${name} \u4E0D\u80FD\u4E3A\u7A7A`);
  }
  return value;
}
function createStreamOpenRequest(streamId, endpoint, args) {
  requireNonEmpty(streamId, "streamId");
  requireNonEmpty(endpoint, "endpoint");
  return createRpcRequest(STREAM_OPEN_METHOD, {
    streamId,
    endpoint,
    payload: { args }
  });
}
function createSessionFollowRequest(streamId, sessionId, maxMessages = MAX_SESSION_FOLLOW_MESSAGES) {
  requireNonEmpty(sessionId, "sessionId");
  if (!Number.isInteger(maxMessages) || maxMessages <= 0) {
    throw new ProtocolError("BAD_PAYLOAD", `maxMessages \u5FC5\u987B\u662F\u6B63\u6574\u6570\uFF1A${String(maxMessages)}`);
  }
  return createStreamOpenRequest(streamId, SESSION_FOLLOW_ENDPOINT, {
    request: {
      address: { kind: "session", sessionId },
      maxMessages
    }
  });
}
function createStreamCloseRequest(streamId) {
  return createRpcRequest(STREAM_CLOSE_METHOD, { streamId: requireNonEmpty(streamId, "streamId") });
}
function parseStreamEvent(event, data) {
  if (event !== EVENT_FRAME && event !== EVENT_STREAM_CLOSED) return void 0;
  if (!isObject5(data)) {
    throw new ProtocolError("BAD_PAYLOAD", `${event} \u7684 data \u4E0D\u662F\u5BF9\u8C61`);
  }
  const streamId = requireNonEmpty(data.streamId, "streamId");
  if (event === EVENT_FRAME) {
    if (data.transfer !== void 0) {
      throw new ProtocolError("BAD_FRAME", "\u6536\u5230\u672A\u542F\u7528\u7684 stream transfer \u63CF\u8FF0\u5E27");
    }
    return {
      kind: "frame",
      streamId,
      hasValue: data.hasValue === true,
      value: data.value
    };
  }
  const reason = data.reason;
  if (typeof reason !== "string" || !CLOSE_REASON_SET.has(reason)) {
    throw new ProtocolError("BAD_PAYLOAD", `\u672A\u77E5\u7684\u6D41\u7EC8\u6B62\u539F\u56E0\uFF1A${String(reason)}`);
  }
  const signal = {
    kind: "closed",
    streamId,
    reason
  };
  if (reason === "failed" && isObject5(data.failure)) {
    signal.failure = {
      code: typeof data.failure.code === "string" && data.failure.code.length > 0 ? data.failure.code : "internal",
      message: typeof data.failure.message === "string" && data.failure.message.length > 0 ? data.failure.message : "\u8FDC\u7AEF\u6D41\u5931\u8D25",
      details: isObject5(data.failure.details) ? data.failure.details : {}
    };
  }
  return signal;
}
var AssistantStreamAggregator = class {
  constructor() {
    this.textValue = "";
    this.reasoningValue = "";
    this.applied = 0;
  }
  /** 已拼接的正文 */
  get text() {
    return this.textValue;
  }
  /** 已拼接的推理内容（与正文分开，界面可折叠展示） */
  get reasoning() {
    return this.reasoningValue;
  }
  /** 实际生效的增量条数，用于判断流是否真的在推进 */
  get chunkCount() {
    return this.applied;
  }
  /** 清空，用于开始新一轮回复 */
  reset() {
    this.textValue = "";
    this.reasoningValue = "";
    this.applied = 0;
  }
  /**
   * 输入一条 `session/follow` 的 item。
   *
   * 同时兼容两种形态：
   * - 普通历史项：`{ type: 'event', event: { type: 'assistant/chunk', data: { chunk } } }`
   * - 流式加速帧：`{ type: 'assistant-stream', frame: { type: 'chunk', chunk } }`
   *
   * @returns 是否产生了新的文本增量
   */
  pushItem(value) {
    if (!isObject5(value)) return false;
    if (value.type === "assistant-stream") {
      const frame = value.frame;
      if (!isObject5(frame) || frame.type !== "chunk") return false;
      return this.applyChunk(frame.chunk);
    }
    if (value.type !== "event" || !isObject5(value.event)) return false;
    const event = value.event;
    if (event.type !== "assistant/chunk" || !isObject5(event.data)) return false;
    return this.applyChunk(event.data.chunk);
  }
  /** 应用单个 chunk；非文本增量返回 false */
  applyChunk(chunk) {
    if (!isObject5(chunk) || typeof chunk.type !== "string") return false;
    if (typeof chunk.text !== "string") return false;
    if (chunk.type === "text-delta") {
      this.textValue += chunk.text;
      this.applied += 1;
      return true;
    }
    if (chunk.type === "reasoning-delta") {
      this.reasoningValue += chunk.text;
      this.applied += 1;
      return true;
    }
    return false;
  }
};

// dsh/interactions.ts
var EVENTS_ENDPOINT = "$events";
var EVENTS_RESULT_ENDPOINT = "$events/result";
function isObject6(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function requireString3(source, key, message) {
  const value = source[key];
  if (typeof value !== "string" || value.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", `${message} \u7F3A\u5C11\u5B57\u7B26\u4E32\u5B57\u6BB5 ${key}`);
  }
  return value;
}
function optionalString3(source, key) {
  const value = source[key];
  return typeof value === "string" && value.length > 0 ? value : void 0;
}
function createEventsStreamRequest(streamId) {
  return createStreamOpenRequest(streamId, EVENTS_ENDPOINT, {});
}
function parseEventsFrame(value) {
  if (!isObject6(value) || typeof value.type !== "string") {
    throw new ProtocolError("BAD_PAYLOAD", "$events \u5E27\u4E0D\u662F\u5BF9\u8C61\u6216\u7F3A\u5C11 type");
  }
  if (value.type === "ready") {
    return { kind: "ready", clientId: requireString3(value, "clientId", "ready") };
  }
  if (value.type === "cancel") {
    return { kind: "cancel", eventId: requireString3(value, "eventId", "cancel") };
  }
  if (value.type !== "waterfall") return void 0;
  const event = value.event;
  if (event !== "approval/request" && event !== "user-questions/request") return void 0;
  const eventId = requireString3(value, "eventId", "waterfall");
  const sessionId = requireString3(value, "agentId", "waterfall");
  const request = value.request;
  if (!isObject6(request)) throw new ProtocolError("BAD_PAYLOAD", "waterfall \u7F3A\u5C11 request \u5BF9\u8C61");
  if (event === "approval/request") {
    const approval = {
      kind: "approval",
      eventId,
      sessionId,
      toolName: optionalString3(request, "toolName") ?? "Harness tool"
    };
    const callId = optionalString3(request, "callId");
    const reason = optionalString3(request, "reason");
    if (callId !== void 0) approval.callId = callId;
    if (reason !== void 0) approval.reason = reason;
    return approval;
  }
  return {
    kind: "question",
    eventId,
    sessionId,
    questions: readQuestions(request.questions)
  };
}
function readQuestions(value) {
  if (!Array.isArray(value)) return [];
  const questions = [];
  for (const item of value) {
    const question = readQuestion(item);
    if (question !== void 0) questions.push(question);
  }
  return questions;
}
function readQuestion(value) {
  if (!isObject6(value)) return void 0;
  const id = optionalString3(value, "id");
  const text = optionalString3(value, "question");
  if (id === void 0 || text === void 0) return void 0;
  const question = { id, question: text };
  const detail = optionalString3(value, "detail");
  const header = optionalString3(value, "header");
  if (detail !== void 0) question.detail = detail;
  if (header !== void 0) question.header = header;
  if (value.multiSelect === true) question.multiSelect = true;
  if (Array.isArray(value.options)) {
    const options = [];
    for (const option of value.options) {
      if (!isObject6(option)) continue;
      const label = optionalString3(option, "label");
      if (label === void 0) continue;
      const description = optionalString3(option, "description");
      options.push(description === void 0 ? { label } : { label, description });
    }
    question.options = options;
  }
  return question;
}
function createResultRequest(clientId, eventId, outcome) {
  if (typeof clientId !== "string" || clientId.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", "$events \u5C1A\u672A ready\uFF0C\u7F3A\u5C11 clientId");
  }
  if (typeof eventId !== "string" || eventId.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", "eventId \u4E0D\u80FD\u4E3A\u7A7A");
  }
  return createRpcRequest("harness.remote.call", {
    endpoint: EVENTS_RESULT_ENDPOINT,
    payload: { args: { clientId, eventId, outcome } }
  });
}
function createApprovalResultRequest(clientId, eventId, outcome) {
  if (outcome !== "allowed-once" && outcome !== "rejected") {
    throw new ProtocolError("BAD_PAYLOAD", `\u672A\u77E5\u5BA1\u6279\u7ED3\u679C\uFF1A${String(outcome)}`);
  }
  return createResultRequest(clientId, eventId, { kind: "result", value: outcome });
}
function createQuestionResultRequest(clientId, eventId, answers) {
  const cleaned = readAnswers(answers);
  return createResultRequest(clientId, eventId, { kind: "result", value: { answers: cleaned } });
}
function createRejectedResultRequest(clientId, eventId, code, message, name = "UserQuestionError") {
  if (typeof code !== "string" || code.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", "\u62D2\u7EDD\u539F\u56E0 code \u4E0D\u80FD\u4E3A\u7A7A");
  }
  if (typeof message !== "string" || message.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", "\u62D2\u7EDD\u539F\u56E0 message \u4E0D\u80FD\u4E3A\u7A7A");
  }
  return createResultRequest(clientId, eventId, {
    kind: "rejected",
    error: { name, code, message }
  });
}
function readAnswers(answers) {
  if (!Array.isArray(answers) || answers.length === 0) {
    throw new ProtocolError("BAD_PAYLOAD", "\u95EE\u7B54\u56DE\u590D\u81F3\u5C11\u9700\u8981\u4E00\u6761\u7B54\u6848");
  }
  return answers.map((answer) => {
    if (!isObject6(answer)) throw new ProtocolError("BAD_PAYLOAD", "\u7B54\u6848\u4E0D\u662F\u5BF9\u8C61");
    const id = answer.id;
    if (typeof id !== "string" || id.length === 0) {
      throw new ProtocolError("BAD_PAYLOAD", "\u7B54\u6848\u7F3A\u5C11\u95EE\u9898 id");
    }
    const selected = answer.selected;
    if (!Array.isArray(selected) || selected.some((item) => typeof item !== "string")) {
      throw new ProtocolError("BAD_PAYLOAD", `\u95EE\u9898 ${id} \u7684 selected \u5FC5\u987B\u662F\u5B57\u7B26\u4E32\u6570\u7EC4`);
    }
    const cleaned = { id, selected: selected.slice() };
    if (typeof answer.custom === "string" && answer.custom.length > 0) {
      cleaned.custom = answer.custom;
    }
    return cleaned;
  });
}

// dsh/recovery.ts
var DEFAULT_RECONNECT_POLICY = {
  baseDelayMs: 500,
  maxDelayMs: 3e4,
  factor: 2
};
function nextReconnectDelay(attempt, policy = DEFAULT_RECONNECT_POLICY) {
  if (policy.baseDelayMs <= 0 || policy.maxDelayMs <= 0 || policy.factor < 1) {
    throw new ProtocolError("BAD_PAYLOAD", "\u91CD\u8FDE\u7B56\u7565\u53C2\u6570\u975E\u6CD5");
  }
  const normalized = Number.isInteger(attempt) && attempt >= 1 ? attempt : 1;
  const raw = policy.baseDelayMs * Math.pow(policy.factor, normalized - 1);
  return Math.min(Math.round(raw), policy.maxDelayMs);
}
var ConnectionEpoch = class {
  constructor() {
    this.value = 0;
  }
  /** 当前代次；尚未连接时为 0 */
  get current() {
    return this.value;
  }
  /** 开始新一代连接，返回新代次 */
  begin() {
    this.value += 1;
    return this.value;
  }
  /** 回调携带的代次是否仍然有效 */
  isCurrent(epoch) {
    return epoch === this.value && epoch > 0;
  }
};
function planRecovery(state = {}) {
  const actions = [];
  for (const requestId of state.pendingRequestIds ?? []) {
    if (typeof requestId === "string" && requestId.length > 0) {
      actions.push({ kind: "failPendingRequest", requestId, code: "UNKNOWN_OUTCOME" });
    }
  }
  actions.push({ kind: "refreshSessions" });
  if (typeof state.activeSessionId === "string" && state.activeSessionId.length > 0) {
    actions.push({ kind: "reopenFollow", sessionId: state.activeSessionId });
  }
  actions.push({ kind: "reopenEvents" });
  for (const eventId of state.pendingApprovalEventIds ?? []) {
    if (typeof eventId === "string" && eventId.length > 0) {
      actions.push({ kind: "dropLocalApproval", eventId });
    }
  }
  return actions;
}

// dsh/workspaces.ts
var WORKSPACE_FOLLOW_ENDPOINT = "workspace/follow";
function isObject7(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function createWorkspaceFollowRequest(streamId) {
  return createStreamOpenRequest(streamId, WORKSPACE_FOLLOW_ENDPOINT, {});
}
function readWorkspaceBaseline(frame) {
  if (!isObject7(frame) || frame.type !== "baseline" || !isObject7(frame.value)) {
    throw new ProtocolError("BAD_PAYLOAD", "workspace/follow \u9996\u5E27\u4E0D\u662F baseline");
  }
  return readWorkspaceList(frame.value);
}
function readWorkspaceList(value) {
  if (!isObject7(value)) {
    return { items: [], archivedSessionIds: [], pinnedSessionIds: [] };
  }
  const items = [];
  if (Array.isArray(value.items)) {
    for (const item of value.items) {
      const workspace = readWorkspace(item);
      if (workspace !== void 0) items.push(workspace);
    }
  }
  return {
    items,
    archivedSessionIds: readStringArray(value.archivedSessionIds),
    pinnedSessionIds: readStringArray(value.pinnedSessionIds)
  };
}
function readWorkspace(value) {
  if (!isObject7(value)) return void 0;
  const workspaceId = value.workspaceId;
  if (typeof workspaceId !== "string" || workspaceId.length === 0) return void 0;
  const workspace = {
    workspaceId,
    path: typeof value.path === "string" ? value.path : "",
    title: typeof value.title === "string" ? value.title : "",
    sessionIds: readStringArray(value.sessionIds)
  };
  if (typeof value.createdAt === "string" && value.createdAt.length > 0) workspace.createdAt = value.createdAt;
  if (typeof value.updatedAt === "string" && value.updatedAt.length > 0) workspace.updatedAt = value.updatedAt;
  return workspace;
}
function readStringArray(value) {
  if (!Array.isArray(value)) return [];
  const out = [];
  for (const item of value) {
    if (typeof item === "string" && item.length > 0) out.push(item);
  }
  return out;
}

// dsh/conversation.ts
var ConversationController = class {
  constructor(handlers = {}) {
    this.handlers = handlers;
    /** 增量聚合器，界面可直接读 `text` / `reasoning` */
    this.aggregator = new AssistantStreamAggregator();
    /** RPC 请求关联表 */
    this.pending = new RpcPending();
    this.promptIds = /* @__PURE__ */ new Set();
    this.interactions = /* @__PURE__ */ new Map();
  }
  get sessionId() {
    return this.sessionValue;
  }
  get clientId() {
    return this.clientIdValue;
  }
  /** 是否还有未返回的 prompt（含排队中的） */
  get streaming() {
    return this.promptIds.size > 0;
  }
  get pendingInteractions() {
    return Array.from(this.interactions.values());
  }
  /**
   * 切换到某个会话并开始跟随。
   *
   * 每次调用都重置聚合文本：历史快照与旧会话的增量混在一起，
   * 界面会显示两段对话。
   */
  attachSession(sessionId, streamId) {
    const request = createSessionFollowRequest(streamId, sessionId);
    this.sessionValue = sessionId;
    this.followStreamId = streamId;
    this.aggregator.reset();
    return request;
  }
  /** 打开 Host 的审批/问答事件流 */
  openEvents(streamId) {
    this.eventsStreamId = streamId;
    return createEventsStreamRequest(streamId);
  }
  /**
   * 发送一条 Prompt。
   *
   * 重发同一条消息时必须显式传 `requestId`：Host 用它做幂等去重，
   * 不传会生成新的 id，用户会看到两条一样的提问。
   */
  sendPrompt(text, requestId) {
    if (this.sessionValue === void 0) {
      throw new ProtocolError("BAD_PAYLOAD", "\u5C1A\u672A\u9009\u62E9\u4F1A\u8BDD");
    }
    const message = createSessionPromptRequest(this.sessionValue, text, { requestId });
    this.pending.track(message.id, "session/prompt");
    this.promptIds.add(message.id);
    return message;
  }
  /** 喂入一条 WS 文本帧（控制帧之外的业务消息） */
  handleMessage(text) {
    const inbound = readInbound(text);
    if (inbound.kind === "response") {
      this.settle(inbound.requestId);
      return;
    }
    if (inbound.kind === "error") {
      this.settle(inbound.requestId);
      this.handlers.onRequestFailed?.(inbound.requestId, inbound.code, inbound.message);
      return;
    }
    if (inbound.kind === "event") {
      const signal = parseStreamEvent(inbound.event, inbound.data);
      if (signal !== void 0) this.handleStreamSignal(signal);
    }
  }
  settle(requestId) {
    if (this.pending.settle(requestId) === void 0) return;
    this.promptIds.delete(requestId);
  }
  handleStreamSignal(signal) {
    if (signal === void 0) return;
    if (signal.kind === "closed") {
      if (signal.streamId !== this.followStreamId) return;
      this.promptIds.clear();
      this.handlers.onStreamClosed?.(signal.reason, signal.failure);
      return;
    }
    if (signal.streamId === this.followStreamId) {
      if (!signal.hasValue) return;
      if (this.aggregator.pushItem(signal.value)) {
        this.handlers.onText?.(this.aggregator.text, this.aggregator.reasoning);
      }
      return;
    }
    if (signal.streamId === this.eventsStreamId && signal.hasValue) {
      this.handleEventsFrame(signal.value);
    }
  }
  /** 处理 `$events` 的 ready / waterfall / cancel */
  handleEventsFrame(value) {
    const event = parseEventsFrame(value);
    if (event === void 0) return;
    if (event.kind === "ready") {
      this.clientIdValue = event.clientId;
      this.handlers.onClientReady?.(event.clientId);
      return;
    }
    if (event.kind === "cancel") {
      this.interactions.delete(event.eventId);
      this.handlers.onInteractionCancelled?.(event.eventId);
      return;
    }
    this.interactions.set(event.eventId, event);
    if (event.kind === "approval") this.handlers.onApproval?.(event);
    else this.handlers.onQuestion?.(event);
  }
  /** 回复审批；回复后本地不再保留该请求 */
  approve(eventId, outcome) {
    const request = createApprovalResultRequest(this.requireClientId(), eventId, outcome);
    this.interactions.delete(eventId);
    return request;
  }
  answerQuestion(eventId, answers) {
    const request = createQuestionResultRequest(this.requireClientId(), eventId, answers);
    this.interactions.delete(eventId);
    return request;
  }
  /** 本地无法处理时显式拒绝，避免 Host 一直等 */
  rejectInteraction(eventId, code, message) {
    const request = createRejectedResultRequest(this.requireClientId(), eventId, code, message);
    this.interactions.delete(eventId);
    return request;
  }
  requireClientId() {
    if (this.clientIdValue === void 0) {
      throw new ProtocolError("BAD_PAYLOAD", "$events \u5C1A\u672A ready\uFF0C\u4E0D\u80FD\u56DE\u590D\u5BA1\u6279\u6216\u95EE\u7B54");
    }
    return this.clientIdValue;
  }
  /**
   * 断线处理：生成恢复计划并清空本地瞬态。
   *
   * `clientId` 与流 id 都作废——重连后是新一代连接，旧 id 回复会被 Host 拒绝。
   */
  disconnect() {
    const plan = planRecovery({
      activeSessionId: this.sessionValue,
      pendingRequestIds: this.pending.ids(),
      pendingApprovalEventIds: Array.from(this.interactions.keys())
    });
    this.pending.dropAll();
    this.promptIds.clear();
    this.interactions.clear();
    this.clientIdValue = void 0;
    this.followStreamId = void 0;
    this.eventsStreamId = void 0;
    return plan;
  }
};

// dsh/hostSession.ts
var HostSessionError = class extends Error {
  constructor(kind, code, message) {
    super(message);
    this.kind = kind;
    this.code = code;
  }
};
var HostSession = class {
  constructor(handlers, options, target) {
    this.handlers = handlers;
    this.options = options;
    this.target = target;
    this.connectionId = "";
    this.ready = false;
    this.closed = false;
    this.starting = false;
    this.sessionIdValue = "";
    this.followStreamId = "";
    this.eventsStreamId = "";
    this.promptIds = /* @__PURE__ */ new Set();
    /** 等待 `harness.remote.call` 响应的回调表 */
    this.waiting = /* @__PURE__ */ new Map();
    this.conversation = new ConversationController({
      onText: (text, reasoning) => {
        this.handlers.onAssistantText(text);
        if (reasoning.length > 0) this.handlers.onReasoning(reasoning);
      },
      onApproval: (request) => {
        this.handlers.onApproval(request.eventId, request.toolName, request.reason ?? "");
      },
      onQuestion: (request) => {
        const first = request.questions[0];
        this.handlers.onQuestion(
          request.eventId,
          first === void 0 ? "\u9700\u8981\u4F60\u786E\u8BA4" : first.question
        );
      },
      onInteractionCancelled: (eventId) => this.handlers.onInteractionCancelled(eventId),
      onRequestFailed: (requestId, code, message) => {
        this.promptIds.delete(requestId);
        this.handlers.onRequestFailed(code, message);
      }
    });
    this.control = new ControlChannel({
      onSecureHandshake: (frame) => this.onSecureHandshake(frame),
      onRelay: (frame) => this.onRelay(frame),
      onConnectRejected: (payload) => {
        const code = typeof payload.code === "string" ? payload.code : "CONNECT_REJECTED";
        this.fail(new HostSessionError("remote", code, "\u7535\u8111\u62D2\u7EDD\u4E86\u8FD9\u6B21\u8FDE\u63A5"));
      },
      onServerError: (error) => {
        ctx.log.w("dsh", `\u670D\u52A1\u7AEF\u9519\u8BEF\uFF1A${error.code} ${error.message}`);
      }
    });
  }
  get active() {
    return this.ready && !this.closed;
  }
  get sessionId() {
    return this.sessionIdValue;
  }
  // -----------------------------------------------------------------------
  // 建连前准备
  // -----------------------------------------------------------------------
  /**
   * 取凭据、必要时刷新 token、确认目标 Host 在线。
   *
   * 拆成静态方法：失败时根本没有 `HostSession` 可谈，
   * 塞进实例方法会让构造函数需要容纳一堆尚未就绪的字段。
   *
   * @throws {HostSessionError} 凭据缺失、Host 不在线等
   */
  static async prepare(hostDeviceId) {
    const auth = loadAuth();
    const identity = loadIdentity();
    if (auth === null || identity === null) {
      throw new HostSessionError("auth", "NOT_LOGGED_IN", "\u8FD8\u6CA1\u6709\u767B\u5F55 DSH \u8D26\u53F7");
    }
    if (hostDeviceId.length === 0) {
      throw new HostSessionError("not-found", "HOST_REQUIRED", "\u8FD8\u6CA1\u6709\u9009\u62E9\u8981\u8FDE\u63A5\u7684\u7535\u8111");
    }
    const stored = loadDeviceCredentials();
    if (stored === null) {
      throw new HostSessionError("auth", "DEVICE_NOT_REGISTERED", "\u672C\u673A\u5C1A\u672A\u6CE8\u518C\u4E3A\u8BBE\u5907\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55");
    }
    const credentials = needsRefresh(stored.deviceAccessTokenExpiresAt) ? await refreshDevice(auth.serverUrl, auth.deviceId, stored.deviceRefreshToken) : stored;
    if (credentials !== stored) saveDeviceCredentials(credentials);
    let hosts;
    try {
      hosts = await listHostDevices(auth.serverUrl, credentials.deviceAccessToken);
    } catch (error) {
      throw toSessionError(error, "network");
    }
    const host = hosts.find((item) => item.deviceId === hostDeviceId);
    if (host === void 0) {
      throw new HostSessionError("not-found", "HOST_NOT_FOUND", "\u8FD9\u53F0\u7535\u8111\u4E0D\u5728\u4F60\u7684\u8D26\u53F7\u4E0B");
    }
    if (!host.online) {
      throw new HostSessionError("offline", "HOST_OFFLINE", `${host.name} \u5F53\u524D\u79BB\u7EBF`);
    }
    return {
      host,
      identity,
      serverUrl: auth.serverUrl,
      deviceId: auth.deviceId,
      accessToken: credentials.deviceAccessToken
    };
  }
  // -----------------------------------------------------------------------
  // 建连
  // -----------------------------------------------------------------------
  /**
   * WS → hello → connect.request → 启动泵。
   *
   * Noise 由泵在收到 `secure.handshake` 时推进：握手帧和业务消息走同一条
   * 读循环，单独再开一个 `recv` 会直接触发宿主「同一连接只允许一个 recv」。
   */
  async open() {
    try {
      this.wsId = await ctx.ws.connect(deriveWsUrl(this.target.serverUrl));
    } catch (error) {
      this.fail(new HostSessionError("network", "WS_CONNECT_FAILED", "\u65E0\u6CD5\u8FDE\u63A5\u670D\u52A1\u5668\uFF0C\u8BF7\u68C0\u67E5\u7F51\u7EDC"));
      return false;
    }
    try {
      this.ctxSend(this.control.createHello({
        deviceId: this.target.deviceId,
        accessToken: this.target.accessToken,
        role: "client"
      }));
      const acked = await this.readUntilHelloAck();
      if (!acked) {
        this.fail(new HostSessionError("network", "WS_CLOSED", "\u8FDE\u63A5\u5728\u63E1\u624B\u5B8C\u6210\u524D\u5C31\u65AD\u5F00\u4E86"));
        return false;
      }
      this.ctxSend(this.control.send("connect.request", {
        hostDeviceId: this.target.host.deviceId,
        protocols: [1],
        transports: ["relay"]
      }));
      void this.pump();
      return true;
    } catch (error) {
      this.fail(toSessionError(error, "protocol"));
      return false;
    }
  }
  /** 读帧直到 `hello.ack`。之前收到的帧不处理：业务还没开始 */
  async readUntilHelloAck() {
    const wsId = this.requireWs();
    for (; ; ) {
      const frame = await ctx.ws.recv(wsId);
      if (frame.type === "close") return false;
      if (frame.type !== "text") continue;
      const step = this.control.handle(frame.data);
      for (const send of step.sends) this.ctxSend(send);
      if (step.ack !== void 0) {
        ctx.log.i("dsh", `\u5DF2\u8FDE\u63A5\u670D\u52A1\u5668 ${step.ack.serverVersion}`);
        return true;
      }
    }
  }
  // -----------------------------------------------------------------------
  // 泵
  // -----------------------------------------------------------------------
  async pump() {
    const wsId = this.requireWs();
    try {
      for (; ; ) {
        const frame = await ctx.ws.recv(wsId);
        if (frame.type === "close") {
          this.finish("\u4E0E\u7535\u8111\u7684\u8FDE\u63A5\u5DF2\u65AD\u5F00");
          return;
        }
        if (frame.type !== "text") continue;
        const step = this.control.handle(frame.data);
        for (const send of step.sends) this.ctxSend(send);
        if (step.error !== void 0 && !this.ready) {
          this.fail(new HostSessionError("remote", step.error.code, step.error.message));
          return;
        }
      }
    } catch (error) {
      if (this.closed) return;
      this.fail(toSessionError(error, "network"));
    }
  }
  // -----------------------------------------------------------------------
  // Noise
  // -----------------------------------------------------------------------
  /**
   * 收到 `secure.handshake`：按 `canWriteHandshake` 决定读还是写。
   *
   * 用「谁该写就写谁」而不是按 `step` 分支：step 是对方的编号，
   * 而 Noise 的顺序约束才是权威，混用两套判断迟早会错一次。
   */
  onSecureHandshake(frame) {
    if (this.closed) return;
    this.connectionId = frame.connectionId;
    if (this.noise === void 0) {
      this.noise = new NoiseIkSession({
        role: "initiator",
        localPrivateKey: this.target.identity.privateKey,
        localPublicKey: this.target.identity.publicKey,
        remotePublicKey: this.target.host.identityKey,
        prologue: createNoisePrologue(
          frame.connectionId,
          this.target.host.deviceId,
          this.target.deviceId
        )
      });
    }
    try {
      if (frame.step === 1) {
        this.noise.readHandshake(fromBase64Url(frame.data));
      }
      if (this.noise.canWriteHandshake) {
        const reply = this.noise.writeHandshake();
        this.ctxSend(this.control.send("secure.handshake", {
          connectionId: this.connectionId,
          targetDeviceId: this.target.host.deviceId,
          step: 2,
          data: toBase64Url(reply)
        }));
      }
    } catch (error) {
      this.fail(toSessionError(error, "protocol"));
      return;
    }
    if (this.noise.complete) {
      ctx.log.i("dsh", "\u5B89\u5168\u901A\u9053\u5DF2\u5EFA\u7ACB");
      void this.startConversation();
    }
  }
  // -----------------------------------------------------------------------
  // 会话
  // -----------------------------------------------------------------------
  /** 握手完成后进入真正的会话：选/建会话、跟随、打开事件流 */
  async startConversation() {
    if (this.starting || this.ready) return;
    this.starting = true;
    try {
      const wanted = this.options.sessionId ?? "";
      if (wanted.length === 0) {
        const result = await this.rpc(createSessionCreateRequest());
        this.sessionIdValue = readSessionCreateResult(unwrapRemoteResult(result));
      } else {
        this.sessionIdValue = wanted;
      }
      this.followStreamId = `follow-${this.sessionIdValue}`;
      this.sendBusiness(this.conversation.attachSession(this.sessionIdValue, this.followStreamId));
      this.eventsStreamId = "events-1";
      this.sendBusiness(this.conversation.openEvents(this.eventsStreamId));
      this.ready = true;
      this.handlers.onReady(this.target.host.name, this.sessionIdValue);
    } catch (error) {
      this.fail(toSessionError(error, "remote"));
    }
  }
  /**
   * 发一条 `harness.remote.call` 并等响应。
   *
   * 自己维护等待表而不是走编排器：后者只跟踪会话相关请求，且结算依赖泵
   * 把响应喂进去——在 `ready` 之前调用会永远等不到。
   *
   * 超时不在这里做：QuickJS 没有 `setTimeout`，宿主的「Promise 长时间不落地」
   * 机制会放弃这次调用（见 `JsSourcePlugin.invoke`）。
   */
  rpc(message) {
    const requestId = message.id;
    this.sendBusiness(message);
    return new Promise((resolve, reject) => {
      this.waiting.set(requestId, { resolve, reject });
    });
  }
  handleBusiness(text) {
    let inbound;
    try {
      inbound = readInbound(text);
    } catch (error) {
      ctx.log.w("dsh", `\u4E1A\u52A1\u6D88\u606F\u65E0\u6CD5\u89E3\u6790\uFF1A${error instanceof Error ? error.message : String(error)}`);
      return;
    }
    if (inbound.kind === "response" || inbound.kind === "error") {
      const waiter = this.waiting.get(inbound.requestId);
      if (waiter !== void 0) {
        this.waiting.delete(inbound.requestId);
        if (inbound.kind === "response") waiter.resolve(inbound.result);
        else waiter.reject(new HostSessionError("remote", inbound.code, inbound.message));
      }
    }
    this.conversation.handleMessage(text);
  }
  // -----------------------------------------------------------------------
  // 业务入口（供 main.ts 的四条会话函数使用）
  // -----------------------------------------------------------------------
  /** 发一条用户消息；返回 false 表示连接不可用 */
  sendPrompt(text) {
    if (!this.active) return false;
    const message = this.conversation.sendPrompt(text);
    this.promptIds.add(message.id);
    this.sendBusiness(message);
    return true;
  }
  approve(eventId, allowed) {
    if (!this.active) return false;
    this.sendBusiness(this.conversation.approve(eventId, allowed ? "allowed-once" : "rejected"));
    return true;
  }
  answer(eventId, questionId, selected) {
    if (!this.active) return false;
    this.sendBusiness(
      this.conversation.answerQuestion(eventId, [{ id: questionId, selected }])
    );
    return true;
  }
  /** 关闭跟随流。协议没有「取消一轮生成」的 RPC，只能停本地跟随 */
  stop() {
    if (!this.active || this.followStreamId.length === 0) return;
    this.sendBusiness(
      createRpcRequest("harness.remote.stream.close", { streamId: this.followStreamId })
    );
    this.promptIds.clear();
  }
  /** 关闭连接。可重复调用 */
  close() {
    if (this.closed) return;
    this.closed = true;
    this.ready = false;
    this.noise?.destroy();
    this.noise = void 0;
    for (const [, waiter] of this.waiting) {
      waiter.reject(new HostSessionError("network", "CLOSED", "\u8FDE\u63A5\u5DF2\u5173\u95ED"));
    }
    this.waiting.clear();
    const wsId = this.wsId;
    this.wsId = void 0;
    if (wsId !== void 0) {
      try {
        ctx.ws.close(wsId, 1e3, "client done");
      } catch {
      }
    }
  }
  // -----------------------------------------------------------------------
  // 发送
  // -----------------------------------------------------------------------
  onRelay(frame) {
    const session = this.noise;
    if (session === void 0 || !session.complete) {
      ctx.log.w("dsh", "\u6536\u5230 relay \u4F46\u5B89\u5168\u901A\u9053\u5C1A\u672A\u5EFA\u7ACB\uFF0C\u5DF2\u4E22\u5F03");
      return;
    }
    let plaintext;
    try {
      plaintext = fromUtf8(session.decrypt(fromBase64Url(frame.ciphertext)));
    } catch (error) {
      this.fail(new HostSessionError("protocol", "DECRYPT_FAILED", "\u6D88\u606F\u89E3\u5BC6\u5931\u8D25\uFF0C\u8FDE\u63A5\u4E0D\u53EF\u7528"));
      return;
    }
    this.handleBusiness(plaintext);
  }
  ctxSend(text) {
    const wsId = this.wsId;
    if (wsId === void 0) throw new HostSessionError("network", "WS_CLOSED", "\u8FDE\u63A5\u5DF2\u5173\u95ED");
    ctx.ws.send(wsId, text);
  }
  /** 业务消息必须装进 relay：先加密，再作为控制帧发出去 */
  sendBusiness(message) {
    const session = this.noise;
    if (session === void 0 || !session.complete) {
      throw new HostSessionError("network", "NOT_READY", "\u5B89\u5168\u901A\u9053\u5C1A\u672A\u5C31\u7EEA");
    }
    const ciphertext = session.encrypt(utf8(encodeMessage(message)));
    this.ctxSend(this.control.send("relay", {
      connectionId: this.connectionId,
      targetDeviceId: this.target.host.deviceId,
      counter: Number(session.sendingCounter()),
      ciphertext: toBase64Url(ciphertext)
    }));
  }
  requireWs() {
    if (this.wsId === void 0) throw new HostSessionError("network", "WS_CLOSED", "\u8FDE\u63A5\u5DF2\u5173\u95ED");
    return this.wsId;
  }
  /** 正常收尾 */
  finish(reason) {
    if (this.closed) return;
    this.close();
    this.handlers.onClosed(reason, null);
  }
  /** 出错收尾：关连接、报原因，避免留下一个半死不活的会话 */
  fail(error) {
    if (this.closed) return;
    this.close();
    this.handlers.onClosed(error.message, error);
  }
};
function toSessionError(error, fallback) {
  if (error instanceof HostSessionError) return error;
  if (error instanceof NoiseSessionError) {
    return new HostSessionError("protocol", error.code, `\u5B89\u5168\u63E1\u624B\u5931\u8D25\uFF1A${error.message}`);
  }
  if (error instanceof ControlStateError) {
    return new HostSessionError("protocol", error.code, error.message);
  }
  if (error instanceof ProtocolError) {
    return new HostSessionError("protocol", error.code, error.message);
  }
  const message = error instanceof Error ? error.message : String(error);
  return new HostSessionError(fallback, "UNKNOWN", message);
}

// dsh/contract.ts
var SLOT_EMAIL = "email";
var SLOT_PASSWORD = "password";
var SLOT_SERVER = "serverUrl";
var SLOT_HOST = "selectedHost";
var SESSION_ID = "main";
var activeSession = null;
var lastFailure = null;
var pendingLogin = null;
async function login(inputs) {
  const form = inputs ?? {};
  try {
    if (pendingLogin === null) {
      const email = (form[SLOT_EMAIL] ?? "").trim();
      const password = form[SLOT_PASSWORD] ?? "";
      const rawServer = (form[SLOT_SERVER] ?? "").trim();
      if (email.length === 0 || password.length === 0) {
        return {
          type: "needInput",
          prompt: "\u767B\u5F55 DSH \u8D26\u53F7",
          fields: [
            { name: SLOT_EMAIL, displayName: "\u90AE\u7BB1", secret: false },
            { name: SLOT_PASSWORD, displayName: "\u5BC6\u7801", secret: true },
            {
              name: SLOT_SERVER,
              displayName: "\u670D\u52A1\u5668",
              secret: false,
              hint: rawServer.length > 0 ? rawServer : "\u7559\u7A7A\u4F7F\u7528\u9ED8\u8BA4\u670D\u52A1\u5668"
            }
          ]
        };
      }
      pendingLogin = { serverUrl: rawServer, email, password };
      return await completeLogin(pendingLogin);
    }
    const pending = pendingLogin;
    pendingLogin = null;
    return await completeLogin(pending);
  } catch (error) {
    pendingLogin = null;
    const message = error instanceof Error ? error.message : String(error);
    ctx.log.e("dsh", `\u767B\u5F55\u5931\u8D25\uFF1A${message}`);
    return { type: "failure", message: `\u767B\u5F55\u5931\u8D25\uFF1A${message}` };
  }
}
async function completeLogin(input) {
  const serverUrl = normalizeServerUrl(input.serverUrl);
  const account = await loginWithPassword(serverUrl, input.email, input.password);
  const pair = generateKeyPair();
  const credentials = await registerDevice(serverUrl, account.token, {
    deviceId: newDeviceId(),
    name: "LifeBoard",
    platform: "android",
    identityKey: pair.publicKey,
    clientVersion: "1.0"
  });
  saveIdentity(pair);
  saveDeviceCredentials(credentials);
  saveAuth({
    serverUrl,
    account: account.account,
    deviceId: currentDeviceId(),
    deviceName: "LifeBoard",
    identityPublicKey: pair.publicKey
  });
  ctx.log.i("dsh", `\u767B\u5F55\u6210\u529F\uFF0C\u8BBE\u5907\u6307\u7EB9 ${identityFingerprint(pair.publicKey)}`);
  return { type: "success" };
}
var deviceIdValue = "";
function currentDeviceId() {
  return deviceIdValue;
}
function newDeviceId() {
  if (deviceIdValue.length === 0) {
    deviceIdValue = `lb-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`;
  }
  return deviceIdValue;
}
function logout() {
  clearAllAuth();
  deviceIdValue = "";
  closeSession();
}
function authState() {
  const auth = loadAuth();
  const credentials = loadDeviceCredentials();
  if (auth === null || credentials === null) return { state: "loggedOut" };
  return { state: "loggedIn", accountName: auth.account };
}
function detailUi() {
  const blocks = [];
  const auth = loadAuth();
  const host = loadSelectedHost();
  if (auth === null) {
    blocks.push({
      type: "text",
      title: "\u8FD8\u6CA1\u6709\u767B\u5F55",
      body: "\u8BF7\u5148\u5728\u63D2\u4EF6\u5217\u8868\u91CC\u5B8C\u6210\u767B\u5F55\uFF0C\u518D\u56DE\u5230\u8FD9\u91CC\u8FDE\u63A5\u7535\u8111\u3002"
    });
    return blocks;
  }
  if (host === null) {
    blocks.push({
      type: "statusCard",
      label: "\u7535\u8111",
      value: "\u672A\u9009\u62E9",
      tone: "warn",
      detail: "\u767B\u5F55\u540E\u4F1A\u81EA\u52A8\u53D1\u73B0\u540C\u8D26\u53F7\u4E0B\u5728\u7EBF\u7684\u7535\u8111"
    });
  } else {
    blocks.push({
      type: "statusCard",
      label: host.name,
      value: activeSession !== null && activeSession.active ? "\u5DF2\u8FDE\u63A5" : "\u672A\u8FDE\u63A5",
      tone: activeSession !== null && activeSession.active ? "ok" : "neutral",
      detail: host.platform
    });
  }
  if (lastFailure !== null) {
    blocks.push({
      type: "text",
      title: "\u4E0A\u6B21\u8FDE\u63A5\u5931\u8D25",
      body: lastFailure.message
    });
  }
  blocks.push({
    type: "conversation",
    id: "main",
    sessionId: SESSION_ID,
    title: host === null ? "\u8FDC\u7A0B\u4F1A\u8BDD" : `${host.name} \u7684\u4F1A\u8BDD`,
    placeholder: "\u7ED9\u7535\u8111\u53D1\u6307\u4EE4\u2026",
    submitLabel: "\u53D1\u9001",
    emptyHint: "\u8FDE\u63A5\u540E\u5728\u8FD9\u91CC\u5BF9\u8BDD"
  });
  return blocks;
}
async function onAction(actionId, value) {
  if (actionId === "dsh.refreshHosts") {
    const hosts = await discoverHosts();
    if (hosts.length === 0) return { type: "showMessage", text: "\u6CA1\u6709\u53D1\u73B0\u5728\u7EBF\u7684\u7535\u8111" };
    saveSelectedHost(hosts[0]);
    return { type: "refreshUi", blocks: detailUi() };
  }
  return { type: "showMessage", text: value === void 0 ? "\u672A\u77E5\u64CD\u4F5C" : `\u5DF2\u5FFD\u7565\uFF1A${actionId}` };
}
function health() {
  if (lastFailure !== null) return { state: "degraded", message: lastFailure.message };
  return { state: "healthy" };
}
function fetch() {
  return [];
}
async function sessionStart() {
  try {
    closeSession();
    lastFailure = null;
    const host = loadSelectedHost() ?? (await discoverHosts())[0];
    if (host === void 0) {
      const error = new HostSessionError("not-found", "NO_HOST", "\u6CA1\u6709\u53D1\u73B0\u5728\u7EBF\u7684\u7535\u8111");
      lastFailure = { kind: error.kind, message: error.message };
      ctx.session.emit(JSON.stringify({ type: "status", text: error.message, inputEnabled: false }));
      return false;
    }
    saveSelectedHost(host);
    ctx.session.emit(JSON.stringify({ type: "status", text: "\u6B63\u5728\u8FDE\u63A5\u2026", inputEnabled: false }));
    const target = await HostSession.prepare(host.deviceId);
    const session = new HostSession({
      onReady: (hostName, sessionId) => {
        ctx.log.i("dsh", `\u4F1A\u8BDD\u5C31\u7EEA\uFF1A${hostName} / ${sessionId}`);
        ctx.session.emit(JSON.stringify({
          type: "status",
          text: `\u5DF2\u8FDE\u63A5 ${hostName}`,
          inputEnabled: true
        }));
      },
      onAssistantText: (text) => {
        const emitted = lastAssistantText;
        lastAssistantText = text;
        const delta = text.startsWith(emitted) ? text.slice(emitted.length) : text;
        if (delta.length > 0) {
          ctx.session.emit(JSON.stringify({ type: "delta", text: delta }));
        }
      },
      onReasoning: () => {
      },
      onApproval: (eventId, toolName, reason) => {
        pendingApprovals.set(eventId, "approval");
        ctx.session.emit(JSON.stringify({
          type: "message",
          role: "system",
          text: `\u9700\u8981\u5BA1\u6279\uFF1A${toolName}${reason.length > 0 ? `\uFF08${reason}\uFF09` : ""}\u3002\u56DE\u590D y \u5141\u8BB8\u4E00\u6B21\uFF0Cn \u62D2\u7EDD\u3002`
        }));
      },
      onQuestion: (eventId, question) => {
        pendingApprovals.set(eventId, "question");
        ctx.session.emit(JSON.stringify({
          type: "message",
          role: "system",
          text: `\u9700\u8981\u4F60\u56DE\u7B54\uFF1A${question}\uFF08\u76F4\u63A5\u56DE\u590D\u9009\u9879\uFF09`
        }));
      },
      onInteractionCancelled: (eventId) => {
        pendingApprovals.delete(eventId);
      },
      onRequestFailed: (code, message) => {
        ctx.session.emit(JSON.stringify({
          type: "message",
          role: "system",
          text: `\u8BF7\u6C42\u5931\u8D25\uFF1A${message}\uFF08${code}\uFF09`
        }));
      },
      onClosed: (reason, failure) => {
        closeSession();
        lastFailure = failure === null ? null : { kind: failure.kind, message: failure.message };
        ctx.session.emit(JSON.stringify({ type: "closed", reason }));
      }
    }, { hostDeviceId: host.deviceId }, target);
    activeSession = session;
    lastAssistantText = "";
    pendingApprovals.clear();
    const opened = await session.open();
    if (!opened) {
      closeSession();
      return false;
    }
    return true;
  } catch (error) {
    const failure = toSessionError(error, "network");
    lastFailure = { kind: failure.kind, message: failure.message };
    ctx.log.e("dsh", `\u8FDE\u63A5\u5931\u8D25\uFF1A${failure.code} ${failure.message}`);
    ctx.session.emit(JSON.stringify({ type: "status", text: failure.message, inputEnabled: false }));
    closeSession();
    return false;
  }
}
var lastAssistantText = "";
var pendingApprovals = /* @__PURE__ */ new Map();
async function sessionEvent(event) {
  const session = activeSession;
  if (session === null || !session.active) {
    return { action: "stop", reason: "\u8FDE\u63A5\u5DF2\u65AD\u5F00" };
  }
  const text = (event.payload ?? "").trim();
  if (text.length === 0) return { action: "continue" };
  const pending = firstPending();
  if (pending !== void 0) {
    pendingApprovals.delete(pending.eventId);
    if (pending.kind === "approval") {
      const allowed = /^(y|yes|允许|同意|可以)$/i.test(text);
      session.approve(pending.eventId, allowed);
      ctx.session.emit(JSON.stringify({
        type: "message",
        role: "system",
        text: allowed ? "\u5DF2\u5141\u8BB8\u4E00\u6B21" : "\u5DF2\u62D2\u7EDD"
      }));
    } else {
      session.answer(pending.eventId, "q0", [text]);
      ctx.session.emit(JSON.stringify({
        type: "message",
        role: "system",
        text: `\u5DF2\u56DE\u7B54\uFF1A${text}`
      }));
    }
    return { action: "continue" };
  }
  lastAssistantText = "";
  if (!session.sendPrompt(text)) {
    return { action: "stop", reason: "\u8FDE\u63A5\u5DF2\u65AD\u5F00" };
  }
  ctx.session.emit(JSON.stringify({ type: "status", text: "\u6B63\u5728\u56DE\u590D", inputEnabled: false }));
  return { action: "continue" };
}
function sessionStop() {
  closeSession();
}
function closeSession() {
  const session = activeSession;
  activeSession = null;
  lastAssistantText = "";
  pendingApprovals.clear();
  if (session !== null) session.close();
}
function firstPending() {
  for (const [eventId, kind] of pendingApprovals) return { eventId, kind };
  return void 0;
}
async function discoverHosts() {
  const auth = loadAuth();
  const credentials = loadDeviceCredentials();
  if (auth === null || credentials === null) return [];
  try {
    return await listHostDevices(auth.serverUrl, credentials.deviceAccessToken);
  } catch (error) {
    ctx.log.w("dsh", `\u62C9\u53D6\u7535\u8111\u5217\u8868\u5931\u8D25\uFF1A${error instanceof Error ? error.message : String(error)}`);
    return [];
  }
}
function loadSelectedHost() {
  const raw = ctx.credentials.get(SLOT_HOST);
  if (typeof raw !== "string" || raw.length === 0) return null;
  try {
    const parsed = JSON.parse(raw);
    if (typeof parsed.deviceId !== "string" || typeof parsed.identityKey !== "string") return null;
    return parsed;
  } catch {
    return null;
  }
}
function saveSelectedHost(host) {
  ctx.credentials.put(SLOT_HOST, JSON.stringify(host));
}

// dsh/main.ts
function fixedRng(hex) {
  const bytes = fromBase64Url(hex);
  let offset = 0;
  return (length) => {
    const out = bytes.slice(offset, offset + length);
    offset += length;
    if (out.byteLength !== length) {
      throw new Error(`\u56FA\u5B9A\u968F\u673A\u6E90\u5DF2\u8017\u5C3D\uFF1A\u9700\u8981 ${length} \u5B57\u8282\uFF0C\u5269\u4F59 ${out.byteLength}`);
    }
    return out;
  };
}
function sameBytes(actual, expected) {
  if (actual.byteLength !== expected.byteLength) return false;
  for (let i = 0; i < actual.byteLength; i += 1) {
    if (actual[i] !== expected[i]) return false;
  }
  return true;
}
function selfTest(vectorsJson) {
  const checks = [];
  const failures = [];
  let file;
  try {
    file = JSON.parse(vectorsJson);
  } catch (e) {
    return { ok: false, checks, failures: [`\u5411\u91CF\u6587\u4EF6\u4E0D\u662F\u5408\u6CD5 JSON\uFF1A${String(e)}`] };
  }
  if (file.fixtureFormat !== "dsh-remote.noise-vectors") {
    failures.push(`fixtureFormat \u4E0D\u7B26\uFF1A${file.fixtureFormat}`);
  }
  file.vectors.forEach((vector) => {
    const prologue = fromBase64Url(vector.prologue.bytes);
    const initiator = new NoiseIkSession({
      role: "initiator",
      localPrivateKey: vector.staticKeys.initiator.privateKey,
      localPublicKey: vector.staticKeys.initiator.publicKey,
      remotePublicKey: vector.staticKeys.responder.publicKey,
      prologue,
      random: fixedRng(vector.ephemeralPrivateKeys.initiator)
    });
    const responder = new NoiseIkSession({
      role: "responder",
      localPrivateKey: vector.staticKeys.responder.privateKey,
      localPublicKey: vector.staticKeys.responder.publicKey,
      remotePublicKey: vector.staticKeys.initiator.publicKey,
      prologue,
      random: fixedRng(vector.ephemeralPrivateKeys.responder)
    });
    const message1 = initiator.writeHandshake(Uint8Array.from(fromBase64Url(vector.handshake.message1.payload)));
    if (sameBytes(message1, fromBase64Url(vector.handshake.message1.message))) {
      checks.push(`${vector.name}: message1 \u4E0E\u5411\u91CF\u4E00\u81F4`);
    } else {
      failures.push(`${vector.name}: message1 \u4E0D\u4E00\u81F4\uFF08\u5B9E\u9645 ${toBase64Url(message1)}\uFF09`);
    }
    const readPayload1 = responder.readHandshake(message1);
    if (sameBytes(readPayload1, fromBase64Url(vector.handshake.message1.payload))) {
      checks.push(`${vector.name}: responder \u89E3\u51FA\u7684 payload1 \u6B63\u786E`);
    } else {
      failures.push(`${vector.name}: responder \u89E3\u51FA\u7684 payload1 \u4E0D\u7B26`);
    }
    const message2 = responder.writeHandshake(Uint8Array.from(fromBase64Url(vector.handshake.message2.payload)));
    if (sameBytes(message2, fromBase64Url(vector.handshake.message2.message))) {
      checks.push(`${vector.name}: message2 \u4E0E\u5411\u91CF\u4E00\u81F4`);
    } else {
      failures.push(`${vector.name}: message2 \u4E0D\u4E00\u81F4\uFF08\u5B9E\u9645 ${toBase64Url(message2)}\uFF09`);
    }
    const readPayload2 = initiator.readHandshake(message2);
    if (sameBytes(readPayload2, fromBase64Url(vector.handshake.message2.payload))) {
      checks.push(`${vector.name}: initiator \u89E3\u51FA\u7684 payload2 \u6B63\u786E`);
    } else {
      failures.push(`${vector.name}: initiator \u89E3\u51FA\u7684 payload2 \u4E0D\u7B26`);
    }
    if (initiator.complete && responder.complete) {
      checks.push(`${vector.name}: \u53CC\u65B9\u63E1\u624B\u5B8C\u6210`);
    } else {
      failures.push(`${vector.name}: \u63E1\u624B\u672A\u5B8C\u6210`);
    }
    vector.transport.forEach((frame, index) => {
      const from = frame.sender === "initiator" ? initiator : responder;
      const to = frame.sender === "initiator" ? responder : initiator;
      const plaintext = fromBase64Url(frame.plaintext);
      const ciphertext = from.encrypt(plaintext);
      if (sameBytes(ciphertext, fromBase64Url(frame.ciphertext))) {
        checks.push(`${vector.name}: transport[${index}] \u5BC6\u6587\u4E00\u81F4`);
      } else {
        failures.push(`${vector.name}: transport[${index}] \u5BC6\u6587\u4E0D\u4E00\u81F4`);
      }
      const decoded = to.decrypt(ciphertext);
      if (sameBytes(decoded, plaintext)) {
        checks.push(`${vector.name}: transport[${index}] \u53EF\u88AB\u5BF9\u7AEF\u8FD8\u539F`);
      } else {
        failures.push(`${vector.name}: transport[${index}] \u5BF9\u7AEF\u8FD8\u539F\u5931\u8D25`);
      }
    });
    initiator.destroy();
    responder.destroy();
  });
  return { ok: failures.length === 0, checks, failures };
}
function fingerprintSelfTest(publicKey) {
  const first = identityFingerprint(publicKey);
  const second = identityFingerprint(publicKey);
  return {
    ok: first === second && /^[0-9A-F]{12}$/.test(first),
    fingerprint: first
  };
}
function protocolSelfTest() {
  const checks = [];
  const failures = [];
  const expect = (name, pass, detail = "") => {
    if (pass) checks.push(name);
    else failures.push(detail.length > 0 ? `${name}\uFF1A${detail}` : name);
  };
  const frame = createFrame("ping", { nonce: "abc" });
  const decoded = decodeFrame(encodeFrame(frame));
  expect("\u5E27\u5F80\u8FD4\u540E type \u4E00\u81F4", decoded.type === "ping", decoded.type);
  expect("\u5E27\u5F80\u8FD4\u540E payload \u4E00\u81F4", decoded.payload.nonce === "abc");
  const rejects = (name, text, code) => {
    try {
      decodeFrame(text);
      expect(name, false, "\u672C\u5E94\u629B\u9519\u5374\u901A\u8FC7\u4E86");
    } catch (e) {
      const actual = e instanceof ProtocolError ? e.code : "NOT_PROTOCOL_ERROR";
      expect(name, actual === code, `\u671F\u671B ${code}\uFF0C\u5B9E\u9645 ${actual}`);
    }
  };
  rejects("\u975E JSON \u88AB\u62D2\u7EDD", "not json", "NOT_JSON");
  rejects("\u7248\u672C\u4E0D\u7B26\u88AB\u62D2\u7EDD", '{"v":2,"id":"a","type":"ping","timestamp":1,"payload":{}}', "BAD_FRAME");
  rejects("\u672A\u77E5\u7C7B\u578B\u88AB\u62D2\u7EDD", '{"v":1,"id":"a","type":"nope","timestamp":1,"payload":{}}', "UNKNOWN_TYPE");
  rejects("payload \u975E\u5BF9\u8C61\u88AB\u62D2\u7EDD", '{"v":1,"id":"a","type":"ping","timestamp":1,"payload":[]}', "BAD_FRAME");
  try {
    const ack = readHelloAck({
      protocol: 1,
      serverVersion: "0.1.0",
      connectionSessionId: "s1",
      heartbeatIntervalMs: 25e3,
      maxControlFrameBytes: 999999,
      maxRelayFrameBytes: 1048576
    });
    expect("hello.ack \u5FC3\u8DF3\u88AB\u8BFB\u51FA", ack.heartbeatIntervalMs === 25e3);
    expect("hello.ack \u5355\u5E27\u4E0A\u9650\u53D6\u8F83\u5C0F\u503C", ack.maxControlFrameBytes === 64 * 1024, String(ack.maxControlFrameBytes));
  } catch (e) {
    expect("hello.ack \u89E3\u6790", false, String(e));
  }
  try {
    readSecureHandshake({ connectionId: "c", targetDeviceId: "d", step: 3, data: "AA" });
    expect("secure.handshake \u975E\u6CD5 step \u88AB\u62D2\u7EDD", false, "\u672C\u5E94\u629B\u9519");
  } catch (e) {
    expect("secure.handshake \u975E\u6CD5 step \u88AB\u62D2\u7EDD", e instanceof ProtocolError && e.code === "BAD_PAYLOAD");
  }
  const urlCases = [
    ["https://dsh.r2049.cn", "https://dsh.r2049.cn", true],
    [" https://dsh.r2049.cn/ ", "https://dsh.r2049.cn", true],
    ["http://dsh.r2049.cn", "", false],
    ["https://dsh.r2049.cn/app", "", false],
    ["https://user:pw@dsh.r2049.cn", "", false],
    ["ftp://dsh.r2049.cn", "", false]
  ];
  urlCases.forEach(([input, expected, shouldPass]) => {
    try {
      const actual = normalizeServerUrl(input);
      expect(`\u5730\u5740\u89C4\u8303\u5316 ${input}`, shouldPass && actual === expected, `\u5F97\u5230 ${actual}`);
    } catch (e) {
      expect(`\u5730\u5740\u89C4\u8303\u5316 ${input}`, !shouldPass, `\u672C\u5E94\u63A5\u53D7\uFF1A${String(e)}`);
    }
  });
  expect("\u5730\u5740\u6D3E\u751F wss", deriveWsUrl("https://dsh.r2049.cn") === "wss://dsh.r2049.cn/ws/v1/connect");
  expect("\u5373\u5C06\u8FC7\u671F\u9700\u8981\u5237\u65B0", needsRefresh(1e3, 900, 150));
  expect("\u5269\u4F59\u5145\u8DB3\u4E0D\u9700\u5237\u65B0", !needsRefresh(1e4, 900, 150));
  expect("\u65E0\u8FC7\u671F\u65F6\u95F4\u6309\u9700\u5237\u65B0", needsRefresh(0, 900, 150));
  return { ok: failures.length === 0, checks, failures };
}
function controlSelfTest() {
  const checks = [];
  const failures = [];
  const expect = (name, pass, detail = "") => {
    if (pass) checks.push(name);
    else failures.push(detail.length > 0 ? `${name}\uFF1A${detail}` : name);
  };
  const frameText = (type, payload) => JSON.stringify({ v: 1, id: `t_${type}`, type, timestamp: Date.now(), payload });
  const ackPayload = {
    protocol: 1,
    serverVersion: "0.1.0",
    connectionSessionId: "s1",
    heartbeatIntervalMs: 25e3,
    maxControlFrameBytes: 65536,
    maxRelayFrameBytes: 1048576,
    webrtcEnabled: false
  };
  const sees = [];
  const handlers = {
    onConnectIncoming: (f) => sees.push(`incoming:${f.connectionId}`),
    onSecureHandshake: (f) => sees.push(`handshake:${f.step}`),
    onUnsupported: (t) => sees.push(`unsupported:${t}`)
  };
  const greeting = new ControlChannel(handlers);
  const hello = JSON.parse(greeting.createHello({ deviceId: "dev-1", accessToken: "tok", role: "client" }));
  expect("hello \u7684 role \u662F client", hello.payload.role === "client");
  expect("hello \u58F0\u660E\u534F\u8BAE\u7248\u672C 1", JSON.stringify(hello.payload.protocols) === "[1]");
  expect("hello \u7684\u5E27\u7C7B\u578B\u6B63\u786E", hello.type === "hello");
  const rejectsFirst = (name, text) => {
    const c = new ControlChannel(handlers);
    try {
      c.handle(text);
      expect(name, false, "\u672C\u5E94\u629B\u9519");
    } catch (e) {
      expect(name, e instanceof ControlStateError && e.code === "BAD_FIRST_FRAME", String(e));
    }
  };
  rejectsFirst("\u9996\u5E27\u4E3A ping \u88AB\u62D2\u7EDD", frameText("ping", { nonce: "n" }));
  rejectsFirst("\u9996\u5E27\u4E3A error \u88AB\u62D2\u7EDD", frameText("error", { code: "AUTH_INVALID", message: "token \u65E0\u6548" }));
  const channel = new ControlChannel(handlers);
  const ready = channel.handle(frameText("hello.ack", ackPayload));
  expect("hello.ack \u88AB\u89E3\u6790\u4E3A ack", ready.ack?.connectionSessionId === "s1");
  expect("\u63E1\u624B\u540E\u72B6\u6001\u4E3A ready", channel.ready);
  const pong = channel.handle(frameText("ping", { nonce: "abc123" }));
  expect("ping \u4EA7\u51FA\u4E00\u5E27 pong", pong.sends.length === 1, String(pong.sends.length));
  const pongFrame = JSON.parse(pong.sends[0] ?? "{}");
  expect("pong \u7684 nonce \u539F\u6837\u5E26\u56DE", pongFrame.payload?.nonce === "abc123");
  expect("pong \u7684\u7248\u672C\u4E3A 1", pongFrame.v === 1);
  channel.handle(frameText("connect.incoming", {
    connectionId: "conn-1",
    clientDeviceId: "cli-1",
    clientIdentityKey: "key",
    authorization: "account"
  }));
  expect("connect.incoming \u88AB\u5206\u53D1", sees.includes("incoming:conn-1"), sees.join(","));
  channel.handle(frameText("secure.handshake", {
    connectionId: "conn-1",
    targetDeviceId: "dev-1",
    step: 1,
    data: "AA"
  }));
  expect("secure.handshake \u88AB\u5206\u53D1", sees.includes("handshake:1"), sees.join(","));
  channel.handle(frameText("signal.offer", { sdp: "x" }));
  channel.handle(frameText("future.thing", {}));
  expect("WebRTC \u4FE1\u4EE4\u88AB\u8BB0\u4E3A\u4E0D\u652F\u6301", sees.includes("unsupported:signal.offer"), sees.join(","));
  expect("\u5B8C\u5168\u672A\u77E5\u7684\u7C7B\u578B\u88AB\u5FFD\u7565", sees.includes("unsupported:<unknown>"), sees.join(","));
  expect("\u9047\u5230\u4E0D\u652F\u6301\u7C7B\u578B\u540E\u4ECD ready", channel.ready);
  try {
    channel.handle(frameText("connect.incoming", {
      connectionId: "conn-2",
      clientDeviceId: "cli-2",
      clientIdentityKey: "key",
      authorization: "code"
    }));
    expect("\u975E account \u6388\u6743\u65B9\u5F0F\u88AB\u62D2\u7EDD", false, "\u672C\u5E94\u629B\u9519");
  } catch (e) {
    expect("\u975E account \u6388\u6743\u65B9\u5F0F\u88AB\u62D2\u7EDD", e instanceof ProtocolError && e.code === "UNSUPPORTED");
  }
  try {
    channel.handle(frameText("secure.handshake", {
      connectionId: "conn-2",
      targetDeviceId: "dev-1",
      step: 3,
      data: "AA"
    }));
    expect("\u975E\u6CD5 step \u88AB\u62D2\u7EDD", false, "\u672C\u5E94\u629B\u9519");
  } catch (e) {
    expect("\u975E\u6CD5 step \u88AB\u62D2\u7EDD", e instanceof ProtocolError && e.code === "BAD_PAYLOAD");
  }
  channel.close();
  try {
    channel.send("connect.request", { hostDeviceId: "h" });
    expect("\u5173\u95ED\u540E\u53D1\u5E27\u88AB\u62D2\u7EDD", false, "\u672C\u5E94\u629B\u9519");
  } catch (e) {
    expect("\u5173\u95ED\u540E\u53D1\u5E27\u88AB\u62D2\u7EDD", e instanceof ControlStateError && e.code === "CLOSED");
  }
  return { ok: failures.length === 0, checks, failures };
}
function rpcSelfTest() {
  const checks = [];
  const failures = [];
  const expect = (name, pass, detail = "") => {
    if (pass) checks.push(name);
    else failures.push(detail.length > 0 ? `${name}\uFF1A${detail}` : name);
  };
  const messageText = (type, payload) => JSON.stringify({ v: 1, id: `m_${type}`, type, timestamp: Date.now(), payload });
  const request = createRpcRequest("harness.remote.call", { endpoint: "session/list", payload: {} });
  expect("\u8BF7\u6C42\u7C7B\u578B\u4E3A rpc.request", request.type === "rpc.request", request.type);
  expect("\u8BF7\u6C42\u7248\u672C\u4E3A 1", request.v === 1, String(request.v));
  expect("\u8BF7\u6C42\u65B9\u6CD5\u540D\u4FDD\u7559", request.payload.method === "harness.remote.call", request.payload.method);
  expect("\u8BF7\u6C42 id \u975E\u7A7A", request.id.length > 0);
  expect("\u4E24\u6B21\u8BF7\u6C42 id \u4E0D\u540C", createRpcRequest("a", {}).id !== createRpcRequest("a", {}).id);
  const roundTrip = decodeMessage(encodeMessage(request));
  expect("\u5F80\u8FD4\u540E id \u4E00\u81F4", roundTrip.id === request.id);
  expect(
    "\u5F80\u8FD4\u540E\u65B9\u6CD5\u540D\u4E00\u81F4",
    roundTrip.payload.method === "harness.remote.call"
  );
  const response = readInbound(messageText("rpc.response", {
    requestId: request.id,
    result: { sessions: [] }
  }));
  expect("\u54CD\u5E94\u88AB\u8BC6\u522B\u4E3A response", response.kind === "response", response.kind);
  expect(
    "\u54CD\u5E94\u7684 requestId \u4FDD\u7559",
    response.kind === "response" && response.requestId === request.id
  );
  const error = readInbound(messageText("rpc.error", {
    requestId: request.id,
    code: "SESSION_NOT_FOUND",
    message: "\u4F1A\u8BDD\u4E0D\u5B58\u5728",
    retryable: true
  }));
  expect("\u9519\u8BEF\u88AB\u8BC6\u522B\u4E3A error", error.kind === "error", error.kind);
  expect("\u9519\u8BEF\u7801\u539F\u6837\u4FDD\u7559", error.kind === "error" && error.code === "SESSION_NOT_FOUND");
  expect("retryable \u88AB\u8BFB\u51FA", error.kind === "error" && error.retryable);
  const noRetry = readInbound(messageText("rpc.error", {
    requestId: request.id,
    code: "AUTH_INVALID",
    message: "\u51ED\u636E\u65E0\u6548"
  }));
  expect("\u7F3A\u7701 retryable \u4E3A false", noRetry.kind === "error" && !noRetry.retryable);
  const event = readInbound(messageText("event", {
    event: "harness.remote.frame",
    data: { streamId: "s1" },
    seq: 7,
    sessionId: "sess-1"
  }));
  expect("\u4E8B\u4EF6\u88AB\u8BC6\u522B\u4E3A event", event.kind === "event", event.kind);
  expect("\u4E8B\u4EF6\u540D\u4FDD\u7559", event.kind === "event" && event.event === "harness.remote.frame");
  expect("\u4E8B\u4EF6\u5E8F\u53F7\u4FDD\u7559", event.kind === "event" && event.seq === 7);
  expect("\u4E8B\u4EF6\u4F1A\u8BDD id \u4FDD\u7559", event.kind === "event" && event.sessionId === "sess-1");
  const inboundRequest = readInbound(messageText("rpc.request", {
    method: "workspace.upload.open",
    params: {}
  }));
  expect("\u53CD\u5411\u8BF7\u6C42\u88AB\u8BC6\u522B\u4E3A request", inboundRequest.kind === "request", inboundRequest.kind);
  const rejects = (name, text, code, limit) => {
    try {
      readInbound(text, limit);
      expect(name, false, "\u672C\u5E94\u629B\u9519\u5374\u901A\u8FC7\u4E86");
    } catch (e) {
      const actual = e instanceof ProtocolError ? e.code : "NOT_PROTOCOL_ERROR";
      expect(name, actual === code, `\u671F\u671B ${code}\uFF0C\u5B9E\u9645 ${actual}`);
    }
  };
  rejects("\u975E JSON \u88AB\u62D2\u7EDD", "not json", "NOT_JSON");
  rejects("\u7248\u672C\u4E0D\u7B26\u88AB\u62D2\u7EDD", '{"v":2,"id":"a","type":"event","timestamp":1,"payload":{}}', "BAD_FRAME");
  rejects("\u672A\u77E5\u6D88\u606F\u7C7B\u578B\u88AB\u62D2\u7EDD", '{"v":1,"id":"a","type":"rpc.ack","timestamp":1,"payload":{}}', "UNKNOWN_TYPE");
  rejects("payload \u975E\u5BF9\u8C61\u88AB\u62D2\u7EDD", '{"v":1,"id":"a","type":"event","timestamp":1,"payload":[]}', "BAD_FRAME");
  rejects("\u4E8B\u4EF6\u7F3A\u5C11\u540D\u79F0\u88AB\u62D2\u7EDD", messageText("event", { data: {} }), "BAD_PAYLOAD");
  rejects("\u54CD\u5E94\u7F3A\u5C11 requestId \u88AB\u62D2\u7EDD", messageText("rpc.response", { result: {} }), "BAD_PAYLOAD");
  try {
    encodeMessage(request, 16);
    expect("\u8D85\u9650\u6D88\u606F\u88AB\u62D2\u7EDD", false, "\u672C\u5E94\u629B\u9519");
  } catch (e) {
    expect("\u8D85\u9650\u6D88\u606F\u88AB\u62D2\u7EDD", e instanceof ProtocolError && e.code === "BAD_FRAME");
  }
  const pending = new RpcPending();
  pending.track("id-1", "harness.remote.call");
  pending.track("id-2", "harness.remote.stream.open");
  expect("\u6302\u8D77\u8868\u8BA1\u6570\u6B63\u786E", pending.size === 2, String(pending.size));
  expect("\u767B\u8BB0\u540E has \u4E3A\u771F", pending.has("id-1"));
  const settled = pending.settle("id-1");
  expect("\u6458\u53D6\u8FD4\u56DE\u65B9\u6CD5\u540D", settled?.method === "harness.remote.call", String(settled?.method));
  expect("\u6458\u53D6\u540E\u4ECE\u8868\u91CC\u79FB\u9664", !pending.has("id-1") && pending.size === 1, String(pending.size));
  expect("\u91CD\u590D\u6458\u53D6\u8FD4\u56DE undefined", pending.settle("id-1") === void 0);
  expect("\u672A\u77E5 id \u6458\u53D6\u8FD4\u56DE undefined", pending.settle("nope") === void 0);
  try {
    pending.track("id-2", "dup");
    expect("\u91CD\u590D\u767B\u8BB0\u88AB\u62D2\u7EDD", false, "\u672C\u5E94\u629B\u9519");
  } catch (e) {
    expect("\u91CD\u590D\u767B\u8BB0\u88AB\u62D2\u7EDD", e instanceof ProtocolError && e.code === "BAD_PAYLOAD");
  }
  expect("\u6E05\u7A7A\u8FD4\u56DE\u4E22\u5F03\u6761\u6570", pending.dropAll() === 1 && pending.size === 0);
  return { ok: failures.length === 0, checks, failures };
}
function sessionsSelfTest() {
  const checks = [];
  const failures = [];
  const expect = (name, pass, detail = "") => {
    if (pass) checks.push(name);
    else failures.push(detail.length > 0 ? `${name}\uFF1A${detail}` : name);
  };
  const paramsOf = (message) => message.payload.params;
  const listRequest = createSessionListRequest();
  expect("\u5217\u8868\u8BF7\u6C42\u4E3A rpc.request", listRequest.type === "rpc.request", listRequest.type);
  expect(
    "\u5217\u8868\u8BF7\u6C42\u8D70 harness.remote.call",
    listRequest.payload.method === REMOTE_CALL_METHOD,
    listRequest.payload.method
  );
  const listParams = paramsOf(listRequest);
  expect(
    "\u5217\u8868\u8BF7\u6C42 endpoint \u4E3A session/list",
    listParams.endpoint === "session/list",
    listParams.endpoint
  );
  expect(
    "\u5217\u8868\u8BF7\u6C42\u5E26 args._request",
    typeof listParams.payload.args._request === "object"
  );
  const byWorkspace = createSessionCreateRequest({ workspaceId: "ws-1" });
  const workspaceParams = paramsOf(byWorkspace);
  const workspaceArgs = workspaceParams.payload.args.request;
  expect(
    "\u65B0\u5EFA\u8BF7\u6C42 endpoint \u4E3A session/create",
    workspaceParams.endpoint === "session/create",
    workspaceParams.endpoint
  );
  expect("workspaceId \u88AB\u4FDD\u7559", workspaceArgs.workspaceId === "ws-1");
  expect("\u6709 workspaceId \u65F6\u4E0D\u5E26 cwd", !("cwd" in workspaceArgs));
  const byCwd = createSessionCreateRequest({ cwd: "/tmp/project" });
  const cwdArgs = paramsOf(byCwd).payload.args.request;
  expect("cwd \u88AB\u4FDD\u7559", cwdArgs.cwd === "/tmp/project");
  const byDefault = createSessionCreateRequest();
  const defaultArgs = paramsOf(byDefault).payload.args.request;
  expect(
    "\u65E0\u76EE\u6807\u65F6 request \u4E3A\u7A7A\u5BF9\u8C61",
    Object.keys(defaultArgs).length === 0,
    JSON.stringify(defaultArgs)
  );
  expect(
    "ok:true \u53D6\u51FA value",
    unwrapRemoteResult({ ok: true, value: { items: [] } }).items.length === 0
  );
  expect("ok:true \u65E0 value \u65F6\u8FD4\u56DE undefined", unwrapRemoteResult({ ok: true }) === void 0);
  const raw = { sessionId: "s1" };
  expect("\u65E0 ok \u5B57\u6BB5\u65F6\u539F\u6837\u8FD4\u56DE", unwrapRemoteResult(raw) === raw);
  try {
    unwrapRemoteResult({ ok: false, error: { code: "SESSION_NOT_FOUND", message: "\u4E0D\u5B58\u5728", details: { id: "x" } } });
    expect("ok:false \u629B RemoteCallError", false, "\u672C\u5E94\u629B\u9519");
  } catch (e) {
    expect("ok:false \u629B RemoteCallError", e instanceof RemoteCallError && e.code === "SESSION_NOT_FOUND");
    expect(
      "\u9519\u8BEF details \u4FDD\u7559",
      e instanceof RemoteCallError && e.details.id === "x"
    );
  }
  try {
    unwrapRemoteResult({ ok: false, error: { code: "X" } });
    expect("\u6B8B\u7F3A\u5931\u8D25\u5916\u58F3\u629B BAD_PAYLOAD", false, "\u672C\u5E94\u629B\u9519");
  } catch (e) {
    expect("\u6B8B\u7F3A\u5931\u8D25\u5916\u58F3\u629B BAD_PAYLOAD", e instanceof ProtocolError && e.code === "BAD_PAYLOAD");
  }
  const sessions = readSessionList({
    items: [
      { sessionId: "a", updatedAt: 3, running: true, blank: false, title: "\u4F1A\u8BDD A" },
      { sessionId: "b", updatedAt: 2, running: false, blank: true },
      { sessionId: "c", updatedAt: 1, running: false, blank: false, projections: { values: { title: "\u6295\u5F71\u6807\u9898" } } },
      { sessionId: "bad", updatedAt: "yesterday", running: false, blank: false },
      { updatedAt: 0, running: false, blank: false }
    ]
  });
  expect("\u5217\u8868\u4E22\u5F03\u574F\u6761\u76EE", sessions.length === 3, String(sessions.length));
  expect("\u5217\u8868\u987A\u5E8F\u4FDD\u6301", sessions[0]?.sessionId === "a" && sessions[2]?.sessionId === "c");
  expect("running \u88AB\u4FDD\u7559", sessions[0]?.running === true);
  expect("\u6295\u5F71\u6807\u9898\u88AB\u56DE\u9000\u91C7\u7528", sessions[2]?.title === "\u6295\u5F71\u6807\u9898", String(sessions[2]?.title));
  expect("\u975E\u6570\u7EC4 items \u89C6\u4E3A\u7A7A\u5217\u8868", readSessionList({ items: null }).length === 0);
  expect("\u975E\u5BF9\u8C61\u8FD4\u56DE\u7A7A\u5217\u8868", readSessionList(void 0).length === 0);
  expect("\u65B0\u5EFA\u8FD4\u56DE sessionId", readSessionCreateResult({ sessionId: "new-1" }) === "new-1");
  try {
    readSessionCreateResult({});
    expect("\u65B0\u5EFA\u7F3A sessionId \u629B BAD_PAYLOAD", false, "\u672C\u5E94\u629B\u9519");
  } catch (e) {
    expect("\u65B0\u5EFA\u7F3A sessionId \u629B BAD_PAYLOAD", e instanceof ProtocolError && e.code === "BAD_PAYLOAD");
  }
  return { ok: failures.length === 0, checks, failures };
}
function streamSelfTest() {
  const checks = [];
  const failures = [];
  const expect = (name, pass, detail = "") => {
    if (pass) checks.push(name);
    else failures.push(detail.length > 0 ? `${name}\uFF1A${detail}` : name);
  };
  const expectThrows = (name, run, code) => {
    try {
      run();
      expect(name, false, "\u672C\u5E94\u629B\u9519\u5374\u901A\u8FC7\u4E86");
    } catch (e) {
      const actual = e instanceof ProtocolError ? e.code : "NOT_PROTOCOL_ERROR";
      expect(name, actual === code, `\u671F\u671B ${code}\uFF0C\u5B9E\u9645 ${actual}`);
    }
  };
  const prompt = createSessionPromptRequest("s-1", "\u4F60\u597D", { requestId: "req-1" });
  const promptPayload = prompt.payload;
  expect("Prompt \u8D70 harness.remote.call", promptPayload.method === REMOTE_CALL_METHOD);
  expect(
    "Prompt endpoint \u4E3A session/prompt",
    promptPayload.params.endpoint === SESSION_PROMPT_ENDPOINT,
    promptPayload.params.endpoint
  );
  const promptRequest = promptPayload.params.payload.args.request;
  expect("Prompt requestId \u53EF\u590D\u7528", promptRequest.requestId === "req-1");
  expect("Prompt sessionId \u88AB\u4FDD\u7559", promptRequest.sessionId === "s-1");
  expect("Prompt \u9ED8\u8BA4 queue \u6A21\u5F0F", promptRequest.mode === "queue", String(promptRequest.mode));
  const content = promptRequest.content;
  expect(
    "Prompt \u5185\u5BB9\u4E3A\u5355\u6761\u6587\u672C",
    Array.isArray(content) && content.length === 1 && content[0].type === "text" && content[0].text === "\u4F60\u597D"
  );
  expect("\u7F3A\u7701\u4E0D\u5E26 clientTimeZone", !("clientTimeZone" in promptRequest));
  const zoned = createSessionPromptRequest("s-1", "x", { clientTimeZone: "Asia/Shanghai" });
  const zonedRequest = zoned.payload.params.payload.args.request;
  expect("clientTimeZone \u53EF\u9009\u5E26\u4E0A", zonedRequest.clientTimeZone === "Asia/Shanghai");
  expectThrows("\u7A7A\u6587\u672C Prompt \u88AB\u62D2\u7EDD", () => createSessionPromptRequest("s-1", ""), "BAD_PAYLOAD");
  const follow = createSessionFollowRequest("stream-1", "s-1");
  const followPayload = follow.payload;
  expect("\u8DDF\u968F\u6D41\u8D70 stream.open", followPayload.method === STREAM_OPEN_METHOD, followPayload.method);
  expect("\u8DDF\u968F\u6D41 streamId \u88AB\u4FDD\u7559", followPayload.params.streamId === "stream-1");
  expect(
    "\u8DDF\u968F\u6D41 endpoint \u4E3A session/follow",
    followPayload.params.endpoint === SESSION_FOLLOW_ENDPOINT,
    followPayload.params.endpoint
  );
  const followRequest = followPayload.params.payload.args.request;
  const address = followRequest.address;
  expect("\u4F1A\u8BDD\u5730\u5740\u4E3A\u666E\u901A session", address.kind === "session" && address.sessionId === "s-1");
  expect(
    "\u9ED8\u8BA4\u643A\u5E26\u6700\u5927\u6D88\u606F\u6570",
    followRequest.maxMessages === MAX_SESSION_FOLLOW_MESSAGES,
    String(followRequest.maxMessages)
  );
  const custom = createSessionFollowRequest("stream-2", "s-1", 5);
  const customRequest = custom.payload.params.payload.args.request;
  expect("maxMessages \u53EF\u8986\u76D6", customRequest.maxMessages === 5);
  expectThrows("\u7A7A sessionId \u88AB\u62D2\u7EDD", () => createSessionFollowRequest("stream-3", ""), "BAD_PAYLOAD");
  expectThrows("\u975E\u6B63 maxMessages \u88AB\u62D2\u7EDD", () => createSessionFollowRequest("stream-4", "s", 0), "BAD_PAYLOAD");
  const close = createStreamCloseRequest("stream-1");
  const closePayload = close.payload;
  expect("\u5173\u95ED\u8D70 stream.close", closePayload.method === STREAM_CLOSE_METHOD, closePayload.method);
  expect("\u5173\u95ED\u643A\u5E26 streamId", closePayload.params.streamId === "stream-1");
  expectThrows("\u5173\u95ED\u7A7A streamId \u88AB\u62D2\u7EDD", () => createStreamCloseRequest(""), "BAD_PAYLOAD");
  const frameSignal = parseStreamEvent(EVENT_FRAME, { streamId: "stream-1", hasValue: true, value: { k: 1 } });
  expect("\u6570\u636E\u5E27\u89E3\u6790\u4E3A frame", frameSignal?.kind === "frame");
  expect(
    "\u6570\u636E\u5E27 value \u4FDD\u7559",
    frameSignal?.kind === "frame" && frameSignal.value.k === 1
  );
  const emptyFrame = parseStreamEvent(EVENT_FRAME, { streamId: "stream-1", hasValue: true });
  expect(
    "\u7F3A\u7701 value \u4E3A undefined",
    emptyFrame?.kind === "frame" && emptyFrame.value === void 0
  );
  expect("\u672A\u77E5\u4E8B\u4EF6\u8FD4\u56DE undefined", parseStreamEvent("harness.unknown", {}) === void 0);
  expectThrows(
    "\u7F3A streamId \u7684\u6570\u636E\u5E27\u88AB\u62D2\u7EDD",
    () => parseStreamEvent(EVENT_FRAME, { hasValue: true }),
    "BAD_PAYLOAD"
  );
  expectThrows(
    "transfer \u63CF\u8FF0\u5E27\u88AB\u62D2\u7EDD",
    () => parseStreamEvent(EVENT_FRAME, { streamId: "s", hasValue: true, transfer: { kind: "chunked" } }),
    "BAD_FRAME"
  );
  const closedSignal = parseStreamEvent(EVENT_STREAM_CLOSED, { streamId: "stream-1", reason: "completed" });
  expect(
    "\u7EC8\u6B62\u5E27\u89E3\u6790\u4E3A closed",
    closedSignal?.kind === "closed" && closedSignal.reason === "completed"
  );
  const failedSignal = parseStreamEvent(EVENT_STREAM_CLOSED, {
    streamId: "stream-1",
    reason: "failed",
    failure: { code: "TIMEOUT", message: "\u8D85\u65F6" }
  });
  expect(
    "failed \u5E26\u51FA failure",
    failedSignal?.kind === "closed" && failedSignal.failure?.code === "TIMEOUT"
  );
  const failureDetails = failedSignal?.kind === "closed" ? failedSignal.failure?.details : void 0;
  expect(
    "failure.details \u7F3A\u7701\u4E3A\u7A7A\u5BF9\u8C61",
    failureDetails !== void 0 && Object.keys(failureDetails).length === 0
  );
  expectThrows(
    "\u672A\u77E5\u7EC8\u6B62\u539F\u56E0\u88AB\u62D2\u7EDD",
    () => parseStreamEvent(EVENT_STREAM_CLOSED, { streamId: "s", reason: "done" }),
    "BAD_PAYLOAD"
  );
  const aggregator = new AssistantStreamAggregator();
  expect("\u5FEB\u7167\u4E0D\u4EA7\u751F\u6587\u672C", !aggregator.pushItem({ type: "snapshot", records: [] }));
  const applied = aggregator.pushItem({
    type: "event",
    event: { type: "assistant/chunk", data: { chunk: { type: "block-start", index: 0 } } }
  });
  expect("block-start \u88AB\u5FFD\u7565", !applied);
  aggregator.pushItem({
    type: "event",
    event: { type: "assistant/chunk", data: { chunk: { type: "text-delta", index: 0, text: "\u4F60" } } }
  });
  aggregator.pushItem({
    type: "event",
    event: { type: "assistant/chunk", data: { chunk: { type: "text-delta", index: 0, text: "\u597D" } } }
  });
  aggregator.pushItem({
    type: "assistant-stream",
    frame: { type: "chunk", chunk: { type: "reasoning-delta", index: 1, text: "\u601D\u8003" } }
  });
  aggregator.pushItem({
    type: "event",
    event: { type: "assistant/chunk", data: { chunk: { type: "tool-call-delta", argumentsDelta: "{}" } } }
  });
  expect("\u6B63\u6587\u589E\u91CF\u6309\u5E8F\u62FC\u63A5", aggregator.text === "\u4F60\u597D", aggregator.text);
  expect("\u63A8\u7406\u589E\u91CF\u5355\u72EC\u4FDD\u5B58", aggregator.reasoning === "\u601D\u8003", aggregator.reasoning);
  expect("\u5DE5\u5177\u8C03\u7528\u4E0D\u8FDB\u5165\u6B63\u6587", aggregator.chunkCount === 3, String(aggregator.chunkCount));
  aggregator.reset();
  expect("reset \u6E05\u7A7A\u6587\u672C\u4E0E\u8BA1\u6570", aggregator.text === "" && aggregator.chunkCount === 0);
  return { ok: failures.length === 0, checks, failures };
}
function interactionsSelfTest() {
  const checks = [];
  const failures = [];
  const expect = (name, pass, detail = "") => {
    if (pass) checks.push(name);
    else failures.push(detail.length > 0 ? `${name}\uFF1A${detail}` : name);
  };
  const expectThrows = (name, run, code) => {
    try {
      run();
      expect(name, false, "\u672C\u5E94\u629B\u9519\u5374\u901A\u8FC7\u4E86");
    } catch (e) {
      const actual = e instanceof ProtocolError ? e.code : "NOT_PROTOCOL_ERROR";
      expect(name, actual === code, `\u671F\u671B ${code}\uFF0C\u5B9E\u9645 ${actual}`);
    }
  };
  const stream = createEventsStreamRequest("events-1");
  const streamPayload = stream.payload;
  expect("$events \u8D70 stream.open", streamPayload.method === STREAM_OPEN_METHOD, streamPayload.method);
  expect(
    "$events endpoint \u6B63\u786E",
    streamPayload.params.endpoint === EVENTS_ENDPOINT,
    streamPayload.params.endpoint
  );
  expect(
    "$events \u53C2\u6570\u4E3A\u7A7A\u5BF9\u8C61",
    Object.keys(streamPayload.params.payload.args).length === 0
  );
  const ready = parseEventsFrame({ type: "ready", clientId: "client-1" });
  expect(
    "ready \u89E3\u6790\u51FA clientId",
    ready?.kind === "ready" && ready.clientId === "client-1"
  );
  expectThrows(
    "ready \u7F3A clientId \u88AB\u62D2\u7EDD",
    () => parseEventsFrame({ type: "ready" }),
    "BAD_PAYLOAD"
  );
  expectThrows("\u975E\u5BF9\u8C61\u5E27\u88AB\u62D2\u7EDD", () => parseEventsFrame("x"), "BAD_PAYLOAD");
  const approval = parseEventsFrame({
    type: "waterfall",
    event: "approval/request",
    eventId: "evt-a-1",
    agentId: "session-1",
    request: { toolName: "bash", callId: "call-1", reason: "\u9700\u8981\u5220\u9664\u6587\u4EF6" }
  });
  expect("\u5BA1\u6279\u8BF7\u6C42\u89E3\u6790\u4E3A approval", approval?.kind === "approval");
  expect(
    "\u5BA1\u6279\u5B57\u6BB5\u88AB\u4FDD\u7559",
    approval?.kind === "approval" && approval.sessionId === "session-1" && approval.toolName === "bash" && approval.callId === "call-1" && approval.reason === "\u9700\u8981\u5220\u9664\u6587\u4EF6"
  );
  const defaultTool = parseEventsFrame({
    type: "waterfall",
    event: "approval/request",
    eventId: "evt-a-2",
    agentId: "session-1",
    request: {}
  });
  expect(
    "\u7F3A toolName \u65F6\u56DE\u9000\u9ED8\u8BA4\u503C",
    defaultTool?.kind === "approval" && defaultTool.toolName === "Harness tool"
  );
  const question = parseEventsFrame({
    type: "waterfall",
    event: "user-questions/request",
    eventId: "evt-q-1",
    agentId: "session-1",
    request: {
      questions: [
        {
          id: "q1",
          question: "\u9009\u62E9\u90E8\u7F72\u65B9\u5F0F",
          header: "\u90E8\u7F72",
          options: [{ label: "\u5BB9\u5668", description: "Docker" }, { label: "\u88F8\u673A" }],
          multiSelect: true
        },
        { id: "bad", question: "" },
        { question: "\u7F3A\u5C11 id" }
      ]
    }
  });
  expect("\u95EE\u7B54\u8BF7\u6C42\u89E3\u6790\u4E3A question", question?.kind === "question");
  expect(
    "\u574F\u95EE\u9898\u88AB\u4E22\u5F03",
    question?.kind === "question" && question.questions.length === 1,
    question?.kind === "question" ? String(question.questions.length) : ""
  );
  const parsedQuestion = question?.kind === "question" ? question.questions[0] : void 0;
  expect(
    "\u95EE\u9898\u5B57\u6BB5\u88AB\u4FDD\u7559",
    parsedQuestion?.id === "q1" && parsedQuestion.header === "\u90E8\u7F72" && parsedQuestion.multiSelect === true
  );
  expect(
    "\u9009\u9879\u63CF\u8FF0\u88AB\u4FDD\u7559",
    parsedQuestion?.options?.length === 2 && parsedQuestion.options[0].description === "Docker"
  );
  const noQuestions = parseEventsFrame({
    type: "waterfall",
    event: "user-questions/request",
    eventId: "evt-q-2",
    agentId: "session-1",
    request: {}
  });
  expect(
    "\u7F3A questions \u65F6\u4E3A\u7A7A\u6570\u7EC4",
    noQuestions?.kind === "question" && noQuestions.questions.length === 0
  );
  expect(
    "\u672A\u77E5 waterfall \u4E8B\u4EF6\u88AB\u5FFD\u7565",
    parseEventsFrame({ type: "waterfall", event: "other/request", eventId: "e", agentId: "s", request: {} }) === void 0
  );
  expect("\u8F6C\u53D1\u4E8B\u4EF6\u88AB\u5FFD\u7565", parseEventsFrame({ type: "emit", event: "x", args: [] }) === void 0);
  const cancel = parseEventsFrame({ type: "cancel", eventId: "evt-a-1" });
  expect(
    "cancel \u89E3\u6790\u51FA eventId",
    cancel?.kind === "cancel" && cancel.eventId === "evt-a-1"
  );
  expectThrows(
    "cancel \u7F3A eventId \u88AB\u62D2\u7EDD",
    () => parseEventsFrame({ type: "cancel" }),
    "BAD_PAYLOAD"
  );
  const allowed = createApprovalResultRequest("client-1", "evt-a-1", "allowed-once");
  const allowedPayload = allowed.payload;
  expect("\u5BA1\u6279\u56DE\u590D\u8D70 harness.remote.call", allowedPayload.method === REMOTE_CALL_METHOD);
  expect(
    "\u5BA1\u6279\u56DE\u590D endpoint \u4E3A $events/result",
    allowedPayload.params.endpoint === EVENTS_RESULT_ENDPOINT,
    allowedPayload.params.endpoint
  );
  const allowedArgs = allowedPayload.params.payload.args;
  expect(
    "\u5BA1\u6279\u56DE\u590D\u5E26 clientId \u4E0E eventId",
    allowedArgs.clientId === "client-1" && allowedArgs.eventId === "evt-a-1"
  );
  expect(
    "allowed-once \u539F\u6837\u4F5C\u4E3A\u7ED3\u679C\u503C",
    allowedArgs.outcome.kind === "result" && allowedArgs.outcome.value === "allowed-once"
  );
  const rejectedOutcome = createApprovalResultRequest("client-1", "evt-a-1", "rejected");
  const rejectedValue = rejectedOutcome.payload.params.payload.args.outcome.value;
  expect("rejected \u539F\u6837\u4F5C\u4E3A\u7ED3\u679C\u503C", rejectedValue === "rejected");
  expectThrows(
    "\u672A\u77E5\u5BA1\u6279\u7ED3\u679C\u88AB\u62D2\u7EDD",
    () => createApprovalResultRequest("client-1", "evt-a-1", "always"),
    "BAD_PAYLOAD"
  );
  expectThrows(
    "\u7F3A clientId \u7684\u56DE\u590D\u88AB\u62D2\u7EDD",
    () => createApprovalResultRequest("", "evt-a-1", "allowed-once"),
    "BAD_PAYLOAD"
  );
  const answerRequest = createQuestionResultRequest("client-1", "evt-q-1", [
    { id: "q1", selected: ["\u5BB9\u5668"], custom: "\u52A0\u76D1\u63A7" },
    { id: "q2", selected: [], custom: "" }
  ]);
  const answerValue = answerRequest.payload.params.payload.args.outcome.value;
  expect("\u95EE\u7B54\u56DE\u590D\u5E26\u4E24\u6761\u7B54\u6848", answerValue.answers.length === 2);
  expect("selected \u539F\u6837\u4FDD\u7559", answerValue.answers[0].selected[0] === "\u5BB9\u5668");
  expect("custom \u4FDD\u7559", answerValue.answers[0].custom === "\u52A0\u76D1\u63A7");
  expect("\u7A7A custom \u88AB\u7701\u7565", !("custom" in answerValue.answers[1]));
  expectThrows(
    "\u7A7A\u7B54\u6848\u6570\u7EC4\u88AB\u62D2\u7EDD",
    () => createQuestionResultRequest("client-1", "evt-q-1", []),
    "BAD_PAYLOAD"
  );
  expectThrows(
    "selected \u7C7B\u578B\u9519\u8BEF\u88AB\u62D2\u7EDD",
    () => createQuestionResultRequest("client-1", "evt-q-1", [
      { id: "q1", selected: [1] }
    ]),
    "BAD_PAYLOAD"
  );
  const localReject = createRejectedResultRequest("client-1", "evt-q-1", "ASK_TIMED_OUT", "\u56DE\u7B54\u7A97\u53E3\u7ED3\u675F");
  const localOutcome = localReject.payload.params.payload.args.outcome;
  expect("\u672C\u5730\u62D2\u7EDD\u4E3A rejected \u5206\u652F", localOutcome.kind === "rejected");
  expect(
    "\u62D2\u7EDD\u9519\u8BEF\u5B57\u6BB5\u5B8C\u6574",
    localOutcome.error.name === "UserQuestionError" && localOutcome.error.code === "ASK_TIMED_OUT" && localOutcome.error.message === "\u56DE\u7B54\u7A97\u53E3\u7ED3\u675F"
  );
  expectThrows(
    "\u62D2\u7EDD\u7F3A code \u88AB\u62D2\u7EDD",
    () => createRejectedResultRequest("client-1", "evt-q-1", "", "x"),
    "BAD_PAYLOAD"
  );
  return { ok: failures.length === 0, checks, failures };
}
function recoverySelfTest() {
  const checks = [];
  const failures = [];
  const expect = (name, pass, detail = "") => {
    if (pass) checks.push(name);
    else failures.push(detail.length > 0 ? `${name}\uFF1A${detail}` : name);
  };
  const kinds = (actions) => actions.map((action) => action.kind).join(",");
  expect("\u9996\u6B21\u7B49\u5F85 500ms", nextReconnectDelay(1) === 500, String(nextReconnectDelay(1)));
  expect("\u7B2C\u4E8C\u6B21\u7B49\u5F85\u7FFB\u500D", nextReconnectDelay(2) === 1e3, String(nextReconnectDelay(2)));
  expect("\u7B2C\u4E09\u6B21\u7B49\u5F85 2000ms", nextReconnectDelay(3) === 2e3, String(nextReconnectDelay(3)));
  expect(
    "\u7B49\u5F85\u5C01\u9876 30s",
    nextReconnectDelay(20) === DEFAULT_RECONNECT_POLICY.maxDelayMs,
    String(nextReconnectDelay(20))
  );
  expect("attempt 0 \u6309 1 \u5904\u7406", nextReconnectDelay(0) === 500);
  expect("\u975E\u6574\u6570 attempt \u6309 1 \u5904\u7406", nextReconnectDelay(2.5) === 500);
  try {
    nextReconnectDelay(1, { baseDelayMs: 0, maxDelayMs: 1e3, factor: 2 });
    expect("\u975E\u6CD5\u7B56\u7565\u88AB\u62D2\u7EDD", false, "\u672C\u5E94\u629B\u9519");
  } catch (e) {
    expect("\u975E\u6CD5\u7B56\u7565\u88AB\u62D2\u7EDD", e instanceof ProtocolError && e.code === "BAD_PAYLOAD");
  }
  const epoch = new ConnectionEpoch();
  expect("\u521D\u59CB\u4EE3\u6B21\u4E3A 0", epoch.current === 0);
  expect("\u672A\u8FDE\u63A5\u65F6\u4EFB\u4F55\u4EE3\u6B21\u90FD\u65E0\u6548", !epoch.isCurrent(0) && !epoch.isCurrent(1));
  const first = epoch.begin();
  expect("begin \u540E\u4ECE 1 \u5F00\u59CB", first === 1);
  expect("\u5F53\u4EE3\u6B21\u6709\u6548", epoch.isCurrent(first));
  const second = epoch.begin();
  expect("\u91CD\u8FDE\u540E\u4EE3\u6B21\u9012\u589E", second === 2);
  expect("\u65E7\u4EE3\u6B21\u88AB\u4E22\u5F03", !epoch.isCurrent(first) && epoch.isCurrent(second));
  const bare = planRecovery();
  expect(
    "\u65E0\u72B6\u6001\u65F6\u4ECD\u5237\u65B0\u5217\u8868\u5E76\u91CD\u5EFA\u4E8B\u4EF6\u6D41",
    kinds(bare) === "refreshSessions,reopenEvents",
    kinds(bare)
  );
  const full = planRecovery({
    activeSessionId: "session-1",
    pendingRequestIds: ["req-1", "req-2", ""],
    pendingApprovalEventIds: ["evt-1", ""]
  });
  expect(
    "\u6062\u590D\u987A\u5E8F\uFF1A\u5931\u8D25 \u2192 \u5237\u65B0 \u2192 \u8DDF\u968F \u2192 \u4E8B\u4EF6 \u2192 \u6E05\u5BA1\u6279",
    kinds(full) === "failPendingRequest,failPendingRequest,refreshSessions,reopenFollow,reopenEvents,dropLocalApproval",
    kinds(full)
  );
  const failed = full.filter((action) => action.kind === "failPendingRequest");
  expect("\u6BCF\u4E2A\u5728\u9014\u8BF7\u6C42\u5404\u4E00\u6761\u5931\u8D25\u52A8\u4F5C", failed.length === 2);
  expect(
    "\u5931\u8D25\u7801\u4E3A UNKNOWN_OUTCOME",
    failed.every((action) => action.code === "UNKNOWN_OUTCOME")
  );
  expect(
    "\u7A7A requestId \u88AB\u8FC7\u6EE4",
    failed.every((action) => action.requestId.length > 0)
  );
  const follow = full.find((action) => action.kind === "reopenFollow");
  expect(
    "\u8DDF\u968F\u52A8\u4F5C\u6307\u5411\u5F53\u524D\u4F1A\u8BDD",
    follow?.kind === "reopenFollow" && follow.sessionId === "session-1"
  );
  const dropped = full.filter((action) => action.kind === "dropLocalApproval");
  expect(
    "\u6BCF\u6761\u672C\u5730\u5BA1\u6279\u5404\u4E00\u6761\u6E05\u7406\u52A8\u4F5C",
    dropped.length === 1 && dropped[0].eventId === "evt-1"
  );
  const noSession = planRecovery({ activeSessionId: "" });
  expect(
    "\u65E0\u5F53\u524D\u4F1A\u8BDD\u65F6\u4E0D\u91CD\u5F00\u8DDF\u968F\u6D41",
    !noSession.some((action) => action.kind === "reopenFollow"),
    kinds(noSession)
  );
  return { ok: failures.length === 0, checks, failures };
}
function discoverySelfTest() {
  const checks = [];
  const failures = [];
  const expect = (name, pass, detail = "") => {
    if (pass) checks.push(name);
    else failures.push(detail.length > 0 ? `${name}\uFF1A${detail}` : name);
  };
  const expectThrows = (name, run, code) => {
    try {
      run();
      expect(name, false, "\u672C\u5E94\u629B\u9519\u5374\u901A\u8FC7\u4E86");
    } catch (e) {
      const actual = e instanceof ProtocolError ? e.code : "NOT_PROTOCOL_ERROR";
      expect(name, actual === code, `\u671F\u671B ${code}\uFF0C\u5B9E\u9645 ${actual}`);
    }
  };
  const hosts = readHostDevices({
    items: [
      {
        deviceId: "host-1",
        membershipId: "m-1",
        name: "\u5DE5\u4F5C\u7AD9",
        role: "host",
        platform: "windows",
        online: true,
        identityKey: "pk-1",
        clientVersion: "1.0.0",
        harnessVersion: "0.9.2",
        lastSeenAt: 17e11
      },
      {
        deviceId: "host-2",
        membershipId: "m-1",
        name: "\u5907\u7528\u673A",
        role: "host",
        platform: "linux",
        identityKey: "pk-2"
      },
      { deviceId: "client-1", membershipId: "m-1", name: "\u624B\u673A", role: "client", platform: "android", identityKey: "pk-3" },
      { deviceId: "host-3", name: "\u7F3A membershipId", role: "host", platform: "macos", identityKey: "pk-4" },
      { deviceId: "host-4", membershipId: "m-1", role: "host", platform: "macos" }
    ]
  });
  expect("\u53EA\u4FDD\u7559 host \u8BBE\u5907", hosts.length === 2, String(hosts.length));
  expect(
    "\u5728\u7EBF\u72B6\u6001\u4E0E\u7248\u672C\u88AB\u4FDD\u7559",
    hosts[0].deviceId === "host-1" && hosts[0].online === true && hosts[0].clientVersion === "1.0.0" && hosts[0].harnessVersion === "0.9.2" && hosts[0].lastSeenAt === 17e11
  );
  expect("\u8EAB\u4EFD\u516C\u94A5\u88AB\u4FDD\u7559", hosts[0].identityKey === "pk-1", hosts[0].identityKey);
  expect("\u7F3A online \u65F6\u6309\u79BB\u7EBF\u5904\u7406", hosts[1].online === false);
  expect(
    "\u574F\u6761\u76EE\u88AB\u4E22\u5F03",
    !hosts.some((host) => host.deviceId === "host-3" || host.deviceId === "host-4")
  );
  expect("\u975E\u5BF9\u8C61\u54CD\u5E94\u8FD4\u56DE\u7A7A\u5217\u8868", readHostDevices("x").length === 0);
  expect("items \u975E\u6570\u7EC4\u8FD4\u56DE\u7A7A\u5217\u8868", readHostDevices({ items: {} }).length === 0);
  const follow = createWorkspaceFollowRequest("ws-1");
  const followPayload = follow.payload;
  expect("\u5DE5\u4F5C\u533A\u8D70 stream.open", followPayload.method === STREAM_OPEN_METHOD, followPayload.method);
  expect(
    "\u5DE5\u4F5C\u533A endpoint \u4E3A workspace/follow",
    followPayload.params.endpoint === WORKSPACE_FOLLOW_ENDPOINT,
    followPayload.params.endpoint
  );
  expect(
    "\u5DE5\u4F5C\u533A\u53C2\u6570\u4E3A\u7A7A\u5BF9\u8C61",
    Object.keys(followPayload.params.payload.args).length === 0
  );
  const baseline = readWorkspaceBaseline({
    type: "baseline",
    value: {
      items: [
        {
          workspaceId: "ws-1",
          path: "D:/work/a",
          title: "\u9879\u76EE A",
          sessionIds: ["s-1", 2, "s-2"],
          createdAt: "2026-11-01T00:00:00Z",
          updatedAt: "2026-11-20T00:00:00Z"
        },
        { workspaceId: "ws-2" },
        { title: "\u7F3A workspaceId" }
      ],
      archivedSessionIds: ["s-old", 3],
      pinnedSessionIds: void 0
    }
  });
  expect("\u57FA\u7EBF\u89E3\u6790\u51FA\u4E24\u4E2A\u5DE5\u4F5C\u533A", baseline.items.length === 2, String(baseline.items.length));
  expect(
    "\u5DE5\u4F5C\u533A\u5B57\u6BB5\u88AB\u4FDD\u7559",
    baseline.items[0].workspaceId === "ws-1" && baseline.items[0].title === "\u9879\u76EE A" && baseline.items[0].path === "D:/work/a" && baseline.items[0].updatedAt === "2026-11-20T00:00:00Z"
  );
  expect(
    "sessionIds \u8FC7\u6EE4\u975E\u5B57\u7B26\u4E32",
    baseline.items[0].sessionIds.length === 2 && baseline.items[0].sessionIds[1] === "s-2"
  );
  expect(
    "\u7F3A path/title \u65F6\u56DE\u9000\u7A7A\u4E32",
    baseline.items[1].path === "" && baseline.items[1].title === ""
  );
  expect(
    "\u5F52\u6863\u4F1A\u8BDD\u8FC7\u6EE4\u975E\u5B57\u7B26\u4E32",
    baseline.archivedSessionIds.length === 1 && baseline.archivedSessionIds[0] === "s-old"
  );
  expect("\u7F3A\u7701\u7F6E\u9876\u5217\u8868\u4E3A\u7A7A", baseline.pinnedSessionIds.length === 0);
  expect("\u975E\u5BF9\u8C61\u57FA\u7EBF\u503C\u5BB9\u9519\u4E3A\u7A7A\u5217\u8868", readWorkspaceList("x").items.length === 0);
  expectThrows(
    "\u9996\u5E27\u4E0D\u662F baseline \u88AB\u62D2\u7EDD",
    () => readWorkspaceBaseline({ type: "event", value: {} }),
    "BAD_PAYLOAD"
  );
  expectThrows(
    "baseline \u7F3A value \u88AB\u62D2\u7EDD",
    () => readWorkspaceBaseline({ type: "baseline" }),
    "BAD_PAYLOAD"
  );
  return { ok: failures.length === 0, checks, failures };
}
function conversationSelfTest() {
  const checks = [];
  const failures = [];
  const expect = (name, pass, detail = "") => {
    if (pass) checks.push(name);
    else failures.push(detail.length > 0 ? `${name}\uFF1A${detail}` : name);
  };
  const expectThrows = (name, run, code) => {
    try {
      run();
      expect(name, false, "\u672C\u5E94\u629B\u9519\u5374\u901A\u8FC7\u4E86");
    } catch (e) {
      const actual = e instanceof ProtocolError ? e.code : "NOT_PROTOCOL_ERROR";
      expect(name, actual === code, `\u671F\u671B ${code}\uFF0C\u5B9E\u9645 ${actual}`);
    }
  };
  let seq = 0;
  const eventMessage = (event, data) => JSON.stringify({
    v: 1,
    id: `t-${++seq}`,
    type: "event",
    timestamp: 1,
    payload: { event, data }
  });
  const response = (requestId) => JSON.stringify({
    v: 1,
    id: `t-${++seq}`,
    type: "rpc.response",
    timestamp: 1,
    payload: { requestId, result: { ok: true } }
  });
  const error = (requestId, code, message) => JSON.stringify({
    v: 1,
    id: `t-${++seq}`,
    type: "rpc.error",
    timestamp: 1,
    payload: { requestId, code, message, retryable: false }
  });
  const streamValue = (streamId, value) => eventMessage("harness.remote.frame", { streamId, hasValue: true, value });
  const streamClosed = (streamId, reason) => eventMessage("harness.remote.stream.closed", { streamId, reason });
  const texts = [];
  const failed = [];
  const approvals = [];
  const questions = [];
  let ready;
  let cancelled = "";
  let closed = "";
  const controller = new ConversationController({
    onText: (text) => texts.push(text),
    onRequestFailed: (requestId, code) => failed.push(`${requestId}:${code}`),
    onClientReady: (clientId) => {
      ready = clientId;
    },
    onApproval: (request) => approvals.push(request.eventId),
    onQuestion: (request) => questions.push(request.eventId),
    onInteractionCancelled: (eventId) => {
      cancelled = eventId;
    },
    onStreamClosed: (reason) => {
      closed = reason;
    }
  });
  expectThrows("\u672A\u9009\u4F1A\u8BDD\u4E0D\u80FD\u53D1 Prompt", () => controller.sendPrompt("hi"), "BAD_PAYLOAD");
  const follow = controller.attachSession("session-1", "follow-1");
  const followPayload = follow.payload;
  expect(
    "\u7ED1\u5B9A\u4F1A\u8BDD\u8D70 session/follow",
    followPayload.params.endpoint === "session/follow",
    followPayload.params.endpoint
  );
  expect("\u8DDF\u968F\u6D41 id \u88AB\u767B\u8BB0", followPayload.params.streamId === "follow-1");
  expect(
    "\u8DDF\u968F\u5730\u5740\u6307\u5411\u4F1A\u8BDD",
    followPayload.params.payload.args.request.address.kind === "session" && followPayload.params.payload.args.request.address.sessionId === "session-1"
  );
  expect("\u4F1A\u8BDD id \u53EF\u8BFB\u53D6", controller.sessionId === "session-1");
  const events = controller.openEvents("events-1");
  expect(
    "\u4E8B\u4EF6\u6D41 endpoint \u4E3A $events",
    events.payload.params.endpoint === "$events"
  );
  const prompt = controller.sendPrompt("\u4F60\u597D");
  const promptPayload = prompt.payload;
  const promptRequest = promptPayload.params.payload.args.request;
  expect("Prompt endpoint \u4E3A session/prompt", promptPayload.params.endpoint === "session/prompt");
  expect("Prompt \u6587\u672C\u6B63\u786E", promptRequest.content[0].text === "\u4F60\u597D");
  expect(
    "Prompt \u5E26\u4F1A\u8BDD id \u4E0E queue \u6A21\u5F0F",
    promptRequest.sessionId === "session-1" && promptRequest.mode === "queue"
  );
  expect("Prompt \u5DF2\u767B\u8BB0\u4E3A\u5728\u9014", controller.pending.size === 1 && controller.streaming);
  const second = controller.sendPrompt("\u7B2C\u4E8C\u6761", "client-req-1");
  const secondRequest = second.payload.params.payload.args.request;
  expect("\u663E\u5F0F requestId \u539F\u6837\u4FDD\u7559", secondRequest.requestId === "client-req-1");
  controller.handleMessage(response(prompt.id));
  expect(
    "\u54CD\u5E94\u540E\u4ECD\u5728\u6D41\u5F0F\u4E2D\uFF08\u8FD8\u6709\u4E00\u6761\u5728\u9014\uFF09",
    controller.pending.size === 1 && controller.streaming
  );
  controller.handleMessage(error(second.id, "SESSION_BUSY", "\u4F1A\u8BDD\u5FD9"));
  expect(
    "\u5931\u8D25\u56DE\u8C03\u5E26\u9519\u8BEF\u7801",
    failed.length === 1 && failed[0].endsWith("SESSION_BUSY"),
    failed.join(",")
  );
  expect("\u5168\u90E8\u7ED3\u7B97\u540E\u505C\u6B62\u6D41\u5F0F", !controller.streaming && controller.pending.size === 0);
  controller.handleMessage(streamValue("follow-1", {
    type: "event",
    event: { type: "assistant/chunk", data: { chunk: { type: "text-delta", text: "\u4F60\u597D" } } }
  }));
  controller.handleMessage(streamValue("follow-1", {
    type: "assistant/chunk",
    frame: void 0
  }));
  controller.handleMessage(streamValue("follow-1", {
    type: "assistant-stream",
    frame: { type: "chunk", chunk: { type: "text-delta", text: "\uFF0C\u4E16\u754C" } }
  }));
  controller.handleMessage(streamValue("follow-1", {
    type: "assistant-stream",
    frame: { type: "chunk", chunk: { type: "reasoning-delta", text: "\u601D\u8003" } }
  }));
  expect("\u6587\u672C\u589E\u91CF\u88AB\u7D2F\u8BA1", controller.aggregator.text === "\u4F60\u597D\uFF0C\u4E16\u754C", controller.aggregator.text);
  expect("onText \u6536\u5230\u7D2F\u8BA1\u503C", texts.length === 3 && texts[2] === "\u4F60\u597D\uFF0C\u4E16\u754C", String(texts.length));
  expect("\u63A8\u7406\u589E\u91CF\u5355\u72EC\u7D2F\u8BA1", controller.aggregator.reasoning === "\u601D\u8003");
  expect(
    "\u672A\u77E5\u4E8B\u4EF6\u88AB\u5FFD\u7565",
    (controller.handleMessage(eventMessage("other/event", { streamId: "follow-1" })), true)
  );
  controller.handleMessage(streamClosed("follow-1", "completed"));
  expect("\u6D41\u7ED3\u675F\u56DE\u8C03\u539F\u56E0", closed === "completed", closed);
  expect("\u6D41\u7ED3\u675F\u540E\u4E0D\u518D\u6D41\u5F0F", !controller.streaming);
  controller.handleMessage(streamValue("events-1", { type: "ready", clientId: "client-1" }));
  expect(
    "ready \u5199\u5165 clientId",
    ready === "client-1" && controller.clientId === "client-1"
  );
  controller.handleMessage(streamValue("events-1", {
    type: "waterfall",
    event: "approval/request",
    eventId: "evt-a",
    agentId: "session-1",
    request: { toolName: "bash" }
  }));
  expect("\u5BA1\u6279\u8BF7\u6C42\u8FDB\u5165\u5F85\u529E", approvals.length === 1 && controller.pendingInteractions.length === 1);
  const approvalReply = controller.approve("evt-a", "allowed-once");
  const approvalOutcome = approvalReply.payload.params;
  expect("\u5BA1\u6279\u56DE\u590D\u8D70 $events/result", approvalOutcome.endpoint === "$events/result");
  expect(
    "\u5BA1\u6279\u7ED3\u679C\u503C\u6B63\u786E",
    approvalOutcome.payload.args.outcome.kind === "result" && approvalOutcome.payload.args.outcome.value === "allowed-once"
  );
  expect("\u56DE\u590D\u540E\u79FB\u9664\u672C\u5730\u5BA1\u6279", controller.pendingInteractions.length === 0);
  controller.handleMessage(streamValue("events-1", {
    type: "waterfall",
    event: "user-questions/request",
    eventId: "evt-q",
    agentId: "session-1",
    request: { questions: [{ id: "q1", question: "\u9009\u4E00\u4E2A" }] }
  }));
  expect("\u95EE\u7B54\u8BF7\u6C42\u8FDB\u5165\u5F85\u529E", questions.length === 1 && controller.pendingInteractions.length === 1);
  const answerReply = controller.answerQuestion("evt-q", [{ id: "q1", selected: ["A"] }]);
  const answerValue = answerReply.payload.params.payload.args.outcome.value;
  expect(
    "\u95EE\u7B54\u7B54\u6848\u88AB\u7F16\u7801",
    answerValue.answers.length === 1 && answerValue.answers[0].selected[0] === "A"
  );
  expect("\u56DE\u7B54\u540E\u79FB\u9664\u672C\u5730\u95EE\u7B54", controller.pendingInteractions.length === 0);
  controller.handleMessage(streamValue("events-1", {
    type: "waterfall",
    event: "user-questions/request",
    eventId: "evt-r",
    agentId: "session-1",
    request: { questions: [] }
  }));
  const rejectReply = controller.rejectInteraction("evt-r", "ASK_TIMED_OUT", "\u7A97\u53E3\u7ED3\u675F");
  expect(
    "\u672C\u5730\u62D2\u7EDD\u8D70 rejected \u5206\u652F",
    rejectReply.payload.params.payload.args.outcome.kind === "rejected"
  );
  controller.handleMessage(streamValue("events-1", {
    type: "waterfall",
    event: "user-questions/request",
    eventId: "evt-c",
    agentId: "session-1",
    request: { questions: [] }
  }));
  controller.handleMessage(streamValue("events-1", { type: "cancel", eventId: "evt-c" }));
  expect(
    "cancel \u79FB\u9664\u5F85\u529E\u5E76\u56DE\u8C03",
    cancelled === "evt-c" && controller.pendingInteractions.length === 0
  );
  controller.attachSession("session-2", "follow-2");
  expect(
    "\u5207\u6362\u4F1A\u8BDD\u91CD\u7F6E\u805A\u5408\u6587\u672C",
    controller.aggregator.text === "" && controller.sessionId === "session-2"
  );
  const lastPrompt = controller.sendPrompt("\u65AD\u7EBF\u524D\u7684\u6700\u540E\u4E00\u6761");
  const plan = controller.disconnect();
  const kinds = plan.map((action) => action.kind).join(",");
  expect(
    "\u65AD\u7EBF\u8BA1\u5212\u5305\u542B\u5168\u90E8\u6062\u590D\u6B65\u9AA4",
    kinds === "failPendingRequest,refreshSessions,reopenFollow,reopenEvents",
    kinds
  );
  expect(
    "\u5728\u9014 prompt \u88AB\u6807\u8BB0\u672A\u77E5",
    plan[0].kind === "failPendingRequest" && plan[0].requestId === lastPrompt.id
  );
  expect(
    "\u65AD\u7EBF\u6E05\u7A7A clientId \u4E0E\u6D41\u5F0F\u72B6\u6001",
    controller.clientId === void 0 && !controller.streaming && controller.pending.size === 0
  );
  expect(
    "\u65AD\u7EBF\u540E\u4E0D\u80FD\u56DE\u590D\u5BA1\u6279",
    (() => {
      try {
        controller.approve("evt-x", "rejected");
        return false;
      } catch (e) {
        return e instanceof ProtocolError && e.code === "BAD_PAYLOAD";
      }
    })()
  );
  return { ok: failures.length === 0, checks, failures };
}
export {
  authState,
  controlSelfTest,
  conversationSelfTest,
  detailUi,
  discoverySelfTest,
  fetch,
  fingerprintSelfTest,
  health,
  interactionsSelfTest,
  login,
  logout,
  onAction,
  protocolSelfTest,
  recoverySelfTest,
  rpcSelfTest,
  selfTest,
  sessionEvent,
  sessionStart,
  sessionStop,
  sessionsSelfTest,
  streamSelfTest
};
/*! Bundled license information:

@noble/curves/utils.js:
@noble/curves/abstract/modular.js:
@noble/curves/abstract/curve.js:
@noble/curves/abstract/edwards.js:
@noble/curves/abstract/montgomery.js:
@noble/curves/ed25519.js:
  (*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) *)

@noble/ciphers/utils.js:
  (*! noble-ciphers - MIT License (c) 2023 Paul Miller (paulmillr.com) *)
*/
