package com.example.patientalert

import android.content.Context

object AlarmState {
    private const val PREFS = "patient_alert"
    private const val MONITORING = "monitoring"
    private const val CONNECTED = "remote_connected"
    private const val LAST_KEY = "last_key"
    private const val ALARMING = "alarming"
    private const val ORIGINAL_MUSIC_VOLUME = "original_music_volume"
    private const val VOLUME_LOWERED_FOR_DETECTION = "volume_lowered_for_detection"
    private const val REMOTE_VISIBLE = "remote_visible"
    private const val GATT_CONNECTED = "gatt_connected"
    private const val EMERGENCY_NUMBER = "emergency_number"
    private const val SMS_SENT_FOR_ALARM = "sms_sent_for_alarm"
    private const val SMS_ALLOWED_FOR_ALARM = "sms_allowed_for_alarm"
    private const val SMS_STATUS = "sms_status"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun monitoring(context: Context) = prefs(context).getBoolean(MONITORING, false)
    fun setMonitoring(context: Context, value: Boolean) = prefs(context).edit().putBoolean(MONITORING, value).apply()
    fun connected(context: Context) = prefs(context).getBoolean(REMOTE_VISIBLE, prefs(context).getBoolean(CONNECTED, false))
    fun setConnected(context: Context, value: Boolean) = prefs(context).edit()
        .putBoolean(REMOTE_VISIBLE, value).putBoolean(CONNECTED, value).apply()
    fun gattConnected(context: Context) = prefs(context).getBoolean(GATT_CONNECTED, false)
    fun setGattConnected(context: Context, value: Boolean) = prefs(context).edit().putBoolean(GATT_CONNECTED, value).apply()
    fun alarming(context: Context) = prefs(context).getBoolean(ALARMING, false)
    fun setAlarming(context: Context, value: Boolean) {
        val editor = prefs(context).edit().putBoolean(ALARMING, value)
        if (!value) editor.putBoolean(SMS_SENT_FOR_ALARM, false).putBoolean(SMS_ALLOWED_FOR_ALARM, false)
        else if (!alarming(context)) editor.putBoolean(SMS_SENT_FOR_ALARM, false).putBoolean(SMS_ALLOWED_FOR_ALARM, true)
        editor.apply()
    }
    fun beginAlarm(context: Context, allowSms: Boolean) = prefs(context).edit()
        .putBoolean(ALARMING, true).putBoolean(SMS_SENT_FOR_ALARM, false)
        .putBoolean(SMS_ALLOWED_FOR_ALARM, allowSms).apply()
    fun smsAllowedForAlarm(context: Context) = prefs(context).getBoolean(SMS_ALLOWED_FOR_ALARM, true)
    fun emergencyNumber(context: Context) = prefs(context).getString(EMERGENCY_NUMBER, "") ?: ""
    fun setEmergencyNumber(context: Context, value: String) = prefs(context).edit().putString(EMERGENCY_NUMBER, value).apply()
    fun smsSentForAlarm(context: Context) = prefs(context).getBoolean(SMS_SENT_FOR_ALARM, false)
    fun setSmsSentForAlarm(context: Context, value: Boolean) = prefs(context).edit().putBoolean(SMS_SENT_FOR_ALARM, value).apply()
    fun smsStatus(context: Context) = prefs(context).getString(SMS_STATUS, "لم يتم إعداد جهة اتصال للطوارئ") ?: "لم يتم إعداد جهة اتصال للطوارئ"
    fun setSmsStatus(context: Context, value: String) = prefs(context).edit().putString(SMS_STATUS, value).apply()
    fun originalMusicVolume(context: Context) = prefs(context).getInt(ORIGINAL_MUSIC_VOLUME, -1)
    fun setOriginalMusicVolume(context: Context, value: Int) = prefs(context).edit().putInt(ORIGINAL_MUSIC_VOLUME, value).apply()
    fun clearOriginalMusicVolume(context: Context) = prefs(context).edit()
        .remove(ORIGINAL_MUSIC_VOLUME).remove(VOLUME_LOWERED_FOR_DETECTION).apply()
    fun volumeLoweredForDetection(context: Context) = prefs(context).getBoolean(VOLUME_LOWERED_FOR_DETECTION, false)
    fun setVolumeLoweredForDetection(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(VOLUME_LOWERED_FOR_DETECTION, value).apply()
    fun lastKey(context: Context) = prefs(context).getString(LAST_KEY, "لم تُلتقط ضغطة ريموت بعد") ?: "لم تُلتقط ضغطة ريموت بعد"
    fun setLastKey(context: Context, value: String) = prefs(context).edit().putString(LAST_KEY, value).apply()
}
