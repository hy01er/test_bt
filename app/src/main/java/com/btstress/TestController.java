package com.btstress;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 蓝牙压测核心控制器
 *
 * 状态机流程：
 *   IDLE → SCANNING → BONDING → CONNECTING → CONNECTED
 *        → DISCONNECTING → UNPAIRING → [下一轮]
 *
 * 任意步骤超时或失败 → 记录原因 → 清理环境 → 进入下一轮
 */
public class TestController {

    private static final String TAG = "BtStressTest";

    /*──── 状态定义 ────*/
    private static final int STATE_IDLE          = 0;
    private static final int STATE_SCANNING      = 1;
    private static final int STATE_BONDING       = 2;
    private static final int STATE_CONNECTING    = 3;
    private static final int STATE_CONNECTED     = 4;
    private static final int STATE_DISCONNECTING = 5;
    private static final int STATE_UNPAIRING     = 6;

    /*──── 超时时间 (ms) ────*/
    private static final long SCAN_TIMEOUT_MS    = 20_000;  // 20秒扫描超时
    private static final long BOND_TIMEOUT_MS    = 30_000;  // 30秒配对超时
    private static final long CONNECT_TIMEOUT_MS = 20_000;  // 20秒连接超时
    private static final long DISC_TIMEOUT_MS    = 10_000;  // 10秒断开超时
    private static final long UNPAIR_TIMEOUT_MS  = 5_000;   // 5秒取消配对超时

    private final Context   context;
    private final BluetoothAdapter btAdapter;
    private final Handler   handler = new Handler(Looper.getMainLooper());
    private final Callback  callback;

    // 配置参数
    private String filterName    = "";
    private String filterAddress = "";
    private int    targetLoops   = 0; // 0=无限循环

    // 状态
    private volatile int              state = STATE_IDLE;
    private volatile boolean          running = false;
    private          BluetoothDevice  targetDevice = null;
    private          BluetoothA2dp    a2dpProxy = null;
    private          int              currentLoop = 0;
    private          long             loopStartTime = 0;

    // 统计
    private final TestStatistics statistics = new TestStatistics();

