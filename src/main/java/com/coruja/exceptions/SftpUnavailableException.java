package com.coruja.exceptions;

public class SftpUnavailableException extends RuntimeException{
    public SftpUnavailableException(String message) {
        super(message);
    }

    public SftpUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
