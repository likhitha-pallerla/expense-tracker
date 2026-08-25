package com.expensetracker.api.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.expensetracker.api.observability.RequestIdFilter;

/**
 * Turns exceptions into a consistent JSON shape the web and mobile clients can
 * both rely on.
 *
 * <p>Field errors are returned as a map keyed by field name so a form can
 * highlight the offending input rather than showing one generic banner.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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

    /**
     * Anything not handled above.
     *
     * <p>Without this, an unexpected exception falls through to Spring's
     * default error handling, which returns a different JSON shape from every
     * other error the clients see, and says nothing about which request it was.
     *
     * <p>The exception's own message never reaches the caller. Messages from
     * deep in a stack quote connection strings, SQL and file paths, and the
     * person reading them cannot act on any of it anyway. What they get instead
     * is the request id, which is in the log next to the stack trace -- so
     * "something went wrong, quote ab12cd34" is a report that can actually be
     * followed up, rather than a screenshot of a stack trace.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception ex) {
        String requestId = MDC.get(RequestIdFilter.REQUEST_ID);

        // error, not warn: nothing reaches here that was anticipated, so every
        // one of these is worth a person looking at.
        log.error("Unhandled exception on request {}", requestId, ex);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Something went wrong");
        problem.setDetail(requestId == null
                ? "Something went wrong at our end. Please try again."
                : "Something went wrong at our end. Please try again, and quote "
                        + requestId + " if it keeps happening.");
        problem.setProperty("timestamp", Instant.now());
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
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
        if (message.contains("budgets_period_unique")) {
            return "You already have a budget for that category and period.";
        }
        if (message.contains("recurring_match_key_unique")) {
            return "You are already tracking that as a recurring payment.";
        }
        return "That change conflicts with existing data.";
    }
}
