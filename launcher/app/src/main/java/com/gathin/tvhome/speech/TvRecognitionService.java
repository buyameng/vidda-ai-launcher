package com.gathin.tvhome.speech;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * Android 官方标准 RecognitionService 实现
 * 供 YouTube、SmartTube 等基于 SpeechRecognizer API 的应用直接进行后台语音识别
 */
public class TvRecognitionService extends RecognitionService {
    private static final String TAG = "TvRecognitionService";
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Callback mCurrentCallback = null;
    private String mLastPartialResult = "";

    private final Runnable mSilenceTimeoutRunnable = () -> {
        Log.i(TAG, "TvRecognitionService 静默超时");
        if (mCurrentCallback != null) {
            try {
                if (mLastPartialResult != null && !mLastPartialResult.trim().isEmpty()) {
                    mCurrentCallback.endOfSpeech();
                    Bundle bundle = new Bundle();
                    ArrayList<String> list = new ArrayList<>();
                    list.add(mLastPartialResult.trim());
                    bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list);
                    mCurrentCallback.results(bundle);
                } else {
                    mCurrentCallback.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                }
            } catch (Exception ignored) {}
            mCurrentCallback = null;
        }
        AiVoiceBridgeService.stopExternalListening();
    };

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        Log.i(TAG, "onStartListening 收到语音识别请求");
        mCurrentCallback = listener;
        mLastPartialResult = "";

        AiVoiceBridgeService.start(getApplicationContext());

        try {
            listener.readyForSpeech(new Bundle());
        } catch (Exception e) {
            Log.e(TAG, "readyForSpeech 异常: " + e.getMessage());
        }

        mHandler.removeCallbacks(mSilenceTimeoutRunnable);
        mHandler.postDelayed(mSilenceTimeoutRunnable, 10000);

        AiVoiceBridgeService.setVoiceInputListener(new AiVoiceBridgeService.VoiceInputListener() {
            @Override
            public void onSpeechStart() {
                mHandler.post(() -> {
                    mHandler.removeCallbacks(mSilenceTimeoutRunnable);
                    mHandler.postDelayed(mSilenceTimeoutRunnable, 10000);
                    if (mCurrentCallback != null) {
                        try {
                            mCurrentCallback.beginningOfSpeech();
                        } catch (Exception ignored) {}
                    }
                });
            }

            @Override
            public void onAsrPartial(String text) {
                if (text == null || text.trim().isEmpty()) return;
                mLastPartialResult = text.trim();
                mHandler.post(() -> {
                    mHandler.removeCallbacks(mSilenceTimeoutRunnable);
                    mHandler.postDelayed(mSilenceTimeoutRunnable, 6000);
                    if (mCurrentCallback != null) {
                        try {
                            Bundle bundle = new Bundle();
                            ArrayList<String> list = new ArrayList<>();
                            list.add(text.trim());
                            bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list);
                            mCurrentCallback.partialResults(bundle);
                        } catch (Exception ignored) {}
                    }
                });
            }

            @Override
            public void onAsrFinal(String text) {
                if (text == null || text.trim().isEmpty()) {
                    onError("结果为空");
                    return;
                }
                mLastPartialResult = "";
                mHandler.post(() -> {
                    mHandler.removeCallbacks(mSilenceTimeoutRunnable);
                    if (mCurrentCallback != null) {
                        try {
                            mCurrentCallback.endOfSpeech();
                            Bundle bundle = new Bundle();
                            ArrayList<String> list = new ArrayList<>();
                            list.add(text.trim());
                            bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list);
                            mCurrentCallback.results(bundle);
                        } catch (Exception ignored) {}
                        mCurrentCallback = null;
                    }
                    AiVoiceBridgeService.stopExternalListening();
                });
            }

            @Override
            public void onError(String error) {
                mHandler.post(() -> {
                    mHandler.removeCallbacks(mSilenceTimeoutRunnable);
                    if (mCurrentCallback != null) {
                        try {
                            if (mLastPartialResult != null && !mLastPartialResult.trim().isEmpty()) {
                                Log.i(TAG, "onError 回退使用 partialResult: " + mLastPartialResult);
                                mCurrentCallback.endOfSpeech();
                                Bundle bundle = new Bundle();
                                ArrayList<String> list = new ArrayList<>();
                                list.add(mLastPartialResult.trim());
                                bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list);
                                mCurrentCallback.results(bundle);
                            } else {
                                mCurrentCallback.error(SpeechRecognizer.ERROR_NO_MATCH);
                            }
                        } catch (Exception ignored) {}
                        mCurrentCallback = null;
                    }
                    mLastPartialResult = "";
                    AiVoiceBridgeService.stopExternalListening();
                });
            }
        });

        AiVoiceBridgeService.startExternalListening();
    }

    @Override
    protected void onStopListening(Callback listener) {
        Log.i(TAG, "onStopListening");
        mHandler.removeCallbacks(mSilenceTimeoutRunnable);
        AiVoiceBridgeService.stopExternalListening();
        mCurrentCallback = null;
    }

    @Override
    protected void onCancel(Callback listener) {
        Log.i(TAG, "onCancel");
        mHandler.removeCallbacks(mSilenceTimeoutRunnable);
        AiVoiceBridgeService.stopExternalListening();
        mCurrentCallback = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacks(mSilenceTimeoutRunnable);
        AiVoiceBridgeService.stopExternalListening();
        mCurrentCallback = null;
    }
}
