package ai.guiji.duix.test.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import ai.guiji.duix.test.R
import ai.guiji.duix.test.ui.activity.CallActivity
import kotlin.math.abs

/**
 * [P2-C] 可拖拽悬浮窗服务
 *
 * 行为：
 * - 80x80dp 圆形悬浮窗（数字人品牌渐变）
 * - 长按 300ms 进入拖拽模式，移动到手指位置
 * - 短按（< 300ms）：把 CallActivity 拉到前台
 * - 右上角 X：stopSelf() 销毁悬浮窗
 * - 边缘吸附：拖拽释放时自动贴到屏幕左/右边缘
 *
 * 权限：依赖 SYSTEM_ALERT_WINDOW（API 23+），需用户到设置手动授权
 * 启动：startService(Intent(this, FloatingWindowService::class.java))
 * 停止：stopService(Intent(...))
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "duix_floating_window"
        private const val NOTIFICATION_ID = 1001
        // 长按进入拖拽模式的判定阈值
        private const val LONG_PRESS_THRESHOLD_MS = 300L
        // 移动多少像素后认为是"拖拽"而非"长按"
        private const val TOUCH_SLOP_PX = 12
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // 拖拽状态
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTimeMs = 0L
    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startInForeground()
        addFloatingView()
    }

    private fun startInForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "数字人悬浮窗",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "保持数字人悬浮窗运行"
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(channel)
                }
            }
            val openIntent = Intent(this, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle("数字人运行中")
                    .setContentText("点击返回通话")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle("数字人运行中")
                    .setContentText("点击返回通话")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build()
            }
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "startInForeground 异常", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addFloatingView() {
        try {
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.floating_window, null) as View
            floatingView = view

            // 关闭按钮
            view.findViewById<ImageView>(R.id.ivFloatingClose).setOnClickListener {
                Log.i(TAG, "用户点击关闭按钮，停止悬浮窗")
                Toast.makeText(this, "已关闭数字人悬浮窗", Toast.LENGTH_SHORT).show()
                stopSelf()
            }

            // 拖拽高亮环
            val dragRing = view.findViewById<View>(R.id.vFloatingDragRing)

            // 计算悬浮窗 LayoutParams（API 26+ 用 TYPE_APPLICATION_OVERLAY）
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // 默认位置：右上角
                val dm = resources.displayMetrics
                x = dm.widthPixels - dpToPx(96)
                y = dm.heightPixels / 3
            }
            layoutParams = params

            // 触摸处理：长按拖拽 + 短按回 Activity
            view.setOnTouchListener { _, event ->
                handleTouch(event, view, params, dragRing)
            }

            windowManager.addView(view, params)
            Log.i(TAG, "悬浮窗已添加: x=${params.x}, y=${params.y}")
        } catch (e: Exception) {
            Log.e(TAG, "添加悬浮窗失败", e)
            stopSelf()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(
        event: MotionEvent,
        view: View,
        params: WindowManager.LayoutParams,
        dragRing: View?
    ): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downTimeMs = System.currentTimeMillis()
                isDragging = false
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging && (abs(dx) > TOUCH_SLOP_PX || abs(dy) > TOUCH_SLOP_PX)) {
                    isDragging = true
                    dragRing?.visibility = View.VISIBLE
                    performHapticFeedback(view)
                }
                if (isDragging) {
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "updateViewLayout 异常", e)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragRing?.visibility = View.GONE
                val duration = System.currentTimeMillis() - downTimeMs
                if (!isDragging && duration < LONG_PRESS_THRESHOLD_MS) {
                    // 短按：把 CallActivity 拉到前台
                    Log.i(TAG, "短按悬浮窗：恢复 CallActivity")
                    val intent = Intent(this, CallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(intent)
                } else if (isDragging) {
                    // 拖拽释放：边缘吸附
                    snapToEdge(params, view)
                }
                isDragging = false
                return true
            }
        }
        return false
    }

    private fun snapToEdge(params: WindowManager.LayoutParams, view: View) {
        try {
            val dm = resources.displayMetrics
            val viewWidth = view.width.takeIf { it > 0 } ?: dpToPx(80)
            val midpoint = dm.widthPixels / 2
            // x 已经被更新过；这里只把 y 钳制到屏幕内
            val maxY = dm.heightPixels - view.height - dpToPx(80)
            params.y = params.y.coerceIn(0, maxY)
            // 吸附到左或右
            val targetX = if (params.x + viewWidth / 2 < midpoint) {
                dpToPx(8)
            } else {
                dm.widthPixels - viewWidth - dpToPx(8)
            }
            // 简易吸附动画：用 WindowManager 多次 update
            val startX = params.x
            val startY = params.y
            val steps = 8
            for (i in 1..steps) {
                val frac = i.toFloat() / steps
                params.x = startX + ((targetX - startX) * frac).toInt()
                params.y = startY + ((0) * frac).toInt()
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) { /* 静默 */ }
                Thread.sleep(15)
            }
        } catch (e: Exception) {
            Log.e(TAG, "snapToEdge 异常", e)
        }
    }

    private fun performHapticFeedback(view: View) {
        try {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        } catch (e: Exception) { /* 静默 */ }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        try {
            floatingView?.let {
                windowManager.removeView(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "移除悬浮窗失败", e)
        }
        floatingView = null
        Log.i(TAG, "FloatingWindowService 销毁")
    }
}
