// utils/crypto.ts
// 纯 JS 实现的 SHA-256（用于登录密码哈希，与 webui 的 CryptoJS.SHA256 一致）

function rightRotate(value: number, amount: number): number {
  return (value >>> amount) | (value << (32 - amount));
}

export function sha256(ascii: string): string {
  const mathPow = Math.pow;
  const maxWord = mathPow(2, 32);
  let result = '';

  const words: number[] = [];
  const asciiBitLength = ascii.length * 8;

  // 缓存的哈希值
  let hash: number[] = (sha256 as any).h || [];
  let k: number[] = (sha256 as any).k || [];
  let primeCounter = k.length;

  if (!primeCounter) {
    hash = (sha256 as any).h = [];
    k = (sha256 as any).k = [];
    const isComposite: Record<number, number> = {};
    for (let candidate = 2; primeCounter < 64; candidate++) {
      if (!isComposite[candidate]) {
        for (let i = 0; i < 313; i += candidate) {
          isComposite[i] = candidate;
        }
        hash[primeCounter] = (mathPow(candidate, 0.5) * maxWord) | 0;
        k[primeCounter++] = (mathPow(candidate, 1 / 3) * maxWord) | 0;
      }
    }
  }

  // UTF-8 编码
  let asciiUtf8 = unescape(encodeURIComponent(ascii));
  const bitLength = asciiUtf8.length * 8;

  asciiUtf8 += '\x80';
  while ((asciiUtf8.length % 64) - 56) asciiUtf8 += '\x00';
  for (let i = 0; i < asciiUtf8.length; i++) {
    const j = asciiUtf8.charCodeAt(i);
    if (j >> 8) return ''; // 非 8 位字符
    words[i >> 2] |= j << (((3 - i) % 4) * 8);
  }
  words[words.length] = (bitLength / maxWord) | 0;
  words[words.length] = bitLength;

  const h = hash.slice(0, 8);

  for (let j = 0; j < words.length; ) {
    const w = words.slice(j, (j += 16));
    const oldHash = h.slice(0);

    for (let i = 0; i < 64; i++) {
      const w15 = w[i - 15];
      const w2 = w[i - 2];

      const a = h[0];
      const e = h[4];
      const temp1 =
        h[7] +
        (rightRotate(e, 6) ^ rightRotate(e, 11) ^ rightRotate(e, 25)) +
        ((e & h[5]) ^ (~e & h[6])) +
        k[i] +
        (w[i] =
          i < 16
            ? w[i]
            : (w[i - 16] +
                (rightRotate(w15, 7) ^ rightRotate(w15, 18) ^ (w15 >>> 3)) +
                w[i - 7] +
                (rightRotate(w2, 17) ^ rightRotate(w2, 19) ^ (w2 >>> 10))) |
              0);

      const temp2 =
        (rightRotate(a, 2) ^ rightRotate(a, 13) ^ rightRotate(a, 22)) +
        ((a & h[1]) ^ (a & h[2]) ^ (h[1] & h[2]));

      h.unshift((temp1 + temp2) | 0);
      h[4] = (h[4] + temp1) | 0;
      h.pop();
    }

    for (let i = 0; i < 8; i++) {
      h[i] = (h[i] + oldHash[i]) | 0;
    }
  }

  for (let i = 0; i < 8; i++) {
    for (let j = 3; j + 1; j--) {
      const b = (h[i] >> (j * 8)) & 255;
      result += (b < 16 ? '0' : '') + b.toString(16);
    }
  }

  // 消除未使用变量告警
  void asciiBitLength;
  return result;
}
