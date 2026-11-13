-- AGV 智能调度与地图管理系统 - 数据库初始化脚本
-- 用法：mysql -uroot -p --default-character-set=utf8mb4 < agv_system.sql
-- 说明：包含建库、建表、以及一份默认 40x30 仓储地图与 3 台示例车辆/日志，
--       便于系统首次启动即可正常查询地图、创建任务、查询日志。

CREATE DATABASE IF NOT EXISTS agv_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE agv_system;

-- 地图（含版本链）
CREATE TABLE IF NOT EXISTS agv_map (
    map_id      INT PRIMARY KEY AUTO_INCREMENT,
    map_code    VARCHAR(64)  NOT NULL COMMENT '地图唯一编码',
    map_name    VARCHAR(64)           COMMENT '地图名称',
    width       INT                   COMMENT '地图宽度（格）',
    height      INT                   COMMENT '地图高度（格）',
    cell_size   INT                   COMMENT '单元格大小',
    description VARCHAR(255)          COMMENT '描述',
    obstacles   JSON                  COMMENT '障碍物坐标列表',
    stations    JSON                  COMMENT '站点列表（id/type/x/y）',
    version     INT DEFAULT 0         COMMENT '版本号',
    is_current  INT DEFAULT 0         COMMENT '1=当前版本 0=历史版本',
    parent_id   INT DEFAULT 0         COMMENT '上一版本地图ID，0为首版',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AGV 地图';

-- AGV 实时状态快照
CREATE TABLE IF NOT EXISTS agv_snapshot (
    agv_code     VARCHAR(32) PRIMARY KEY COMMENT 'AGV 编号',
    x            INT DEFAULT 0,
    y            INT DEFAULT 0,
    direction    INT DEFAULT 0     COMMENT '朝向角度',
    status       VARCHAR(16) DEFAULT 'idle' COMMENT 'idle/running/stopping/charging/error',
    battery      DOUBLE DEFAULT 100 COMMENT '电量百分比',
    speed        INT DEFAULT 0     COMMENT '速度 cm/s',
    task_id      INT NULL          COMMENT '当前任务ID',
    description  VARCHAR(255)      COMMENT '当前任务描述',
    last_update  VARCHAR(64)       COMMENT '最后更新时间（ISO8601）',
    planned_path TEXT              COMMENT '规划路径（JSON 节点数组）',
    path_index   INT DEFAULT 0     COMMENT '当前路径索引',
    is_charge    INT DEFAULT 0     COMMENT '0=未充电 1=充电中'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AGV 状态快照';

-- 任务
CREATE TABLE IF NOT EXISTS agv_task (
    task_id            INT PRIMARY KEY AUTO_INCREMENT,
    task_code          VARCHAR(64) NOT NULL COMMENT '任务编号',
    agv_code           VARCHAR(32)          COMMENT '执行车辆',
    task_type          VARCHAR(32)          COMMENT '物料运输/充电',
    status             VARCHAR(16)          COMMENT 'waiting/processing/paused/completed/failed/cancelled',
    priority           VARCHAR(16)          COMMENT 'low/medium/high',
    source             VARCHAR(64)          COMMENT '起点站点编号',
    destination        VARCHAR(64)          COMMENT '终点站点编号',
    start_time         DATETIME             COMMENT '开始时间',
    estimated_end_time DATETIME             COMMENT '预计结束时间',
    actual_end_time    DATETIME             COMMENT '实际结束时间',
    description        VARCHAR(255)         COMMENT '任务描述',
    created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    start_point_x      INT                  COMMENT '起点 X',
    start_point_y      INT                  COMMENT '起点 Y',
    pass_mid_point     INT DEFAULT 0        COMMENT '0=无需经过起点 1=需先经过起点'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AGV 任务';

-- 车辆占用路径（用于实时避让）
CREATE TABLE IF NOT EXISTS agv_path (
    path_id  BIGINT PRIMARY KEY AUTO_INCREMENT,
    agv_code VARCHAR(32),
    x        INT,
    y        INT,
    type     VARCHAR(16) DEFAULT 'path',
    map_id   INT,
    KEY idx_agv_code (agv_code),
    KEY idx_map_id (map_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AGV 占用路径点';

-- 系统日志
CREATE TABLE IF NOT EXISTS agv_log (
    log_id     INT PRIMARY KEY AUTO_INCREMENT,
    `timestamp` DATETIME DEFAULT CURRENT_TIMESTAMP,
    level      VARCHAR(16)  COMMENT 'info/warning/error',
    message    VARCHAR(500) COMMENT '日志内容',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统日志';

-- ---------------------------------------------------------------
-- 默认地图：40x30 仓储地图（8 组货架 + 6 个站点 + 2 个充电站）
-- ---------------------------------------------------------------
INSERT INTO agv_map (map_code, map_name, width, height, cell_size, description, obstacles, stations, version, is_current, parent_id)
VALUES (
  'map_seed_v1',
  '默认仓储地图',
  40,
  30,
  20,
  '系统初始化地图：40×30 栅格，8 组货架、6 个作业站点（S1-S6）、2 个充电站（C1/C2）',
  '[{"x":7,"y":6},{"x":8,"y":6},{"x":9,"y":6},{"x":10,"y":6},{"x":11,"y":6},{"x":7,"y":7},{"x":8,"y":7},{"x":9,"y":7},{"x":10,"y":7},{"x":11,"y":7},{"x":7,"y":8},{"x":8,"y":8},{"x":9,"y":8},{"x":10,"y":8},{"x":11,"y":8},{"x":7,"y":9},{"x":8,"y":9},{"x":9,"y":9},{"x":10,"y":9},{"x":11,"y":9},{"x":7,"y":14},{"x":8,"y":14},{"x":9,"y":14},{"x":10,"y":14},{"x":11,"y":14},{"x":7,"y":15},{"x":8,"y":15},{"x":9,"y":15},{"x":10,"y":15},{"x":11,"y":15},{"x":7,"y":16},{"x":8,"y":16},{"x":9,"y":16},{"x":10,"y":16},{"x":11,"y":16},{"x":7,"y":17},{"x":8,"y":17},{"x":9,"y":17},{"x":10,"y":17},{"x":11,"y":17},{"x":16,"y":6},{"x":17,"y":6},{"x":18,"y":6},{"x":19,"y":6},{"x":20,"y":6},{"x":16,"y":7},{"x":17,"y":7},{"x":18,"y":7},{"x":19,"y":7},{"x":20,"y":7},{"x":16,"y":8},{"x":17,"y":8},{"x":18,"y":8},{"x":19,"y":8},{"x":20,"y":8},{"x":16,"y":9},{"x":17,"y":9},{"x":18,"y":9},{"x":19,"y":9},{"x":20,"y":9},{"x":16,"y":14},{"x":17,"y":14},{"x":18,"y":14},{"x":19,"y":14},{"x":20,"y":14},{"x":16,"y":15},{"x":17,"y":15},{"x":18,"y":15},{"x":19,"y":15},{"x":20,"y":15},{"x":16,"y":16},{"x":17,"y":16},{"x":18,"y":16},{"x":19,"y":16},{"x":20,"y":16},{"x":16,"y":17},{"x":17,"y":17},{"x":18,"y":17},{"x":19,"y":17},{"x":20,"y":17},{"x":25,"y":6},{"x":26,"y":6},{"x":27,"y":6},{"x":28,"y":6},{"x":29,"y":6},{"x":25,"y":7},{"x":26,"y":7},{"x":27,"y":7},{"x":28,"y":7},{"x":29,"y":7},{"x":25,"y":8},{"x":26,"y":8},{"x":27,"y":8},{"x":28,"y":8},{"x":29,"y":8},{"x":25,"y":9},{"x":26,"y":9},{"x":27,"y":9},{"x":28,"y":9},{"x":29,"y":9},{"x":25,"y":14},{"x":26,"y":14},{"x":27,"y":14},{"x":28,"y":14},{"x":29,"y":14},{"x":25,"y":15},{"x":26,"y":15},{"x":27,"y":15},{"x":28,"y":15},{"x":29,"y":15},{"x":25,"y":16},{"x":26,"y":16},{"x":27,"y":16},{"x":28,"y":16},{"x":29,"y":16},{"x":25,"y":17},{"x":26,"y":17},{"x":27,"y":17},{"x":28,"y":17},{"x":29,"y":17},{"x":7,"y":22},{"x":8,"y":22},{"x":9,"y":22},{"x":10,"y":22},{"x":11,"y":22},{"x":7,"y":23},{"x":8,"y":23},{"x":9,"y":23},{"x":10,"y":23},{"x":11,"y":23},{"x":7,"y":24},{"x":8,"y":24},{"x":9,"y":24},{"x":10,"y":24},{"x":11,"y":24},{"x":25,"y":22},{"x":26,"y":22},{"x":27,"y":22},{"x":28,"y":22},{"x":29,"y":22},{"x":25,"y":23},{"x":26,"y":23},{"x":27,"y":23},{"x":28,"y":23},{"x":29,"y":23},{"x":25,"y":24},{"x":26,"y":24},{"x":27,"y":24},{"x":28,"y":24},{"x":29,"y":24}]',
  '[{"id":"S1","type":"station","x":2,"y":2},{"id":"S2","type":"station","x":20,"y":2},{"id":"S3","type":"station","x":37,"y":2},{"id":"S4","type":"station","x":2,"y":27},{"id":"S5","type":"station","x":20,"y":27},{"id":"S6","type":"station","x":37,"y":27},{"id":"C1","type":"charge","x":2,"y":15},{"id":"C2","type":"charge","x":37,"y":15}]',
  1,
  1,
  0
);

-- 示例车辆（可在前端"车队"页继续批量加车）
INSERT INTO agv_snapshot (agv_code, x, y, direction, status, battery, speed, task_id, description, last_update, planned_path, path_index, is_charge)
VALUES
  ('AGV001', 2, 1, 0, 'idle', 100, 0, NULL, '暂无', '2026-11-13T09:21:26.728Z', NULL, 0, 0),
  ('AGV002', 20, 1, 90, 'idle', 87.5, 0, NULL, '暂无', '2026-11-13T09:21:26.728Z', NULL, 0, 0),
  ('AGV003', 37, 1, 180, 'idle', 64, 0, NULL, '暂无', '2026-11-13T09:21:26.728Z', NULL, 0, 0);

-- 示例系统日志
INSERT INTO agv_log (`timestamp`, level, message) VALUES
  ('2026-11-13 17:21:26', 'info', '系统初始化完成：已加载默认仓储地图 v1（40×30），站点 6 个，充电站 2 个'),
  ('2026-11-13 17:21:26', 'info', '当前在线 AGV 3 辆：AGV001、AGV002、AGV003'),
  ('2026-11-13 17:21:26', 'warning', 'AGV003 电量 64%，低于 70% 建议关注');
