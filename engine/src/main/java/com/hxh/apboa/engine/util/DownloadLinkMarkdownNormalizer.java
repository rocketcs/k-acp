package com.hxh.apboa.engine.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将助手文本中的附件下载地址规范为 Markdown 链接。
 *
 * <p>文档导出工具已成功返回下载地址时，模型偶尔会把地址包在反引号中，
 * 导致前端按代码而非链接渲染。本类只处理平台内部附件下载路径，避免影响其他文本。</p>
 */
public final class DownloadLinkMarkdownNormalizer {
    private static final Pattern ATTACHMENT_DOWNLOAD_URL =
            Pattern.compile("`?(/api/attach/download/\\d+)`?");

    private DownloadLinkMarkdownNormalizer() {
    }

    public static String normalize(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        Matcher matcher = ATTACHMENT_DOWNLOAD_URL.matcher(content);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            if (isExistingMarkdownLink(content, matcher.start())) {
                matcher.appendReplacement(normalized, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String markdownLink = "[下载文件](" + matcher.group(1) + ")";
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(markdownLink));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    private static boolean isExistingMarkdownLink(String content, int urlStartIndex) {
        return urlStartIndex >= 2
                && content.charAt(urlStartIndex - 2) == ']'
                && content.charAt(urlStartIndex - 1) == '(';
    }
}
