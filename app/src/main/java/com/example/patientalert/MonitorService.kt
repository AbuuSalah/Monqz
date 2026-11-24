package com.example.patientalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.ToneGenerator
import android.telephony.SmsManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.provider.Settings
import android.view.InputDevice

class MonitorService : Service(), InputManager.InputDeviceListener {
    private lateinit var inputManager: InputManager
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitorWakeLock: PowerManager.WakeLock? = null
    private var silentTrack: AudioTrack? = null
    private var volumeObserver: ContentObserver? = null
    private var lastMusicVolume = -1
    private var volumeReference = -1
    private var beepOn = false
    private var hasShutter = false
    private var gattConnected = false
    private var remoteGatt: BluetoothGatt? = null
    private var reconnectDelayMs = 3000L
    private var gattAttemptStartedAt = 0L
    private var reconnectScheduled = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(this, handler)
        refreshConnection()
        connectToRemote()
        handler.postDelayed(connectionWatchdog, CONNECTION_WATCHDOG_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITORING) {
            AlarmState.setMonitoring(this, false)
            releaseMonitorWakeLock()
            stopSilentVolumeProbe()
            stopAlarm()
            getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildMonitoringNotification())
        if (AlarmState.monitoring(this)) {
            acquireMonitorWakeLock()
            startSilentVolumeProbe()
            startForegroundCompat(buildMonitoringNotification())
            connectToRemote()
        }
        when (intent?.action) {
            ACTION_TRIGGER -> startAlarm()
            ACTION_TEST_TRIGGER -> startAlarm(sendSms = false)
            ACTION_STOP_ALARM -> stopAlarm()
            null -> if (AlarmState.alarming(this)) startAlarm(AlarmState.smsAllowedForAlarm(this))
        }
        return START_STICKY
    }

    private fun startAlarm(sendSms: Boolean = true) {
        if (beepOn) return
        beepOn = true
        if (!AlarmState.alarming(this)) AlarmState.beginAlarm(this, sendSms)
        tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:alert")
            .apply { setReferenceCounted(false); acquire() }
        if (AlarmState.smsAllowedForAlarm(this)) sendEmergencySmsOnce()
        handler.post(beepRunnable)
        // Keep the foreground-service notification stable. Post the urgent alarm
        // separately so Android processes its full-screen intent on a locked display.
        startForegroundCompat(buildMonitoringNotification())
        getSystemService(NotificationManager::class.java).notify(ALARM_NOTIFICATION_ID, buildAlarmNotification())
    }

    private val beepRunnable = object : Runnable {
        override fun run() {
            if (!beepOn) return
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
            handler.postDelayed(this, 1100)
        }
    }

    private fun stopAlarm() {
        beepOn = false
        AlarmState.setAlarming(this, false)
        handler.removeCallbacks(beepRunnable)
        tone?.stopTone()
        tone?.release()
        tone = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIFICATION_ID)
        if (AlarmState.monitoring(this)) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildMonitoringNotification())
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun sendEmergencySmsOnce() {
        if (AlarmState.smsSentForAlarm(this)) return
        val number = AlarmState.emergencyNumber(this).trim()
        if (number.isEmpty()) {
            AlarmState.setSmsStatus(this, "لم يتم ضبط رقم مرافق — الإنذار محلي فقط")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AlarmState.setSmsStatus(this, "رسالة المرافق غير مفعّلة: إذن SMS غير ممنوح")
            return
        }
        try {
            val smsSentIntent = PendingIntent.getBroadcast(
                this,
                SMS_SENT_REQUEST_CODE,
                Intent(this, SmsStatusReceiver::class.java).setAction(SmsStatusReceiver.ACTION_SMS_SENT),
                pendingFlags()
            )
            SmsManager.getDefault().sendTextMessage(
                number,
                null,
                "نداء مساعدة من تطبيق منقذ: تم الضغط على زر طلب المساعدة. يرجى التواصل مع صاحب الهاتف.",
                smsSentIntent,
                null
            )
            AlarmState.setSmsSentForAlarm(this, true)
            AlarmState.setSmsStatus(this, "جاري إرسال رسالة للمرافق")
        } catch (_: SecurityException) {
            AlarmState.setSmsStatus(this, "تعذر إرسال الرسالة: راجع إذن SMS")
        } catch (_: IllegalArgumentException) {
            AlarmState.setSmsStatus(this, "تعذر إرسال الرسالة: راجع رقم المرافق")
        } catch (_: UnsupportedOperationException) {
            AlarmState.setSmsStatus(this, "إرسال SMS غير مدعوم على هذا الجهاز")
        } catch (_: RuntimeException) {
            AlarmState.setSmsStatus(this, "تعذر بدء إرسال SMS؛ تحقق من الشريحة والشبكة")
        }
    }

    private fun refreshConnection() {
        val found = inputManager.inputDeviceIds.any { id ->
            InputDevice.getDevice(id)?.name?.contains("AB Shutter", ignoreCase = true) == true
        }
        hasShutter = found
        AlarmState.setConnected(this, found)
        AlarmState.setGattConnected(this, gattConnected)
        if (::inputManager.isInitialized && AlarmState.monitoring(this)) {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildMonitoringNotification()
            )
            if (beepOn) getSystemService(NotificationManager::class.java)
                .notify(ALARM_NOTIFICATION_ID, buildAlarmNotification())
        }
    }

    private fun connectToRemote() {
        if (!AlarmState.monitoring(this) || remoteGatt != null) return
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                scheduleReconnect()
                return
            }
            val remote = adapter.bondedDevices.firstOrNull {
                it.name?.contains("AB Shutter", ignoreCase = true) == true
            }
            if (remote == null) {
                scheduleReconnect()
                return
            }
            remoteGatt = remote.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (remoteGatt == null) {
                scheduleReconnect()
            } else {
                gattAttemptStartedAt = SystemClock.elapsedRealtime()
            }
        } catch (_: SecurityException) {
            AlarmState.setLastKey(this, "تعذر الوصول للبلوتوث. راجع إذن الأجهزة القريبة.")
            scheduleReconnect()
        } catch (_: IllegalArgumentException) {
            scheduleReconnect()
        }
    }

    private fun acquireMonitorWakeLock() {
        if (monitorWakeLock?.isHeld == true) return
        monitorWakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:monitor")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseMonitorWakeLock() {
        monitorWakeLock?.let { if (it.isHeld) it.release() }
        monitorWakeLock = null
    }

    private fun startSilentVolumeProbe() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (AlarmState.originalMusicVolume(this) < 0) {
            AlarmState.setOriginalMusicVolume(this, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
            AlarmState.setVolumeLoweredForDetection(this, false)
        }
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (currentVolume >= maxVolume) {
            volumeReference = (maxVolume - 1).coerceAtLeast(0)
            AlarmState.setVolumeLoweredForDetection(this, true)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeReference, 0)
        } else {
            volumeReference = currentVolume
        }
        if (silentTrack == null) {
            val samples = ShortArray(8000)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(8000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.setVolume(0f)
            if (track.write(samples, 0, samples.size) == samples.size &&
                track.setLoopPoints(0, samples.size, -1) == AudioTrack.SUCCESS) {
                track.play()
                silentTrack = track
            } else {
                track.release()
            }
        }
        if (volumeObserver == null) {
            lastMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            volumeObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val interactive = powerManager.isInteractive
                    if (!interactive && AlarmState.monitoring(this@MonitorService) && current > lastMusicVolume) {
                        AlarmState.setLastKey(this@MonitorService, "تم التقاط زر رفع الصوت أثناء انطفاء الشاشة")
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeReference, 0)
                        lastMusicVolume = volumeReference
                        startAlarm()
                        return
                    }
                    if (current < lastMusicVolume || (interactive && current != lastMusicVolume)) {
                        volumeReference = current
                        if (interactive || current < lastMusicVolume) {
                            AlarmState.setVolumeLoweredForDetection(this@MonitorService, false)
                        }
                    }
                    lastMusicVolume = current
                }
            }
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver!!)
        }
    }

    private fun stopSilentVolumeProbe() {
        volumeObserver?.let { contentResolver.unregisterContentObserver(it) }
        volumeObserver = null
        val originalVolume = AlarmState.originalMusicVolume(this)
        if (originalVolume >= 0 && AlarmState.volumeLoweredForDetection(this)) {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
        AlarmState.clearOriginalMusicVolume(this)
        silentTrack?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
            it.release()
        }
        silentTrack = null
        lastMusicVolume = -1
        volumeReference = -1
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (remoteGatt !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    gattConnected = true
                    reconnectDelayMs = 3000L
                    reconnectScheduled = false
                    handler.removeCallbacks(reconnectRunnable)
                    refreshConnection()
                    try {
                        gatt.discoverServices()
                    } catch (_: SecurityException) {
                        Unit
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gattConnected = false
                    refreshConnection()
                    // A clean disconnect is still a disconnect. Release this GATT
                    // client so connectToRemote can create a fresh auto-connect request.
                    gatt.close()
                    if (remoteGatt === gatt) remoteGatt = null
                    gattAttemptStartedAt = 0L
                    scheduleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!AlarmState.monitoring(this) || reconnectScheduled) return
        reconnectScheduled = true
        handler.postDelayed(reconnectRunnable, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
    }

    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        connectToRemote()
    }

    /**
     * Android or the remote can silently lose a pending BLE GATT attempt while
     * the screen is off. Periodically check it and renew requests that never
     * reached CONNECTED; the service foreground/wakelock keeps this check alive.
     */
    private val connectionWatchdog = object : Runnable {
        override fun run() {
            if (!AlarmState.monitoring(this@MonitorService)) return
            val currentGatt = remoteGatt
            if (gattConnected) {
                reconnectDelayMs = 3000L
            } else if (currentGatt == null && !reconnectScheduled) {
                connectToRemote()
            } else if (currentGatt != null && gattAttemptStartedAt > 0L &&
                SystemClock.elapsedRealtime() - gattAttemptStartedAt >= GATT_ATTEMPT_TIMEOUT_MS) {
                currentGatt.close()
                if (remoteGatt === currentGatt) remoteGatt = null
                gattAttemptStartedAt = 0L
                scheduleReconnect()
            }
            refreshConnection()
            handler.postDelayed(this, CONNECTION_WATCHDOG_INTERVAL_MS)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "حالة الاتصال", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "إشعار دائم بحالة مراقبة ريموت المساعدة" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "إنذار المساعدة", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "صوت إنذار متكرر عند ضغط ريموت المساعدة"
                    setSound(null, null)
                    enableVibration(true)
                }
        )
    }

    private fun buildMonitoringNotification(): Notification {
        val text = if (hasShutter) "المراقبة تعمل · الريموت ظاهر للنظام" else "المراقبة تعمل · الريموت غير ظاهر"
        return Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("منقذ · نداء المساعدة")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .addAction(0, "إيقاف المراقبة", servicePendingIntent(ACTION_STOP_MONITORING, 2))
            .build()
    }

    private fun buildAlarmNotification(): Notification = Notification.Builder(this, CHANNEL_ALARM)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle("منقذ · إنذار المساعدة")
        .setContentText("اضغط لإيقاف صوت الإنذار")
        .setCategory(Notification.CATEGORY_ALARM)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOngoing(true)
        .setContentIntent(alarmActivityPendingIntent())
        .setFullScreenIntent(alarmActivityPendingIntent(), true)
        .addAction(0, "إيقاف الصوت", servicePendingIntent(ACTION_STOP_ALARM, 4))
        .build()

    private fun openAppPendingIntent() = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java), pendingFlags()
    )

    private fun alarmActivityPendingIntent() = PendingIntent.getActivity(
        this, 5, Intent(this, AlarmActivity::class.java), pendingFlags()
    )

    private fun servicePendingIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, MonitorService::class.java).setAction(action), pendingFlags()
    )

    private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (beepOn || silentTrack != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshConnection()
    override fun onInputDeviceChanged(deviceId: Int) = refreshConnection()
    override fun onInputDeviceRemoved(deviceId: Int) = refreshConnection()

    override fun onDestroy() {
        inputManager.unregisterInputDeviceListener(this)
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(connectionWatchdog)
        remoteGatt?.close()
        remoteGatt = null
        gattConnected = false
        AlarmState.setGattConnected(this, false)
        releaseMonitorWakeLock()
        stopSilentVolumeProbe()
        stopAlarm()
        AlarmState.setConnected(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_TRIGGER = "com.example.patientalert.TRIGGER"
        const val ACTION_TEST_TRIGGER = "com.example.patientalert.TEST_TRIGGER"
        const val ACTION_STOP_ALARM = "com.example.patientalert.STOP_ALARM"
        const val ACTION_STOP_MONITORING = "com.example.patientalert.STOP_MONITORING"
        private const val CHANNEL_STATUS = "monitor_status"
        private const val CHANNEL_ALARM = "help_alarm"
        private const val NOTIFICATION_ID = 71
        private const val ALARM_NOTIFICATION_ID = 73
        private const val CONNECTION_WATCHDOG_INTERVAL_MS = 15_000L
        private const val GATT_ATTEMPT_TIMEOUT_MS = 45_000L
        private const val SMS_SENT_REQUEST_CODE = 94
    }
}
