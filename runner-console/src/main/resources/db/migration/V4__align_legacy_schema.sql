ALTER TABLE `agent_definition`
    ALTER COLUMN `avatar` SET DEFAULT 'agent-avatar-01.png';

ALTER TABLE `channel`
    MODIFY COLUMN `type` enum('EMAIL','WECOM','DINGTALK','FEISHU') DEFAULT NULL COMMENT '渠道类型',
    MODIFY COLUMN `health_status` enum('HEALTHY','UNHEALTHY','UNKNOWN') DEFAULT 'UNKNOWN' COMMENT '健康状态';

ALTER TABLE `quartz_job_info`
    MODIFY COLUMN `id` bigint NOT NULL,
    MODIFY COLUMN `type` enum('AGENT','WORKFLOW') DEFAULT NULL;

ALTER TABLE `secret_key`
    MODIFY COLUMN `value` text NULL COMMENT '密钥';

ALTER TABLE `workflow_run`
    MODIFY COLUMN `outputs` mediumtext NULL COMMENT '运行输出 JSON';

CREATE TABLE IF NOT EXISTS `dashboard` (
    `id` bigint NOT NULL COMMENT '主键',
    `tenant_id` bigint NOT NULL COMMENT '租户ID',
    `name` varchar(128) NOT NULL COMMENT '工作台模板名称',
    `remark` varchar(512) DEFAULT NULL COMMENT '备注',
    `status` enum('PUBLISHED','DRAFT') DEFAULT 'DRAFT' COMMENT '模板状态',
    `is_default` tinyint(1) DEFAULT 0 COMMENT '是否租户默认模板',
    `version` varchar(32) DEFAULT NULL COMMENT '模板版本',
    `config` json DEFAULT NULL COMMENT '工作台DSL配置',
    `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by` bigint DEFAULT NULL,
    `updated_by` bigint DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_dashboard_tenant_enabled` (`tenant_id`, `enabled`),
    KEY `idx_dashboard_tenant_default` (`tenant_id`, `is_default`)
) COMMENT='工作台模板';

CREATE TABLE IF NOT EXISTS `dashboard_user` (
    `id` bigint NOT NULL COMMENT '主键',
    `tenant_id` bigint NOT NULL COMMENT '租户ID',
    `dashboard_id` bigint NOT NULL COMMENT '关联模板ID',
    `config` json DEFAULT NULL COMMENT '个人DSL快照',
    `based_version` varchar(32) DEFAULT NULL COMMENT '克隆自的模板版本',
    `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by` bigint DEFAULT NULL,
    `updated_by` bigint DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dashboard_user` (`dashboard_id`, `created_by`),
    KEY `idx_dashboard_user_tenant` (`tenant_id`)
) COMMENT='工作台个人覆盖配置';

CREATE TABLE IF NOT EXISTS `dashboard_dataset` (
    `id` bigint NOT NULL COMMENT '主键',
    `tenant_id` bigint NOT NULL COMMENT '租户ID',
    `name` varchar(128) NOT NULL COMMENT '数据集名称',
    `remark` varchar(512) DEFAULT NULL COMMENT '备注',
    `shared` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否租户内共享（仅创建人可修改/删除）',
    `type` varchar(16) NOT NULL DEFAULT 'SQL' COMMENT '数据集类型：SQL / HTTP',
    `http_config` json DEFAULT NULL COMMENT 'HTTP 数据集配置（url、queries、headers、dataPath）',
    `sql_text` text COMMENT '查询语句（仅限SELECT）',
    `params` json DEFAULT NULL COMMENT '参数声明',
    `result_schema` json DEFAULT NULL COMMENT '缓存的结果列',
    `cache_ttl` int DEFAULT 0 COMMENT '结果缓存时长（秒）',
    `datasource_id` bigint DEFAULT NULL COMMENT '绑定的外部数据源ID（为空表示主库）',
    `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by` bigint DEFAULT NULL,
    `updated_by` bigint DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_dashboard_dataset_tenant_enabled` (`tenant_id`, `enabled`),
    KEY `idx_dashboard_dataset_tenant_name` (`tenant_id`, `name`)
) COMMENT='工作台数据集';

SET @dashboard_dataset_add_shared = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `dashboard_dataset` ADD COLUMN `shared` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''是否租户内共享（仅创建人可修改/删除）'' AFTER `remark`',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'dashboard_dataset'
      AND column_name = 'shared'
);
PREPARE dashboard_dataset_add_shared FROM @dashboard_dataset_add_shared;
EXECUTE dashboard_dataset_add_shared;
DEALLOCATE PREPARE dashboard_dataset_add_shared;

CREATE TABLE IF NOT EXISTS `dashboard_history` (
    `id` bigint NOT NULL COMMENT '主键',
    `tenant_id` bigint NOT NULL COMMENT '租户ID',
    `dashboard_id` bigint NOT NULL COMMENT '关联模板ID',
    `config` json DEFAULT NULL COMMENT '版本DSL快照',
    `note` varchar(200) DEFAULT NULL COMMENT '版本备注',
    `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by` bigint DEFAULT NULL,
    `updated_by` bigint DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_dashboard_history_owner` (`dashboard_id`, `created_by`, `created_at`),
    KEY `idx_dashboard_history_tenant` (`tenant_id`)
) COMMENT='工作台个人版本历史';