    // BroadcastReceiver：监听所有蓝牙事件
    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case BluetoothDevice.ACTION_FOUND:
                    onDeviceFound(intent);
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    onDiscoveryFinished();
                    break;
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    onBondStateChanged(intent);
                    break;
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                    onA2dpStateChanged(intent);
                    break;
                case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                    onAclDisconnected(intent);
                    break;
            }
        }
    };

    /** 状态回调接口（通知UI） */
    public interface Callback {
        void onLoopStart(int loop, int total);
        void onStateChange(String stateDesc);
        void onLoopSuccess(int loop, long costMs);
        void onLoopFailure(int loop, TestStatistics.FailReason reason, String detail);
        void onAllDone(TestStatistics stats);
        void onLog(String msg, int type);
    }

    public TestController(Context context, BluetoothAdapter adapter, Callback callback) {
        this.context   = context.getApplicationContext();
        this.btAdapter = adapter;
        this.callback  = callback;
    }

    /*──────────────────────────────
     *  公开控制接口
     *──────────────────────────────*/

    public void setFilter(String name, String address) {
        this.filterName    = (name    != null) ? name.trim()    : "";
        this.filterAddress = (address != null) ? address.trim().toUpperCase() : "";
    }

    public void setTargetLoops(int loops) {
        this.targetLoops = loops;
    }

    public TestStatistics getStatistics() { return statistics; }

    /** 开始压测 */
    public void start() {
        if (running) return;
        running = true;
        currentLoop = 0;
        statistics.reset();
        statistics.start();
        registerReceiver();
        getA2dpProxy();
        nextLoop();
    }

    /** 停止压测 */
    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stopDiscovery();
        state = STATE_IDLE;
        unregisterReceiver();
        closeA2dpProxy();
        callback.onAllDone(statistics);
    }

    /*──────────────────────────────
     *  内部流程
     *──────────────────────────────*/

    private void nextLoop() {
        if (!running) return;
        if (targetLoops > 0 && currentLoop >= targetLoops) {
            // 达到目标次数，压测结束
            stop();
            return;
        }
        currentLoop++;
        loopStartTime = System.currentTimeMillis();
        targetDevice = null;

        callback.onLoopStart(currentLoop, targetLoops);
        log("========== 第 " + currentLoop + " 轮开始 ==========", LogAdapter.TYPE_INFO);
        startScanning();
    }

    // -------- Step 1: 扫描 --------

    private void startScanning() {
        setState("扫描中...");
        state = STATE_SCANNING;
        if (btAdapter.isDiscovering()) btAdapter.cancelDiscovery();
        btAdapter.startDiscovery();
        log("开始扫描蓝牙设备...", LogAdapter.TYPE_INFO);
        scheduleTimeout(SCAN_TIMEOUT_MS, () -> {
            if (state == STATE_SCANNING) {
                log("扫描超时，未找到目标设备", LogAdapter.TYPE_FAILURE);
                stopDiscovery();
                failLoop(TestStatistics.FailReason.SCAN_TIMEOUT, "扫描超时");
            }
        });
    }

    private void onDeviceFound(Intent intent) {
        if (state != STATE_SCANNING) return;
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null) return;

        String name = "";
        try { name = device.getName(); } catch (SecurityException ignored) {}
        String address = device.getAddress();
        if (name == null) name = "";

        log("发现设备: " + name + " [" + address + "]", LogAdapter.TYPE_INFO);

        // 匹配过滤条件（名称 或 地址，任一匹配即可）
        boolean matchByName    = !filterName.isEmpty()    && name.contains(filterName);
        boolean matchByAddress = !filterAddress.isEmpty() && address.equalsIgnoreCase(filterAddress);
        boolean filterEmpty    = filterName.isEmpty() && filterAddress.isEmpty();

        if (filterEmpty || matchByName || matchByAddress) {
            targetDevice = device;
            handler.removeCallbacksAndMessages(null); // 取消扫描超时
            stopDiscovery();
            log("匹配目标设备: " + name + " [" + address + "]", LogAdapter.TYPE_WARNING);
            startBonding();
        }
    }

    private void onDiscoveryFinished() {
        if (state == STATE_SCANNING) {
            // 扫描结束但未找到目标
            log("扫描完成，未找到目标设备", LogAdapter.TYPE_FAILURE);
            failLoop(TestStatistics.FailReason.SCAN_TIMEOUT, "扫描完成未找到设备");
        }
    }

    // -------- Step 2: 配对 --------

    private void startBonding() {
        if (targetDevice == null || !running) return;
        setState("配对中...");
        state = STATE_BONDING;
        log("发起配对请求...", LogAdapter.TYPE_INFO);

        boolean result = false;
        try { result = targetDevice.createBond(); }
        catch (SecurityException e) { log("createBond权限异常: " + e.getMessage(), LogAdapter.TYPE_FAILURE); }

        if (!result) {
            // 可能已经在配对列表中，直接检查
            int bondState = BluetoothDevice.BOND_NONE;
            try { bondState = targetDevice.getBondState(); } catch (SecurityException ignored) {}
            if (bondState == BluetoothDevice.BOND_BONDED) {
                log("设备已配对，直接进入连接阶段", LogAdapter.TYPE_WARNING);
                startConnecting();
                return;
            }
            log("createBond() 返回false", LogAdapter.TYPE_FAILURE);
            failLoop(TestStatistics.FailReason.BOND_FAILED, "createBond返回false");
            return;
        }

        scheduleTimeout(BOND_TIMEOUT_MS, () -> {
            if (state == STATE_BONDING) {
                log("配对超时 (可能 Page Timeout)", LogAdapter.TYPE_FAILURE);
                failLoop(TestStatistics.FailReason.PAGE_TIMEOUT, "配对请求超时");
            }
        });
    }

    private void onBondStateChanged(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || targetDevice == null) return;
        if (!device.getAddress().equalsIgnoreCase(targetDevice.getAddress())) return;

        int newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
        int reason   = intent.getIntExtra("android.bluetooth.device.extra.REASON", -1);

        switch (newState) {
            case BluetoothDevice.BOND_BONDING:
                log("配对进行中...", LogAdapter.TYPE_INFO);
                break;

            case BluetoothDevice.BOND_BONDED:
                if (state == STATE_BONDING) {
                    handler.removeCallbacksAndMessages(null);
                    log("配对成功!", LogAdapter.TYPE_SUCCESS);
                    startConnecting();
                } else if (state == STATE_UNPAIRING) {
                    // 配对状态未变为NONE，取消配对失败
                    failLoop(TestStatistics.FailReason.UNPAIR_FAILED, "取消配对后状态仍为BONDED");
                }
                break;

            case BluetoothDevice.BOND_NONE:
                if (state == STATE_BONDING) {
                    handler.removeCallbacksAndMessages(null);
                    // 解析失败原因
                    String reasonStr = decodeBondFailReason(reason);
                    TestStatistics.FailReason failReason = isPageTimeout(reason)
                            ? TestStatistics.FailReason.PAGE_TIMEOUT
                            : TestStatistics.FailReason.BOND_FAILED;
                    log("配对失败! 原因: " + reasonStr + " (code=" + reason + ")", LogAdapter.TYPE_FAILURE);
                    failLoop(failReason, reasonStr);
                } else if (state == STATE_UNPAIRING) {
                    handler.removeCallbacksAndMessages(null);
                    log("取消配对成功，设备已从配对列表移除", LogAdapter.TYPE_SUCCESS);
                    finishLoop(true);
                }
                break;
        }
    }

    /** 判断是否 Page Timeout（HCI错误码0x04） */
    private boolean isPageTimeout(int reason) {
        // Android内部reason码：
        // UNBOND_REASON_AUTH_FAILED=1, PAGE_TIMEOUT (HCI 0x04 → reason=6 or conn_timeout相关)
        // 不同AOSP版本reason码略有差异，常见Page Timeout为 reason=6（AUTH_TIMEOUT）
        return reason == 6 || reason == 14; // 14=HCI_ERR_PAGE_TIMEOUT in some ROMs
    }

    private String decodeBondFailReason(int reason) {
        switch (reason) {
            case 1:  return "Authentication Failed";
            case 2:  return "Authentication Cancelled";
            case 3:  return "Authentication Timeout";
            case 4:  return "Auto Pairing Failed";
            case 5:  return "Wrong PIN";
            case 6:  return "Page Timeout (AUTH_TIMEOUT)";
            case 7:  return "Remote Device Down";
            case 8:  return "Discovery Failed";
            case 9:  return "Pairing User Rejected";
            case 10: return "Remote Auth Cancelled";
            case 11: return "Discovery In Progress";
            case 12: return "Connection Failed";
            case 13: return "Repeated Attempts";
            case 14: return "Page Timeout (HCI)";
            default: return "Unknown Reason(" + reason + ")";
        }
    }

    // -------- Step 3: 等待A2DP/RFCOMM连接 --------

    private void startConnecting() {
        if (!running) return;
        setState("等待A2DP连接...");
        state = STATE_CONNECTING;
        log("等待A2DP Profile自动连接...", LogAdapter.TYPE_INFO);

        // 部分手机需要主动触发A2DP连接
        if (a2dpProxy != null && targetDevice != null) {
            try {
                Method m = BluetoothA2dp.class.getDeclaredMethod("connect", BluetoothDevice.class);
                m.setAccessible(true);
                m.invoke(a2dpProxy, targetDevice);
                log("已调用A2dp.connect()", LogAdapter.TYPE_INFO);
            } catch (Exception e) {
                log("A2dp.connect()调用失败: " + e.getMessage(), LogAdapter.TYPE_WARNING);
            }
        }

        scheduleTimeout(CONNECT_TIMEOUT_MS, () -> {
            if (state == STATE_CONNECTING) {
                log("A2DP连接超时", LogAdapter.TYPE_FAILURE);
                failLoop(TestStatistics.FailReason.CONNECT_TIMEOUT, "等待A2DP连接超时");
            }
        });
    }

    private void onA2dpStateChanged(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || targetDevice == null) return;
        if (!device.getAddress().equalsIgnoreCase(targetDevice.getAddress())) return;

        int newState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        log("A2DP状态变化: " + a2dpStateStr(newState), LogAdapter.TYPE_INFO);

        if (newState == BluetoothProfile.STATE_CONNECTED && state == STATE_CONNECTING) {
            handler.removeCallbacksAndMessages(null);
            log("A2DP连接成功!", LogAdapter.TYPE_SUCCESS);
            startDisconnecting();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED && state == STATE_DISCONNECTING) {
            handler.removeCallbacksAndMessages(null);
            log("A2DP已断开，开始取消配对...", LogAdapter.TYPE_INFO);
            startUnpairing();
        }
    }

    private String a2dpStateStr(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:    return "CONNECTED";
            case BluetoothProfile.STATE_CONNECTING:   return "CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTED: return "DISCONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING:return "DISCONNECTING";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    // -------- Step 4: 断开连接 --------

    private void startDisconnecting() {
        if (!running) return;
        setState("断开A2DP连接...");
        state = STATE_DISCONNECTING;
        log("主动断开A2DP连接...", LogAdapter.TYPE_INFO);

        if (a2dpProxy != null && targetDevice != null) {
            try {
                Method m = BluetoothA2dp.class.getDeclaredMethod("disconnect", BluetoothDevice.class);
                m.setAccessible(true);
                m.invoke(a2dpProxy, targetDevice);
            } catch (Exception e) {
                log("A2dp.disconnect()调用失败: " + e.getMessage(), LogAdapter.TYPE_WARNING);
                // 直接进下一步
                startUnpairing();
                return;
            }
        } else {
            startUnpairing();
            return;
        }

        scheduleTimeout(DISC_TIMEOUT_MS, () -> {
            if (state == STATE_DISCONNECTING) {
                log("断开A2DP超时，强制进入取消配对", LogAdapter.TYPE_WARNING);
                startUnpairing();
            }
        });
    }

    private void onAclDisconnected(Intent intent) {
        // ACL 断开作为备用检测
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || targetDevice == null) return;
        if (!device.getAddress().equalsIgnoreCase(targetDevice.getAddress())) return;
        if (state == STATE_DISCONNECTING) {
            handler.removeCallbacksAndMessages(null);
            log("ACL已断开，开始取消配对...", LogAdapter.TYPE_INFO);
            startUnpairing();
        }
    }

    // -------- Step 5: 取消配对 --------

    private void startUnpairing() {
        if (!running) return;
        setState("取消配对...");
        state = STATE_UNPAIRING;
        log("开始取消配对(removeBond)...", LogAdapter.TYPE_INFO);

        boolean result = removeBond(targetDevice);
        if (!result) {
            log("removeBond()返回false", LogAdapter.TYPE_FAILURE);
            failLoop(TestStatistics.FailReason.UNPAIR_FAILED, "removeBond返回false");
            return;
        }
        log("removeBond()已调用，等待BOND_NONE...", LogAdapter.TYPE_INFO);

        scheduleTimeout(UNPAIR_TIMEOUT_MS, () -> {
            if (state == STATE_UNPAIRING) {
                // 验证是否真的从配对列表移除
                boolean stillBonded = isBonded(targetDevice);
                if (!stillBonded) {
                    log("取消配对成功(超时检查)", LogAdapter.TYPE_SUCCESS);
                    finishLoop(true);
                } else {
                    log("取消配对失败，设备仍在配对列表", LogAdapter.TYPE_FAILURE);
                    failLoop(TestStatistics.FailReason.UNPAIR_FAILED, "取消配对后设备仍在配对列表");
                }
            }
        });
    }

    /** 反射调用隐藏API removeBond */
    private boolean removeBond(BluetoothDevice device) {
        if (device == null) return false;
        try {
            Method m = BluetoothDevice.class.getMethod("removeBond");
            return (Boolean) m.invoke(device);
        } catch (Exception e) {
            log("removeBond反射调用异常: " + e.getMessage(), LogAdapter.TYPE_FAILURE);
            return false;
        }
    }

    private boolean isBonded(BluetoothDevice device) {
        if (device == null) return false;
        try {
            return device.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException e) {
            return false;
        }
    }

    /*──────────────────────────────
     *  循环结束处理
     *──────────────────────────────*/

    private void finishLoop(boolean success) {
        long costMs = System.currentTimeMillis() - loopStartTime;
        if (success) {
            statistics.recordSuccess();
            callback.onLoopSuccess(currentLoop, costMs);
            log("▶ 第" + currentLoop + "轮 【成功】 耗时" + costMs + "ms", LogAdapter.TYPE_SUCCESS);
        }
        state = STATE_IDLE;
        // 稍微延迟再开始下一轮，让系统稳定
        handler.postDelayed(this::nextLoop, 1500);
    }

    private void failLoop(TestStatistics.FailReason reason, String detail) {
        long costMs = System.currentTimeMillis() - loopStartTime;
        statistics.recordFailure(reason);
        callback.onLoopFailure(currentLoop, reason, detail);
        log("▶ 第" + currentLoop + "轮 【失败】 原因:" + reason.desc + " 耗时" + costMs + "ms",
                LogAdapter.TYPE_FAILURE);

        // 清理状态再开始下一轮
        state = STATE_IDLE;
        handler.removeCallbacksAndMessages(null);
        stopDiscovery();
        // 尝试清理配对关系
        if (targetDevice != null && isBonded(targetDevice)) {
            removeBond(targetDevice);
        }
        handler.postDelayed(this::nextLoop, 2000);
    }

    /*──────────────────────────────
     *  工具方法
     *──────────────────────────────*/

    private void setState(String desc) {
        callback.onStateChange(desc);
    }

    private void log(String msg, int type) {
        callback.onLog(msg, type);
        Log.d(TAG, msg);
    }

    private Runnable currentTimeoutRunnable = null;

    private void scheduleTimeout(long delayMs, Runnable task) {
        handler.removeCallbacksAndMessages(null);
        currentTimeoutRunnable = task;
        handler.postDelayed(task, delayMs);
    }

    private void stopDiscovery() {
        try {
            if (btAdapter.isDiscovering()) btAdapter.cancelDiscovery();
        } catch (SecurityException ignored) {}
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        context.registerReceiver(btReceiver, filter);
    }

    private void unregisterReceiver() {
        try { context.unregisterReceiver(btReceiver); }
        catch (Exception ignored) {}
    }

    private void getA2dpProxy() {
        try {
            btAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProxy = (BluetoothA2dp) proxy;
                        log("A2DP Profile Proxy 已获取", LogAdapter.TYPE_INFO);
                    }
                }
                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP) a2dpProxy = null;
                }
            }, BluetoothProfile.A2DP);
        } catch (Exception e) {
            log("获取A2DP Proxy失败: " + e.getMessage(), LogAdapter.TYPE_WARNING);
        }
    }

    private void closeA2dpProxy() {
        if (a2dpProxy != null) {
            try { btAdapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProxy); }
            catch (Exception ignored) {}
            a2dpProxy = null;
        }
    }
}
