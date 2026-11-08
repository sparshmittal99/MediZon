package com.medizon.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

import java.util.Map;

/**
 * Masks ids in every link, image, form and redirect the site builds, with no change to templates or controllers.
 * Thymeleaf's th:href, th:src and th:action, and Spring's "redirect:", all pass their URL through the
 * {@code requestDataValueProcessor} bean (Spring Security's, which adds the CSRF field to forms); this wraps it.
 *
 * <p>It also wraps the {@code flashMapManager}, which keeps a message ("Reminders paused.") for the page a redirect
 * goes to. A redirect back to the page the customer came from (its Referer) names it with tokens, while
 * {@link MaskedUrlFilter} hands that page's request on with ids, so the message is filed under the ids instead.
 */
@Component
public class MaskedUrlConfig implements BeanPostProcessor {

    static final String BEAN = "requestDataValueProcessor";
    static final String FLASH_BEAN = "flashMapManager";

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (BEAN.equals(name) && bean instanceof RequestDataValueProcessor p && !(bean instanceof Masking)) return new Masking(p);
        if (FLASH_BEAN.equals(name) && bean instanceof FlashMapManager f && !(bean instanceof UnmaskingFlash)) return new UnmaskingFlash(f);
        return bean;
    }

    /** Files each redirect's message under the page's ids, as the page's request will carry them. */
    static final class UnmaskingFlash implements FlashMapManager {
        private final FlashMapManager delegate;

        UnmaskingFlash(FlashMapManager delegate) { this.delegate = delegate; }

        @Override
        public FlashMap retrieveAndUpdate(HttpServletRequest request, HttpServletResponse response) {
            return delegate.retrieveAndUpdate(request, response);
        }

        @Override
        public void saveOutputFlashMap(FlashMap flashMap, HttpServletRequest request, HttpServletResponse response) {
            unmask(flashMap, request.getContextPath());
            delegate.saveOutputFlashMap(flashMap, request, response);
        }

        static void unmask(FlashMap flashMap, String contextPath) {
            String path = flashMap.getTargetRequestPath();
            if (path != null) {
                String ctx = contextPath == null ? "" : contextPath;
                if (path.startsWith(ctx)) flashMap.setTargetRequestPath(ctx + MaskedUrls.unmaskPath(path.substring(ctx.length())));
            }
            flashMap.getTargetRequestParams().replaceAll((name, values) ->
                    values.stream().map(v -> MaskedUrls.unmaskParam(name, v)).collect(java.util.stream.Collectors.toList()));
        }
    }

    /** Masks the URL, then lets the wrapped processor (CSRF) do its part. */
    static final class Masking implements RequestDataValueProcessor {
        private final RequestDataValueProcessor delegate;

        Masking(RequestDataValueProcessor delegate) { this.delegate = delegate; }

        @Override
        public String processAction(HttpServletRequest request, String action, String httpMethod) {
            return delegate.processAction(request, MaskedUrls.mask(action, request.getContextPath()), httpMethod);
        }

        @Override
        public String processFormFieldValue(HttpServletRequest request, String name, String value, String type) {
            return delegate.processFormFieldValue(request, name, value, type);
        }

        @Override
        public Map<String, String> getExtraHiddenFields(HttpServletRequest request) {
            return delegate.getExtraHiddenFields(request);
        }

        @Override
        public String processUrl(HttpServletRequest request, String url) {
            return delegate.processUrl(request, MaskedUrls.mask(url, request.getContextPath()));
        }
    }
}
