package com.hxh.apboa.engine.tool.builtins;

import com.hxh.apboa.common.entity.Attach;
import com.hxh.apboa.engine.agui.AgentContext;
import com.hxh.apboa.resource.service.AttachService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExportDocumentToolTest {

    @Test
    void normalizesChineseFormatNamesToSupportedExtensions() {
        assertEquals("docx", ExportDocumentTool.normalizeFormat("Word"));
        assertEquals("pdf", ExportDocumentTool.normalizeFormat("PDF"));
        assertEquals("xlsx", ExportDocumentTool.normalizeFormat("Excel"));
    }

    @Test
    void rejectsUnsupportedFormats() {
        assertThrows(IllegalArgumentException.class,
                () -> ExportDocumentTool.normalizeFormat("pptx"));
    }

    @Test
    void generatesDocxAndPublishesDownloadUrl() throws Exception {
        AttachService attachService = mock(AttachService.class);
        Attach uploaded = new Attach();
        uploaded.setId(100L);
        when(attachService.upload(any(), anyString())).thenReturn(uploaded);

        ExportDocumentTool tool = new ExportDocumentTool(attachService);
        AgentContext context = new AgentContext();
        context.setTenantId(1L);
        context.setTenantCode("test");

        Map<?, ?> result = (Map<?, ?>) tool.exportDocument(
                "docx", "测试文档", "第一行\n第二行", null, context);

        assertTrue((Boolean) result.get("success"), String.valueOf(result));
        assertEquals("/api/attach/download/100", result.get("download_url"));

        var multipart = org.mockito.Mockito.mockingDetails(attachService)
                .getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("upload"))
                .findFirst().orElseThrow().getArgument(0, org.springframework.web.multipart.MultipartFile.class);
        try (XWPFDocument document = new XWPFDocument(multipart.getInputStream())) {
            assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().contains("第一行")));
        }
    }

    @Test
    void convertsMarkdownTableToNativeWordTable() throws Exception {
        AttachService attachService = mock(AttachService.class);
        Attach uploaded = new Attach();
        uploaded.setId(102L);
        when(attachService.upload(any(), anyString())).thenReturn(uploaded);

        ExportDocumentTool tool = new ExportDocumentTool(attachService);
        AgentContext context = new AgentContext();
        context.setTenantId(1L);
        context.setTenantCode("test");

        tool.exportDocument("docx", "测试", "## 标题\n\n| 姓名 | 金额 |\n| --- | --- |\n| 张三 | 100 |", null, context);
        var multipart = org.mockito.Mockito.mockingDetails(attachService).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("upload"))
                .findFirst().orElseThrow().getArgument(0, org.springframework.web.multipart.MultipartFile.class);
        try (XWPFDocument document = new XWPFDocument(multipart.getInputStream())) {
            assertEquals(1, document.getTables().size());
            assertEquals("姓名", document.getTables().get(0).getRow(0).getCell(0).getText());
            assertEquals("张三", document.getTables().get(0).getRow(1).getCell(0).getText());
            assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().contains("标题")));
        }
    }

    @Test
    void parsesMarkdownBlocksWithoutLeakingSyntax() {
        var blocks = ExportDocumentTool.parseMarkdown("# 标题\n\n正文\n\n- 项目\n\n| A | B |\n| --- | --- |\n| 1 | 2 |");
        assertEquals(4, blocks.size());
        assertEquals(ExportDocumentTool.BlockType.HEADING, blocks.get(0).type());
        assertEquals("标题", blocks.get(0).text());
        assertEquals(ExportDocumentTool.BlockType.TABLE, blocks.get(3).type());
        assertEquals("A", blocks.get(3).rows().get(0).get(0));
    }

    @Test
    void generatesXlsxAndPublishesDownloadUrl() throws Exception {
        AttachService attachService = mock(AttachService.class);
        Attach uploaded = new Attach();
        uploaded.setId(101L);
        when(attachService.upload(any(), anyString())).thenReturn(uploaded);

        ExportDocumentTool tool = new ExportDocumentTool(attachService);
        AgentContext context = new AgentContext();
        context.setTenantId(1L);
        context.setTenantCode("test");

        Map<?, ?> result = (Map<?, ?>) tool.exportDocument(
                "xlsx", "数据", "[{\"姓名\":\"张三\",\"金额\":100}]", null, context);

        assertTrue((Boolean) result.get("success"), String.valueOf(result));
        var multipart = org.mockito.Mockito.mockingDetails(attachService)
                .getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("upload"))
                .findFirst().orElseThrow().getArgument(0, org.springframework.web.multipart.MultipartFile.class);
        try (XSSFWorkbook workbook = new XSSFWorkbook(multipart.getInputStream())) {
            assertEquals("姓名", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("张三", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void convertsMarkdownTableToNativeExcelCells() throws Exception {
        AttachService attachService = mock(AttachService.class);
        Attach uploaded = new Attach();
        uploaded.setId(103L);
        when(attachService.upload(any(), anyString())).thenReturn(uploaded);

        ExportDocumentTool tool = new ExportDocumentTool(attachService);
        AgentContext context = new AgentContext();
        context.setTenantId(1L);
        context.setTenantCode("test");

        tool.exportDocument("xlsx", "测试表", "| 姓名 | 金额 |\n| --- | --- |\n| 张三 | 100 |", null, context);
        var multipart = org.mockito.Mockito.mockingDetails(attachService).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("upload"))
                .findFirst().orElseThrow().getArgument(0, org.springframework.web.multipart.MultipartFile.class);
        try (XSSFWorkbook workbook = new XSSFWorkbook(multipart.getInputStream())) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("姓名", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("张三", sheet.getRow(2).getCell(0).getStringCellValue());
        }
    }

    @Test
    void convertsMarkdownContentToPdf() throws Exception {
        AttachService attachService = mock(AttachService.class);
        Attach uploaded = new Attach();
        uploaded.setId(104L);
        when(attachService.upload(any(), anyString())).thenReturn(uploaded);

        ExportDocumentTool tool = new ExportDocumentTool(attachService);
        var fontField = ExportDocumentTool.class.getDeclaredField("pdfFontPath");
        fontField.setAccessible(true);
        fontField.set(tool, "/System/Library/Fonts/SFNS.ttf");
        AgentContext context = new AgentContext();
        context.setTenantId(1L);
        context.setTenantCode("test");

        Map<?, ?> result = (Map<?, ?>) tool.exportDocument(
                "pdf", "Test", "## Heading\n\n| A | B |\n| --- | --- |\n| 1 | 2 |", null, context);
        assertTrue((Boolean) result.get("success"), String.valueOf(result));
    }
}
