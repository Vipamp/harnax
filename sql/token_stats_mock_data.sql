-- Token 消耗统计模拟数据
-- 生成最近 30 天的模拟数据

-- 清空现有数据（可选）
-- TRUNCATE TABLE token_stats;

-- 插入模拟数据
INSERT INTO `token_stats` (`agent_id`, `session_id`, `chat_model_id`, `input_token`, `output_token`, `total_token`, `ts`) VALUES
-- 第 1 天 (30天前)
(1, 'session-001', 1, 1520, 890, 2410, DATE_SUB(NOW(), INTERVAL 30 DAY)),
(1, 'session-001', 1, 2340, 1250, 3590, DATE_SUB(NOW(), INTERVAL 30 DAY)),
(2, 'session-002', 2, 1890, 950, 2840, DATE_SUB(NOW(), INTERVAL 30 DAY)),
(3, 'session-003', 1, 3200, 1680, 4880, DATE_SUB(NOW(), INTERVAL 30 DAY)),

-- 第 2 天
(1, 'session-001', 1, 1680, 920, 2600, DATE_SUB(NOW(), INTERVAL 29 DAY)),
(1, 'session-004', 2, 2100, 1150, 3250, DATE_SUB(NOW(), INTERVAL 29 DAY)),
(2, 'session-002', 2, 1750, 880, 2630, DATE_SUB(NOW(), INTERVAL 29 DAY)),
(4, 'session-005', 3, 2890, 1420, 4310, DATE_SUB(NOW(), INTERVAL 29 DAY)),

-- 第 3 天
(1, 'session-001', 1, 1920, 1050, 2970, DATE_SUB(NOW(), INTERVAL 28 DAY)),
(2, 'session-002', 2, 2200, 1180, 3380, DATE_SUB(NOW(), INTERVAL 28 DAY)),
(3, 'session-003', 1, 3500, 1850, 5350, DATE_SUB(NOW(), INTERVAL 28 DAY)),
(5, 'session-006', 2, 1650, 820, 2470, DATE_SUB(NOW(), INTERVAL 28 DAY)),

-- 第 4 天
(1, 'session-001', 1, 2100, 1120, 3220, DATE_SUB(NOW(), INTERVAL 27 DAY)),
(2, 'session-007', 3, 1850, 960, 2810, DATE_SUB(NOW(), INTERVAL 27 DAY)),
(4, 'session-005', 3, 3100, 1580, 4680, DATE_SUB(NOW(), INTERVAL 27 DAY)),

-- 第 5 天
(1, 'session-001', 1, 1780, 950, 2730, DATE_SUB(NOW(), INTERVAL 26 DAY)),
(3, 'session-003', 1, 2950, 1520, 4470, DATE_SUB(NOW(), INTERVAL 26 DAY)),
(5, 'session-006', 2, 2250, 1180, 3430, DATE_SUB(NOW(), INTERVAL 26 DAY)),

-- 第 6 天
(2, 'session-002', 2, 1950, 1020, 2970, DATE_SUB(NOW(), INTERVAL 25 DAY)),
(1, 'session-004', 2, 2450, 1280, 3730, DATE_SUB(NOW(), INTERVAL 25 DAY)),
(3, 'session-003', 1, 3800, 1950, 5750, DATE_SUB(NOW(), INTERVAL 25 DAY)),

-- 第 7 天
(1, 'session-001', 1, 2200, 1150, 3350, DATE_SUB(NOW(), INTERVAL 24 DAY)),
(4, 'session-005', 3, 2680, 1380, 4060, DATE_SUB(NOW(), INTERVAL 24 DAY)),
(2, 'session-007', 3, 1920, 980, 2900, DATE_SUB(NOW(), INTERVAL 24 DAY)),
(5, 'session-006', 2, 2100, 1080, 3180, DATE_SUB(NOW(), INTERVAL 24 DAY)),

-- 第 8 天
(1, 'session-001', 1, 1850, 980, 2830, DATE_SUB(NOW(), INTERVAL 23 DAY)),
(3, 'session-003', 1, 3200, 1680, 4880, DATE_SUB(NOW(), INTERVAL 23 DAY)),
(2, 'session-002', 2, 2050, 1090, 3140, DATE_SUB(NOW(), INTERVAL 23 DAY)),

-- 第 9 天
(1, 'session-004', 2, 2350, 1220, 3570, DATE_SUB(NOW(), INTERVAL 22 DAY)),
(4, 'session-005', 3, 2890, 1480, 4370, DATE_SUB(NOW(), INTERVAL 22 DAY)),
(5, 'session-006', 2, 1980, 1020, 3000, DATE_SUB(NOW(), INTERVAL 22 DAY)),

