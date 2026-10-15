package com.medizon.service;

/**
 * The thing asked for doesn't exist, or isn't the viewer's to see; both look the same so IDs of other accounts'
 * records aren't revealed. Answered with a 404 page rather than a flash message.
 */
public class NotFoundException extends BusinessException {
    public NotFoundException(String message) { super(message); }
}
