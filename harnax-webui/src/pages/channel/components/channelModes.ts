/**
 * Which communication modes each channel type can actually run.
 *
 * This is a runtime constraint, not a preference: a mode with no transport implementation
 * produces a channel that starts, is never stopped, and never receives anything. The admin
 * service persists what it is told and `channel-service` only discovers the problem on its next
 * reconcile tick, so the list has to exist on this side too — keep it aligned with the
 * `supportsCallback()` / `getModeName()` implementations in harnax-channel.
 *
 * - feishu: WebSocket long connection, or HTTP callback (the one platform with an event contract)
 * - dingtalk: Stream only — no bot callback endpoint exists
 * - wecom: the smart-robot frame protocol over WebSocket — no callback endpoint
 * - wechat: iLink long polling only; the admin service also force-corrects this
 * - http: no adaptor is registered yet, so no mode works; kept visible but not recommended
 */
export const CHANNEL_MODES: Record<string, string[]> = {
  feishu: ['websocket', 'webhook'],
  dingtalk: ['stream'],
  wecom: ['websocket'],
  wechat: ['long_polling'],
  http: ['webhook'],
};

/** Recommended mode per type, used as the form default and as the fallback for a stale stored value. */
export const DEFAULT_CHANNEL_MODE: Record<string, string> = {
  feishu: 'websocket',
  dingtalk: 'stream',
  wecom: 'websocket',
  wechat: 'long_polling',
  http: 'webhook',
};

export function allowedModes(type?: string): string[] {
  if (!type) {
    return ['websocket', 'stream', 'long_polling', 'webhook'];
  }
  return CHANNEL_MODES[type] ?? [];
}

export function modeFor(type: string | undefined, stored: string | undefined): string {
  const fallback = DEFAULT_CHANNEL_MODE[type ?? ''] ?? 'websocket';
  if (!stored) {
    return fallback;
  }
  return allowedModes(type).includes(stored) ? stored : fallback;
}

/**
 * Feishu's callback mode authenticates with the Encrypt Key (the platform signs with it, and
 * refuses to sign at all without one), and the URL-verification handshake compares the payload's
 * `token` against the Verification Token — the SDK rejects the challenge when it does not match.
 * So both are mandatory in that mode and irrelevant in every other combination; a half-configured
 * callback channel fails at the platform's own verification step, where the operator cannot see why.
 */
export function requiresEncryptKey(type?: string, mode?: string): boolean {
  return type === 'feishu' && mode === 'webhook';
}
