package com.example.patientalert

import android.Manifest
import android.app.Activity
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var statusDot: TextView
    private lateinit var remoteStatus: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var lastKeyStatus: TextView
    private lateinit var startButton: TextView
    private lateinit var gattStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var alarmAccessStatus: TextView
    private lateinit var alarmAccessButton: TextView
    private lateinit var emergencyNumberInput: EditText
    private lateinit var smsStatus: TextView
    private var askedForAccessibility = false
    private var pendingEmergencyNumber: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            renderState()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(244, 248, 247)
        window.navigationBarColor = Color.rgb(244, 248, 247)
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        buildUi()
        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        renderState()
        if (askedForAccessibility && isAccessibilityEnabled() && hasRequiredPermissions()) {
            askedForAccessibility = false
            startMonitoring()
        } else if (AlarmState.monitoring(this) && isAccessibilityEnabled() && hasRequiredPermissions()) {
            startMonitoring()
        }
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun buildUi() {
        val root = ScrollView(this).apply {
            setBackgroundColor(BG)
            clipToPadding = false
            isFillViewport = true
            setPadding(0, 0, 0, dp(18))
        }
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(frame)
        val tablet = resources.configuration.smallestScreenWidthDp >= 600
        val wideTablet = tablet && resources.configuration.screenWidthDp >= 760
        val maxContentWidth = if (tablet) 1160 else 560
        val contentWidth = (resources.configuration.screenWidthDp - 40).coerceAtLeast(280).coerceAtMost(maxContentWidth)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        frame.addView(content, LinearLayout.LayoutParams(dp(contentWidth), LinearLayout.LayoutParams.WRAP_CONTENT))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = roundedGradient(intArrayOf(0xFF0D756D.toInt(), 0xFF123F4A.toInt()), 22)
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(TextView(this).apply {
            text = "منقذ"
            textSize = if (tablet) 27f else 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        brand.addView(TextView(this).apply {
            text = "مراقبة زر طلب المساعدة"
            textSize = 14f
            setTextColor(0xFFD7E9E8.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "م"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFF0D655F.toInt())
            background = rounded(Color.WHITE, 18)
        }, LinearLayout.LayoutParams(dp(50), dp(50)))
        content.addView(header, marginParams(bottom = 18))

        val stateCard = buildStatusCard()
        val controlsCard = card().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        controlsCard.addView(TextView(this).apply {
            text = "التحكم"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(INK)
        })
        startButton = actionButton("ابدأ المراقبة", true) { onStartMonitoringClicked() }
        controlsCard.addView(startButton, marginParams(top = 12, bottom = 8))
        val quickActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        quickActions.addView(actionButton("إعداد Bluetooth", false) { openBluetoothSettings() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(5) })
        quickActions.addView(actionButton("تجربة الإنذار", false) { triggerAlarm() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(5) })
        controlsCard.addView(quickActions, marginParams(bottom = 8))
        val serviceActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        serviceActions.addView(textAction("إيقاف المراقبة") {
            sendServiceAction(MonitorService.ACTION_STOP_MONITORING)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        serviceActions.addView(textAction("إيقاف الإنذار") {
            sendServiceAction(MonitorService.ACTION_STOP_ALARM)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
        controlsCard.addView(serviceActions)
        alarmAccessStatus = bodyText("", 12f, MUTED).apply { setPadding(0, dp(10), 0, 0) }
        controlsCard.addView(alarmAccessStatus)
        alarmAccessButton = actionButton("إعداد ظهور الإنذار", false) { openAlarmAccessSettings() }
        controlsCard.addView(alarmAccessButton, marginParams(top = 7))

        val guide = card().apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }
        guide.addView(TextView(this).apply {
            text = "إعداد سريع"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(INK)
        })
        guide.addView(bodyText("١. أقرن AB Shutter3 من إعداد Bluetooth.\n٢. فعّل «استقبال زر ريموت المساعدة» في إعدادات إمكانية الوصول.\n٣. ابدأ المراقبة، وسيظهر إشعار ثابت أثناء عملها.", 14f, MUTED)
            .apply { setPadding(0, dp(8), 0, 0) })
        val note = bodyText("معلومة: عند انطفاء الشاشة، قد تلتقط أزرار الصوت الأخرى نفس إشارة الريموت.", 12f, MUTED)
            .apply { setPadding(0, dp(10), 0, 0) }
        guide.addView(note)
        guide.addView(actionButton("إعدادات عمل منقذ بالخلفية", false) {
            openBatterySettings()
        }, marginParams(top = 12))

        val emergencyCard = buildEmergencyContactCard()

        if (wideTablet) {
            val columns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.TOP
            }
            val rightColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val leftColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            rightColumn.addView(stateCard, marginParams(bottom = 14))
            rightColumn.addView(controlsCard)
            leftColumn.addView(sectionHeading("ابدأ من هنا", "حالة الريموت وخطوات التجهيز"), marginParams(bottom = 10))
            leftColumn.addView(guide)
            leftColumn.addView(emergencyCard, marginParams(top = 12))
            columns.addView(rightColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            columns.addView(leftColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
            content.addView(columns)
        } else {
            content.addView(stateCard, marginParams(bottom = 12))
            content.addView(controlsCard, marginParams(bottom = 16))
            content.addView(guide)
            content.addView(emergencyCard, marginParams(top = 12))
        }

        setContentView(root)
    }

    private fun buildStatusCard(): LinearLayout {
        val stateCard = card().apply { setPadding(dp(19), dp(18), dp(19), dp(18)) }
        stateCard.addView(TextView(this).apply {
            text = "حالة الجهاز"
            textSize = 14f
            setTextColor(MUTED)
        })
        val stateLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(5), 0, dp(7))
        }
        statusDot = TextView(this).apply {
            text = "●"
            textSize = 14f
            setPadding(0, 0, dp(7), 0)
            setTextColor(0xFF9AA9AD.toInt())
        }
        status = TextView(this).apply {
            text = "المراقبة متوقفة"
            textSize = 19f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
        }
        stateLine.addView(statusDot)
        stateLine.addView(status)
        stateCard.addView(stateLine)
        remoteStatus = bodyText("الريموت غير ظاهر", 14f, MUTED)
        serviceStatus = bodyText("استقبال الزر يحتاج تفعيلًا", 13f, MUTED)
        gattStatus = bodyText("حالة BLE غير معروفة", 13f, MUTED)
        batteryStatus = bodyText("حالة توفير البطارية غير معروفة", 13f, MUTED)
        lastKeyStatus = bodyText("لم تُلتقط ضغطة ريموت بعد", 12f, MUTED).apply { maxLines = 2 }
        stateCard.addView(remoteStatus)
        stateCard.addView(serviceStatus, marginParams(top = 3))
        stateCard.addView(gattStatus, marginParams(top = 3))
        stateCard.addView(batteryStatus, marginParams(top = 3))
        stateCard.addView(lastKeyStatus, marginParams(top = 8))
        return stateCard
    }

    private fun buildEmergencyContactCard(): LinearLayout {
        val contactCard = card().apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }
        contactCard.addView(TextView(this).apply {
            text = "تنبيه المرافق"
            textSize = 17f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
        })
        contactCard.addView(bodyText(
            "اختياري: يرسل منقذ رسالة SMS تلقائيًا عند تشغيل الإنذار. يلزم رقم محفوظ وإذن SMS وشريحة تدعم الإرسال.",
            13f, MUTED
        ).apply { setPadding(0, dp(5), 0, dp(10)) })
        emergencyNumberInput = EditText(this).apply {
            hint = "رقم المرافق، مثال +20 10..."
            textSize = 16f
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xFFF1F5F5.toInt(), 12)
            setText(AlarmState.emergencyNumber(this@MainActivity))
        }
        contactCard.addView(emergencyNumberInput, marginParams(bottom = 8))
        contactCard.addView(actionButton("حفظ رقم المرافق", false) {
            saveEmergencyContact()
        })
        smsStatus = bodyText(AlarmState.smsStatus(this), 12f, MUTED).apply {
            setPadding(0, dp(8), 0, 0)
            maxLines = 3
        }
        contactCard.addView(smsStatus)
        return contactCard
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(18), dp(17), dp(18), dp(17))
        background = rounded(Color.WHITE, 22)
        elevation = dp(2).toFloat()
    }

    private fun bodyText(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.RIGHT
        textDirection = View.TEXT_DIRECTION_RTL
    }

    private fun sectionHeading(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 19f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(3), 0, 0)
        })
    }

    private fun actionButton(text: String, primary: Boolean, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = if (primary) 18f else 15f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = if (primary) dp(60) else dp(52)
        setTextColor(if (primary) Color.WHITE else TEAL_DARK)
        background = if (primary) roundedGradient(intArrayOf(0xFF0A8E83.toInt(), 0xFF08736F.toInt()), 18)
            else rounded(0xFFE5F1EF.toInt(), 16)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun textAction(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(MUTED)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun marginParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun roundedGradient(colors: IntArray, radiusDp: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, colors
    ).apply { cornerRadius = dp(radiusDp).toFloat() }

    private fun onStartMonitoringClicked() {
        if (!hasRequiredPermissions()) {
            requestRuntimePermissions()
            return
        }
        if (!isAccessibilityEnabled()) {
            askedForAccessibility = true
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "فعّل «استقبال زر ريموت المساعدة» ثم ارجع للتطبيق", Toast.LENGTH_LONG).show()
            return
        }
        if (AlarmState.monitoring(this)) {
            Toast.makeText(this, "المراقبة تعمل بالفعل", Toast.LENGTH_SHORT).show()
            return
        }
        startMonitoring()
    }

    private fun startMonitoring() {
        AlarmState.setMonitoring(this, true)
        val intent = Intent(this, MonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: SecurityException) {
            AlarmState.setMonitoring(this, false)
            Toast.makeText(this, "تعذر تشغيل المراقبة. راجع أذونات الإشعارات والبلوتوث.", Toast.LENGTH_LONG).show()
        }
        renderState()
    }

    private fun triggerAlarm() {
        if (!AlarmState.monitoring(this)) {
            Toast.makeText(this, "ابدأ المراقبة أولًا لتجربة الإنذار", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_TEST_TRIGGER)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: SecurityException) {
            Toast.makeText(this, "تعذر تشغيل الإنذار. راجع إعدادات الإشعارات.", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, MonitorService::class.java).setAction(action)
        if (!AlarmState.monitoring(this) && action != MonitorService.ACTION_STOP_MONITORING) {
            Toast.makeText(this, "ابدأ المراقبة أولًا", Toast.LENGTH_SHORT).show()
            return
        }
        startService(intent)
        if (action == MonitorService.ACTION_STOP_MONITORING) AlarmState.setMonitoring(this, false)
        renderState()
    }

    private fun saveEmergencyContact() {
        val entered = emergencyNumberInput.text.toString().trim()
        val number = entered.replace(Regex("[\\s().-]"), "")
        if (!number.matches(Regex("^\\+?[0-9]{7,18}$"))) {
            smsStatus.text = "راجع الرقم واكتبه مع مفتاح الدولة، مثل +20..."
            return
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            pendingEmergencyNumber = number
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), REQUEST_SMS_PERMISSION)
            return
        }
        storeEmergencyContact(number)
    }

    private fun storeEmergencyContact(number: String) {
        AlarmState.setEmergencyNumber(this, number)
        AlarmState.setSmsStatus(this, "الرسالة مفعّلة لهذا الرقم؛ الإرسال يعتمد على الشبكة والشريحة")
        emergencyNumberInput.setText(number)
        smsStatus.text = AlarmState.smsStatus(this)
        Toast.makeText(this, "تم حفظ رقم المرافق", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_SMS_PERMISSION) return
        val number = pendingEmergencyNumber
        pendingEmergencyNumber = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && number != null) {
            storeEmergencyContact(number)
        } else {
            smsStatus.text = "لم يُمنح إذن SMS؛ الإنذار المحلي يظل يعمل دون رسالة للمرافق"
        }
    }

    private fun openAlarmAccessSettings() {
        val manager = getSystemService(NotificationManager::class.java)
        val intent = when {
            !manager.areNotificationsEnabled() -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            Build.VERSION.SDK_INT >= 34 && !manager.canUseFullScreenIntent() ->
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(Uri.parse("package:$packageName"))
            Build.VERSION.SDK_INT >= 26 -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, "help_alarm")
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", packageName, null))
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null)))
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null)))
        }
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val component = "${packageName}/${ShutterKeyService::class.java.name}"
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == ShutterKeyService::class.java.name } ||
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains(component, ignoreCase = true) == true
    }

    private fun hasRequiredPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun renderState() {
        if (!::status.isInitialized) return
        val monitoring = AlarmState.monitoring(this)
        val connected = AlarmState.connected(this)
        val keyServiceEnabled = isAccessibilityEnabled()
        status.text = when {
            AlarmState.alarming(this) -> "إنذار المساعدة يعمل الآن"
            monitoring && !keyServiceEnabled -> "فعّل استقبال زر الريموت"
            monitoring -> "المراقبة تعمل"
            else -> "المراقبة متوقفة"
        }
        statusDot.setTextColor(when {
            AlarmState.alarming(this) -> 0xFFE44E4E.toInt()
            monitoring && keyServiceEnabled -> 0xFF199B72.toInt()
            else -> 0xFF9AA9AD.toInt()
        })
        remoteStatus.text = if (connected) "الريموت ظاهر للنظام كجهاز إدخال" else "الريموت غير ظاهر للنظام"
        serviceStatus.text = if (keyServiceEnabled) "استقبال زر الريموت مفعّل" else "استقبال زر الريموت يحتاج تفعيلًا"
        gattStatus.text = if (AlarmState.gattConnected(this)) "اتصال BLE الخاص بالتطبيق قائم" else "اتصال BLE يعيد المحاولة عند الحاجة"
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        batteryStatus.text = if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            "منقذ مستثنى من تحسين البطارية"
        } else {
            "تحسين البطارية قد يقيّد المراقبة بالخلفية"
        }
        lastKeyStatus.text = "آخر حدث: ${AlarmState.lastKey(this)}"
        startButton.text = when {
            !keyServiceEnabled -> "فعّل استقبال زر الريموت"
            monitoring -> "المراقبة نشطة"
            else -> "ابدأ المراقبة"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notificationsEnabled = notificationManager.areNotificationsEnabled()
        val fullScreenAllowed = Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent()
        val alarmChannelEnabled = Build.VERSION.SDK_INT < 26 ||
            (notificationManager.getNotificationChannel("help_alarm")?.importance ?: NotificationManager.IMPORTANCE_HIGH) >= NotificationManager.IMPORTANCE_HIGH
        alarmAccessStatus.text = when {
            !notificationsEnabled -> "إشعارات منقذ متوقفة؛ شاشة الإنذار لن تظهر حتى تفعيلها."
            !fullScreenAllowed -> "اسمح لمنقذ بعرض شاشة الإنذار فوق القفل من إعدادات أندرويد."
            !alarmChannelEnabled -> "ارفع أهمية قناة إنذار المساعدة من إعدادات الإشعارات."
            else -> "إشعارات الإنذار وظهوره على شاشة القفل جاهزان."
        }
        alarmAccessButton.text = when {
            !notificationsEnabled -> "تفعيل إشعارات منقذ"
            !fullScreenAllowed -> "السماح بشاشة الإنذار فوق القفل"
            !alarmChannelEnabled -> "إعداد قناة إنذار المساعدة"
            else -> "إعداد إشعارات الإنذار"
        }
        alarmAccessButton.visibility = if (!notificationsEnabled || !fullScreenAllowed || !alarmChannelEnabled) View.VISIBLE else View.GONE
        smsStatus.text = when {
            AlarmState.emergencyNumber(this).isBlank() -> "لم يتم إعداد رقم مرافق — الإنذار محلي فقط"
            checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED -> "الرقم محفوظ لكن إذن SMS غير ممنوح؛ الإنذار المحلي يظل يعمل"
            else -> AlarmState.smsStatus(this)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PERMISSIONS = 51
        private const val REQUEST_SMS_PERMISSION = 52
        private const val BG = 0xFFF4F8F7.toInt()
        private const val INK = 0xFF193238.toInt()
        private const val MUTED = 0xFF6C8084.toInt()
        private const val TEAL_DARK = 0xFF08736F.toInt()
    }
}
