import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Card, Checkbox, Descriptions, Form, Input, Modal, Space, Tag, Tooltip, Typography, message } from 'antd';
import { useIntl } from '@umijs/max';
import {
  KeyOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import {
  discoverMcpOAuth,
  getMcpOAuthAuthorizeUrl,
  getMcpOAuthStatus,
  revokeMcpOAuth,
  saveMcpOAuthClient,
} from '@/services/ant-design-pro/mcp';

const { Text, Paragraph } = Typography;

interface OAuthPanelProps {
  mcpId: number;
  config?: API.McpOAuthConfig;
}

/** 发现结果里 issuer 的来历，与后端 McpOAuthDiscoveryResponse.issuerSource 一一对应 */
const ISSUER_SOURCE_LABEL: Record<string, { id: string; defaultMessage: string }> = {
  CONFIG: { id: 'pages.mcp.oauth.issuerSourceConfig', defaultMessage: 'Filled in by hand' },
  PROTECTED_RESOURCE: {
    id: 'pages.mcp.oauth.issuerSourceProtectedResource',
    defaultMessage: 'RFC 9728 protected-resource metadata',
  },
  RESOURCE_METADATA: {
    id: 'pages.mcp.oauth.issuerSourceResourceMetadata',
    defaultMessage: 'resource_metadata of the 401 challenge',
  },
};

const EndpointValue: React.FC<{ value?: string; empty?: string }> = ({ value, empty }) =>
  value ? (
    <Paragraph copyable={{ text: value }} style={{ margin: 0, fontFamily: 'monospace', fontSize: 12 }}>
      {value}
    </Paragraph>
  ) : (
    <Text type="secondary">{empty || '-'}</Text>
  );

/**
 * OAuth 2.1 面板：回显库里的非敏感配置，给管理员发现与登记客户端两个动作，也给当前用户「去授权 /
 * 撤销自己这份凭据」的入口。
 *
 * 进页面只读一次授权状态：那是个不带副作用的查询。发现不是——它会向授权服务器发出站请求并写库，
 * 只能由管理员主动触发。客户端密钥也不回显明文，只有一个「是否已存」的状态。
 */
const OAuthPanel: React.FC<OAuthPanelProps> = ({ mcpId, config }) => {
  const intl = useIntl();
  const [discovery, setDiscovery] = useState<API.McpOAuthDiscoveryResponse | null>(null);
  const [discovering, setDiscovering] = useState(false);
  const [clientModalOpen, setClientModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [clearSecret, setClearSecret] = useState(false);
  const [form] = Form.useForm();
  const [grant, setGrant] = useState<API.McpOAuthStatusResponse | null>(null);
  // 「还没读到」与「读到了、没有凭据行」是两件事：前者什么都不知道，徽标不能替它断言未授权。
  const [grantUnknown, setGrantUnknown] = useState(true);
  const [authorizing, setAuthorizing] = useState(false);
  const [revoking, setRevoking] = useState(false);

  // 库里配了 issuer，或刚发现出来，才知道往哪个授权服务器登记客户端
  const knownIssuer = discovery?.issuer || config?.authorizationServer;

  const loadGrant = useCallback(async () => {
    try {
      const res = await getMcpOAuthStatus(mcpId);
      if (res.code === 200 && res.data) {
        setGrant(res.data);
        setGrantUnknown(false);
      } else {
        // 撤销成功后重读失败也走这里：库里已经变了，留着上一次的徽标就是说谎。
        setGrant(null);
        setGrantUnknown(true);
        message.error(res.message || intl.formatMessage({ id: 'pages.mcp.oauth.statusFailed', defaultMessage: 'Failed to read your authorization' }));
      }
    } catch (error: any) {
      setGrant(null);
      setGrantUnknown(true);
      message.error(
        error?.message || error?.info?.errorMessage ||
        intl.formatMessage({ id: 'pages.mcp.oauth.statusFailed', defaultMessage: 'Failed to read your authorization' }),
      );
    }
  }, [mcpId, intl]);

  // 「你授权过没有」是一个只读查询，所以进页面就读；发现不是，它出站并写库，只能由管理员点。
  useEffect(() => {
    loadGrant();
  }, [loadGrant]);

  const handleAuthorize = async () => {
    setAuthorizing(true);
    try {
      const res = await getMcpOAuthAuthorizeUrl(mcpId);
      if (res.code === 200 && res.data?.authorizeUrl) {
        // 同页跳转：window.open 受弹窗拦截，而授权完成后回跳的是另一个页面，留着这页没有意义。
        window.location.href = res.data.authorizeUrl;
        return;
      }
      message.error(res.message || intl.formatMessage({ id: 'pages.mcp.oauth.authorizeStartFailed', defaultMessage: 'Failed to start the authorization' }));
    } catch (error: any) {
      message.error(
        error?.message || error?.info?.errorMessage ||
        intl.formatMessage({ id: 'pages.mcp.oauth.authorizeStartFailed', defaultMessage: 'Failed to start the authorization' }),
      );
    } finally {
      setAuthorizing(false);
    }
  };

  const handleRevoke = () => {
    Modal.confirm({
      title: intl.formatMessage({ id: 'pages.mcp.oauth.revokeConfirm', defaultMessage: 'Revoke your authorization?' }),
      content: intl.formatMessage({
        id: 'pages.mcp.oauth.revokeConfirmHint',
        defaultMessage: 'This clears the stored credential for your account only; other users of this MCP server keep theirs.',
      }),
      okText: intl.formatMessage({ id: 'pages.mcp.oauth.revoke', defaultMessage: 'Revoke' }),
      okButtonProps: { danger: true },
      cancelText: intl.formatMessage({ id: 'pages.common.cancel', defaultMessage: 'Cancel' }),
      onOk: async () => {
        setRevoking(true);
        try {
          const res = await revokeMcpOAuth(mcpId);
          if (res.code === 200 && res.data) {
            // 上游有没有真的撤销，后端已经写在这句话里，这里不复述也不改写
            message.success(res.data.message || intl.formatMessage({ id: 'pages.mcp.oauth.revoked', defaultMessage: 'Revoked' }));
            await loadGrant();
          } else {
            message.error(res.message || intl.formatMessage({ id: 'pages.mcp.oauth.revokeFailed', defaultMessage: 'Failed to revoke' }));
          }
        } catch (error: any) {
          message.error(
            error?.message || error?.info?.errorMessage ||
            intl.formatMessage({ id: 'pages.mcp.oauth.revokeFailed', defaultMessage: 'Failed to revoke' }),
          );
        } finally {
          setRevoking(false);
        }
      },
    });
  };

  const handleDiscover = async () => {
    setDiscovering(true);
    try {
      const res = await discoverMcpOAuth(mcpId);
      if (res.code === 200 && res.data) {
        setDiscovery(res.data);
        message.success(intl.formatMessage({ id: 'pages.mcp.oauth.discoverSuccess', defaultMessage: 'Discovery completed' }));
      } else {
        message.error(res.message || intl.formatMessage({ id: 'pages.mcp.oauth.discoverFailed', defaultMessage: 'Discovery failed' }));
      }
    } catch (error: any) {
      message.error(
        error?.message || error?.info?.errorMessage ||
        intl.formatMessage({ id: 'pages.mcp.oauth.discoverFailed', defaultMessage: 'Discovery failed' }),
      );
    } finally {
      setDiscovering(false);
    }
  };

  const openClientModal = () => {
    setClearSecret(false);
    setClientModalOpen(true);
  };

  // 弹窗 destroyOnClose，表单要等它挂载后才存在，值就在挂载后填
  useEffect(() => {
    if (clientModalOpen) {
      form.setFieldsValue({ clientId: discovery?.clientId || '', clientSecret: '', callbackUrl: '' });
    }
  }, [clientModalOpen, discovery?.clientId, form]);

  const handleSaveClient = async () => {
    let values: any;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const data: API.McpOAuthClientRequest = { clientId: values.clientId.trim() };
    if (clearSecret) {
      // 空串才是后端的「清除」语义，留空不等
      data.clientSecret = '';
    } else if (values.clientSecret?.trim()) {
      data.clientSecret = values.clientSecret.trim();
    }
    if (values.callbackUrl?.trim()) {
      data.callbackUrl = values.callbackUrl.trim();
    }
    setSaving(true);
    try {
      const res = await saveMcpOAuthClient(mcpId, data);
      if (res.code === 200 && res.data) {
        setDiscovery(res.data);
        message.success(intl.formatMessage({ id: 'pages.mcp.oauth.clientSaved', defaultMessage: 'Client credentials saved' }));
        setClientModalOpen(false);
      } else {
        message.error(res.message || intl.formatMessage({ id: 'pages.mcp.oauth.clientSaveFailed', defaultMessage: 'Failed to save client credentials' }));
      }
    } catch (error: any) {
      message.error(
        error?.message || error?.info?.errorMessage ||
        intl.formatMessage({ id: 'pages.mcp.oauth.clientSaveFailed', defaultMessage: 'Failed to save client credentials' }),
      );
    } finally {
      setSaving(false);
    }
  };

  const scopeTags = (scopes?: string[]) =>
    scopes && scopes.length > 0 ? (
      <Space size={[4, 4]} wrap>
        {scopes.map((s) => (
          <Tag key={s} style={{ margin: 0 }}>{s}</Tag>
        ))}
      </Space>
    ) : (
      <Text type="secondary">-</Text>
    );

  /** 读不到就不画：三态只属于真读到的答案（没有行 / 有行但用不了 / 有可用的行） */
  const grantBadge = grantUnknown ? null : grant?.authorized ? (
    <Tag color="green" style={{ margin: 0 }}>
      {intl.formatMessage({ id: 'pages.mcp.oauth.authorized', defaultMessage: 'Authorized' })}
    </Tag>
  ) : grant?.status ? (
    <Tag color="orange" style={{ margin: 0 }}>
      {intl.formatMessage({ id: 'pages.mcp.oauth.needsConsent', defaultMessage: 'Needs re-authorizing' })}
    </Tag>
  ) : (
    <Tag style={{ margin: 0 }}>
      {intl.formatMessage({ id: 'pages.mcp.oauth.notAuthorized', defaultMessage: 'Not authorized' })}
    </Tag>
  );

  return (
    <Card
      title={
        <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <SafetyCertificateOutlined style={{ color: 'var(--vip-primary)' }} />
          {intl.formatMessage({ id: 'pages.mcp.oauth.title', defaultMessage: 'OAuth 2.1' })}
        </span>
      }
      extra={
        <Space>
          <Button icon={<SearchOutlined />} loading={discovering} onClick={handleDiscover}>
            {intl.formatMessage({ id: 'pages.mcp.oauth.discover', defaultMessage: 'Discover authorization server' })}
          </Button>
          <Tooltip
            title={
              knownIssuer
                ? undefined
                : intl.formatMessage({
                    id: 'pages.mcp.oauth.discoverFirst',
                    defaultMessage: 'Run discovery first: the client is registered against a specific issuer',
                  })
            }
          >
            <Button icon={<KeyOutlined />} disabled={!knownIssuer} onClick={openClientModal}>
              {intl.formatMessage({ id: 'pages.mcp.oauth.registerClient', defaultMessage: 'Register client' })}
            </Button>
          </Tooltip>
        </Space>
      }
      style={{
        borderRadius: '16px',
        border: '1px solid var(--vip-border)',
        boxShadow: 'var(--vip-card-shadow)',
        marginBottom: 16,
      }}
    >
      {/* 按人授权：这一段说的是「你」在这个 MCP 上有没有可用凭据，与上面的服务器配置是两件事 */}
      <div style={{ marginBottom: 16 }}>
        <Space size={8} wrap style={{ marginBottom: 8 }}>
          <Text strong>{intl.formatMessage({ id: 'pages.mcp.oauth.myAuthorization', defaultMessage: 'Your authorization' })}</Text>
          {grantBadge}
          <Button type="primary" size="small" loading={authorizing} onClick={handleAuthorize}>
            {intl.formatMessage({
              id: grant?.authorized ? 'pages.mcp.oauth.reauthorize' : 'pages.mcp.oauth.authorize',
              defaultMessage: 'Authorize',
            })}
          </Button>
          {grant?.status && (
            <Button size="small" danger loading={revoking} onClick={handleRevoke}>
              {intl.formatMessage({ id: 'pages.mcp.oauth.revoke', defaultMessage: 'Revoke' })}
            </Button>
          )}
        </Space>
        {grant?.scopes && grant.scopes.length > 0 && (
          <Space size={[4, 4]} wrap style={{ marginBottom: 4 }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {intl.formatMessage({ id: 'pages.mcp.oauth.grantedScopes', defaultMessage: 'Granted scopes' })}
            </Text>
            {grant.scopes.map((s) => (
              <Tag key={s} style={{ margin: 0 }}>{s}</Tag>
            ))}
          </Space>
        )}
        {grant?.accessExpiresAt && (
          <div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {intl.formatMessage(
                { id: 'pages.mcp.oauth.expiresAt', defaultMessage: 'This authorization expires at {time}' },
                { time: grant.accessExpiresAt },
              )}
            </Text>
          </div>
        )}
        {grant?.lastError && (
          <Alert type="warning" showIcon style={{ marginTop: 8 }} message={grant.lastError} />
        )}
      </div>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={intl.formatMessage({
          id: 'pages.mcp.oauth.runtimePending',
          defaultMessage: 'Authorizing here stores a credential for your account. The runtime still connects with the headers configured above until token injection is wired (design P3).',
        })}
      />

      <Descriptions
        bordered
        size="small"
        column={1}
        styles={{ label: { width: 200, background: 'var(--vip-bg-layout)' } }}
        items={[
          {
            key: 'issuer',
            label: intl.formatMessage({ id: 'pages.mcp.oauth.issuer', defaultMessage: 'Authorization Server' }),
            children: config?.authorizationServer ? (
              <Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{config.authorizationServer}</Text>
            ) : (
              <Text type="secondary">
                {intl.formatMessage({
                  id: 'pages.mcp.oauth.issuerAuto',
                  defaultMessage: 'Not set: resolved from the MCP url during discovery',
                })}
              </Text>
            ),
          },
          {
            key: 'scopes',
            label: intl.formatMessage({ id: 'pages.mcp.oauth.scopes', defaultMessage: 'Scopes' }),
            children: scopeTags(config?.scopes),
          },
          {
            key: 'audience',
            label: intl.formatMessage({ id: 'pages.mcp.oauth.audience', defaultMessage: 'Audience' }),
            children: config?.audience ? (
              <Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{config.audience}</Text>
            ) : (
              <Text type="secondary">-</Text>
            ),
          },
          {
            key: 'resourceIndicator',
            label: intl.formatMessage({ id: 'pages.mcp.oauth.resourceIndicator', defaultMessage: 'Resource indicator' }),
            children: (
              <Tag color={config?.resourceIndicator === false ? 'default' : 'blue'}>
                {config?.resourceIndicator === false
                  ? intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })
                  : intl.formatMessage({ id: 'pages.mcp.oauth.on', defaultMessage: 'On' })}
              </Tag>
            ),
          },
        ]}
      />

      {!discovery && (
        <Text type="secondary" style={{ display: 'block', marginTop: 12, fontSize: 12 }}>
          {intl.formatMessage({
            id: 'pages.mcp.oauth.notDiscovered',
            defaultMessage: 'Endpoints and client status show up after the first discovery run, which reaches out to the authorization server.',
          })}
        </Text>
      )}

      {discovery && (
        <div style={{ marginTop: 16 }}>
          {discovery.unknownScopes && discovery.unknownScopes.length > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={intl.formatMessage(
                { id: 'pages.mcp.oauth.unknownScopesWarn', defaultMessage: 'The authorization server did not advertise: {scopes}' },
                { scopes: discovery.unknownScopes.join(', ') },
              )}
            />
          )}
          <Descriptions
            title={intl.formatMessage({ id: 'pages.mcp.oauth.discoveryResult', defaultMessage: 'Discovery result' })}
            bordered
            size="small"
            column={1}
            styles={{ label: { width: 200, background: 'var(--vip-bg-layout)' } }}
            items={[
              {
                key: 'discoveredIssuer',
                label: intl.formatMessage({ id: 'pages.mcp.oauth.issuer', defaultMessage: 'Authorization Server' }),
                children: (
                  <Space size={8} wrap>
                    <Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{discovery.issuer || '-'}</Text>
                    {(() => {
                      const source = discovery.issuerSource;
                      if (!source) {
                        return null;
                      }
                      const label = ISSUER_SOURCE_LABEL[source];
                      return (
                        <Tag color="geekblue" style={{ margin: 0 }}>
                          {label ? intl.formatMessage(label) : source}
                        </Tag>
                      );
                    })()}
                  </Space>
                ),
              },
              {
                key: 'authorizationEndpoint',
                label: intl.formatMessage({ id: 'pages.mcp.oauth.authorizationEndpoint', defaultMessage: 'Authorization endpoint' }),
                children: <EndpointValue value={discovery.authorizationEndpoint} />,
              },
              {
                key: 'tokenEndpoint',
                label: intl.formatMessage({ id: 'pages.mcp.oauth.tokenEndpoint', defaultMessage: 'Token endpoint' }),
                children: <EndpointValue value={discovery.tokenEndpoint} />,
              },
              {
                key: 'registrationEndpoint',
                label: intl.formatMessage({ id: 'pages.mcp.oauth.registrationEndpoint', defaultMessage: 'Registration endpoint' }),
                children: (
                  <EndpointValue
                    value={discovery.registrationEndpoint}
                    empty={intl.formatMessage({
                      id: 'pages.mcp.oauth.noRegistrationEndpoint',
                      defaultMessage: 'None: clients are pre-registered',
                    })}
                  />
                ),
              },
              {
                key: 'revocationEndpoint',
                label: intl.formatMessage({ id: 'pages.mcp.oauth.revocationEndpoint', defaultMessage: 'Revocation endpoint' }),
                children: (
                  <EndpointValue
                    value={discovery.revocationEndpoint}
                    empty={intl.formatMessage({
                      id: 'pages.mcp.oauth.noRevocationEndpoint',
                      defaultMessage: 'None: revocation stays local',
                    })}
                  />
                ),
              },
              {
                key: 'scopesSupported',
                label: intl.formatMessage({ id: 'pages.mcp.oauth.scopesSupported', defaultMessage: 'Scopes offered' }),
                children: scopeTags(discovery.scopesSupported),
              },
              {
                key: 'clientId',
                label: 'client_id',
                children: discovery.clientId ? (
                  <Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{discovery.clientId}</Text>
                ) : (
                  <Text type="secondary">
                    {intl.formatMessage({ id: 'pages.mcp.oauth.clientNotRegistered', defaultMessage: 'No client registered yet' })}
                  </Text>
                ),
              },
              {
                key: 'clientSecretPresent',
                label: 'client_secret',
                children: (
                  <Tag color={discovery.clientSecretPresent ? 'green' : 'default'} style={{ margin: 0 }}>
                    {discovery.clientSecretPresent
                      ? intl.formatMessage({ id: 'pages.mcp.oauth.secretStored', defaultMessage: 'Stored (encrypted)' })
                      : intl.formatMessage({ id: 'pages.mcp.oauth.secretAbsent', defaultMessage: 'Not stored: public client, PKCE only' })}
                  </Tag>
                ),
              },
              {
                key: 'callbackUrl',
                label: intl.formatMessage({ id: 'pages.mcp.oauth.callbackUrl', defaultMessage: 'Callback URL' }),
                children: (
                  <Space direction="vertical" size={4} style={{ width: '100%' }}>
                    <EndpointValue value={discovery.callbackUrl} />
                    {discovery.defaultCallbackUrl && discovery.callbackUrl !== discovery.defaultCallbackUrl && (
                      <Text type="warning" style={{ fontSize: 12 }}>
                        {intl.formatMessage(
                          {
                            id: 'pages.mcp.oauth.callbackUrlStale',
                            defaultMessage: 'This build sends the browser to {url}. The authorization server compares redirect_uri character by character, so re-save the client with that value.',
                          },
                          { url: discovery.defaultCallbackUrl },
                        )}
                      </Text>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        </div>
      )}

      <Modal
        open={clientModalOpen}
        title={intl.formatMessage({ id: 'pages.mcp.oauth.clientModalTitle', defaultMessage: 'Register OAuth client' })}
        onCancel={() => setClientModalOpen(false)}
        onOk={handleSaveClient}
        okText={intl.formatMessage({ id: 'pages.mcp.oauth.submit', defaultMessage: 'Save' })}
        confirmLoading={saving}
        destroyOnClose
      >
        <Paragraph type="secondary" style={{ fontSize: 12 }}>
          {intl.formatMessage(
            { id: 'pages.mcp.oauth.clientModalHint', defaultMessage: 'Stored for issuer {issuer}. Dynamic registration (DCR) is not implemented yet, so paste the credentials your authorization server already issued.' },
            { issuer: knownIssuer || '-' },
          )}
        </Paragraph>
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            name="clientId"
            label="client_id"
            rules={[{ required: true, message: intl.formatMessage({ id: 'pages.mcp.oauth.clientIdRequired', defaultMessage: 'client_id is required' }) }]}
          >
            <Input placeholder="harnax-mcp" maxLength={255} />
          </Form.Item>
          <Form.Item
            name="clientSecret"
            label="client_secret"
            extra={intl.formatMessage({
              id: 'pages.mcp.oauth.clientSecretExtra',
              defaultMessage: 'Leave empty to keep the stored secret; type a new one to replace it.',
            })}
          >
            <Input.Password
              autoComplete="new-password"
              disabled={clearSecret}
              placeholder={
                clearSecret
                  ? ''
                  : discovery?.clientSecretPresent
                    ? '••••••••'
                    : intl.formatMessage({ id: 'pages.mcp.oauth.secretPlaceholder', defaultMessage: 'Not stored yet' })
              }
            />
          </Form.Item>
          <Form.Item style={{ marginBottom: 12 }}>
            <Checkbox
              checked={clearSecret}
              onChange={(e) => {
                setClearSecret(e.target.checked);
                // 勾上就丢掉已填的值：否则「粘了新密钥又勾删除」会按勾选提交空串，
                // 管理员以为换了密钥，实际变成了一个公共客户端。
                if (e.target.checked) form.setFieldsValue({ clientSecret: '' });
              }}
            >
              {intl.formatMessage({
                id: 'pages.mcp.oauth.clearSecret',
                defaultMessage: 'Delete the stored secret (public client, PKCE only)',
              })}
            </Checkbox>
          </Form.Item>
          <Form.Item
            name="callbackUrl"
            label={intl.formatMessage({ id: 'pages.mcp.oauth.callbackUrl', defaultMessage: 'Callback URL' })}
            extra={intl.formatMessage({
              id: 'pages.mcp.oauth.callbackUrlExtra',
              defaultMessage: 'Only set this if your authorization server lists a different redirect_uri; empty keeps the stored one. The two must match character by character, so after changing APP_FRONTEND_BASE_URL or APP_BASE_URL the new value has to be saved here.',
            })}
          >
            <Input placeholder={discovery?.callbackUrl} maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
};

export default OAuthPanel;
