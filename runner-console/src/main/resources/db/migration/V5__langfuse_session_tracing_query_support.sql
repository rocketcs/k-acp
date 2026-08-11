CREATE TABLE IF NOT EXISTS `langfuse_session_tracing` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `tenant_id` bigint NOT NULL,
    `session_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
    `project_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `langfuse_base_url` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
    `retrieval_method` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL,
    `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
    `trace_count` int NOT NULL DEFAULT '0',
    `seed_observation_count` int NOT NULL DEFAULT '0',
    `full_observation_count` int NOT NULL DEFAULT '0',
    `score_count` int NOT NULL DEFAULT '0',
    `qa_pair_count` int NOT NULL DEFAULT '0',
    `type_counts_json` json DEFAULT NULL,
    `qa_pairs_json` json DEFAULT NULL,
    `conversation_json` json DEFAULT NULL,
    `envelope_json` json NOT NULL,
    `llm_analysis_json` json DEFAULT NULL,
    `warnings_json` json DEFAULT NULL,
    `source_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
    `first_observation_start_time` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `last_observation_end_time` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `processed_at` datetime(3) NOT NULL,
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by` bigint DEFAULT NULL,
    `updated_by` bigint DEFAULT NULL,
    `enabled` tinyint(1) NOT NULL DEFAULT '1',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_langfuse_session_tracing_tenant_session` (`tenant_id`, `session_id`),
    KEY `idx_langfuse_session_tracing_project` (`project_id`),
    KEY `idx_langfuse_session_tracing_processed_at` (`processed_at`),
    KEY `idx_langfuse_session_tracing_source_hash` (`source_hash`),
    KEY `idx_langfuse_session_tracing_tenant_processed` (`tenant_id`, `processed_at`),
    KEY `idx_langfuse_session_tracing_tenant_status_processed` (`tenant_id`, `status`, `processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `langfuse_session_trace_cursor` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `tenant_id` bigint NOT NULL,
    `session_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    `project_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `first_seen_at` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `last_seen_at` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `observation_count` int NOT NULL DEFAULT '0',
    `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DISCOVERED',
    `attempt_count` int NOT NULL DEFAULT '0',
    `leased_until` datetime(3) DEFAULT NULL,
    `lease_owner` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `last_error` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
    `discovered_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `processed_at` datetime(3) DEFAULT NULL,
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `enabled` tinyint(1) NOT NULL DEFAULT '1',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_langfuse_session_trace_cursor_tenant_session` (`tenant_id`, `session_id`),
    KEY `idx_langfuse_session_trace_cursor_status` (`tenant_id`, `status`, `leased_until`),
    KEY `idx_langfuse_session_trace_cursor_last_seen` (`tenant_id`, `last_seen_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @langfuse_session_tracing_add_tenant_processed = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `langfuse_session_tracing` ADD KEY `idx_langfuse_session_tracing_tenant_processed` (`tenant_id`, `processed_at`)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'langfuse_session_tracing'
      AND index_name = 'idx_langfuse_session_tracing_tenant_processed'
);
PREPARE langfuse_session_tracing_add_tenant_processed FROM @langfuse_session_tracing_add_tenant_processed;
EXECUTE langfuse_session_tracing_add_tenant_processed;
DEALLOCATE PREPARE langfuse_session_tracing_add_tenant_processed;

SET @langfuse_session_tracing_add_tenant_status_processed = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE `langfuse_session_tracing` ADD KEY `idx_langfuse_session_tracing_tenant_status_processed` (`tenant_id`, `status`, `processed_at`)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'langfuse_session_tracing'
      AND index_name = 'idx_langfuse_session_tracing_tenant_status_processed'
);
PREPARE langfuse_session_tracing_add_tenant_status_processed FROM @langfuse_session_tracing_add_tenant_status_processed;
EXECUTE langfuse_session_tracing_add_tenant_status_processed;
DEALLOCATE PREPARE langfuse_session_tracing_add_tenant_status_processed;
