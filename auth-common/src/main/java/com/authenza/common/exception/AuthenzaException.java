package com.authenza.common.exception;

/**
 * The root parent exception for all custom Authenza application exceptions.
 * By embedding the HttpStatus directly into the parent, the global RestExceptionHandler
 * can dynamically return the correct HTTP status code without needing a separate
 * handler method for every single child exception class.
 */
public abstract class AuthenzaException extends RuntimeException {

//    private final HttpStatus status;

    public AuthenzaException(String message) {
        super(message);
//        this.status = status;
    }

    public AuthenzaException(String message, Throwable cause) {
        super(message, cause);
//        this.status = status;
    }

//    public HttpStatus getStatus() {
//        return status;
//    }
}
