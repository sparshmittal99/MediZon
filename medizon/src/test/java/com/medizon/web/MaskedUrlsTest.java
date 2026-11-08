package com.medizon.web;

import com.medizon.domain.IdMask;
import com.medizon.domain.OrderRef;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Ids in customer URLs are masked on the way out and turned back on the way in; staff pages keep plain ids. */
class MaskedUrlsTest {

    @Test
    void everyIdRoundTripsAndKindsDiffer() {
        for (long id : new long[]{0, 1, 7, 42, 123_456, 99_999_999L, (1L << 58) - 1}) {
            for (String kind : List.of("plan", "ticket", "medicine", "")) {
                String token = IdMask.encode(id, kind);
                assertThat(token).hasSize(IdMask.LENGTH);
                assertThat(IdMask.decode(token, kind)).isEqualTo(id);
            }
        }
        assertThat(IdMask.encode(42, "plan")).isNotEqualTo(IdMask.encode(42, "ticket"));
        assertThat(IdMask.encode(42, "plan")).isNotEqualTo(IdMask.encode(43, "plan"));
    }

    @Test
    void orderLinksAlreadySentStillWork() {
        // Tokens from before other ids were masked
        assertThat(OrderRef.encode(1)).isEqualTo("HnhFiVYw7W");
        assertThat(OrderRef.encode(42)).isEqualTo("4rUCFRxFjm");
        assertThat(OrderRef.encode(123456)).isEqualTo("sii0cVzKTM");
        assertThat(OrderRef.decode("4rUCFRxFjm")).isEqualTo(42L);
        assertThat(OrderRef.decode("42")).isEqualTo(42L);
    }

    @Test
    void masksCustomerLinks() {
        String plan = IdMask.encode(42, "plan");
        assertThat(MaskedUrls.mask("/plans/42/edit", "")).isEqualTo("/plans/" + plan + "/edit");
        assertThat(MaskedUrls.mask("/plans/42", "")).isEqualTo("/plans/" + plan);
        assertThat(MaskedUrls.mask("/plans?member=9&x=1", ""))
                .isEqualTo("/plans?member=" + IdMask.encode(9, "member") + "&x=1");
        assertThat(MaskedUrls.mask("/plans/new?medicineId=5&memberId=9", ""))
                .isEqualTo("/plans/new?medicineId=" + IdMask.encode(5, "medicine") + "&memberId=" + IdMask.encode(9, "member"));
        // The index after the id stays as it is
        assertThat(MaskedUrls.mask("/support/attachments/12/0", ""))
                .isEqualTo("/support/attachments/" + IdMask.encode(12, "ticket-message") + "/0");
        assertThat(MaskedUrls.mask("/notifications/devices/3/forget", ""))
                .isEqualTo("/notifications/devices/" + IdMask.encode(3, "device") + "/forget");
        // With a context path: only this site's links
        assertThat(MaskedUrls.mask("/other/plans/7", "/shop")).isEqualTo("/other/plans/7");
        assertThat(MaskedUrls.mask("/shop/plans/7#top", "/shop")).isEqualTo("/shop/plans/" + IdMask.encode(7, "plan") + "#top");
    }

    @Test
    void leavesEverythingElseAlone() {
        Stream.of("/plans", "/plans/new", "/addresses/save", "/admin/orders/42", "/admin/plans/42", "/api/cart/42",
                "https://www.google.com/maps/search/?api=1&query=1,2", "#", "//cdn.example/plans/1", "/orders/42",
                "/plans/" + IdMask.encode(42, "plan"))
                .forEach(u -> assertThat(MaskedUrls.mask(u, "")).isEqualTo(u));
    }

    @Test
    void unmasksTokensBackToIds() {
        assertThat(MaskedUrls.unmaskPath("/plans/" + IdMask.encode(42, "plan") + "/dose")).isEqualTo("/plans/42/dose");
        assertThat(MaskedUrls.unmaskPath("/plans/new")).isEqualTo("/plans/new");
        assertThat(MaskedUrls.unmaskQuery("member=" + IdMask.encode(9, "member") + "&q=x")).isEqualTo("member=9&q=x");
        // A token of the wrong kind doesn't turn into the same plan
        assertThat(MaskedUrls.unmaskPath("/plans/" + IdMask.encode(42, "ticket"))).isNotEqualTo("/plans/42");
    }

