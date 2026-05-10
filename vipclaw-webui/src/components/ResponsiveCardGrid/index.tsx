import { Card, Col, Row } from 'antd';
import React, { useEffect, useRef, useState } from 'react';

/**
 * 响应式卡片网格组件
 * 
 * 特性：
 * - 卡片高度固定
 * - 根据容器宽度动态计算每行卡片数量
 * - 确保卡片宽高比 >= 指定比例
 * - 卡片均匀分布，不会重叠
 * 
 * 使用示例：
 * ```tsx
 * <ResponsiveCardGrid
 *   data={items}
 *   cardHeight={260}
 *   minAspectRatio={1.4}
 *   gutter={[20, 20]}
 *   renderCard={(item, index) => <MyCard item={item} index={index} />}
 * />
 * ```
 */
export interface ResponsiveCardGridProps<T = any> {
  /** 数据列表 */
  data: T[];
  /** 卡片固定高度（px） */
  cardHeight?: number;
  /** 最小宽高比（宽度/高度），默认 1.4 */
  minAspectRatio?: number;
  /** 网格间距，默认 [20, 20] */
  gutter?: [number, number];
  /** 渲染卡片内容的函数 */
  renderCard: (item: T, index: number) => React.ReactNode;
  /** 容器自定义样式 */
  containerStyle?: React.CSSProperties;
  /** 加载状态 */
  loading?: boolean;
  /** 空状态显示 */
  emptyText?: React.ReactNode;
  /** 每行卡片数量变化回调（用于分页组件计算） */
  onCardsPerRowChange?: (cardsPerRow: number) => void;
}

const ResponsiveCardGrid: React.FC<ResponsiveCardGridProps> = ({
  data,
  cardHeight = 260,
  minAspectRatio = 1.4,
  gutter = [20, 20],
  renderCard,
  containerStyle,
  loading = false,
  emptyText = '暂无数据',
  onCardsPerRowChange,
}) => {
  const [cardsPerRow, setCardsPerRow] = useState<number>(4);
  const containerRef = useRef<HTMLDivElement>(null);

  // 动态计算每行卡片数量
  useEffect(() => {
    const calculateCardsPerRow = () => {
      if (containerRef.current) {
        // 获取容器的总宽度
        const containerWidth = containerRef.current.offsetWidth;
        
        // 根据最小宽高比和卡片高度计算最小宽度
        const minCardWidth = cardHeight * minAspectRatio;
        
        // 计算可以显示的卡片数量
        // 公式：(containerWidth + gutter) / (minCardWidth + gutter)
        const [horizontalGutter] = gutter;
        const cardsCount = Math.floor(
          (containerWidth + horizontalGutter) / (minCardWidth + horizontalGutter)
        );
        
        // 至少显示 1 张卡片
        const newCardsPerRow = Math.max(1, cardsCount);
        setCardsPerRow(newCardsPerRow);
        
        // 通知父组件每行卡片数量变化
        if (onCardsPerRowChange) {
          onCardsPerRowChange(newCardsPerRow);
        }
      }
    };

    // 初始计算
    calculateCardsPerRow();
    
    // 监听窗口大小变化
    window.addEventListener('resize', calculateCardsPerRow);
    
    // 使用 ResizeObserver 监听容器大小变化
    const resizeObserver = new ResizeObserver(calculateCardsPerRow);
    if (containerRef.current) {
      resizeObserver.observe(containerRef.current);
    }
    
    return () => {
      window.removeEventListener('resize', calculateCardsPerRow);
      resizeObserver.disconnect();
    };
  }, [cardHeight, minAspectRatio, gutter, onCardsPerRowChange]);

  if (loading) {
    return <div style={{ textAlign: 'center', padding: '40px' }}>加载中...</div>;
  }

  if (!data || data.length === 0) {
    return (
      <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
        {emptyText}
      </div>
    );
  }

  return (
    <div ref={containerRef} style={containerStyle}>
      <Row gutter={gutter}>
        {data.map((item, index) => (
          <Col
            key={index}
            style={{
              width: `${100 / cardsPerRow}%`,
              maxWidth: 'none',
              flex: `0 0 ${100 / cardsPerRow}%`,
              marginBottom: gutter[1],
            }}
          >
            <div style={{ height: cardHeight }}>{renderCard(item, index)}</div>
          </Col>
        ))}
      </Row>
    </div>
  );
};

export default ResponsiveCardGrid;
