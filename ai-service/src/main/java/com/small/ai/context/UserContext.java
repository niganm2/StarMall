package com.small.ai.context;

/**
 * 当前登录用户上下文（ThreadLocal）。
 * 用户身份来自网关解析 JWT 后下传的 user-info 请求头，前端传入的 userId 一律不可信。
 */
public final class UserContext {

    private static final ThreadLocal<Long> TL = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUser(Long userId) {
        TL.set(userId);
    }

    public static Long getUser() {
        return TL.get();
    }

    public static void removeUser() {
        TL.remove();
    }
}
