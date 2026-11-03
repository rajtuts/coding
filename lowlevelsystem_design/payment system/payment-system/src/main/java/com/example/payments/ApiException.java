package com.example.payments;

/** Business/API error carrying an HTTP-style status so a controller layer could map it directly. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    @Override
    public String toString() { return "HTTP " + status + " " + code + ": " + getMessage(); }
}
