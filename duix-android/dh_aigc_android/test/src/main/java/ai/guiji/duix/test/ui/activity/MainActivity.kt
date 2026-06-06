package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.BuildConfig
import ai.guiji.duix.sdk.client.VirtualModelUtil
import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.ActivityMainBinding
import ai.guiji.duix.test.ui.dialog.LoadingDialog
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import java.io.File


class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mLoadingDialog: LoadingDialog? = null
    private var mLastProgress = 0

    companion object {
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
        setupSettings()
        setupPlayButton()
        refreshModelStatus()
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
        for ((index, model) in MODELS.withIndex()) {
            val isDownloaded = VirtualModelUtil.checkModel(mContext, model.url)
            updateModelStatus(index, isDownloaded)
        }
    }

    private fun updateModelStatus(index: Int, isDownloaded: Boolean) {
        val statusText = if (isDownloaded) getString(R.string.model_downloaded) else getString(R.string.model_not_downloaded)
        val statusIcon = if (isDownloaded) R.drawable.bg_status_downloaded else R.drawable.bg_status_not_downloaded

        when (index) {
            0 -> {
                binding.tvModel1Status.text = statusText
                binding.ivModel1Status.setImageResource(statusIcon)
            }
            1 -> {
                binding.tvModel2Status.text = statusText
                binding.ivModel2Status.setImageResource(statusIcon)
            }
        }
    }

    private fun play() {
        if (mSelectedModelIndex < 0) {
            Toast.makeText(mContext, R.string.model_url_cannot_be_empty, Toast.LENGTH_SHORT).show()
            return
        }
        mDebugMode = binding.switchDebug.isChecked
        checkBaseConfig()
    }

    private fun checkBaseConfig() {
        showLoadingDialog(getString(R.string.model_checking))
        if (VirtualModelUtil.checkBaseConfig(mContext)) {
            mLoadingDialog?.dismiss()
            checkModel()
        } else {
            baseConfigDownload()
        }
    }

    private fun checkModel() {
        if (VirtualModelUtil.checkModel(mContext, mModelUrl)) {
            jumpPlayPage()
        } else {
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
                    mLoadingDialog?.showError(
                        getString(R.string.base_config_download_error, msg ?: "未知错误")
                    ) {
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
                    mLoadingDialog?.showError(
                        getString(R.string.model_download_error, msg ?: "未知错误")
                    ) {
                        modelDownload()
                    }
                }
            }
        })
    }
}
