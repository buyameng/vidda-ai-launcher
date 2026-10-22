package com.gathin.tvhome.speech;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 标准 Android 语音识别弹窗 Activity
 * 响应 android.speech.action.RECOGNIZE_SPEECH 和 android.speech.action.WEB_SEARCH
 * 供 YouTube、SmartTube、各类 TV 应用点击麦克风时调用
 */
public class TvSpeechRecognizeActivity extends Activity implements AiVoiceBridgeService.VoiceInputListener {
    private static final String TAG = "TvSpeechRecognize";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mTvStatus;
    private TextView mTvResult;
    private TextView mTvHint;
    private FrameLayout mRootLayout;
    private LinearLayout mCardLayout;

    private boolean mIsCompleted = false;
    private String mLatestText = "";

    // 10 秒无语音输入自动超时退出
    private final Runnable mTimeoutRunnable = () -> {
        Log.i(TAG, "语音识别超时未收到输入，自动关闭");
        finishWithCancel();
    };

    // 用户说完后停顿 1.4 秒自动提交
    private final Runnable mAutoSubmitRunnable = () -> {
        if (!mLatestText.isEmpty() && !mIsCompleted) {
            Log.i(TAG, "语音识别静默超时自动提交: " + mLatestText);
            finishWithResult(mLatestText);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "TvSpeechRecognizeActivity onCreate");

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }

        buildUi();

        // 确保后台语音服务常驻就绪
        AiVoiceBridgeService.start(this);

        AiVoiceBridgeService.setVoiceInputListener(this);
        AiVoiceBridgeService.startExternalListening();

        mHandler.postDelayed(mTimeoutRunnable, 10000);
    }

    private void buildUi() {
        mRootLayout = new FrameLayout(this);
        mRootLayout.setBackgroundColor(Color.parseColor("#4D000000")); // 轻微遮罩突出当前语音搜索框

        mCardLayout = new LinearLayout(this);
        mCardLayout.setOrientation(LinearLayout.VERTICAL);
        mCardLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        mCardLayout.setPadding(dp(28), dp(22), dp(28), dp(22));

        // 深色半透明高级毛玻璃质感背景
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.parseColor("#F20F172A")); // 深暗 slate
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1.2f), Color.parseColor("#38BDF8")); // 柔和科技蓝微光边框
        mCardLayout.setBackground(bg);

        // 1. 顶部状态栏：麦克风小图标 + 提示文字
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        mTvStatus = new TextView(this);
        mTvStatus.setText("🎙️  正在倾听 · 请对电视或遥控器说话");
        mTvStatus.setTextColor(Color.parseColor("#38BDF8"));
        mTvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mTvStatus.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(mTvStatus);

        mCardLayout.addView(header);

        // 2. 识别内容实时显示区域
        mTvResult = new TextView(this);
        mTvResult.setText("请说出你想搜索的内容...");
        mTvResult.setTextColor(Color.parseColor("#80FFFFFF"));
        mTvResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        mTvResult.setTypeface(Typeface.DEFAULT_BOLD);
        mTvResult.setLineSpacing(dp(4), 1.3f);
        mTvResult.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams resultLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        resultLp.topMargin = dp(16);
        resultLp.bottomMargin = dp(16);
        mCardLayout.addView(mTvResult, resultLp);

        // 3. 底部按键提示
        mTvHint = new TextView(this);
        mTvHint.setText("按【返回键】取消 · 说完后自动搜索");
        mTvHint.setTextColor(Color.parseColor("#66FFFFFF"));
        mTvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mCardLayout.addView(mTvHint);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp(680), ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        cardLp.bottomMargin = dp(60);

        mRootLayout.addView(mCardLayout, cardLp);
        setContentView(mRootLayout);
    }

    @Override
    public void onSpeechStart() {
        mHandler.post(() -> {
            mHandler.removeCallbacks(mTimeoutRunnable);
            mHandler.postDelayed(mTimeoutRunnable, 10000);
            if (mTvStatus != null) {
                mTvStatus.setText("🎙️  正在聆听中 · 说话中...");
                mTvStatus.setTextColor(Color.parseColor("#4ADE80")); // 浅绿拾音指示
            }
        });
    }

    @Override
    public void onAsrPartial(String text) {
        if (text == null || text.trim().isEmpty()) return;
        final String t = text.trim();
        mLatestText = t;
        mHandler.post(() -> {
            mHandler.removeCallbacks(mTimeoutRunnable);
            mHandler.removeCallbacks(mAutoSubmitRunnable);
            // 收到声音输入后，重设 1.5 秒静音检测定时器
            mHandler.postDelayed(mAutoSubmitRunnable, 1500);

            if (mTvResult != null) {
                mTvResult.setText("“" + t + "”");
                mTvResult.setTextColor(Color.parseColor("#FFFFFF"));
            }
        });
    }

    @Override
    public void onAsrFinal(String text) {
        if (text == null || text.trim().isEmpty()) return;
        final String t = text.trim();
        mLatestText = t;
        mHandler.post(() -> {
            mHandler.removeCallbacks(mTimeoutRunnable);
            mHandler.removeCallbacks(mAutoSubmitRunnable);

            if (mTvResult != null) {
                mTvResult.setText("“" + t + "”");
                mTvResult.setTextColor(Color.parseColor("#38BDF8"));
            }
            if (mTvStatus != null) {
                mTvStatus.setText("✓  识别完成，正在搜索");
                mTvStatus.setTextColor(Color.parseColor("#38BDF8"));
            }
            mHandler.postDelayed(() -> finishWithResult(t), 200);
        });
    }

    @Override
    public void onError(String error) {
        Log.w(TAG, "onError: " + error);
        mHandler.post(() -> {
            if (!mLatestText.isEmpty() && !mIsCompleted) {
                Log.i(TAG, "onError 触发但有已识别文本，自动提交: " + mLatestText);
                finishWithResult(mLatestText);
                return;
            }
            if (mTvStatus != null) {
                mTvStatus.setText("未检测到声音 · 请重试");
                mTvStatus.setTextColor(Color.parseColor("#F87171"));
            }
            mHandler.postDelayed(this::finishWithCancel, 1000);
        });
    }

    private synchronized void finishWithResult(String text) {
        if (mIsCompleted) return;
        mIsCompleted = true;
        Log.i(TAG, "finishWithResult: " + text);

        Intent data = new Intent();
        ArrayList<String> results = new ArrayList<>();
        results.add(text);
        data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
        data.putExtra(RecognizerIntent.EXTRA_RESULTS, results);
        setResult(RESULT_OK, data);

        cleanup();
        finish();
    }

    private synchronized void finishWithCancel() {
        if (mIsCompleted) return;
        mIsCompleted = true;
        Log.i(TAG, "finishWithCancel");
        setResult(RESULT_CANCELED);
        cleanup();
        finish();
    }

    private void cleanup() {
        mHandler.removeCallbacks(mTimeoutRunnable);
        mHandler.removeCallbacks(mAutoSubmitRunnable);
        AiVoiceBridgeService.stopExternalListening();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finishWithCancel();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanup();
    }

    private int dp(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
