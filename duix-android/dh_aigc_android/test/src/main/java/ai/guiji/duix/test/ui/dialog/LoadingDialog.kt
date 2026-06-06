package ai.guiji.duix.test.ui.dialog

import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.DialogLoadingBinding
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.Window
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator


class LoadingDialog(private var mContext: Context, private val content: String = "") :
    Dialog(mContext, R.style.dialog_center) {

    private lateinit var binding: DialogLoadingBinding
    private var retryListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogLoadingBinding.inflate(layoutInflater)
        super.setContentView(binding.root)

        if (!TextUtils.isEmpty(content)) {
            binding.tvStage.text = content
        }

        binding.progressBar.progress = 0
        binding.tvProgressDetail.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE

        binding.btnRetry.setOnClickListener {
            binding.btnRetry.visibility = View.GONE
            binding.ivProgress.visibility = View.VISIBLE
            binding.progressBar.visibility = View.VISIBLE
            val animation: Animation = AnimationUtils.loadAnimation(mContext, R.anim.rotate)
            animation.interpolator = LinearInterpolator()
            binding.ivProgress.startAnimation(animation)
            retryListener?.invoke()
        }

        setCancelable(false)
        setCanceledOnTouchOutside(false)
    }

    fun setContent(content: String) {
        binding.tvContent.text = content
    }

    fun setStage(stage: String) {
        binding.tvStage.text = stage
    }

    fun setProgress(progress: Int) {
        binding.progressBar.progress = progress
    }

    fun setProgressDetail(detail: String) {
        binding.tvProgressDetail.text = detail
        binding.tvProgressDetail.visibility = View.VISIBLE
    }

    fun showError(message: String, onRetry: (() -> Unit)? = null) {
        binding.ivProgress.clearAnimation()
        binding.ivProgress.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.tvProgressDetail.visibility = View.GONE
        binding.tvStage.text = mContext.getString(R.string.download_failed)
        binding.tvContent.text = message
        retryListener = onRetry
        if (onRetry != null) {
            binding.btnRetry.visibility = View.VISIBLE
        }
    }

    override fun show() {
        super.show()
        binding.ivProgress.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.GONE
        val animation: Animation = AnimationUtils.loadAnimation(mContext, R.anim.rotate)
        animation.interpolator = LinearInterpolator()
        binding.ivProgress.startAnimation(animation)
    }

    override fun dismiss() {
        super.dismiss()
        binding.ivProgress.clearAnimation()
    }
}
