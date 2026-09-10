import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hxh.apboa.common.entity.Attach;
import com.hxh.apboa.common.util.TenantUtils;
import com.hxh.apboa.engine.agui.AgentContext;
import com.hxh.apboa.engine.tool.dynamices.IDynamicAgentTool;
import com.hxh.apboa.resource.service.AttachService;
import jakarta.annotation.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Dynamic tool source for the biaoshu-interpreter agent.
 *
 * It implements the non-writing subset of the Skill: interpretation, package
 * extraction, compliance review, task lifecycle, and local report export.
 * Bid-document generation is deliberately not implemented.
 */
public class BiaoshuInterpreterTool implements IDynamicAgentTool {
    private static final String BASE = "https://biaoshu.zhiliaobiaoxun.com/api/open/v1";
    private static final String KEY_FILE_ENV = "BIAOSHU_BAILIAN_APP_KEY_FILE";
    private static final long MAX_FILE_BYTES = 50L * 1024L * 1024L;
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Resource
    private AttachService attachService;

    @Override
    public Object execute(AgentContext context, Map<String, Object> params) {
        String operation = text(params, "operation").toLowerCase(Locale.ROOT);
        if ("export".equals(operation)) operation = "report";
        if (!List.of("account", "interpret", "status", "result", "packages", "compliance", "cancel", "report").contains(operation)) {
            return failure(operation, 422, "unsupported_operation", "Unsupported operation");
        }
        try {
            return switch (operation) {
                case "account" -> response(operation, request("GET", "/me", null, null));
                case "interpret" -> interpret(context, params);
                case "status" -> status(context, params);
                case "result" -> result(context, params);
                case "packages" -> packages(context, params);
                case "compliance" -> compliance(context, params);
                case "cancel" -> cancel(context, params);
                case "report" -> report(context, params);
                default -> failure(operation, 422, "unsupported_operation", "Unsupported operation");
            };
        } catch (ToolFailure error) {
            return failure(operation, error.status, error.code, error.getMessage());
        } catch (Exception error) {
            return failure(operation, 500, "tool_execution_failed", "The interpretation service could not complete the operation");
        }
    }

    private Map<String, Object> interpret(AgentContext context, Map<String, Object> params) throws Exception {
        requireContext(context);
        Attachment attachment = singleAttachment(context, params);
        String extension = attachment.attach.getExtension() == null ? "" : attachment.attach.getExtension().toLowerCase(Locale.ROOT);
        if (!List.of("pdf", "doc", "docx").contains(extension)) {
            throw new ToolFailure(422, "unsupported_file_type", "Only PDF, DOC, and DOCX tender files are supported");
        }
        if (attachment.attach.getAttachSize() != null && attachment.attach.getAttachSize() > MAX_FILE_BYTES) {
            throw new ToolFailure(422, "interpretation_file_too_large", "Tender files must not exceed 50 MB");
        }
        String boundary = "----KacpBiaoshu" + UUID.randomUUID().toString().replace("-", "");
        List<InputStream> streams = new ArrayList<>();
        streams.add(new ByteArrayInputStream(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + header(attachment.attach.getOriginalName()) + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8)));
        streams.add(attachmentStream(attachment));
        streams.add(new ByteArrayInputStream("\r\n".getBytes(StandardCharsets.UTF_8)));
        streams.add(new ByteArrayInputStream(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofInputStream(() -> new SequenceInputStream(Collections.enumeration(streams)));
        Map<String, Object> value = response("interpret", request("POST", "/interpretations", body, boundary));
        String jobId = text(value, "job_id");
        if (!jobId.isBlank()) saveJob(context, jobId);
        value.put("billable", true);
        // A tender interpretation is a single end-to-end operation by default:
        // submit, wait, fetch the eight-dimension result, then render its report.
        // Tool-call models sometimes omit optional arguments, so treating a missing
        // with_report as false silently turns this back into a manual workflow.
        boolean withReport = params == null || !params.containsKey("with_report")
                || Boolean.parseBoolean(text(params, "with_report"));
        if (withReport) {
            waitForCompletion(context, jobId, 300);
            Map<String, Object> completed = result(context, Map.of("job_id", jobId));
            String format = text(params, "report_format");
            if (format.isBlank()) format = "html";
            Map<String, Object> report = report(context, Map.of("job_id", jobId, "format", format,
                    "tender_name", attachment.attach.getOriginalName()));
            value.put("result", completed.get("result"));
            value.put("report_paths", report.get("report_paths"));
        }
        return value;
    }

    private void waitForCompletion(AgentContext context, String jobId, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> status = status(context, Map.of("job_id", jobId));
            String state = text(status, "status");
            if ("succeeded".equals(state)) return;
            if ("failed".equals(state) || "canceled".equals(state)) throw new ToolFailure(409, "interpretation_not_completed", "The interpretation task did not complete successfully");
            Thread.sleep(5000L);
        }
        throw new ToolFailure(504, "interpretation_timeout", "The interpretation task did not finish in time");
    }