    @Test
    void filterRedirectsPlainIdsAndUnmasksTokens() throws Exception {
        MaskedUrlFilter filter = new MaskedUrlFilter();

        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/plans/42/edit"), res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(302);
        assertThat(res.getHeader("Location")).isEqualTo("/plans/" + IdMask.encode(42, "plan") + "/edit");

        MockHttpServletRequest withQuery = new MockHttpServletRequest("GET", "/checkout");
        withQuery.setQueryString("address=5");
        res = new MockHttpServletResponse();
        filter.doFilter(withQuery, res, new MockFilterChain());
        assertThat(res.getHeader("Location")).isEqualTo("/checkout?address=" + IdMask.encode(5, "address"));

        String token = IdMask.encode(42, "plan");
        MockHttpServletRequest masked = new MockHttpServletRequest("GET", "/plans/" + token + "/edit");
        masked.setQueryString("member=" + IdMask.encode(9, "member"));
        masked.addParameter("member", IdMask.encode(9, "member"));
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(masked, new MockHttpServletResponse(), chain);
        HttpServletRequest seen = (HttpServletRequest) chain.getRequest();
        assertThat(seen.getRequestURI()).isEqualTo("/plans/42/edit");
        assertThat(seen.getServletPath()).isEqualTo("/plans/42/edit");
        assertThat(seen.getParameter("member")).isEqualTo("9");
        assertThat(seen.getQueryString()).isEqualTo("member=9");

        // Forms may still post plain ids; staff pages are never touched
        for (MockHttpServletRequest r : List.of(new MockHttpServletRequest("POST", "/plans/42/dose"),
                new MockHttpServletRequest("GET", "/admin/orders/42"))) {
            chain = new MockFilterChain();
            res = new MockHttpServletResponse();
            filter.doFilter(r, res, chain);
            assertThat(res.getStatus()).isEqualTo(200);
            assertThat(((HttpServletRequest) chain.getRequest()).getRequestURI()).isEqualTo(r.getRequestURI());
        }
    }

    /** A real page like /medicines/categories must never be mistaken for a masked id. */
    @Test
    void noRouteNameLooksLikeAToken() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> paths = new ArrayList<>();
        for (var def : scan.findCandidateComponents("com.medizon")) {
            Class<?> c = Class.forName(def.getBeanClassName());
            String[] bases = c.isAnnotationPresent(RequestMapping.class) ? paths(c.getAnnotation(RequestMapping.class).value()) : new String[]{""};
            for (Method m : c.getDeclaredMethods()) {
                for (String p : methodPaths(m)) for (String b : bases) paths.add(b + p);
            }
        }
        assertThat(paths).isNotEmpty();
        for (String path : paths) {
            for (MaskedUrls.PathRule r : MaskedUrls.PATHS) {
                if (!path.startsWith(r.prefix())) continue;
                String seg = path.substring(r.prefix().length()).split("/")[0];
                if (seg.startsWith("{")) continue;
                assertThat(IdMask.isToken(seg)).as("route %s has a segment shaped like a token", path).isFalse();
            }
        }
    }

    private static String[] paths(String[] v) { return v.length == 0 ? new String[]{""} : v; }

    private static String[] methodPaths(Method m) {
        if (m.isAnnotationPresent(GetMapping.class)) return paths(m.getAnnotation(GetMapping.class).value());
        if (m.isAnnotationPresent(PostMapping.class)) return paths(m.getAnnotation(PostMapping.class).value());
        if (m.isAnnotationPresent(PutMapping.class)) return paths(m.getAnnotation(PutMapping.class).value());
        if (m.isAnnotationPresent(DeleteMapping.class)) return paths(m.getAnnotation(DeleteMapping.class).value());
        if (m.isAnnotationPresent(RequestMapping.class)) return paths(m.getAnnotation(RequestMapping.class).value());
        return new String[0];
    }

    /** "Reminders paused." after a redirect back to the Referer (with tokens) reaches the page, which arrives with ids. */
    @Test
    void aRedirectsMessageReachesTheMaskedPage() throws Exception {
        org.springframework.web.servlet.support.SessionFlashMapManager real = new org.springframework.web.servlet.support.SessionFlashMapManager();
        org.springframework.web.servlet.FlashMapManager flash = (org.springframework.web.servlet.FlashMapManager)
                new MaskedUrlConfig().postProcessAfterInitialization(real, MaskedUrlConfig.FLASH_BEAN);

        String member = IdMask.encode(12, "member");
        org.springframework.web.servlet.FlashMap out = new org.springframework.web.servlet.FlashMap();
        out.put("success", "Reminders paused.");
        out.setTargetRequestPath("/plans");
        out.addTargetRequestParam("member", member);           // as RedirectView takes it from the Referer
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/plans/42/toggle");
        filterAndSave(post, flash, out);

        // The browser follows the redirect to the masked page; the filter hands it on with the member's id
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/plans");
        get.setSession(post.getSession());
        get.setQueryString("member=" + member);
        get.addParameter("member", member);
        MockFilterChain chain = new MockFilterChain();
        new MaskedUrlFilter().doFilter(get, new MockHttpServletResponse(), chain);
        org.springframework.web.servlet.FlashMap in = flash.retrieveAndUpdate((HttpServletRequest) chain.getRequest(), new MockHttpServletResponse());
        assertThat((Object) in).isNotNull();
        assertThat(in.get("success")).isEqualTo("Reminders paused.");

        // A masked path is filed under its id too
        org.springframework.web.servlet.FlashMap edit = new org.springframework.web.servlet.FlashMap();
        edit.setTargetRequestPath("/plans/" + IdMask.encode(42, "plan") + "/edit");
        MaskedUrlConfig.UnmaskingFlash.unmask(edit, "");
        assertThat(edit.getTargetRequestPath()).isEqualTo("/plans/42/edit");
    }

    private static void filterAndSave(MockHttpServletRequest request, org.springframework.web.servlet.FlashMapManager flash,
                                      org.springframework.web.servlet.FlashMap out) {
        flash.saveOutputFlashMap(out, request, new MockHttpServletResponse());
    }
}
