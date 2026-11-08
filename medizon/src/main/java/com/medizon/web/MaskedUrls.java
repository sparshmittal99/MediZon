package com.medizon.web;

import com.medizon.domain.IdMask;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Which ids in customer URLs are masked with {@link IdMask}, and the rewriting both ways. Links and redirects the
 * site builds are masked on the way out ({@link MaskedUrlConfig}); {@link MaskedUrlFilter} turns tokens back into
 * ids on the way in, and sends old plain-number links (emails, saved notifications, the app) to their masked form.
 *
 * <p>Orders aren't listed: they have their own references ({@code OrderController}). Staff pages ({@code /admin})
 * and the JSON API ({@code /api}) keep plain ids.
 */
public final class MaskedUrls {

    /** The segment right after {@code prefix} is an id of {@code kind}. */
    record PathRule(String prefix, String kind) {}

    static final List<PathRule> PATHS = List.of(
            new PathRule("/plans/", "plan"),
            new PathRule("/addresses/", "address"),
            new PathRule("/prescriptions/", "prescription"),
            new PathRule("/support/tickets/", "ticket"),
            new PathRule("/support/attachments/", "ticket-message"),
            new PathRule("/notifications/", "notification"),
            new PathRule("/notifications/devices/", "device"),
            new PathRule("/account/devices/", "device"),
            new PathRule("/members/", "member"),
            new PathRule("/medicines/", "medicine"),
            new PathRule("/stock-alerts/", "medicine"),
            new PathRule("/cart/", "medicine"),
            new PathRule("/returns/", "return"));

    /** Query parameters holding ids on customer pages (?member=, ?address= ...). */
    static final Map<String, String> PARAMS = Map.of(
            "member", "member", "memberId", "member", "address", "address", "medicineId", "medicine");

    private MaskedUrls() {}

    /** Customer pages: everything but staff pages and the JSON API. */
    static boolean customerPath(String path) {
        return !(path.equals("/admin") || path.startsWith("/admin/") || path.startsWith("/api/"));
    }

    /**
     * Masks the ids in a link of this site ("/plans/42/edit?x=1" becomes "/plans/k7Qx2mPaB3/edit?x=1"), with or
     * without the context path. Anything else (other sites, "#", "javascript:") is returned as it is.
     */
    public static String mask(String url, String contextPath) {
        if (url == null || !url.startsWith("/") || url.startsWith("//")) return url;
        String ctx = contextPath == null ? "" : contextPath;
        if (!ctx.isEmpty() && !(url.equals(ctx) || url.startsWith(ctx + "/") || url.startsWith(ctx + "?"))) return url;
        String rest = url.substring(ctx.length());
        String fragment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) { fragment = rest.substring(hash); rest = rest.substring(0, hash); }
        String query = null;
        int q = rest.indexOf('?');
        if (q >= 0) { query = rest.substring(q + 1); rest = rest.substring(0, q); }
        if (rest.isEmpty() || !customerPath(rest)) return url;
        String path = maskPath(rest);
        String newQuery = query == null ? null : maskQuery(query);
        return ctx + path + (newQuery == null ? "" : "?" + newQuery) + fragment;
    }

    /** The path with plain ids in masked positions replaced by tokens. */
    static String maskPath(String path) {
        if (!customerPath(path)) return path;
        String out = path;
        for (PathRule r : PATHS) {
            String seg = segmentAfter(out, r.prefix());
            if (seg != null && IdMask.isPlainId(seg)) out = replaceSegment(out, r.prefix(), IdMask.encode(Long.parseLong(seg), r.kind()));
        }
        return out;
    }

    /** The path with tokens in masked positions turned back into ids; the same path when there are none. */
    public static String unmaskPath(String path) {
        if (!customerPath(path)) return path;
        String out = path;
        for (PathRule r : PATHS) {
            String seg = segmentAfter(out, r.prefix());
            Long id = seg == null ? null : IdMask.decode(seg, r.kind());
            if (id != null) out = replaceSegment(out, r.prefix(), String.valueOf(id));
        }
        return out;
    }

    static String maskQuery(String query) {
        return rewriteQuery(query, true);
    }

    static String unmaskQuery(String query) {
        return rewriteQuery(query, false);
    }

    /** A query parameter's value as the controller should see it: a token turned back into its id. */
    static String unmaskParam(String name, String value) {
        String kind = PARAMS.get(name);
        if (kind == null || value == null) return value;
        Long id = IdMask.decode(value, kind);
        return id == null ? value : String.valueOf(id);
    }

    private static String rewriteQuery(String query, boolean mask) {
        if (query.isEmpty()) return query;
        String[] pairs = query.split("&", -1);
        boolean changed = false;
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq <= 0) continue;
            String name = URLDecoder.decode(pairs[i].substring(0, eq), StandardCharsets.UTF_8);
            String kind = PARAMS.get(name);
            if (kind == null) continue;
            String value = pairs[i].substring(eq + 1);
            String now = null;
            if (mask && IdMask.isPlainId(value)) now = IdMask.encode(Long.parseLong(value), kind);
            if (!mask) { Long id = IdMask.decode(value, kind); if (id != null) now = String.valueOf(id); }
            if (now != null) { pairs[i] = pairs[i].substring(0, eq + 1) + now; changed = true; }
        }
        return changed ? String.join("&", pairs) : query;
    }

    /** The path segment right after {@code prefix}, or null when the path doesn't go past it. */
    private static String segmentAfter(String path, String prefix) {
        if (!path.startsWith(prefix) || path.length() == prefix.length()) return null;
        int end = path.indexOf('/', prefix.length());
        return path.substring(prefix.length(), end < 0 ? path.length() : end);
    }

    private static String replaceSegment(String path, String prefix, String value) {
        int end = path.indexOf('/', prefix.length());
        return prefix + value + (end < 0 ? "" : path.substring(end));
    }
}