    private Map<String, Object> status(AgentContext context, Map<String, Object> params) throws Exception {
        String jobId = ownedJob(context, required(params, "job_id"));
        Map<String, Object> value = response("status", request("GET", "/jobs/" + segment(jobId), null, null));
        value.put("job_id", jobId);
        return value;
    }

    private Map<String, Object> result(AgentContext context, Map<String, Object> params) throws Exception {
        String jobId = ownedJob(context, required(params, "job_id"));
        Map<String, Object> value = response("result", request("GET", "/jobs/" + segment(jobId) + "/result", null, null));
        value.put("job_id", jobId);
        Object result = value.get("result");
        if (result instanceof Map) {
            Map resultMap = (Map) result;
            if (resultMap.get("project_id") != null) saveProject(context, String.valueOf(resultMap.get("project_id")));
        }
        return value;
    }

    private Map<String, Object> packages(AgentContext context, Map<String, Object> params) throws Exception {
        String projectId = ownedProject(context, required(params, "project_id"));
        Map<String, Object> value = response("packages", request("POST", "/bid-documents/" + segment(projectId) + "/packages", null, null));
        String jobId = text(value, "job_id");
        if (!jobId.isBlank()) saveJob(context, jobId);
        value.put("project_id", projectId);
        return value;
    }

