package com.yeosal.api.common;

/**
 * Wire-format envelope for any non-2xx response. Symmetric to
 * {@link ApiResponse}, which wraps successful payloads in {@code {data: ...}}.
 *
 * <pre>{@code
 * { "error": { "code": "BAD_REQUEST", "message": "이메일이 잘못되었습니다." } }
 * }</pre>
 *
 * <p>{@code code} is a stable machine-readable enum string the client can
 * branch on (e.g. show a localized toast, route to retry/login, etc.).
 * {@code message} is human-readable and currently always Korean — the
 * client should not parse it.
 */
public record ApiErrorResponse(Error error) {

    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(new Error(code, message));
    }

    public record Error(String code, String message) {}
}
