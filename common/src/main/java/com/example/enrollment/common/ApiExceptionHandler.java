package com.example.enrollment.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
        log.warn("[HTTP ERROR] {} {} -> {} ({})", request.getMethod(), request.getRequestURI(), status.value(), message);
        return ResponseEntity.status(status).body(Map.of("timestamp", Instant.now().toString(), "status", status.value(),
                "error", status.getReasonPhrase(), "message", message,
                "path", request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("[DB CONSTRAINT] {} {} rejected by database constraint", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("timestamp", Instant.now().toString(), "status", 409,
                "error", "Conflict", "message", "The requested record conflicts with existing data.", "path", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleOther(Exception ex, HttpServletRequest request) {
        log.error("[HTTP ERROR] Unhandled failure method={} path={}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("timestamp", Instant.now().toString(), "status", 500,
                "error", "Internal Server Error", "message", "The service could not complete the request.", "path", request.getRequestURI()));
    }
}
