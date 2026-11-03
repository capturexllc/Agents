package com.vamsdest.service;

/** Trip missing or not owned by the caller; maps to HTTP 404 so ownership is not disclosed. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
