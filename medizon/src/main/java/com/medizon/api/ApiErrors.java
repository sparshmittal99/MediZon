package com.medizon.api;

import com.medizon.monitoring.RequestMonitorFilter;
import com.medizon.service.BusinessException;
import com.medizon.service.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every /api error is JSON in one shape: {@code {timestamp, status, error, path, requestId}}, where {@code error} is
 * the message to show. Errors raised outside these controllers (sign-in, access, unknown paths, crashes) get the same
 * shape from {@link com.medizon.config.AppErrorController}.
 */
@RestControllerAdvice(basePackages = "com.medizon.api")
public class ApiErrors {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e, HttpServletRequest request) {
        return of(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> rejected(BusinessException e, HttpServletRequest request) {
        return of(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e, HttpServletRequest request) {
        return of(HttpStatus.BAD_REQUEST, "The request isn't valid.", request);
    }

    public static ResponseEntity<Map<String, Object>> of(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(body(status.value(), message, request.getRequestURI(),
                request.getAttribute(RequestMonitorFilter.REQUEST_ID_ATTRIBUTE)));
    }

    public static Map<String, Object> body(int status, String message, String path, Object requestId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", LocalDateTime.now());
        out.put("status", status);
        out.put("error", message);
        out.put("path", path);
        if (requestId != null) out.put("requestId", requestId);
        return out;
    }
}
