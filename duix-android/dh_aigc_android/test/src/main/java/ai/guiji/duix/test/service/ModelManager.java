package ai.guiji.duix.test.service;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.guiji.duix.sdk.client.VirtualModelUtil;

/**
 * 模型下载管理器
 * 包装 VirtualModelUtil，提供UI友好的下载回调
 */
public class ModelManager {

    private static final String TAG = "ModelManager";

    public static final String BASE_CONFIG_URL = "http://114.215.183.45/downloads/duix/models/gj_dh_res.zip";
    public static final String MODEL_XIAOBEN_URL = "http://114.215.183.45/downloads/duix/models/bendi3_20240518.zip";
    public static final String MODEL_AIRUIKE_URL = "http://114.215.183.45/downloads/duix/models/airuike_20240409.zip";

    public static final String MODEL_NAME_XIAOBEN = "bendi3_20240518";
    public static final String MODEL_NAME_AIRUIKE = "airuike_20240409";

    public interface DownloadCallback {
        void onDownloadStart();
        void onDownloadProgress(long current, long total);
        void onUnzipProgress(long current, long total);
        void onDownloadComplete();
        void onDownloadFail(int code, String message);
    }

    private static volatile ModelManager instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static ModelManager getInstance() {
        if (instance == null) {
            synchronized (ModelManager.class) {
                if (instance == null) {
                    instance = new ModelManager();
                }
            }
        }
        return instance;
    }

    private ModelManager() {}

    /**
     * 检查基础配置文件是否已下载
     */
    public boolean isBaseConfigReady(Context context) {
        try {
            return VirtualModelUtil.checkBaseConfig(context);
        } catch (Exception e) {
            Log.e(TAG, "检查基础配置失败", e);
            return false;
        }
    }

    /**
     * 检查指定模型是否已下载
     */
    public boolean isModelReady(Context context, String modelName) {
        try {
            return VirtualModelUtil.checkModel(context, modelName);
        } catch (Exception e) {
            Log.e(TAG, "检查模型失败: " + modelName, e);
            return false;
        }
    }

    /**
     * 异步下载基础配置
     */
    public void downloadBaseConfig(Context context, DownloadCallback callback) {
        if (isBaseConfigReady(context)) {
            Log.i(TAG, "基础配置已存在，跳过下载");
            if (callback != null) callback.onDownloadComplete();
            return;
        }
        executor.submit(() -> {
            try {
                VirtualModelUtil.baseConfigDownload(context, new VirtualModelUtil.ModelDownloadCallback() {
                    @Override
                    public void onDownloadProgress(String url, long current, long total) {
                        if (callback != null) callback.onDownloadProgress(current, total);
                    }
                    @Override
                    public void onUnzipProgress(String url, long current, long total) {
                        if (callback != null) callback.onUnzipProgress(current, total);
                    }
                    @Override
                    public void onDownloadComplete(String url, File dir) {
                        Log.i(TAG, "基础配置下载完成: " + dir.getAbsolutePath());
                        if (callback != null) callback.onDownloadComplete();
                    }
                    @Override
                    public void onDownloadFail(String url, int code, String msg) {
                        Log.e(TAG, "基础配置下载失败: " + code + " - " + msg);
                        if (callback != null) callback.onDownloadFail(code, msg);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "下载基础配置异常", e);
                if (callback != null) callback.onDownloadFail(-9999, e.getMessage());
            }
        });
    }

    /**
     * 异步下载指定模型
     */
    public void downloadModel(Context context, String modelUrl, DownloadCallback callback) {
        // 先检查是否已下载
        if (modelUrl.startsWith("https://") || modelUrl.startsWith("http://")) {
            String dirName = modelUrl.substring(modelUrl.lastIndexOf("/") + 1).replace(".zip", "");
            if (isModelReady(context, dirName)) {
                Log.i(TAG, "模型已存在: " + dirName);
                if (callback != null) callback.onDownloadComplete();
                return;
            }
        }

        executor.submit(() -> {
            try {
                VirtualModelUtil.modelDownload(context, modelUrl, new VirtualModelUtil.ModelDownloadCallback() {
                    @Override
                    public void onDownloadProgress(String url, long current, long total) {
                        if (callback != null) callback.onDownloadProgress(current, total);
                    }
                    @Override
                    public void onUnzipProgress(String url, long current, long total) {
                        if (callback != null) callback.onUnzipProgress(current, total);
                    }
                    @Override
                    public void onDownloadComplete(String url, File dir) {
                        Log.i(TAG, "模型下载完成: " + dir.getAbsolutePath());
                        if (callback != null) callback.onDownloadComplete();
                    }
                    @Override
                    public void onDownloadFail(String url, int code, String msg) {
                        Log.e(TAG, "模型下载失败: " + code + " - " + msg);
                        if (callback != null) callback.onDownloadFail(code, msg);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "下载模型异常", e);
                if (callback != null) callback.onDownloadFail(-9999, e.getMessage());
            }
        });
    }

    /**
     * 获取模型本地目录路径
     */
    public String getModelLocalPath(Context context, String modelName) {
        try {
            File duixDir = context.getExternalFilesDir("duix");
            if (duixDir == null) return null;
            return new File(duixDir, "model/" + modelName).getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "获取模型路径失败", e);
            return null;
        }
    }
}
