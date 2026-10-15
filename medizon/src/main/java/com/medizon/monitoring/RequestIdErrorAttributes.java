package com.medizon.monitoring;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

/** Puts the request id on the error page, so a shopper can quote it and admins can find the error under Monitoring. */
@Component
public class RequestIdErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(request, options);
        Object id = request.getAttribute(RequestMonitorFilter.REQUEST_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (id != null) attributes.put("requestId", id);
        return attributes;
    }
}
