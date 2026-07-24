import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ResolverFixtureTest {
    public static void main(String[] args) throws Exception {
        testUnicodeSourceUrlExtraction();
        testEscapedQuoteSourceUrlExtraction();
        testOnlyStructuredSourcesAreAccepted();
        testAggregationSubdomainsAreRejected();
        testStructuredSourceRetentionStatus();
        testClientRoutePreserved();
        testClientRouteIsReturnedAsSourceLink();
        testStatusTaxonomy();
        testTwoHundredKeyMappings();
        testInvalidBatchKeys();
        testEmptyBatch();
        System.out.println("PASS resolver-v2 200-case regression");
    }

    private static void testUnicodeSourceUrlExtraction() throws Exception {
        Method method = TenderSourceUrlResolverV2Tool.class.getDeclaredMethod(
            "extractFromAggregatePage", String.class, URI.class);
        method.setAccessible(true);
        String html = "sourceUrl:\"https:\\u002F\\u002Fecsg.com.cn\\u002Fcms\\u002FNoticeDetail.html?id=123\"";
        List<?> candidates = (List<?>) method.invoke(null, html,
            URI.create("https://www.zhiliaobiaoxun.com/content/123/b1"));
        if (candidates.isEmpty()) throw new AssertionError("Unicode sourceUrl was not extracted");
        Object candidate = candidates.get(0);
        var field = candidate.getClass().getDeclaredField("url");
        field.setAccessible(true);
        String actual = String.valueOf(field.get(candidate));
        if (!"https://ecsg.com.cn/cms/NoticeDetail.html?id=123".equals(actual)) {
            throw new AssertionError("Unexpected decoded URL: " + actual);
        }
    }

    private static void testEscapedQuoteSourceUrlExtraction() throws Exception {
        Method method = TenderSourceUrlResolverV2Tool.class.getDeclaredMethod(
            "extractFromAggregatePage", String.class, URI.class);
        method.setAccessible(true);
        String html = "sourceUrl:\\\"http:\\u002F\\u002Fwww.chinaunicombidding.cn"
            + "\\u002FbidInformation\\u002Fdetail?id=2078084324913573888\\\"";
        List<?> candidates = (List<?>) method.invoke(null, html,
            URI.create("https://www.zhiliaobiaoxun.com/content/599147824/b1"));
        if (candidates.isEmpty()) throw new AssertionError("Escaped sourceUrl was not extracted");
        Object candidate = candidates.get(0);
        var field = candidate.getClass().getDeclaredField("url");
        field.setAccessible(true);
        String actual = String.valueOf(field.get(candidate));
        String expected = "http://www.chinaunicombidding.cn/bidInformation/detail?id=2078084324913573888";
        if (!expected.equals(actual)) {
            throw new AssertionError("Unexpected escaped URL: " + actual);
        }
    }

    private static void testOnlyStructuredSourcesAreAccepted() throws Exception {
        Method method = TenderSourceUrlResolverV2Tool.class.getDeclaredMethod(
            "extractFromAggregatePage", String.class, URI.class);
        method.setAccessible(true);
        String html = "sourceUrl:\"https:\\u002F\\u002Fnotice.example.gov.cn\\u002F1\" "
            + "来源 https://bj.zhiliaobiaoxun.com";
        List<?> candidates = (List<?>) method.invoke(null, html,
            URI.create("https://www.zhiliaobiaoxun.com/content/1/b1"));
        if (candidates.size() != 1) {
            throw new AssertionError("Only structured source fields may become candidates: " + candidates.size());
        }
    }

    private static void testAggregationSubdomainsAreRejected() throws Exception {
        Method method = TenderSourceUrlResolverV2Tool.class.getDeclaredMethod("isAggregateHost", String.class);
        method.setAccessible(true);
        if (!Boolean.TRUE.equals(method.invoke(null, "https://bj.zhiliaobiaoxun.com"))) {
            throw new AssertionError("Aggregation subdomain was accepted as a source URL");
        }
    }

    private static void testStructuredSourceRetentionStatus() throws Exception {
        Method method = TenderSourceUrlResolverV2Tool.class.getDeclaredMethod(
            "isDisplayableOriginalStatus", String.class);
        method.setAccessible(true);
        for (String status : List.of("VERIFIED", "EXTRACTED_CLIENT_ROUTE",
                "EXTRACTED_SOURCE_UNVERIFIED")) {
            if (!Boolean.TRUE.equals(method.invoke(null, status))) {
                throw new AssertionError("Original URL status was not retained: " + status);
            }
        }
        if (Boolean.TRUE.equals(method.invoke(null, "SOURCE_DELETED"))) {
            throw new AssertionError("Deleted source was incorrectly retained");
        }
    }

    private static void testStatusTaxonomy() throws Exception {
        Method method = TenderSourceUrlResolverV2Tool.class.getDeclaredMethod("statusForHttp", int.class);
        method.setAccessible(true);
        Map<Integer, String> expected = Map.of(
            403, "AUTH_REQUIRED", 404, "SOURCE_DELETED", 410, "SOURCE_DELETED",
            429, "RATE_LIMITED", 503, "TEMP_UNREACHABLE");
        for (Map.Entry<Integer, String> entry : expected.entrySet()) {
            String actual = String.valueOf(method.invoke(null, entry.getKey()));
            if (!entry.getValue().equals(actual)) {
                throw new AssertionError(entry.getKey() + " mapped to " + actual);
            }
        }
    }

    private static void testClientRoutePreserved() throws Exception {
        Method normalize = TenderSourceUrlResolverV2Tool.class.getDeclaredMethod("normalizeUrl", URI.class);
        normalize.setAccessible(true);
        URI original = URI.create("https://ygp.gdzwfw.gov.cn/#/44/new/jygg/v3/R?noticeId=123&siteCode=441900");
        String normalized = String.valueOf(normalize.invoke(null, original));
        if (!normalized.equals(original.toString())) {
            throw new AssertionError("Client-side source route was changed: " + normalized);
        }
        Method clientRoute = TenderSourceUrlResolverV2Tool.class.getDeclaredMethod("usesClientSideRoute", URI.class);
        clientRoute.setAccessible(true);
        if (!Boolean.TRUE.equals(clientRoute.invoke(null, original))) {
            throw new AssertionError("Client-side source route was not detected");
        }
    }

    private static void testClientRouteIsReturnedAsSourceLink() throws Exception {
        String source = "https://ygp.gdzwfw.gov.cn/#/44/new/jygg/v3/R?noticeId=123&siteCode=441900";
        Class<?> candidateClass = Class.forName("TenderSourceUrlResolverV2Tool$Candidate");
        var constructor = candidateClass.getDeclaredConstructor(String.class, String.class, boolean.class);
        constructor.setAccessible(true);
        Object candidate = constructor.newInstance(source, "AGGREGATE_STRUCTURED:sourceUrl", false);
        Method evaluate = TenderSourceUrlResolverV2Tool.class.getDeclaredMethod(
            "evaluateCandidate", candidateClass, String.class, String.class, String.class);
        evaluate.setAccessible(true);
        Object outcome = evaluate.invoke(null, candidate, "测试项目", "123", "");
        var status = outcome.getClass().getDeclaredField("status");
        status.setAccessible(true);
        var finalUrl = outcome.getClass().getDeclaredField("finalUrl");
        finalUrl.setAccessible(true);
        if (!"EXTRACTED_CLIENT_ROUTE".equals(status.get(outcome))
                || !source.equals(finalUrl.get(outcome))) {
            throw new AssertionError("Client-routed source was not retained: " + finalUrl.get(outcome));
        }
    }

    @SuppressWarnings("unchecked")
    private static void testTwoHundredKeyMappings() {
        TenderSourceUrlResolverV2Tool tool = new TenderSourceUrlResolverV2Tool();
        int checked = 0;
        for (int batch = 0; batch < 10; batch++) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                String key = "case:" + batch + ":" + index;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("record_key", key);
                item.put("title", "回归项目 " + key);
                item.put("source_url", "http://127.0.0.1/notice/" + key);
                items.add(item);
            }
            Map<String, Object> result = (Map<String, Object>) tool.execute(null, Map.of("items", items));
            List<Map<String, Object>> outputs = (List<Map<String, Object>>) result.get("items");
            if (outputs.size() != items.size()) throw new AssertionError("Output count mismatch");
            for (Map<String, Object> output : outputs) {
                String key = String.valueOf(output.get("record_key"));
                if (items.stream().noneMatch(item -> key.equals(item.get("record_key")))) {
                    throw new AssertionError("Unknown record_key: " + key);
                }
                if (!"INVALID_INPUT".equals(output.get("source_status"))) {
                    throw new AssertionError("Private target was not rejected: " + output);
                }
                checked++;
            }
        }
        if (checked != 200) throw new AssertionError("Expected 200 cases, checked " + checked);
    }

    @SuppressWarnings("unchecked")
    private static void testInvalidBatchKeys() {
        TenderSourceUrlResolverV2Tool tool = new TenderSourceUrlResolverV2Tool();
        Map<String, Object> missing = (Map<String, Object>) tool.execute(null, Map.of(
            "items", List.of(Map.of("title", "缺少键"))));
        if (!Boolean.FALSE.equals(missing.get("success"))) throw new AssertionError("Missing key accepted");
        Map<String, Object> duplicate = (Map<String, Object>) tool.execute(null, Map.of(
            "items", List.of(
                Map.of("record_key", "dup", "title", "A"),
                Map.of("record_key", "dup", "title", "B"))));
        if (!Boolean.FALSE.equals(duplicate.get("success"))) throw new AssertionError("Duplicate key accepted");
    }

    @SuppressWarnings("unchecked")
    private static void testEmptyBatch() {
        TenderSourceUrlResolverV2Tool tool = new TenderSourceUrlResolverV2Tool();
        Map<String, Object> result = (Map<String, Object>) tool.execute(null, Map.of("items", List.of()));
        if (!Boolean.TRUE.equals(result.get("success"))
                || !Boolean.TRUE.equals(result.get("link_resolution_complete"))) {
            throw new AssertionError("Empty display batch should be a complete no-op");
        }
    }
}
