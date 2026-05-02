import React, { ReactNode } from 'react';
import { Card, Input, Select, Button, Space, DatePicker } from 'antd';
import { SearchOutlined, PlusOutlined } from '@ant-design/icons';
import type { RangePickerProps } from 'antd/es/date-picker';
import dayjs from 'dayjs';

/**
 * 搜索筛选栏组件
 * 
 * 统一的搜索筛选栏样式，用于所有管理页面
 * 
 * @example
 * <SearchFilterBar
 *   onSearch={handleSearch}
 *   onReset={handleReset}
 *   extra={<Button type="primary">新建</Button>}
 * >
 *   <Input placeholder="搜索..." />
 *   <Select options={[...]} />
 * </SearchFilterBar>
 */
export interface SearchFilterBarProps {
  /** 搜索回调 */
  onSearch?: () => void;
  /** 重置回调 */
  onReset?: () => void;
  /** 额外的操作按钮（右侧） */
  extra?: ReactNode;
  /** 子元素（筛选组件） */
  children?: ReactNode;
  /** 搜索按钮文本 */
  searchText?: string;
  /** 重置按钮文本 */
  resetText?: string;
  /** 是否显示搜索按钮 */
  showSearchButton?: boolean;
  /** 是否显示重置按钮 */
  showResetButton?: boolean;
  /** 自定义样式 */
  style?: React.CSSProperties;
}

