package com.example.patientalert

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.InputDevice
import android.view.KeyEvent

class ShutterKeyService : AccessibilityService() {
    private val consumedKeyUps = mutableSetOf<Pair<Int, Int>>()

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            eventTypes = 0
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val input = InputDevice.getDevice(event.deviceId) ?: return false
        val remoteName = (input.name ?: "").trim()
        if (!remoteName.contains("AB Shutter", ignoreCase = true)) return false
        if (event.keyCode !in SUPPORTED_KEYS) return false
        val key = event.deviceId to event.keyCode
        if (event.action == KeyEvent.ACTION_UP) return consumedKeyUps.remove(key)
        if (event.action != KeyEvent.ACTION_DOWN || !AlarmState.monitoring(this)) return false
        consumedKeyUps.add(key)
        if (event.repeatCount > 0) return true

        AlarmState.setLastKey(this, "تم التقاط زر ${KeyEvent.keyCodeToString(event.keyCode)} من $remoteName")
        startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_TRIGGER))
        return true
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        val SUPPORTED_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_ENTER)
    }
}
