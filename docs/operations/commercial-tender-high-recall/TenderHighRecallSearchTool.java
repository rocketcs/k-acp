import com.hxh.apboa.engine.tool.dynamices.IDynamicAgentTool;
import com.hxh.apboa.engine.agui.AgentContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

public final class TenderHighRecallSearchTool implements IDynamicAgentTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final URI SEARCH_API = URI.create(
        "https://mcp-server.zhiliaobiaoxun.com/api_v2/query_bids_advanced");
    private static final Path PROFILE_DIR = Paths.get(
        ".apboa", "secrets", "http-profiles").toAbsolutePath().normalize();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES_PER_ROUND = 1;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final ZoneOffset BEIJING = ZoneOffset.ofHours(8);
    private static final Pattern PROJECT_CODE = Pattern.compile(
        "(?i)(?:项目编号|采购编号|招标编号|project\\s*(?:no|number)?)[：:\\s]*([a-z0-9][a-z0-9\\-_/]{4,})");
    private static final Set<Integer> ALLOWED_PROCESS = Set.of(1, 2, 4, 5, 6, 7, 8, 9, 10);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @Override
    public Object execute(AgentContext context, Map<String, Object> params) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            JsonNode plan = parseObject(params == null ? null : params.get("query_plan"));
            List<String> validationErrors = validatePlan(plan);
            if (!validationErrors.isEmpty()) {
                response.put("success", false);
                response.put("errors", validationErrors);
                response.put("records", List.of());
                response.put("is_complete", false);
                return response;
            }
            JsonNode profile = loadZhiliaoProfile();
            validateProfile(profile);
            return executePlan(plan, profile);
        } catch (Exception e) {
            response.put("success", false);
            response.put("errors", List.of(safeMessage(e, "High-recall search failed")));
            response.put("records", List.of());
            response.put("is_complete", false);
            return response;
        }
    }

    private static Map<String, Object> executePlan(JsonNode plan, JsonNode profile) {
        List<RoundSpec> rounds = buildRounds(plan);
        List<Failure> failures = new ArrayList<>();
        List<RoundResult> roundResults = new ArrayList<>();
        LinkedHashMap<String, RecordAccumulator> unique = new LinkedHashMap<>();

        for (RoundSpec round : rounds) {
            RoundResult result = executeRound(round, profile, plan, failures);
            roundResults.add(result);
            merge(unique, result.items, round.number, plan);
            if (hasTerminalFailure(failures)) break;
        }

        Correction correction = decideCorrection(unique, roundResults, failures, plan);
        if (correction.execute && !hasTerminalFailure(failures)) {
            RoundResult result = executeRound(correction.round, profile, plan, failures);
            roundResults.add(result);
            merge(unique, result.items, correction.round.number, plan);
        }

        List<Map<String, Object>> records = new ArrayList<>();
        for (RecordAccumulator accumulator : unique.values()) {
            if (!passesHardFilters(accumulator.record, plan.path("hard_filters"))) continue;
            finalizeRecord(accumulator, plan);
            records.add(accumulator.record);
        }
        records.sort(recordComparator());
        List<String> stableKeys = records.stream()
            .map(record -> string(record.get("record_key"))).toList();

        boolean capped = roundResults.stream().anyMatch(result -> result.capped);
        boolean complete = failures.isEmpty() && !capped;
        String terminalFailure = terminalFailureKind(failures);
        String incompleteReason = capped ? "API_PAGE_LIMIT"
            : terminalFailure != null ? terminalFailure
            : failures.isEmpty() ? null : "PAGE_FAILURE";
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("round_reported_total", roundResults.stream().map(result -> result.metrics()).toList());
        metrics.put("unique_loaded_count", records.size());
        metrics.put("a_count", countTier(records, "A"));
        metrics.put("b_count", countTier(records, "B"));
        metrics.put("c_count", countTier(records, "C"));
        metrics.put("query_plan_version", text(plan, "query_plan_version"));
        metrics.put("query_end_boundary", OffsetDateTime.now(BEIJING)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("is_complete", complete);
        response.put("incomplete_reason", incompleteReason);
        response.put("records", records);
        String stableKeyText = String.join("\n", stableKeys);
        response.put("stable_keys_gzip_b64", gzipBase64(stableKeyText));
        response.put("stable_keys_sha256", sha256(stableKeyText));
        response.put("failures", failures.stream().map(Failure::toMap).toList());
        response.put("correction", correction.toMap());
        response.put("metrics", metrics);
        return response;
    }

    private static List<RoundSpec> buildRounds(JsonNode plan) {
        List<RoundSpec> rounds = new ArrayList<>();
        rounds.add(new RoundSpec(1, "exact", buildExactRequest(plan)));
        rounds.add(new RoundSpec(2, "expanded", buildExpandedRequest(plan, false)));
        rounds.add(new RoundSpec(3, "fulltext", buildExpandedRequest(plan, true)));
        return rounds;
    }

    private static ObjectNode buildExactRequest(JsonNode plan) {
        ObjectNode request = baseRequest(plan);
        ArrayNode keywords = request.putArray("keywords");
        ArrayNode modes = request.putArray("match_modes");
        RoleBinding role = applyRoleBinding(plan, keywords, modes);
        if (!role.bound) {
            keywords.add(text(plan, "subject"));
            modes.add("title");
            modes.add("sm");
        } else {
            addBusinessGroup(request, List.of(text(plan, "subject")), List.of("title", "sm"));
        }
        return request;
    }

    private static ObjectNode buildExpandedRequest(JsonNode plan, boolean fulltext) {
        ObjectNode request = baseRequest(plan);
        List<ConceptGroup> groups = conceptGroups(plan, true);
        ArrayNode keywords = request.putArray("keywords");
        ArrayNode modes = request.putArray("match_modes");
        RoleBinding role = applyRoleBinding(plan, keywords, modes);
        List<String> matchModes = fulltext ? List.of("fulltext") : List.of("title", "sm");
        if (!role.bound && !groups.isEmpty()) {
            groups.get(0).terms.forEach(keywords::add);
            matchModes.forEach(modes::add);
            for (int i = 1; i < groups.size(); i++) {
                addBusinessGroup(request, groups.get(i).terms, matchModes);
            }
        } else if (!role.bound) {
            keywords.add(text(plan, "subject"));
            matchModes.forEach(modes::add);
        } else {
            if (groups.isEmpty()) addBusinessGroup(request,
                List.of(text(plan, "subject")), matchModes);
            else groups.forEach(group -> addBusinessGroup(request, group.terms, matchModes));
        }
        return request;
    }

    private static ObjectNode baseRequest(JsonNode plan) {
        ObjectNode request = MAPPER.createObjectNode();
        JsonNode hard = plan.path("hard_filters");
        copyArray(hard, request, "provinces");
        copyArray(hard, request, "cities");
        copyArray(hard, request, "counties");
        copyArray(hard, request, "bid_process");
        copyText(hard, request, "begin_date");
        copyText(hard, request, "end_date");
        copyNumber(hard, request, "min_money");
        copyNumber(hard, request, "max_money");
        copyArray(plan, request, "exclude_keywords");
        request.put("sort_field", "pub_time");
        request.put("sort_order", "desc");
        request.put("page_size", PAGE_SIZE);
        return request;
    }

    private static RoleBinding applyRoleBinding(
            JsonNode plan, ArrayNode keywords, ArrayNode modes) {
        JsonNode hard = plan.path("hard_filters");
        List<String> callers = stringList(hard.path("caller_names"));
        List<String> winners = stringList(hard.path("winner_names"));
        if (!callers.isEmpty()) {
            callers.forEach(keywords::add);
            modes.add("caller");
            return new RoleBinding(true, "caller");
        }
        if (!winners.isEmpty()) {
            winners.forEach(keywords::add);
            modes.add("winner");
            return new RoleBinding(true, "winner");
        }
        return new RoleBinding(false, "");
    }

    private static void addBusinessGroup(
            ObjectNode request, List<String> terms, List<String> modes) {
        if (terms == null || terms.isEmpty()) return;
        ArrayNode groups = request.withArray("keyword_groups");
        ObjectNode group = groups.addObject();
        ArrayNode keywords = group.putArray("keywords");
        terms.stream().filter(term -> term != null && !term.isBlank()).forEach(keywords::add);
        ArrayNode matchModes = group.putArray("match_modes");
        modes.forEach(matchModes::add);
    }

    private static RoundResult executeRound(
            RoundSpec round, JsonNode profile, JsonNode plan, List<Failure> failures) {
        List<JsonNode> items = new ArrayList<>();
        long reportedTotal = 0;
        int pagesLoaded = 0;
        boolean capped = false;
        for (int page = 1; page <= MAX_PAGES_PER_ROUND; page++) {
            ObjectNode request = round.request.deepCopy();
            request.put("page", page);
            try {
                JsonNode root = postSearch(request, profile);
                JsonNode data = root.path("data");
                if (page == 1) reportedTotal = data.path("total").asLong(0);
                JsonNode pageItems = data.path("items");
                if (!pageItems.isArray() || pageItems.isEmpty()) break;
                pageItems.forEach(items::add);
                pagesLoaded = page;
                if (reportedTotal > 0 && items.size() >= reportedTotal) break;
                if (pageItems.size() < PAGE_SIZE) break;
                if (page == MAX_PAGES_PER_ROUND) capped = reportedTotal > items.size();
            } catch (HttpStatusException e) {
                failures.add(new Failure(round.name, page, "HTTP_" + e.statusCode));
                break;
            } catch (ApiResponseException e) {
                failures.add(new Failure(round.name, page, e.code));
                break;
            } catch (Exception e) {
                failures.add(new Failure(round.name, page,
                    classifyFailure(e)));
                break;
            }
        }
        return new RoundResult(round.number, round.name, reportedTotal,
            pagesLoaded, capped, items);
    }

    private static JsonNode postSearch(ObjectNode request, JsonNode profile) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(SEARCH_API)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "K-ACP-Tender-High-Recall/1.0")
            .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(request)));
        Iterator<Map.Entry<String, JsonNode>> fields = profile.path("headers").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String value = field.getValue().asText("");
            if (!field.getKey().isBlank() && !value.contains("\r") && !value.contains("\n")) {
                builder.header(field.getKey(), value);
            }
        }
        HttpResponse<InputStream> response = send(builder.build());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            closeBody(response.body());
            throw new HttpStatusException(response.statusCode());
        }
        byte[] bytes = readBounded(response.body(), MAX_RESPONSE_BYTES);
        JsonNode root = MAPPER.readTree(bytes);
        if (!root.path("success").asBoolean(false)) {
            String code = root.path("error").path("code").asText("API_REJECTED");
            throw new ApiResponseException(code.replaceAll("[^A-Z0-9_]", "_"));
        }
        return root;
    }

    private static void merge(
            LinkedHashMap<String, RecordAccumulator> unique,
            List<JsonNode> items, int round, JsonNode plan) {
        for (JsonNode item : items) {
            Map<String, Object> normalized = normalizeRecord(item, round);
            String key = string(normalized.get("record_key"));
            RecordAccumulator existing = unique.get(key);
            if (existing == null) {
                existing = new RecordAccumulator(normalized);
                unique.put(key, existing);
            } else {
                mergeFields(existing.record, normalized);
            }
            existing.rounds.add(round);
            evidenceFor(item, plan, round).forEach(existing.evidence::add);
        }
    }

    private static Map<String, Object> normalizeRecord(JsonNode item, int round) {
        String bidId = firstText(item, "bid_id", "id");
        String uniqKey = firstText(item, "uniq_key", "unique_key");
        String title = firstText(item, "title", "project_title", "name");
        String aggregate = firstText(item, "url", "bid_url", "aggregate_url");
        String caller = firstText(item, "caller_name", "purchaser", "buyer_name", "tenderee");
        String pubTime = firstText(item, "pub_time", "publish_time", "pub_date", "date");
        String process = firstText(item, "bid_process", "process");
        String recordKey = !bidId.isBlank() ? "bid:" + bidId
            : !uniqKey.isBlank() ? "uniq:" + uniqKey
            : !aggregate.isBlank() ? "url:" + normalizeAggregateKey(aggregate)
            : "fp:" + Integer.toUnsignedString((title + "|" + caller + "|" + pubTime + "|" + process).hashCode(), 36);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("record_key", recordKey);
        record.put("bid_id", emptyToNull(bidId));
        record.put("uniq_key", emptyToNull(uniqKey));
        record.put("title", title);
        record.put("caller_name", emptyToNull(caller));
        record.put("winner_names", stringOrArray(item, "winner_names", "winners"));
        record.put("sm_names", stringOrArray(item, "sm_names", "products"));
        record.put("province", emptyToNull(firstText(item, "province")));
        record.put("city", emptyToNull(firstText(item, "city")));
        record.put("county", emptyToNull(firstText(item, "county")));
        record.put("money", numberOrNull(item, "money", "amount", "budget"));
        record.put("money_wan", numberOrNull(item, "money_wan", "amount_wan", "budget_wan"));
        record.put("pub_time", emptyToNull(pubTime));
        record.put("deadline", emptyToNull(firstText(item,
            "deadline", "end_time", "bid_end_time", "registration_end_time")));
        record.put("bid_process", integerOrNull(item, "bid_process", "process"));
        record.put("bid_type", emptyToNull(firstText(item, "bid_type", "type")));
        record.put("aggregate_url", emptyToNull(aggregate));
        record.put("source_url", emptyToNull(firstText(item,
            "source_url", "original_url", "sourceUrl", "originalUrl")));
        record.put("contact", emptyToNull(firstText(item, "contact", "contact_name")));
        record.put("round_first_seen", round);
        record.put("lifecycle_key", lifecycleKey(title, caller, item));
        return record;
    }

    private static void finalizeRecord(RecordAccumulator accumulator, JsonNode plan) {
        Map<String, Object> record = accumulator.record;
        List<Integer> rounds = new ArrayList<>(accumulator.rounds);
        record.put("rounds", rounds);
        record.put("match_evidence", new ArrayList<>(accumulator.evidence));
        String tier;
        if (rounds.contains(1) && coversOriginal(plan, record)) tier = "A";
        else if ((rounds.contains(1) || rounds.contains(2)) && coversRequired(plan, record)) tier = "B";
        else tier = "C";
        String deadlineStatus = text(plan.path("hard_filters"), "deadline_status");
        if ("open".equals(deadlineStatus) && !hasOpenDeadline(record)) {
            tier = "C";
            record.put("needs_verification", true);
            accumulator.evidence.add("截止状态需核实");
            record.put("match_evidence", new ArrayList<>(accumulator.evidence));
        }
        record.put("tier", tier);
        record.put("sort_key", buildSortKey(record));
    }

    private static Correction decideCorrection(
            LinkedHashMap<String, RecordAccumulator> unique,
            List<RoundResult> results, List<Failure> failures, JsonNode plan) {
        if (!plan.path("correction_allowed").asBoolean(true)) return Correction.none();
        if (!failures.isEmpty() && failures.stream().anyMatch(Failure::retryable)) {
            Failure failure = failures.get(0);
            RoundSpec original = results.stream()
                .filter(result -> result.name.equals(failure.round))
                .findFirst().map(result -> buildRounds(plan).stream()
                    .filter(round -> round.name.equals(result.name)).findFirst().orElse(null))
                .orElse(null);
            if (original != null) return new Correction(true, original,
                "重试失败轮次", "failed_page_range");
        }
        if (unique.isEmpty()) {
            ObjectNode request = baseRequest(plan);
            request.putArray("keywords").add(text(plan, "subject"));
            request.putArray("match_modes").add("fulltext");
            return new Correction(true, new RoundSpec(4, "correction", request),
                "前三轮无结果，使用完整主题词全文纠偏", "match_modes");
        }
        return Correction.none();
    }

    private static List<String> evidenceFor(JsonNode item, JsonNode plan, int round) {
        String title = firstText(item, "title", "project_title", "name");
        String products = String.join(" ", stringList(item.path("sm_names")));
        String haystack = normalize(title + " " + products);
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        String subject = text(plan, "subject");
        if (!subject.isBlank() && haystack.contains(normalize(subject))) {
            evidence.add("标题或标的物命中完整主题词“" + subject + "”");
        }
        for (ConceptGroup group : conceptGroups(plan, true)) {
            String hit = group.terms.stream().filter(term -> haystack.contains(normalize(term)))
                .findFirst().orElse("");
            if (!hit.isBlank()) evidence.add(group.name + "命中“" + hit + "”");
        }
        if (round == 3 && evidence.isEmpty()) evidence.add("公告全文命中受控业务词");
        if (round == 4 && evidence.isEmpty()) evidence.add("纠偏轮次命中完整主题词");
        return new ArrayList<>(evidence);
    }

    private static boolean coversOriginal(JsonNode plan, Map<String, Object> record) {
        String haystack = recordText(record);
        String subject = normalize(text(plan, "subject"));
        if (!subject.isBlank() && haystack.contains(subject)) return true;
        for (JsonNode group : plan.path("concept_groups")) {
            if (!group.path("required").asBoolean(true)) continue;
            if (!containsAny(haystack, stringList(group.path("original_terms")))) return false;
        }
        return plan.path("concept_groups").isArray() && !plan.path("concept_groups").isEmpty();
    }

    private static boolean coversRequired(JsonNode plan, Map<String, Object> record) {
        String haystack = recordText(record);
        for (ConceptGroup group : conceptGroups(plan, true)) {
            if (!containsAny(haystack, group.terms)) return false;
        }
        return true;
    }

    private static String recordText(Map<String, Object> record) {
        return normalize(string(record.get("title")) + " " + string(record.get("sm_names")) + " " + string(record.get("match_evidence")));
    }

    private static boolean passesHardFilters(Map<String, Object> record, JsonNode hard) {
        if (!matchesAllowed(string(record.get("province")), stringList(hard.path("provinces")))) return false;
        if (!matchesAllowed(string(record.get("city")), stringList(hard.path("cities")))) return false;
        if (!matchesAllowed(string(record.get("county")), stringList(hard.path("counties")))) return false;
        List<Integer> processes = integerList(hard.path("bid_process"));
        Object process = record.get("bid_process");
        if (!processes.isEmpty() && process instanceof Number
                && !processes.contains(((Number) process).intValue())) return false;
        LocalDate pubDate = parseDate(string(record.get("pub_time")));
        LocalDate begin = parseDate(text(hard, "begin_date"));
        LocalDate end = parseDate(text(hard, "end_date"));
        if (pubDate != null && begin != null && pubDate.isBefore(begin)) return false;
        if (pubDate != null && end != null && pubDate.isAfter(end)) return false;
        Double money = doubleValue(record.get("money"));
        if (money != null && hard.path("min_money").isNumber()
                && money < hard.path("min_money").asDouble()) return false;
        if (money != null && hard.path("max_money").isNumber()
                && money > hard.path("max_money").asDouble()) return false;
        return true;
    }

    private static boolean hasOpenDeadline(Map<String, Object> record) {
        LocalDate deadline = parseDate(string(record.get("deadline")));
        return deadline != null && !deadline.isBefore(LocalDate.now(BEIJING));
    }

    private static Comparator<Map<String, Object>> recordComparator() {
        return Comparator.comparingInt((Map<String, Object> record) -> tierRank(string(record.get("tier"))))
            .thenComparing(record -> deadlineSort(record.get("deadline")))
            .thenComparing((Map<String, Object> record) -> string(record.get("pub_time")), Comparator.reverseOrder())
            .thenComparing(record -> string(record.get("record_key")));
    }

    private static String buildSortKey(Map<String, Object> record) {
        return tierRank(string(record.get("tier"))) + "|" + deadlineSort(record.get("deadline")) + "|" + invertDate(string(record.get("pub_time"))) + "|" + string(record.get("record_key"));
    }

    private static int tierRank(String tier) {
        return "A".equals(tier) ? 0 : "B".equals(tier) ? 1 : 2;
    }

    private static String deadlineSort(Object value) {
        LocalDate date = parseDate(string(value));
        if (date == null) return "99999999";
        if (date.isBefore(LocalDate.now(BEIJING))) return "99999998";
        return date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static String invertDate(String value) {
        LocalDate date = parseDate(value);
        return date == null ? "99999999"
            : String.format("%08d", 99999999 - Integer.parseInt(date.format(DateTimeFormatter.BASIC_ISO_DATE)));
    }

    private static String lifecycleKey(String title, String caller, JsonNode item) {
        Matcher matcher = PROJECT_CODE.matcher(title == null ? "" : title);
        if (matcher.find()) return "project-code:" + normalize(matcher.group(1));
        String cleaned = normalize(title).replaceAll(
            "采购意向|招标公告|采购公告|更正公告|变更公告|中标公告|结果公告|合同公告|候选人公示|废标公告", "");
        return "project:" + normalize(caller) + ":" + cleaned;
    }

    private static void mergeFields(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object current = target.get(entry.getKey());
            if ((current == null || string(current).isBlank()) && entry.getValue() != null) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static JsonNode loadZhiliaoProfile() throws Exception {
        Path profilePath = PROFILE_DIR.resolve("zhiliao.json").normalize();
        if (!profilePath.startsWith(PROFILE_DIR) || !Files.isRegularFile(profilePath)) {
            throw new IllegalArgumentException("zhiliao auth profile is unavailable");
        }
        JsonNode profile = MAPPER.readTree(Files.readString(profilePath, StandardCharsets.UTF_8));
        if (profile == null || !profile.isObject()) throw new IllegalArgumentException("zhiliao auth profile is invalid");
        return profile;
    }

    private static void validateProfile(JsonNode profile) {
        Set<String> hosts = new HashSet<>();
        profile.path("allowed_hosts").forEach(node -> hosts.add(lower(node.asText(""))));
        Set<String> methods = new HashSet<>();
        profile.path("allowed_methods").forEach(node -> methods.add(node.asText("").toUpperCase(Locale.ROOT)));
        if (!hostAllowed(SEARCH_API.getHost(), hosts) || !methods.contains("POST")
                || !profile.path("headers").isObject()) {
            throw new IllegalArgumentException("zhiliao auth profile does not allow tender search");
        }
    }

    private static HttpResponse<InputStream> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static boolean hasTerminalFailure(List<Failure> failures) {
        return terminalFailureKind(failures) != null;
    }

    private static String terminalFailureKind(List<Failure> failures) {
        for (Failure failure : failures) {
            if ("QUOTA_EXCEEDED".equals(failure.errorKind)
                    || "INSUFFICIENT_BALANCE".equals(failure.errorKind)
                    || "AUTHENTICATION_FAILED".equals(failure.errorKind)
                    || "RATE_LIMITED".equals(failure.errorKind)) {
                return failure.errorKind;
            }
        }
        return null;
    }

    private static byte[] readBounded(InputStream body, int maxBytes) throws Exception {
        if (body == null) return new byte[0];
        try (InputStream input = body) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("Tender API response exceeds size limit");
            return bytes;
        }
    }

    private static List<String> validatePlan(JsonNode plan) {
        List<String> errors = new ArrayList<>();
        if (plan == null || !plan.isObject()) return List.of("query_plan must be an object");
        if (!"tender-query-plan-v1".equals(text(plan, "query_plan_version"))) errors.add("unsupported query_plan_version");
        if (text(plan, "subject").isBlank()) errors.add("subject is required");
        JsonNode hard = plan.path("hard_filters");
        if (!hard.isObject()) errors.add("hard_filters must be an object");
        LocalDate begin = parseDate(text(hard, "begin_date"));
        LocalDate end = parseDate(text(hard, "end_date"));
        if (!text(hard, "begin_date").isBlank() && begin == null) errors.add("begin_date is invalid");
        if (!text(hard, "end_date").isBlank() && end == null) errors.add("end_date is invalid");
        if (begin != null && end != null && begin.isAfter(end)) errors.add("begin_date is after end_date");
        for (Integer process : integerList(hard.path("bid_process"))) {
            if (!ALLOWED_PROCESS.contains(process)) errors.add("bid_process contains unsupported value");
        }
        if (hard.path("min_money").isNumber() && hard.path("max_money").isNumber()
                && hard.path("min_money").asDouble() > hard.path("max_money").asDouble()) {
            errors.add("min_money is greater than max_money");
        }
        return errors;
    }

    private static JsonNode parseObject(Object value) throws Exception {
        if (value == null) return MAPPER.createObjectNode();
        if (value instanceof String) return MAPPER.readTree((String) value);
        return MAPPER.valueToTree(value);
    }

    private static List<ConceptGroup> conceptGroups(JsonNode plan, boolean includeExpanded) {
        List<ConceptGroup> groups = new ArrayList<>();
        JsonNode nodes = plan.path("concept_groups");
        if (!nodes.isArray()) return groups;
        for (JsonNode node : nodes) {
            if (!node.path("required").asBoolean(true)) continue;
            LinkedHashSet<String> terms = new LinkedHashSet<>(stringList(node.path("original_terms")));
            if (includeExpanded) terms.addAll(stringList(node.path("expanded_terms")));
            terms.removeIf(String::isBlank);
            if (!terms.isEmpty()) groups.add(new ConceptGroup(text(node, "name"), new ArrayList<>(terms)));
        }
        return groups;
    }

    private static boolean containsAny(String haystack, List<String> terms) {
        for (String term : terms) if (!term.isBlank() && haystack.contains(normalize(term))) return true;
        return false;
    }

    private static boolean matchesAllowed(String actual, List<String> allowed) {
        if (allowed.isEmpty() || actual.isBlank()) return true;
        String normalized = normalize(actual);
        return allowed.stream().map(TenderHighRecallSearchTool::normalize)
            .anyMatch(value -> normalized.contains(value) || value.contains(normalized));
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.isValueNode() ? value.asText("").trim() : "";
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private static Object stringOrArray(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isArray()) return stringList(value);
            if (value.isValueNode() && !value.asText("").isBlank()) return value.asText("");
        }
        return List.of();
    }

    private static Number numberOrNull(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) return value.numberValue();
            if (value.isTextual()) {
                try { return Double.parseDouble(value.asText().replaceAll("[^0-9.]", "")); }
                catch (Exception ignored) { }
            }
        }
        return null;
    }

    private static Integer integerOrNull(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.canConvertToInt()) return value.asInt();
            try { return Integer.parseInt(value.asText("")); } catch (Exception ignored) { }
        }
        return null;
    }

    private static void copyArray(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.path(field);
        if (value.isArray() && !value.isEmpty()) target.set(field, value.deepCopy());
    }

    private static void copyText(JsonNode source, ObjectNode target, String field) {
        String value = text(source, field);
        if (!value.isBlank()) target.put(field, value);
    }

    private static void copyNumber(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.path(field);
        if (value.isNumber()) target.set(field, value.deepCopy());
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) return values;
        if (node.isArray()) node.forEach(value -> {
            String text = value.asText("").trim();
            if (!text.isBlank() && !values.contains(text)) values.add(text);
        });
        else if (node.isValueNode() && !node.asText("").isBlank()) values.add(node.asText("").trim());
        return values;
    }

    private static List<Integer> integerList(JsonNode node) {
        List<Integer> values = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(value -> {
            if (value.canConvertToInt()) values.add(value.asInt());
        });
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private static String normalizeAggregateKey(String value) {
        try {
            URI uri = URI.create(value);
            return lower(uri.getHost()) + (uri.getPath() == null ? "" : uri.getPath());
        } catch (Exception ignored) {
            return normalize(value);
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String date = value.trim();
        if (date.length() >= 10) date = date.substring(0, 10);
        try { return LocalDate.parse(date); } catch (DateTimeParseException ignored) { return null; }
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return value == null ? null : Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static long countTier(List<Map<String, Object>> records, String tier) {
        return records.stream().filter(record -> tier.equals(record.get("tier"))).count();
    }

    private static String gzipBase64(String value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(value.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode stable key order", e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash stable key order", e);
        }
    }

    private static String classifyFailure(Exception e) {
        String name = e.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        if (name.contains("TIMEOUT")) return "TIMEOUT";
        if (name.contains("UNKNOWNHOST")) return "DNS";
        return "TEMPORARY";
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

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
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

    private static final class RecordAccumulator {
        private final Map<String, Object> record;
        private final LinkedHashSet<Integer> rounds = new LinkedHashSet<>();
        private final LinkedHashSet<String> evidence = new LinkedHashSet<>();

        private RecordAccumulator(Map<String, Object> record) { this.record = record; }
    }

    private static final class ConceptGroup {
        private final String name;
        private final List<String> terms;

        private ConceptGroup(String name, List<String> terms) {
            this.name = name == null || name.isBlank() ? "业务概念" : name;
            this.terms = terms;
        }
    }

    private static final class RoleBinding {
        private final boolean bound;
        private final String mode;

        private RoleBinding(boolean bound, String mode) {
            this.bound = bound;
            this.mode = mode;
        }
    }

    private static final class RoundSpec {
        private final int number;
        private final String name;
        private final ObjectNode request;

        private RoundSpec(int number, String name, ObjectNode request) {
            this.number = number;
            this.name = name;
            this.request = request;
        }
    }

    private static final class RoundResult {
        private final int number;
        private final String name;
        private final long reportedTotal;
        private final int pagesLoaded;
        private final boolean capped;
        private final List<JsonNode> items;

        private RoundResult(int number, String name, long reportedTotal,
                int pagesLoaded, boolean capped, List<JsonNode> items) {
            this.number = number;
            this.name = name;
            this.reportedTotal = reportedTotal;
            this.pagesLoaded = pagesLoaded;
            this.capped = capped;
            this.items = items;
        }

        private Map<String, Object> metrics() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("round", name);
            map.put("reported_total", reportedTotal);
            map.put("loaded", items.size());
            map.put("pages_loaded", pagesLoaded);
            map.put("capped", capped);
            return map;
        }
    }

    private static final class Failure {
        private final String round;
        private final int page;
        private final String errorKind;

        private Failure(String round, int page, String errorKind) {
            this.round = round;
            this.page = page;
            this.errorKind = errorKind;
        }

        private Map<String, Object> toMap() {
            return Map.of("round", round, "page", page, "error_kind", errorKind);
        }

        private boolean retryable() {
            return "TIMEOUT".equals(errorKind) || "DNS".equals(errorKind)
                || "TEMPORARY".equals(errorKind) || "RATE_LIMITED".equals(errorKind)
                || errorKind.matches("HTTP_5[0-9][0-9]");
        }
    }

    private static final class Correction {
        private final boolean execute;
        private final RoundSpec round;
        private final String reason;
        private final String changedField;

        private Correction(boolean execute, RoundSpec round, String reason, String changedField) {
            this.execute = execute;
            this.round = round;
            this.reason = reason;
            this.changedField = changedField;
        }

        private static Correction none() { return new Correction(false, null, "", ""); }

        private Map<String, Object> toMap() {
            return Map.of("executed", execute, "correction_reason", reason,
                "changed_field", changedField);
        }
    }

    private static final class HttpStatusException extends Exception {
        private final int statusCode;

        private HttpStatusException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }

    private static final class ApiResponseException extends Exception {
        private final String code;

        private ApiResponseException(String code) {
            super(code);
            this.code = code == null || code.isBlank() ? "API_REJECTED" : code;
        }
    }
}
