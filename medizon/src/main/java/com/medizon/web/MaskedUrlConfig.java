package com.medizon.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

import java.util.Map;

/**
 * Masks ids in every link, image, form and redirect the site builds, with no change to templates or controllers.
 * Thymeleaf's th:href, th:src and th:action, and Spring's "redirect:", all pass their URL through the
 * {@code requestDataValueProcessor} bean (Spring Security's, which adds the CSRF field to forms); this wraps it.
 */
@Component
public class MaskedUrlConfig implements BeanPostProcessor {

    static final String BEAN = "requestDataValueProcessor";

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (BEAN.equals(name) && bean instanceof RequestDataValueProcessor p && !(bean instanceof Masking)) return new Masking(p);
        return bean;
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
