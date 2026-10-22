package com.keylab.speech.core.vidaa.aidl;

interface ICoreListener {
    // 1: TRANSACTION_onSpeechState
    void onSpeechState(int state);

    // 2: TRANSACTION_onVolumeChanged
    void onVolumeChanged(int volume);

    // 3: TRANSACTION_onError
    void onError(int errorCode);

    // 4: TRANSACTION_onAsrResult
    void onAsrResult(String asrResult, boolean isLast, int arg1, int arg2);

    // 5: TRANSACTION_onVerbalCommand
    boolean onVerbalCommand(String command);

    // 6: TRANSACTION_onNlpResult
    void onNlpResult(int code, String nlpResult);

    // 7: TRANSACTION_onInlineResult
    void onInlineResult(String result, boolean isLast);

    // 8: TRANSACTION_onExitView
    void onExitView();

    // 9: TRANSACTION_onCommandExecute
    void onCommandExecute(String cmd, String params);

    // 10: TRANSACTION_onAppInstalled
    void onAppInstalled(String pkgName, String action);

    // 11: TRANSACTION_onBlendShapeData
    void onBlendShapeData(in byte[] data, int length);

    // 12: TRANSACTION_onSendBsData
    void onSendBsData(in byte[] data, int length, int arg1, String arg2);

    // 13: TRANSACTION_onChatRoomConfig
    void onChatRoomConfig(int config, String params);
}
