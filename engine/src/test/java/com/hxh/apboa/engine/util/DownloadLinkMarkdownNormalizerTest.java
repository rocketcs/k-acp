package com.hxh.apboa.engine.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadLinkMarkdownNormalizerTest {

    @Test
    void convertsCodeWrappedAttachmentDownloadUrlToMarkdownLink() {
        assertEquals(
                "Word 文档已生成\n\n[下载文件](/api/attach/download/2092900851501174786)",
                DownloadLinkMarkdownNormalizer.normalize(
                        "Word 文档已生成\n\n`/api/attach/download/2092900851501174786`"));
    }

    @Test
    void preservesExistingMarkdownAttachmentDownloadLink() {
        assertEquals(
                "[下载 Word 文档](/api/attach/download/2092900851501174786)",
                DownloadLinkMarkdownNormalizer.normalize(
                        "[下载 Word 文档](/api/attach/download/2092900851501174786)"));
    }
}
