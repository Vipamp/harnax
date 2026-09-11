import React from 'react';
import { Form, Input, Select, Switch } from 'antd';
import type { IntlShape } from 'react-intl';

export interface OAuthFieldsProps {
  intl: IntlShape;
  /** 库里已有的 issuer，只用来决定提示文案：留空提交不会清掉它 */
  storedIssuer?: string;
}

/**
 * OAuth 配置的表单片段，创建与编辑共用。
 *
 * 这里只放非敏感字段：client_id / client_secret 属于 `mcp_oauth_client`，走详情页的登记入口，
 * 混进 MCP 表单就会让人以为它们和 scopes 一样存在 `oauth_config` 里。
 */
const OAuthFields: React.FC<OAuthFieldsProps> = ({ intl, storedIssuer }) => (
  <>
    <Form.Item
      name={['oauthConfig', 'authorizationServer']}
      label={intl.formatMessage({ id: 'pages.mcp.oauth.issuer', defaultMessage: 'Authorization Server' })}
      rules={[
        {
          pattern: /^https?:\/\/[\w\-]+(\.[\w\-]+)*(:\d+)?(\/[^\?#]*)?$/,
          message: intl.formatMessage({ id: 'pages.mcp.oauth.issuerInvalid', defaultMessage: 'Enter a full http(s) URL without userinfo, e.g. https://auth.example.com/realms/harnax' }),
        },
      ]}
      extra={intl.formatMessage(
        { id: 'pages.mcp.oauth.issuerExtra', defaultMessage: 'Leave it empty to let discovery locate the server from the MCP url. {stored}' },
        { stored: storedIssuer ? intl.formatMessage({ id: 'pages.mcp.oauth.issuerStored', defaultMessage: 'Leaving it empty keeps the stored one.' }) : '' },
      )}
    >
      <Input placeholder="https://auth.example.com/realms/harnax" maxLength={255} allowClear />
    </Form.Item>

    <Form.Item
      name={['oauthConfig', 'scopes']}
      label={intl.formatMessage({ id: 'pages.mcp.oauth.scopes', defaultMessage: 'Scopes' })}
      extra={intl.formatMessage({ id: 'pages.mcp.oauth.scopesExtra', defaultMessage: 'Press Enter after each scope. Keep this to the minimum the tool surface needs — the AS decides what is actually granted.' })}
    >
      <Select
        mode="tags"
        tokenSeparators={[',', ' ']}
        placeholder="mcp:tools"
        open={false}
        suffixIcon={null}
      />
    </Form.Item>

    <Form.Item
      name={['oauthConfig', 'audience']}
      label={intl.formatMessage({ id: 'pages.mcp.oauth.audience', defaultMessage: 'Audience' })}
      extra={intl.formatMessage({ id: 'pages.mcp.oauth.audienceExtra', defaultMessage: 'Only for authorization servers that require an explicit audience parameter. Leave empty otherwise.' })}
    >
      <Input placeholder="harnax" allowClear />
    </Form.Item>

    <Form.Item
      name={['oauthConfig', 'resourceIndicator']}
      label={intl.formatMessage({ id: 'pages.mcp.oauth.resourceIndicator', defaultMessage: 'Resource indicator' })}
      valuePropName="checked"
      initialValue={true}
      extra={intl.formatMessage({ id: 'pages.mcp.oauth.resourceIndicatorExtra', defaultMessage: 'Send the RFC 8707 resource parameter so the token is bound to this MCP server. Turn it off only for servers that reject unknown parameters.' })}
    >
      <Switch />
    </Form.Item>
  </>
);

export default OAuthFields;
