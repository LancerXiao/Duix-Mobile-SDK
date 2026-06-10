package ai.guiji.duix.test.ui.activity

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

import ai.guiji.duix.test.R
import ai.guiji.duix.test.service.AiConfig
import ai.guiji.duix.test.service.ModelManager

/**
 * MainActivity - 首页
 *
 * 职责：
 * 1. 选择数字人模型
 * 2. 下载所需模型文件
 * 3. 进入数字人对话
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SUCCESS_COLOR = 0xFF22C55E.toInt()
        private const val ERROR_COLOR = 0xFFEF4444.toInt()
        private const val DISABLED_COLOR = 0xFF4B5563.toInt()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val modelManager = ModelManager()

    private var mSelectedModel: String? = null

    // UI 控件
    private var mStatusText: TextView? = null
    private var mDownloadProgress: ProgressBar? = null
    private var mDownloadStatus: TextView? = null
    private var mDownloadSection: LinearLayout? = null
    private var mPlayButton: TextView? = null
    private var mTvSdkVersion: TextView? = null
    private var mTvUpdateHint: TextView? = null

    // 模型卡片控件引用
    private var mCardXiaoben: LinearLayout? = null
    private var mCardAiruike: LinearLayout? = null
    private var mStatusXiaoben: TextView? = null
    private var mStatusAiruike: TextView? = null
    private var mBtnDownloadXiaoben: TextView? = null
    private var mBtnDownloadAiruike: TextView? = null
    private var mCheckXiaoben: ImageView? = null
    private var mCheckAiruike: ImageView? = null

    private data class ModelCardViews(
        val card: LinearLayout,
        val statusView: TextView,
        val downloadBtn: TextView,
        val checkMark: ImageView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "===== MainActivity.onCreate =====")
        try {
            super.onCreate(savedInstanceState)
        } catch (e: Throwable) {
            Log.e(TAG, "super.onCreate 失败", e)
        }

        try {
            setContentView(R.layout.activity_main)
            bindViews()
            setupVersionInfo()
            Log.i(TAG, "===== UI创建成功 =====")
        } catch (e: Throwable) {
            Log.e(TAG, "setContentView 失败", e)
            createEmergencyUI(e.message ?: "未知错误")
            return
        }

        // 刷新模型状态
        refreshModelStatus()
        // 检查更新
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        refreshModelStatus()
        checkForUpdate()
    }

    private fun bindViews() {
        mTvSdkVersion = findViewById(R.id.tvSdkVersion)
        mTvUpdateHint = findViewById(R.id.tvUpdateHint)
        mStatusText = findViewById(R.id.tvDownloadTips)

        mCardXiaoben = findViewById(R.id.cardModel1)
        mCardAiruike = findViewById(R.id.cardModel2)
        mStatusXiaoben = findViewById(R.id.tvModel1Status)
        mStatusAiruike = findViewById(R.id.tvModel2Status)
        mBtnDownloadXiaoben = findViewById(R.id.btnModel1Download)
        mBtnDownloadAiruike = findViewById(R.id.btnModel2Download)
        mCheckXiaoben = findViewById(R.id.ivModel1Check)
        mCheckAiruike = findViewById(R.id.ivModel2Check)

        mPlayButton = findViewById(R.id.btnPlay)

        // 下载进度区
        mDownloadSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x1A7C3AED.toInt())
            setPadding(20, 20, 20, 20)
        }
        mDownloadStatus = TextView(this).apply {
            text = "等待中..."
            textSize = 12f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, 8, 0, 8)
        }
        mDownloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val dlTitle = TextView(this).apply {
            text = "下载进度"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
        }
        mDownloadSection?.addView(dlTitle)
        mDownloadSection?.addView(mDownloadStatus)
        mDownloadSection?.addView(mDownloadProgress)
        mDownloadSection?.visibility = View.GONE

        // 插入到模型卡片之后
        val scrollContent = findViewById<LinearLayout>(R.id.cardModel2).parent as LinearLayout
        val idx = scrollContent.indexOfChild(mCardAiruike)
        scrollContent.addView(mDownloadSection, idx + 1)

        // 点击事件
        mCardXiaoben?.setOnClickListener {
            onModelCardClicked(AiConfig.MODEL_NAME_XIAOBEN, AiConfig.MODEL_XIAOBEN_URL)
        }
        mCardAiruike?.setOnClickListener {
            onModelCardClicked(AiConfig.MODEL_NAME_AIRUIKE, AiConfig.MODEL_AIRUIKE_URL)
        }
        mBtnDownloadXiaoben?.setOnClickListener {
            onModelCardClicked(AiConfig.MODEL_NAME_XIAOBEN, AiConfig.MODEL_XIAOBEN_URL)
        }
        mBtnDownloadAiruike?.setOnClickListener {
            onModelCardClicked(AiConfig.MODEL_NAME_AIRUIKE, AiConfig.MODEL_AIRUIKE_URL)
        }
        mPlayButton?.setOnClickListener { onPlayClicked() }

        // 设置按钮
        findViewById<ImageView>(R.id.ivSettings)?.setOnClickListener {
            showSettingsDialog()
        }
    }

    /**
     * 设置版本号信息 - 从 build.gradle 动态读取，不再硬编码
     */
    private fun setupVersionInfo() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName ?: "unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
            mTvSdkVersion?.text = "v$versionName ($versionCode)"
        } catch (e: Exception) {
            Log.e(TAG, "读取版本号失败", e)
            mTvSdkVersion?.text = "v?.?"
        }
    }

    /**
     * 检查更新 - 从服务器获取 version.json 并对比
     */
    private fun checkForUpdate() {
        Thread {
            try {
                val url = java.net.URL("https://www.enlyai.com/downloads/duix/version.json")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().readText()
                    val jsonObj = org.json.JSONObject(json)
                    val remoteCode = jsonObj.getInt("version_code")
                    val remoteName = jsonObj.optString("version_name", "")

                    val packageInfo = packageManager.getPackageInfo(packageName, 0)
                    val localCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode
                    }

                    if (remoteCode > localCode) {
                        mainHandler.post {
                            mTvUpdateHint?.text = "New version available: $remoteName, tap to update"
                            mTvUpdateHint?.visibility = View.VISIBLE
                            mTvUpdateHint?.setOnClickListener {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://www.enlyai.com/downloads/duix/"))
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "打开下载页失败", e)
                                }
                            }
                        }
                    } else {
                        mainHandler.post {
                            mTvUpdateHint?.visibility = View.GONE
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.d(TAG, "检查更新失败（网络不可达）: ${e.message}")
            }
        }.start()
    }

    /**
     * 显示设置对话框
     */
    private fun showSettingsDialog() {
        try {
            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("Settings")
                .setItems(arrayOf("LLM Engine", "TTS Engine", "ASR Engine", "About")) { _, which ->
                    when (which) {
                        0 -> showEngineSelector("LLM", "llm_engine",
                            arrayOf("Agnes AI", "MiMo"),
                            arrayOf(AiConfig.LLM_BASE_URL, AiConfig.MIMO_LLM_BASE_URL),
                            arrayOf(AiConfig.AGNES_AI_API_KEY, AiConfig.MIMO_API_KEY),
                            arrayOf(AiConfig.LLM_MODEL, AiConfig.MIMO_LLM_MODEL))
                        1 -> showTtsEngineSelector()
                        2 -> showToast("ASR engine: System default")
                        3 -> showAboutDialog()
                    }
                }
                .create()
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "显示设置对话框失败", e)
        }
    }

    private fun showEngineSelector(title: String, prefKey: String, names: Array<String>,
                                    baseUrls: Array<String>, apiKeys: Array<String>, models: Array<String>) {
        val prefs = getSharedPreferences("duix_prefs", MODE_PRIVATE)
        val current = prefs.getString(prefKey, names[0])
        val selectedIdx = names.indexOf(current).coerceAtLeast(0)

        android.app.AlertDialog.Builder(this)
            .setTitle("$title Engine")
            .setSingleChoiceItems(names, selectedIdx) { dialog, which ->
                prefs.edit().putString(prefKey, names[which]).apply()
                dialog.dismiss()
                showToast("$title engine: ${names[which]}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTtsEngineSelector() {
        val names = arrayOf("Qwen TTS", "MiMo TTS", "Edge TTS", "Android TTS")
        val prefs = getSharedPreferences("duix_prefs", MODE_PRIVATE)
        val current = prefs.getString("tts_engine", "Qwen TTS")
        val selectedIdx = names.indexOf(current).coerceAtLeast(0)

        android.app.AlertDialog.Builder(this)
            .setTitle("TTS Engine")
            .setSingleChoiceItems(names, selectedIdx) { dialog, which ->
                prefs.edit().putString("tts_engine", names[which]).apply()
                dialog.dismiss()
                showToast("TTS engine: ${names[which]}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAboutDialog() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName ?: "unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("About")
                .setMessage("DUIX Digital Human\nVersion: $versionName ($versionCode)\n\nPowered by Agnes AI\nhttps://www.enlyai.com")
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示关于对话框失败", e)
        }
    }

    private fun showToast(msg: String) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Toast失败", e)
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

                mainHandler.post {
                    try {
                        // 更新每个模型卡片的状态
                        updateCardStatus(mStatusXiaoben, mBtnDownloadXiaoben, mCheckXiaoben, xiaobenReady && baseReady)
                        updateCardStatus(mStatusAiruike, mBtnDownloadAiruike, mCheckAiruike, airuikeReady && baseReady)

                        // 自动选择第一个已下载的模型
                        if (mSelectedModel == null) {
                            if (xiaobenReady && baseReady) {
                                mSelectedModel = AiConfig.MODEL_NAME_XIAOBEN
                                highlightSelectedCard(mCardXiaoben, mCheckXiaoben, true)
                                highlightSelectedCard(mCardAiruike, mCheckAiruike, false)
                            } else if (airuikeReady && baseReady) {
                                mSelectedModel = AiConfig.MODEL_NAME_AIRUIKE
                                highlightSelectedCard(mCardAiruike, mCheckAiruike, true)
                                highlightSelectedCard(mCardXiaoben, mCheckXiaoben, false)
                            }
                        }
                        updatePlayButton()
                    } catch (e: Throwable) {
                        Log.e(TAG, "更新UI失败", e)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "检查模型状态失败", e)
            }
        }.start()
    }

    private fun updateCardStatus(
        statusView: TextView?,
        downloadBtn: TextView?,
        checkMark: ImageView?,
        ready: Boolean
    ) {
        if (statusView == null || downloadBtn == null) return
        if (ready) {
            statusView.text = "Ready to chat"
            statusView.setTextColor(SUCCESS_COLOR)
            downloadBtn.text = "Ready"
            downloadBtn.setTextColor(SUCCESS_COLOR)
            checkMark?.visibility = View.VISIBLE
        } else {
            statusView.text = "Not downloaded"
            statusView.setTextColor(0xFF9CA3AF.toInt())
            downloadBtn.text = "Download"
            downloadBtn.setTextColor(0xFFFFFFFF.toInt())
            checkMark?.visibility = View.GONE
        }
    }

    private fun highlightSelectedCard(card: LinearLayout?, checkMark: ImageView?, selected: Boolean) {
        if (card == null) return
        checkMark?.visibility = if (selected) View.VISIBLE else View.GONE
        // 选中时卡片背景变亮
        card.setBackgroundResource(if (selected) R.drawable.bg_card_selected_16 else R.drawable.bg_card_border_16)
    }

    private fun onModelCardClicked(modelName: String, modelUrl: String) {
        Log.i(TAG, "点击模型: $modelName")
        try {
            mSelectedModel = modelName
            highlightSelectedCard(
                if (modelName == AiConfig.MODEL_NAME_XIAOBEN) mCardXiaoben else mCardAiruike,
                if (modelName == AiConfig.MODEL_NAME_XIAOBEN) mCheckXiaoben else mCheckAiruike,
                true
            )
            highlightSelectedCard(
                if (modelName == AiConfig.MODEL_NAME_XIAOBEN) mCardAiruike else mCardXiaoben,
                if (modelName == AiConfig.MODEL_NAME_XIAOBEN) mCheckAiruike else mCheckXiaoben,
                false
            )

            val baseReady = modelManager.isBaseConfigReady(this)
            val modelReady = modelManager.isModelReady(this, modelName)

            if (baseReady && modelReady) {
                showToast("Selected: $modelName")
                updatePlayButton()
            } else {
                startDownload(baseReady, modelName, modelUrl)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onModelCardClicked 失败", e)
            showToast("Error: ${e.message}")
        }
    }

    private fun startDownload(baseReady: Boolean, modelName: String, modelUrl: String) {
        if (mDownloadSection?.visibility != View.VISIBLE) {
            mDownloadSection?.visibility = View.VISIBLE
        }
        mDownloadProgress?.progress = 0
        mDownloadStatus?.text = "Preparing..."
        mDownloadStatus?.setTextColor(0xCCFFFFFF.toInt())

        if (!baseReady) {
            mDownloadStatus?.text = "Downloading base config..."
            modelManager.downloadBaseConfig(this, object : ModelManager.DownloadCallback {
                override fun onDownloadStart() {
                    mainHandler.post { mDownloadStatus?.text = "Starting base config download..." }
                }
                override fun onDownloadProgress(current: Long, total: Long) {
                    val percent = if (total > 0) (current * 100 / total).toInt() else 0
                    mainHandler.post {
                        mDownloadProgress?.progress = percent
                        mDownloadStatus?.text = "Base config: $percent% (${formatSize(current)}/${formatSize(total)})"
                    }
                }
                override fun onUnzipProgress(current: Long, total: Long) {
                    val percent = if (total > 0) (current * 100 / total).toInt() else 0
                    mainHandler.post {
                        mDownloadProgress?.progress = percent
                        mDownloadStatus?.text = "Extracting base config: $percent%"
                    }
                }
                override fun onDownloadComplete() {
                    Log.i(TAG, "基础资源下载完成")
                    downloadSpecificModel(modelName, modelUrl)
                }
                override fun onDownloadFail(code: Int, message: String) {
                    mainHandler.post {
                        mDownloadStatus?.text = "Base config download failed: $message"
                        mDownloadStatus?.setTextColor(ERROR_COLOR)
                        showToast("Download failed: $message")
                    }
                }
            })
        } else {
            downloadSpecificModel(modelName, modelUrl)
        }
    }

    private fun downloadSpecificModel(modelName: String, modelUrl: String) {
        mDownloadStatus?.text = "Downloading model: $modelName"
        modelManager.downloadModel(this, modelUrl, object : ModelManager.DownloadCallback {
            override fun onDownloadStart() {
                mainHandler.post { mDownloadStatus?.text = "Starting model download..." }
            }
            override fun onDownloadProgress(current: Long, total: Long) {
                val percent = if (total > 0) (current * 100 / total).toInt() else 0
                mainHandler.post {
                    mDownloadProgress?.progress = percent
                    mDownloadStatus?.text = "Model: $percent% (${formatSize(current)}/${formatSize(total)})"
                }
            }
            override fun onUnzipProgress(current: Long, total: Long) {
                val percent = if (total > 0) (current * 100 / total).toInt() else 0
                mainHandler.post {
                    mDownloadProgress?.progress = percent
                    mDownloadStatus?.text = "Extracting model: $percent%"
                }
            }
            override fun onDownloadComplete() {
                Log.i(TAG, "模型下载完成: $modelName")
                mainHandler.post {
                    mDownloadProgress?.progress = 100
                    mDownloadStatus?.text = "Download complete"
                    mDownloadStatus?.setTextColor(SUCCESS_COLOR)
                    showToast("Model downloaded: $modelName")
                    refreshModelStatus()
                }
            }
            override fun onDownloadFail(code: Int, message: String) {
                mainHandler.post {
                    mDownloadStatus?.text = "Model download failed: $message"
                    mDownloadStatus?.setTextColor(ERROR_COLOR)
                    showToast("Download failed: $message")
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
            mPlayButton?.text = "Select a model to start"
            mPlayButton?.isEnabled = false
            mPlayButton?.alpha = 0.4f
            return
        }
        val baseReady = modelManager.isBaseConfigReady(this)
        val modelReady = modelManager.isModelReady(this, mSelectedModel!!)
        if (baseReady && modelReady) {
            mPlayButton?.text = "Start Chat"
            mPlayButton?.isEnabled = true
            mPlayButton?.alpha = 1.0f
        } else {
            mPlayButton?.text = "Download model first"
            mPlayButton?.isEnabled = false
            mPlayButton?.alpha = 0.4f
        }
    }

    private fun onPlayClicked() {
        try {
            val modelName = mSelectedModel
            if (modelName == null) {
                showToast("Please select a model")
                return
            }
            val baseReady = modelManager.isBaseConfigReady(this)
            val modelReady = modelManager.isModelReady(this, modelName)
            if (!baseReady || !modelReady) {
                showToast("Model not downloaded yet")
                refreshModelStatus()
                return
            }
            try {
                val intent = Intent(this, CallActivity::class.java)
                intent.putExtra("modelName", modelName)
                startActivity(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "启动CallActivity失败", e)
                showToast("Failed to start: ${e.message}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onPlayClicked失败", e)
        }
    }

    private fun createEmergencyUI(errorMessage: String) {
        try {
            val rootLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF06060F.toInt())
                setPadding(48, 80, 48, 48)
            }
            val titleView = TextView(this).apply {
                text = "DUIX Digital Human"
                textSize = 28f
                setTextColor(0xFFFFFFFF.toInt())
            }
            rootLayout.addView(titleView)
            val errorView = TextView(this).apply {
                text = "UI Error:\n$errorMessage\n\nPlease restart the app"
                textSize = 14f
                setTextColor(0xFF9CA3AF.toInt())
                setPadding(0, 32, 0, 0)
            }
            rootLayout.addView(errorView)
            setContentView(rootLayout)
        } catch (e: Throwable) {
            Log.e(TAG, "紧急UI也创建失败", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        Log.i(TAG, "===== MainActivity.onDestroy =====")
    }
}
