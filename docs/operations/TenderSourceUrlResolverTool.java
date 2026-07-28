import com.hxh.apboa.engine.tool.dynamices.IDynamicAgentTool;
import com.hxh.apboa.engine.agui.AgentContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TenderSourceUrlResolverTool implements IDynamicAgentTool {
    private static final int MAX_ITEMS = 20;
    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SOURCE_URL = Pattern.compile(
        "(?i)(?:sourceUrl|source_url)\\s*[:=]\\s*[\"']((?:\\\\.|[^\"'\\\\])*)[\"']");
    private static final Pattern HREF_URL = Pattern.compile(
        "(?i)href\\s*=\\s*[\"'](https?://[^\"'\\s<>]+)");
    private static final Pattern HREF_ATTRIBUTE = Pattern.compile(
        "(?i)\\bhref\\s*=\\s*[\"']([^\"'\\s<>]+)");
    private static final Pattern HTTP_URL = Pattern.compile(
        "https?://[^\\s\"'<>]+");
    private static final Pattern ANCHOR_TAG = Pattern.compile(
        "(?is)<a\\b([^>]*)>(.*?)</a\\s*>");
    private static final Pattern BUTTON_TAG = Pattern.compile(
        "(?is)<button\\b([^>]*)>(.*?)</button\\s*>");
    private static final Pattern TAGS = Pattern.compile("(?is)<[^>]+>");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @Override
    public Object execute(AgentContext context, Map<String, Object> params) {
        Map<String, Object> response = new LinkedHashMap<>();
        JsonNode items = MAPPER.valueToTree(params == null ? null : params.get("items"));
        if (items == null || !items.isArray() || items.isEmpty() || items.size() > MAX_ITEMS) {
            response.put("success", false);
            response.put("error", "items must be an array containing 1 to 20 records");
            response.put("total", items != null && items.isArray() ? items.size() : 0);
            response.put("resolved", 0);
            response.put("items", List.of());
            return response;
        }

        ConcurrentHashMap<String, FutureTask<FetchResult>> pageCache = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
        items.forEach(item -> tasks.add(() -> resolveItem(item, pageCache)));

        List<Map<String, Object>> results = new ArrayList<>();
        try {
            List<Future<Map<String, Object>>> futures = executor.invokeAll(tasks);
            for (Future<Map<String, Object>> future : futures) {
                results.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return batchFailure(items, "Resolver interrupted");
        } catch (Exception e) {
            return batchFailure(items, safeMessage(e, "Resolver failed"));
        } finally {
            executor.shutdownNow();
        }

        long resolved = results.stream()
            .filter(item -> "EXTRACTED".equals(item.get("status")))
            .count();
        response.put("success", true);
        response.put("total", results.size());
        response.put("resolved", resolved);
        response.put("items", results);
        return response;
    }

    private static Map<String, Object> resolveItem(
            JsonNode item,
            ConcurrentHashMap<String, FutureTask<FetchResult>> pageCache) {
        String bidId = text(item, "bid_id");
        String title = text(item, "title");
        String method = "INPUT_SOURCE_URL";
        String sourceUrl = text(item, "source_url");
        String lookupMessage = "";
        try {
            String aggregateUrl = aggregationUrl(item);
            if (!aggregateUrl.isBlank()) {
                method = "PAGE_VIEW_ORIGINAL";
                URI aggregateUri = validateAggregateUri(aggregateUrl);
                FetchResult page = cachedPage(aggregateUri.toString(), pageCache);
                if (page.statusCode >= 200 && page.statusCode < 300) {
                    sourceUrl = sourceFromPage(page.body, page.finalUri);
                    if (sourceUrl.isBlank()) {
                        lookupMessage = "Original source URL was not found in the aggregation page HTML";
                    }
                } else {
                    lookupMessage = "Aggregation page returned HTTP " + page.statusCode;
                }
                if (sourceUrl.isBlank()) {
                    return result(bidId, title, null, null, "NOT_FOUND", method,
                        lookupMessage.isBlank() ? "Original source URL was not found" : lookupMessage);
                }
            }

            URI sourceUri = validateSourceUri(sourceUrl);
            return result(bidId, title, sourceUri.toString(), sourceUri.getHost(),
                "EXTRACTED", method, "");
        } catch (IllegalArgumentException e) {
            return result(bidId, title, null, null, "INVALID_INPUT", method,
                safeMessage(e, "Invalid input"));
        } catch (Exception e) {
            return result(bidId, title, null, null, "UNREACHABLE", method,
                safeMessage(e, "Original source could not be reached"));
        }
    }

    private static String aggregationUrl(JsonNode item) {
        for (String field : List.of("source_url", "aggregate_url", "url")) {
            String candidate = text(item, field);
            try {
                URI uri = parseHttpUri(candidate);
                if (isAggregationHost(uri.getHost())) return uri.toString();
            } catch (Exception ignored) {
                // The caller receives the normal invalid-input result if no usable URL remains.
            }
        }
        return "";
    }

    private static String sourceFromPage(String html, URI pageUri) throws Exception {
        String source = sourceFromViewOriginalTag(ANCHOR_TAG, html, pageUri);
        if (!source.isBlank()) return source;
        source = sourceFromViewOriginalTag(BUTTON_TAG, html, pageUri);
        if (!source.isBlank()) return source;
        Matcher matcher = SOURCE_URL.matcher(html);
        while (matcher.find()) {
            String sourceUrl = firstExternalUrl(decodeJsonString(matcher.group(1)));
            if (!sourceUrl.isBlank()) return sourceUrl;
        }
        return "";
    }

    private static String sourceFromViewOriginalTag(Pattern tagPattern, String html, URI pageUri) {
        Matcher tag = tagPattern.matcher(html);
        while (tag.find()) {
            String label = TAGS.matcher(tag.group(2)).replaceAll(" ")
                .replaceAll("&nbsp;", " ").replaceAll("\\s+", "").trim();
            if (!label.contains("查看原文")) continue;
            String source = firstExternalUrl(tag.group(1));
            if (!source.isBlank()) return source;
            Matcher href = HREF_ATTRIBUTE.matcher(tag.group(1));
            if (href.find()) {
                try {
                    String candidate = pageUri.resolve(href.group(1)).toString();
                    if (!isAggregationHost(parseHttpUri(candidate).getHost())) return candidate;
                } catch (Exception ignored) {
                    // Continue searching for another "查看原文" control.
                }
            }
        }
        return "";
    }

    private static String firstExternalUrl(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replace("&amp;", "&");
        Matcher href = HREF_URL.matcher(normalized);
        while (href.find()) {
            String candidate = externalUrl(href.group(1));
            if (!candidate.isBlank()) return candidate;
        }
        Matcher url = HTTP_URL.matcher(normalized);
        while (url.find()) {
            String candidate = externalUrl(url.group());
            if (!candidate.isBlank()) return candidate;
        }
        return "";
    }

    private static String externalUrl(String value) {
        try {
            URI uri = parseHttpUri(value);
            String host = lower(uri.getHost());
            return ("www.zhiliaobiaoxun.com".equals(host) || "zhiliaobiaoxun.com".equals(host))
                ? "" : uri.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static FetchResult cachedPage(
            String url,
            ConcurrentHashMap<String, FutureTask<FetchResult>> cache) throws Exception {
        FutureTask<FetchResult> fresh = new FutureTask<>(() -> fetchPage(URI.create(url)));
        FutureTask<FetchResult> existing = cache.putIfAbsent(url, fresh);
        FutureTask<FetchResult> task = existing == null ? fresh : existing;
        if (existing == null) task.run();
        return task.get();
    }

    private static FetchResult fetchPage(URI uri) throws Exception {
        URI current = uri;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validatePublicTarget(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("User-Agent", "K-ACP-Tender-Source-Resolver/1.0")
                .GET()
                .build();
            HttpResponse<InputStream> response = sendWithRetry(request);
            int status = response.statusCode();
            if (isRedirect(status)) {
                closeBody(response.body());
                current = nextLocation(current, response);
                continue;
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(MAX_PAGE_BYTES + 1);
            }
            if (bytes.length > MAX_PAGE_BYTES) {
                throw new IllegalArgumentException("Aggregation page exceeds 2 MiB");
            }
            return new FetchResult(status,
                new String(bytes, StandardCharsets.UTF_8), current);
        }
        throw new IllegalArgumentException("Too many redirects");
    }

    private static HttpResponse<InputStream> sendWithRetry(HttpRequest request) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("HTTP request failed") : last;
    }

    private static URI validateAggregateUri(String url) throws Exception {
        URI uri = parseHttpUri(url);
        if (!isAggregationHost(uri.getHost())) {
            throw new IllegalArgumentException("aggregate URL must be a zhiliaobiaoxun page");
        }
        validatePublicTarget(uri);
        return uri;
    }

    private static URI validateSourceUri(String url) throws Exception {
        URI uri = parseHttpUri(url);
        if (isAggregationHost(uri.getHost())) {
            throw new IllegalArgumentException("source URL cannot be an aggregation page");
        }
        validatePublicTarget(uri);
        return uri;
    }

    private static URI parseHttpUri(String url) throws Exception {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("URL is required");
        URI uri = new URI(url.trim());
        String scheme = lower(uri.getScheme());
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("Only http and https URLs are allowed");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URL host is invalid");
        }
        return uri;
    }

    private static void validatePublicTarget(URI uri) throws Exception {
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        if (addresses.length == 0) throw new IllegalArgumentException("URL host cannot be resolved");
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("Private, local or reserved targets are blocked");
            }
        }
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 255;
            int b = bytes[1] & 255;
            int c = bytes[2] & 255;
            if (a == 0 || a == 10 || a == 127 || a >= 224) return true;
            if (a == 100 && b >= 64 && b <= 127) return true;
            if (a == 169 && b == 254) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 192 && b == 168) return true;
            if (a == 198 && (b == 18 || b == 19)) return true;
            if (a == 192 && b == 0 && (c == 0 || c == 2)) return true;
            if (a == 198 && b == 51 && c == 100) return true;
            if (a == 203 && b == 0 && c == 113) return true;
        } else if (bytes.length == 16) {
            int first = bytes[0] & 255;
            int second = bytes[1] & 255;
            if ((first & 254) == 252) return true;
            if (first == 254 && (second & 192) == 128) return true;
        }
        return false;
    }

    private static URI nextLocation(
            URI current, HttpResponse<InputStream> response) throws Exception {
        String location = response.headers().firstValue("location")
            .orElseThrow(() -> new IllegalArgumentException("Redirect location is missing"));
        URI next = current.resolve(location);
        if (current.getRawFragment() != null && next.getRawFragment() == null) {
            next = URI.create(next.toString() + "#" + current.getRawFragment());
        }
        parseHttpUri(next.toString());
        validatePublicTarget(next);
        return next;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
            || status == 307 || status == 308;
    }

    private static String decodeJsonString(String encoded) throws Exception {
        return MAPPER.readTree("\"" + encoded + "\"").asText();
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean isAggregationHost(String host) {
        String normalized = lower(host);
        return "www.zhiliaobiaoxun.com".equals(normalized)
            || "zhiliaobiaoxun.com".equals(normalized);
    }

    private static void closeBody(InputStream body) {
        if (body == null) return;
        try { body.close(); } catch (Exception ignored) {}
    }

    private static Map<String, Object> result(
            String bidId, String title, String originalUrl, String sourceDomain,
            String status, String method, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bid_id", bidId);
        result.put("title", title);
        result.put("original_url", originalUrl);
        result.put("source_domain", sourceDomain);
        result.put("status", status);
        result.put("method", method);
        result.put("message", message == null ? "" : message);
        return result;
    }

    private static Map<String, Object> batchFailure(JsonNode items, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", message);
        response.put("total", items == null ? 0 : items.size());
        response.put("resolved", 0);
        response.put("items", List.of());
        return response;
    }

    private static String safeMessage(Exception error, String fallback) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static final class FetchResult {
        private final int statusCode;
        private final String body;
        private final URI finalUri;

        private FetchResult(int statusCode, String body, URI finalUri) {
            this.statusCode = statusCode;
            this.body = body;
            this.finalUri = finalUri;
        }
    }

}
