package com.medizon.service;

/** A rule the user broke; its message is shown to them as-is. */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) { super(message); }
}
