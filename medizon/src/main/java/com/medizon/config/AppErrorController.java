package com.medizon.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medizon.api.ApiErrors;
import com.medizon.monitoring.RequestMonitorFilter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.json.MappingJackson2JsonView;

import java.util.Map;

/**
 * Spring Boot's /error, except that errors on /api paths are always JSON, even when a browser asks for HTML: not
 * signed in, no access, a bad CSRF token, an unknown path or a crash. Pages keep the HTML error page.
 */
@Controller
@RequestMapping("${server.error.path:${error.path:/error}}")
public class AppErrorController extends BasicErrorController {

    private final ObjectMapper json;

    public AppErrorController(ErrorAttributes errorAttributes, ServerProperties server, ObjectProvider<ErrorViewResolver> resolvers,
                              ObjectMapper json) {
        super(errorAttributes, server.getError(), resolvers.orderedStream().toList());
        this.json = json;
    }

    @Override
    public ModelAndView errorHtml(HttpServletRequest request, HttpServletResponse response) {
        if (!isApi(request)) return super.errorHtml(request, response);
        HttpStatus status = getStatus(request);
        response.setStatus(status.value());
        // Spring's own mapper, so dates and field order match the other API responses
        MappingJackson2JsonView view = new MappingJackson2JsonView(json);
        view.setExtractValueFromSingleKeyModel(true);
        return new ModelAndView(view, Map.of("body", apiBody(request, status)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        if (!isApi(request)) return super.error(request);
        HttpStatus status = getStatus(request);
        return ResponseEntity.status(status).body(apiBody(request, status));
    }

    private static boolean isApi(HttpServletRequest request) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return uri != null && uri.toString().startsWith(request.getContextPath() + "/api/");
    }

    /** The message passed to sendError for 4xx; a crash's own message stays in the logs. */
    private static Map<String, Object> apiBody(HttpServletRequest request, HttpStatus status) {
        Object message = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        String text = status.is5xxServerError() ? "Something went wrong. Try again in a moment."
                : status == HttpStatus.NOT_FOUND ? "Not found."   // not Spring's "No static resource ..."
                : message != null && !message.toString().isBlank() ? message.toString() : status.getReasonPhrase();
        return ApiErrors.body(status.value(), text, String.valueOf(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)),
                request.getAttribute(RequestMonitorFilter.REQUEST_ID_ATTRIBUTE));
    }
}
