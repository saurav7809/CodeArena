package com.codearena.api;

import java.util.Map;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
            .headers(error.getHeaders())
            .body(Map.of("message", error.getReason() == null ? "The request could not be completed." : error.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException error) {
        var field = error.getBindingResult().getFieldError();
        String message = field == null ? "Check the request fields." : field.getField() + " " + field.getDefaultMessage() + ".";
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> badFormat(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiRequestFilter.BodyTooLarge) {
                return ResponseEntity.status(413).body(Map.of("message", "The request body is too large."));
            }
        }
        return ResponseEntity.badRequest().body(Map.of("message", "Check the request format and field values."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(409).body(Map.of("message", "That record conflicts with an existing record."));
    }

    @ExceptionHandler({TransientDataAccessException.class, CannotGetJdbcConnectionException.class})
    ResponseEntity<Map<String, String>> temporarilyUnavailable() {
        return ResponseEntity.status(503).header("Retry-After", "3")
            .body(Map.of("message", "The service is temporarily unavailable. Try again shortly."));
    }
}
