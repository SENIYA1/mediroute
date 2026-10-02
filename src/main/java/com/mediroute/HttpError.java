package com.mediroute;

public class HttpError extends RuntimeException {
    public final int code;
    public HttpError(int code, String msg) { super(msg); this.code = code; }
}
