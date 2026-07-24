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
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TenderSourceUrlResolverV2Tool implements IDynamicAgentTool {
    private static final int MAX_ITEMS = 20;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PROBE_BYTES = 256 * 1024;
    private static final double TITLE_SIMILARITY_THRESHOLD = 0.72d;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private static final Set<String> SOURCE_FIELD_NAMES = Set.of(
        "sourceurl", "source_url", "originalurl", "original_url",
        "sourcelink", "source_link", "originallink", "original_link",
        "noticeurl", "notice_url", "announcementurl", "announcement_url");
    private static final Pattern STRUCTURED_SOURCE = Pattern.compile(
        "(?is)[\\\"']?(sourceUrl|source_url|originalUrl|original_url|sourceLink|source_link|originalLink|original_link|noticeUrl|notice_url|announcementUrl|announcement_url)[\\\"']?\\s*[:=]\\s*(?:\\\\)?[\\\"']((?:\\\\.|(?![\\\"']).)+?)(?:\\\\)?[\\\"']");
    private static final Pattern CONTROLLED_CONTEXT = Pattern.compile(
        "(?is)(?:原文|来源|公告地址|原公告|source|original)[^<>\\n]{0,240}?(https?(?::|\\\\u003[aA])(?:\\\\u002[fF]|/){2}[^\\s\\\"'<>]+)");
    private static final Pattern HTML_HREF = Pattern.compile(
        "(?is)href\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
    private static final Pattern HTML_TITLE = Pattern.compile(
        "(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern OG_TITLE = Pattern.compile(
        "(?is)<meta[^>]+(?:property|name)=[\\\"'](?:og:title|title)[\\\"'][^>]+content=[\\\"']([^\\\"']+)[\\\"']");
    private static final Pattern IDENTIFIER = Pattern.compile(
        "(?i)(?<![a-z0-9])[a-z0-9][a-z0-9\\-_/]{5,}(?![a-z0-9])");
    private static final Pattern ERROR_PAGE = Pattern.compile(
        "(?i)(404|not[ -]?found|page[ -]?not[ -]?found|页面不存在|访问出错|系统错误|链接失效|已删除)");
    private static final Pattern LOGIN_PAGE = Pattern.compile(
        "(?i)(login|sign[ -]?in|统一身份认证|用户登录|账号登录|请登录|身份验证)");

    @Override
    public Object execute(AgentContext context, Map<String, Object> params) {
        JsonNode items = MAPPER.valueToTree(params == null ? null : params.get("items"));
        if (items != null && items.isArray() && items.isEmpty()) {
            return batchResponse(true, 0, 0, true, "", List.of());
        }
        String inputError = validateBatch(items);
        if (!inputError.isBlank()) {
            return batchResponse(false, items != null && items.isArray() ? items.size() : 0,
                0, false, inputError, List.of());
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(5, items.size()));
        List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
        items.forEach(item -> tasks.add(() -> resolveItem(item)));
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            List<Future<Map<String, Object>>> futures = executor.invokeAll(tasks);
            for (Future<Map<String, Object>> future : futures) {
                results.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return batchResponse(false, items.size(), 0, false,
                "Resolver interrupted", List.of());
        } catch (Exception e) {
            return batchResponse(false, items.size(), 0, false,
                safeMessage(e, "Resolver failed"), List.of());
        } finally {
            executor.shutdownNow();
        }

        Set<String> inputKeys = new LinkedHashSet<>();
        items.forEach(item -> inputKeys.add(text(item, "record_key")));
        Set<String> outputKeys = new LinkedHashSet<>();
        results.forEach(item -> outputKeys.add(stringValue(item.get("record_key"))));
        boolean mappingComplete = results.size() == items.size()
            && inputKeys.equals(outputKeys) && outputKeys.size() == results.size();
        long resolved = results.stream()
            .filter(TenderSourceUrlResolverV2Tool::hasDisplayableSource)
            .count();
        boolean linksComplete = mappingComplete && resolved == results.size();
        String error = !mappingComplete ? "record_key mapping mismatch"
            : linksComplete ? "" : "some source URLs could not be verified or extracted";
        return batchResponse(mappingComplete, results.size(), resolved,
            linksComplete, error, results);
    }

    private static String validateBatch(JsonNode items) {
        if (items == null || !items.isArray() || items.isEmpty() || items.size() > MAX_ITEMS) {
            return "items must be an array containing 1 to 20 records";
        }
        Set<String> keys = new HashSet<>();
        for (JsonNode item : items) {
            String key = text(item, "record_key");
            if (key.isBlank()) return "every item must contain a nonblank record_key";
            if (!keys.add(key)) return "record_key must be unique within the batch";
            if (text(item, "title").isBlank()) return "every item must contain title";
        }
        return "";
    }

    private static Map<String, Object> resolveItem(JsonNode item) {
        String recordKey = text(item, "record_key");
        String bidId = text(item, "bid_id");
        String uniqKey = text(item, "uniq_key");
        String title = text(item, "title");
        String aggregateRaw = firstNonblank(text(item, "aggregate_url"), text(item, "url"));
        String aggregateUrl = validatedAggregate(aggregateRaw);

        List<Candidate> candidates = new ArrayList<>();
        collectControlledFields(item, "", "INPUT_EXPLICIT", true, candidates);
        candidates.removeIf(candidate -> isAggregateHost(candidate.url));

        String aggregateFailure = "";
        if (candidates.isEmpty() && !aggregateUrl.isBlank()) {
            try {
                FetchResult page = fetchPage(URI.create(aggregateUrl), MAX_PAGE_BYTES);
                if (page.getStatusCode() >= 200 && page.getStatusCode() < 300) {
                    candidates.addAll(extractFromAggregatePage(page.getBody(), page.getFinalUri()));
                } else {
                    aggregateFailure = statusForHttp(page.getStatusCode());
                }
            } catch (Exception e) {
                aggregateFailure = "TEMP_UNREACHABLE";
            }
        }

        LinkedHashMap<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            try {
                URI uri = validateSourceUri(candidate.url);
                unique.putIfAbsent(normalizeUrl(uri), new Candidate(
                    normalizeUrl(uri), candidate.method, candidate.explicit));
            } catch (Exception ignored) {
                // Invalid candidates are ignored; if all are invalid the item becomes INVALID_INPUT.
            }
        }

        if (unique.isEmpty()) {
            String status = !candidates.isEmpty() ? "INVALID_INPUT"
                : firstNonblank(aggregateFailure, "NOT_FOUND");
            return itemResult(recordKey, bidId, title, null, aggregateUrl,
                status, "NONE", "", "NO_VALID_CANDIDATE",
                reasonForStatus(status));
        }

        // sourceUrl is the structured original URL provided by the aggregation page.
        // Do not make its visibility depend on probing a third-party site: those sites
        // commonly require JavaScript, cookies, or authentication and a failed probe must
        // not replace a valid extracted URL with an unrelated page link.
        Candidate selected = unique.values().iterator().next();
        URI sourceUri;
        try {
            sourceUri = validateSourceUri(selected.url);
        } catch (Exception e) {
            return itemResult(recordKey, bidId, title, null, aggregateUrl,
                "INVALID_INPUT", "NONE", "", selected.method,
                safeMessage(e, reasonForStatus("INVALID_INPUT")));
        }
        String original = normalizeUrl(sourceUri);
        return itemResult(recordKey, bidId, title, original, aggregateUrl,
            "EXTRACTED", "SOURCE", lower(sourceUri.getHost()), selected.method,
            "Original URL extracted from a structured source field", original);
    }

    private static CandidateOutcome evaluateCandidate(
            Candidate candidate, String expectedTitle, String bidId, String uniqKey) {
        try {
            URI uri = validateSourceUri(candidate.url);
            boolean structuredAggregateSource = isStructuredAggregateSource(candidate);
            if (usesClientSideRoute(uri)) {
                return new CandidateOutcome("EXTRACTED_CLIENT_ROUTE", normalizeUrl(uri),
                    lower(uri.getHost()), candidate.method,
                    "Source URL was extracted from a controlled field but uses a client-side route");
            }
            ProbeResult probe = probe(uri);
            String status = statusForHttp(probe.getStatusCode());
            if (!"REACHABLE".equals(status)) {
                if (structuredAggregateSource && !"SOURCE_DELETED".equals(status)) {
                    return extractedUnverified(candidate, uri,
                        "Structured source URL could not be fully verified: " + status);
                }
                return new CandidateOutcome(status, probe.getFinalUri().toString(),
                    lower(probe.getFinalUri().getHost()), candidate.method,
                    reasonForStatus(status));
            }
            String body = probe.getBodyPrefix();
            String pageTitle = extractPageTitle(body);
            String combined = pageTitle + " " + stripMarkup(body);
            if (looksLikeLogin(probe.getFinalUri(), pageTitle, body)) {
                if (structuredAggregateSource) {
                    return extractedUnverified(candidate, uri,
                        "Structured source URL requires authentication to validate");
                }
                return new CandidateOutcome("AUTH_REQUIRED", probe.getFinalUri().toString(),
                    lower(probe.getFinalUri().getHost()), candidate.method,
                    reasonForStatus("AUTH_REQUIRED"));
            }
            if (looksLikeError(pageTitle, body)) {
                if (structuredAggregateSource) {
                    return extractedUnverified(candidate, uri,
                        "Structured source URL returned an error-like page during validation");
                }
                return new CandidateOutcome("REACHABLE_UNCONFIRMED", probe.getFinalUri().toString(),
                    lower(probe.getFinalUri().getHost()), candidate.method,
                    "Reachable page looks like an error page");
            }
            boolean identityMatch = exactIdentifierMatch(expectedTitle, bidId, uniqKey,
                    probe.getFinalUri().toString() + " " + combined)
                || titleSimilarity(expectedTitle, pageTitle) >= TITLE_SIMILARITY_THRESHOLD;
            boolean obviousHomepage = isHomepage(probe.getFinalUri())
                && titleSimilarity(expectedTitle, pageTitle) < TITLE_SIMILARITY_THRESHOLD;
            if (candidate.explicit && !obviousHomepage) identityMatch = true;
            if (!identityMatch) {
                if (structuredAggregateSource) {
                    return extractedUnverified(candidate, uri,
                        "Structured source URL is reachable but its tender identity could not be confirmed");
                }
                return new CandidateOutcome("REACHABLE_UNCONFIRMED", probe.getFinalUri().toString(),
                    lower(probe.getFinalUri().getHost()), candidate.method,
                    "Reachable source identity did not match the tender record");
            }
            return new CandidateOutcome("VERIFIED", probe.getFinalUri().toString(),
                lower(probe.getFinalUri().getHost()), candidate.method, "");
        } catch (IllegalArgumentException e) {
            return new CandidateOutcome("INVALID_INPUT", null, "", candidate.method,
                safeMessage(e, reasonForStatus("INVALID_INPUT")));
        } catch (Exception e) {
            if (isStructuredAggregateSource(candidate)) {
                return new CandidateOutcome("EXTRACTED_SOURCE_UNVERIFIED", candidate.url,
                    "", candidate.method,
                    "Structured source URL could not be reached during validation: " + safeMessage(e, "temporary error"));
            }
            return new CandidateOutcome("TEMP_UNREACHABLE", null, "", candidate.method,
                safeMessage(e, reasonForStatus("TEMP_UNREACHABLE")));
        }
    }

    private static CandidateOutcome extractedUnverified(Candidate candidate, URI sourceUri,
            String reason) {
        return new CandidateOutcome("EXTRACTED_SOURCE_UNVERIFIED", candidate.url,
            lower(sourceUri.getHost()), candidate.method, reason);
    }

    private static boolean isStructuredAggregateSource(Candidate candidate) {
        return candidate != null && candidate.method != null
            && candidate.method.startsWith("AGGREGATE_STRUCTURED:");
    }

    private static void collectControlledFields(
            JsonNode node, String parentName, String method, boolean explicit,
            List<Candidate> output) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized = normalizeFieldName(field.getKey());
                JsonNode value = field.getValue();
                if (SOURCE_FIELD_NAMES.contains(normalized) && value.isValueNode()) {
                    for (String url : urlsFromValue(value.asText(""), null)) {
                        output.add(new Candidate(url, method + ":" + field.getKey(), explicit));
                    }
                }
                collectControlledFields(value, normalized, method, explicit, output);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectControlledFields(child, parentName, method, explicit, output));
        }
    }

    private static List<Candidate> extractFromAggregatePage(String html, URI baseUri) {
        if (html == null || html.isBlank()) return List.of();
        List<Candidate> results = new ArrayList<>();
        Matcher structured = STRUCTURED_SOURCE.matcher(html);
        while (structured.find()) {
            String field = structured.group(1);
            for (String url : urlsFromValue(structured.group(2), baseUri)) {
                results.add(new Candidate(url, "AGGREGATE_STRUCTURED:" + field, false));
            }
        }
        return results;
    }

    private static List<String> urlsFromValue(String raw, URI baseUri) {
        if (raw == null || raw.isBlank()) return List.of();
        String decoded = decodeEscaped(raw);
        decoded = htmlDecode(decoded).trim();
        List<String> results = new ArrayList<>();
        try {
            URI uri = baseUri == null ? new URI(decoded) : baseUri.resolve(decoded);
            if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                results.add(uri.toString());
            }
        } catch (Exception ignored) {
            // A controlled value can contain markup; extract controlled hrefs below.
        }
        Matcher href = HTML_HREF.matcher(decoded);
        while (href.find()) {
            try {
                URI uri = baseUri == null ? new URI(href.group(1)) : baseUri.resolve(href.group(1));
                if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                    results.add(uri.toString());
                }
            } catch (Exception ignored) {
                // Ignore malformed controlled href.
            }
        }
        return results;
    }

    private static String decodeEscaped(String value) {
        String decoded = value;
        for (int i = 0; i < 3; i++) {
            String next = decoded
                .replaceAll("(?i)\\\\u002f", "/")
                .replaceAll("(?i)\\\\u003a", ":")
                .replaceAll("(?i)\\\\u0026", "&")
                .replace("\\/", "/")
                .replace("\\\\", "\\");
            if (next.equals(decoded)) break;
            decoded = next;
        }
        return decoded;
    }

    private static String htmlDecode(String value) {
        return value.replace("&amp;", "&").replace("&#38;", "&")
            .replace("&quot;", "\"").replace("&#34;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    private static ProbeResult probe(URI uri) throws Exception {
        ProbeResult head = probeMethod(uri, "HEAD", 0);
        if (head.getStatusCode() >= 200 && head.getStatusCode() < 400) {
            if (isDocumentContent(head.getContentType())) return head;
            return probeMethod(head.getFinalUri(), "GET", MAX_PROBE_BYTES);
        }
        if (needsGetFallback(head.getStatusCode())) {
            return probeMethod(head.getFinalUri(), "GET", MAX_PROBE_BYTES);
        }
        return head;
    }

    private static ProbeResult probeMethod(URI uri, String method, int maxBytes) throws Exception {
        URI current = uri;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validatePublicTarget(current);
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/html,application/xhtml+xml,application/pdf,*/*;q=0.8")
                .header("User-Agent", "K-ACP-Tender-Source-Resolver/2.0");
            if ("HEAD".equals(method)) {
                builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Range", "bytes=0-262143").GET();
            }
            HttpResponse<InputStream> response = send(builder.build());
            int status = response.statusCode();
            if (isRedirect(status)) {
                closeBody(response.body());
                current = nextLocation(current, response);
                continue;
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            String body = "";
            if (maxBytes > 0) {
                byte[] bytes = readBounded(response.body(), maxBytes);
                body = new String(bytes, StandardCharsets.UTF_8);
            } else {
                closeBody(response.body());
            }
            return new ProbeResult(status, current, contentType, body);
        }
        throw new IllegalArgumentException("Too many redirects");
    }

    private static FetchResult fetchPage(URI uri, int maxBytes) throws Exception {
        URI current = uri;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validatePublicTarget(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("User-Agent", "K-ACP-Tender-Source-Resolver/2.0")
                .GET().build();
            HttpResponse<InputStream> response = send(request);
            if (isRedirect(response.statusCode())) {
                closeBody(response.body());
                current = nextLocation(current, response);
                continue;
            }
            byte[] bytes = readBounded(response.body(), maxBytes);
            return new FetchResult(response.statusCode(),
                new String(bytes, StandardCharsets.UTF_8), current);
        }
        throw new IllegalArgumentException("Too many redirects");
    }

    private static HttpResponse<InputStream> send(HttpRequest request) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("HTTP request failed") : last;
    }

    private static byte[] readBounded(InputStream body, int maxBytes) throws Exception {
        if (body == null) return new byte[0];
        try (InputStream input = body) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                byte[] limited = new byte[maxBytes];
                System.arraycopy(bytes, 0, limited, 0, maxBytes);
                return limited;
            }
            return bytes;
        }
    }

    private static URI validateAggregateUri(String value) throws Exception {
        URI uri = parseHttpUri(value);
        String host = lower(uri.getHost());
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!("zhiliaobiaoxun.com".equals(host) || "www.zhiliaobiaoxun.com".equals(host))
                || !path.startsWith("/content/")) {
            throw new IllegalArgumentException("aggregate_url must be a Zhiliao content page");
        }
        validatePublicTarget(uri);
        return uri;
    }

    private static String validatedAggregate(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return normalizeUrl(validateAggregateUri(value));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static URI validateSourceUri(String value) throws Exception {
        URI uri = parseHttpUri(value);
        if (isAggregateHost(uri.toString())) {
            throw new IllegalArgumentException("source URL cannot use the aggregation domain");
        }
        validatePublicTarget(uri);
        return uri;
    }

    private static URI parseHttpUri(String value) throws Exception {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("URL is required");
        URI uri = new URI(value.trim());
        String scheme = lower(uri.getScheme());
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("Only http and https URLs are allowed");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URL host is invalid");
        }
        if (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443) {
            throw new IllegalArgumentException("Non-standard URL ports are blocked");
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
            if (first == 32 && second == 1 && (bytes[2] & 255) == 13
                    && (bytes[3] & 255) == 184) return true;
        }
        return false;
    }

    private static URI nextLocation(URI current, HttpResponse<InputStream> response)
            throws Exception {
        String location = response.headers().firstValue("location")
            .orElseThrow(() -> new IllegalArgumentException("Redirect location is missing"));
        URI next = current.resolve(location);
        parseHttpUri(next.toString());
        validatePublicTarget(next);
        return next;
    }

    private static boolean exactIdentifierMatch(
            String expectedTitle, String bidId, String uniqKey, String actualText) {
        String haystack = normalizeIdentity(actualText);
        if (!bidId.isBlank() && haystack.contains(normalizeIdentity(bidId))) return true;
        if (!uniqKey.isBlank() && haystack.contains(normalizeIdentity(uniqKey))) return true;
        Matcher matcher = IDENTIFIER.matcher(expectedTitle == null ? "" : expectedTitle);
        while (matcher.find()) {
            String identifier = normalizeIdentity(matcher.group());
            if (identifier.length() >= 6 && haystack.contains(identifier)) return true;
        }
        return false;
    }

    private static double titleSimilarity(String expected, String actual) {
        String left = normalizeTitle(expected);
        String right = normalizeTitle(actual);
        if (left.isBlank() || right.isBlank()) return 0d;
        if (left.equals(right) || left.contains(right) || right.contains(left)) return 1d;
        Set<String> a = ngrams(left);
        Set<String> b = ngrams(right);
        if (a.isEmpty() || b.isEmpty()) return 0d;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return (2d * intersection.size()) / (a.size() + b.size());
    }

    private static Set<String> ngrams(String value) {
        if (value.length() == 1) return Set.of(value);
        Set<String> grams = new HashSet<>();
        for (int i = 0; i < value.length() - 1; i++) grams.add(value.substring(i, i + 2));
        return grams;
    }

    private static String extractPageTitle(String html) {
        if (html == null || html.isBlank()) return "";
        Matcher og = OG_TITLE.matcher(html);
        if (og.find()) return htmlDecode(stripMarkup(og.group(1))).trim();
        Matcher title = HTML_TITLE.matcher(html);
        return title.find() ? htmlDecode(stripMarkup(title.group(1))).trim() : "";
    }

    private static boolean looksLikeLogin(URI uri, String title, String body) {
        String sample = uri.toString() + " " + title + " " + body.substring(0, Math.min(body.length(), 8192));
        return LOGIN_PAGE.matcher(sample).find();
    }

    private static boolean looksLikeError(String title, String body) {
        String sample = title + " " + body.substring(0, Math.min(body.length(), 4096));
        return ERROR_PAGE.matcher(sample).find();
    }

    private static boolean isHomepage(URI uri) {
        String path = uri.getPath();
        return path == null || path.isBlank() || "/".equals(path)
            || "/index.html".equalsIgnoreCase(path) || "/index.htm".equalsIgnoreCase(path);
    }

    private static boolean isAggregateHost(String value) {
        try {
            String host = lower(new URI(value).getHost());
            return "zhiliaobiaoxun.com".equals(host) || host.endsWith(".zhiliaobiaoxun.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalizeUrl(URI uri) {
        try {
            return new URI(lower(uri.getScheme()), null, lower(uri.getHost()), uri.getPort(),
                uri.getPath(), uri.getQuery(), uri.getFragment()).normalize().toString();
        } catch (Exception ignored) {
            return uri.toString();
        }
    }

    private static boolean usesClientSideRoute(URI uri) {
        return uri != null && uri.getRawFragment() != null && !uri.getRawFragment().isBlank();
    }

    private static boolean hasDisplayableSource(Map<String, Object> item) {
        String type = stringValue(item.get("link_type"));
        return "SOURCE".equals(type) || "SOURCE_UNVERIFIED".equals(type);
    }

    private static boolean isDisplayableOriginalStatus(String status) {
        return "VERIFIED".equals(status)
            || "EXTRACTED_CLIENT_ROUTE".equals(status)
            || "EXTRACTED_SOURCE_UNVERIFIED".equals(status);
    }

    private static String normalizeTitle(String value) {
        if (value == null) return "";
        return htmlDecode(value).toLowerCase(Locale.ROOT)
            .replaceAll("(?i)(招标公告|采购公告|中标公告|结果公告|竞争性磋商|公开招标)", "")
            .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private static String normalizeIdentity(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String normalizeFieldName(String value) {
        if (value == null) return "";
        String compact = value.trim().replace("-", "_");
        if (compact.contains("_")) return compact.toLowerCase(Locale.ROOT);
        return compact.toLowerCase(Locale.ROOT);
    }

    private static String stripMarkup(String value) {
        return value == null ? "" : value.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ");
    }

    private static boolean isDocumentContent(String contentType) {
        String value = lower(contentType);
        return value.contains("application/pdf") || value.contains("application/octet-stream")
            || value.contains("application/msword") || value.contains("officedocument");
    }

    private static boolean needsGetFallback(int status) {
        return status == 400 || status == 401 || status == 403 || status == 405
            || status == 406 || status == 429 || status >= 500;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
            || status == 307 || status == 308;
    }

    private static String statusForHttp(int status) {
        if (status >= 200 && status < 400) return "REACHABLE";
        if (status == 401 || status == 403) return "AUTH_REQUIRED";
        if (status == 404 || status == 410) return "SOURCE_DELETED";
        if (status == 429) return "RATE_LIMITED";
        if (status >= 500 || status == 0) return "TEMP_UNREACHABLE";
        return "REACHABLE_UNCONFIRMED";
    }

    private static int statusPriority(String status) {
        if ("VERIFIED".equals(status)) return 0;
        if ("REACHABLE_UNCONFIRMED".equals(status)) return 1;
        if ("AUTH_REQUIRED".equals(status)) return 2;
        if ("RATE_LIMITED".equals(status)) return 3;
        if ("SOURCE_DELETED".equals(status)) return 4;
        if ("TEMP_UNREACHABLE".equals(status)) return 5;
        if ("INVALID_INPUT".equals(status)) return 6;
        return 7;
    }

    private static String reasonForStatus(String status) {
        if ("VERIFIED".equals(status)) return "";
        if ("REACHABLE_UNCONFIRMED".equals(status)) return "Source is reachable but identity could not be confirmed";
        if ("AUTH_REQUIRED".equals(status)) return "Source requires authentication";
        if ("RATE_LIMITED".equals(status)) return "Source rate limited validation";
        if ("SOURCE_DELETED".equals(status)) return "Source returned 404 or 410";
        if ("TEMP_UNREACHABLE".equals(status)) return "Source is temporarily unreachable";
        if ("INVALID_INPUT".equals(status)) return "No safe public source URL was available";
        if ("NOT_FOUND".equals(status)) return "Original source URL was not found";
        return "Source validation was inconclusive";
    }

    private static Map<String, Object> itemResult(
            String recordKey, String bidId, String title, String originalUrl,
            String aggregateUrl, String sourceStatus, String linkType,
            String sourceDomain, String resolveMethod, String reason) {
        String display = originalUrl;
        return itemResult(recordKey, bidId, title, originalUrl, aggregateUrl,
            sourceStatus, linkType, sourceDomain, resolveMethod, reason, display);
    }

    private static Map<String, Object> itemResult(
            String recordKey, String bidId, String title, String originalUrl,
            String aggregateUrl, String sourceStatus, String linkType,
            String sourceDomain, String resolveMethod, String reason, String displayUrl) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record_key", recordKey);
        result.put("bid_id", bidId);
        result.put("title", title);
        result.put("original_url", originalUrl);
        result.put("aggregate_url", emptyToNull(aggregateUrl));
        result.put("display_url", displayUrl);
        result.put("link_type", displayUrl == null ? "NONE" : linkType);
        result.put("source_status", sourceStatus);
        result.put("source_domain", emptyToNull(sourceDomain));
        result.put("resolve_method", resolveMethod);
        result.put("status_reason", reason == null ? "" : reason);
        return result;
    }

    private static Map<String, Object> batchResponse(
            boolean success, long total, long resolved, boolean mappingComplete,
            String error, List<Map<String, Object>> items) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", success);
        response.put("total", total);
        response.put("resolved", resolved);
        response.put("link_resolution_complete", mappingComplete);
        response.put("error", error == null || error.isBlank() ? null : error);
        response.put("items", items);
        return response;
    }

    private static boolean hostAllowed(String host, Set<String> allowedHosts) {
        String normalized = lower(host);
        for (String allowed : allowedHosts) {
            if (normalized.equals(allowed)) return true;
            if (allowed.startsWith("*.") && normalized.endsWith(allowed.substring(1))
                    && normalized.length() > allowed.length() - 1) return true;
        }
        return false;
    }

    private static boolean containsHeaderBreak(String value) {
        return value != null && (value.contains("\r") || value.contains("\n"));
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String firstNonblank(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String safeMessage(Exception error, String fallback) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) return fallback;
        return message.replaceAll("(?i)(x-api-key|authorization|token)\\s*[:=]\\s*[^,;\\s]+", "[redacted]");
    }

    private static void closeBody(InputStream body) {
        if (body == null) return;
        try { body.close(); } catch (Exception ignored) { }
    }

    private static final class Candidate {
        private final String url;
        private final String method;
        private final boolean explicit;

        private Candidate(String url, String method, boolean explicit) {
            this.url = url;
            this.method = method;
            this.explicit = explicit;
        }
    }

    private static final class CandidateOutcome {
        private final String status;
        private final String finalUrl;
        private final String domain;
        private final String method;
        private final String reason;

        private CandidateOutcome(String status, String finalUrl, String domain,
                String method, String reason) {
            this.status = status;
            this.finalUrl = finalUrl;
            this.domain = domain;
            this.method = method;
            this.reason = reason;
        }
    }

    private static final class ProbeResult {
        private final int statusCode;
        private final URI finalUri;
        private final String contentType;
        private final String bodyPrefix;

        private ProbeResult(int statusCode, URI finalUri,
                String contentType, String bodyPrefix) {
            this.statusCode = statusCode;
            this.finalUri = finalUri;
            this.contentType = contentType == null ? "" : contentType;
            this.bodyPrefix = bodyPrefix == null ? "" : bodyPrefix;
        }

        private int getStatusCode() { return statusCode; }
        private URI getFinalUri() { return finalUri; }
        private String getContentType() { return contentType; }
        private String getBodyPrefix() { return bodyPrefix; }
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

        private int getStatusCode() { return statusCode; }
        private String getBody() { return body; }
        private URI getFinalUri() { return finalUri; }
    }

    private static final class HttpStatusException extends Exception {
        private final int statusCode;

        private HttpStatusException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }
}
