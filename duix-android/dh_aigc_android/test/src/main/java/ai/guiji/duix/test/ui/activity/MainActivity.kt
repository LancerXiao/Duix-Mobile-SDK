package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.BuildConfig
import ai.guiji.duix.sdk.client.VirtualModelUtil
import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.ActivityMainBinding
import ai.guiji.duix.test.ui.dialog.LoadingDialog
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File


class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mLoadingDialog: LoadingDialog? = null
    private var mLastProgress = 0

    companion object {
        private const val TAG = "MainActivity"
        private const val BASE_CONFIG_URL = "http://114.215.183.45/downloads/duix/models/gj_dh_res.zip"

        data class ModelInfo(
            val url: String,
            val name: String,
            val initial: String
        )

        private val MODELS = listOf(
            ModelInfo(
                "http://114.215.183.45/downloads/duix/models/bendi3_20240518.zip",
                "小本",
                "小"
            ),
            ModelInfo(
                "http://114.215.183.45/downloads/duix/models/airuike_20240409.zip",
                "艾瑞克",
                "艾"
            ),
        )
    }

    private var mSelectedModelIndex = -1
    private var mModelUrl = ""
    private var mDebugMode = false

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvSdkVersion.text = "SDK v${BuildConfig.VERSION_NAME}"

        setupModelCards()
        setupDownloadButtons()
        setupSettings()
        setupPlayButton()
        refreshModelStatus()
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        refreshModelStatus()
    }

    private fun setupModelCards() {
        binding.cardModel1.setOnClickListener {
            selectModel(0)
        }
        binding.cardModel2.setOnClickListener {
            selectModel(1)
        }
    }

    private fun setupDownloadButtons() {
        binding.btnModel1Download.setOnClickListener {
            mSelectedModelIndex = 0
            mModelUrl = MODELS[0].url
            selectModel(0)
            downloadModelDirectly(0)
        }
        binding.btnModel2Download.setOnClickListener {
            mSelectedModelIndex = 1
            mModelUrl = MODELS[1].url
            selectModel(1)
            downloadModelDirectly(1)
        }
    }

    private fun selectModel(index: Int) {
        mSelectedModelIndex = index
        mModelUrl = MODELS[index].url

        // Update card backgrounds
        binding.cardModel1.background = if (index == 0)
            resources.getDrawable(R.drawable.bg_card_selected_16, null)
        else
            resources.getDrawable(R.drawable.bg_card_border_16, null)

        binding.cardModel2.background = if (index == 1)
            resources.getDrawable(R.drawable.bg_card_selected_16, null)
        else
            resources.getDrawable(R.drawable.bg_card_border_16, null)

        // Update checkmarks
        binding.ivModel1Check.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.ivModel2Check.visibility = if (index == 1) View.VISIBLE else View.GONE

        // Update hint text
        binding.tvSelectHint.visibility = View.GONE
    }

    private fun setupSettings() {
        binding.ivSettings.setOnClickListener {
            val debugLayout = binding.layoutDebug
            if (debugLayout.visibility == View.VISIBLE) {
                debugLayout.visibility = View.GONE
            } else {
                debugLayout.visibility = View.VISIBLE
            }
        }
    }

    private fun setupPlayButton() {
        binding.btnPlay.setOnClickListener {
            play()
        }
    }

    private fun refreshModelStatus() {
        val duixDir = mContext.getExternalFilesDir("duix")?.absolutePath ?: ""

        // 检查并修复基础配置标记文件
        val baseDir = java.io.File(duixDir, "model/gj_dh_res")
        val baseTag = java.io.File(duixDir, "model/tmp/gj_dh_res")
        Log.i(TAG, "基础配置检测: 目录=${baseDir.exists()}, 标记=${baseTag.exists()}")
        if (baseDir.exists() && !baseTag.exists()) {
            Log.i(TAG, "基础配置目录存在但标记文件缺失，自动创建")
            try { baseTag.mkdirs() } catch (e: Exception) { Log.e(TAG, "创建失败: ${e.message}") }
        }

        // 检查并修复每个模型的标记文件
        for ((index, model) in MODELS.withIndex()) {
            val dirName = model.url.substring(model.url.lastIndexOf("/") + 1).replace(".zip", "")
            val modelDir = java.io.File(duixDir, "model/$dirName")
            val modelTag = java.io.File(duixDir, "model/tmp/$dirName")

            Log.i(TAG, "模型[${model.name}] 检测: 目录=${modelDir.exists()}, 标记=${modelTag.exists()}")

            // 如果模型目录存在但标记文件不存在，自动创建标记文件
            if (modelDir.exists() && !modelTag.exists()) {
                Log.i(TAG, "模型[${model.name}] 目录存在但标记缺失，自动创建")
                try { modelTag.mkdirs() } catch (e: Exception) { Log.e(TAG, "创建失败: ${e.message}") }
            }

            val isDownloaded = VirtualModelUtil.checkModel(mContext, model.url)
            Log.i(TAG, "模型[${model.name}] 状态: ${if (isDownloaded) "已下载" else "未下载"}")
            updateModelStatus(index, isDownloaded)
        }
    }

    private fun updateModelStatus(index: Int, isDownloaded: Boolean) {
        val statusText = if (isDownloaded) getString(R.string.model_downloaded) else getString(R.string.model_not_downloaded)

        when (index) {
            0 -> {
                binding.tvModel1Status.text = statusText
                if (isDownloaded) {
                    binding.btnModel1Download.text = "已就绪"
                    binding.btnModel1Download.setBackgroundResource(R.drawable.bg_btn_download_ready)
                    binding.btnModel1Download.setTextColor(resources.getColor(R.color.text_secondary, null))
                    binding.btnModel1Download.isClickable = false
                } else {
                    binding.btnModel1Download.text = "下载"
                    binding.btnModel1Download.setBackgroundResource(R.drawable.bg_btn_download)
                    binding.btnModel1Download.setTextColor(resources.getColor(R.color.text_on_primary, null))
                    binding.btnModel1Download.isClickable = true
                }
            }
            1 -> {
                binding.tvModel2Status.text = statusText
                if (isDownloaded) {
                    binding.btnModel2Download.text = "已就绪"
                    binding.btnModel2Download.setBackgroundResource(R.drawable.bg_btn_download_ready)
                    binding.btnModel2Download.setTextColor(resources.getColor(R.color.text_secondary, null))
                    binding.btnModel2Download.isClickable = false
                } else {
                    binding.btnModel2Download.text = "下载"
                    binding.btnModel2Download.setBackgroundResource(R.drawable.bg_btn_download)
                    binding.btnModel2Download.setTextColor(resources.getColor(R.color.text_on_primary, null))
                    binding.btnModel2Download.isClickable = true
                }
            }
        }
    }

    private fun play() {
        if (mSelectedModelIndex < 0) {
            Toast.makeText(mContext, "请先选择一个数字人模型", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedModel = MODELS[mSelectedModelIndex]
        Log.i(TAG, "点击播放: 模型=${selectedModel.name}, URL=${selectedModel.url}")

        // 先刷新模型状态（自动修复标记文件）
        refreshModelStatus()

        mDebugMode = binding.switchDebug.isChecked
        checkBaseConfig()
    }

    private fun checkBaseConfig() {
        showLoadingDialog("检查基础配置...")
        Log.i(TAG, "开始检查基础配置(gj_dh_res)")

        if (VirtualModelUtil.checkBaseConfig(mContext)) {
            Log.i(TAG, "基础配置已就绪")
            mLoadingDialog?.dismiss()
            checkModel()
        } else {
            Log.i(TAG, "基础配置缺失，开始下载")
            Toast.makeText(mContext, "基础配置缺失，正在下载...", Toast.LENGTH_SHORT).show()
            baseConfigDownload()
        }
    }

    private fun checkModel() {
        Log.i(TAG, "检查模型: $mModelUrl")
        if (VirtualModelUtil.checkModel(mContext, mModelUrl)) {
            Log.i(TAG, "模型已就绪，跳转到播放页面")
            jumpPlayPage()
        } else {
            val dirName = mModelUrl.substring(mModelUrl.lastIndexOf("/") + 1).replace(".zip", "")
            Log.i(TAG, "模型未就绪，开始下载: $dirName")
            Toast.makeText(mContext, "模型(${dirName})需要下载，正在开始...", Toast.LENGTH_SHORT).show()
            modelDownload()
        }
    }

    private fun jumpPlayPage() {
        val intent = Intent(mContext, CallActivity::class.java)
        intent.putExtra("modelUrl", mModelUrl)
        intent.putExtra("debug", mDebugMode)
        startActivity(intent)
    }

    private fun showLoadingDialog(stage: String) {
        mLoadingDialog?.dismiss()
        mLoadingDialog = LoadingDialog(mContext, stage)
        mLoadingDialog?.show()
        mLastProgress = 0
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            .coerceIn(0, units.size - 1)
        return String.format("%.0f", bytes / Math.pow(1024.0, digitGroups.toDouble())) + units[digitGroups]
    }

    private fun baseConfigDownload() {
        mLoadingDialog?.setStage(getString(R.string.downloading_base_config))
        mLoadingDialog?.setContent("")
        mLoadingDialog?.setProgress(0)

        VirtualModelUtil.baseConfigDownload(mContext, BASE_CONFIG_URL, object :
            VirtualModelUtil.ModelDownloadCallback {
            override fun onDownloadProgress(url: String?, current: Long, total: Long) {
                val progress = (current * 100 / total).toInt()
                if (progress != mLastProgress) {
                    mLastProgress = progress
                    runOnUiThread {
                        if (mLoadingDialog?.isShowing == true) {
                            mLoadingDialog?.setProgress(progress)
                            mLoadingDialog?.setContent("${progress}%")
                            mLoadingDialog?.setProgressDetail(
                                getString(R.string.download_progress_format, formatFileSize(current), formatFileSize(total))
                            )
                        }
                    }
                }
            }

            override fun onUnzipProgress(url: String?, current: Long, total: Long) {
                val progress = (current * 100 / total).toInt()
                if (progress != mLastProgress) {
                    mLastProgress = progress
                    runOnUiThread {
                        if (mLoadingDialog?.isShowing == true) {
                            mLoadingDialog?.setStage(getString(R.string.unzipping))
                            mLoadingDialog?.setProgress(progress)
                            mLoadingDialog?.setContent("${progress}%")
                            mLoadingDialog?.setProgressDetail(
                                getString(R.string.unzip_progress_format, progress)
                            )
                        }
                    }
                }
            }

            override fun onDownloadComplete(url: String?, dir: File?) {
                runOnUiThread {
                    mLoadingDialog?.dismiss()
                    checkModel()
                }
            }

            override fun onDownloadFail(url: String?, code: Int, msg: String?) {
                runOnUiThread {
                    val errorMsg = "基础配置下载失败(错误码:$code): ${msg ?: "未知错误"}"
                    Log.e(TAG, errorMsg)
                    mLoadingDialog?.showError(errorMsg) {
                        baseConfigDownload()
                    }
                }
            }
        })
    }

    private fun modelDownload() {
        mLoadingDialog?.setStage(getString(R.string.downloading_model))
        mLoadingDialog?.setContent("")
        mLoadingDialog?.setProgress(0)
        mLastProgress = 0

        VirtualModelUtil.modelDownload(mContext, mModelUrl, object : VirtualModelUtil.ModelDownloadCallback {
            override fun onDownloadProgress(url: String?, current: Long, total: Long) {
                val progress = (current * 100 / total).toInt()
                if (progress != mLastProgress) {
                    mLastProgress = progress
                    runOnUiThread {
                        if (mLoadingDialog?.isShowing == true) {
                            mLoadingDialog?.setProgress(progress)
                            mLoadingDialog?.setContent("${progress}%")
                            mLoadingDialog?.setProgressDetail(
                                getString(R.string.download_progress_format, formatFileSize(current), formatFileSize(total))
                            )
                        }
                    }
                }
            }

            override fun onUnzipProgress(url: String?, current: Long, total: Long) {
                val progress = (current * 100 / total).toInt()
                if (progress != mLastProgress) {
                    mLastProgress = progress
                    runOnUiThread {
                        if (mLoadingDialog?.isShowing == true) {
                            mLoadingDialog?.setStage(getString(R.string.unzipping))
                            mLoadingDialog?.setProgress(progress)
                            mLoadingDialog?.setContent("${progress}%")
                            mLoadingDialog?.setProgressDetail(
                                getString(R.string.unzip_progress_format, progress)
                            )
                        }
                    }
                }
            }

            override fun onDownloadComplete(url: String?, dir: File?) {
                runOnUiThread {
                    mLoadingDialog?.dismiss()
                    refreshModelStatus()
                    jumpPlayPage()
                }
            }

            override fun onDownloadFail(url: String?, code: Int, msg: String?) {
                runOnUiThread {
                    val errorMsg = "模型下载失败(错误码:$code): ${msg ?: "未知错误"}"
                    Log.e(TAG, errorMsg)
                    mLoadingDialog?.showError(errorMsg) {
                        modelDownload()
                    }
                }
            }
        })
    }

    private fun downloadModelDirectly(index: Int) {
        val model = MODELS[index]
        mSelectedModelIndex = index
        mModelUrl = model.url

        if (VirtualModelUtil.checkModel(mContext, model.url)) {
            Toast.makeText(mContext, "${model.name}已就绪，无需下载", Toast.LENGTH_SHORT).show()
            return
        }

        showLoadingDialog("下载${model.name}模型...")
        Log.i(TAG, "直接下载模型: ${model.name}")

        // 先检查基础配置
        if (VirtualModelUtil.checkBaseConfig(mContext)) {
            modelDownload()
        } else {
            Toast.makeText(mContext, "基础配置缺失，正在下载...", Toast.LENGTH_SHORT).show()
            baseConfigDownload()
        }
    }

    private fun checkForUpdate() {
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("http://114.215.183.45/downloads/duix/version.json")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@Thread

                val json = JSONObject(body)
                val latestVersion = json.optString("version_name", "")
                val updateMsg = json.optString("update_message", "发现新版本，点击更新")

                if (latestVersion.isNotEmpty() && latestVersion != BuildConfig.VERSION_NAME) {
                    runOnUiThread {
                        binding.tvUpdateHint.text = updateMsg
                        binding.tvUpdateHint.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "版本检查失败: ${e.message}")
            }
        }.start()
    }
}
