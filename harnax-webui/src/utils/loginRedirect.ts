const oauthCallbackPath = '/mcp/oauth/callback';

/**
 * 跳登录页时带上「回来去哪」。
 *
 * 授权落地页是例外：它的 query 里是一次性的授权码与 state，原样带进登录页地址就等于把它们抄进
 * 浏览器历史、并跟着重定向再走一遍。登录已经打断这次授权了，所以送回 MCP 列表重新发起。
 */
export const loginRedirect = (location: { pathname: string; search: string }): string => {
  const target = location.pathname === oauthCallbackPath ? '/context/mcp' : location.pathname + location.search;
  return `?redirect=${encodeURIComponent(target)}`;
};
