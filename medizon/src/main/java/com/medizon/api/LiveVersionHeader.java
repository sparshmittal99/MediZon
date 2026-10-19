package com.medizon.api;

import com.medizon.service.CurrentUser;
import com.medizon.service.LiveUpdates;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * When a page changes something itself (add to cart, the assistant...), the reply carries the user's new change
 * version, so that page updates in place instead of reloading for its own change. Other tabs still reload.
 */
@ControllerAdvice
@RequiredArgsConstructor
public class LiveVersionHeader implements ResponseBodyAdvice<Object> {

    public static final String HEADER = "X-MZ-Live";

    private final CurrentUser currentUser;
    private final LiveUpdates liveUpdates;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (request.getMethod() != HttpMethod.GET && request.getMethod() != HttpMethod.HEAD) {
            currentUser.find().ifPresent(u -> response.getHeaders().set(HEADER, String.valueOf(liveUpdates.version(u))));
        }
        return body;
    }
}
