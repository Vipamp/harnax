import { PageContainer } from '@ant-design/pro-components';
import {
  Card,
  Row,
  Col,
  Statistic,
  DatePicker,
  Spin,
  Empty,
  theme,
  Tag,
  Typography,
  Space,
} from 'antd';
import React, { useEffect, useState } from 'react';
import dayjs from 'dayjs';
import {
  PieChartOutlined,
  BarChartOutlined,
  TeamOutlined,
  MessageOutlined,
  AppstoreOutlined,
  TrophyOutlined,
} from '@ant-design/icons';
import { getTokenStatsAggregation, getTokenStatsTimeSeries, getModelTimeSeries, getAgentTimeSeries, getSessionTimeSeries } from '@/services/ant-design-pro/tokenStats';
import { Pie, Line } from '@ant-design/plots';
import { Select } from 'antd';

const { RangePicker } = DatePicker;
const { Text, Title } = Typography;
const { Option } = Select;

interface TokenStatsData {
  overall: {
    totalInputToken: number;
    totalOutputToken: number;
    grandTotalToken: number;
    totalFee: number;
    agentCount: number;
    sessionCount: number;
    modelCount: number;
  };
  modelStats: Array<{
    modelId: number;
    modelName: string;
    providerName: string;
    totalInputToken: number;
    totalOutputToken: number;
    grandTotalToken: number;
    totalFee: number;
  }>;
  sessionStats: Array<{
    sessionId: string;
    sessionTitle: string;
    totalInputToken: number;
    totalOutputToken: number;
    grandTotalToken: number;
    totalFee: number;
  }>;
  agentStats: Array<{
    agentId: number;
    agentName: string;
    totalInputToken: number;
    totalOutputToken: number;
    grandTotalToken: number;
    totalFee: number;
  }>;
}

