package ai.guiji.duix.test.ui.activity

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 终极简化版MainActivity
 *
 * 设计原则：
 * 1. 不继承 AppCompatActivity，避免 AppCompat 兼容性问题
 * 2. 不使用任何第三方主题，使用系统默认 Theme.Material.Light.NoActionBar
 * 3. 不使用 ViewBinding/数据绑定，避免任何资源加载问题
 * 4. 第一行就设置高对比度背景的 ScrollView，确保任何时候都看得到内容
 * 5. 不在 onCreate 中执行任何可能崩溃的操作
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BG_COLOR = 0xFF6C63FF.toInt()  // 紫色背景 - 醒目，绝不会是白色
        private const val TEXT_COLOR = 0xFFFFFFFF.toInt() // 白色文字
        private const val CARD_COLOR = 0xFFFFFFFF.toInt()
        private const val CARD_TEXT_COLOR = 0xFF1A1A2E.toInt()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        // 第一步：最激进地设置 - 直接在 super.onCreate 之前先建立日志
        Log.i(TAG, "===== MainActivity.onCreate 开始 v4.2.0 =====")

        try {
            super.onCreate(savedInstanceState)
        } catch (e: Throwable) {
            // 连 super.onCreate 都失败 - 记录到logcat
            Log.e(TAG, "super.onCreate 失败", e)
            // 仍然尝试继续
        }

        // 第二步：设置窗口标志 - 不让 edge-to-edge 干扰
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.statusBarColor = 0xFF5A52D5.toInt()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "设置窗口标志失败", e)
        }

        // 第三步：立即创建UI - 第一行就 setContentView
        try {
            createSafeUI()
            Log.i(TAG, "===== UI创建成功 =====")
        } catch (e: Throwable) {
            // 最后的fallback - 用代码创建最简单的UI
            Log.e(TAG, "createSafeUI 失败，使用fallback", e)
            createEmergencyUI(e.message ?: "未知错误")
        }

        // 第四步：显示一个Toast告知用户
        try {
            Toast.makeText(this, "DUIX 数字人 v4.2.0 已启动", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Toast显示失败", e)
        }

        // 第五步：延迟检查模型状态 - 不阻塞UI
        mainHandler.postDelayed({
            try {
                checkModelStatus()
            } catch (e: Throwable) {
                Log.e(TAG, "检查模型状态失败", e)
            }
        }, 500)
    }

    /**
     * 创建主UI - 使用ScrollView确保任何屏幕都能看到内容
     */
    private fun createSafeUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_COLOR)
            setPadding(0, 80, 0, 0)  // 顶部留出状态栏空间
        }

        // 标题
        val titleView = TextView(this).apply {
            text = "DUIX 数字人"
            textSize = 32f
            setTextColor(TEXT_COLOR)
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 8)
        }
        rootLayout.addView(titleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 副标题
        val subtitleView = TextView(this).apply {
            text = "Powered by Agnes AI"
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(32, 0, 32, 40)
        }
        rootLayout.addView(subtitleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 状态文本 - 用白色卡片背景+深色文字，确保能看清
        val statusView = TextView(this).apply {
            text = "正在初始化..."
            textSize = 14f
            setTextColor(CARD_TEXT_COLOR)
            setBackgroundColor(CARD_COLOR)
            setPadding(24, 16, 24, 16)
            id = View.generateViewId()
        }
        val statusParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(24, 0, 24, 24)
        }
        rootLayout.addView(statusView, statusParams)

        // 滚动容器
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
        }

        // 模型1选择按钮 - 小本
        scrollContent.addView(createModelCard("小本 (bend3)", "数字人模型 1") {
            onModelSelected(0, "小本")
        })

        // 模型2选择按钮 - 艾瑞克
        scrollContent.addView(createModelCard("艾瑞克 (airuike)", "数字人模型 2") {
            onModelSelected(1, "艾瑞克")
        })

        // 提示信息
        val tipsView = TextView(this).apply {
            text = "提示：\n• 首次使用需要下载模型文件\n• 请确保网络畅通\n• 模型会自动保存到本地"
            textSize = 12f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(16, 24, 16, 24)
        }
        val tipsParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 16, 0, 16)
        }
        scrollContent.addView(tipsView, tipsParams)

        scrollView.addView(scrollContent)
        val scrollParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        rootLayout.addView(scrollView, scrollParams)

        // 开始对话按钮 - 固定在底部
        val playButton = Button(this).apply {
            text = "开始对话"
            textSize = 18f
            setTextColor(TEXT_COLOR)
            setBackgroundColor(0xFF5A52D5.toInt())
            setOnClickListener { onPlayClicked() }
        }
        val playParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            140
        ).apply {
            setMargins(24, 16, 24, 32)
        }
        rootLayout.addView(playButton, playParams)

        setContentView(rootLayout)
    }

    /**
     * 创建模型选择卡片
     */
    private fun createModelCard(name: String, desc: String, onClick: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CARD_COLOR)
            setPadding(20, 20, 20, 20)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        // 图标
        val iconView = TextView(this).apply {
            text = name.substring(0, 1)
            textSize = 24f
            setTextColor(0xFF6C63FF.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFEEEDFF.toInt())
        }
        val iconParams = LinearLayout.LayoutParams(80, 80).apply {
            setMargins(0, 0, 20, 0)
        }
        card.addView(iconView, iconParams)

        // 信息
        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val nameView = TextView(this).apply {
            text = name
            textSize = 18f
            setTextColor(CARD_TEXT_COLOR)
        }
        val descView = TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 4, 0, 0)
        }
        infoLayout.addView(nameView)
        infoLayout.addView(descView)

        val infoParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        card.addView(infoLayout, infoParams)

        val cardParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 12)
        }
        card.layoutParams = cardParams
        return card
    }

    /**
     * 紧急UI - 当主UI创建失败时使用
     */
    private fun createEmergencyUI(errorMessage: String) {
        try {
            val rootLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF6C63FF.toInt())
                setPadding(48, 80, 48, 48)
            }

            val titleView = TextView(this).apply {
                text = "DUIX 数字人"
                textSize = 32f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            rootLayout.addView(titleView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            val errorView = TextView(this).apply {
                text = "UI加载出现错误:\n$errorMessage\n\n请重启应用重试"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(0, 32, 0, 0)
            }
            rootLayout.addView(errorView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            setContentView(rootLayout)
        } catch (e: Throwable) {
            // 真的连最简单的UI都失败 - 什么都不做了
            Log.e(TAG, "紧急UI也创建失败", e)
        }
    }

    /**
     * 检查模型状态
     */
    private fun checkModelStatus() {
        Thread {
            try {
                val duixDir = getExternalFilesDir("duix")?.absolutePath
                    ?: filesDir.absolutePath + "/duix"

                val model1Dir = java.io.File(duixDir, "model/bendi3_20240518")
                val model2Dir = java.io.File(duixDir, "model/airuike_20240409")

                val model1Ready = model1Dir.exists() && model1Dir.isDirectory
                val model2Ready = model2Dir.exists() && model2Dir.isDirectory

                mainHandler.post {
                    try {
                        updateStatusText("小本: ${if (model1Ready) "✓ 已下载" else "未下载"}\n艾瑞克: ${if (model2Ready) "✓ 已下载" else "未下载"}")
                    } catch (e: Throwable) {
                        Log.e(TAG, "更新状态文本失败", e)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "检查模型状态失败", e)
                mainHandler.post {
                    try {
                        updateStatusText("检查模型失败: ${e.message}")
                    } catch (e2: Throwable) {
                        Log.e(TAG, "更新错误状态失败", e2)
                    }
                }
            }
        }.start()
    }

    /**
     * 更新状态文本 - 通过遍历视图树找到TextView
     */
    private fun updateStatusText(text: String) {
        try {
            val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)
            updateTextViewRecursive(root, text)
        } catch (e: Throwable) {
            Log.e(TAG, "更新状态文本失败", e)
        }
    }

    private fun updateTextViewRecursive(view: View?, text: String) {
        if (view == null) return
        if (view is TextView && view.id != View.NO_ID) {
            // 简单启发式：找到带有"初始化"或"检查"字样的TextView
            val currentText = view.text.toString()
            if (currentText.contains("初始化") || currentText.contains("检查") || currentText.contains("下载")) {
                view.text = text
                return
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                updateTextViewRecursive(view.getChildAt(i), text)
            }
        }
    }

    /**
     * 模型被选中
     */
    private fun onModelSelected(index: Int, name: String) {
        try {
            Toast.makeText(this, "已选择: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "显示Toast失败", e)
        }
    }

    /**
     * 点击开始对话
     */
    private fun onPlayClicked() {
        try {
            Toast.makeText(this, "启动数字人对话中...", Toast.LENGTH_SHORT).show()
            // 启动CallActivity - 用try-catch确保不崩溃
            try {
                val intent = android.content.Intent(this, CallActivity::class.java)
                intent.putExtra("modelUrl", "https://www.enlyai.com/downloads/duix/models/")
                intent.putExtra("debug", false)
                startActivity(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "启动CallActivity失败", e)
                Toast.makeText(this, "启动对话失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onPlayClicked失败", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        Log.i(TAG, "===== MainActivity.onDestroy =====")
    }
}
