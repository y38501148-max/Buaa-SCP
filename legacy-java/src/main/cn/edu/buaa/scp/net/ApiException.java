package cn.edu.buaa.scp.net;

/** 业务接口异常：区分「会话失效」与普通业务错误，便于上层决定是否重新登录。 */
public class ApiException extends Exception {

    private final boolean authExpired;

    public ApiException(String message) {
        this(message, false);
    }

    public ApiException(String message, boolean authExpired) {
        super(message);
        this.authExpired = authExpired;
    }

    public boolean isAuthExpired() {
        return authExpired;
    }
}
