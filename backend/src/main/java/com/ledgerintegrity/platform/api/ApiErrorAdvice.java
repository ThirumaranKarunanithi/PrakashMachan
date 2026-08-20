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
}
