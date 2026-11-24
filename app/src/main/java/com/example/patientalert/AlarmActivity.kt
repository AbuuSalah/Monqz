package com.example.patientalert

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class AlarmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFF781922.toInt()
        window.navigationBarColor = 0xFF781922.toInt()

        val wide = resources.configuration.screenWidthDp >= 700 &&
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val root = FrameLayout(this).apply {
            foregroundGravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFB52A34.toInt(), 0xFF781922.toInt())
            )
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val content = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(28), dp(26), dp(28), dp(26))
            background = GradientDrawable().apply {
                setColor(0xFF9E202B.toInt())
                cornerRadius = dp(28).toFloat()
            }
            elevation = dp(8).toFloat()
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        details.addView(TextView(this).apply {
            text = "!"
            textSize = if (wide) 48f else 56f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFB52A34.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }, LinearLayout.LayoutParams(dp(100), dp(100)).apply { bottomMargin = dp(20) })
        details.addView(TextView(this).apply {
            text = "منقذ"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFDCDD.toInt())
            setPadding(0, 0, 0, dp(6))
        })
        details.addView(TextView(this).apply {
            text = "إنذار المساعدة"
            textSize = if (wide) 28f else 31f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(10))
        })
        details.addView(TextView(this).apply {
            text = "تم الضغط على زر طلب المساعدة"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFE9E9.toInt())
        })
        content.addView(details, LinearLayout.LayoutParams(
            if (wide) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            if (wide) 1f else 0f
        ).apply { if (wide) marginEnd = dp(20) else bottomMargin = dp(24) })

        content.addView(TextView(this).apply {
            text = "إيقاف صوت الإنذار"
            textSize = 19f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF8D2027.toInt())
            minHeight = dp(70)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startService(Intent(this@AlarmActivity, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_STOP_ALARM))
                finish()
            }
        }, LinearLayout.LayoutParams(
            if (wide) dp(300) else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val maxWidth = resources.configuration.screenWidthDp.coerceAtMost(if (wide) 1080 else 600)
        root.addView(content, FrameLayout.LayoutParams(dp(maxWidth), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
