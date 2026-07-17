CREATE TABLE IF NOT EXISTS `agent_diy_page_config` (
  `id` bigint NOT NULL COMMENT '主键',
  `agent_definition_id` bigint NOT NULL COMMENT '智能体ID',
  `draft_config` json DEFAULT NULL COMMENT 'DIY草稿配置',
  `published_config` json DEFAULT NULL COMMENT 'DIY已发布配置',
  `published_at` datetime DEFAULT NULL COMMENT '发布时间',
  `enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_diy_page` (`tenant_id`, `agent_definition_id`),
  KEY `idx_agent_diy_page_agent` (`agent_definition_id`)
) COMMENT='智能体DIY页面配置';
