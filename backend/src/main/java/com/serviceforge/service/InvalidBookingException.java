package com.serviceforge.service;

/**
 * Thrown when a booking request is malformed (e.g. end time not after start time).
 * Mapped to HTTP 400 by the controller.
 */
public class InvalidBookingException extends RuntimeException {

    public InvalidBookingException(String message) {
        super(message);
    }
}
