import React, { ReactNode } from 'react';
import type { RangePickerProps } from 'antd/es/date-picker';
import dayjs from 'dayjs';

/**
 * 搜索筛选栏组件属性
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

/**
 * 搜索输入框组件属性
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

/**
 * 筛选下拉框组件属性
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

/**
 * 操作按钮组件属性
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

/**
 * 时间范围选择器组件属性
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

/**
 * 搜索筛选栏组件
 */
declare const SearchFilterBar: React.FC<SearchFilterBarProps>;

/**
 * 搜索输入框组件
 */
export declare const SearchInput: React.FC<SearchInputProps>;

/**
 * 筛选下拉框组件
 */
export declare const FilterSelect: React.FC<FilterSelectProps>;

/**
 * 操作按钮组件
 */
export declare const ActionButton: React.FC<ActionButtonProps>;

/**
 * 时间范围选择器组件
 */
export declare const FilterDatePicker: React.FC<FilterDatePickerProps>;

export default SearchFilterBar;
