package com.ledgerintegrity.platform.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

/**
 * QA P2: a bare {"status":400} tells the caller nothing. Every request-shape
 * error names the offending part or parameter so an import failure can say
 * "multipart file part 'gl' is missing" instead of "Bad Request".
 */
@RestControllerAdvice
public class ApiErrorAdvice {

    @ExceptionHandler(MissingServletRequestPartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> missingPart(MissingServletRequestPartException ex) {
        return Map.of("error", "Multipart file part '" + ex.getRequestPartName() + "' is missing. "
                + "The import endpoints expect file parts 'gl' and 'tb' plus a 'mapping' field.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> missingParam(MissingServletRequestParameterException ex) {
        return Map.of("error", "Required parameter '" + ex.getParameterName() + "' ("
                + ex.getParameterType() + ") is missing.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return Map.of("error", "Parameter '" + ex.getName() + "' has an invalid value: "
                + String.valueOf(ex.getValue()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> unreadableBody(HttpMessageNotReadableException ex) {
        String detail = ex.getMostSpecificCause().getMessage();
        return Map.of("error", "Request body could not be parsed"
                + (detail == null ? "." : ": " + (detail.length() > 200 ? detail.substring(0, 200) : detail)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String, String> tooLarge(MaxUploadSizeExceededException ex) {
        return Map.of("error", "The uploaded file exceeds the size limit (200MB per file).");
    }

    /** Deliberate statuses (404/403/400 from guards and controllers) pass through unchanged. */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> deliberate(
            org.springframework.web.server.ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() == null ? ex.getMessage() : ex.getReason()));
    }

    /**
     * Unexpected failures: return the exception class and a trimmed message instead of
     * a blank 500, and log the full trace. Acceptable while the platform runs demo
     * data; revisit (log-reference only) with the production hardening pass.
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> unexpected(Exception ex) {
        org.slf4j.LoggerFactory.getLogger(ApiErrorAdvice.class).error("Unhandled API error", ex);
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        return Map.of("error", "Unexpected error: " + ex.getClass().getSimpleName()
                + (msg.isEmpty() ? "" : " - " + (msg.length() > 200 ? msg.substring(0, 200) : msg)));
    }
}