const TokenMonitor: React.FC = () => {
  const { token: themeToken } = theme.useToken();
  const [loading, setLoading] = useState(false);
  const [statsData, setStatsData] = useState<TokenStatsData | null>(null);
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs, dayjs.Dayjs]>([
    dayjs().subtract(7, 'day'),
    dayjs(),
  ]);
  const [timeGranularity, setTimeGranularity] = useState<string>('day');
  const [statDimension, setStatDimension] = useState<string>('token'); // 'token' 或 'fee'
  const [timeSeriesData, setTimeSeriesData] = useState<any[]>([]);
  const [modelTimeSeriesData, setModelTimeSeriesData] = useState<any[]>([]);
  const [agentTimeSeriesData, setAgentTimeSeriesData] = useState<any[]>([]);
  const [sessionTimeSeriesData, setSessionTimeSeriesData] = useState<any[]>([]);

  // 获取统计数据
  const fetchStats = async (startTime: string, endTime: string) => {
    setLoading(true);
    try {
      const response = await getTokenStatsAggregation({ startTime, endTime });
      if (response.code === 200) {
        setStatsData(response.data);
      }
    } catch (error) {
      console.error('获取 Token 统计数据失败:', error);
    } finally {
      setLoading(false);
    }
  };

  // 获取时序数据
  const fetchTimeSeries = async (startTime: string, endTime: string, granularity: string) => {
    try {
      const response = await getTokenStatsTimeSeries({ startTime, endTime, granularity });
      if (response.code === 200 && response.data.timeSeriesData) {
        setTimeSeriesData(response.data.timeSeriesData);
      }
    } catch (error) {
      console.error('获取时序数据失败:', error);
    }
  };

  // 获取模型时序数据
  const fetchModelTimeSeries = async (startTime: string, endTime: string, granularity: string) => {
    try {
      const response = await getModelTimeSeries({ startTime, endTime, granularity });
      if (response.code === 200 && response.data.timeSeriesData) {
        setModelTimeSeriesData(response.data.timeSeriesData);
      }
    } catch (error) {
      console.error('获取模型时序数据失败:', error);
    }
  };

  // 获取智能体时序数据
  const fetchAgentTimeSeries = async (startTime: string, endTime: string, granularity: string) => {
    try {
      const response = await getAgentTimeSeries({ startTime, endTime, granularity });
      if (response.code === 200 && response.data.timeSeriesData) {
        setAgentTimeSeriesData(response.data.timeSeriesData);
      }
    } catch (error) {
      console.error('获取智能体时序数据失败:', error);
    }
  };

  // 获取会话时序数据
  const fetchSessionTimeSeries = async (startTime: string, endTime: string, granularity: string) => {
    try {
      const response = await getSessionTimeSeries({ startTime, endTime, granularity });
      if (response.code === 200 && response.data.timeSeriesData) {
        setSessionTimeSeriesData(response.data.timeSeriesData);
      }
    } catch (error) {
      console.error('获取会话时序数据失败:', error);
    }
  };

  useEffect(() => {
    if (dateRange) {
      const startTime = dateRange[0].format('YYYY-MM-DD HH:mm:ss');
      const endTime = dateRange[1].format('YYYY-MM-DD HH:mm:ss');
      fetchStats(startTime, endTime);
      fetchTimeSeries(startTime, endTime, timeGranularity);
      fetchModelTimeSeries(startTime, endTime, timeGranularity);
      fetchAgentTimeSeries(startTime, endTime, timeGranularity);
      fetchSessionTimeSeries(startTime, endTime, timeGranularity);
    }
  }, [dateRange, timeGranularity]);

  // 格式化 Token 数字
  const formatToken = (value: number | undefined | null): string => {
    if (!value) {
      return '0';
    }
    if (value >= 1000000) {
      return `${(value / 1000000).toFixed(2)}M`;
    }
    if (value >= 1000) {
      return `${(value / 1000).toFixed(2)}K`;
    }
    return value.toString();
  };

  // 格式化费用（元）
  const formatFee = (value: number): string => {
    return `¥${value.toFixed(2)}`;
  };

  // 统计卡片配置 - 采用冷暖对比的配色体系
  const statCards = [
    {
      title: '总费用',
      value: statsData?.overall.totalFee || 0,
      icon: <TrophyOutlined />,
      color: '#f59e0b', // 琥珀色 - 费用
      suffix: '元',
      formatter: formatFee,
    },
    {
      title: '总输入 Token',
      value: statsData?.overall.totalInputToken || 0,
      icon: <PieChartOutlined />,
      color: '#0ea5e9', // 天空蓝 - 输入/冷色调
      suffix: '',
      percentage: statsData?.overall.grandTotalToken 
        ? ((statsData.overall.totalInputToken / statsData.overall.grandTotalToken) * 100).toFixed(1)
        : '0',
    },
    {
      title: '总输出 Token',
      value: statsData?.overall.totalOutputToken || 0,
      icon: <BarChartOutlined />,
      color: '#f97316', // 橙色 - 输出/暖色调
      suffix: '',
      percentage: statsData?.overall.grandTotalToken 
        ? ((statsData.overall.totalOutputToken / statsData.overall.grandTotalToken) * 100).toFixed(1)
        : '0',
    },
    {
      title: '总消耗 Token',
      value: statsData?.overall.grandTotalToken || 0,
      icon: <AppstoreOutlined />,
      color: '#10b981', // 翠绿 - 汇总/中性色
      suffix: '',
    },
    {
      title: '活跃智能体',
      value: statsData?.overall.agentCount || 0,
      icon: <TeamOutlined />,
      color: '#8b5cf6', // 紫罗兰
      suffix: '个',
    },
    {
      title: '活跃会话',
      value: statsData?.overall.sessionCount || 0,
      icon: <MessageOutlined />,
      color: '#ec4899', // 玫红
      suffix: '个',
    },
    {
      title: '使用模型数',
      value: statsData?.overall.modelCount || 0,
      icon: <AppstoreOutlined />,
      color: '#06b6d4', // 青色
      suffix: '个',
    },
  ];

  // 饼图颜色配置 - 采用 Tableau 风格的专业数据可视化配色
  const pieColors = [
    '#0ea5e9', '#f97316', '#10b981', '#8b5cf6', 
    '#ec4899', '#06b6d4', '#f59e0b', '#ef4444',
    '#6366f1', '#14b8a6', '#a855f7', '#e11d48'
  ];

  // 模型消耗饼图配置
  const modelPieConfig = {
    data: (statsData?.modelStats || []).map((item, index) => ({
      type: item.modelName,
      value: statDimension === 'token' ? item.grandTotalToken : item.totalFee,
      provider: item.providerName,
    })),
    angleField: 'value',
    colorField: 'type',
    radius: 0.8,
    label: {
      text: 'value',
      style: {
        fontWeight: 'bold' as const,
        fontSize: 12,
      },
      formatter: (datum: any) => {
        return statDimension === 'token' ? formatToken(datum.value) : formatFee(datum.value);
      },
    },
    legend: {
      color: {
        position: 'right' as const,
        layout: { justifyContent: 'flex-start' },
        itemMarker: (datum: any) => {
          return 'circle';
        },
        itemName: {
          style: {
            fontSize: 12,
            fill: '#666',
          },
          formatter: (datum: any) => {
            const total = statDimension === 'token' 
              ? (statsData?.overall.grandTotalToken || 1)
              : (statsData?.overall.totalFee || 1);
            const percentage = ((datum.value / total) * 100).toFixed(1);
            return `${datum.type}${datum.provider ? ` (${datum.provider})` : ''} - ${percentage}%`;
          },
        },
      },
    },
    tooltip: {
      title: 'type',
      items: [
        { 
          channel: 'y', 
          name: statDimension === 'token' ? 'Token 消耗' : '费用', 
          valueFormatter: (v: number) => statDimension === 'token' ? formatToken(v) : formatFee(v)
        },
      ],
    },
    style: {
      stroke: '#fff',
      lineWidth: 2,
      radius: 4,
    },
    // 立体效果：使用渐变和变换
    transform: [
      { type: 'jitterY', padding: 0.1 },
    ],
    color: pieColors,
    scale: {
      color: {
        range: pieColors,
      },
    },
    interactions: [
      {
        type: 'elementHighlight',
        link: true,
        background: true,
      },
    ],
    // 动画配置
    animate: {
      enter: {
        type: 'fadeIn',
        duration: 800,
      },
      update: {
        type: 'fadeIn',
        duration: 300,
      },
      exit: {
        type: 'fadeOut',
        duration: 300,
      },
    },
    // 悬停状态 - 使用缩放效果
    state: {
      active: {
        link: {
          linkFill: 'rgba(0, 0, 0, 0.05)',
          linkStrokeLineWidth: 10,
        },
      },
      inactive: {
        style: {
          fillOpacity: 0.45,
          strokeOpacity: 0.45,
        },
      },
    },
    innerRadius: 0.6,
    annotations: statsData?.modelStats.length > 0 ? [
      {
        type: 'text',
        style: {
          text: '模型分布',
          x: '50%',
          y: '50%',
          textAlign: 'center' as const,
          fontSize: 14,
          fontStyle: 'bold' as const,
          fill: '#666',
        },
      },
    ] : [],
  };

  // 智能体消耗饼图配置
  const agentPieConfig = {
    data: (statsData?.agentStats || []).map((item, index) => ({
      type: item.agentName,
      value: statDimension === 'token' ? item.grandTotalToken : item.totalFee,
    })),
    angleField: 'value',
    colorField: 'type',
    radius: 0.8,
    label: {
      text: 'value',
      style: {
        fontWeight: 'bold' as const,
        fontSize: 11,
      },
      formatter: (datum: any) => {
        return statDimension === 'token' ? formatToken(datum.value) : formatFee(datum.value);
      },
    },
    legend: {
      color: {
        position: 'right' as const,
        layout: { justifyContent: 'flex-start' },
        itemMarker: 'circle',
        itemName: {
          style: {
            fontSize: 12,
            fill: '#666',
          },
          formatter: (datum: any) => {
            const total = statDimension === 'token' 
              ? (statsData?.overall.grandTotalToken || 1)
              : (statsData?.overall.totalFee || 1);
            const percentage = ((datum.value / total) * 100).toFixed(1);
            return `${datum.type} - ${percentage}%`;
          },
        },
      },
    },
    tooltip: {
      title: 'type',
      items: [
        { 
          channel: 'y', 
          name: statDimension === 'token' ? 'Token 消耗' : '费用', 
          valueFormatter: (v: number) => statDimension === 'token' ? formatToken(v) : formatFee(v)
        },
      ],
    },
    style: {
      stroke: '#fff',
      lineWidth: 2,
      radius: 4,
    },
    // 立体效果：使用渐变和变换
    transform: [
      { type: 'jitterY', padding: 0.1 },
    ],
    color: pieColors,
    scale: {
      color: {
        range: pieColors,
      },
    },
    interactions: [
      {
        type: 'elementHighlight',
        link: true,
        background: true,
      },
    ],
    // 动画配置
    animate: {
      enter: {
        type: 'fadeIn',
        duration: 800,
      },
      update: {
        type: 'fadeIn',
        duration: 300,
      },
      exit: {
        type: 'fadeOut',
        duration: 300,
      },
    },
    // 悬停状态 - 使用缩放效果
    state: {
      active: {
        link: {
          linkFill: 'rgba(0, 0, 0, 0.05)',
          linkStrokeLineWidth: 10,
        },
      },
      inactive: {
        style: {
          fillOpacity: 0.45,
          strokeOpacity: 0.45,
        },
      },
    },
    innerRadius: 0.6,
    annotations: statsData?.agentStats.length > 0 ? [
      {
        type: 'text',
        style: {
          text: '智能体分布',
          x: '50%',
          y: '50%',
          textAlign: 'center' as const,
          fontSize: 14,
          fontStyle: 'bold' as const,
          fill: '#666',
        },
      },
    ] : [],
  };

  // 会话消耗饼图配置
  const sessionPieConfig = {
    data: (statsData?.sessionStats || []).slice(0, 10).map((item) => ({
      type: item.sessionTitle || item.sessionId,
      value: statDimension === 'token' ? item.grandTotalToken : item.totalFee,
    })),
    angleField: 'value',
    colorField: 'type',
    radius: 0.8,
    label: {
      text: 'value',
      style: {
        fontWeight: 'bold' as const,
        fontSize: 11,
      },
      formatter: (datum: any) => {
        return statDimension === 'token' ? formatToken(datum.value) : formatFee(datum.value);
      },
    },
    legend: {
      color: {
        position: 'right' as const,
        layout: { justifyContent: 'flex-start' },
        itemMarker: 'circle',
        itemName: {
          style: {
            fontSize: 11,
            fill: '#666',
          },
          formatter: (datum: any) => {
            const total = statDimension === 'token' 
              ? (statsData?.overall.grandTotalToken || 1)
              : (statsData?.overall.totalFee || 1);
            const percentage = ((datum.value / total) * 100).toFixed(1);
            return `${datum.type} - ${percentage}%`;
          },
        },
      },
    },
    tooltip: {
      title: 'type',
      items: [
        { 
          channel: 'y', 
          name: statDimension === 'token' ? 'Token 消耗' : '费用', 
          valueFormatter: (v: number) => statDimension === 'token' ? formatToken(v) : formatFee(v)
        },
      ],
    },
    style: {
      stroke: '#fff',
      lineWidth: 2,
      radius: 4,
    },
    // 立体效果：使用渐变和变换
    transform: [
      { type: 'jitterY', padding: 0.1 },
    ],
    color: pieColors,
    scale: {
      color: {
        range: pieColors,
      },
    },
    interactions: [
      {
        type: 'elementHighlight',
        link: true,
        background: true,
      },
    ],
    // 动画配置
    animate: {
      enter: {
        type: 'fadeIn',
        duration: 800,
      },
      update: {
        type: 'fadeIn',
        duration: 300,
      },
      exit: {
        type: 'fadeOut',
        duration: 300,
      },
    },
    // 悬停状态 - 使用缩放效果
    state: {
      active: {
        link: {
          linkFill: 'rgba(0, 0, 0, 0.05)',
          linkStrokeLineWidth: 10,
        },
      },
      inactive: {
        style: {
          fillOpacity: 0.45,
          strokeOpacity: 0.45,
        },
      },
    },
    innerRadius: 0.6,
    annotations: statsData?.sessionStats.length > 0 ? [
      {
        type: 'text',
        style: {
          text: '会话分布',
          x: '50%',
          y: '50%',
          textAlign: 'center' as const,
          fontSize: 14,
          fontStyle: 'bold' as const,
          fill: '#666',
        },
      },
    ] : [],
  };

  // 折线图配置
  const lineConfig = {
    data: statDimension === 'token' 
      ? timeSeriesData.flatMap((item) => [
          { time: item.timePoint, type: '输入 Token', value: item.totalInputToken },
          { time: item.timePoint, type: '输出 Token', value: item.totalOutputToken },
          { time: item.timePoint, type: '总消耗', value: item.grandTotalToken },
        ])
      : timeSeriesData.map((item) => ({
          time: item.timePoint,
          type: '总费用',
          value: item.totalFee,
        })),
    xField: 'time',
    yField: 'value',
    colorField: 'type',
    shapeField: 'smooth',
    axis: {
      x: {
        labelFormatter: (time: string) => {
          const date = dayjs(time);
          if (timeGranularity === 'hour') {
            return date.format('MM-DD HH:mm');
          } else if (timeGranularity === 'week') {
            return date.format('MM-DD');
          } else if (timeGranularity === 'month') {
            return date.format('YYYY-MM');
          }
          return date.format('MM-DD');
        },
        labelAutoRotate: false,
      },
      y: {
        labelFormatter: (v: number) => statDimension === 'token' ? formatToken(v) : formatFee(v),
      },
    },
    legend: {
      color: {
        position: 'top' as const,
        layout: { justifyContent: 'center' },
      },
    },
    tooltip: {
      title: 'time',
      items: [
        { 
          channel: 'y', 
          name: statDimension === 'token' ? 'Token' : '费用',
          valueFormatter: (v: number) => statDimension === 'token' ? formatToken(v) : formatFee(v)
        },
      ],
    },
    style: {
      lineWidth: 2,
    },
    color: statDimension === 'token' ? ['#0ea5e9', '#f97316', '#10b981'] : ['#10b981'],
    point: {
      shapeField: 'circle',
      sizeField: 3,
    },
    area: {
      style: {
        fillOpacity: 0.1,
      },
    },
    scale: {
      color: {
        range: statDimension === 'token' ? ['#0ea5e9', '#f97316', '#10b981'] : ['#10b981'],
      },
    },
    interactions: [
      {
        type: 'tooltip',
      },
    ],
    animation: {
      enter: {
        type: 'fadeIn',
      },
    },
  };

  // 维度折线图配置生成器
  const createDimensionLineConfig = (data: any[], title: string, colors: string[]) => ({
    data: data.flatMap((item) => ({
      time: item.timePoint,
      type: item.dimensionName || '未知',
      value: statDimension === 'token' ? item.grandTotalToken : item.totalFee,
    })),
    xField: 'time',
    yField: 'value',
    colorField: 'type',
    shapeField: 'smooth',
    axis: {
      x: {
        labelFormatter: (time: string) => {
          const date = dayjs(time);
          if (timeGranularity === 'hour') {
            return date.format('MM-DD HH:mm');
          } else if (timeGranularity === 'month') {
            return date.format('YYYY-MM');
          }
          return date.format('MM-DD');
        },
        labelAutoRotate: false,
      },
      y: {
        labelFormatter: (v: number) => statDimension === 'token' ? formatToken(v) : formatFee(v),
      },
    },
    legend: {
      color: {
        position: 'top' as const,
        layout: { justifyContent: 'center' },
      },
    },
    tooltip: {
      title: 'time',
      items: [
        { 
          channel: 'y', 
          name: statDimension === 'token' ? 'Token' : '费用',
          valueFormatter: (v: number) => statDimension === 'token' ? formatToken(v) : formatFee(v)
        },
      ],
    },
    style: {
      lineWidth: 2,
    },
    point: {
      shapeField: 'circle',
      sizeField: 3,
    },
    area: {
      style: {
        fillOpacity: 0.1,
      },
    },
    scale: {
      color: {
        range: colors,
      },
    },
    interactions: [
      {
        type: 'tooltip',
      },
    ],
    animation: {
      enter: {
        type: 'fadeIn',
      },
    },
  });

  // 模型折线图配置
  const modelLineConfig = createDimensionLineConfig(
    modelTimeSeriesData,
    '模型',
    pieColors
  );

  // 智能体折线图配置
  const agentLineConfig = createDimensionLineConfig(
    agentTimeSeriesData,
    '智能体',
    pieColors
  );

  // 会话折线图配置
  const sessionLineConfig = createDimensionLineConfig(
    sessionTimeSeriesData,
    '会话',
    pieColors
  );

  return (
    <PageContainer
      header={{
        title: 'Token 消耗监控',
        subTitle: '实时监控和分析 Token 消耗情况',
      }}
    >
      <div style={{ marginBottom: 24 }}>
        <Row gutter={[16, 16]} align="middle">
          <Col>
            <RangePicker
              showTime={{
                format: 'HH:mm:ss',
              }}
              format="YYYY-MM-DD HH:mm:ss"
              value={dateRange}
              onChange={(dates) => {
                if (dates && dates[0] && dates[1]) {
                  setDateRange([dates[0], dates[1]]);
                }
              }}
              presets={[
                { label: '最近 3 天', value: [dayjs().subtract(3, 'day'), dayjs()] },
                { label: '最近 7 天', value: [dayjs().subtract(7, 'day'), dayjs()] },
                { label: '最近 30 天', value: [dayjs().subtract(30, 'day'), dayjs()] },
                { label: '最近 90 天', value: [dayjs().subtract(90, 'day'), dayjs()] },
              ]}
            />
          </Col>
          <Col>
            <Select
              value={timeGranularity}
              onChange={setTimeGranularity}
              style={{ width: 120 }}
              placeholder="时间粒度"
            >
              <Option value="hour">按小时</Option>
              <Option value="day">按天</Option>
              <Option value="month">按月</Option>
            </Select>
          </Col>
          <Col>
            <Select
              value={statDimension}
              onChange={setStatDimension}
              style={{ width: 120 }}
              placeholder="统计维度"
            >
              <Option value="token">Token</Option>
              <Option value="fee">费用</Option>
            </Select>
          </Col>
        </Row>
      </div>

      <Spin spinning={loading}>
        {!statsData || statsData.overall.grandTotalToken === 0 ? (
          <Empty description="暂无 Token 消耗数据" />
        ) : (
          <>
            {/* 统计卡片 */}
            <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
              {statCards.map((card, index) => (
                <Col xs={24} sm={12} md={8} lg={4} key={index}>
                  <Card
                    hoverable
                    style={{
                      borderRadius: 12,
                      border: `1px solid ${card.color}20`,
                      background: `linear-gradient(135deg, ${card.color}08 0%, ${card.color}02 100%)`,
                      minHeight: 150,
                    }}
                  >
                    <div>
                      <div style={{ fontSize: 13, color: '#666', marginBottom: 10 }}>
                        {card.icon}
                        <span style={{ marginLeft: 8 }}>{card.title}</span>
                      </div>
                      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
                        <span style={{ color: card.color, fontSize: 36, fontWeight: 'bold', lineHeight: 1 }}>
                          {card.formatter ? card.formatter(card.value) : formatToken(card.value)}
                        </span>
                        {card.suffix && (
                          <span style={{ color: card.color, fontSize: 18, fontWeight: 500 }}>{card.suffix}</span>
                        )}
                        {card.percentage && (
                          <Tag 
                            color={card.color} 
                            style={{ 
                              fontSize: 12, 
                              padding: '2px 8px',
                              borderRadius: 10,
                              lineHeight: 'normal',
                              fontWeight: 500
                            }}
                          >
                            {card.percentage}%
                          </Tag>
                        )}
                      </div>
                    </div>
                  </Card>
                </Col>
              ))}
            </Row>

            {/* 三个维度的饼图 */}
            <Row gutter={[20, 20]} style={{ marginBottom: 24 }}>
              {/* 模型消耗分布 */}
              <Col xs={24} lg={8}>
                <Card
                  title={
                    <Space>
                      <AppstoreOutlined style={{ color: '#0ea5e9', fontSize: 16 }} />
                      <span>模型消耗分布</span>
                    </Space>
                  }
                  bordered={false}
                  style={{ 
                    borderRadius: 16,
                    boxShadow: '0 4px 20px rgba(14, 165, 233, 0.1)',
                    border: '1px solid #e0f2fe'
                  }}
                >
                  {statsData.modelStats.length > 0 ? (
                    <div style={{ height: 420 }}>
                      <Pie {...modelPieConfig} />
                    </div>
                  ) : (
                    <Empty description="暂无模型数据" />
                  )}
                </Card>
              </Col>

              {/* 智能体消耗分布 */}
              <Col xs={24} lg={8}>
                <Card
                  title={
                    <Space>
                      <TeamOutlined style={{ color: '#f97316', fontSize: 16 }} />
                      <span>智能体消耗分布</span>
                    </Space>
                  }
                  bordered={false}
                  style={{ 
                    borderRadius: 16,
                    boxShadow: '0 4px 20px rgba(249, 115, 22, 0.1)',
                    border: '1px solid #ffedd5'
                  }}
                >
                  {statsData.agentStats.length > 0 ? (
                    <div style={{ height: 420 }}>
                      <Pie {...agentPieConfig} />
                    </div>
                  ) : (
                    <Empty description="暂无智能体数据" />
                  )}
                </Card>
              </Col>

              {/* 会话消耗分布 */}
              <Col xs={24} lg={8}>
                <Card
                  title={
                    <Space>
                      <MessageOutlined style={{ color: '#10b981', fontSize: 16 }} />
                      <span>会话消耗分布 (Top 10)</span>
                    </Space>
                  }
                  bordered={false}
                  style={{ 
                    borderRadius: 16,
                    boxShadow: '0 4px 20px rgba(16, 185, 129, 0.1)',
                    border: '1px solid #d1fae5'
                  }}
                >
                  {statsData.sessionStats.length > 0 ? (
                    <div style={{ height: 420 }}>
                      <Pie {...sessionPieConfig} />
                    </div>
                  ) : (
                    <Empty description="暂无会话数据" />
                  )}
                </Card>
              </Col>
            </Row>

            {/* 三个维度的折线图 */}
            <Row gutter={[20, 20]} style={{ marginBottom: 24 }}>
              {/* 模型消耗趋势 */}
              <Col xs={24} lg={8}>
                <Card
                  title={
                    <Space>
                      <BarChartOutlined style={{ color: '#0ea5e9', fontSize: 16 }} />
                      <span>模型消耗趋势</span>
                    </Space>
                  }
                  bordered={false}
                  style={{ 
                    borderRadius: 16,
                    boxShadow: '0 4px 20px rgba(14, 165, 233, 0.1)',
                    border: '1px solid #e0f2fe'
                  }}
                >
                  {modelTimeSeriesData.length > 0 ? (
                    <div style={{ height: 350 }}>
                      <Line {...modelLineConfig} />
                    </div>
                  ) : (
                    <Empty description="暂无模型时序数据" />
                  )}
                </Card>
              </Col>

              {/* 智能体消耗趋势 */}
              <Col xs={24} lg={8}>
                <Card
                  title={
                    <Space>
                      <BarChartOutlined style={{ color: '#f97316', fontSize: 16 }} />
                      <span>智能体消耗趋势</span>
                    </Space>
                  }
                  bordered={false}
                  style={{ 
                    borderRadius: 16,
                    boxShadow: '0 4px 20px rgba(249, 115, 22, 0.1)',
                    border: '1px solid #ffedd5'
                  }}
                >
                  {agentTimeSeriesData.length > 0 ? (
                    <div style={{ height: 350 }}>
                      <Line {...agentLineConfig} />
                    </div>
                  ) : (
                    <Empty description="暂无智能体时序数据" />
                  )}
                </Card>
              </Col>

              {/* 会话消耗趋势 */}
              <Col xs={24} lg={8}>
                <Card
                  title={
                    <Space>
                      <BarChartOutlined style={{ color: '#10b981', fontSize: 16 }} />
                      <span>会话消耗趋势</span>
                    </Space>
                  }
                  bordered={false}
                  style={{ 
                    borderRadius: 16,
                    boxShadow: '0 4px 20px rgba(16, 185, 129, 0.1)',
                    border: '1px solid #d1fae5'
                  }}
                >
                  {sessionTimeSeriesData.length > 0 ? (
                    <div style={{ height: 350 }}>
                      <Line {...sessionLineConfig} />
                    </div>
                  ) : (
                    <Empty description="暂无会话时序数据" />
                  )}
                </Card>
              </Col>
            </Row>

            {/* 时序折线图 */}
            <Row gutter={[20, 20]} style={{ marginTop: 24 }}>
              <Col xs={24}>
                <Card
                  title={
                    <Space>
                      <BarChartOutlined style={{ color: '#10b981', fontSize: 16 }} />
                      <span>{statDimension === 'token' ? 'Token 消耗趋势' : '费用消耗趋势'}</span>
                    </Space>
                  }
                  bordered={false}
                  style={{ 
                    borderRadius: 16,
                    boxShadow: '0 4px 20px rgba(16, 185, 129, 0.1)',
                    border: '1px solid #d1fae5'
                  }}
                >
                  {timeSeriesData.length > 0 ? (
                    <div style={{ height: 400 }}>
                      <Line {...lineConfig} />
                    </div>
                  ) : (
                    <Empty description="暂无时序数据" />
                  )}
                </Card>
              </Col>
            </Row>
          </>
        )}
      </Spin>
    </PageContainer>
  );
};

export default TokenMonitor;
