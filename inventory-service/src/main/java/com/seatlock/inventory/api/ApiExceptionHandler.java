package com.seatlock.inventory.api;

import com.seatlock.inventory.application.EventNotFoundException;
import com.seatlock.inventory.application.InvalidPageException;
import com.seatlock.inventory.application.ReservationConflictException;
import com.seatlock.inventory.application.SeatNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(EventNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Event not found", ex.getMessage());
    }

    @ExceptionHandler(InvalidPageException.class)
    public ResponseEntity<ProblemDetail> badPage(InvalidPageException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid pagination", ex.getMessage());
    }

    @ExceptionHandler(SeatNotFoundException.class)
    public ResponseEntity<ProblemDetail> seatNotFound(SeatNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Seat not found", ex.getMessage());
    }

    @ExceptionHandler(ReservationConflictException.class)
    public ResponseEntity<ProblemDetail> conflict(ReservationConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Reservation conflict", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "bookingId must be a valid UUID");
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String title, String message) {
        var body = ProblemDetail.forStatusAndDetail(status, message);
        body.setTitle(title);
        return ResponseEntity.status(status).body(body);
    }
}
