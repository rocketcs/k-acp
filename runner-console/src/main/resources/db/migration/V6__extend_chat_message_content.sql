-- 完整持久化工具调用结果（含 Neo4j 字段证据图），避免 TEXT（64 KiB）截断。
ALTER TABLE `chat_message`
    MODIFY COLUMN `content` MEDIUMTEXT NOT NULL COMMENT '消息内容';
