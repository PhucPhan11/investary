package com.investary.email.parsing;

/**
 * The input cannot be interpreted as an email at all.
 */
public class MalformedEmailException extends Exception {

    public MalformedEmailException(String message) {
        super(message);
    }

    public MalformedEmailException(String message, Throwable cause) {
        super(message, cause);
    }
}
