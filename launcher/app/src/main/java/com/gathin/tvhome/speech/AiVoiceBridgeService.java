package com.gathin.tvhome.speech;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.gathin.tvhome.R;
import com.gathin.tvhome.TvConfig;
import com.keylab.speech.core.vidaa.aidl.ICoreInterface;
import com.keylab.speech.core.vidaa.aidl.ICoreListener;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 语音桥接服务 (V2.0 屏幕内容识别架构)
 * 核心升级：
 * 1. 唤醒瞬间触发 ScreenCaptureEngine 静默异步预抓帧，零 UI 遮挡
 * 2. VisionIntentRouter 毫秒级意图分流 (纯文本 / 预抓帧 / 实时补帧)
 * 3. AI 视觉网关多模态通信与“当轮带图、历史留文”
 * 4. 完美融合海信 Vidaa 远场语音引擎底层状态机，支持多轮连续对话与优雅退出
 */
public class AiVoiceBridgeService extends Service {
    private static final String TAG = "AiVoiceBridge";

    public static final String ACTION_TEST_AI = "com.gathin.tvhome.TEST_AI";
    public static final String ACTION_EXIT_AI = "com.gathin.tvhome.ACTION_EXIT_AI";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "ai_voice_bridge_channel";

    // 海信 com.keylab.speech.core.vidaa 官方 AIDL 常量
    private static final int DISMISS_KEYBACK = 0;                 // 触发 returnKey() + FullDuplex.stop(true)，真正关闭拾音！
    private static final int DISMISS_AICORE = 2;                  // 触发 FullDuplex.stop(true)
    private static final int NAVIGATE_START_SPEECH = 14;          // 触发 CoreController.startFarFieldSpeech(1)
    private static final int NAVIGATE_CANCEL_SPEECH = 15;         // 触发 CoreController.cancelFarFieldRec()
    private static final int NAVIGATE_TTS_PLAY = 19;              // 传空字符串触发 CoreController.stopTTS(true)

    private static final int STATE_RECORD_START = 0;              // 近场/遥控器语音键开始录音
    private static final int STATE_RECORD_VOLUME = 1;             // 录音音量变化
    private static final int STATE_RECORD_END = 2;                // 录音结束
    private static final int STATE_COMPLETED = 5;                 // 语音会话完成/退出
    private static final int STATE_ERROR = 6;                     // 识别异常
    private static final int STATE_SPEAK_START = 7;               // 系统 TTS 开始播报
    private static final int STATE_SPEAK_END = 8;                 // 系统 TTS 播报结束
    private static final int STATE_MICARRAY_NOINPUT_STOP = 12;    // 远场阵列无输入自动停止
    private static final int STATE_RECORD_START_MICARRAY = 20;    // 远场唤醒词唤醒并开始拾音
    private static final int STATE_RECORD_START_FULLDUPLEX = 30;  // 全双工唤醒开始拾音
    private static final int STATE_RECORD_STOP_FULLDUPLEX = 31;   // 全双工停止

    // 退出后防残余语音/防 workByTtsSpeakEnd 自动重开拾音的冷却时间窗口 (毫秒)
    private static final long EXIT_COOLDOWN_MS = 2800L;

    private ICoreInterface mCoreInterface;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mLogExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mWorkerExecutor = Executors.newSingleThreadExecutor();
    private boolean mIsBinding = false;

    private long mLastProcessedTime = 0;
    private String mLastProcessedQuery = "";
    private volatile boolean mIsProcessing = false;
    private volatile boolean mIsConversationActive = false;
    private volatile boolean mHasPreCapturedForTurn = false;
    private volatile long mLastExitTimeMs = 0;
    private final AtomicInteger mSessionEpoch = new AtomicInteger(0);

    private AiSubtitleOverlay mOverlay;

    private AudioManager mAudioManager;
    private AudioFocusRequest mAudioFocusRequest;
    private volatile boolean mHasAudioFocus = false;

