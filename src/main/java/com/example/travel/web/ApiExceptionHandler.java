package com.example.travel.web;

import com.example.travel.common.BadRequestException;
import com.example.travel.common.ConflictException;
import com.example.travel.common.DuplicateTransactionException;
import com.example.travel.common.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** One error shape for the whole API: {"error": "<code>", "message": "...", ...extras}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> badRequest(BadRequestException e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    /** Most specific handler wins, so this takes precedence over the generic ConflictException one. */
    @ExceptionHandler(DuplicateTransactionException.class)
    public ResponseEntity<Map<String, Object>> duplicate(DuplicateTransactionException e) {
        Map<String, Object> body = errorBody(e.getCode(), e.getMessage());
        body.put("existing_id", e.getExistingId());
        body.put("id", e.getExistingId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException e) {
        List<Map<String, String>> details = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(fe -> {
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("field", toSnakeCase(fe.getField()));
            detail.put("message", String.valueOf(fe.getDefaultMessage()));
            details.add(detail);
        });
        Map<String, Object> body = errorBody("validation_failed", "Request validation failed");
        body.put("details", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        String cause = e.getMostSpecificCause().getMessage();
        String firstLine = cause == null ? "" : cause.split("\\R", 2)[0];
        return body(HttpStatus.BAD_REQUEST, "malformed_request", "Malformed or invalid JSON request body. " + firstLine);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> badParam(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", "Invalid value for parameter '" + e.getName() + "'");
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(errorBody(code, message));
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }

    private static String toSnakeCase(String camel) {
        return camel.replaceAll("([A-Z])", "_$1").toLowerCase();
    }
}
