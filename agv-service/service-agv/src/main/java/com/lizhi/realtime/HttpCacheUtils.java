package com.lizhi.realtime;

/**
 * HTTP 缓存协商小工具（ETag 匹配）。
 */
public final class HttpCacheUtils {

    private HttpCacheUtils() {
    }

    /** If-None-Match 宽松匹配：支持引号、W/ 前缀、逗号列表与 * */
    public static boolean matchesEtag(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || etag == null) {
            return false;
        }
        String normalizedTarget = normalizeEtag(etag);
        for (String part : ifNoneMatch.split(",")) {
            String candidate = part.trim();
            if ("*".equals(candidate)) {
                return true;
            }
            if (normalizedTarget.equals(normalizeEtag(candidate))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeEtag(String value) {
        String normalized = value.trim();
        if (normalized.startsWith("W/") || normalized.startsWith("w/")) {
            normalized = normalized.substring(2).trim();
        }
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }
}
