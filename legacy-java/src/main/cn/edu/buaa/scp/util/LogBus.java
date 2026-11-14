package cn.edu.buaa.scp.util;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 极简运行日志：内存环形缓冲 + 监听器。
 *
 * <p>只记录流程与状态，绝不写入密码；Cookie 只记录「有没有 / 多长」，不记录原文。
 */
public final class LogBus {

    private static final int MAX_LINES = 800;
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);

    private static final List<String> LINES = new ArrayList<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private LogBus() {
    }

    public static void add(String tag, String message) {
        String line;
        synchronized (TIME) {
            line = TIME.format(new Date()) + " [" + tag + "] " + message;
        }
        synchronized (LINES) {
            LINES.add(line);
            while (LINES.size() > MAX_LINES) {
                LINES.remove(0);
            }
        }
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // 日志监听器异常不应影响主流程
            }
        }
    }

    public static void step(String message) {
        add("STEP", message);
    }

    public static void net(String message) {
        add("HTTP", message);
    }

    public static void warn(String message) {
        add("WARN", message);
    }

    public static void error(String message) {
        add("ERR", message);
    }

    /** 返回日志快照（可直接展示）。 */
    public static String dump() {
        synchronized (LINES) {
            return String.join("\n", LINES);
        }
    }

    public static int size() {
        synchronized (LINES) {
            return LINES.size();
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // ignore
            }
        }
    }

    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }
}
