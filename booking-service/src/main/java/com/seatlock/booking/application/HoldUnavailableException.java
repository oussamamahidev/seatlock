package com.seatlock.booking.application;
public class HoldUnavailableException extends RuntimeException {
    public HoldUnavailableException(String message) { super(message); }
    public HoldUnavailableException(String message, Throwable cause) { super(message, cause); }
}
