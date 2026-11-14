package cn.edu.buaa.scp.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 校园卡响应的容错取值与格式化工具。
 *
 * <p>接口字段可能随发版变化，故所有取值都按「路径逐级查找 + 缺失给空串」处理，
 * 不假设字段一定存在。
 */
public final class Json {

    private Json() {
    }

    /** 逐级取字符串：{@code str(root, "data", "cardinfo", "cardbal")}。缺失返回 ""。 */
    public static String str(JSONObject root, String... path) {
        Object value = find(root, path);
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        return String.valueOf(value);
    }

    public static JSONObject obj(JSONObject root, String... path) {
        Object value = find(root, path);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    public static JSONArray arr(JSONObject root, String... path) {
        Object value = find(root, path);
        return value instanceof JSONArray ? (JSONArray) value : null;
    }

    private static Object find(JSONObject root, String... path) {
        if (root == null || path == null || path.length == 0) {
            return null;
        }
        Object current = root;
        for (String key : path) {
            if (!(current instanceof JSONObject)) {
                return null;
            }
            JSONObject object = (JSONObject) current;
            if (!object.has(key)) {
                return null;
            }
            current = object.opt(key);
        }
        return current;
    }

    public static boolean bool(JSONObject root, String key, boolean fallback) {
        if (root == null) {
            return fallback;
        }
        return root.optBoolean(key, fallback);
    }

    /** 取数字字符串（含小数）为 double；缺失或非法返回 NaN。 */
    public static double number(String text) {
        if (text == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * 分 → 元。接口金额单位多为「分」，前端 fenToYuan 除以 100。
     * 已带小数点的值原样返回（避免重复换算）。
     */
    public static String fenToYuan(String fenText) {
        if (fenText == null || fenText.trim().isEmpty()) {
            return "0.00";
        }
        String text = fenText.trim();
        boolean negative = text.startsWith("-");
        String digits = negative ? text.substring(1) : text;
        if (digits.contains(".")) {
            return text;
        }
        try {
            BigDecimal value = new BigDecimal(digits)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            return (negative ? "-" : "") + value.toPlainString();
        } catch (NumberFormatException e) {
            return fenText;
        }
    }

    /** 元字符串统一保留两位小数（"429.17" / "429" / ""）。 */
    public static String yuan(String yuanText) {
        if (yuanText == null || yuanText.trim().isEmpty()) {
            return "0.00";
        }
        try {
            return new BigDecimal(yuanText.trim()).setScale(2, RoundingMode.HALF_UP)
                    .toPlainString();
        } catch (NumberFormatException e) {
            return yuanText;
        }
    }

    /** 金额展示：带 ¥ 前缀。 */
    public static String money(String yuanText) {
        return "¥" + yuan(yuanText);
    }

    /** 学号 / 卡号等脱敏：保留前 2 后 2。 */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 5) {
            return value.charAt(0) + "***";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    public static String now(String pattern) {
        return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date());
    }

    /** 当前月份 "yyyy-MM"。 */
    public static String currentMonth() {
        return now("yyyy-MM");
    }

    /** 月份加偏移："2026-11" + (-1) → "2026-10"。 */
    public static String shiftMonth(String yyyyMM, int delta) {
        try {
            String[] parts = yyyyMM.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            month += delta;
            while (month <= 0) {
                month += 12;
                year -= 1;
            }
            while (month > 12) {
                month -= 12;
                year += 1;
            }
            return String.format(Locale.US, "%04d-%02d", year, month);
        } catch (RuntimeException e) {
            return yyyyMM;
        }
    }

    /** "2026-11" → "2026年11月"。 */
    public static String monthLabel(String yyyyMM) {
        try {
            String[] parts = yyyyMM.split("-");
            return parts[0] + "年" + Integer.parseInt(parts[1]) + "月";
        } catch (RuntimeException e) {
            return yyyyMM;
        }
    }

    /** 当月天数对应的时间范围："2026-11" → {"2026-11-01", "2026-11-30"}。 */
    public static String[] monthRange(String yyyyMM) {
        try {
            String[] parts = yyyyMM.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.set(year, month - 1, 1);
            int days = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH);
            return new String[]{
                    String.format(Locale.US, "%04d-%02d-01", year, month),
                    String.format(Locale.US, "%04d-%02d-%02d", year, month, days)
            };
        } catch (RuntimeException e) {
            return new String[]{yyyyMM + "-01", yyyyMM + "-31"};
        }
    }

    /** 卡类型编码（官方 pcodeCard）→ 中文名。 */
    public static String cardType(String pcode) {
        if (pcode == null || pcode.isEmpty()) {
            return "未获取";
        }
        switch (pcode) {
            case "YG":
                return "员工卡";
            case "BKS":
                return "学生卡";
            case "FK":
                return "访客卡";
            case "LS":
                return "一般消费卡";
            default:
                return pcode;
        }
    }

    /** 时间戳 → "MM-dd HH:mm:ss"；0 值返回空串。 */
    public static String time(long epochMillis) {
        if (epochMillis <= 0) {
            return "";
        }
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(epochMillis));
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static String or(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }
}
