package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.auth.AccessDeniedException;
import com.yizhixianyu.agentvideo.auth.AuthRateLimitException;
import com.yizhixianyu.agentvideo.auth.AuthenticationRequiredException;
import com.yizhixianyu.agentvideo.auth.CsrfProtectionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<ApiError> unauthorized(AuthenticationRequiredException exception) {
        return error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", exception);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(AccessDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", exception);
    }

    @ExceptionHandler(CsrfProtectionException.class)
    public ResponseEntity<ApiError> csrfForbidden(CsrfProtectionException exception) {
        return error(HttpStatus.FORBIDDEN, "CSRF_REJECTED", exception);
    }

    @ExceptionHandler(AuthRateLimitException.class)
    public ResponseEntity<ApiError> rateLimited(AuthRateLimitException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()))
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ApiError("AUTH_RATE_LIMITED", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiError> badRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internalError(Exception exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", exception);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, Exception exception) {
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ApiError(code, exception.getMessage(), Instant.now()));
    }

    public record ApiError(String code, String message, Instant timestamp) {
    }
}
