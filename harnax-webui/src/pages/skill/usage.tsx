import React, { useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Col, Empty, Row, Select, Spin, Statistic, Switch, Table, Tag, Tooltip, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { BarChartOutlined, ThunderboltOutlined, QuestionCircleOutlined } from '@ant-design/icons';
// @ts-ignore
import { history } from '@umijs/max';
import { useIntl } from '@umijs/max';
import dayjs from 'dayjs';
import { getSkillUsageSummary } from '@/services/ant-design-pro/skillUsage';
import SkillOriginTag from './components/SkillOriginTag';

/**
 * Read-only usage analytics over `skill_usage` (design section 7).
 *
 * The row set is the tenant's whole skill list, unused skills included — the question this page exists to
 * answer is which skill never gets loaded, and a table built from the event stream would omit exactly those
 * rows and leave the operator unable to tell "unused" from "unknown".
 */
const SkillUsage: React.FC = () => {
  const intl = useIntl();
  const [days, setDays] = useState<number>(30);
  const [summary, setSummary] = useState<API.SkillUsageSummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [zeroOnly, setZeroOnly] = useState(false);

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      try {
        const response = await getSkillUsageSummary({ days });
        if (response.code === 200) {
          setSummary(response.data ?? null);
        } else {
          message.error(response.message || intl.formatMessage({ id: 'pages.skill.usage.loadFailed', defaultMessage: 'Failed to load usage data' }));
        }
      } catch (error: any) {
        message.error(error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.skill.usage.loadFailed', defaultMessage: 'Failed to load usage data' }));
      } finally {
        setLoading(false);
      }
    };
    load();
  }, [days]);

  const rows = useMemo(() => {
    const all = summary?.rows ?? [];
    return zeroOnly ? all.filter((row) => row.viewCount === 0 && row.useCount === 0) : all;
  }, [summary, zeroOnly]);

  const statCards: { title: string; value: number; color: string; hint?: string }[] = [
    {
      title: intl.formatMessage({ id: 'pages.skill.usage.totalSkills', defaultMessage: 'Skills' }),
      value: summary?.totalSkills ?? 0,
      color: '#4f6ef7',
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.usage.totalViews', defaultMessage: 'Loads' }),
      value: summary?.totalViews ?? 0,
      color: '#0ea5e9',
      hint: intl.formatMessage({ id: 'pages.skill.usage.viewsHint', defaultMessage: 'Times a skill was read into a context' }),
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.usage.totalUses', defaultMessage: 'Uses' }),
      value: summary?.totalUses ?? 0,
      color: '#10b981',
      hint: intl.formatMessage({ id: 'pages.skill.usage.usesHint', defaultMessage: "Times a skill's instructions were actually followed, reported by the runtime when a load succeeds" }),
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.usage.zeroUse', defaultMessage: 'Never used' }),
      value: summary?.zeroUseCount ?? 0,
      color: '#f59e0b',
      hint: intl.formatMessage({ id: 'pages.skill.usage.zeroHint', defaultMessage: 'No load and no use inside the window' }),
    },
  ];

  const columns: ColumnsType<API.SkillUsageRow> = [
    {
      title: intl.formatMessage({ id: 'pages.skill.list.name', defaultMessage: 'Skill Name' }),
      dataIndex: 'name',
      key: 'name',
      width: 220,
      render: (name: string, record) => (
        <a
          onClick={() => history.push(`/context/skill/detail/${record.skillId}`)}
          style={{ fontWeight: 500, cursor: 'pointer' }}
        >
          {name}
        </a>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.repository', defaultMessage: 'Skill Repository' }),
      dataIndex: 'repositoryName',
      key: 'repositoryName',
      width: 180,
      ellipsis: true,
      render: (repositoryName?: string) => repositoryName || '-',
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.list.origin', defaultMessage: 'Origin' }),
      dataIndex: 'origin',
      key: 'origin',
      width: 130,
      render: (origin?: string) => <SkillOriginTag origin={origin} />,
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.list.status', defaultMessage: 'Status' }),
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: number) =>
        status === 1 ? (
          <Tag color="green">{intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' })}</Tag>
        ) : (
          <Tag>{intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' })}</Tag>
        ),
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.usage.viewCount', defaultMessage: 'Loads' }),
      dataIndex: 'viewCount',
      key: 'viewCount',
      width: 100,
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.usage.useCount', defaultMessage: 'Uses' }),
      dataIndex: 'useCount',
      key: 'useCount',
      width: 100,
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.usage.lastUsedAt', defaultMessage: 'Last event' }),
      dataIndex: 'lastUsedAt',
      key: 'lastUsedAt',
      width: 180,
      render: (time?: string) => (time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'),
    },
  ];

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '18px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <BarChartOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.skill.usage.title', defaultMessage: 'Skill Usage' })}
          </span>
        ),
      }}
    >
      <Spin spinning={loading}>
        <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
          {statCards.map((card) => (
            <Col xs={12} sm={12} md={6} key={card.title}>
              <Card
                style={{
                  borderRadius: 12,
                  border: '1px solid var(--vip-border)',
                  background: 'var(--vip-bg-container)',
                }}
              >
                <Statistic
                  title={
                    card.hint ? (
                      <Tooltip title={card.hint}>
                        <span>
                          {card.title} <QuestionCircleOutlined style={{ color: 'var(--vip-text-tertiary)' }} />
                        </span>
                      </Tooltip>
                    ) : (
                      card.title
                    )
                  }
                  value={card.value}
                  valueStyle={{ color: card.color, fontWeight: 600 }}
                />
              </Card>
            </Col>
          ))}
        </Row>

        <Card
          title={
            <span style={{ fontSize: '14px', fontWeight: 600 }}>
              <ThunderboltOutlined style={{ marginRight: 8, color: '#4f6ef7' }} />
              {intl.formatMessage({ id: 'pages.skill.usage.perSkill', defaultMessage: 'Per-skill usage' })}
            </span>
          }
          extra={
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              <Select
                value={days}
                onChange={setDays}
                style={{ width: 140 }}
                options={[
                  { label: intl.formatMessage({ id: 'pages.skill.usage.last7', defaultMessage: 'Last 7 days' }), value: 7 },
                  { label: intl.formatMessage({ id: 'pages.skill.usage.last30', defaultMessage: 'Last 30 days' }), value: 30 },
                  { label: intl.formatMessage({ id: 'pages.skill.usage.last90', defaultMessage: 'Last 90 days' }), value: 90 },
                ]}
              />
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                <Switch checked={zeroOnly} onChange={setZeroOnly} size="small" />
                {intl.formatMessage({ id: 'pages.skill.usage.zeroOnly', defaultMessage: 'Unused only' })}
              </span>
            </div>
          }
          styles={{ body: { padding: '12px' } }}
        >
          <Table<API.SkillUsageRow>
            className="styled-pro-table"
            rowKey="skillId"
            columns={columns}
            dataSource={rows}
            loading={loading}
            size="small"
            scroll={{ x: 'max-content' }}
            locale={{
              emptyText: (
                <Empty
                  description={intl.formatMessage({ id: 'pages.skill.usage.empty', defaultMessage: 'No skill to report' })}
                />
              ),
            }}
            pagination={{
              pageSize: 20,
              showSizeChanger: true,
              showTotal: (t) => `${intl.formatMessage({ id: 'pages.common.total', defaultMessage: 'Total' })} ${t} ${intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}`,
            }}
          />
          <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
            {intl.formatMessage({ id: 'pages.skill.usage.windowHint', defaultMessage: 'Counts cover the last {days} days. An unused list is not by itself a reason to delete a skill.' }, { days })}
          </Typography.Paragraph>
        </Card>
      </Spin>
    </PageContainer>
  );
};

export default SkillUsage;
