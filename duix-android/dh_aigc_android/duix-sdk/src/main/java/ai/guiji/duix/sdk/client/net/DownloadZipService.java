package ai.guiji.duix.sdk.client.net;

import android.content.Context;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import ai.guiji.duix.sdk.client.util.Logger;
import ai.guiji.duix.sdk.client.util.MD5Util;
import ai.guiji.duix.sdk.client.util.ZipUtil;


public class DownloadZipService {

    public interface Callback {
        void onDownloadProgress(long current, long total);

        void onUnzipProgress(long current, long total);

        void onComplete(File dirFile);

        void onError(int code, String msg);
    }

    /**
     * 下载zip文件并解压
     *
     */
    public static void downloadAndUnzip(Context context, String url, File targetDirFile, Callback callback, boolean deleteZip) {
        Executor executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            // Check if model is already fully extracted (tmp tag exists)
            File tmpTag = new File(targetDirFile.getParentFile(), "tmp/" + targetDirFile.getName());
            if (tmpTag.exists() && targetDirFile.exists()) {
                Logger.i("Model already downloaded and extracted, skip download.");
                callback.onComplete(targetDirFile);
                return;
            }

            File cacheDir = context.getExternalCacheDir();
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            File zipFile = new File(cacheDir, MD5Util.string2MD5(url));
            boolean result = true;
            if (!zipFile.exists()) {
                Logger.i("zip not found, try download.");
                result = new FileDownloader(url, zipFile.getAbsolutePath(), callback::onDownloadProgress).download();
                Logger.i("download file done.");
            } else {
                Logger.i( "found cache zip file.");
            }
            if (result) {
                Logger.e( "try unzip file.");
                if (targetDirFile.exists()) {
                    Logger.e("delete old files.");
                    deleteContents(targetDirFile);
                }
                // 拿到目标路径的父级
                File targetParentDir = targetDirFile.getParentFile();
                if (!targetParentDir.exists()) {
                    targetParentDir.mkdirs();
                }
                result = ZipUtil.unzip(zipFile.getAbsolutePath(), targetParentDir.getAbsolutePath(), callback::onUnzipProgress);
                if (result) {
                    Logger.i( "unzip file complete.");
                    // 这里时候targetDirFile应该是存在的
                    if (targetDirFile.exists()) {
                        File tmpDir = new File(targetParentDir, "tmp/" + targetDirFile.getName());
                        if (!tmpDir.mkdirs()){
                            Logger.e("make tmp dir fail");
                        }
                        if (deleteZip && zipFile.exists()){
                            zipFile.delete();
                        }
                        callback.onComplete(targetDirFile);
                    } else {
                        callback.onError(-1002,"unzip dir not found!");
                    }
                } else {
                    callback.onError(-1001, "unzip file error!");
                    zipFile.delete();
                }
            } else {
                callback.onError(-1000, "zip file download error");
            }
        });
    }

    public static boolean deleteContents(File dir) {
        File[] files = dir.listFiles();
        boolean success = true;
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    success &= deleteContents(file);
                }
                if (!file.delete()) {
                    success = false;
                }
            }
        }
        return success;
    }

}
