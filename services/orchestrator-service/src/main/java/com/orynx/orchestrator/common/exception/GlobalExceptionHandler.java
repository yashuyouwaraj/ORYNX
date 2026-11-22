package com.orynx.orchestrator.common.exception;

import com.orynx.orchestrator.common.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WorkflowNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiErrorResponse> handleWorkflowNotFound(WorkflowNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiErrorResponse.builder()
                        .timestamp(Instant.now().toString())
                        .status(HttpStatus.NOT_FOUND.value())
                        .error(ex.getMessage())
                        .path(request.getRequestURI())
                        .requestId(request.getHeader("X-Request-ID"))
                        .build()
        );
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ResponseEntity<ApiErrorResponse> handleUnauthorized(UnauthorizedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiErrorResponse.builder()
                        .timestamp(Instant.now().toString())
                        .status(HttpStatus.UNAUTHORIZED.value())
                        .error(ex.getMessage())
                        .path(request.getRequestURI())
                        .requestId(request.getHeader("X-Request-ID"))
                        .build()
        );
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(RuntimeException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiErrorResponse.builder()
                        .timestamp(Instant.now().toString())
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error(ex.getMessage())
                        .path(request.getRequestURI())
                        .requestId(request.getHeader("X-Request-ID"))
                        .build()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiErrorResponse.builder()
                        .timestamp(Instant.now().toString())
                        .status(HttpStatus.NOT_FOUND.value())
                        .error("Resource not found")
                        .path(request.getRequestURI())
                        .requestId(request.getHeader("X-Request-ID"))
                        .build()
        );
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ResponseEntity<ApiErrorResponse> handleException(Exception ex, HttpServletRequest request){
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiErrorResponse.builder()
                        .timestamp(Instant.now().toString())
                        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .error(ex.getMessage())
                        .path(request.getRequestURI())
                        .requestId(request.getHeader("X-Request-ID"))
                        .build()
        );
    }
}
