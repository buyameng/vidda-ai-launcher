package com.gathin.tvhome.speech;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.gathin.tvhome.MainActivity;

/**
 * 开机与待机唤醒广播接收器
 * - 冷启动 (BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / HISENSE_BOOT_COMPLETED)：拉起桌面与语音服务
 * - 待机唤醒 (ACTION_PANEL_ON / USER_PRESENT)：仅保活语音服务，不抢用户正在看的应用
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = (intent != null) ? intent.getAction() : "unknown";
        boolean isColdBoot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || "com.hisense.intent.action.HISENSE_BOOT_COMPLETED".equals(action);
        Log.i(TAG, "监听到系统开机/唤醒广播: " + action + "，coldBoot=" + isColdBoot
                + "，启动 AiVoiceBridgeService" + (isColdBoot ? " 与 tvhome 桌面" : ""));

        AiVoiceBridgeService.start(context);

        // 开机与待机唤醒时静默触发 Karing 自动化连接保底
        try {
            Intent karingIntent = new Intent("com.nebula.karing.action.CONNECT");
            karingIntent.setPackage("com.nebula.karing");
            context.sendBroadcast(karingIntent);
            Log.i(TAG, "已向 Karing 发送后台自动化连接广播");
        } catch (Throwable t) {
            Log.w(TAG, "发送 Karing 自动化广播异常: " + t.getMessage());
        }

        // 仅开机时拉起桌面；待机唤醒回到原应用时绝不强抢焦点
        if (isColdBoot) {
            try {
                Intent homeIntent = new Intent(context, MainActivity.class);
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(homeIntent);
            } catch (Throwable t) {
                Log.e(TAG, "开机拉起桌面失败", t);
            }
        }
    }
}
