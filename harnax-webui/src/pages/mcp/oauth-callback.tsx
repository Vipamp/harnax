import React, { useEffect, useState } from 'react';
import { Button, Card, Result, Space, Spin, Tag, Typography } from 'antd';
import { history, useIntl, useLocation } from '@umijs/max';
import { exchangeMcpOAuthCode } from '@/services/ant-design-pro/mcp';

const { Text } = Typography;

/** 本页面自己的路径，抹掉 query 时原样填回去 */
const CALLBACK_PATH = '/mcp/oauth/callback';

type Outcome = {
  authorized: boolean;
  message: string;
  scopes: string[];
  expiresAt?: string;
};

/**
 * 授权服务器把浏览器送回这里，页面只做三件事：读走 URL 上的 code 与 state、把它们交给带 JWT 的
 * 换票接口、用一句人话把结果说清楚。
 *
 * query 在读完的下一刻就被 history.replace 抹掉：code 是一次性的，但它在几分钟内是有效的，
 * 留在地址栏里就会进浏览器历史，也容易被顺手复制走。这四个值只停在 effect 里的一个局部对象上、
 * 立刻发出去，不进 useState 也不写 localStorage —— 换票认的是调用者的登录态，凭据最终落到谁的
 * 账号上由后端比对 state 的归属。失败时页面只给「返回 MCP」一个动作：重新发起要点的是详情页
 * 那个按钮，这里重发一次同一个 code 不会有别的下场。
 */
const McpOAuthCallbackPage: React.FC = () => {
  const intl = useIntl();
  const location = useLocation();
  const [waiting, setWaiting] = useState(true);
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const request: API.McpOAuthExchangeRequest = {
      code: params.get('code') || undefined,
      state: params.get('state') || undefined,
      error: params.get('error') || undefined,
      errorDescription: params.get('error_description') || undefined,
    };
    // 换票还没发出去就先抹：无论后面成功还是失败，这个页面都不再需要 query。
    history.replace(CALLBACK_PATH);

    if (!request.code && !request.state && !request.error) {
      // 直接打开这个地址（书签、误点）：没有可提交的东西，也就不去打扰后端。
      setWaiting(false);
      setOutcome({
        authorized: false,
        message: intl.formatMessage({
          id: 'pages.mcp.oauth.nothingToFinish',
          defaultMessage: 'There is nothing to complete here: an authorization server sends you to this page with a code.',
        }),
        scopes: [],
      });
      return;
    }

    let alive = true;
    const fail = (reason?: string) => {
      if (alive) {
        setOutcome({
          authorized: false,
          message: reason || intl.formatMessage({ id: 'pages.mcp.oauth.exchangeFailed', defaultMessage: 'The authorization could not be completed' }),
          scopes: [],
        });
        setWaiting(false);
      }
    };

    exchangeMcpOAuthCode(request)
      .then((res) => {
        if (!alive) {
          return;
        }
        if (res.code === 200 && res.data) {
          setOutcome({
            authorized: res.data.authorized,
            message: res.data.message || '',
            scopes: res.data.scopes || [],
            expiresAt: res.data.accessExpiresAt,
          });
        } else {
          fail(res.message);
        }
        setWaiting(false);
      })
      .catch((error: any) => {
        fail(error?.message || error?.info?.errorMessage);
      });

    return () => {
      alive = false;
    };
  }, []);

  const back = (
    <Button type="primary" onClick={() => history.push('/context/mcp')}>
      {intl.formatMessage({ id: 'pages.mcp.oauth.backToMcp', defaultMessage: 'Back to MCP services' })}
    </Button>
  );

  return (
    <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 64 }}>
      <Card variant="borderless" style={{ minWidth: 480 }}>
        {waiting ? (
          <div style={{ textAlign: 'center', padding: '48px 0' }}>
            <Spin />
            <div style={{ marginTop: 16 }}>
              <Text type="secondary">
                {intl.formatMessage({ id: 'pages.mcp.oauth.finishing', defaultMessage: 'Finishing the authorization...' })}
              </Text>
            </div>
          </div>
        ) : (
          <Result
            status={outcome?.authorized ? 'success' : 'error'}
            title={intl.formatMessage({
              id: outcome?.authorized ? 'pages.mcp.oauth.authorizeSuccess' : 'pages.mcp.oauth.authorizeFailed',
              defaultMessage: outcome?.authorized ? 'Authorized' : 'Authorization did not go through',
            })}
            subTitle={outcome?.message}
            extra={
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                {outcome?.authorized && outcome.scopes.length > 0 && (
                  <Space size={[4, 4]} wrap>
                    <Text type="secondary">
                      {intl.formatMessage({ id: 'pages.mcp.oauth.grantedScopes', defaultMessage: 'Granted scopes' })}
                    </Text>
                    {outcome.scopes.map((scope) => (
                      <Tag key={scope} style={{ margin: 0 }}>
                        {scope}
                      </Tag>
                    ))}
                  </Space>
                )}
                {outcome?.authorized && outcome.expiresAt && (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {intl.formatMessage(
                      { id: 'pages.mcp.oauth.expiresAt', defaultMessage: 'This authorization expires at {time}' },
                      { time: outcome.expiresAt },
                    )}
                  </Text>
                )}
                {back}
              </Space>
            }
          />
        )}
      </Card>
    </div>
  );
};

export default McpOAuthCallbackPage;
