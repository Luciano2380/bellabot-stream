package com.lpopas.bellabotstream.application.exception;

public class MessageDeliveryException extends RuntimeException {

    public MessageDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