    private Map<String, Object> compliance(AgentContext context, Map<String, Object> params) throws Exception {
        requireContext(context);
        String projectId = ownedProject(context, required(params, "project_id"));
        List<Attachment> attachments = attachments(context, params, "attachment_ids");
        String boundary = "----KacpBiaoshu" + UUID.randomUUID().toString().replace("-", "");
        List<InputStream> streams = new ArrayList<>();
        streams.add(new ByteArrayInputStream(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"is_blind_bid\"\r\n\r\n" + Boolean.parseBoolean(text(params, "blind")) + "\r\n").getBytes(StandardCharsets.UTF_8)));
        streams.add(new ByteArrayInputStream(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"is_electronic_bid\"\r\n\r\n" + Boolean.parseBoolean(text(params, "electronic")) + "\r\n").getBytes(StandardCharsets.UTF_8)));
        for (Attachment attachment : attachments) {
            streams.add(new ByteArrayInputStream(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"bid_files\"; filename=\"" + header(attachment.attach.getOriginalName()) + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8)));
            streams.add(attachmentStream(attachment));
            streams.add(new ByteArrayInputStream("\r\n".getBytes(StandardCharsets.UTF_8)));
        }
        streams.add(new ByteArrayInputStream(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofInputStream(() -> new SequenceInputStream(Collections.enumeration(streams)));
        Map<String, Object> value = response("compliance", request("POST", "/projects/" + segment(projectId) + "/compliance-reviews", body, boundary));
        String jobId = text(value, "job_id");
        if (!jobId.isBlank()) saveJob(context, jobId);
        value.put("project_id", projectId);
        return value;
    }

    private Map<String, Object> cancel(AgentContext context, Map<String, Object> params) throws Exception {
        String jobId = ownedJob(context, required(params, "job_id"));
        Map<String, Object> value = response("cancel", request("POST", "/jobs/" + segment(jobId) + "/cancel", null, null));
        value.put("job_id", jobId);
        return value;
    }

    private Map<String, Object> report(AgentContext context, Map<String, Object> params) throws Exception {
        String jobId = ownedJob(context, required(params, "job_id"));
        String format = text(params, "format").toLowerCase(Locale.ROOT).replace("word", "docx");
        if ("html,docx".equals(format) || "docx,html".equals(format)) format = "both";
        if (format.isBlank()) format = "html";
        if (!List.of("html", "docx", "both").contains(format)) throw new ToolFailure(422, "invalid_report_format", "format must be html, docx, or both");
        Map<String, Object> result = response("report", request("GET", "/jobs/" + segment(jobId) + "/result", null, null));
        Path root = workspaceRoot(context).resolve("biaoshu-reports");
        Files.createDirectories(root);
        Path input = root.resolve(sha256(jobId) + ".json");
        Files.writeString(input, JSON.writeValueAsString(result), StandardCharsets.UTF_8);
        Path script = Path.of(".apboa", "tenants", context.getTenantCode(), "skills", "biaoshu-bailian", "scripts", "report.py").toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) throw new ToolFailure(503, "report_renderer_unavailable", "The report renderer is not installed");
        String tenderName = text(params, "tender_name");
        List<String> command = new ArrayList<>(List.of("python3", script.toString(), "--in", input.toString(), "--format", format, "-o", root.toString()));
        if (!tenderName.isBlank()) { command.add("--tender-name"); command.add(tenderName); }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) { output = reader.lines().reduce("", (a, b) -> a + b + "\n"); }
        if (process.waitFor() != 0) throw new ToolFailure(500, "report_render_failed", "The report renderer could not create the report");
        List<String> paths = output.lines().filter(line -> line.endsWith(".html") || line.endsWith(".docx")).toList();
        Long previousTenantId = TenantUtils.getCurrentTenantId();
        String previousTenantCode = TenantUtils.getCurrentTenantCode();
        List<Map<String, Object>> downloads = new ArrayList<>();
        try {
            // Tool calls run on an async worker. Restore tenant context explicitly
            // so AttachService can populate tenant_id and store the report safely.
            TenantUtils.setCurrentTenant(context.getTenantId(), context.getTenantCode());
            for (String reportPath : paths) {
                Path generated = Path.of(reportPath).toAbsolutePath().normalize();
                if (!Files.isRegularFile(generated)) continue;
                Attach uploaded = attachService.upload(reportMultipartFile(generated), generated.getFileName().toString());
                Map<String, Object> download = new LinkedHashMap<>();
                download.put("attachment_id", String.valueOf(uploaded.getId()));
                download.put("file_name", uploaded.getOriginalName());
                download.put("download_url", "/api/attach/download/" + uploaded.getId());
                downloads.add(download);
            }
        } finally {
            if (previousTenantId == null) TenantUtils.clear();
            else TenantUtils.setCurrentTenant(previousTenantId, previousTenantCode);
        }
        if (downloads.size() != paths.size()) throw new ToolFailure(500, "report_attachment_upload_failed", "The report was created but could not be published for download");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("success", true); value.put("operation", "report"); value.put("job_id", jobId); value.put("report_paths", paths); value.put("downloadable_reports", downloads);
        return value;
    }

    private static MultipartFile reportMultipartFile(Path file) {
        return new MultipartFile() {
            @Override public String getName() { return "file"; }
            @Override public String getOriginalFilename() { return file.getFileName().toString(); }
            @Override public String getContentType() { return file.getFileName().toString().endsWith(".html") ? "text/html" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; }
            @Override public boolean isEmpty() { return false; }
            @Override public long getSize() { try { return Files.size(file); } catch (IOException error) { throw new ToolFailure(500, "report_attachment_read_failed", "The generated report could not be read"); } }
            @Override public byte[] getBytes() throws IOException { return Files.readAllBytes(file); }
            @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(file); }
            @Override public void transferTo(File destination) throws IOException { Files.copy(file, destination.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        };
    }

    private HttpResponse<String> request(String method, String path, HttpRequest.BodyPublisher body, String boundary) throws Exception {
        String key = appKey();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(TIMEOUT).header("Accept", "application/json").header("X-App-Key", key);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else {
            builder.method(method, body);
            builder.header("Content-Type", "multipart/form-data; boundary=" + boundary);
            builder.header("Idempotency-Key", sha256(method + path + System.nanoTime()));
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Map<String, Object> response(String operation, HttpResponse<String> http) throws Exception {
        if (http.statusCode() < 200 || http.statusCode() >= 300) {
            throw new ToolFailure(http.statusCode(), externalCode(http.statusCode()), externalMessage(http.statusCode()));
        }
        JsonNode parsed = http.body() == null || http.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(http.body());
        JsonNode sanitized = sanitize(parsed);
        Map<String, Object> value = sanitized.isObject()
                ? JSON.convertValue(sanitized, new TypeReference<LinkedHashMap<String, Object>>() {})
                : new LinkedHashMap<>(Map.of("payload", JSON.convertValue(sanitized, Object.class)));
        value.put("success", true);
        value.put("operation", operation);
        return value;
    }

    private Attachment singleAttachment(AgentContext context, Map<String, Object> params) {
        if (attachService == null) throw new ToolFailure(500, "attachment_service_unavailable", "Attachment service unavailable");
        String explicit = text(params, "attachment_id");
        List<String> ids = explicit.isBlank() ? context.getFileIds() : List.of(explicit);
        if (ids == null || ids.size() != 1 || ids.get(0) == null || ids.get(0).isBlank()) {
            throw new ToolFailure(422, "interpretation_file_count", "Exactly one tender file is required");
        }
        try {
            Attach attach = attachService.getById(Long.valueOf(ids.get(0).trim()));
            if (attach == null) throw new ToolFailure(404, "attachment_not_found", "Tender file was not found");
            if (!Objects.equals(attach.getTenantId(), context.getTenantId())) throw new ToolFailure(403, "attachment_scope_forbidden", "Tender file is outside the current tenant");
            return new Attachment(attach);
        } catch (NumberFormatException error) {
            throw new ToolFailure(422, "invalid_attachment_id", "Invalid tender file identifier");
        }
    }

    private List<Attachment> attachments(AgentContext context, Map<String, Object> params, String key) {
        String raw = text(params, key);
        List<String> ids = raw.isBlank() ? context.getFileIds() : List.of(raw.split(","));
        if (ids == null || ids.isEmpty()) throw new ToolFailure(422, "compliance_file_count", "At least one bid document is required");
        List<Attachment> values = new ArrayList<>();
        for (String id : ids) {
            try {
                Attach attach = attachService.getById(Long.valueOf(id.trim()));
                if (attach == null || !Objects.equals(attach.getTenantId(), context.getTenantId())) throw new ToolFailure(403, "attachment_scope_forbidden", "A bid document is outside the current tenant");
                String extension = attach.getExtension() == null ? "" : attach.getExtension().trim().toLowerCase(Locale.ROOT);
                if (!List.of("doc", "docx").contains(extension)) throw new ToolFailure(422, "unsupported_bid_file_type", "Only DOC and DOCX bid documents are supported");
                values.add(new Attachment(attach));
            } catch (NumberFormatException error) { throw new ToolFailure(422, "invalid_attachment_id", "Invalid bid document identifier"); }
        }
        return values;
    }

    private InputStream attachmentStream(Attachment attachment) {
        try {
            InputStream stream = attachService.downloadAsStream(attachment.attach);
            if (stream == null) throw new IOException();
            return stream;
        } catch (Exception error) {
            throw new ToolFailure(422, "attachment_read_failed", "Tender file could not be read");
        }
    }

    private void saveJob(AgentContext context, String jobId) throws IOException {
        Path file = stateFile(context, jobId);
        Files.createDirectories(file.getParent());
        Files.writeString(file, context.getTenantId() + "|" + context.getTenantCode() + "|" + context.getThreadId(), StandardCharsets.UTF_8);
    }

    private void saveProject(AgentContext context, String projectId) throws IOException {
        Path file = projectStateFile(context, projectId);
        Files.createDirectories(file.getParent());
        Files.writeString(file, context.getTenantId() + "|" + context.getTenantCode() + "|" + context.getThreadId(), StandardCharsets.UTF_8);
    }

    private String ownedJob(AgentContext context, String jobId) throws IOException {
        requireContext(context);
        Path file = stateFile(context, jobId);
        if (!Files.isRegularFile(file) || !Files.readString(file, StandardCharsets.UTF_8)
                .equals(context.getTenantId() + "|" + context.getTenantCode() + "|" + context.getThreadId())) {
            throw new ToolFailure(403, "job_scope_forbidden", "Job is outside the current conversation");
        }
        return jobId;
    }

    private String ownedProject(AgentContext context, String projectId) throws IOException {
        requireContext(context);
        Path file = projectStateFile(context, projectId);
        if (!Files.isRegularFile(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(context.getTenantId() + "|" + context.getTenantCode() + "|" + context.getThreadId())) throw new ToolFailure(403, "project_scope_forbidden", "Project is outside the current conversation");
        return projectId;
    }

    private Path stateFile(AgentContext context, String jobId) {
        Path root = workspaceRoot(context).resolve(".biaoshu-interpreter-state");
        Path file = root.resolve(sha256(jobId) + ".state").normalize();
        if (!file.startsWith(root)) throw new ToolFailure(422, "invalid_job_id", "Invalid job identifier");
        return file;
    }

    private Path projectStateFile(AgentContext context, String projectId) { Path root = workspaceRoot(context).resolve(".biaoshu-interpreter-projects"); Path file = root.resolve(sha256(projectId) + ".state").normalize(); if (!file.startsWith(root)) throw new ToolFailure(422, "invalid_project_id", "Invalid project identifier"); return file; }
    private Path workspaceRoot(AgentContext context) { return Path.of(".apboa", "tenants", context.getTenantCode(), "workspaces", context.getThreadId()).toAbsolutePath().normalize(); }

    private static String appKey() throws IOException {
        String configured = System.getenv(KEY_FILE_ENV);
        if (configured == null || configured.isBlank()) throw new ToolFailure(503, "credentials_not_configured", "Interpretation credentials are not configured");
        String key = Files.readString(Path.of(configured.trim()), StandardCharsets.UTF_8).trim();
        if (!key.matches("bk_live_[A-Za-z0-9_-]+")) throw new ToolFailure(401, "invalid_credentials", "Interpretation credentials are invalid");
        return key;
    }

    private static JsonNode sanitize(JsonNode node) {
        if (node == null || node.isValueNode()) return node;
        if (node.isArray()) { var array = JSON.createArrayNode(); node.forEach(item -> array.add(sanitize(item))); return array; }
        ObjectNode object = JSON.createObjectNode();
        node.fields().forEachRemaining(item -> { if (!unsafe(item.getKey())) object.set(item.getKey(), sanitize(item.getValue())); });
        return object;
    }

    private static boolean unsafe(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("apikey") || normalized.contains("token") || normalized.contains("secret")
                || normalized.contains("password") || normalized.contains("authorization") || normalized.contains("cookie");
    }

    private static void requireContext(AgentContext context) {
        if (context == null || context.getTenantId() == null || context.getTenantCode() == null
                || !context.getTenantCode().matches("[A-Za-z0-9._-]+") || context.getThreadId() == null
                || !context.getThreadId().matches("[A-Za-z0-9._-]+")) {
            throw new ToolFailure(403, "tenant_context_required", "Authorized conversation context is required");
        }
    }

    private static String required(Map<String, Object> params, String key) { String value = text(params, key); if (value.isBlank()) throw new ToolFailure(422, key + "_required", key + " is required"); return value; }
    private static String text(Map<String, Object> params, String key) { Object value = params == null ? null : params.get(key); return value == null ? "" : String.valueOf(value).trim(); }
    private static String segment(String value) { if (!value.matches("[A-Za-z0-9._:-]+")) throw new ToolFailure(422, "invalid_identifier", "Invalid identifier"); return value; }
    private static String header(String value) { return value == null ? "tender" : value.replaceAll("[\\\\\"\\r\\n]", "_"); }
    private static String externalCode(int status) { return switch (status) { case 401 -> "external_unauthorized"; case 402 -> "insufficient_balance"; case 404 -> "external_not_found"; case 409 -> "external_conflict"; case 422 -> "external_validation_failed"; case 429 -> "external_rate_limited"; default -> status >= 500 ? "external_service_failed" : "external_request_failed"; }; }
    private static String externalMessage(int status) { return switch (status) { case 401 -> "The configured account was rejected"; case 402 -> "The account balance is insufficient for this operation"; case 404 -> "The requested task was not found"; case 409 -> "The task is not ready for this operation"; case 422 -> "The submitted tender file or parameters were rejected"; case 429 -> "The service is busy; try again later"; default -> "The interpretation service is temporarily unavailable"; }; }
    private static String sha256(String value) { try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte item : bytes) result.append(String.format("%02x", item)); return result.toString(); } catch (Exception error) { return Integer.toHexString(value.hashCode()); } }
    private static Map<String, Object> failure(String operation, int status, String code, String message) { Map<String, Object> error = new LinkedHashMap<>(); error.put("http_status", status); error.put("code", code); error.put("message", message); Map<String, Object> result = new LinkedHashMap<>(); result.put("success", false); result.put("operation", operation); result.put("error", error); return result; }
    private static final class Attachment { private final Attach attach; private Attachment(Attach attach) { this.attach = attach; } }
    private static final class ToolFailure extends RuntimeException { private final int status; private final String code; private ToolFailure(int status, String code, String message) { super(message); this.status = status; this.code = code; } }
}
