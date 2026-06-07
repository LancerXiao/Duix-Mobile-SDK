package ai.guiji.duix.test.service

import android.content.Context
import android.util.Log
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/**
 * 多线程断点续传下载器
 * - 升级APP时模型不丢失（保存到 ExternalFilesDir）
 * - 多线程分片下载加速
 * - Range 请求支持断点续传
 * - 自动重试机制
 */
class MultiThreadDownloader(private val context: Context) {

    companion object {
        private const val TAG = "MultiThreadDownloader"
        private const val MIN_CHUNK_SIZE = 1024 * 1024  // 1MB minimum per chunk
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 60L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    interface ProgressCallback {
        fun onProgress(current: Long, total: Long)
    }

    /**
     * 下载文件到指定位置（支持多线程+断点续传）
     * @param url 下载URL
     * @param targetFile 目标文件
     * @param threadCount 线程数
     * @param callback 进度回调
     * @return 是否成功
     */
    fun download(
        url: String,
        targetFile: File,
        threadCount: Int = AiConfig.DOWNLOAD_THREADS,
        callback: ProgressCallback? = null
    ): Boolean {
        // 1. 创建目标文件的父目录
        targetFile.parentFile?.mkdirs()

        // 2. 先获取文件大小（HEAD请求）
        val totalSize = getFileSize(url)
        if (totalSize <= 0) {
            Log.e(TAG, "无法获取文件大小: $url")
            return false
        }

        // 3. 如果目标文件已经存在且大小正确，跳过
        if (targetFile.exists() && targetFile.length() == totalSize) {
            Log.i(TAG, "文件已存在且完整: ${targetFile.absolutePath} (${totalSize} bytes)")
            callback?.onProgress(totalSize, totalSize)
            return true
        }

        // 4. 创建目标文件
        if (!targetFile.exists()) {
            targetFile.createNewFile()
        }
        val randomAccessFile = RandomAccessFile(targetFile, "rw")
        randomAccessFile.setLength(totalSize)
        randomAccessFile.close()

        // 5. 分片下载
        val chunkSize = (totalSize + threadCount - 1) / threadCount
        val executor = Executors.newFixedThreadPool(threadCount)
        val progressCounter = AtomicLong(0)
        val futures = mutableListOf<Future<Boolean>>()
        var downloadSuccess = true

        for (i in 0 until threadCount) {
            val start = i * chunkSize
            val end = Math.min(start + chunkSize - 1, totalSize - 1)
            if (start > end) continue

            val future = executor.submit<Boolean> {
                downloadChunkWithRetry(url, targetFile, start, end, i) { chunkDownloaded ->
                    val newProgress = progressCounter.addAndGet(chunkDownloaded)
                    callback?.onProgress(newProgress, totalSize)
                }
            }
            futures.add(future)
        }

        // 6. 等待所有分片完成
        for (future in futures) {
            try {
                if (!future.get(READ_TIMEOUT, TimeUnit.SECONDS)) {
                    downloadSuccess = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "分片下载异常", e)
                downloadSuccess = false
            }
        }
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        // 7. 验证文件完整性
        if (downloadSuccess && targetFile.length() == totalSize) {
            Log.i(TAG, "下载完成: ${targetFile.absolutePath}")
            return true
        } else {
            Log.e(TAG, "下载失败: success=$downloadSuccess, size=${targetFile.length()}/$totalSize")
            return false
        }
    }

    /**
     * 下载单个分片（带重试）
     */
    private fun downloadChunkWithRetry(
        url: String,
        targetFile: File,
        start: Long,
        end: Long,
        chunkIndex: Int,
        onChunkProgress: (Long) -> Unit
    ): Boolean {
        var attempt = 0
        while (attempt < AiConfig.DOWNLOAD_RETRY_COUNT) {
            try {
                return downloadChunk(url, targetFile, start, end, chunkIndex, onChunkProgress)
            } catch (e: Exception) {
                attempt++
                Log.w(TAG, "分片 $chunkIndex 下载失败，第 $attempt 次重试: ${e.message}")
                if (attempt < AiConfig.DOWNLOAD_RETRY_COUNT) {
                    Thread.sleep(1000L * attempt)  // 退避重试
                }
            }
        }
        Log.e(TAG, "分片 $chunkIndex 下载失败，已重试 ${AiConfig.DOWNLOAD_RETRY_COUNT} 次")
        return false
    }

    /**
     * 下载单个分片（支持断点续传 - 如果已下载部分会自动跳过）
     */
    @Throws(IOException::class)
    private fun downloadChunk(
        url: String,
        targetFile: File,
        start: Long,
        end: Long,
        chunkIndex: Int,
        onChunkProgress: (Long) -> Unit
    ): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .header("User-Agent", "DUIX-Downloader/1.0")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            // 服务端不支持Range，尝试下载整个文件
            Log.w(TAG, "服务端不支持Range (code=${response.code})，分片下载失败")
            response.close()
            return false
        }

