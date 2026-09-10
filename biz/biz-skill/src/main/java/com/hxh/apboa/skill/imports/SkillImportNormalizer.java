package com.hxh.apboa.skill.imports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 导入前规范化技能目录（兼容常见命名差异）。
 */
public final class SkillImportNormalizer {

    private static final Logger log = LoggerFactory.getLogger(SkillImportNormalizer.class);

    private SkillImportNormalizer() {
    }

    /**
     * 将各技能子目录中的 skill.md 规范为 SKILL.md。
     */
    public static void normalizeSkillFiles(Path skillsDir) throws IOException {
        if (skillsDir == null || !Files.isDirectory(skillsDir)) {
            return;
        }
        try (Stream<Path> subdirs = Files.list(skillsDir)) {
            subdirs.filter(Files::isDirectory)
                    .filter(dir -> !SkillImportConstants.isNoiseDirectory(dir.getFileName().toString()))
                    .forEach(SkillImportNormalizer::normalizeSingleSkillDir);
        }
    }

    private static void normalizeSingleSkillDir(Path skillDir) {
        Path skillFile = skillDir.resolve(SkillImportConstants.SKILL_FILE);
        Path legacyFile = skillDir.resolve(SkillImportConstants.LEGACY_SKILL_FILE);
        try {
            if (!Files.exists(skillFile)) {
                if (!Files.exists(legacyFile)) {
                    return;
                }
                Files.move(legacyFile, skillFile, StandardCopyOption.REPLACE_EXISTING);
                log.info("Renamed {} to SKILL.md in {}", SkillImportConstants.LEGACY_SKILL_FILE, skillDir.getFileName());
            }
            normalizeSkillMdEncoding(skillFile, skillDir);
        } catch (IOException e) {
            log.warn("Failed to normalize skill file in {}: {}", skillDir, e.getMessage());
        }
    }

    /**
     * 兼容 Windows 环境生成的 SKILL.md：剥离 UTF-8 BOM、CRLF 换行统一为 LF。
     * 幂等：已是标准格式的文件不会重写。
     */
    private static void normalizeSkillMdEncoding(Path skillFile, Path skillDir) throws IOException {
        String content = Files.readString(skillFile, StandardCharsets.UTF_8);
        String normalized = content;
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replace("\r\n", "\n");
        if (!normalized.equals(content)) {
            Files.writeString(skillFile, normalized, StandardCharsets.UTF_8);
            log.info("Normalized BOM/line-endings in SKILL.md of {}", skillDir.getFileName());
        }
    }
}
