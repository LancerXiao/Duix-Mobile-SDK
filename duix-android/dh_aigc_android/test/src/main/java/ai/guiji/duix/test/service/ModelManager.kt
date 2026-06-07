package ai.guiji.duix.test.service

import android.content.Context
import android.util.Log
import ai.guiji.duix.sdk.client.util.ZipUtil
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 模型下载管理器
 * 使用多线程+断点续传下载模型文件，保存到 ExternalFilesDir
 * 升级APP后模型不丢失
 */
class ModelManager {

    companion object {
        private const val TAG = "ModelManager"
    }

    private val executor = Executors.newSingleThreadExecutor()

    interface DownloadCallback {
        fun onDownloadStart()
        fun onDownloadProgress(current: Long, total: Long)
        fun onUnzipProgress(current: Long, total: Long)
        fun onDownloadComplete()
        fun onDownloadFail(code: Int, message: String)
    }

    private fun isBaseConfigReadyInternal(context: Context): Boolean {
        return try {
            ai.guiji.duix.sdk.client.VirtualModelUtil.checkBaseConfig(context)
        } catch (e: Exception) {
            Log.e(TAG, "检查基础配置失败", e)
            false
        }
    }

    private fun isModelReadyInternal(context: Context, modelName: String): Boolean {
        return try {
            ai.guiji.duix.sdk.client.VirtualModelUtil.checkModel(context, modelName)
        } catch (e: Exception) {
            Log.e(TAG, "检查模型失败: $modelName", e)
            false
        }
    }

    /**
     * 检查基础配置文件是否已下载
     */
    fun isBaseConfigReady(context: Context): Boolean = isBaseConfigReadyInternal(context)

    /**
     * 检查指定模型是否已下载
     */
    fun isModelReady(context: Context, modelName: String): Boolean =
        isModelReadyInternal(context, modelName)

    /**
     * 异步下载基础配置
     */
    fun downloadBaseConfig(context: Context, callback: DownloadCallback) {
        if (isBaseConfigReady(context)) {
            Log.i(TAG, "基础配置已存在，跳过下载")
            callback.onDownloadComplete()
            return
        }
        executor.submit {
            try {
                callback.onDownloadStart()
                downloadAndExtract(
                    context,
                    AiConfig.MODEL_BASE_CONFIG_URL,
                    "gj_dh_res",
                    callback
                )
            } catch (e: Exception) {
                Log.e(TAG, "下载基础配置异常", e)
                callback.onDownloadFail(-9999, e.message ?: "未知错误")
            }
        }
    }

    /**
     * 异步下载指定模型
     */
    fun downloadModel(context: Context, modelUrl: String, callback: DownloadCallback) {
        // 提取dirName
        val dirName = modelUrl.substringAfterLast("/").removeSuffix(".zip")
        if (dirName.isEmpty()) {
            callback.onDownloadFail(-1003, "无效的模型URL: $modelUrl")
            return
        }
        if (isModelReady(context, dirName)) {
            Log.i(TAG, "模型已存在: $dirName")
            callback.onDownloadComplete()
            return
        }
        executor.submit {
            try {
                callback.onDownloadStart()
                downloadAndExtract(context, modelUrl, dirName, callback)
            } catch (e: Exception) {
                Log.e(TAG, "下载模型异常", e)
                callback.onDownloadFail(-9999, e.message ?: "未知错误")
            }
        }
    }

    /**
     * 下载zip并解压到正确位置
     * zip文件保存到 ExternalFilesDir/duix/downloads/ (升级不丢失)
     * 解压到 ExternalFilesDir/duix/model/{dirName}/ (DUIX SDK期望的位置)
     */
    private fun downloadAndExtract(
        context: Context,
        url: String,
        dirName: String,
        callback: DownloadCallback
    ) {
        val downloader = MultiThreadDownloader(context)
        val zipFile = downloader.getZipFile(url)
        val duixDir = context.getExternalFilesDir("duix") ?: context.filesDir
        val modelDir = File(duixDir, "model/$dirName")
        val tmpTagDir = File(duixDir, "model/tmp")

        Log.i(TAG, "开始下载: $url -> ${zipFile.absolutePath}")

        // 1. 下载 zip 文件（多线程+断点续传）
        val success = downloader.download(url, zipFile) { current, total ->
            callback.onDownloadProgress(current, total)
        }
        if (!success) {
            // zip文件可能损坏，删除重试
            if (zipFile.exists()) zipFile.delete()
            callback.onDownloadFail(-1000, "文件下载失败")
            return
        }

        // 2. 准备解压目录
        val targetParentDir = File(duixDir, "model")
        if (!targetParentDir.exists()) targetParentDir.mkdirs()

        // 3. 如果旧的模型目录存在，清理内容
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }

        // 4. 解压
        Log.i(TAG, "开始解压: ${zipFile.absolutePath} -> ${targetParentDir.absolutePath}")
        val unzipResult = ZipUtil.unzip(
            zipFile.absolutePath,
            targetParentDir.absolutePath
        ) { current, total ->
            callback.onUnzipProgress(current, total)
        }
        if (!unzipResult) {
            callback.onDownloadFail(-1001, "文件解压失败")
            return
        }

        // 5. 验证解压结果
        if (!modelDir.exists()) {
            callback.onDownloadFail(-1002, "解压后未找到模型目录: ${modelDir.absolutePath}")
            return
        }

        // 6. 创建 tmp tag 标记（DUIX SDK 依赖此标记判断是否已下载）
        if (!tmpTagDir.exists()) tmpTagDir.mkdirs()
        val tmpTag = File(tmpTagDir, dirName)
        if (!tmpTag.exists()) {
            try {
                tmpTag.mkdirs()
            } catch (e: Exception) {
                Log.w(TAG, "创建tmp tag失败: ${e.message}")
            }
        }

        Log.i(TAG, "下载并解压完成: $dirName")
        callback.onDownloadComplete()
    }

    /**
     * 获取模型本地目录路径
     */
    fun getModelLocalPath(context: Context, modelName: String): String? {
        return try {
            val duixDir = context.getExternalFilesDir("duix") ?: return null
            File(duixDir, "model/$modelName").absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "获取模型路径失败", e)
            null
        }
    }

    /**
     * 获取下载的zip文件存储路径
     */
    fun getZipFilePath(context: Context, modelName: String): String? {
        return try {
            val duixDir = context.getExternalFilesDir("duix") ?: return null
            File(duixDir, "downloads/$modelName.zip").absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "获取zip路径失败", e)
            null
        }
    }
}
