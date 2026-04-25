import React, { useEffect, useState } from 'react';
import { Table, Switch, message, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { getSkillPage, toggleSkillStatus } from '@/services/ant-design-pro/skill';
// @ts-ignore
import { history } from '@umijs/max';
import dayjs from 'dayjs';

interface SkillListProps {
  repositoryId: number;
  filters: { name: string; status: number | undefined };
  onRefresh: () => void;
}

const SkillList: React.FC<SkillListProps> = ({ repositoryId, filters, onRefresh }) => {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<API.SkillItem[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);

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
      message.error('加载技能列表失败');
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
        message.success('状态切换成功');
        // 局部更新状态，不刷新列表
        setData((prev) =>
          prev.map((item) => (item.id === id ? { ...item, status } : item))
        );
      } else {
        message.error(response.message || '状态切换失败');
      }
    } catch (error: any) {
      message.error(error?.message || error?.info?.errorMessage || '状态切换失败');
    }
  };

  const columns: ColumnsType<API.SkillItem> = [
    {
      title: '技能名称',
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
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (text: string) => text || '暂无描述',
    },
    {
      title: '是否公开',
      dataIndex: 'isPublic',
      key: 'isPublic',
      width: 100,
      render: (isPublic: number) => isPublic === 1 ? <Tag color="blue">公开</Tag> : <Tag>私有</Tag>,
    },
    {
      title: '创建人',
      dataIndex: 'creator',
      key: 'creator',
      width: 120,
      render: (creator: string) => creator || '-',
    },
    {
      title: '同步时间',
      dataIndex: 'updateTime',
      key: 'updateTime',
      width: 180,
      render: (time: string) => (time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'),
    },
    {
      title: '状态',
      key: 'status',
      width: 120,
      render: (_, record) => (
        <Switch
          checked={record.status === 1}
          checkedChildren="启用"
          unCheckedChildren="禁用"
          onChange={(checked) => handleToggle(record.id, checked ? 1 : 0)}
        />
      ),
    },
  ];

  return (
    <Table
      rowKey="id"
      columns={columns}
      dataSource={data}
      loading={loading}
      pagination={{
        current: pageNum,
        pageSize,
        total,
        showSizeChanger: true,
        showTotal: (t) => `共 ${t} 条`,
        onChange: (page, size) => {
          setPageNum(page);
          setPageSize(size);
        },
      }}
    />
  );
};

export default SkillList;
