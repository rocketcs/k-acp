package com.hxh.apboa.engine.tool.builtins;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hxh.apboa.common.annotation.Scope;
import com.hxh.apboa.common.entity.Attach;
import com.hxh.apboa.common.enums.ScopeType;
import com.hxh.apboa.common.util.TenantUtils;
import com.hxh.apboa.engine.agui.AgentContext;
import com.hxh.apboa.engine.tool.IAgentTool;
import com.hxh.apboa.resource.service.AttachService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 生成并发布可下载的 Word、PDF、Excel 文档。
 *
 * 工具协议名使用 ASCII 的 export_document；工具描述、参数和返回信息均为中文。
 */
@Component
@Scope(ScopeType.GLOBAL)
@RequiredArgsConstructor
public class ExportDocumentTool implements IAgentTool {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile("^\\s*[-*+]\\s+(.+)$");
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^\\s*\\d+[.)]\\s+(.+)$");
    private static final Pattern TABLE_SEPARATOR_PATTERN = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");

    private final AttachService attachService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** PDF 中文字体文件，例如 NotoSansCJK-Regular.ttc。 */
    @Value("${document-export.pdf-font-path:}")
    private String pdfFontPath;

    @Tool(
            name = "export_document",
            description = "生成并下载文档。支持 Word、PDF、Excel 三种格式；content 支持 Markdown 标题、段落、列表和表格，并会转换为对应格式的原生结构。生成成功后返回可直接下载的 download_url。Excel 也支持传 JSON 数组，例如 [{\"姓名\":\"张三\",\"金额\":100}]。"
    )
    public Object exportDocument(
            @ToolParam(name = "format", description = "文件格式：docx、pdf 或 xlsx") String format,
            @ToolParam(name = "title", description = "文档标题") String title,
            @ToolParam(name = "content", description = "文档正文；生成 Excel 时传 JSON 数组") String content,
            @ToolParam(name = "file_name", description = "输出文件名，可不填写", required = false) String fileName,
            AgentContext agentContext) {
        if (agentContext == null) {
            return error("AgentContext 不存在");
        }

        try {
            String normalizedFormat = normalizeFormat(format);
            byte[] bytes;
            String outputName;
            switch (normalizedFormat) {
                case "docx" -> {
                    bytes = buildDocx(title, content);
                    outputName = normalizeFileName(fileName, title, ".docx");
                }
                case "pdf" -> {
                    bytes = buildPdf(title, content);
                    outputName = normalizeFileName(fileName, title, ".pdf");
                }
                case "xlsx" -> {
                    bytes = buildXlsx(title, content);
                    outputName = normalizeFileName(fileName, title, ".xlsx");
                }
                default -> throw new IllegalArgumentException("不支持的文件格式：" + format);
            }
            return upload(bytes, outputName, agentContext);
        } catch (Exception e) {
            return error("文档生成失败：" + e.getMessage());
        }
    }

    static String normalizeFormat(String format) {
        String value = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "word", "doc", "docx" -> "docx";
            case "pdf" -> "pdf";
            case "excel", "xls", "xlsx" -> "xlsx";
            default -> throw new IllegalArgumentException("不支持的文件格式：" + format);
        };
    }

    private byte[] buildDocx(String title, String content) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            String documentTitle = title == null || title.isBlank() ? "未命名文档" : title;
            List<MarkdownBlock> blocks = parseMarkdown(content);
            boolean titleAlreadyPresent = !blocks.isEmpty() && blocks.get(0).type() == BlockType.HEADING
                    && documentTitle.equals(blocks.get(0).text());
            if (!titleAlreadyPresent) {
                XWPFParagraph titleParagraph = document.createParagraph();
                titleParagraph.setAlignment(ParagraphAlignment.CENTER);
                var titleRun = titleParagraph.createRun();
                titleRun.setText(documentTitle);
                titleRun.setBold(true);
                titleRun.setFontSize(18);
                titleRun.setFontFamily("Microsoft YaHei");
            }

            for (MarkdownBlock block : blocks) {
                switch (block.type()) {
                    case HEADING -> {
                        XWPFParagraph paragraph = document.createParagraph();
                        paragraph.setStyle("Heading " + Math.min(block.level(), 3));
                        var run = paragraph.createRun();
                        run.setText(block.text());
                        run.setBold(true);
                        run.setFontSize(block.level() == 1 ? 16 : 13);
                        run.setFontFamily("Microsoft YaHei");
                    }
                    case TABLE -> appendDocxTable(document, block.rows());
                    case LIST -> {
                        XWPFParagraph paragraph = document.createParagraph();
                        paragraph.setStyle(block.ordered() ? "List Number" : "List Bullet");
                        var run = paragraph.createRun();
                        run.setText(block.text());
                        run.setFontSize(11);
                        run.setFontFamily("Microsoft YaHei");
                    }
                    case PARAGRAPH -> {
                        XWPFParagraph paragraph = document.createParagraph();
                        var run = paragraph.createRun();
                        run.setText(block.text());
                        run.setFontSize(11);
                        run.setFontFamily("Microsoft YaHei");
                    }
                }
            }
            document.write(output);
            return output.toByteArray();
        }
    }

    private void appendDocxTable(XWPFDocument document, List<List<String>> rows) {
        if (rows.isEmpty()) return;
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        XWPFTable table = document.createTable(rows.size(), columns);
        var tableProperties = table.getCTTbl().getTblPr();
        var tableStyle = tableProperties.isSetTblStyle() ? tableProperties.getTblStyle() : tableProperties.addNewTblStyle();
        tableStyle.setVal("TableGrid");
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            var row = table.getRow(rowIndex);
            for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
                String value = columnIndex < rows.get(rowIndex).size() ? rows.get(rowIndex).get(columnIndex) : "";
                var cell = row.getCell(columnIndex);
                cell.setText(value);
                for (var run : cell.getParagraphs().get(0).getRuns()) {
                    run.setFontFamily("Microsoft YaHei");
                    run.setFontSize(10);
                    if (rowIndex == 0) run.setBold(true);
                }
            }
        }
    }

    private byte[] buildPdf(String title, String content) throws IOException {
        if (pdfFontPath == null || pdfFontPath.isBlank()) {
            throw new IllegalStateException("未配置 document-export.pdf-font-path，无法生成中文 PDF");
        }
        Path font = Path.of(pdfFontPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(font)) {
            throw new IllegalStateException("PDF 中文字体文件不存在：" + font);
        }

        String documentTitle = title == null || title.isBlank() ? "未命名文档" : title;
        List<MarkdownBlock> blocks = new ArrayList<>();
        blocks.add(MarkdownBlock.heading(1, documentTitle));
        List<MarkdownBlock> contentBlocks = parseMarkdown(content);
        if (!contentBlocks.isEmpty() && contentBlocks.get(0).type() == BlockType.HEADING
                && documentTitle.equals(contentBlocks.get(0).text())) {
            blocks.clear();
        }
        blocks.addAll(contentBlocks);

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            TrueTypeCollection collection = null;
            TrueTypeFont collectionFont = null;
            try {
                PDType0Font pdfFont;
                if (isFontCollection(font)) {
                    collection = new TrueTypeCollection(font.toFile());
                    TrueTypeFont[] firstFont = new TrueTypeFont[1];
                    collection.processAllFonts(candidate -> {
                        if (firstFont[0] == null) {
                            firstFont[0] = candidate;
                        } else {
                            candidate.close();
                        }
                    });
                    collectionFont = firstFont[0];
                    if (collectionFont == null) {
                        throw new IllegalStateException("字体集合中没有可用字体：" + font);
                    }
                    // PDFBox 不能直接把 TTC 当作单个 TTF 解析，需先取出集合中的字体。
                    pdfFont = PDType0Font.load(document, collectionFont, true);
                } else {
                    pdfFont = PDType0Font.load(document, font.toFile());
                }

                renderPdfBlocks(document, pdfFont, blocks);
                document.save(output);
                return output.toByteArray();
            } finally {
                if (collectionFont != null) {
                    collectionFont.close();
                }
                if (collection != null) {
                    collection.close();
                }
            }
        }
    }

    private void renderPdfBlocks(PDDocument document, PDType0Font font, List<MarkdownBlock> blocks) throws IOException {
        PDPage page = null;
        PDPageContentStream stream = null;
        float y = 800;
        try {
            for (MarkdownBlock block : blocks) {
                if (page == null || y < 80) {
                    if (stream != null) stream.close();
                    page = new PDPage(PDRectangle.A4);
                    document.addPage(page);
                    stream = new PDPageContentStream(document, page);
                    y = 800;
                }
                if (block.type() == BlockType.TABLE) {
                    int offset = 0;
                    while (offset < block.rows().size()) {
                        PdfTableResult tableResult = renderPdfTable(stream, font, block.rows().subList(offset, block.rows().size()), y);
                        if (tableResult.rowsRendered() == 0) {
                            stream.close();
                            page = new PDPage(PDRectangle.A4);
                            document.addPage(page);
                            stream = new PDPageContentStream(document, page);
                            y = 800;
                            tableResult = renderPdfTable(stream, font, block.rows().subList(offset, block.rows().size()), y);
                            if (tableResult.rowsRendered() == 0) throw new IOException("PDF 表格行高度超过页面可用空间");
                        }
                        offset += tableResult.rowsRendered();
                        y = tableResult.y();
                        if (offset < block.rows().size()) {
                            stream.close();
                            page = new PDPage(PDRectangle.A4);
                            document.addPage(page);
                            stream = new PDPageContentStream(document, page);
                            y = 800;
                        }
                    }
                    continue;
                }
                int size = block.type() == BlockType.HEADING ? (block.level() == 1 ? 16 : 13) : 11;
                List<String> lines = wrapText(block.text(), size, 495);
                for (String line : lines) {
                    if (y < 55) {
                        stream.close();
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        stream = new PDPageContentStream(document, page);
                        y = 800;
                    }
                    stream.beginText();
                    stream.setFont(font, size);
                    stream.newLineAtOffset(50, y);
                    stream.showText((block.type() == BlockType.LIST ? (block.ordered() ? "1. " : "• ") : "") + line);
                    stream.endText();
                    y -= block.type() == BlockType.HEADING ? 24 : 18;
                }
                y -= 6;
            }
        } finally {
            if (stream != null) stream.close();
        }
    }

    private PdfTableResult renderPdfTable(PDPageContentStream stream, PDType0Font font, List<List<String>> rows, float y) throws IOException {
        if (rows.isEmpty()) return new PdfTableResult(y, 0);
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        float width = 495f / columns;
        int rendered = 0;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<List<String>> wrapped = new ArrayList<>();
            int maxLines = 1;
            for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
                String value = columnIndex < rows.get(rowIndex).size() ? rows.get(rowIndex).get(columnIndex) : "";
                List<String> cellLines = wrapText(value, 9, width - 8);
                wrapped.add(cellLines);
                maxLines = Math.max(maxLines, cellLines.size());
            }
            float rowHeight = maxLines * 13 + 7;
            if (y - rowHeight < 45) {
                break;
            }
            stream.setLineWidth(0.5f);
            stream.addRect(50, y - rowHeight, 495, rowHeight);
            for (int columnIndex = 1; columnIndex < columns; columnIndex++) {
                stream.moveTo(50 + width * columnIndex, y);
                stream.lineTo(50 + width * columnIndex, y - rowHeight);
            }
            stream.stroke();
            for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
                stream.beginText();
                stream.setFont(font, 9);
                if (rowIndex == 0) stream.setFont(font, 9);
                stream.newLineAtOffset(54 + width * columnIndex, y - 13);
                for (int lineIndex = 0; lineIndex < wrapped.get(columnIndex).size(); lineIndex++) {
                    if (lineIndex > 0) stream.newLineAtOffset(0, -13);
                    stream.showText(wrapped.get(columnIndex).get(lineIndex));
                }
                stream.endText();
            }
            y -= rowHeight;
            rendered++;
        }
        return new PdfTableResult(y - 10, rendered);
    }

    private record PdfTableResult(float y, int rowsRendered) {}

    private List<String> wrapText(String value, int fontSize, float width) {
        int maxChars = Math.max(1, (int) (width / (fontSize * 0.9f)));
        List<String> result = new ArrayList<>();
        String text = value == null ? "" : value;
        if (text.isEmpty()) return List.of("");
        for (String part : text.split("\\R", -1)) {
            for (int start = 0; start < part.length(); start += maxChars) {
                result.add(part.substring(start, Math.min(part.length(), start + maxChars)));
            }
        }
        return result.isEmpty() ? List.of("") : result;
    }

    private boolean isFontCollection(Path font) {
        String fileName = font.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".ttc") || fileName.endsWith(".otc");
    }

    private byte[] buildXlsx(String title, String content) throws IOException {
        if (!looksLikeJsonArray(content)) {
            return buildMarkdownXlsx(title, content);
        }
        List<LinkedHashMap<String, Object>> rows = objectMapper.readValue(
                content == null || content.isBlank() ? "[]" : content,
                new TypeReference<>() {});

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("数据");
            Set<String> columns = new LinkedHashSet<>();
            rows.forEach(row -> columns.addAll(row.keySet()));
            if (columns.isEmpty()) {
                sheet.createRow(0).createCell(0).setCellValue("暂无数据");
            } else {
                Row header = sheet.createRow(0);
                int columnIndex = 0;
                for (String column : columns) {
                    header.createCell(columnIndex++).setCellValue(column);
                }
                int rowIndex = 1;
                for (Map<String, Object> data : rows) {
                    Row row = sheet.createRow(rowIndex++);
                    columnIndex = 0;
                    for (String column : columns) {
                        writeCell(row.createCell(columnIndex++), data.get(column));
                    }
                }
                for (int index = 0; index < columns.size(); index++) {
                    sheet.autoSizeColumn(index);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private boolean looksLikeJsonArray(String content) {
        String value = content == null ? "" : content.trim();
        return value.equals("[]") || value.startsWith("[{" );
    }

    private byte[] buildMarkdownXlsx(String title, String content) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("文档");
            int rowIndex = 0;
            List<MarkdownBlock> blocks = parseMarkdown(content);
            boolean titleAlreadyPresent = !blocks.isEmpty() && blocks.get(0).type() == BlockType.HEADING
                    && title != null && title.equals(blocks.get(0).text());
            if (title != null && !title.isBlank() && !titleAlreadyPresent) {
                Row titleRow = sheet.createRow(rowIndex++);
                titleRow.createCell(0).setCellValue(title);
            }
            int maxColumns = 1;
            for (MarkdownBlock block : blocks) {
                if (block.type() == BlockType.TABLE) maxColumns = Math.max(maxColumns, block.rows().stream().mapToInt(List::size).max().orElse(1));
            }
            if (title != null && !title.isBlank() && !titleAlreadyPresent && maxColumns > 1) {
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, maxColumns - 1));
            }
            for (MarkdownBlock block : blocks) {
                if (block.type() == BlockType.TABLE) {
                    for (int r = 0; r < block.rows().size(); r++) {
                        Row row = sheet.createRow(rowIndex++);
                        List<String> values = block.rows().get(r);
                        for (int c = 0; c < values.size(); c++) {
                            Cell cell = row.createCell(c);
                            cell.setCellValue(values.get(c));
                            if (r == 0) {
                                var style = workbook.createCellStyle();
                                style.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_BLUE.getIndex());
                                style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
                                var font = workbook.createFont();
                                font.setBold(true);
                                font.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
                                style.setFont(font);
                                cell.setCellStyle(style);
                            }
                        }
                    }
                    rowIndex++;
                } else {
                    Row row = sheet.createRow(rowIndex++);
                    row.createCell(0).setCellValue(block.type() == BlockType.LIST ? (block.ordered() ? "1. " : "• ") + block.text() : block.text());
                }
            }
            for (int c = 0; c < maxColumns; c++) sheet.autoSizeColumn(c);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void writeCell(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    private Object upload(byte[] bytes, String fileName, AgentContext context) {
        Long previousTenantId = TenantUtils.getCurrentTenantId();
        String previousTenantCode = TenantUtils.getCurrentTenantCode();
        try {
            TenantUtils.setCurrentTenant(context.getTenantId(), context.getTenantCode());
            Attach attach = attachService.upload(
                    new BytesMultipartFile("file", fileName, contentType(fileName), bytes),
                    fileName);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("file_name", fileName);
            result.put("attachment_id", attach.getId());
            result.put("download_url", "/api/attach/download/" + attach.getId());
            result.put("message", "文档已生成，可以下载");
            return result;
        } finally {
            if (previousTenantId == null) {
                TenantUtils.clear();
            } else {
                TenantUtils.setCurrentTenant(previousTenantId, previousTenantCode);
            }
        }
    }

    private String normalizeFileName(String fileName, String title, String extension) {
        String value = fileName == null || fileName.isBlank() ? title : fileName;
        if (value == null || value.isBlank()) value = "导出文档";
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return value.toLowerCase(Locale.ROOT).endsWith(extension) ? value : value + extension;
    }

    private String contentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }

    enum BlockType { HEADING, PARAGRAPH, LIST, TABLE }

    record MarkdownBlock(BlockType type, String text, int level, boolean ordered, List<List<String>> rows) {
        static MarkdownBlock heading(int level, String text) { return new MarkdownBlock(BlockType.HEADING, text, level, false, List.of()); }
        static MarkdownBlock paragraph(String text) { return new MarkdownBlock(BlockType.PARAGRAPH, text, 0, false, List.of()); }
        static MarkdownBlock list(String text, boolean ordered) { return new MarkdownBlock(BlockType.LIST, text, 0, ordered, List.of()); }
        static MarkdownBlock table(List<List<String>> rows) { return new MarkdownBlock(BlockType.TABLE, "", 0, false, rows); }
    }

    static List<MarkdownBlock> parseMarkdown(String markdown) {
        List<MarkdownBlock> blocks = new ArrayList<>();
        String[] lines = (markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n')).split("\n", -1);
        StringBuilder paragraph = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) { flushParagraph(blocks, paragraph); continue; }
            var heading = HEADING_PATTERN.matcher(line);
            if (heading.matches()) { flushParagraph(blocks, paragraph); blocks.add(MarkdownBlock.heading(heading.group(1).length(), cleanInlineMarkdown(heading.group(2).replaceFirst("\\s+#+$", "").trim()))); continue; }
            if (isTableLine(line) && i + 1 < lines.length && TABLE_SEPARATOR_PATTERN.matcher(lines[i + 1]).matches()) {
                flushParagraph(blocks, paragraph);
                List<List<String>> rows = new ArrayList<>();
                rows.add(splitTableRow(line));
                i++;
                while (i + 1 < lines.length && isTableLine(lines[i + 1]) && !lines[i + 1].isBlank()) rows.add(splitTableRow(lines[++i]));
                blocks.add(MarkdownBlock.table(rows));
                continue;
            }
            var unordered = UNORDERED_LIST_PATTERN.matcher(line);
            var ordered = ORDERED_LIST_PATTERN.matcher(line);
            if (unordered.matches() || ordered.matches()) { flushParagraph(blocks, paragraph); blocks.add(MarkdownBlock.list(cleanInlineMarkdown((unordered.matches() ? unordered.group(1) : ordered.group(1)).trim()), ordered.matches())); continue; }
            if (paragraph.length() > 0) paragraph.append('\n');
            paragraph.append(cleanInlineMarkdown(line.trim()));
        }
        flushParagraph(blocks, paragraph);
        return blocks;
    }

    private static void flushParagraph(List<MarkdownBlock> blocks, StringBuilder paragraph) {
        if (paragraph.length() > 0) { blocks.add(MarkdownBlock.paragraph(paragraph.toString())); paragraph.setLength(0); }
    }

    private static boolean isTableLine(String line) { return line.indexOf('|') >= 0; }

    private static List<String> splitTableRow(String line) {
        String value = line.trim();
        if (value.startsWith("|")) value = value.substring(1);
        if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
        return java.util.Arrays.stream(value.split("(?<!\\\\)\\|", -1)).map(String::trim).map(ExportDocumentTool::cleanInlineMarkdown).map(s -> s.replace("\\|", "|")) .toList();
    }

    private static String cleanInlineMarkdown(String value) {
        return value.replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("(`{1,3}|\\*\\*|__|~~)", "");
    }

    private record BytesMultipartFile(
            String name,
            String originalFilename,
            String contentType,
            byte[] bytes
    ) implements MultipartFile {
        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), bytes); }
    }
}
