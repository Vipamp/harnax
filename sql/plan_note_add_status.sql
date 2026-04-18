-- 为 plan_note 表添加 status 字段
ALTER TABLE `plan_note` 
ADD COLUMN `status` VARCHAR(32) DEFAULT 'TODO' COMMENT '状态（TODO, IN_PROGRESS, DONE, ABANDONED）' 
AFTER `cost_timeseconds`;
