package com.btstress;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面 Activity
 * 负责权限申请、UI控制、与压测控制器的数据绑定
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM = 100;

    // UI控件
    private EditText  etDeviceName;
    private EditText  etDeviceAddr;
    private EditText  etLoopCount;
    private Button    btnStart;
    private TextView  tvStatus;
    private TextView  tvSuccess;
    private TextView  tvFail;
    private TextView  tvTotal;
    private TextView  tvRate;
    private TextView  tvElapsed;
    private TextView  tvFailDetail;
    private RecyclerView rvLog;

    // 压测组件
    private BluetoothAdapter    btAdapter;
    private TestController      testController;
    private BluetoothTestService testService;
    private boolean             serviceBound = false;
    private boolean             testing      = false;

    private LogAdapter logAdapter;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // 定时刷新统计数据（每秒）
    private final Runnable statsRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStats();
            if (testing) uiHandler.postDelayed(this, 1000);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            BluetoothTestService.LocalBinder lb = (BluetoothTestService.LocalBinder) binder;
            testService = lb.getService();
            serviceBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    /*──────────────────────────────
     *  生命周期
     *──────────────────────────────*/

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 保持屏幕常亮（压测期间）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initViews();
        initBluetooth();
        requestPermissions();
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    /*──────────────────────────────
     *  初始化
     *──────────────────────────────*/

    private void initViews() {
        etDeviceName = findViewById(R.id.et_device_name);
        etDeviceAddr = findViewById(R.id.et_device_addr);
        etLoopCount  = findViewById(R.id.et_loop_count);
        btnStart     = findViewById(R.id.btn_start);
        tvStatus     = findViewById(R.id.tv_status);
        tvSuccess    = findViewById(R.id.tv_success);
        tvFail       = findViewById(R.id.tv_fail);
        tvTotal      = findViewById(R.id.tv_total);
        tvRate       = findViewById(R.id.tv_rate);
        tvElapsed    = findViewById(R.id.tv_elapsed);
        tvFailDetail = findViewById(R.id.tv_fail_detail);
        rvLog        = findViewById(R.id.rv_log);

        logAdapter = new LogAdapter();
        rvLog.setLayoutManager(new LinearLayoutManager(this));
        rvLog.setAdapter(logAdapter);

        btnStart.setOnClickListener(v -> {
            if (testing) stopTest();
            else startTest();
        });
    }

    private void initBluetooth() {
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            Toast.makeText(this, "该设备不支持蓝牙！", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /*──────────────────────────────
     *  权限处理
     *──────────────────────────────*/

    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            perms = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN
            };
        }
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "需要蓝牙权限才能运行压测！", Toast.LENGTH_LONG).show();
                return;
            }
        }
    }

    /*──────────────────────────────
     *  压测控制
     *──────────────────────────────*/

    private void startTest() {
        if (btAdapter == null || !btAdapter.isEnabled()) {
            Toast.makeText(this, "请先开启蓝牙！", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }

        String name = etDeviceName.getText().toString().trim();
        String addr = etDeviceAddr.getText().toString().trim();
        String loopStr = etLoopCount.getText().toString().trim();

        if (name.isEmpty() && addr.isEmpty()) {
            Toast.makeText(this, "请至少填写设备名称或MAC地址其中一个！", Toast.LENGTH_SHORT).show();
            return;
        }

        int loops = 0;
        if (!loopStr.isEmpty()) {
            try { loops = Integer.parseInt(loopStr); }
            catch (NumberFormatException e) { loops = 0; }
        }

        // 清空日志
        logAdapter.clear();
        testing = true;
        btnStart.setText("停止测试");
        tvStatus.setText("启动中...");

        // 启动前台服务
        Intent serviceIntent = new Intent(this, BluetoothTestService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 创建并启动TestController
        testController = new TestController(this, btAdapter, new TestController.Callback() {
            @Override
            public void onLoopStart(int loop, int total) {
                runOnUiThread(() -> {
                    String s = total > 0 ? "第" + loop + "/" + total + "轮" : "第" + loop + "轮";
                    tvStatus.setText("运行中 - " + s);
                    if (serviceBound && testService != null) {
                        testService.updateNotification(s + " - 成功:" +
                                testController.getStatistics().getSuccessCount() +
                                " 失败:" + testController.getStatistics().getFailureCount());
                    }
                });
            }

            @Override
            public void onStateChange(String stateDesc) {
                runOnUiThread(() -> tvStatus.setText(stateDesc));
            }

            @Override
            public void onLoopSuccess(int loop, long costMs) {
                runOnUiThread(() -> refreshStats());
            }

            @Override
            public void onLoopFailure(int loop, TestStatistics.FailReason reason, String detail) {
                runOnUiThread(() -> refreshStats());
            }

            @Override
            public void onAllDone(TestStatistics stats) {
                runOnUiThread(() -> stopTestUi());
            }

            @Override
            public void onLog(String msg, int type) {
                runOnUiThread(() -> {
                    logAdapter.addLog(msg, type);
                    logAdapter.trimIfNeeded(500); // 最多500条
                    // 自动滚动到底部
                    rvLog.scrollToPosition(logAdapter.getItemCount() - 1);
                });
            }
        });

        testController.setFilter(name, addr);
        testController.setTargetLoops(loops);
        testController.start();

        // 启动统计刷新
        uiHandler.post(statsRefreshRunnable);
    }

    private void stopTest() {
        if (testController != null) testController.stop();
        stopTestUi();
    }

    private void stopTestUi() {
        testing = false;
        uiHandler.removeCallbacks(statsRefreshRunnable);
        btnStart.setText("开始测试");
        tvStatus.setText("已停止");
        refreshStats();

        // 停止服务
        Intent serviceIntent = new Intent(this, BluetoothTestService.class);
        stopService(serviceIntent);
        if (serviceBound) {
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
            serviceBound = false;
        }
    }

    private void refreshStats() {
        if (testController == null) return;
        TestStatistics s = testController.getStatistics();
        tvSuccess.setText(String.valueOf(s.getSuccessCount()));
        tvFail.setText(String.valueOf(s.getFailureCount()));
        tvTotal.setText(String.valueOf(s.getTotalCount()));
        tvRate.setText(s.getSuccessRate());
        tvElapsed.setText(s.getElapsedTime());
        tvFailDetail.setText(s.getFailureSummary());
    }
}
