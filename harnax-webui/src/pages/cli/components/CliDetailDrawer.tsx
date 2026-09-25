import React, { useCallback, useEffect, useState } from 'react';
import { CodeOutlined } from '@ant-design/icons';
import { Descriptions, Drawer, Empty, Spin, Tag, Typography, message } from 'antd';
import { history, useIntl } from '@umijs/max';
import { getCliDetail } from '@/services/ant-design-pro/cli';

const { Text } = Typography;

interface CliDetailDrawerProps {
  cliId?: number;
  cliName?: string;
  onClose: () => void;
}

const digestStyle: React.CSSProperties = { fontFamily: 'monospace', wordBreak: 'break-all' };
const labelStyle = { width: 168, background: 'var(--vip-bg-layout)' };

/**
 * What a registered package declared in its `plugin.yaml`.
 *
 * The table has room for the name, version, skill, params, health check and package digest. The image
 * fingerprint, the apt dependencies and the runtime env slots existed only in the library: the page
 * shape answers without them, so this drawer is the only reader of `GET /api/admin/clis/{id}` (CLI-05).
 */
const CliDetailDrawer: React.FC<CliDetailDrawerProps> = ({ cliId, cliName, onClose }) => {
  const intl = useIntl();
  const [detail, setDetail] = useState<API.CliDetail>();
  const [loading, setLoading] = useState(false);

  const loadDetail = useCallback(async () => {
    if (!cliId) return;
    setLoading(true);
    try {
      const res = await getCliDetail(cliId);
      if (res.code === 200) {
        setDetail(res.data as API.CliDetail);
      } else {
        setDetail(undefined);
        message.error(
          res.message ||
            intl.formatMessage({ id: 'pages.message.loadFailed', defaultMessage: 'Failed to load data' }),
        );
      }
    } catch {
      setDetail(undefined);
      message.error(intl.formatMessage({ id: 'pages.message.loadFailed', defaultMessage: 'Failed to load data' }));
    } finally {
      setLoading(false);
    }
  }, [cliId, intl]);

  useEffect(() => {
    if (cliId) {
      loadDetail();
    } else {
      setDetail(undefined);
    }
  }, [cliId, loadDetail]);

  const runtimeEnv = Object.entries(detail?.runtimeEnv || {});
  const skillId = detail?.skill?.skillId;

  return (
    <Drawer
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <CodeOutlined style={{ color: 'var(--vip-primary)' }} />
          <span style={{ fontFamily: 'monospace' }}>{cliName}</span>
        </div>
      }
      open={!!cliId}
      onClose={onClose}
      width={560}
    >
      <Spin spinning={loading}>
        {!detail && !loading ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={intl.formatMessage({
              id: 'pages.cli.detail.empty',
              defaultMessage: 'No package detail available',
            })}
            style={{ marginTop: 60 }}
          />
        ) : (
          <Descriptions
            bordered
            size="small"
            column={1}
            styles={{ label: labelStyle }}
          >
            <Descriptions.Item
              label={intl.formatMessage({ id: 'pages.cli.version', defaultMessage: 'Version' })}
            >
              {detail?.version || '-'}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({ id: 'pages.common.description', defaultMessage: 'Description' })}
            >
              {detail?.description || '-'}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({ id: 'pages.common.status', defaultMessage: 'Status' })}
            >
              {detail ? (
                <Tag color={detail.status === 1 ? 'green' : 'default'}>
                  {detail.status === 1
                    ? intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' })
                    : intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}
                </Tag>
              ) : (
                '-'
              )}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({ id: 'pages.cli.healthCheck', defaultMessage: 'Health Check' })}
            >
              {detail?.checkCommand ? <Text code>{detail.checkCommand}</Text> : '-'}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.cli.envParams',
                defaultMessage: 'Env Params',
              })}
            >
              {detail?.envParams?.length
                ? detail.envParams.map((param) => (
                    <Tag key={param.envParamName} style={{ marginBottom: 4 }}>
                      {param.envParamName}
                    </Tag>
                  ))
                : '-'}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({ id: 'pages.cli.skill', defaultMessage: 'Shipped Skill' })}
            >
              {detail?.skill?.skillName ? (
                <Tag
                  color="blue"
                  style={{ cursor: skillId ? 'pointer' : 'default' }}
                  onClick={() => skillId && history.push(`/context/skill/detail/${skillId}`)}
                >
                  {detail.skill.skillName}
                </Tag>
              ) : (
                '-'
              )}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.cli.packageDigest',
                defaultMessage: 'Package Digest',
              })}
            >
              <span style={digestStyle}>{detail?.packageDigest || '-'}</span>
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.cli.detail.imageFingerprint',
                defaultMessage: 'Image Fingerprint',
              })}
            >
              <span style={digestStyle}>{detail?.payloadDigest || '-'}</span>
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.cli.detail.aptDeps',
                defaultMessage: 'apt Dependencies',
              })}
            >
              {detail?.depsApt?.length
                ? detail.depsApt.map((dep) => (
                    <Tag key={dep} style={{ marginBottom: 4 }}>
                      {dep}
                    </Tag>
                  ))
                : '-'}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.cli.detail.runtimeEnv',
                defaultMessage: 'Runtime Env Slots',
              })}
            >
              {runtimeEnv.length ? (
                <div>
                  {runtimeEnv.map(([key, value]) => (
                    <div key={key} style={{ fontFamily: 'monospace', fontSize: 12 }}>
                      <Text strong>{key}</Text>
                      {' = '}
                      {value}
                    </div>
                  ))}
                </div>
              ) : (
                '-'
              )}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.common.createTime',
                defaultMessage: 'Create Time',
              })}
            >
              {detail?.createTime || '-'}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Spin>
    </Drawer>
  );
};

export default CliDetailDrawer;