-- 第 10 天
(2, 'session-002', 2, 2150, 1120, 3270, DATE_SUB(NOW(), INTERVAL 21 DAY)),
(1, 'session-001', 1, 1920, 1010, 2930, DATE_SUB(NOW(), INTERVAL 21 DAY)),
(3, 'session-003', 1, 3450, 1780, 5230, DATE_SUB(NOW(), INTERVAL 21 DAY)),

-- 第 11 天
(1, 'session-001', 1, 2080, 1090, 3170, DATE_SUB(NOW(), INTERVAL 20 DAY)),
(2, 'session-007', 3, 1780, 920, 2700, DATE_SUB(NOW(), INTERVAL 20 DAY)),
(4, 'session-005', 3, 3050, 1560, 4610, DATE_SUB(NOW(), INTERVAL 20 DAY)),

-- 第 12 天
(3, 'session-003', 1, 2850, 1480, 4330, DATE_SUB(NOW(), INTERVAL 19 DAY)),
(1, 'session-004', 2, 2250, 1170, 3420, DATE_SUB(NOW(), INTERVAL 19 DAY)),
(5, 'session-006', 2, 2050, 1060, 3110, DATE_SUB(NOW(), INTERVAL 19 DAY)),

-- 第 13 天
(2, 'session-002', 2, 1890, 980, 2870, DATE_SUB(NOW(), INTERVAL 18 DAY)),
(1, 'session-001', 1, 2180, 1140, 3320, DATE_SUB(NOW(), INTERVAL 18 DAY)),
(4, 'session-005', 3, 2750, 1410, 4160, DATE_SUB(NOW(), INTERVAL 18 DAY)),

-- 第 14 天
(3, 'session-003', 1, 3350, 1750, 5100, DATE_SUB(NOW(), INTERVAL 17 DAY)),
(1, 'session-001', 1, 1950, 1020, 2970, DATE_SUB(NOW(), INTERVAL 17 DAY)),
(2, 'session-007', 3, 2020, 1050, 3070, DATE_SUB(NOW(), INTERVAL 17 DAY)),

-- 第 15 天
(1, 'session-004', 2, 2420, 1260, 3680, DATE_SUB(NOW(), INTERVAL 16 DAY)),
(5, 'session-006', 2, 1880, 970, 2850, DATE_SUB(NOW(), INTERVAL 16 DAY)),
(4, 'session-005', 3, 3150, 1620, 4770, DATE_SUB(NOW(), INTERVAL 16 DAY)),

-- 第 16 天
(2, 'session-002', 2, 2100, 1090, 3190, DATE_SUB(NOW(), INTERVAL 15 DAY)),
(1, 'session-001', 1, 1820, 960, 2780, DATE_SUB(NOW(), INTERVAL 15 DAY)),
(3, 'session-003', 1, 3580, 1850, 5430, DATE_SUB(NOW(), INTERVAL 15 DAY)),

-- 第 17 天
(1, 'session-001', 1, 2250, 1180, 3430, DATE_SUB(NOW(), INTERVAL 14 DAY)),
(4, 'session-005', 3, 2920, 1500, 4420, DATE_SUB(NOW(), INTERVAL 14 DAY)),
(2, 'session-007', 3, 1950, 1010, 2960, DATE_SUB(NOW(), INTERVAL 14 DAY)),

-- 第 18 天
(3, 'session-003', 1, 3100, 1620, 4720, DATE_SUB(NOW(), INTERVAL 13 DAY)),
(1, 'session-004', 2, 2380, 1240, 3620, DATE_SUB(NOW(), INTERVAL 13 DAY)),
(5, 'session-006', 2, 2120, 1090, 3210, DATE_SUB(NOW(), INTERVAL 13 DAY)),

-- 第 19 天
(2, 'session-002', 2, 1980, 1030, 3010, DATE_SUB(NOW(), INTERVAL 12 DAY)),
(1, 'session-001', 1, 2150, 1120, 3270, DATE_SUB(NOW(), INTERVAL 12 DAY)),
(4, 'session-005', 3, 2850, 1460, 4310, DATE_SUB(NOW(), INTERVAL 12 DAY)),

-- 第 20 天
(3, 'session-003', 1, 3420, 1780, 5200, DATE_SUB(NOW(), INTERVAL 11 DAY)),
(1, 'session-001', 1, 1890, 990, 2880, DATE_SUB(NOW(), INTERVAL 11 DAY)),
(2, 'session-007', 3, 2080, 1070, 3150, DATE_SUB(NOW(), INTERVAL 11 DAY)),

-- 第 21 天
(1, 'session-004', 2, 2520, 1310, 3830, DATE_SUB(NOW(), INTERVAL 10 DAY)),
(5, 'session-006', 2, 1950, 1010, 2960, DATE_SUB(NOW(), INTERVAL 10 DAY)),
(4, 'session-005', 3, 3080, 1590, 4670, DATE_SUB(NOW(), INTERVAL 10 DAY)),

