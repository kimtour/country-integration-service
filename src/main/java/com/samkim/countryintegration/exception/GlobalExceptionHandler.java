package com.samkim.countryintegration.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleStatusException(
            ResponseStatusException exception,
            HttpServletRequest request) {

        String detail = exception.getReason() == null
                ? "The request could not be completed"
                : exception.getReason();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                exception.getStatusCode(), detail);

        addContext(problem, request);

        return ResponseEntity.status(exception.getStatusCode())
                .headers(exception.getHeaders())
                .body(problem);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMethod(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.METHOD_NOT_ALLOWED,
                "HTTP method " + request.getMethod()
                        + " is not supported for this URL");

        addContext(problem, request);

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(exception.getHeaders())
                .body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {

        Map<String, String> errors = new LinkedHashMap<>();

        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(
                        error.getField(),
                        error.getDefaultMessage() == null
                                ? "Invalid value"
                                : error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Please correct the invalid request fields");

        problem.setProperty("errors", errors);
        addContext(problem, request);

        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleInvalidJson(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {

        return error(
                HttpStatus.BAD_REQUEST,
                "Request body must contain valid JSON with the expected field types",
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleInvalidParameter(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {

        return error(
                HttpStatus.BAD_REQUEST,
                "Invalid value for parameter: " + exception.getName(),
                request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {

        // Spring framework errors retain their standard status, detail and headers.
        if (exception instanceof ErrorResponse response) {
            ProblemDetail problem = response.getBody();
            addContext(problem, request);
            return ResponseEntity.status(response.getStatusCode())
                    .headers(response.getHeaders())
                    .body(problem);
        }

        log.error(
                "Request failed: method={} path={}",
                request.getMethod(),
                request.getRequestURI(),
                exception);

        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "The request could not be completed. Please try again later",
                request);
    }

    private ResponseEntity<ProblemDetail> error(
            HttpStatus status,
            String detail,
            HttpServletRequest request) {

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(status, detail);

        addContext(problem, request);

        return ResponseEntity.status(status).body(problem);
    }

    private void addContext(
            ProblemDetail problem,
            HttpServletRequest request) {

        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("path", request.getRequestURI());
    }
}