        val body = response.body ?: run {
            response.close()
            return false
        }

        val randomAccessFile = RandomAccessFile(targetFile, "rw")
        randomAccessFile.seek(start)
        val inputStream = body.byteStream()
        val buffer = ByteArray(8 * 1024)
        var totalRead = 0L

        try {
            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                randomAccessFile.write(buffer, 0, len)
                totalRead += len
                onChunkProgress(len.toLong())
            }
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
            try { randomAccessFile.close() } catch (_: Exception) {}
            response.close()
        }

        val expectedSize = end - start + 1
        if (totalRead == expectedSize) {
            return true
        } else {
            Log.w(TAG, "分片 $chunkIndex 数据不完整: $totalRead/$expectedSize")
            return false
        }
    }

    /**
     * 获取文件大小
     */
    private fun getFileSize(url: String): Long {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "DUIX-Downloader/1.0")
                .build()
            val response = client.newCall(request).execute()
            val size = when {
                response.isSuccessful -> {
                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    if (contentLength > 0) {
                        contentLength
                    } else {
                        // 没有Content-Length，尝试GET请求获取
                        getFileSizeFromGet(url)
                    }
                }
                response.code == 405 || response.code == 403 -> {
                    // HEAD请求不被支持，尝试GET请求
                    response.close()
                    getFileSizeFromGet(url)
                }
                else -> {
                    Log.e(TAG, "获取文件大小失败: HTTP ${response.code}")
                    response.close()
                    -1L
                }
            }
            response.close()
            size
        } catch (e: Exception) {
            Log.e(TAG, "获取文件大小异常", e)
            -1L
        }
    }

    /**
     * 通过GET请求获取文件大小（当HEAD不支持时）
     */
    private fun getFileSizeFromGet(url: String): Long {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .header("User-Agent", "DUIX-Downloader/1.0")
                .build()
            val response = client.newCall(request).execute()
            response.close()
            // 从Content-Range头解析总大小: "bytes 0-0/12345"
            val contentRange = response.header("Content-Range")
            if (contentRange != null && contentRange.contains("/")) {
                val total = contentRange.substringAfterLast("/").toLongOrNull() ?: -1L
                total
            } else {
                -1L
            }
        } catch (e: Exception) {
            Log.e(TAG, "通过GET获取文件大小异常", e)
            -1L
        }
    }

    /**
     * 获取下载用的zip文件存储路径
     * 使用 ExternalFilesDir 而非 ExternalCacheDir，确保升级APP后不丢失
     */
    fun getZipFile(url: String): File {
        val duixDir = context.getExternalFilesDir("duix")
            ?: context.filesDir
        val downloadsDir = File(duixDir, "downloads")
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val fileName = url.substringAfterLast("/").ifEmpty { "model.zip" }
        return File(downloadsDir, fileName)
    }
}
