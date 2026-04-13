package com.authenza.iam.exeption;

import com.authenza.common.dto.ApiResponse;
import com.authenza.common.exception.AuthenzaException;
import com.authenza.common.exception.ResourceAlreadyExistsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RestExceptionHandler {

    /**
     * Catches ANY custom exception that inherits from AuthenzaException
     * and dynamically parses its assigned HttpStatus!
     */
    @ExceptionHandler(ResourceAlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Object>> handleAuthenzaException(ResourceAlreadyExistsException ex){
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.badRequest( ex.getMessage()));
    }
}
