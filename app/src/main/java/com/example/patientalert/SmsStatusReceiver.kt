package com.example.patientalert

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SMS_SENT) return
        val status = when (resultCode) {
            Activity.RESULT_OK -> "تم قبول الرسالة للإرسال؛ وصولها للمرافق يعتمد على الشبكة"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "لم تُرسل الرسالة: لا توجد خدمة شبكة خلوية"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "لم تُرسل الرسالة: راديو الهاتف الخلوي مغلق"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "لم تُرسل الرسالة: تجاوز حد الإرسال"
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "لم تُرسل الرسالة: راجع إعدادات الأرقام المسموح بها"
            else -> "تعذر إرسال SMS؛ الإنذار المحلي ما زال يعمل"
        }
        AlarmState.setSmsStatus(context, status)
    }

    companion object {
        const val ACTION_SMS_SENT = "com.example.patientalert.SMS_SENT"
    }
}
