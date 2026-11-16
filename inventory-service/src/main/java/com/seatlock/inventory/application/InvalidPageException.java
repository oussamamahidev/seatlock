package com.seatlock.inventory.application;

public class InvalidPageException extends RuntimeException {
    public InvalidPageException() {
        super("page must be >= 0 and size must be between 1 and 50");
    }
}
