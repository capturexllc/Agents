package com.vamsdest.service;

/** Input rejected by validation; maps to HTTP 400. */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