    /**
     * 语音交互时临时获取音频焦点，通知后台视频/音乐播放器暂停
     */
    private synchronized void requestMediaPause() {
        if (mHasAudioFocus) return;
        try {
            if (mAudioManager == null) {
                mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            if (mAudioManager != null) {
                int res;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (mAudioFocusRequest == null) {
                        AudioAttributes attrs = new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build();
                        mAudioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                                .setAudioAttributes(attrs)
                                .setAcceptsDelayedFocusGain(false)
                                .setOnAudioFocusChangeListener(focusChange -> {
                                    writeLog("【音频焦点状态】focusChange=" + focusChange);
                                })
                                .build();
                    }
                    res = mAudioManager.requestAudioFocus(mAudioFocusRequest);
                } else {
                    res = mAudioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                }
                if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    mHasAudioFocus = true;
                    writeLog("【音频焦点】已成功获取音频焦点，后台媒体自动暂停");
                }
            }
        } catch (Throwable t) {
            writeLog("【音频焦点】请求异常 (安全忽略): " + t.getMessage());
        }
    }

    /**
     * 语音交互结束或退出时释放音频焦点，允许后台视频/音乐恢复播放
     */
    private synchronized void releaseMediaPause() {
        if (!mHasAudioFocus) return;
        mHasAudioFocus = false;
        try {
            if (mAudioManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (mAudioFocusRequest != null) {
                        mAudioManager.abandonAudioFocusRequest(mAudioFocusRequest);
                    }
                } else {
                    mAudioManager.abandonAudioFocus(null);
                }
                writeLog("【音频焦点】已释放音频焦点，后台媒体恢复播放");
            }
        } catch (Throwable t) {
            writeLog("【音频焦点】释放异常 (安全忽略): " + t.getMessage());
        }
    }

    private static volatile AiVoiceBridgeService sInstance = null;

    public interface VoiceInputListener {
        void onSpeechStart();
        void onAsrPartial(String text);
        void onAsrFinal(String text);
        void onError(String error);
    }

    private static volatile VoiceInputListener sVoiceInputListener = null;

    public static void setVoiceInputListener(VoiceInputListener listener) {
        sVoiceInputListener = listener;
    }

    public static boolean isExternalInputMode() {
        return sVoiceInputListener != null;
    }

    public static void startExternalListening() {
        if (sInstance != null) {
            sInstance.startExternalListeningInternal();
        }
    }

    public static void stopExternalListening() {
        if (sInstance != null) {
            sInstance.stopExternalListeningInternal();
        }
    }

    public void startExternalListeningInternal() {
        writeLog("【开启外部应用语音拾音】");
        mHandler.removeCallbacks(mListeningTimeoutRunnable);
        mHandler.removeCallbacks(mRestartListenRunnable);
        stopSystemTtsOnly();
        if (mCoreInterface != null) {
            try {
                mCoreInterface.onSetting("chatgpt_tts_end", "1");
                mCoreInterface.onNavigate(NAVIGATE_START_SPEECH, "no_ding", "");
                mCoreInterface.onShow(231);
            } catch (Exception e) {
                writeLog("startExternalListeningInternal 异常: " + e.getMessage());
            }
        } else {
            writeLog("mCoreInterface 为空，重新发起绑定...");
            bindSpeechCore();
        }
    }

    public void stopExternalListeningInternal() {
        writeLog("【关闭外部应用语音拾音】");
        stopSpeechCoreListening();
        sVoiceInputListener = null;
    }

    // =========================================================================
    // 静态预编译正则常量（启动单次编译，彻底消除高频语音识别过程中的重复 compile 损耗与 GC 堆积）
    // =========================================================================

    // 标点与空白字符快速清理
    private static final Pattern PATTERN_PUNCT_AND_SPACE = Pattern.compile("[，。？！、,\\.?!\\s]");

    // 唤醒词剥离相关正则
    private static final Pattern PATTERN_WAKE_COMPOUND = Pattern.compile("(?i)^(你好|海信|嗨|hi|hello)[，,\\s]*(小智|大萌|小聚)");
    private static final Pattern PATTERN_WAKE_REPEATED = Pattern.compile("(?i)^(小智小智|大萌大萌|小聚小聚|海信电视)");
    private static final Pattern PATTERN_WAKE_SINGLE = Pattern.compile("^(小智|大萌|小聚)");

    // 唤醒词开头判断正则
    private static final Pattern PATTERN_STARTS_WAKE_1 = Pattern.compile("(?i)^(你好|海信|嗨|hi|hello)[，,\\s]*(小智|大萌|小聚).*");
    private static final Pattern PATTERN_STARTS_WAKE_2 = Pattern.compile("(?i)^(小智小智|大萌大萌|小聚小聚|海信电视).*");
    private static final Pattern PATTERN_STARTS_WAKE_3 = Pattern.compile("(?i)^(小智|大萌|小聚).*");

    // 纯唤醒词判断正则
    private static final Pattern PATTERN_PURE_WAKE_1 = Pattern.compile("^(你好|海信|嗨|hi|hello)?(小智|大萌|小聚)$");
    private static final Pattern PATTERN_PURE_WAKE_2 = Pattern.compile("^(小智小智|大萌大萌|小聚小聚|海信电视)$");

    // 氛围灯控制意图正则
    private static final Pattern PATTERN_LIGHT_ALL = Pattern.compile(".*(开灯|开启氛围灯|打开氛围灯|打开背景灯|开启背景灯|打开灯|把灯开开|把灯开了|把灯打开|开启灯光|开下灯|开一下灯|关灯|关闭氛围灯|把灯关了|把灯关掉|关闭背景灯|关掉氛围灯|关掉背景灯|关闭灯光|关下灯|关一下灯|关掉灯|切换氛围灯|切换灯光|开关灯|电影模式|看电影模式|开启电影模式|打开电影模式|观影模式|游戏模式|打游戏模式|开启游戏模式|打开游戏模式|电竞模式).*");
    private static final Pattern PATTERN_LIGHT_ON = Pattern.compile(".*(开灯|开启氛围灯|打开氛围灯|打开背景灯|开启背景灯|打开灯|把灯开开|把灯开了|把灯打开|开启灯光|开下灯|开一下灯).*");
    private static final Pattern PATTERN_LIGHT_OFF = Pattern.compile(".*(关灯|关闭氛围灯|把灯关了|把灯关掉|关闭背景灯|关掉氛围灯|关掉背景灯|关闭灯光|关下灯|关一下灯|关掉灯).*");
    private static final Pattern PATTERN_LIGHT_TOGGLE = Pattern.compile(".*(切换氛围灯|切换灯光|开关灯).*");
    private static final Pattern PATTERN_LIGHT_MOVIE = Pattern.compile(".*(电影模式|看电影模式|开启电影模式|打开电影模式|观影模式).*");
    private static final Pattern PATTERN_LIGHT_GAME = Pattern.compile(".*(游戏模式|打游戏模式|开启游戏模式|打开游戏模式|电竞模式).*");

    // YouTube / 油管 各种常见中文同音误识别词汇库（由海信电视科大讯飞声学模型实测提炼）
    private static final String YT_VARIANTS =
            "(?:" +
            "youtube|u\\s*tube|ytb|油管|游管|有管|邮管|友管|" +
            "有兔[不布步部]?|油兔[不布步部]?|优兔[不布步部]?|又兔[不布步部]?|友兔[不布步部]?|" +
            "有头[部步布]?|油头[部步布]?|由头[部步布]?|优头[部步布]?|" +
            "有多[不布步部]?|优多[不布步部]?|" +
            "有图[不布步部]?|油图[不布步部]?|优图[不布步部]?|又图[不布步部]?|游图[不布步部]?|" +
            "优土[不布步部]?|油土[不布步部]?|有土[不布步部]?|又土[不布步部]?|" +
            "屈有处[不布步部]?|去有处[不布步部]?|有处[不布步部]?|" +
            "又吐[不布步部]?|有吐[不布步部]?|" +
            "u\\s*吐|u\\s*兔|u\\s*土|u\\s*图|" +
            "smarttube" +
            ")";

    // 动作词（包含吸收末尾 b 爆破音的：不看、卟看、步看、部看、康、看等）
    private static final String ACTION_VERBS =
            "(?:搜索|搜一下|搜搜|搜|查找|找一下|找找|找|播放|播|不看|卟看|步看|部看|康|看|打开|点播)";

    private static final Pattern PATTERN_YT_OPEN = Pattern.compile(
            "^(?:(?:请|麻烦)?(?:帮我|替我|给我)?\\s*)?(?:打开|启动|运行|进入|进|去)?\\s*" + YT_VARIANTS + "\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_YT_SEARCH_1 = Pattern.compile(
            "^(?:(?:去|在|到|用|帮我|请|打开|我想|想|给我|让|麻烦)+)?\\s*(?:在|到|用|去)?\\s*"
            + YT_VARIANTS +
            "\\s*(?:上|里|里面)?\\s*(?:的)?\\s*(?:" + ACTION_VERBS + ")?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_YT_SEARCH_2 = Pattern.compile(
            "^(?:(?:去|在|到|用|帮我|请|打开|我想|想|给我)+)?\\s*(?:" + ACTION_VERBS + ")\\s*"
            + YT_VARIANTS +
            "\\s*(?:上|里|里面|的)?\\s*(?:的)?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    // 夸克网盘直接打开指令（支持打开夸克、打开夸克网盘、帮我打开夸克等）
    private static final Pattern PATTERN_QUARK_OPEN = Pattern.compile(
            "^(?:(?:请|麻烦)?(?:帮我|替我|给我)?\\s*)?(?:打开|启动|运行|进入|进|去)?\\s*(?:夸克网盘|夸克|quark|quark\\s*tv)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    // 媒体中心直接打开指令（支持打开媒体中心、进入本地媒体等）
    private static final Pattern PATTERN_MEDIACENTER_OPEN = Pattern.compile(
            "^(?:(?:请|麻烦)?(?:帮我|替我|给我)?\\s*)?(?:打开|启动|运行|进入|进|去)?\\s*(?:媒体中心|本地媒体|usb媒体)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    // 全局通用视频搜索指令（帮我找xx、帮我搜xx、搜索xx、搜xx、找xx）
    private static final Pattern PATTERN_GLOBAL_SEARCH = Pattern.compile(
            "^(?:(?:请|麻烦)?(?:帮我|替我|给我)?\\s*(?:去|在)?\\s*)?(?:搜索|搜一下|搜搜|搜|查找|找一下|找找|找)\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_BILI_OPEN = Pattern.compile(
            "^(?:(?:请|麻烦)?(?:帮我|替我|给我)?\\s*)?(?:打开|启动|运行|进入|进|去)?\\s*(?:b站|哔哩哔哩)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_BILI_SEARCH_1 = Pattern.compile(
            "^(?:(?:去|在|到|用|帮我|请|打开|我想|想|给我|让|麻烦)+)?\\s*(?:在|到|用|去)?\\s*(?:b站|哔哩哔哩)\\s*(?:上|里|里面)?\\s*(?:的)?\\s*(?:搜索|搜一下|搜搜|搜|查找|找一下|找找|找|播放|播|看|打开)?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_BILI_SEARCH_2 = Pattern.compile(
            "^(?:(?:去|在|到|用|帮我|请|打开|我想|想|给我)+)?\\s*(?:搜索|搜一下|搜搜|搜|查找|找一下|找找|找|播放|播|看)\\s*(?:b站|哔哩哔哩)\\s*(?:上|里|里面|的)?\\s*(?:的)?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_GENERIC_SEARCH = Pattern.compile(
            "^(?:搜索|搜一下|搜搜|搜|查找|找一下|找找|找|播放|播|看)(.+)$"
    );

    private static final Pattern PATTERN_SEARCH_PREFIX = Pattern.compile(
            "^(?:搜索|搜一下|搜搜|搜|查找|找一下|找找|找|播放|播|看)\\s*"
    );

    private static final Pattern PATTERN_ACTION_KW_PREFIX = Pattern.compile(
            "^(?:一下|搜索|搜|看|不看|卟看|步看|部看|康|播放|找|点播)\\s*"
    );

    private static final Pattern PATTERN_TRAILING_PUNCT = Pattern.compile(
            "[，。？！、,\\.?!\\s]+$"
    );

    // ThreadLocal 日期格式化，线程安全且零 GC 开销
    private static final ThreadLocal<SimpleDateFormat> LOG_DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()));

    private static final List<String> SYSTEM_COMMANDS = Arrays.asList(
            "增大音量", "减小音量", "调大音量", "调小音量", "静音", "取消静音", "关机", "重启"
    );

    // 支持的唤醒词基准词汇表（不区分大小写，支持组合如 "hi，小智"、"hi小智"、"你好小智"、"海信小智" 等）
    private static final List<String> WAKE_WORDS = Arrays.asList(
            "你好小智", "海信小智", "小智小智", "hi小智", "嗨小智", "hello小智", "小智",
            "你好大萌", "大萌大萌", "大萌", "海信小聚", "你好小聚", "海信电视", "小聚小聚", "小聚"
    );

    // 连续对话拾音等待超时（从 TvConfig 集中配置与动态热重载）
    private long getSpeechListenTimeoutMs() {
        return TvConfig.getListenTimeoutMs();
    }
    private volatile long mListenDeadlineMs = 0;

    // 连续对话拾音超时定时器（指定时间内无语音输入自动彻底结束等待）
    private final Runnable mListeningTimeoutRunnable = () -> {
        writeLog("【连续对话超时】" + (getSpeechListenTimeoutMs() / 1000) + "秒未检测到新提问，自动关闭麦克风拾音并隐藏悬浮窗");
        exitConversationAndStopListening();
    };

    // 远场硬件拾音单次切片续航任务（应对海信底层麦克风阵列 5-6 秒硬件超时，保证在 25 秒总等待期内无感连续拾音）
    private final Runnable mRestartListenSliceRunnable = () -> {
        if (!mIsConversationActive || mIsProcessing) return;
        startSpeechCoreListening();
    };

    // 单次切片静音兜底任务：若录音结束 500ms 后仍无 ASR 结果，且在等待期内，自动重开底层拾音
    private final Runnable mCheckSliceSilenceRunnable = () -> {
        if (!mIsConversationActive || mIsProcessing) return;
        long now = SystemClock.elapsedRealtime();
        if (now < mListenDeadlineMs) {
            long remainSec = (mListenDeadlineMs - now) / 1000;
            writeLog("【切片静音续航】底层硬件单次录音结束，当前仍在思考等待期内(剩余 " + remainSec + " 秒)，续航重新开听");
            startSpeechCoreListening();
        } else {
            writeLog("【连续对话超时】已满等待时长，自动结束本轮拾音");
            exitConversationAndStopListening();
        }
    };

    // 连续对话开启下一轮拾音任务
    private final Runnable mRestartListenRunnable = () -> {
        if (!mIsConversationActive) {
            stopSpeechCoreListening();
            return;
        }
        startSpeechCoreListening();
        // 启动连续等待计时：若超时时间内无人说话，自动关闭拾音退出会话
        long timeoutMs = getSpeechListenTimeoutMs();
        mListenDeadlineMs = SystemClock.elapsedRealtime() + timeoutMs;
        mHandler.removeCallbacks(mListeningTimeoutRunnable);
        mHandler.postDelayed(mListeningTimeoutRunnable, timeoutMs);
    };

    // 退出后二次清场任务（防止底层 workByTtsSpeakEnd 在告别语播完后自动重启远场拾音）
    private final Runnable mPostExitStopRunnable = () -> {
        if (!mIsConversationActive) {
            stopSpeechCoreListening();
            releaseMediaPause();
        }
    };

    // 超时看门狗，防止 mIsProcessing 异常卡死导致后续无法提问
    private final Runnable mWatchdogResetProcessing = () -> {
        if (mIsProcessing) {
            writeLog("【超时看门狗】处理超时强制解锁 mIsProcessing");
            mIsProcessing = false;
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (ACTION_TEST_AI.equals(action)) {
                if (intent.hasExtra("asr")) {
                    String asr = intent.getStringExtra("asr");
                    writeLog("收到模拟 ASR 广播: " + asr);
                    try {
                        mCoreListener.onAsrResult(asr, true, 0, 0);
                    } catch (Exception ignored) {}
                    return;
                }
                String query = intent.getStringExtra("query");
                if (query == null || query.isEmpty()) {
                    query = intent.getStringExtra("text");
                }
                if (query != null && !query.isEmpty()) {
                    writeLog("收到手动测试广播: " + query);
                    mIsConversationActive = true;
                    requestMediaPause();
                    processUserQuery(query);
                }
            } else if (ACTION_EXIT_AI.equals(action)) {
                writeLog("收到退出广播 ACTION_EXIT_AI");
                exitConversationAndStopListening();
            }
        }
    };

    private final ICoreListener.Stub mCoreListener = new ICoreListener.Stub() {
        @Override
        public void onSpeechState(int state) throws RemoteException {
            writeLog("onSpeechState: " + state + " (active=" + mIsConversationActive + ")");
            long nowElapsed = SystemClock.elapsedRealtime();

            if (state == STATE_RECORD_START ||
                state == STATE_RECORD_START_MICARRAY ||
                state == STATE_RECORD_START_FULLDUPLEX) {
                // 0 / 20 / 30: 近场或远场唤醒开始拾音
                VoiceInputListener inputListener = sVoiceInputListener;
                if (inputListener != null) {
                    writeLog("【外部语音输入路由】拾音已开启 (state=" + state + ")");
                    inputListener.onSpeechStart();
                    return;
                }
                if (!mIsConversationActive && (nowElapsed - mLastExitTimeMs < EXIT_COOLDOWN_MS)) {
                    writeLog("【拦截误唤醒】退出冷却期内收到 state=" + state + "，立即强制关闭底层拾音！");
                    stopSpeechCoreListening();
                    return;
                }
                if (!mIsConversationActive) {
                    writeLog("【唤醒激活】检测到用户唤醒电视语音 (state=" + state + ")，开启对话会话");
                    mIsConversationActive = true;
                    requestMediaPause();
                }

                // 唤醒瞬间触发静默异步预抓帧（避开自身文字浮层遮挡）
                if (!mHasPreCapturedForTurn) {
                    mHasPreCapturedForTurn = true;
                    writeLog("【唤醒瞬间预抓帧】触发静默异步预抓帧 (state=" + state + ")");
                    ScreenCaptureEngine.triggerPreCapture();
                }

                mHandler.removeCallbacks(mListeningTimeoutRunnable);
                if (mOverlay != null) {
                    mOverlay.showListening();
                }
                // 设定本轮拾音超时时间与保底定时器
                long timeoutMs = getSpeechListenTimeoutMs();
                mListenDeadlineMs = nowElapsed + timeoutMs;
                mHandler.postDelayed(mListeningTimeoutRunnable, timeoutMs);

            } else if (state == STATE_SPEAK_END) {
                // 状态 8: 电视 TTS 朗读结束
                mHasPreCapturedForTurn = false;
                writeLog("TTS 朗读完毕 (state=8), mIsConversationActive=" + mIsConversationActive);
                if (!mIsConversationActive) {
                    writeLog("会话已处于非活跃状态 (如告别语播报完毕)，立即并延时压制底层自动续听");
                    mLastExitTimeMs = SystemClock.elapsedRealtime();
                    stopSpeechCoreListening();
                    mHandler.removeCallbacks(mPostExitStopRunnable);
                    mHandler.postDelayed(mPostExitStopRunnable, 150);
                    mHandler.postDelayed(mPostExitStopRunnable, 600);
                    return;
                }

                if (mOverlay != null) {
                    mOverlay.notifyTtsFinished();
                }

                // 延迟 250ms 开启下一轮连续拾音，避开底层 workByTtsSpeakEnd 尾音
                mHandler.removeCallbacks(mRestartListenRunnable);
                mHandler.postDelayed(mRestartListenRunnable, 250);

            } else if (state == STATE_RECORD_END) {
                // 状态 2: 单次录音切片结束（可能是用户说话完毕，也可能是静音硬件超时）
                writeLog("onSpeechState: RECORD_END (state=2), active=" + mIsConversationActive + ", processing=" + mIsProcessing);
                if (mIsConversationActive && !mIsProcessing) {
                    // 若 500ms 内没有收到 ASR 识别结果进入 processing，说明是静音超时切片，自动续航拾音
                    mHandler.removeCallbacks(mCheckSliceSilenceRunnable);
                    mHandler.postDelayed(mCheckSliceSilenceRunnable, 500);
                }

            } else if (state == STATE_MICARRAY_NOINPUT_STOP) {
                // 状态 12: 远场麦克风阵列超时无语音输入自动停止
                long now = SystemClock.elapsedRealtime();
                writeLog("收到远场单次切片无输入停止 (state=12), active=" + mIsConversationActive + ", processing=" + mIsProcessing);
                if (mIsConversationActive && !mIsProcessing) {
                    if (now < mListenDeadlineMs) {
                        long remainSec = (mListenDeadlineMs - now) / 1000;
                        writeLog("【连续对话续航】仍在思考等待期内(剩余 " + remainSec + " 秒)，续航麦克风拾音以防用户追问思考迟疑");
                        mHandler.removeCallbacks(mCheckSliceSilenceRunnable);
                        mHandler.removeCallbacks(mRestartListenSliceRunnable);
                        mHandler.postDelayed(mRestartListenSliceRunnable, 250);
                    } else {
                        writeLog("【连续对话超时】已满 " + (getSpeechListenTimeoutMs() / 1000) + " 秒无输入，自动结束本轮拾音");
                        exitConversationAndStopListening();
                    }
                }
            }
        }

        @Override
        public void onVolumeChanged(int volume) throws RemoteException {}

        @Override
        public void onError(int errorCode) throws RemoteException {
            writeLog("onError: " + errorCode + " (active=" + mIsConversationActive + ", processing=" + mIsProcessing + ")");
            VoiceInputListener inputListener = sVoiceInputListener;
            if (inputListener != null) {
                inputListener.onError("语音错误: " + errorCode);
                return;
            }

            // 109 (MSP_ERROR_ASR_NO_INPUT), 10114 (TIMEOUT), 10118 (NO_MATCH) 均代表单次硬件切片静音无输入
            if (errorCode == 109 || errorCode == 10114 || errorCode == 10118 || errorCode == 6) {
                if (mIsConversationActive && !mIsProcessing) {
                    long now = SystemClock.elapsedRealtime();
                    if (now < mListenDeadlineMs) {
                        long remainSec = (mListenDeadlineMs - now) / 1000;
                        writeLog("【静音超时续航】底层硬件单次拾音超时 (err=" + errorCode + ")，当前仍在等待期(剩余 " + remainSec + " 秒)，立即续期重新开听");
                        mHandler.removeCallbacks(mCheckSliceSilenceRunnable);
                        mHandler.removeCallbacks(mRestartListenSliceRunnable);
                        mHandler.postDelayed(mRestartListenSliceRunnable, 200);
                        return;
                    } else {
                        writeLog("【连续对话超时】已满 " + (getSpeechListenTimeoutMs() / 1000) + " 秒无输入，自动结束本轮拾音");
                        exitConversationAndStopListening();
                        return;
                    }
                }
            }
        }

        @Override
        public void onAsrResult(String asrResult, boolean isLast, int arg1, int arg2) throws RemoteException {
            writeLog("onAsrResult: [" + asrResult + "], isLast=" + isLast + ", active=" + mIsConversationActive);
            if (asrResult == null || asrResult.trim().isEmpty()) {
                return;
            }
            String trimmed = asrResult.trim();

            VoiceInputListener inputListener = sVoiceInputListener;
            if (inputListener != null) {
                String cleanInput = cleanInAppSearchQuery(trimmed);
                if (cleanInput.isEmpty()) {
                    cleanInput = trimmed;
                }
                writeLog("【外部语音输入路由】收到识别结果: [" + cleanInput + "], isLast=" + isLast);
                if (isLast) {
                    inputListener.onAsrFinal(cleanInput);
                } else {
                    inputListener.onAsrPartial(cleanInput);
                }
                return;
            }

            // 即使还在流式识别途中 (isLast=false)，一旦命中明确的退出指令，也立即响应退出！
            if (isExitCommand(trimmed) || isExitCommand(stripWakeWord(trimmed))) {
                writeLog("onAsrResult 捕获到退出指令 (" + trimmed + ")，立即执行语音退出");
                handleVoiceExit(trimmed);
                return;
            }

            if (!isLast) {
                if (mIsConversationActive) {
                    mHandler.removeCallbacks(mCheckSliceSilenceRunnable);
                    mHandler.removeCallbacks(mRestartListenSliceRunnable);
                    mHandler.removeCallbacks(mListeningTimeoutRunnable);
                    // 识别中途防波动：若流式传输因波动中断未返回 isLast，20秒后自动超时防呆
                    mHandler.postDelayed(mListeningTimeoutRunnable, 20_000L);
                }
                return;
            }
            mHasPreCapturedForTurn = false;
            mHandler.removeCallbacks(mCheckSliceSilenceRunnable);
            mHandler.removeCallbacks(mRestartListenSliceRunnable);
            processUserQuery(trimmed);
        }

        @Override
        public boolean onVerbalCommand(String command) throws RemoteException {
            writeLog("onVerbalCommand: " + command);
            if (handleLightControlCommand(command)) {
                writeLog("onVerbalCommand 拦截并处理氛围灯指令: " + command);
                return true;
            }
            for (String sysCmd : SYSTEM_COMMANDS) {
                if (command != null && command.contains(sysCmd)) {
                    return false; // 放行系统级控制命令
                }
            }
            return mIsConversationActive;
        }

        @Override
        public void onNlpResult(int code, String nlpResult) throws RemoteException {
            writeLog("onNlpResult: code=" + code + ", result=" + nlpResult);
            if (nlpResult == null || nlpResult.isEmpty()) return;
            try {
                if (nlpResult.startsWith("{")) {
                    JSONObject json = new JSONObject(nlpResult);
                    String query = json.optString("query");
                    if (query == null || query.isEmpty()) {
                        JSONObject llmData = json.optJSONObject("llmdata");
                        if (llmData != null) {
                            JSONObject semantic = llmData.optJSONObject("semantic");
                            if (semantic != null) {
                                query = semantic.optString("origin_text");
                            }
                        }
                    }
                    if (query == null || query.isEmpty()) {
                        JSONObject semantic = json.optJSONObject("semantic");
                        if (semantic != null) {
                            query = semantic.optString("origin_text");
                        }
                    }
                    if (query != null && !query.trim().isEmpty()) {
                        writeLog("从 NLP 中成功提取用户原始提问: " + query);
                        mHasPreCapturedForTurn = false;
                        processUserQuery(query.trim());
                    }
                }
            } catch (Exception e) {
                writeLog("解析 nlpResult 异常: " + e.getMessage());
            }
        }

        @Override
        public void onInlineResult(String result, boolean isLast) throws RemoteException {}

        @Override
        public void onExitView() throws RemoteException {
            writeLog("【系统语音回调】onExitView 触发，同步退出对话");
            exitConversationAndStopListening();
        }

        @Override
        public void onCommandExecute(String command, String params) throws RemoteException {
            writeLog("onCommandExecute: " + command + ", params: " + params);
        }

        @Override
        public void onAppInstalled(String pkgName, String action) throws RemoteException {}

        @Override
        public void onBlendShapeData(byte[] data, int length) throws RemoteException {}

        @Override
        public void onSendBsData(byte[] data, int length, int arg1, String arg2) throws RemoteException {}

        @Override
        public void onChatRoomConfig(int config, String params) throws RemoteException {}
    };

    /**
     * 去除开头可能携带的唤醒词前缀（兼容 "hi，小智"、"hi小智"、"Hi，小智"、"你好小智"、"海信小智" 等）
     */
    public static String stripWakeWord(String text) {
        if (text == null) return "";
        String t = text.trim();

        // 1. 正则优先剥离组合式唤醒词: (你好|海信|嗨|hi|hello)[，,\s]*(小智|大萌|小聚)
        t = PATTERN_WAKE_COMPOUND.matcher(t).replaceFirst("").trim();

        // 2. 剥离重复叠词或电视主唤醒词: 小智小智、大萌大萌、小聚小聚、海信电视
        t = PATTERN_WAKE_REPEATED.matcher(t).replaceFirst("").trim();

        // 3. 剥离单个名字开头: 小智、大萌、小聚
        t = PATTERN_WAKE_SINGLE.matcher(t).replaceFirst("").trim();

        // 4. 清理残余开头的标点与空格
        while (!t.isEmpty() && (t.startsWith("，") || t.startsWith(",") || t.startsWith("。") ||
                               t.startsWith("！") || t.startsWith("!") || t.startsWith("？") ||
                               t.startsWith("?") || t.startsWith(" ") || t.startsWith("、"))) {
            t = t.substring(1).trim();
        }
        return t;
    }

    /**
     * 判断文本是否以显式唤醒词开头（不区分大小写，支持如 "hi，小智"、"hi小智"、"你好小智"、"海信小智" 等）
     */
    private boolean startsWithWakeWord(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (PATTERN_STARTS_WAKE_1.matcher(t).matches() ||
            PATTERN_STARTS_WAKE_2.matcher(t).matches() ||
            PATTERN_STARTS_WAKE_3.matcher(t).matches()) {
            return true;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        for (String ww : WAKE_WORDS) {
            if (lower.startsWith(ww)) return true;
        }
        return false;
    }

    /**
     * 判断文本是否仅为一个单纯的唤醒词（不含后续提问问题）
     */
    private boolean isPureWakeWord(String text) {
        if (text == null) return false;
        String stripped = stripWakeWord(text);
        if (!stripped.isEmpty()) {
            return false;
        }
        String norm = PATTERN_PUNCT_AND_SPACE.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
        if (norm.isEmpty()) return false;
        if (PATTERN_PURE_WAKE_1.matcher(norm).matches() ||
            PATTERN_PURE_WAKE_2.matcher(norm).matches() ||
            norm.equals("小智") || norm.equals("大萌") || norm.equals("小聚")) {
            return true;
        }
        for (String ww : WAKE_WORDS) {
            if (norm.equals(ww)) return true;
        }
        return false;
    }

    /**
     * 判断是否为退出指令
     */
    private boolean isExitCommand(String text) {
        if (text == null) return false;
        String t = PATTERN_PUNCT_AND_SPACE.matcher(text).replaceAll("");
        if (t.isEmpty()) return false;

        // 1. 精确匹配短词
        if (t.equals("没事了") || t.equals("没事啦") || t.equals("退下吧") ||
                t.equals("退下") || t.equals("再见") || t.equals("拜拜") ||
                t.equals("不用了") || t.equals("不需要了") || t.equals("没有了") ||
                t.equals("算了吧") || t.equals("算了") || t.equals("闭嘴") ||
                t.equals("关掉") || t.equals("关闭") || t.equals("退出") ||
                t.equals("退出吧") || t.equals("退出对话") || t.equals("结束对话") ||
                t.equals("退出自然对话") || t.equals("小智结束对话") || t.equals("大萌结束对话") ||
                t.equals("停止监听") || t.equals("停止收音") || t.equals("别听了") ||
                t.equals("别说了") || t.equals("出去") || t.equals("怎么才出去") ||
                t.equals("休息吧")) {
            return true;
        }

        // 2. 包含高置信度退出词组
        String[] strongExitPhrases = {
                "没事了", "没事啦", "退下", "结束对话", "退出对话", "退出自然对话",
                "停止监听", "停止收音", "关闭监听", "关闭语音", "退出语音",
                "别听了", "不要听了", "别说了", "不要说了", "闭嘴",
                "不用了", "不需要了", "没别的事", "没有别的事", "没有其他事",
                "先这样吧", "去休息吧", "休息一下吧", "你可以退下了", "随时叫我"
        };
        for (String phrase : strongExitPhrases) {
            if (t.contains(phrase)) {
                return true;
            }
        }

        // 3. 较短口语中包含退出词
        if (t.length() <= 8) {
            if ((t.contains("退出") || t.contains("再见") || t.contains("拜拜") ||
                 t.contains("没有了") || t.contains("算了吧") || t.contains("关掉吧")) &&
                !t.contains("怎么") && !t.contains("如何") && !t.contains("为什么") && !t.contains("什么")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 仅打断系统自带 TTS 播报
     */
    private void stopSystemTtsOnly() {
        if (mCoreInterface != null) {
            try {
                mCoreInterface.onTextToSpeech("");
                mCoreInterface.onNavigate(NAVIGATE_TTS_PLAY, "", "");
            } catch (Exception e) {
                writeLog("stopSystemTtsOnly 异常: " + e.getMessage());
            }
        }
        try {
            sendBroadcast(new Intent("com.hisense.speech.tts.STOP"));
        } catch (Exception ignored) {}
    }

    /**
     * 开启下一轮拾音
     */
    private void startSpeechCoreListening() {
        if (!mIsConversationActive) {
            writeLog("【拒绝开启拾音】当前会话已退出，忽略 startSpeechCoreListening");
            stopSpeechCoreListening();
            return;
        }
        writeLog("【开启拾音】通过 NAVIGATE_START_SPEECH(14) 通知语音核心开启下一轮远场拾音");
        if (mCoreInterface != null) {
            try {
                mCoreInterface.onSetting("chatgpt_tts_end", "1");
                mCoreInterface.onNavigate(NAVIGATE_START_SPEECH, "no_ding", "");
                mCoreInterface.onShow(231);
            } catch (Exception e) {
                writeLog("startSpeechCoreListening 异常: " + e.getMessage());
            }
        }
    }

    /**
     * 彻底停止系统拾音，关闭麦克风与全双工
     */
    private void stopSpeechCoreListening() {
        writeLog("【彻底关闭拾音】调用 onNavigate(15) + onDismiss(0) 终止远场拾音与全双工");
        if (mCoreInterface != null) {
            try {
                mCoreInterface.onSetting("chatgpt_tts_end", "0");
                mCoreInterface.onNavigate(NAVIGATE_CANCEL_SPEECH, "", "");
                mCoreInterface.onDismiss(DISMISS_KEYBACK);
            } catch (Exception e) {
                writeLog("stopSpeechCoreListening 异常: " + e.getMessage());
            }
        }
    }

    /**
     * 语音指令退出
     */
    private synchronized void handleVoiceExit(String rawText) {
        long nowElapsed = SystemClock.elapsedRealtime();
        if (!mIsConversationActive && (nowElapsed - mLastExitTimeMs < 2000L)) {
            writeLog("【忽略重复退出】已在退出流程中: " + rawText);
            stopSpeechCoreListening();
            return;
        }

        writeLog("【语音指令退出】用户说: " + rawText + "，立即关闭会话并停止监听");
        mSessionEpoch.incrementAndGet();
        mIsConversationActive = false;
        mIsProcessing = false;
        mHasPreCapturedForTurn = false;
        mLastExitTimeMs = nowElapsed;

        mHandler.removeCallbacks(mListeningTimeoutRunnable);
        mHandler.removeCallbacks(mWatchdogResetProcessing);
        mHandler.removeCallbacks(mRestartListenRunnable);
        mHandler.removeCallbacks(mRestartListenSliceRunnable);
        mHandler.removeCallbacks(mCheckSliceSilenceRunnable);
        mHandler.removeCallbacks(mPostExitStopRunnable);

        stopSystemTtsOnly();
        stopSpeechCoreListening();

        if (mOverlay != null) {
            mOverlay.showExit();
        }

        // 关键：只有用户明确要求“清空对话”、“重新开始”、“重置”时才清空多轮对话记忆；
        // 用户说“没事了”、“退下”、“再见”、“不用了”等收起指令时，严格保留上下文记忆供后续追问！
        if (rawText != null && (rawText.contains("清空") || rawText.contains("重新开始") || rawText.contains("重置"))) {
            writeLog("【清空记忆】用户明确要求清空记忆/重新开始");
            AiClient.resetConversation();
            ScreenCaptureEngine.clearCache();
        } else {
            writeLog("【保留记忆】用户仅要求收起卡片(" + rawText + ")，保留多轮对话记忆供后续追问");
        }

        mHandler.postDelayed(() -> speakText("好的，随时叫我哦！"), 120);
        mHandler.postDelayed(mPostExitStopRunnable, 2200);
        mHandler.postDelayed(mPostExitStopRunnable, 3200);
    }

    /**
     * 终止对话、停止监听、收起悬浮窗
     */
    public synchronized void exitConversationAndStopListening() {
        writeLog("【收起会话】关闭麦克风收音、收起悬浮窗并释放音频焦点（保留多轮对话上下文以供后续追问）");
        mSessionEpoch.incrementAndGet();
        mIsConversationActive = false;
        mIsProcessing = false;
        mHasPreCapturedForTurn = false;
        mLastExitTimeMs = SystemClock.elapsedRealtime();

        mHandler.removeCallbacks(mListeningTimeoutRunnable);
        mHandler.removeCallbacks(mWatchdogResetProcessing);
        mHandler.removeCallbacks(mRestartListenRunnable);
        mHandler.removeCallbacks(mRestartListenSliceRunnable);
        mHandler.removeCallbacks(mCheckSliceSilenceRunnable);
        mHandler.removeCallbacks(mPostExitStopRunnable);

        stopSystemTtsOnly();
        stopSpeechCoreListening();
        mHandler.postDelayed(mPostExitStopRunnable, 300);

        if (mOverlay != null) {
            mOverlay.dismiss(true);
        }

        releaseMediaPause();
        // 注意：此处不主动清空 AiClient 会话记忆，保留上下文供用户在思考后随时再次追问
        // 仅在用户明确说出退出指令时由 handleVoiceExit() 或超过 10 分钟无互动由 AiClient 自动清理
    }

    private synchronized void processUserQuery(String query) {
        if (query == null) return;
        String rawTrimmed = query.trim();
        if (rawTrimmed.isEmpty()) return;

        if (!mIsConversationActive) {
            mIsConversationActive = true;
            requestMediaPause();
        }

        if (isExitCommand(rawTrimmed)) {
            handleVoiceExit(rawTrimmed);
            return;
        }

        // 1. 判断是否为单纯呼唤唤醒词（如“你好小智”、“hi，小智”、“海信小智”、“小智”等，不带具体提问内容）
        if (isPureWakeWord(rawTrimmed)) {
            writeLog("【单纯唤醒词】唤醒激活并友好回应: " + rawTrimmed);
            mIsConversationActive = true;
            if (mOverlay != null) {
                mOverlay.showListening(rawTrimmed);
            }
            speakText("在呢，请说！");
            return;
        }

        boolean hadExplicitWakeWord = startsWithWakeWord(rawTrimmed);
        String trimmed = stripWakeWord(rawTrimmed);
        if (trimmed.isEmpty()) {
            writeLog("去除唤醒词后提问内容为空，友好回应并等待提问: " + rawTrimmed);
            mIsConversationActive = true;
            if (mOverlay != null) {
                mOverlay.showListening(rawTrimmed);
            }
            speakText("在呢，请说！");
            return;
        }

        if (isExitCommand(trimmed)) {
            handleVoiceExit(trimmed);
            return;
        }

        // 氛围灯控制指令优先级最高，不论是近场按键还是远场指令，直接拦截执行！
        if (handleLightControlCommand(trimmed)) {
            writeLog("检测到氛围灯控制指令，优先执行: " + trimmed);
            mIsProcessing = false;
            mHandler.removeCallbacks(mWatchdogResetProcessing);
            return;
        }

        long nowElapsed = SystemClock.elapsedRealtime();
        if (!mIsConversationActive) {
            if (hadExplicitWakeWord && (nowElapsed - mLastExitTimeMs >= EXIT_COOLDOWN_MS)) {
                writeLog("检测到带唤醒词的提问，激活会话: " + rawTrimmed);
                mIsConversationActive = true;
                requestMediaPause();
            } else {
                writeLog("【会话守门拦截】当前处于已退出/非活跃状态，拒绝响应闲聊语音并强制关闭麦克风: " + trimmed);
                stopSpeechCoreListening();
                return;
            }
        }

        final String cleanText = trimmed;
        long now = System.currentTimeMillis();
        String norm = cleanText.replaceAll("[，。？！、,\\.?!\\s]", "");

        if (norm.equals(mLastProcessedQuery) && (now - mLastProcessedTime < 8000 || mIsProcessing)) {
            writeLog("【防重拦截】忽略短时间内重复请求: " + cleanText);
            return;
        }
        // 通过防重检查后立即记录本条指令：系统命令/应用搜索等提前 return 的路径
        // 也要留下防重指纹，否则海信 ASR 与 onNlpResult 双重投递同一句话时会执行两次。
        mLastProcessedTime = now;
        mLastProcessedQuery = norm;

        for (String sysCmd : SYSTEM_COMMANDS) {
            if (cleanText.equals(sysCmd)) {
                writeLog("检测到系统控制命令，放行: " + cleanText);
                mIsProcessing = false;
                mHandler.removeCallbacks(mWatchdogResetProcessing);
                return;
            }
        }

        if (handleAppOrVideoSearch(cleanText)) {
            writeLog("检测到影视/应用搜索指令，已触发对应应用搜索: " + cleanText);
            mIsProcessing = false;
            mHandler.removeCallbacks(mWatchdogResetProcessing);
            return;
        }

        mIsProcessing = true;
        mIsConversationActive = true;
        final int currentEpoch = mSessionEpoch.incrementAndGet();

        mHandler.removeCallbacks(mListeningTimeoutRunnable);
        mHandler.removeCallbacks(mRestartListenRunnable);
        mHandler.removeCallbacks(mRestartListenSliceRunnable);
        mHandler.removeCallbacks(mCheckSliceSilenceRunnable);

        stopSystemTtsOnly();

        mHandler.removeCallbacks(mWatchdogResetProcessing);
        mHandler.postDelayed(mWatchdogResetProcessing, 15000);

        writeLog("【开始处理提问】(epoch=" + currentEpoch + "): " + cleanText);

        if (mOverlay != null) {
            mOverlay.showThinking(cleanText);
        }

        // 异步分流路由、画面补帧与 AI 网关多模态通信
        mWorkerExecutor.execute(() -> {
            try {
                VisionIntentRouter.RouteMode routeMode = VisionIntentRouter.route(
                        cleanText, ScreenCaptureEngine.getLastCaptureTimeMs()
                );
                writeLog("【视觉路由决策】模式: " + routeMode);

                FrameMemoryEncoder.CapturedFrame frame = null;
                if (routeMode == VisionIntentRouter.RouteMode.VISION_CACHE_FRAME) {
                    frame = ScreenCaptureEngine.getLatestFrame();
                    if (frame == null) {
                        writeLog("预抓帧缓存为空，执行 1200ms 实时静默补帧");
                        frame = ScreenCaptureEngine.captureFreshFrame(1200);
                    }
                } else if (routeMode == VisionIntentRouter.RouteMode.VISION_FRESH_FRAME) {
                    writeLog("视觉意图需要最新画面，执行 1200ms 实时静默补帧");
                    frame = ScreenCaptureEngine.captureFreshFrame(1200);
                }

                byte[] jpegBytes = null;
                String queryToSend = cleanText;

                if (frame != null && frame.jpegBytes != null) {
                    jpegBytes = frame.jpegBytes;
                    writeLog("【附带屏幕画面】体积: " + jpegBytes.length + " bytes, 黑屏: " + frame.isPureBlack);

                    if (frame.isPureBlack) {
                        writeLog("【DRM防黑屏保护】检测到当前画面全黑，追加系统提示语");
                        queryToSend = "[系统提示：当前视频层受版权加密保护无法直接截屏，请结合画面可见文字或上下文回答] " + cleanText;
                    }
                } else {
                    writeLog("【纯文本模式】不附带屏幕画面直接请求大模型");
                }

                final String finalQueryText = queryToSend;

                AiClient.chat(finalQueryText, cleanText, jpegBytes, new AiClient.Callback() {
                    @Override
                    public void onSuccess(String reply) {
                        mHandler.post(() -> {
                            if (currentEpoch != mSessionEpoch.get() || !mIsConversationActive) {
                                writeLog("【丢弃过期回复】会话已退出或已发起新提问，忽略旧回复: " + reply);
                                return;
                            }
                            mHandler.removeCallbacks(mWatchdogResetProcessing);
                            mIsProcessing = false;
                            writeLog("【AI 网关回复成功】: " + reply);

                            if (mOverlay != null) {
                                mOverlay.showReply(cleanText, reply);
                            }

                            stopSystemTtsOnly();
                            speakText(reply);
                        });
                    }

                    @Override
                    public void onError(String error) {
                        mHandler.post(() -> {
                            if (currentEpoch != mSessionEpoch.get() || !mIsConversationActive) {
                                return;
                            }
                            mHandler.removeCallbacks(mWatchdogResetProcessing);
                            mIsProcessing = false;
                            writeLog("【AI 网关请求失败】: " + error);
                            if (mOverlay != null) {
                                mOverlay.showReply(cleanText, "抱歉，连接 AI 网关失败，请检查网络。");
                            }
                            stopSystemTtsOnly();
                            speakText("抱歉，连接 AI 网关失败，请检查网络。");
                            // 延时优雅退出，确保用户听到提示音与字幕，杜绝瞬时闪退的错觉
                            mHandler.postDelayed(() -> {
                                if (currentEpoch == mSessionEpoch.get() && mIsConversationActive) {
                                    exitConversationAndStopListening();
                                }
                            }, 4000);
                        });
                    }
                });

            } catch (Throwable t) {
                mHandler.post(() -> {
                    mHandler.removeCallbacks(mWatchdogResetProcessing);
                    mIsProcessing = false;
                    writeLog("processUserQuery 异步链路异常: " + t.getMessage());
                    if (mOverlay != null) {
                        mOverlay.dismiss(true);
                    }
                    speakText("抱歉，刚才没有听清楚。");
                    exitConversationAndStopListening();
                });
            }
        });
    }

    // YouTube / 油管 各种常见中文同音误识别词汇库（由海信电视科大讯飞声学模型实测提炼）
    private long mLastLightCommandTimeMs = 0;

    /**
     * 快速检查指令是否命中氛围灯操作
     */
    private boolean isLightControlCommand(String query) {
        if (query == null || query.trim().isEmpty()) return false;
        String t = PATTERN_PUNCT_AND_SPACE.matcher(query).replaceAll("");
        return PATTERN_LIGHT_ALL.matcher(t).matches();
    }

    /**
     * 判断并处理电视氛围灯控制指令（开关 / 关灯 / 电影模式 / 游戏模式）
     * 内置 3 秒防重拦截机制，避免 ASR 流式识别与 onVerbalCommand 连续回调造成一开一关
     */
    private boolean handleLightControlCommand(String query) {
        if (!isLightControlCommand(query)) return false;

        long now = SystemClock.elapsedRealtime();
        if (now - mLastLightCommandTimeMs < 3000L) {
            writeLog("【防重拦截】3秒内已执行过氛围灯指令，吸收底层重复回调: " + query);
            return true;
        }
        mLastLightCommandTimeMs = now;

        String t = PATTERN_PUNCT_AND_SPACE.matcher(query).replaceAll("");
        if (PATTERN_LIGHT_ON.matcher(t).matches()) {
            com.gathin.tvhome.TvLightController.turnOnWithCheck(this);
            speakText("好的，已为您开灯");
            return true;
        }
        if (PATTERN_LIGHT_OFF.matcher(t).matches()) {
            com.gathin.tvhome.TvLightController.turnOffWithCheck(this);
            speakText("好的，已为您关灯");
            return true;
        }
        if (PATTERN_LIGHT_TOGGLE.matcher(t).matches()) {
            com.gathin.tvhome.TvLightController.toggle(this);
            speakText("好的，已切换氛围灯");
            return true;
        }
        if (PATTERN_LIGHT_MOVIE.matcher(t).matches()) {
            com.gathin.tvhome.TvLightController.setMovieMode(this);
            speakText("好的，已切换至电影模式");
            return true;
        }
        if (PATTERN_LIGHT_GAME.matcher(t).matches()) {
            com.gathin.tvhome.TvLightController.setGameMode(this);
            speakText("好的，已切换至游戏模式");
            return true;
        }
        return false;
    }

    /**
     * 判断并处理影视 / 视频应用专项搜索指令（如 YouTube / SmartTube / B站 / 夸克 / 媒体中心）
     */
    private boolean handleAppOrVideoSearch(String query) {
        if (query == null || query.trim().isEmpty()) return false;
        String text = query.trim();

        // 1. 夸克网盘 直接打开应用
        if (PATTERN_QUARK_OPEN.matcher(text).matches()) {
            launchQuarkApp();
            return true;
        }

        // 2. 媒体中心 直接打开应用
        if (PATTERN_MEDIACENTER_OPEN.matcher(text).matches()) {
            launchMediaCenterApp();
            return true;
        }

        // 3. YouTube / 油管 直接打开应用（无具体搜索关键词）
        if (isYouTubeOpenCommand(text)) {
            launchYouTubeApp(text);
            return true;
        }

        // 4. 显式指定 YouTube / 油管 搜索或播放
        // 支持所有常见口语表达与同音字变体：
        // "去油管搜索周围", "去YouTube搜索周杰伦", "在有头部康学云", "去有兔不看徐云", "在有多不看虚云"
        String ytKeyword = extractYouTubeKeyword(text);
        if (ytKeyword != null && !ytKeyword.isEmpty()) {
            launchYouTubeSearch(ytKeyword);
            return true;
        }

        // 5. B站 / 哔哩哔哩 直接打开或搜索
        if (PATTERN_BILI_OPEN.matcher(text).matches()) {
            launchBilibiliApp();
            return true;
        }

        String biliKeyword = extractBilibiliKeyword(text);
        if (biliKeyword != null && !biliKeyword.isEmpty()) {
            launchBilibiliSearch(biliKeyword);
            return true;
        }

        // 6. 全局通用视频搜索指令（帮我找xx、帮我搜xx、搜索xx、搜xx、找xx）统一唤起 YouTube 搜索
        String globalKeyword = extractGlobalSearchKeyword(text);
        if (globalKeyword != null && !globalKeyword.isEmpty()) {
            launchYouTubeSearch(globalKeyword);
            return true;
        }

        // 7. 当前前台处于 YouTube 或 SmartTube 时，通用“搜索xxx / 搜xxx / 看xxx / 播放xxx”
        String fgPkg = ScreenCaptureAccessibilityService.getForegroundPackage();
        if (fgPkg == null || fgPkg.isEmpty()) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    List<android.app.ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                    if (procs != null) {
                        for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
                            if (p.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                                if (p.pkgList != null && p.pkgList.length > 0) {
                                    for (String pkg : p.pkgList) {
                                        if ("com.google.android.youtube.tv".equals(pkg) || "org.smarttube.stable".equals(pkg)) {
                                            fgPkg = pkg;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        writeLog("【影视搜索检测】fgPkg=" + fgPkg + ", query=" + text);
        if ("com.google.android.youtube.tv".equals(fgPkg) || "org.smarttube.stable".equals(fgPkg)) {
            Matcher m = PATTERN_GENERIC_SEARCH.matcher(text);
            if (m.find()) {
                String keyword = cleanExtractedKeyword(m.group(1));
                if (!keyword.isEmpty()) {
                    launchYouTubeSearch(keyword);
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isYouTubeOpenCommand(String text) {
        return PATTERN_YT_OPEN.matcher(text).matches();
    }

    private String extractYouTubeKeyword(String text) {
        if (text == null) return null;
        String t = text.trim();

        // 模式 1: 前置修饰词 + (youtube|油管|同音词) + 动作词 + 关键词
        Matcher m1 = PATTERN_YT_SEARCH_1.matcher(t);
        if (m1.matches()) {
            String kw = cleanExtractedKeyword(m1.group(1));
            if (!kw.isEmpty()) return kw;
        }

        // 模式 2: 动作词 + 前置修饰词 + (youtube|油管|同音词) + 关键词
        Matcher m2 = PATTERN_YT_SEARCH_2.matcher(t);
        if (m2.matches()) {
            String kw = cleanExtractedKeyword(m2.group(1));
            if (!kw.isEmpty()) return kw;
        }

        return null;
    }

    /**
     * 清理应用内语音搜索词：去除用户口述带入的唤醒词、YouTube/油管同音词以及各类前置动作动词
     */
    public static String cleanInAppSearchQuery(String text) {
        if (text == null) return "";
        String t = text.trim();
        t = stripWakeWord(t);

        // 如果用户在第三方应用语音输入时口述了 "YouTube xxx"、"在油管看 xxx" 或 "有兔不看 xxx" 等
        Matcher m = PATTERN_YT_SEARCH_1.matcher(t);
        if (m.matches()) {
            String kw = cleanExtractedKeyword(m.group(1));
            if (!kw.isEmpty()) return kw;
        }

        // 去除开头的通用搜索动词
        t = PATTERN_SEARCH_PREFIX.matcher(t).replaceAll("").trim();
        t = PATTERN_TRAILING_PUNCT.matcher(t).replaceAll("").trim();
        return t.isEmpty() ? text.trim() : t;
    }

    private static String cleanExtractedKeyword(String kw) {
        if (kw == null) return "";
        kw = PATTERN_ACTION_KW_PREFIX.matcher(kw).replaceAll("").trim();
        kw = PATTERN_TRAILING_PUNCT.matcher(kw).replaceAll("").trim();
        return kw;
    }

    private String extractBilibiliKeyword(String text) {
        if (text == null) return null;
        String t = text.trim();

        Matcher m1 = PATTERN_BILI_SEARCH_1.matcher(t);
        if (m1.matches()) {
            String kw = cleanExtractedKeyword(m1.group(1));
            if (!kw.isEmpty()) return kw;
        }

        Matcher m2 = PATTERN_BILI_SEARCH_2.matcher(t);
        if (m2.matches()) {
            String kw = cleanExtractedKeyword(m2.group(1));
            if (!kw.isEmpty()) return kw;
        }

        return null;
    }

    private String extractGlobalSearchKeyword(String text) {
        if (text == null) return null;
        String t = text.trim();
        Matcher m = PATTERN_GLOBAL_SEARCH.matcher(t);
        if (m.matches()) {
            String kw = cleanExtractedKeyword(m.group(1));
            if (!kw.isEmpty()) return kw;
        }
        return null;
    }

    private Intent getAppLaunchIntent(String packageName) {
        if (packageName == null) return null;
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                intent = pm.getLeanbackLaunchIntentForPackage(packageName);
            } catch (Throwable ignored) {}
        }
        return intent;
    }

    private void launchQuarkApp() {
        writeLog("【应用启动路由】打开夸克网盘");
        speakText("正在为您打开夸克网盘");
        if (mOverlay != null) {
            mOverlay.showReply("打开应用", "正在打开夸克网盘...");
        }
        mHandler.postDelayed(() -> {
            try {
                Intent launchIntent = getAppLaunchIntent("com.quark.yun.tv");
                if (launchIntent == null) {
                    launchIntent = new Intent(Intent.ACTION_MAIN);
                    launchIntent.setComponent(new ComponentName("com.quark.yun.tv", "com.extscreen.runtime.LauncherAlias"));
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(launchIntent);
                exitConversationAndStopListening();
            } catch (Exception e) {
                writeLog("打开夸克网盘异常: " + e.getMessage());
            }
        }, 500);
    }

    private void launchMediaCenterApp() {
        writeLog("【应用启动路由】打开媒体中心");
        speakText("正在为您打开媒体中心");
        if (mOverlay != null) {
            mOverlay.showReply("打开应用", "正在打开媒体中心...");
        }
        mHandler.postDelayed(() -> {
            try {
                Intent launchIntent = getAppLaunchIntent("com.jamdeo.tv.mediacenter");
                if (launchIntent == null) {
                    launchIntent = new Intent(Intent.ACTION_MAIN);
                    launchIntent.setComponent(new ComponentName("com.jamdeo.tv.mediacenter", "com.jamdeo.tv.mediacenter.MediaCenterActivity"));
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(launchIntent);
                exitConversationAndStopListening();
            } catch (Exception e) {
                writeLog("打开媒体中心异常: " + e.getMessage());
            }
        }, 500);
    }

    private void launchYouTubeApp() {
        launchYouTubeApp(null);
    }

    private void launchYouTubeApp(String text) {
        writeLog("【应用启动路由】打开 YouTube / SmartTube: " + text);
        speakText("正在为您打开 YouTube");
        if (mOverlay != null) {
            mOverlay.showReply("打开应用", "正在打开 YouTube...");
        }
        mHandler.postDelayed(() -> {
            try {
                String targetPkg = "com.google.android.youtube.tv";
                if (text != null && text.toLowerCase().contains("smarttube")) {
                    if (isPackageInstalled("org.smarttube.stable")) {
                        targetPkg = "org.smarttube.stable";
                    }
                } else {
                    if (isPackageInstalled("com.google.android.youtube.tv")) {
                        targetPkg = "com.google.android.youtube.tv";
                    } else if (isPackageInstalled("org.smarttube.stable")) {
                        targetPkg = "org.smarttube.stable";
                    }
                }
                Intent launchIntent = getAppLaunchIntent(targetPkg);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(launchIntent);
                } else {
                    writeLog("未找到可启动的 YouTube 应用: " + targetPkg);
                }
                exitConversationAndStopListening();
            } catch (Exception e) {
                writeLog("打开 YouTube 异常: " + e.getMessage());
            }
        }, 500);
    }

    private void launchBilibiliApp() {
        writeLog("【应用启动路由】打开 B站");
        speakText("正在为您打开哔哩哔哩");
        if (mOverlay != null) {
            mOverlay.showReply("打开应用", "正在打开哔哩哔哩...");
        }
        mHandler.postDelayed(() -> {
            try {
                Intent launchIntent = getAppLaunchIntent("com.bilibili.tv");
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(launchIntent);
                }
                exitConversationAndStopListening();
            } catch (Exception e) {
                writeLog("打开 B站 异常: " + e.getMessage());
            }
        }, 500);
    }

    private void launchBilibiliSearch(String keyword) {
        writeLog("【视频搜索路由】在 B站 搜索: " + keyword);
        speakText("正在为您在哔哩哔哩搜索 " + keyword);
        if (mOverlay != null) {
            mOverlay.showReply("搜索", "正在为您在哔哩哔哩搜索：" + keyword);
        }
        mHandler.postDelayed(() -> {
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.bilibili.tv");
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launchIntent);
                }
                exitConversationAndStopListening();
            } catch (Exception e) {
                writeLog("拉起 B站 搜索异常: " + e.getMessage());
            }
        }, 500);
    }

    private void launchYouTubeSearch(String keyword) {
        final String currentFg = ScreenCaptureAccessibilityService.getForegroundPackage();
        writeLog("【视频搜索路由】在 YouTube 搜索: " + keyword + ", 当前前台: " + currentFg);
        speakText("正在为您在 YouTube 搜索 " + keyword);
        if (mOverlay != null) {
            mOverlay.showReply("搜索", "正在为您在 YouTube 搜索：" + keyword);
        }
        mHandler.postDelayed(() -> {
            try {
                String targetPkg = null;
                if ("com.google.android.youtube.tv".equals(currentFg)) {
                    targetPkg = "com.google.android.youtube.tv";
                } else if (isPackageInstalled("org.smarttube.stable")) {
                    targetPkg = "org.smarttube.stable";
                } else if (isPackageInstalled("com.google.android.youtube.tv")) {
                    targetPkg = "com.google.android.youtube.tv";
                }

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(keyword)));
                if (targetPkg != null) {
                    intent.setPackage(targetPkg);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                exitConversationAndStopListening();
            } catch (Exception e) {
                writeLog("拉起 YouTube 搜索异常: " + e.getMessage());
            }
        }, 500);
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            writeLog("已成功连接海信语音核心 ServiceConnection");
            mCoreInterface = ICoreInterface.Stub.asInterface(service);
            mIsBinding = false;
            try {
                mCoreInterface.onRegister("com.keylab.speech.view.vidaa", mCoreListener);
                writeLog("【成功】已成功将 ICoreListener 监听器注册到电视系统语音核心！");
            } catch (Exception e) {
                writeLog("注册监听器异常: " + e.getMessage());
            }
            syncSystemWakeWordSettings();
            if (isExternalInputMode()) {
                startExternalListeningInternal();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            writeLog("与海信语音核心服务断开连接");
            mCoreInterface = null;
            mIsBinding = false;
            scheduleRebind();
        }
    };

    private void startAsForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "AI语音助手",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("保持AI语音助手常驻后台，支持全局与视频浮窗对话");
                channel.enableLights(false);
                channel.enableVibration(false);
                nm.createNotificationChannel(channel);
            }

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            Notification notification = builder
                    .setContentTitle("AI 语音助手")
                    .setContentText("正在后台守护，支持识屏对话与视频悬浮")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build();

            startForeground(NOTIFICATION_ID, notification);
            writeLog("【成功】AiVoiceBridgeService 已作为前台服务启动！");
        } catch (Exception e) {
            writeLog("startForeground 异常: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        writeLog("AiVoiceBridgeService onCreate 启动 (V2 识屏探索版)");

        startAsForeground();

        TvConfig.init(this);
        ScreenCaptureEngine.init(this);
        AiClient.init(this);

        mOverlay = new AiSubtitleOverlay(this);
        mOverlay.setOnDismissListener(this::exitConversationAndStopListening);

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_TEST_AI);
            filter.addAction(ACTION_EXIT_AI);
            if (Build.VERSION.SDK_INT >= 33) {
                // RECEIVER_NOT_EXPORTED：调试/退出广播仅接受同应用与系统（adb shell）投递，
                // 防止第三方侧载应用伪造 TEST_AI 注入语音指令。
                registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(mBroadcastReceiver, filter);
            }
        } catch (Exception e) {
            writeLog("注册广播接收器失败: " + e.getMessage());
        }
        bindSpeechCore();
        syncSystemWakeWordSettings();
    }

    /**
     * 自动同步并保障系统底层硬件远场唤醒词（你好小智）
     */
    private void syncSystemWakeWordSettings() {
        mWorkerExecutor.execute(() -> {
            try {
                // 1. 设置 Settings.Global 与 Settings.System 唤醒词为 "你好小智"
                try {
                    android.provider.Settings.Global.putString(getContentResolver(), "farfield_selfwakeup", "你好小智");
                    android.provider.Settings.Global.putString(getContentResolver(), "wakeupState", "1");
                    android.provider.Settings.Global.putInt(getContentResolver(), "double_wake_up_ability", 1);
                    android.provider.Settings.Global.putInt(getContentResolver(), "customwakeup_user_confirm", 1);

                    android.provider.Settings.System.putString(getContentResolver(), "farfield_selfwakeup", "你好小智");
                    android.provider.Settings.System.putString(getContentResolver(), "wakeupState", "1");
                    android.provider.Settings.System.putInt(getContentResolver(), "double_wake_up_ability", 1);
                    android.provider.Settings.System.putInt(getContentResolver(), "customwakeup_user_confirm", 1);
                } catch (Throwable t) {
                    writeLog("写入系统设置项警告 (非致命): " + t.getMessage());
                }

                // 2. 发送海信语音核心广播通知底层讯飞硬件远场服务热重载 "你好小智"
                Intent addIntent = new Intent("com.speech.area.selfwakeup.add");
                addIntent.setPackage("com.keylab.speech.core.vidaa");
                addIntent.putExtra("name", "你好小智");
                addIntent.putExtra("source", 0);
                addIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                sendBroadcast(addIntent);

                Intent switchIntent = new Intent("com.speech.area.selfwakeup.switch");
                switchIntent.setPackage("com.keylab.speech.core.vidaa");
                switchIntent.putExtra("switch", true);
                switchIntent.putExtra("name", "你好小智");
                switchIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                sendBroadcast(switchIntent);

                writeLog("【唤醒词同步】已向系统底层与讯飞远场硬件服务同步下发自定义唤醒词：你好小智");
            } catch (Exception e) {
                writeLog("【唤醒词同步异常】: " + e.getMessage());
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        writeLog("onStartCommand 触发");
        startAsForeground();

        if (intent != null && intent.hasExtra("query")) {
            String query = intent.getStringExtra("query");
            if (query != null && !query.trim().isEmpty()) {
                writeLog("收到 startService 提问请求: " + query);
                mIsConversationActive = true;
                processUserQuery(query.trim());
            }
        }
        if (mCoreInterface == null) {
            bindSpeechCore();
        }
        return START_STICKY;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        writeLog("onTrimMemory 触发 (level=" + level + ")，立即清空截屏单槽缓存");
        ScreenCaptureEngine.onTrimMemory(level);
    }

    private void bindSpeechCore() {
        if (mIsBinding) return;
        mIsBinding = true;
        try {
            Intent intent = new Intent("com.keylab.hispeech.core.aisdk");
            intent.setPackage("com.keylab.speech.core.vidaa");
            boolean ok = bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
            writeLog("bindService com.keylab.hispeech.core.aisdk: " + ok);
            if (!ok) {
                mIsBinding = false;
                scheduleRebind();
            }
        } catch (Exception e) {
            writeLog("绑定异常: " + e.getMessage());
            mIsBinding = false;
            scheduleRebind();
        }
    }

    private void scheduleRebind() {
        mHandler.postDelayed(this::bindSpeechCore, 5000);
    }

    private void speakText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        try {
            writeLog("调用电视 TTS 朗读: " + text);
            Intent intent = new Intent("hisense.intent.action.TTS_REMINDER");
            intent.putExtra("content", text);
            intent.putExtra("fromapp", getPackageName());
            sendBroadcast(intent);
        } catch (Exception e) {
            writeLog("发送 TTS 广播失败: " + e.getMessage());
        }
    }

    private void writeLog(String line) {
        Log.i(TAG, line);
        mLogExecutor.execute(() -> {
            try {
                File dir = getFilesDir();
                if (dir != null) {
                    File file = new File(dir, "ai_bridge.log");
                    // 到达 512KB 上限时滚动保留：旧日志改名 .old（再覆盖上一份），新日志从空开始。
                    // 原先到达上限后单条写入即截断整个文件，出问题时刻的证据恰恰在最需要时丢失。
                    if (file.exists() && file.length() >= 512 * 1024) {
                        File old = new File(dir, "ai_bridge.log.old");
                        if (old.exists() && !old.delete()) {
                            // 删除失败也继续，最坏情况是这条覆盖写入
                        }
                        if (!file.renameTo(old)) {
                            file.delete(); // rename 失败（跨设备等），退化为清空重来
                        }
                    }
                    String time = LOG_DATE_FORMAT.get().format(new Date());
                    try (FileWriter fw = new FileWriter(file, true)) {
                        fw.write(time + " " + line + "\n");
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sInstance == this) {
            sInstance = null;
        }
        writeLog("AiVoiceBridgeService onDestroy 触发");
        exitConversationAndStopListening();
        try {
            unregisterReceiver(mBroadcastReceiver);
        } catch (Exception ignored) {}
        if (mCoreInterface != null) {
            try {
                mCoreInterface.onUnRegister(mCoreListener);
            } catch (Exception ignored) {}
            try {
                unbindService(mConnection);
            } catch (Exception ignored) {}
        }
    }

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, AiVoiceBridgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "启动前台服务失败", e);
        }
    }
}
