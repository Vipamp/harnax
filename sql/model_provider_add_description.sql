-- 为 model_provider 表添加 description 字段
-- 执行时间: 2026-05-10

ALTER TABLE model_provider 
ADD COLUMN description VARCHAR(500) NULL COMMENT '服务商描述' 
AFTER name;

-- 验证字段是否添加成功
-- SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT 
-- FROM INFORMATION_SCHEMA.COLUMNS 
-- WHERE TABLE_NAME = 'model_provider' 
-- AND COLUMN_NAME = 'description';
