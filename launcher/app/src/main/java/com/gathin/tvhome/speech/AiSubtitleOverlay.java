package com.gathin.tvhome.speech;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AiSubtitleOverlay {
    private static final String TAG = "AiSubtitleOverlay";

    public interface OnDismissListener {
        void onDismissRequested();
    }

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private OverlayRootLayout mRootView;
    private TextView mTvStatus;
    private LinearLayout mLayoutQuery;
    private TextView mTvQuery;
    private TextView mTvReply;
    private TextView mTvPrevContext;
    private boolean mIsShowing = false;
    private OnDismissListener mDismissListener;

    private String mPreviousRound = "";
    private static final long OVERLAY_IDLE_TIMEOUT_MS = 30_000L; // 悬浮窗空闲超时（30秒），留足用户思考与追问时间

    private final Runnable mInactivityDismissRunnable = () -> {
        if (mDismissListener != null) {
            mDismissListener.onDismissRequested();
        } else {
            dismiss(true);
        }
    };

    /**
     * 自定义根布局：拦截遥控器【返回键】，消费事件并通知 Service 结束对话
     */
    public class OverlayRootLayout extends LinearLayout {
        public OverlayRootLayout(Context context) {
            super(context);
            setOrientation(LinearLayout.VERTICAL);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    Log.i(TAG, "【返回键拦截】悬浮窗捕获到遥控器返回键，主动退出对话并停止监听！");
                    if (mDismissListener != null) {
                        mDismissListener.onDismissRequested();
                    } else {
                        dismiss(true);
                    }
                }
                return true; // 拦截消费返回键，避免底层全屏播放器误退出
            }
            return super.dispatchKeyEvent(event);
        }
    }

    public AiSubtitleOverlay(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void setOnDismissListener(OnDismissListener listener) {
        mDismissListener = listener;
    }

    private int dp(float dpValue) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dpValue, mContext.getResources().getDisplayMetrics()
        );
    }

    private void ensureView() {
        if (mRootView != null) return;

        OverlayRootLayout root = new OverlayRootLayout(mContext);
        int padH = dp(22);
        int padV = dp(14);
        root.setPadding(padH, padV, padH, padV);

        // 透明干净的极简中性暗色底板（无任何蓝色调，高透光率）
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#8A121212")); // 约 54% 透明度纯正中性黑灰
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1f), Color.parseColor("#24FFFFFF")); // 极细微白描边
        root.setBackground(bg);
        root.setElevation(dp(12));

        // 1. 顶部极简状态栏（去除繁琐徽标与标题，仅保留轻量化状态与退出提示）
        LinearLayout header = new LinearLayout(mContext);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        header.setLayoutParams(headerLp);

        // 状态文字 (自带权重，自动推开右侧退出提示)
        mTvStatus = new TextView(mContext);
        mTvStatus.setText("●  正在思考...");
        mTvStatus.setTextColor(Color.parseColor("#D9FFFFFF"));
        mTvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mTvStatus.setTypeface(Typeface.DEFAULT_BOLD);
        mTvStatus.setShadowLayer(4f, 0f, 1f, 0x80000000);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        );
        header.addView(mTvStatus, statusLp);

        // 遥控器退出提示
        TextView hint = new TextView(mContext);
        hint.setText("按返回键或说“没事了”退出");
        hint.setTextColor(Color.parseColor("#66FFFFFF"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        hint.setShadowLayer(3f, 0f, 1f, 0x80000000);
        header.addView(hint);

        root.addView(header);

        // 2. 上轮上下文占位（默认隐藏，保持界面干净利落）
        mTvPrevContext = new TextView(mContext);
        mTvPrevContext.setVisibility(View.GONE);
        root.addView(mTvPrevContext);

        // 3. 当前提问行（无边框无底色，干净通透）
        mLayoutQuery = new LinearLayout(mContext);
        mLayoutQuery.setOrientation(LinearLayout.HORIZONTAL);
        mLayoutQuery.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        qLp.topMargin = dp(8);

        mTvQuery = new TextView(mContext);
        mTvQuery.setTextColor(Color.parseColor("#B3FFFFFF")); // 柔和雾白，与回答形成干净层次
        mTvQuery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        mTvQuery.setShadowLayer(4f, 0f, 1f, 0x80000000);
        mLayoutQuery.addView(mTvQuery);

        root.addView(mLayoutQuery, qLp);

        // 4. 当前回答文字（纯白高对比度 + 柔和文字阴影确保透明背景下依然清晰易读）
        mTvReply = new TextView(mContext);
        mTvReply.setTextColor(Color.parseColor("#FAFAFA"));
        mTvReply.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mTvReply.setLineSpacing(dp(4), 1.3f);
        mTvReply.setShadowLayer(5f, 0f, 1.5f, 0x99000000);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        aLp.topMargin = dp(5);
        root.addView(mTvReply, aLp);

        mRootView = root;
    }

    private WindowManager.LayoutParams createLayoutParams() {
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }

        int width = dp(780); // 电视端轻巧简洁展示宽度
        // 允许获取返回键事件
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        params.setTitle("AiSubtitleOverlay");
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(30);
        return params;
    }

    private final Runnable mExitDismissRunnable = () -> dismiss(true);

    public void showThinking(String query) {
        mHandler.post(() -> {
            mHandler.removeCallbacks(mInactivityDismissRunnable);
            mHandler.removeCallbacks(mExitDismissRunnable);
            ensureView();

            mTvPrevContext.setVisibility(View.GONE);

            mTvStatus.setText("●  正在思考...");
            mTvStatus.setTextColor(Color.parseColor("#D9FFFFFF"));

            mTvQuery.setText("“" + query + "”");
            mTvReply.setText("思考中...");
            mTvReply.setTextColor(Color.parseColor("#88FFFFFF"));

            attachOrUpdateView();

            // 思考保底超时 30 秒
            mHandler.postDelayed(mInactivityDismissRunnable, 30000);
        });
    }

    public void showReply(String query, String reply) {
        mHandler.post(() -> {
            mHandler.removeCallbacks(mInactivityDismissRunnable);
            mHandler.removeCallbacks(mExitDismissRunnable);
            ensureView();

            mTvStatus.setText("●  正在播报");
            mTvStatus.setTextColor(Color.parseColor("#F2FFFFFF"));

            mTvQuery.setText("“" + query + "”");
            mTvReply.setText(reply);
            mTvReply.setTextColor(Color.parseColor("#FAFAFA"));

            // 记录为历史回顾
            String repSnippet = reply.length() > 30 ? reply.substring(0, 30) + "..." : reply;
            mPreviousRound = query + " → " + repSnippet;

            attachOrUpdateView();

            // 朗读期间保持显示（超时保底）
            int readDurationMs = Math.max(30000, Math.min(120000, reply.length() * 400 + 10000));
            mHandler.postDelayed(mInactivityDismissRunnable, readDurationMs);
        });
    }

    public void showListening() {
        showListening(null);
    }

    public void showListening(String query) {
        mHandler.post(() -> {
            mHandler.removeCallbacks(mInactivityDismissRunnable);
            mHandler.removeCallbacks(mExitDismissRunnable);
            ensureView();

            mTvPrevContext.setVisibility(View.GONE);
            mTvStatus.setText("●  正在倾听 · 请直接说话");
            mTvStatus.setTextColor(Color.parseColor("#FFFFFFFF"));

            if (query != null && !query.trim().isEmpty()) {
                mTvQuery.setText("“" + query.trim() + "”");
            } else if (!mIsShowing) {
                mTvQuery.setText("“你好小智”");
            }
            mTvReply.setText("在呢，请问有什么可以帮您？");
            mTvReply.setTextColor(Color.parseColor("#B3FFFFFF"));

            attachOrUpdateView();

            // 倾听状态持续无语音输入则自动触发超时退出
            mHandler.postDelayed(mInactivityDismissRunnable, OVERLAY_IDLE_TIMEOUT_MS);
        });
    }

    public void notifyTtsFinished() {
        mHandler.post(() -> {
            if (!mIsShowing || mRootView == null) return;
            mHandler.removeCallbacks(mInactivityDismissRunnable);
            mHandler.removeCallbacks(mExitDismissRunnable);

            mTvStatus.setText("●  播报完毕 · 可随时追问");
            mTvStatus.setTextColor(Color.parseColor("#E6FFFFFF"));

            // 播报完毕后保持显示并等待追问，超时无操作则自动退出
            mHandler.postDelayed(mInactivityDismissRunnable, OVERLAY_IDLE_TIMEOUT_MS);
        });
    }

    public void showExit() {
        mHandler.post(() -> {
            mHandler.removeCallbacks(mInactivityDismissRunnable);
            mHandler.removeCallbacks(mExitDismissRunnable);
            if (!mIsShowing || mRootView == null) return;

            mTvStatus.setText("●  好的，随时叫我");
            mTvStatus.setTextColor(Color.parseColor("#99FFFFFF"));

            mHandler.postDelayed(mExitDismissRunnable, 1500);
        });
    }

    private void attachOrUpdateView() {
        if (mRootView == null) return;
        // 必须先清空 listener 再 cancel，否则 cancel() 会同步触发 onAnimationEnd 导致视图被移除置空
        mRootView.animate().setListener(null);
        mRootView.animate().cancel();
        mRootView.setTranslationY(0f);
        mRootView.setAlpha(1.0f);
        mRootView.setVisibility(View.VISIBLE);

        if (!mIsShowing) {
            try {
                mWindowManager.addView(mRootView, createLayoutParams());
                mIsShowing = true;
                Log.i(TAG, "【成功】全新毛玻璃全局悬浮卡片已成功挂载！");
            } catch (Exception e) {
                Log.e(TAG, "挂载悬浮卡片异常: " + e.getMessage(), e);
            }
        } else {
            try {
                mWindowManager.updateViewLayout(mRootView, createLayoutParams());
            } catch (Exception e) {
                Log.w(TAG, "更新悬浮窗布局异常: " + e.getMessage());
            }
        }
    }

    public void dismiss(boolean animated) {
        mHandler.post(() -> {
            mHandler.removeCallbacks(mInactivityDismissRunnable);
            mHandler.removeCallbacks(mExitDismissRunnable);
            if (!mIsShowing && mRootView == null) return;

            final View viewToRemove = mRootView;
            if (viewToRemove == null) {
                mIsShowing = false;
                return;
            }

            if (animated) {
                viewToRemove.animate().setListener(null);
                viewToRemove.animate().cancel();
                viewToRemove.animate()
                        .alpha(0f)
                        .translationY(dp(20))
                        .setDuration(200)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                safelyRemoveView();
                            }

                            @Override
                            public void onAnimationCancel(Animator animation) {
                                safelyRemoveView();
                            }
                        })
                        .start();
                // 300ms 兜底保护：无论系统动画出现任何异常中断，强制移出卡片，绝不卡屏
                mHandler.postDelayed(this::safelyRemoveView, 300);
            } else {
                safelyRemoveView();
            }
        });
    }

    private synchronized void safelyRemoveView() {
        mHandler.removeCallbacks(mInactivityDismissRunnable);
        mHandler.removeCallbacks(mExitDismissRunnable);
        try {
            if (mRootView != null) {
                mRootView.animate().setListener(null);
                mRootView.animate().cancel();
                if (mRootView.getParent() != null || mIsShowing) {
                    mWindowManager.removeViewImmediate(mRootView);
                    Log.i(TAG, "【成功】悬浮卡片已从窗口安全移除");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "safelyRemoveView 异常: " + e.getMessage());
        } finally {
            mIsShowing = false;
            mRootView = null; // 彻底置空，下次显示重新构造干净的视图树
            mTvStatus = null;
            mLayoutQuery = null;
            mTvQuery = null;
            mTvReply = null;
            mTvPrevContext = null;
        }
    }

    public boolean isShowing() {
        return mIsShowing;
    }
}
