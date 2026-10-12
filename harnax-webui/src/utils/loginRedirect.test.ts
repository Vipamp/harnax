import { loginRedirect } from './loginRedirect';

describe('the return address a login page hands back', () => {
  it('keeps the pathname and query string of an ordinary page', () => {
    expect(loginRedirect({ pathname: '/system/user', search: '?page=2' })).toBe(
      '?redirect=%2Fsystem%2Fuser%3Fpage%3D2',
    );
  });

  it('sends the oauth landing back to the mcp list instead of replaying the one-time code', () => {
    expect(loginRedirect({ pathname: '/mcp/oauth/callback', search: '?code=abc&state=xyz' })).toBe(
      '?redirect=%2Fcontext%2Fmcp',
    );
  });
});
