SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 咖啡馆 · 咖啡豆批次 / 饮品配方 / 菜单 / 桌台 / 点单出单 / 豆量消耗流水
-- 库名 cafe_house；ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注：mysql 容器是 UTC，时间戳统一用 UTC_TIMESTAMP() + INTERVAL 8 HOUR（北京时间）。
--
-- 闭环口径：
--   1. 配方（menu_recipe）维护「在售饮品 -> 豆种(=批次产地) + 每杯克数」；
--   2. 完成制作时按【当时保存的配方】生成 bean_consumption 流水并扣减批次余量，
--      批次只允许从「已入库 且 remaining_g>0」的批次中按入库时间 FIFO 自动拆分，
--      可跨批次，但必须整张账单全量满足，否则全部回滚、一克不扣；
--   3. order_item 保存点单时的菜名/单价/类别快照，bean_consumption 保存
--      批次号/豆种/菜名快照，事后改菜单价、改配方都不会影响历史账单。

CREATE DATABASE IF NOT EXISTS cafe_house
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE cafe_house;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS bean_consumption;
DROP TABLE IF EXISTS menu_recipe;
DROP TABLE IF EXISTS order_item;
DROP TABLE IF EXISTS order_ticket;
DROP TABLE IF EXISTS table_info;
DROP TABLE IF EXISTS menu_item;
DROP TABLE IF EXISTS bean_batch;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 咖啡豆批次 ----------------
CREATE TABLE bean_batch (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no     VARCHAR(32) NOT NULL UNIQUE COMMENT '批次号 BB-20260901',
    origin       VARCHAR(64) NOT NULL COMMENT '豆种/产地（配方按此匹配）',
    roast_level  VARCHAR(8)  NOT NULL COMMENT '烘焙度 浅/中/深',
    weight_kg    DECIMAL(10,2) NOT NULL COMMENT '入库重量(kg)',
    remaining_g  INT         NOT NULL DEFAULT 0 COMMENT '批次余量(克)；入库时=重量，制作完成时按流水扣减',
    status       VARCHAR(16) NOT NULL COMMENT '待烘焙/烘焙中/已入库/已用完',
    stored_at    DATETIME    COMMENT '入库时间（FIFO 分配依据）；标记入库时写入',
    created_at   DATETIME    COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 饮品菜单 ----------------
CREATE TABLE menu_item (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    name     VARCHAR(64) NOT NULL UNIQUE COMMENT '饮品名',
    price    DECIMAL(10,2) NOT NULL COMMENT '价格',
    category VARCHAR(16)  NOT NULL COMMENT '类别 意式/手冲/非咖啡',
    status   VARCHAR(16)  NOT NULL COMMENT '在售/下架',
    created_at DATETIME   COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 饮品配方（管理员维护） ----------------
CREATE TABLE menu_recipe (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    menu_item_id BIGINT      NOT NULL COMMENT '饮品 id',
    bean_origin  VARCHAR(64) NOT NULL COMMENT '所需豆种（对应批次产地）',
    grams        INT         NOT NULL COMMENT '每杯用量(克)',
    created_at   DATETIME    COMMENT '创建时间',
    updated_at   DATETIME    COMMENT '更新时间',
    UNIQUE KEY uk_recipe_bean (menu_item_id, bean_origin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 桌台 ----------------
CREATE TABLE table_info (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    table_no VARCHAR(16) NOT NULL UNIQUE COMMENT '桌号 A1',
    seats    INT         NOT NULL COMMENT '座位数',
    status   VARCHAR(16) NOT NULL COMMENT '空闲/占用/清洁中',
    created_at DATETIME  COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 点单出单（账单） ----------------
CREATE TABLE order_ticket (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    table_id    BIGINT       COMMENT '桌台 id',
    total       DECIMAL(10,2) NOT NULL COMMENT '合计金额(点单时按快照汇总)',
    status      VARCHAR(16) NOT NULL COMMENT '制作中/已完成/已结账',
    finished_by VARCHAR(32) COMMENT '完成操作者（收银员）',
    finished_at DATETIME    COMMENT '完成制作时间（消耗扣减时点）',
    created_at  DATETIME    COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 账单明细（含点单快照，事后改价/改名不影响历史账单） ----------------
CREATE TABLE order_item (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_id     BIGINT NOT NULL COMMENT '所属账单 id',
    menu_item_id  BIGINT NOT NULL COMMENT '饮品 id',
    qty           INT    NOT NULL COMMENT '数量',
    menu_name     VARCHAR(64) NOT NULL COMMENT '点单时饮品名快照',
    unit_price    DECIMAL(10,2) NOT NULL COMMENT '点单时单价快照',
    menu_category VARCHAR(16) NOT NULL COMMENT '点单时类别快照（判断是否需配方）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 咖啡豆消耗流水（只在制作完成时写入，不可修改） ----------------
CREATE TABLE bean_consumption (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_id      BIGINT      NOT NULL COMMENT '账单 id',
    ticket_item_id BIGINT      NOT NULL COMMENT '账单明细 id',
    menu_item_id   BIGINT      NOT NULL COMMENT '饮品 id',
    menu_name      VARCHAR(64) NOT NULL COMMENT '饮品名快照',
    bean_origin    VARCHAR(64) NOT NULL COMMENT '豆种快照（取自已分配批次）',
    batch_id       BIGINT      NOT NULL COMMENT '实际扣减的批次 id',
    batch_no       VARCHAR(32) NOT NULL COMMENT '批次号快照',
    grams          INT         NOT NULL COMMENT '本次扣减克数',
    operator       VARCHAR(32) NOT NULL COMMENT '操作者',
    consumed_at    DATETIME    NOT NULL COMMENT '扣减时间',
    UNIQUE KEY uk_consume_item_batch (ticket_id, ticket_item_id, batch_id) COMMENT '防同一明细重复扣同批次',
    KEY idx_consume_batch (batch_id),
    KEY idx_consume_ticket (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT INTO bean_batch
    (batch_no, origin, roast_level, weight_kg, remaining_g, status, stored_at, created_at) VALUES
('BB-20260901', '埃塞俄比亚 耶加雪菲', '浅', 10.00,    0, '待烘焙', NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
('BB-20260905', '哥伦比亚 慧兰',     '中', 12.50,    0, '烘焙中', NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
('BB-20260908', '巴西 喜拉多',       '深',  0.30,  120, '已入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 32 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 32 DAY),
('BB-20260910', '巴西 喜拉多',       '深', 15.00, 15000, '已入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
('BB-20260912', '危地马拉 安提瓜',   '中',  8.00, 8000, '已入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
('BB-20260915', '印尼 曼特宁',       '深', 20.00,    0, '已用完', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
('BB-20260920', '肯尼亚 AA',         '浅',  6.00,    0, '待烘焙', NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY),
('BB-20260925', '巴西 喜拉多',       '深',  0.25,  250, '已入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

INSERT INTO menu_item (name, price, category, status, created_at) VALUES
('意式浓缩',     18.00, '意式',   '在售', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('拿铁',         28.00, '意式',   '在售', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('卡布奇诺',     26.00, '意式',   '在售', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('美式',         22.00, '意式',   '在售', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('手冲 耶加雪菲', 38.00, '手冲',   '在售', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('手冲 曼特宁',   42.00, '手冲',   '在售', UTC_TIMESTAMP() + INTERVAL 60 DAY),
('抹茶拿铁',     30.00, '非咖啡', '在售', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('气泡柚子',     25.00, '非咖啡', '下架', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY);

-- 配方：意式/拼配豆走「巴西 喜拉多」；手冲单品按各自豆种。非咖啡类无配方（不耗豆）。
INSERT INTO menu_recipe (menu_item_id, bean_origin, grams, created_at, updated_at)
SELECT id, '巴西 喜拉多', 18, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY
FROM menu_item WHERE name IN ('意式浓缩', '拿铁', '卡布奇诺');
INSERT INTO menu_recipe (menu_item_id, bean_origin, grams, created_at, updated_at)
SELECT id, '巴西 喜拉多', 20, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY
FROM menu_item WHERE name = '美式';
INSERT INTO menu_recipe (menu_item_id, bean_origin, grams, created_at, updated_at)
SELECT id, '埃塞俄比亚 耶加雪菲', 15, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY
FROM menu_item WHERE name = '手冲 耶加雪菲';
INSERT INTO menu_recipe (menu_item_id, bean_origin, grams, created_at, updated_at)
SELECT id, '印尼 曼特宁', 15, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY
FROM menu_item WHERE name = '手冲 曼特宁';

INSERT INTO table_info (table_no, seats, status, created_at) VALUES
('A1', 2, '空闲',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('A2', 4, '占用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('B1', 2, '空闲',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('B2', 6, '清洁中', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('C1', 4, '占用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('C2', 2, '空闲',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY);

-- 账单：A2(table=2) 制作中、C1(table=5) 已完成、B1(table=3) 已结账（桌台已释放为空闲）
INSERT INTO order_ticket (id, table_id, total, status, created_at) VALUES
(1, 2, 54.00, '制作中', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(2, 5, 38.00, '已完成', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(3, 3, 46.00, '已结账', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY);

-- 明细同时点入菜名/单价/类别快照（按饮品名关联，与自增 id 解耦）
INSERT INTO order_item (ticket_id, menu_item_id, qty, menu_name, unit_price, menu_category)
SELECT 1, id, 1, name, price, category FROM menu_item WHERE name = '拿铁';
INSERT INTO order_item (ticket_id, menu_item_id, qty, menu_name, unit_price, menu_category)
SELECT 1, id, 1, name, price, category FROM menu_item WHERE name = '卡布奇诺';
INSERT INTO order_item (ticket_id, menu_item_id, qty, menu_name, unit_price, menu_category)
SELECT 2, id, 1, name, price, category FROM menu_item WHERE name = '手冲 耶加雪菲';
INSERT INTO order_item (ticket_id, menu_item_id, qty, menu_name, unit_price, menu_category)
SELECT 3, id, 1, name, price, category FROM menu_item WHERE name = '意式浓缩';
INSERT INTO order_item (ticket_id, menu_item_id, qty, menu_name, unit_price, menu_category)
SELECT 3, id, 1, name, price, category FROM menu_item WHERE name = '拿铁';
