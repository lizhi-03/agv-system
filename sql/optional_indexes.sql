-- 可选索引（表结构变更，默认不执行；请先在测试库评估后再上线）
-- 用法：mysql -uroot -p agv_system < optional_indexes.sql
--
-- 适用场景：任务 / 日志数据量增大后，/task/page、/log/page 的筛选与排序变慢。
-- 数据量小（万行以内）时收益不明显，且会增加写入开销，按需启用。

USE agv_system;

-- /task/page：按 status、agv_code 过滤（排序走主键 task_id）
ALTER TABLE agv_task ADD INDEX idx_task_status_agv (status, agv_code);
ALTER TABLE agv_task ADD INDEX idx_task_agv (agv_code);

-- /log/page：按 level 过滤 + timestamp 倒序
ALTER TABLE agv_log ADD INDEX idx_log_level_time (level, `timestamp`);
ALTER TABLE agv_log ADD INDEX idx_log_time (`timestamp`);

-- agv_path：按车辆删除 / 查询占用路径、按地图过滤
-- （sql/agv_system.sql 已有 idx_agv_code、idx_map_id 两个单列索引；复合索引建好后 idx_agv_code 可以删除）
ALTER TABLE agv_path ADD INDEX idx_path_agv_map (agv_code, map_id);
