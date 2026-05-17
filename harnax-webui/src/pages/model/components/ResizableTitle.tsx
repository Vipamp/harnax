import React from 'react';
import { Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';

interface ResizableTitleProps {
  title: React.ReactNode;
  onResize: (index: number) => (e: React.MouseEvent<HTMLDivElement>) => void;
  width: number;
  index: number;
}

export const ResizableTitle: React.FC<ResizableTitleProps> = (props) => {
  const { title, onResize, width, index, ...rest } = props;

  if (!width) {
    return <th {...rest}>{title}</th>;
  }

  return (
    <th {...rest}>
      <div className="resize-table-title" style={{ display: 'flex', alignItems: 'center' }}>
        <span style={{ flex: 1 }}>{title}</span>
        <div
          onClick={onResize(index)}
          onMouseDown={onResize(index)}
          className="resize-table-handle"
          style={{
            position: 'absolute',
            right: 0,
            top: 0,
            bottom: 0,
            width: 10,
            cursor: 'col-resize',
            background: 'transparent',
          }}
        />
      </div>
    </th>
  );
};

export const useResizableColumns = <T,>(
  columns: ColumnsType<T>,
  setColumns: React.Dispatch<React.SetStateAction<ColumnsType<T>>>,
) => {
  const handleResize = (index: number) => {
    return (e: React.MouseEvent<HTMLDivElement>) => {
      e.stopPropagation();
    };
  };

  const resizableColumns = columns.map((col, index) => {
    if (col.key === 'action' || col.key === 'capabilities') {
      return col;
    }
    return {
      ...col,
      onHeaderCell: (record: T) => ({
        width: col.width as number,
        onResize: handleResize(index),
      }),
    };
  });

  return resizableColumns;
};
