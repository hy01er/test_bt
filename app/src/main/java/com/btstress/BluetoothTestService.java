package com.btstress;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * 蓝牙压测前台服务
 * 持有WakeLock，防止手机息屏后压测中断
 */
public class BluetoothTestService extends Service {

    private static final String CHANNEL_ID   = "BtStressTest";
    private static final int    NOTIF_ID     = 1001;
    public  static final String ACTION_STOP  = "com.btstress.ACTION_STOP";

    private final IBinder binder = new LocalBinder();
    private PowerManager.WakeLock wakeLock;
    private TestController testController;

    public class LocalBinder extends Binder {
        public BluetoothTestService getService() { return BluetoothTestService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("压测服务运行中..."));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        if (testController != null) testController.stop();
        releaseWakeLock();
        super.onDestroy();
    }

    /** 更新通知栏显示 */
    public void updateNotification(String status) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(status));
    }

    /** 设置TestController（由MainActivity绑定后调用） */
    public void setController(TestController controller) {
        this.testController = controller;
    }

    /*──────────────────────────────
     *  内部工具
     *──────────────────────────────*/

    private Notification buildNotification(String content) {
        Intent stopIntent = new Intent(this, BluetoothTestService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPi = PendingIntent.getActivity(this, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BT压测工具")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(mainPi)
                .addAction(android.R.drawable.ic_delete, "停止", stopPi)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "蓝牙压测", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("蓝牙压测进度通知");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BtStressTest:WakeLock");
            wakeLock.acquire(2 * 60 * 60 * 1000L); // 最长持锁2小时
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
