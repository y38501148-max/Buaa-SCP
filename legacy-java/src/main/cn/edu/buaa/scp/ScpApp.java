package cn.edu.buaa.scp;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.edu.buaa.scp.net.ApiException;
import cn.edu.buaa.scp.net.CardApi;
import cn.edu.buaa.scp.net.CasAuth;
import cn.edu.buaa.scp.net.CookieJar;
import cn.edu.buaa.scp.net.HttpEngine;
import cn.edu.buaa.scp.store.SecureStore;
import cn.edu.buaa.scp.store.SessionData;
import cn.edu.buaa.scp.util.LogBus;

/**
 * 应用级单例：网络栈、会话仓库、线程池。
 *
 * <p>会话管理策略（依据实测链路）：
 * <ol>
 *   <li>启动时先用本地 JSESSIONID 试一次业务接口；</li>
 *   <li>失败且存有 CASTGC 时，走 CAS 免密续期，换新 JSESSIONID；</li>
 *   <li>两者都失效才要求重新输入密码。</li>
 * </ol>
 */
public class ScpApp extends Application {

    private static ScpApp instance;

    private final Object sessionLock = new Object();

    private CookieJar jar;
    private HttpEngine http;
    private CasAuth cas;
    private CardApi api;
    private SecureStore store;
    private ExecutorService pool;
    private Handler main;

    private volatile SessionData session;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        jar = new CookieJar();
        http = new HttpEngine(jar);
        cas = new CasAuth(http);
        api = new CardApi(http);
        store = new SecureStore(this);
        pool = Executors.newFixedThreadPool(3);
        main = new Handler(Looper.getMainLooper());

        SessionData saved = store.loadSession();
        if (saved != null) {
            session = saved;
            applyCookies(saved);
            LogBus.step("已加载本地会话（学号 " + saved.studentId + "，CASTGC "
                    + (saved.hasCastgc() ? "有" : "无") + "，JSESSIONID "
                    + (saved.hasJsessionid() ? "有" : "无") + "）");
        }
    }

    public static ScpApp get(Context context) {
        return (ScpApp) context.getApplicationContext();
    }

    public static ScpApp get() {
        return instance;
    }

    public CookieJar jar() {
        return jar;
    }

    public HttpEngine http() {
        return http;
    }

    public CasAuth cas() {
        return cas;
    }

    public CardApi api() {
        return api;
    }

    public SecureStore store() {
        return store;
    }

    public SessionData session() {
        return session;
    }

    public boolean hasSession() {
        SessionData data = session;
        return data != null && data.hasAnything();
    }

    public String openid() {
        SessionData data = session;
        return data == null ? "" : data.openid;
    }

    /** 后台线程执行。 */
    public void async(Runnable task) {
        pool.execute(task);
    }

    /** 主线程更新 UI。 */
    public void ui(Runnable task) {
        main.post(task);
    }

    /** 业务调用失败且会话过期时，自动续期并重试一次。 */
    public interface ApiCall {
        JSONObject run() throws Exception;
    }

    public JSONObject callWithRecovery(ApiCall call) throws Exception {
        try {
            return call.run();
        } catch (ApiException e) {
            if (e.isAuthExpired() && recoverSession()) {
                LogBus.step("会话已自动续期，重试请求");
                return call.run();
            }
            throw e;
        }
    }

    /**
     * 用现有凭据验证业务会话是否可用。阻塞调用，必须在后台线程执行。
     */
    public boolean validateSession() {
        try {
            JSONObject root = api.openCardPay();
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                LogBus.warn("会话校验：openCardPay 返回缺少 data 字段");
                return false;
            }
            JSONObject cardPay = data.optJSONObject("cardPay");
            if (cardPay == null) {
                LogBus.warn("会话校验：openCardPay 未返回 cardPay（可能未绑卡），但仍视为已认证");
                return true;
            }
            LogBus.step("会话校验通过，余额 " + cardPay.optString("cardbal", "?") + " 元");
            return true;
        } catch (ApiException e) {
            LogBus.warn("会话校验未通过：" + e.getMessage());
            return false;
        } catch (Exception e) {
            LogBus.warn("会话校验异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /**
     * 免密续期：用 CASTGC 换新票据 → 新 JSESSIONID，并校验业务会话。
     * 阻塞调用，必须在后台线程执行。
     */
    public boolean recoverSession() {
        synchronized (sessionLock) {
            SessionData data = session;
            if (data == null) {
                return false;
            }

            if (data.hasJsessionid()) {
                LogBus.step("尝试复用本地 JSESSIONID");
                jar.put(CasAuth.CARD_HOST, "JSESSIONID", data.jsessionid);
                if (validateSession()) {
                    return true;
                }
            }
            if (!data.hasCastgc()) {
                LogBus.warn("本地无 CASTGC，无法免密续期");
                return false;
            }
            try {
                CasAuth.Result result = cas.restore();
                if (!result.ok) {
                    LogBus.warn("免密续期失败：" + result.message);
                    return false;
                }
                if (!validateSession()) {
                    LogBus.warn("CASTGC 换取会话成功，但业务会话不可用");
                    return false;
                }
                rememberSession(data.studentId, result.openid);
                return true;
            } catch (Exception e) {
                LogBus.warn("免密续期异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
                return false;
            }
        }
    }

    /** 登录/续期成功后：抓取最新 Cookie 落盘。 */
    public void rememberSession(String studentId, String openid) {
        synchronized (sessionLock) {
            SessionData data = new SessionData();
            data.studentId = studentId == null ? "" : studentId;
            data.openid = openid == null ? "" : openid;
            String castgc = jar.valueOf(CasAuth.SSO_HOST, "CASTGC");
            String jsessionid = jar.valueOf(CasAuth.CARD_HOST, "JSESSIONID");
            data.castgc = castgc == null ? "" : castgc;
            data.jsessionid = jsessionid == null ? "" : jsessionid;
            data.savedAt = System.currentTimeMillis();
            SessionData old = session;
            data.lastSyncAt = old == null ? 0L : old.lastSyncAt;
            session = data;
            store.saveSession(data);
            if (!data.studentId.isEmpty()) {
                store.setRememberedStudentId(data.studentId);
            }
        }
    }

    /** 记录一次成功的数据刷新时间。 */
    public void markSynced() {
        SessionData data = session;
        if (data == null) {
            return;
        }
        data.lastSyncAt = System.currentTimeMillis();
        store.saveSession(data);
    }

    public void logout() {
        synchronized (sessionLock) {
            session = null;
            jar.clear();
            store.clearSession();
            LogBus.step("已退出登录，本地会话已清除");
        }
    }

    private void applyCookies(SessionData data) {
        if (data.hasCastgc()) {
            jar.put(CasAuth.SSO_HOST, "CASTGC", data.castgc);
        }
        if (data.hasJsessionid()) {
            jar.put(CasAuth.CARD_HOST, "JSESSIONID", data.jsessionid);
        }
    }
}
