-- Store one bundled avatar filename for every agent. The UI serves the files
-- from ui/public/agent-avatars and does not support custom avatar uploads.
SET @agent_avatar_column_missing := (
    SELECT COUNT(*) = 0
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_definition'
      AND COLUMN_NAME = 'avatar'
);
SET @add_agent_avatar_column_sql := IF(
    @agent_avatar_column_missing,
    'ALTER TABLE `agent_definition` ADD COLUMN `avatar` varchar(64) NULL COMMENT ''智能体默认头像文件名'' AFTER `description`',
    'SELECT 1'
);
PREPARE add_agent_avatar_column_stmt FROM @add_agent_avatar_column_sql;
EXECUTE add_agent_avatar_column_stmt;
DEALLOCATE PREPARE add_agent_avatar_column_stmt;

-- Normalize existing data before narrowing the column type. Existing agents
-- receive a stable distribution across the bundled avatar files.
UPDATE `agent_definition`
SET `avatar` = CONCAT(
    'agent-avatar-',
    LPAD(MOD(CAST(`id` AS UNSIGNED), 24) + 1, 2, '0'),
    '.png'
)
WHERE `avatar` IS NULL
   OR `avatar` = ''
   OR `avatar` NOT REGEXP '^agent-avatar-(0[1-9]|1[0-9]|2[0-4])\\.png$';

ALTER TABLE `agent_definition`
    MODIFY COLUMN `avatar` varchar(64) NOT NULL DEFAULT 'agent-avatar-01.png'
    COMMENT '智能体默认头像文件名';
