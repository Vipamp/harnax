import React, { useEffect, useState } from 'react';
import { Table, message, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { getSkillPage, toggleSkillStatus } from '@/services/ant-design-pro/skill';
// @ts-ignore
import { history } from '@umijs/max';
import dayjs from 'dayjs';
import { useIntl } from '@umijs/max';
import StatusSwitch from '@/components/StatusSwitch';

interface SkillListProps {
  repositoryId: number;
  filters: { name: string; status: number | undefined };
  onRefresh: () => void;
}

const SkillList: React.FC<SkillListProps> = ({ repositoryId, filters, onRefresh }) => {
  const intl = useIntl();
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<API.SkillItem[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  const columns: ColumnsType<API.SkillItem> = [
    {
      title: intl.formatMessage({ id: 'pages.skill.list.name', defaultMessage: 'Skill Name' }),
      dataIndex: 'name',
      key: 'name',
      width: 200,
      render: (text: string, record: API.SkillItem) => (
        <a
          onClick={() => history.push(`/context/skill/detail/${record.id}`)}
          style={{ fontWeight: 500, cursor: 'pointer' }}
        >
          {text}
        </a>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.list.description', defaultMessage: 'Description' }),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (text: string) => text || intl.formatMessage({ id: 'pages.common.noDescription', defaultMessage: 'No description' }),
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.list.isPublic', defaultMessage: 'Is Public' }),
      dataIndex: 'isPublic',
      key: 'isPublic',
      width: 100,
      render: (isPublic: number) => isPublic === 1 ? <Tag color="blue">{intl.formatMessage({ id: 'pages.common.public', defaultMessage: 'Public' })}</Tag> : <Tag>{intl.formatMessage({ id: 'pages.common.private', defaultMessage: 'Private' })}</Tag>,
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.list.creator', defaultMessage: 'Creator' }),
      dataIndex: 'creator',
      key: 'creator',
      width: 120,
      render: (creator: string) => creator || '-',
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.list.updateTime', defaultMessage: 'Sync Time' }),
      dataIndex: 'updateTime',
      key: 'updateTime',
      width: 180,
      render: (time: string) => (time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'),
    },
    {
      title: intl.formatMessage({ id: 'pages.skill.list.status', defaultMessage: 'Status' }),
      key: 'status',
      width: 120,
      render: (_, record) => (
        <StatusSwitch
          status={record.status}
          onChange={(newStatus) => handleToggle(record.id, newStatus)}
        />
      ),
    },
  ];

  const loadData = async () => {
    setLoading(true);
    try {
      const res = await getSkillPage({
        pageNum: pageNum,
        pageSize: pageSize,
        repositoryId,
        name: filters.name || undefined,
        status: filters.status,
      });
      setData(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch (error) {
      message.error(intl.formatMessage({ id: 'pages.skill.list.loadFailed', defaultMessage: 'Failed to load skill list' }));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (repositoryId) {
      loadData();
    }
  }, [repositoryId, pageNum, pageSize, filters.name, filters.status]);

  const handleToggle = async (id: number, status: number) => {
    try {
      const response = await toggleSkillStatus(id, status);
      if (response.code === 200) {

        // 局部更新状态，不刷新列表
        setData((prev) =>
          prev.map((item) => (item.id === id ? { ...item, status } : item))
        );
      } else {
        message.error(response.message || intl.formatMessage({ id: 'pages.skill.list.toggleFailed', defaultMessage: 'Status toggle failed' }));
      }
    } catch (error: any) {
      message.error(error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.skill.list.toggleFailed', defaultMessage: 'Status toggle failed' }));
    }
  };

  return (
    <Table
      className="styled-pro-table"
      rowKey="id"
      columns={columns}
      dataSource={data}
      loading={loading}
      size="small"
      scroll={{ x: 'max-content' }}
      pagination={{
        current: pageNum,
        pageSize,
        total,
        showSizeChanger: true,
        showTotal: (t) => `${intl.formatMessage({ id: 'pages.common.total', defaultMessage: 'Total' })} ${t} ${intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}`,
        onChange: (page, size) => {
          setPageNum(page);
          setPageSize(size);
        },
      }}
    />
  );
};

export default SkillList;