const SearchFilterBar: React.FC<SearchFilterBarProps> = ({
  onSearch,
  onReset,
  extra,
  children,
  searchText = 'Search',
  resetText = 'Reset',
  showSearchButton = true,
  showResetButton = true,
  style,
}) => {
  return (
    <Card
      style={{
        marginBottom: 24,
        borderRadius: '12px',
        boxShadow: '0 2px 12px rgba(0,0,0,0.04)',
        border: '1px solid var(--vip-border)',
        ...style,
      }}
      styles={{ body: { padding: '12px 20px' } }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
        {/* 左侧：搜索筛选组 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flex: 1, minWidth: 300 }}>
          {children}
          
          {/* 搜索按钮 */}
          {showSearchButton && (
            <Button
              type="primary"
              onClick={onSearch}
              style={{
                borderRadius: '6px',
                height: '28px',
                padding: '0 12px',
                fontSize: '12px',
              }}
            >
              {searchText}
            </Button>
          )}
          
          {/* 重置按钮 */}
          {showResetButton && (
            <Button
              onClick={onReset}
              style={{
                borderRadius: '6px',
                height: '28px',
                padding: '0 12px',
                fontSize: '12px',
                background: 'var(--vip-bg-container)',
                borderColor: 'var(--vip-border)',
                color: 'var(--vip-text-primary)',
              }}
            >
              {resetText}
            </Button>
          )}
        </div>
        
        {/* 右侧：额外操作按钮 */}
        {extra && (
          <div>
            {extra}
          </div>
        )}
      </div>
    </Card>
  );
};

/**
 * 搜索输入框组件
 * 
 * @example
 * <SearchInput
 *   value={keyword}
 *   onChange={setKeyword}
 *   onSearch={handleSearch}
 *   placeholder="搜索..."
 * />
 */
export interface SearchInputProps {
  /** 输入值 */
  value?: string;
  /** 变化回调 */
  onChange?: (value: string) => void;
  /** 回车搜索回调 */
  onSearch?: () => void;
  /** 占位符 */
  placeholder?: string;
  /** 宽度 */
  width?: number | 'auto';
  /** 自定义样式 */
  style?: React.CSSProperties;
}

export const SearchInput: React.FC<SearchInputProps> = ({
  value,
  onChange,
  onSearch,
  placeholder = 'Please enter to search',
  width = 240,
  style,
}) => {
  return (
    <Input
      placeholder={placeholder}
      prefix={<SearchOutlined style={{ color: '#8c8c9a', fontSize: '12px' }} />}
      value={value}
      onChange={(e) => onChange?.(e.target.value)}
      onPressEnter={onSearch}
      allowClear
      style={{
        width: width === 'auto' ? 'auto' : width,
        minWidth: width === 'auto' ? 200 : undefined,
        maxWidth: width === 'auto' ? 400 : undefined,
        borderRadius: '6px',
        height: '28px',
        fontSize: '12px',
        ...style,
      }}
    />
  );
};

/**
 * 筛选下拉框组件
 * 
 * @example
 * <FilterSelect
 *   value={status}
 *   onChange={setStatus}
 *   placeholder="状态筛选"
 *   options={[...]}
 * />
 */
export interface FilterSelectProps {
  /** 选中值 */
  value?: any;
  /** 变化回调 */
  onChange?: (value: any) => void;
  /** 占位符 */
  placeholder?: string;
  /** 选项 */
  options: { label: string; value: any }[];
  /** 宽度 */
  width?: number | 'auto';
  /** 是否支持多选 */
  mode?: 'multiple' | 'tags';
  /** 自定义样式 */
  style?: React.CSSProperties;
}

export const FilterSelect: React.FC<FilterSelectProps> = ({
  value,
  onChange,
  placeholder = 'Please select',
  options,
  width = 120,
  mode,
  style,
}) => {
  return (
    <Select
      mode={mode}
      placeholder={placeholder}
      value={value}
      onChange={onChange}
      allowClear
      style={{
        width: width === 'auto' ? 'auto' : width,
        minWidth: width === 'auto' ? 100 : undefined,
        maxWidth: width === 'auto' ? 300 : undefined,
        height: '28px',
        fontSize: '12px',
        ...style,
      }}
      options={options}
    />
  );
};

/**
 * 操作按钮组件（用于右侧的新建等操作）
 * 
 * @example
 * <ActionButton
 *   type="primary"
 *   icon={<PlusOutlined />}
 *   onClick={handleCreate}
 * >
 *   新建
 * </ActionButton>
 */
export interface ActionButtonProps {
  /** 按钮类型 */
  type?: 'primary' | 'default' | 'dashed' | 'text' | 'link';
  /** 图标 */
  icon?: ReactNode;
  /** 点击回调 */
  onClick?: () => void;
  /** 按钮文本 */
  children?: ReactNode;
  /** 自定义样式 */
  style?: React.CSSProperties;
  /** 危险按钮 */
  danger?: boolean;
}

export const ActionButton: React.FC<ActionButtonProps> = ({
  type = 'primary',
  icon,
  onClick,
  children,
  style,
  danger,
}) => {
  return (
    <Button
      type={type}
      icon={icon}
      onClick={onClick}
      danger={danger}
      style={{
        borderRadius: '6px',
        height: '28px',
        padding: '0 16px',
        fontWeight: 500,
        fontSize: '12px',
        ...style,
      }}
    >
      {children}
    </Button>
  );
};

/**
 * 时间范围选择器组件
 * 
 * @example
 * <FilterDatePicker
 *   value={dateRange}
 *   onChange={setDateRange}
 *   placeholder={['开始时间', '结束时间']}
 *   showTime
 * />
 */
export interface FilterDatePickerProps {
  /** 选中值 */
  value?: [dayjs.Dayjs | null, dayjs.Dayjs | null] | null;
  /** 变化回调 */
  onChange?: (dates: [dayjs.Dayjs | null, dayjs.Dayjs | null] | null, dateStrings: [string, string]) => void;
  /** 占位符 */
  placeholder?: [string, string];
  /** 是否显示时间 */
  showTime?: boolean;
  /** 宽度 */
  width?: number;
  /** 自定义样式 */
  style?: React.CSSProperties;
  /** 禁用日期函数 */
  disabledDate?: RangePickerProps['disabledDate'];
  /** 禁用时间函数 */
  disabledTime?: RangePickerProps['disabledTime'];
  /** 日期格式 */
  format?: string;
}

export const FilterDatePicker: React.FC<FilterDatePickerProps> = ({
  value,
  onChange,
  placeholder = ['Start date', 'End date'],
  showTime = false,
  width = 240,
  style,
  disabledDate,
  disabledTime,
  format,
}) => {
  const { RangePicker } = DatePicker;
  
  return (
    <RangePicker
      showTime={showTime ? {
        format: 'HH:mm:ss',
      } : false}
      value={value}
      onChange={onChange}
      placeholder={placeholder}
      format={format || (showTime ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD')}
      style={{
        width,
        height: '28px',
        fontSize: '12px',
        borderRadius: '6px',
        ...style,
      }}
      disabledDate={disabledDate}
      disabledTime={disabledTime}
    />
  );
};

export default SearchFilterBar;
