package ai.guiji.duix.test.ui.activity

import android.app.Activity
import android.content.Intent
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import ai.guiji.duix.test.service.AiConfig
import ai.guiji.duix.test.service.ModelManager

/**
 * 终极简化版MainActivity
 *
 * 职责：
 * 1. 选择数字人模型
 * 2. 下载所需模型文件
 * 3. 进入数字人对话
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BG_COLOR = 0xFF0099CC.toInt()
        private const val TEXT_COLOR = 0xFFFFFFFF.toInt()
        private const val CARD_COLOR = 0xFFFFFFFF.toInt()
        private const val CARD_TEXT_COLOR = 0xFF1A1A2E.toInt()
        private const val PRIMARY_COLOR = 0xFF00D4FF.toInt()
        private const val SUCCESS_COLOR = 0xFF22C55E.toInt()
        private const val ERROR_COLOR = 0xFFEF4444.toInt()
        private const val DISABLED_COLOR = 0xFF9CA3AF.toInt()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val modelManager = ModelManager()

    private var mSelectedModel: String? = null  // 当前选中的模型文件名
    private var mStatusText: TextView? = null
    private var mDownloadProgress: ProgressBar? = null
    private var mDownloadStatus: TextView? = null
    private var mDownloadSection: LinearLayout? = null
    private var mPlayButton: Button? = null

    // 模型卡片控件引用
    private var mCardXiaoben: LinearLayout? = null
    private var mCardAiruike: LinearLayout? = null
    private var mStatusXiaoben: TextView? = null
    private var mStatusAiruike: TextView? = null
    private var mBtnDownloadXiaoben: Button? = null
    private var mBtnDownloadAiruike: Button? = null

    /**
     * 模型卡片数据
     */
    private data class ModelCardViews(
        val card: LinearLayout,
        val statusView: TextView,
        val downloadBtn: Button
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "===== MainActivity.onCreate 开始 =====")
        try {
            super.onCreate(savedInstanceState)
        } catch (e: Throwable) {
            Log.e(TAG, "super.onCreate 失败", e)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.statusBarColor = 0xFF0099CC.toInt()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "设置窗口标志失败", e)
        }

        try {
            createSafeUI()
            Log.i(TAG, "===== UI创建成功 =====")
        } catch (e: Throwable) {
            Log.e(TAG, "createSafeUI 失败", e)
            createEmergencyUI(e.message ?: "未知错误")
            return
        }

        try {
            Toast.makeText(this, "DUIX 数字人 v4.3.0 已启动", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Toast显示失败", e)
        }

        // 刷新模型状态
        refreshModelStatus()
    }

    override fun onResume() {
        super.onResume()
        // 每次回到主页都刷新模型状态
        refreshModelStatus()
    }

    private fun createSafeUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_COLOR)
            setPadding(0, 80, 0, 0)
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
            setPadding(32, 0, 32, 24)
        }
        rootLayout.addView(subtitleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 全局状态文本
        mStatusText = TextView(this).apply {
            text = "正在检查模型状态..."
            textSize = 14f
            setTextColor(CARD_TEXT_COLOR)
            setBackgroundColor(CARD_COLOR)
            setPadding(24, 16, 24, 16)
        }
        val statusParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(24, 0, 24, 16)
        }
        rootLayout.addView(mStatusText, statusParams)

        // 滚动容器
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
        }

        // 模型1卡片 - 小本
        val xiaobenCard = createModelCard(
            name = "小本 (bend3)",
            desc = "数字人模型 1"
        ) {
            onModelCardClicked(AiConfig.MODEL_NAME_XIAOBEN, AiConfig.MODEL_XIAOBEN_URL)
        }
        mCardXiaoben = xiaobenCard.card
        mStatusXiaoben = xiaobenCard.statusView
        mBtnDownloadXiaoben = xiaobenCard.downloadBtn
        scrollContent.addView(mCardXiaoben)

        // 模型2卡片 - 艾瑞克
        val airuikeCard = createModelCard(
            name = "艾瑞克 (airuike)",
            desc = "数字人模型 2"
        ) {
            onModelCardClicked(AiConfig.MODEL_NAME_AIRUIKE, AiConfig.MODEL_AIRUIKE_URL)
        }
        mCardAiruike = airuikeCard.card
        mStatusAiruike = airuikeCard.statusView
        mBtnDownloadAiruike = airuikeCard.downloadBtn
        scrollContent.addView(mCardAiruike)

        // 下载进度区（默认隐藏）
        mDownloadSection = createDownloadSection()
        mDownloadSection?.visibility = View.GONE
        scrollContent.addView(mDownloadSection)

        scrollView.addView(scrollContent)
        val scrollParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        rootLayout.addView(scrollView, scrollParams)

        // 开始对话按钮
        mPlayButton = Button(this).apply {
            text = "请先选择并下载模型"
            textSize = 18f
            setTextColor(TEXT_COLOR)
            setBackgroundColor(DISABLED_COLOR)
            isEnabled = false
            setOnClickListener { onPlayClicked() }
        }
        val playParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            140
        ).apply {
            setMargins(24, 16, 24, 32)
        }
        rootLayout.addView(mPlayButton, playParams)

        setContentView(rootLayout)
    }

    private fun createModelCard(
        name: String,
        desc: String,
        onClick: () -> Unit
    ): ModelCardViews {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARD_COLOR)
            setPadding(20, 20, 20, 20)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        // 顶部行：图标 + 名称 + 状态
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconView = TextView(this).apply {
            text = name.substring(0, 1)
            textSize = 24f
            setTextColor(PRIMARY_COLOR)
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFEEEDFF.toInt())
        }
        val iconParams = LinearLayout.LayoutParams(80, 80).apply {
            setMargins(0, 0, 20, 0)
        }
        topRow.addView(iconView, iconParams)

        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val nameView = TextView(this).apply {
            text = name
            textSize = 18f
            setTextColor(CARD_TEXT_COLOR)
        }
        val statusView = TextView(this).apply {
            text = "检查中..."
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 4, 0, 0)
        }
        infoLayout.addView(nameView)
        infoLayout.addView(statusView)
        val infoParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        topRow.addView(infoLayout, infoParams)

        card.addView(topRow)

        // 底部行：下载按钮
        val btnDownload = Button(this).apply {
            text = "下载模型"
            textSize = 14f
            setTextColor(TEXT_COLOR)
            setBackgroundColor(PRIMARY_COLOR)
            setOnClickListener { onClick() }
        }
        val btnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 16, 0, 0)
        }
        card.addView(btnDownload, btnParams)

        val cardParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 12)
        }
        card.layoutParams = cardParams
        return ModelCardViews(card, statusView, btnDownload)
    }

    private fun createDownloadSection(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x33FFFFFF.toInt())
            setPadding(20, 20, 20, 20)
        }
        val titleView = TextView(this).apply {
            text = "下载进度"
            textSize = 14f
            setTextColor(TEXT_COLOR)
        }
        section.addView(titleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        mDownloadStatus = TextView(this).apply {
            text = "等待中..."
            textSize = 12f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, 8, 0, 8)
        }
        section.addView(mDownloadStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        mDownloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val progressParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        section.addView(mDownloadProgress, progressParams)

        val sectionParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 16, 0, 0)
        }
        section.layoutParams = sectionParams
        return section
    }

    private fun createEmergencyUI(errorMessage: String) {
        try {
            val rootLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(PRIMARY_COLOR)
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
            Log.e(TAG, "紧急UI也创建失败", e)
        }
    }

    /**
     * 刷新模型状态显示
     */
    private fun refreshModelStatus() {
        Thread {
            try {
                val baseReady = modelManager.isBaseConfigReady(this)
                val xiaobenReady = modelManager.isModelReady(this, AiConfig.MODEL_NAME_XIAOBEN)
                val airuikeReady = modelManager.isModelReady(this, AiConfig.MODEL_NAME_AIRUIKE)

                val statusText = StringBuilder()
                statusText.append("基础资源: ${if (baseReady) "✓ 已就绪" else "✗ 未下载"}\n")
                statusText.append("小本: ${if (xiaobenReady) "✓ 已下载" else "○ 未下载"}\n")
                statusText.append("艾瑞克: ${if (airuikeReady) "✓ 已下载" else "○ 未下载"}")

                mainHandler.post {
                    try {
                        mStatusText?.text = statusText.toString()

                        // 更新每个模型卡片的状态
                        updateCardStatus(mStatusXiaoben, mBtnDownloadXiaoben, xiaobenReady && baseReady)
                        updateCardStatus(mStatusAiruike, mBtnDownloadAiruike, airuikeReady && baseReady)

                        // 自动选择第一个已下载的模型
                        if (mSelectedModel == null) {
                            if (xiaobenReady && baseReady) {
                                mSelectedModel = AiConfig.MODEL_NAME_XIAOBEN
                                highlightSelectedCard(mCardXiaoben, true)
                            } else if (airuikeReady && baseReady) {
                                mSelectedModel = AiConfig.MODEL_NAME_AIRUIKE
                                highlightSelectedCard(mCardAiruike, true)
                            }
                        }
                        updatePlayButton()
                    } catch (e: Throwable) {
                        Log.e(TAG, "更新UI失败", e)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "检查模型状态失败", e)
                mainHandler.post {
                    mStatusText?.text = "检查模型失败: ${e.message}"
                }
            }
        }.start()
    }

    private fun updateCardStatus(
        statusView: TextView?,
        downloadBtn: Button?,
        ready: Boolean
    ) {
        if (statusView == null || downloadBtn == null) return
        if (ready) {
            statusView.text = "✓ 已下载，可以对话"
            statusView.setTextColor(SUCCESS_COLOR)
            downloadBtn.text = "已下载"
            downloadBtn.setBackgroundColor(SUCCESS_COLOR)
        } else {
            statusView.text = "○ 未下载，点击下载"
            statusView.setTextColor(0xFF6B7280.toInt())
            downloadBtn.text = "下载模型"
            downloadBtn.setBackgroundColor(PRIMARY_COLOR)
        }
    }

    /**
     * 点击模型卡片或下载按钮
     */
    private fun onModelCardClicked(modelName: String, modelUrl: String) {
        Log.i(TAG, "点击模型: $modelName")
        try {
            // 先高亮显示选中的卡片
            mSelectedModel = modelName
            highlightSelectedCard(
                if (modelName == AiConfig.MODEL_NAME_XIAOBEN) mCardXiaoben else mCardAiruike,
                true
            )
            highlightSelectedCard(
                if (modelName == AiConfig.MODEL_NAME_XIAOBEN) mCardAiruike else mCardXiaoben,
                false
            )

            // 检查是否已下载
            val baseReady = modelManager.isBaseConfigReady(this)
            val modelReady = modelManager.isModelReady(this, modelName)

            if (baseReady && modelReady) {
                Toast.makeText(this, "已选择: $modelName", Toast.LENGTH_SHORT).show()
                updatePlayButton()
            } else {
                // 启动下载流程
                startDownload(baseReady, modelName, modelUrl)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onModelCardClicked 失败", e)
            Toast.makeText(this, "操作失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun highlightSelectedCard(card: LinearLayout?, selected: Boolean) {
        if (card == null) return
        if (selected) {
            card.setBackgroundColor(0xFFEEEDFF.toInt())
        } else {
            card.setBackgroundColor(CARD_COLOR)
        }
    }

    /**
     * 启动下载流程：先下载基础资源，再下载模型
     */
    private fun startDownload(
        baseReady: Boolean,
        modelName: String,
        modelUrl: String
    ) {
        if (mDownloadSection?.visibility != View.VISIBLE) {
            mDownloadSection?.visibility = View.VISIBLE
        }
        mDownloadProgress?.progress = 0
        mDownloadStatus?.text = "准备下载..."
        mDownloadStatus?.setTextColor(0xCCFFFFFF.toInt())

        // 先下载基础资源
        if (!baseReady) {
            mDownloadStatus?.text = "正在下载基础资源..."
            modelManager.downloadBaseConfig(this, object : ModelManager.DownloadCallback {
                override fun onDownloadStart() {
                    mainHandler.post { mDownloadStatus?.text = "开始下载基础资源..." }
                }
                override fun onDownloadProgress(current: Long, total: Long) {
                    val percent = if (total > 0) (current * 100 / total).toInt() else 0
                    mainHandler.post {
                        mDownloadProgress?.progress = percent
                        mDownloadStatus?.text = "下载基础资源: $percent% (${formatSize(current)}/${formatSize(total)})"
                    }
                }
                override fun onUnzipProgress(current: Long, total: Long) {
                    val percent = if (total > 0) (current * 100 / total).toInt() else 0
                    mainHandler.post {
                        mDownloadProgress?.progress = percent
                        mDownloadStatus?.text = "解压基础资源: $percent%"
                    }
                }
                override fun onDownloadComplete() {
                    Log.i(TAG, "基础资源下载完成")
                    // 基础资源下载完成后，开始下载模型
                    downloadSpecificModel(modelName, modelUrl)
                }
                override fun onDownloadFail(code: Int, message: String) {
                    mainHandler.post {
                        mDownloadStatus?.text = "基础资源下载失败: $message"
                        mDownloadStatus?.setTextColor(ERROR_COLOR)
                        Toast.makeText(this@MainActivity, "基础资源下载失败: $message", Toast.LENGTH_LONG).show()
                    }
                }
            })
        } else {
            downloadSpecificModel(modelName, modelUrl)
        }
    }

    private fun downloadSpecificModel(modelName: String, modelUrl: String) {
        mDownloadStatus?.text = "正在下载模型: $modelName"
        modelManager.downloadModel(this, modelUrl, object : ModelManager.DownloadCallback {
            override fun onDownloadStart() {
                mainHandler.post { mDownloadStatus?.text = "开始下载模型..." }
            }
            override fun onDownloadProgress(current: Long, total: Long) {
                val percent = if (total > 0) (current * 100 / total).toInt() else 0
                mainHandler.post {
                    mDownloadProgress?.progress = percent
                    mDownloadStatus?.text = "下载模型: $percent% (${formatSize(current)}/${formatSize(total)})"
                }
            }
            override fun onUnzipProgress(current: Long, total: Long) {
                val percent = if (total > 0) (current * 100 / total).toInt() else 0
                mainHandler.post {
                    mDownloadProgress?.progress = percent
                    mDownloadStatus?.text = "解压模型: $percent%"
                }
            }
            override fun onDownloadComplete() {
                Log.i(TAG, "模型下载完成: $modelName")
                mainHandler.post {
                    mDownloadProgress?.progress = 100
                    mDownloadStatus?.text = "✓ 下载完成"
                    mDownloadStatus?.setTextColor(SUCCESS_COLOR)
                    Toast.makeText(this@MainActivity, "模型下载完成: $modelName", Toast.LENGTH_SHORT).show()
                    // 刷新状态
                    refreshModelStatus()
                }
            }
            override fun onDownloadFail(code: Int, message: String) {
                mainHandler.post {
                    mDownloadStatus?.text = "模型下载失败: $message"
                    mDownloadStatus?.setTextColor(ERROR_COLOR)
                    Toast.makeText(this@MainActivity, "模型下载失败: $message", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        if (bytes < 1024L * 1024 * 1024) return "${bytes / (1024 * 1024)} MB"
        return "${bytes / (1024L * 1024 * 1024)} GB"
    }

    private fun updatePlayButton() {
        if (mSelectedModel == null) {
            mPlayButton?.text = "请先选择并下载模型"
            mPlayButton?.isEnabled = false
            mPlayButton?.setBackgroundColor(DISABLED_COLOR)
            return
        }
        val baseReady = modelManager.isBaseConfigReady(this)
        val modelReady = modelManager.isModelReady(this, mSelectedModel!!)
        if (baseReady && modelReady) {
            mPlayButton?.text = "开始对话"
            mPlayButton?.isEnabled = true
            mPlayButton?.setBackgroundColor(PRIMARY_COLOR)
        } else {
            mPlayButton?.text = "请先下载模型"
            mPlayButton?.isEnabled = false
            mPlayButton?.setBackgroundColor(DISABLED_COLOR)
        }
    }

    /**
     * 点击开始对话
     */
    private fun onPlayClicked() {
        try {
            val modelName = mSelectedModel
            if (modelName == null) {
                Toast.makeText(this, "请先选择模型", Toast.LENGTH_SHORT).show()
                return
            }
            val baseReady = modelManager.isBaseConfigReady(this)
            val modelReady = modelManager.isModelReady(this, modelName)
            if (!baseReady || !modelReady) {
                Toast.makeText(this, "模型未下载完成", Toast.LENGTH_SHORT).show()
                refreshModelStatus()
                return
            }
            Toast.makeText(this, "启动数字人对话...", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(this, CallActivity::class.java)
                intent.putExtra("modelName", modelName)
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
