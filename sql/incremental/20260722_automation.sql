DROP TABLE IF EXISTS `quartz_job_info`;
CREATE TABLE `quartz_job_info` (
`id` bigint NOT NULL COMMENT '任务身份唯一标识',
`type` enum('AGENT','WORKFLOW')  DEFAULT NULL COMMENT '类型(AGENT、WORKFLOW)',
`biz_id` varchar(64) DEFAULT NULL COMMENT '关联业务ID',
`cron` varchar(64) DEFAULT NULL COMMENT 'cron',
`job_class` varchar(100) DEFAULT NULL COMMENT 'job类路径',
`data_map` text COMMENT '执行参数',
`enabled` tinyint(1) DEFAULT NULL COMMENT '状态（0停止 1启动）',
`tenant_id` bigint NOT NULL,
`created_at` datetime DEFAULT NULL,
`updated_at` datetime DEFAULT NULL,
`created_by` bigint DEFAULT NULL,
`updated_by` bigint DEFAULT NULL,
PRIMARY KEY (`id`) USING BTREE,
KEY `idx_tenant_id` (`tenant_id`)
) COMMENT='quartz定时任务状态';

DROP TABLE IF EXISTS `quartz_job_records`;
CREATE TABLE `quartz_job_records` (
`job_id` bigint DEFAULT NULL COMMENT '任务ID',
`record_id` bigint DEFAULT NULL COMMENT '记录ID（chat_session_id，workflow_run_id）',
`create_time` datetime DEFAULT NULL COMMENT '创建时间'
) COMMENT='任务记录表';
