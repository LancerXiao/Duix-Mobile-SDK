package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.BuildConfig
import ai.guiji.duix.sdk.client.VirtualModelUtil
import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.ActivityMainBinding
import ai.guiji.duix.test.ui.dialog.LoadingDialog
import ai.guiji.duix.test.ui.dialog.ModelSelectorDialog
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import java.io.File


class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mLoadingDialog: LoadingDialog?=null
    private var mLastProgress = 0

    val models = arrayListOf(
        "http://114.215.183.45/downloads/duix/models/bendi3_20240518.zip",
        "http://114.215.183.45/downloads/duix/models/airuike_20240409.zip",
    )

    private var mBaseConfigUrl = ""
    private var mModelUrl = ""

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvSdkVersion.text = "SDK Version: ${BuildConfig.VERSION_NAME}"


        binding.btnMoreModel.setOnClickListener {
            val modelSelectorDialog = ModelSelectorDialog(mContext, models, object : ModelSelectorDialog.Listener{
                override fun onSelect(url: String) {
                    binding.etUrl.setText(url)
                }
            })
            modelSelectorDialog.show()
        }
        binding.btnPlay.setOnClickListener {
            play()
        }
    }

    private fun play(){
        mBaseConfigUrl = binding.etBaseConfig.text.toString()
        mModelUrl = binding.etUrl.text.toString()
        if (TextUtils.isEmpty(mBaseConfigUrl)){
            Toast.makeText(mContext, R.string.base_config_cannot_be_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (TextUtils.isEmpty(mModelUrl)){
            Toast.makeText(mContext, R.string.model_url_cannot_be_empty, Toast.LENGTH_SHORT).show()
            return
        }
        checkBaseConfig()
    }

    private fun checkBaseConfig(){
        if (VirtualModelUtil.checkBaseConfig(mContext)){
            checkModel()
        } else {
            baseConfigDownload()
        }
    }

    private fun checkModel(){
        if (VirtualModelUtil.checkModel(mContext, mModelUrl)){
            jumpPlayPage()
        } else {
            modelDownload()
        }
    }

    private fun jumpPlayPage(){
        val intent = Intent(mContext, CallActivity::class.java)
        intent.putExtra("modelUrl", mModelUrl)
        val debug = binding.switchDebug.isChecked
        intent.putExtra("debug", debug)
        startActivity(intent)
    }

    private fun baseConfigDownload(){
        mLoadingDialog?.dismiss()
        mLoadingDialog = LoadingDialog(mContext, "Start downloading")
        mLoadingDialog?.show()
        VirtualModelUtil.baseConfigDownload(mContext, mBaseConfigUrl, object :
            VirtualModelUtil.ModelDownloadCallback {
            override fun onDownloadProgress(url: String?, current: Long, total: Long) {
                val progress = (current * 100 / total).toInt()
                if (progress != mLastProgress){
                    mLastProgress = progress
                    runOnUiThread {
                        if (mLoadingDialog?.isShowing == true){
                            mLoadingDialog?.setContent("Config download(${progress}%)")
                        }
                    }
                }
            }

            override fun onUnzipProgress(url: String?, current: Long, total: Long) {
                val progress = (current * 100 / total).toInt()
                if (progress != mLastProgress){
                    mLastProgress = progress
                    runOnUiThread {
                        if (mLoadingDialog?.isShowing == true){
                            mLoadingDialog?.setContent("Config unzip(${progress}%)")
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
                    mLoadingDialog?.dismiss()
                    Toast.makeText(mContext, "BaseConfig download error: $msg", Toast.LENGTH_SHORT).show()
                }
            }

        })
    }

    private fun modelDownload(){
        mLoadingDialog?.dismiss()
        mLoadingDialog = LoadingDialog(mContext, "Start downloading")
        mLoadingDialog?.show()
        VirtualModelUtil.modelDownload(mContext, mModelUrl, object : VirtualModelUtil.ModelDownloadCallback{
            override fun onDownloadProgress(
                url: String?,
                current: Long,
                total: Long,
            ) {
                val progress = (current * 100 / total).toInt()
                if (progress != mLastProgress){
                    mLastProgress = progress
                    runOnUiThread {
                        if (mLoadingDialog?.isShowing == true){
                            mLoadingDialog?.setContent("Model download(${progress}%)")
                        }
                    }
                }
            }

            override fun onUnzipProgress(
                url: String?,
                current: Long,
                total: Long,
            ) {
                val progress = (current * 100 / total).toInt()
                if (progress != mLastProgress){
                    mLastProgress = progress
                    runOnUiThread {
                        if (mLoadingDialog?.isShowing == true){
                            mLoadingDialog?.setContent("Model unzip(${progress}%)")
                        }
                    }
                }
            }

            override fun onDownloadComplete(url: String?, dir: File?) {
                runOnUiThread {
                    mLoadingDialog?.dismiss()
                    jumpPlayPage()
                }
            }

            override fun onDownloadFail(
                url: String?,
                code: Int,
                msg: String?,
            ) {
                runOnUiThread {
                    mLoadingDialog?.dismiss()
                    Toast.makeText(mContext, "Model download error: $msg", Toast.LENGTH_SHORT).show()
                }
            }

        })
    }

}
