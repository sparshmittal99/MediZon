package com.medizon.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.util.Map;

/**
 * A file over the upload limit (5 MB each, spring.servlet.multipart) is refused before any controller runs, which
 * used to send an empty 413: browsers and the Android app showed a blank error page. Pages now go back with a message;
 * the JSON API gets an error it can show.
 */
@ControllerAdvice
public class UploadLimitAdvice {

    static final String MESSAGE = "That file is too big. Upload files up to 5 MB each: a clear photo of the page is enough.";

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Object tooBig(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", MESSAGE));
        }
        RequestContextUtils.getOutputFlashMap(request).put("error", MESSAGE);
        String referer = request.getHeader("Referer");
        return "redirect:" + (referer != null && !referer.isBlank() ? referer : "/prescriptions");
    }
}