-- 第 22 天
(2, 'session-002', 2, 2180, 1130, 3310, DATE_SUB(NOW(), INTERVAL 9 DAY)),
(1, 'session-001', 1, 1920, 1000, 2920, DATE_SUB(NOW(), INTERVAL 9 DAY)),
(3, 'session-003', 1, 3650, 1890, 5540, DATE_SUB(NOW(), INTERVAL 9 DAY)),

-- 第 23 天
(1, 'session-001', 1, 2320, 1210, 3530, DATE_SUB(NOW(), INTERVAL 8 DAY)),
(4, 'session-005', 3, 2980, 1530, 4510, DATE_SUB(NOW(), INTERVAL 8 DAY)),
(2, 'session-007', 3, 2010, 1040, 3050, DATE_SUB(NOW(), INTERVAL 8 DAY)),

-- 第 24 天
(3, 'session-003', 1, 3250, 1690, 4940, DATE_SUB(NOW(), INTERVAL 7 DAY)),
(1, 'session-004', 2, 2450, 1270, 3720, DATE_SUB(NOW(), INTERVAL 7 DAY)),
(5, 'session-006', 2, 2080, 1070, 3150, DATE_SUB(NOW(), INTERVAL 7 DAY)),

-- 第 25 天
(2, 'session-002', 2, 2050, 1060, 3110, DATE_SUB(NOW(), INTERVAL 6 DAY)),
(1, 'session-001', 1, 2180, 1140, 3320, DATE_SUB(NOW(), INTERVAL 6 DAY)),
(4, 'session-005', 3, 2920, 1500, 4420, DATE_SUB(NOW(), INTERVAL 6 DAY)),

-- 第 26 天
(3, 'session-003', 1, 3480, 1810, 5290, DATE_SUB(NOW(), INTERVAL 5 DAY)),
(1, 'session-001', 1, 1950, 1020, 2970, DATE_SUB(NOW(), INTERVAL 5 DAY)),
(2, 'session-007', 3, 2120, 1090, 3210, DATE_SUB(NOW(), INTERVAL 5 DAY)),

-- 第 27 天
(1, 'session-004', 2, 2580, 1340, 3920, DATE_SUB(NOW(), INTERVAL 4 DAY)),
(5, 'session-006', 2, 2010, 1040, 3050, DATE_SUB(NOW(), INTERVAL 4 DAY)),
(4, 'session-005', 3, 3180, 1640, 4820, DATE_SUB(NOW(), INTERVAL 4 DAY)),

-- 第 28 天
(2, 'session-002', 2, 2220, 1150, 3370, DATE_SUB(NOW(), INTERVAL 3 DAY)),
(1, 'session-001', 1, 1980, 1030, 3010, DATE_SUB(NOW(), INTERVAL 3 DAY)),
(3, 'session-003', 1, 3720, 1930, 5650, DATE_SUB(NOW(), INTERVAL 3 DAY)),

-- 第 29 天 (昨天)
(1, 'session-001', 1, 2380, 1240, 3620, DATE_SUB(NOW(), INTERVAL 2 DAY)),
(4, 'session-005', 3, 3050, 1570, 4620, DATE_SUB(NOW(), INTERVAL 2 DAY)),
(2, 'session-007', 3, 2080, 1070, 3150, DATE_SUB(NOW(), INTERVAL 2 DAY)),
(5, 'session-006', 2, 2150, 1110, 3260, DATE_SUB(NOW(), INTERVAL 2 DAY)),

-- 第 30 天 (今天)
(3, 'session-003', 1, 3550, 1850, 5400, DATE_SUB(NOW(), INTERVAL 1 DAY)),
(1, 'session-004', 2, 2620, 1360, 3980, DATE_SUB(NOW(), INTERVAL 1 DAY)),
(2, 'session-002', 2, 2280, 1180, 3460, DATE_SUB(NOW(), INTERVAL 1 DAY)),
(4, 'session-005', 3, 3220, 1660, 4880, DATE_SUB(NOW(), INTERVAL 1 DAY)),
(1, 'session-001', 1, 2050, 1070, 3120, NOW()),
(3, 'session-003', 1, 3680, 1910, 5590, NOW());

-- 查询验证
SELECT 
    COUNT(*) as total_records,
    COUNT(DISTINCT agent_id) as unique_agents,
    COUNT(DISTINCT session_id) as unique_sessions,
    COUNT(DISTINCT chat_model_id) as unique_models,
    MIN(ts) as earliest_record,
    MAX(ts) as latest_record,
    SUM(input_token) as total_input,
    SUM(output_token) as total_output,
    SUM(total_token) as grand_total
FROM token_stats;
