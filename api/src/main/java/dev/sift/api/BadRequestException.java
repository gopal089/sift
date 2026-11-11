package dev.sift.api;

/** Thrown when the request is wrong in a way the user can fix. Becomes an HTTP 400 with friendly JSON. */
public class BadRequestException extends RuntimeException {

    private final String code;

    public BadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
