package com.keylab.speech.core.vidaa.aidl;

import com.keylab.speech.core.vidaa.aidl.ICoreListener;

interface ICoreInterface {
    // 1: TRANSACTION_onRegister
    void onRegister(String pkgName, ICoreListener listener);

    // 2: TRANSACTION_onUnRegister
    void onUnRegister(ICoreListener listener);

    // 3: TRANSACTION_onTextToSpeech
    void onTextToSpeech(String text);

    // 4: TRANSACTION_onShow
    void onShow(int arg);

    // 5: TRANSACTION_onDismiss
    void onDismiss(int arg);

    // 6: TRANSACTION_onSetting
    void onSetting(String key, String value);

    // 7: TRANSACTION_onGetInfomation
    String onGetInfomation(int type);

    // 8: TRANSACTION_onVerbalCommandMatch
    boolean onVerbalCommandMatch(String cmd, String text);

    // 9: TRANSACTION_onNavigate
    void onNavigate(int arg1, String arg2, String arg3);

    // 10: TRANSACTION_onSchedule
    void onSchedule(int arg1, long arg2, long arg3, String arg4);

    // 11: TRANSACTION_onMoranContext
    void onMoranContext(boolean flag, String arg1, String arg2);

    // 12: TRANSACTION_onMoranSearchShops
    void onMoranSearchShops(String arg1, String arg2, String arg3, double d1, double d2);

    // 13: TRANSACTION_onMoranNlpReq
    void onMoranNlpReq(String arg1, String arg2);

    // 14: TRANSACTION_onChatRoomState
    void onChatRoomState(int state);
}
