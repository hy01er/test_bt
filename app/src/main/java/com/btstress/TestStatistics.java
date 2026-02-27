package com.btstress;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 压测统计数据模型
 * 记录成功次数、失败次数及失败原因
 */
public class TestStatistics {

    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);

    // 各失败原因计数
    private final AtomicInteger scanTimeout = new AtomicInteger(0);
    private final AtomicInteger bondFailed = new AtomicInteger(0);
    private final AtomicInteger connectTimeout = new AtomicInteger(0);
    private final AtomicInteger pageTimeout = new AtomicInteger(0);
    private final AtomicInteger disconnectFailed = new AtomicInteger(0);
    private final AtomicInteger unpairFailed = new AtomicInteger(0);
    private final AtomicInteger otherError = new AtomicInteger(0);

    private long startTimeMs = 0;

    /** 失败原因枚举 */
    public enum FailReason {
        SCAN_TIMEOUT("扫描超时，未找到目标设备"),
        BOND_FAILED("配对失败(Bond Failed)"),
        PAGE_TIMEOUT("Page Timeout(连接超时)"),
        CONNECT_TIMEOUT("连接超时，未收到A2DP/RFCOMM连接"),
        DISCONNECT_FAILED("断开连接失败"),
        UNPAIR_FAILED("取消配对失败"),
        OTHER("其他错误");

        public final String desc;
        FailReason(String desc) { this.desc = desc; }
    }

    public void start() {
        startTimeMs = System.currentTimeMillis();
    }

    public void reset() {
        successCount.set(0);
        failureCount.set(0);
        totalCount.set(0);
        scanTimeout.set(0);
        bondFailed.set(0);
        connectTimeout.set(0);
        pageTimeout.set(0);
        disconnectFailed.set(0);
        unpairFailed.set(0);
        otherError.set(0);
        startTimeMs = System.currentTimeMillis();
    }

    public void recordSuccess() {
        successCount.incrementAndGet();
        totalCount.incrementAndGet();
    }

    public void recordFailure(FailReason reason) {
        failureCount.incrementAndGet();
        totalCount.incrementAndGet();
        switch (reason) {
            case SCAN_TIMEOUT:    scanTimeout.incrementAndGet(); break;
            case BOND_FAILED:     bondFailed.incrementAndGet(); break;
            case PAGE_TIMEOUT:    pageTimeout.incrementAndGet(); break;
            case CONNECT_TIMEOUT: connectTimeout.incrementAndGet(); break;
            case DISCONNECT_FAILED: disconnectFailed.incrementAndGet(); break;
            case UNPAIR_FAILED:   unpairFailed.incrementAndGet(); break;
            default:              otherError.incrementAndGet(); break;
        }
    }

    public int getSuccessCount()  { return successCount.get(); }
    public int getFailureCount()  { return failureCount.get(); }
    public int getTotalCount()    { return totalCount.get(); }

    /** 获取运行时长字符串 */
    public String getElapsedTime() {
        if (startTimeMs == 0) return "00:00:00";
        long elapsed = System.currentTimeMillis() - startTimeMs;
        long h = elapsed / 3600000;
        long m = (elapsed % 3600000) / 60000;
        long s = (elapsed % 60000) / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s);
    }

    /** 获取成功率字符串 */
    public String getSuccessRate() {
        int total = totalCount.get();
        if (total == 0) return "0.0%";
        return String.format(Locale.getDefault(), "%.1f%%", successCount.get() * 100.0 / total);
    }

    /** 获取失败原因汇总字符串 */
    public String getFailureSummary() {
        StringBuilder sb = new StringBuilder();
        appendIfNonZero(sb, "Page Timeout", pageTimeout.get());
        appendIfNonZero(sb, "扫描超时", scanTimeout.get());
        appendIfNonZero(sb, "配对失败", bondFailed.get());
        appendIfNonZero(sb, "连接超时", connectTimeout.get());
        appendIfNonZero(sb, "断开失败", disconnectFailed.get());
        appendIfNonZero(sb, "取消配对失败", unpairFailed.get());
        appendIfNonZero(sb, "其他", otherError.get());
        return sb.length() == 0 ? "无" : sb.toString().trim();
    }

    private void appendIfNonZero(StringBuilder sb, String label, int count) {
        if (count > 0) {
            sb.append(label).append(": ").append(count).append("  ");
        }
    }
}
