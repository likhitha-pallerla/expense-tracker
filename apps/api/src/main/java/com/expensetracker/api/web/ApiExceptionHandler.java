package com.expensetracker.api.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns exceptions into a consistent JSON shape the web and mobile clients can
 * both rely on.
 *
 * <p>Field errors are returned as a map keyed by field name so a form can
 * highlight the offending input rather than showing one generic banner.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidationFailure(MethodArgumentNotValidException ex) {
        Map<String, String> fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage(),
                        (first, second) -> first,
                        LinkedHashMap::new));

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid.");
        problem.setProperty("fields", fields);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail onResponseStatus(ResponseStatusException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(ex.getStatusCode());
        problem.setDetail(ex.getReason());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Database constraints are part of the contract, not just a safety net: the
     * unique index on (user_id, name) is what makes duplicate account names
     * impossible under concurrent requests, where a pre-check cannot.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onConstraintViolation(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Conflict");
        problem.setDetail(describe(ex));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /** Maps known constraint names to messages a user can act on. */
    static String describe(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause().getMessage();
        if (message == null) {
            return "That change conflicts with existing data.";
        }
        if (message.contains("accounts_name_unique")) {
            return "You already have an account with that name.";
        }
        if (message.contains("categories_name_unique")) {
            return "You already have a category with that name under the same parent.";
        }
        if (message.contains("transactions_external_ref_unique")) {
            return "A transaction with that bank reference already exists.";
        }
        if (message.contains("merchants_name_unique")) {
            return "That merchant already exists.";
        }
        return "That change conflicts with existing data.";
    }
}
