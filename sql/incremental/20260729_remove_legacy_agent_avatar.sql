-- Avatar names are derived from the agent ID at runtime and are not persisted.
-- Older deployments may retain this NOT NULL legacy column, which prevents
-- creating new agents because the current insert intentionally omits it.
-- MySQL does not support "DROP COLUMN IF EXISTS", so prepare a no-op when the
-- database has already been corrected.
SET @has_legacy_avatar_column := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_definition'
      AND COLUMN_NAME = 'avatar'
);
SET @remove_legacy_avatar_sql := IF(
    @has_legacy_avatar_column > 0,
    'ALTER TABLE `agent_definition` DROP COLUMN `avatar`',
    'SELECT 1'
);
PREPARE remove_legacy_avatar_stmt FROM @remove_legacy_avatar_sql;
EXECUTE remove_legacy_avatar_stmt;
DEALLOCATE PREPARE remove_legacy_avatar_stmt;
