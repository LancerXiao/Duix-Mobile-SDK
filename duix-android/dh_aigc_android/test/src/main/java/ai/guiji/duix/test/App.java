package ai.guiji.duix.test;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class App extends Application {

    public static App mApp;
    private static OkHttpClient mOkHttpClient;
    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();
        mApp = this;

        // 安装全局异常处理 - 把崩溃信息写入文件，下次启动时可以查看
        final Thread.UncaughtExceptionHandler defaultHandler =
            Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                Log.e(TAG, "未捕获异常", throwable);
                writeCrashLog(throwable);
            } catch (Exception e) {
                Log.e(TAG, "写crash日志失败", e);
            }
            // 调用默认handler，让系统处理
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private void writeCrashLog(Throwable throwable) {
        try {
            File crashDir = new File(getExternalFilesDir(null), "crashes");
            if (!crashDir.exists()) crashDir.mkdirs();

            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            File crashFile = new File(crashDir, "crash_" + sdf.format(new Date()) + ".txt");

            PrintWriter pw = new PrintWriter(new FileWriter(crashFile));
            pw.println("Time: " + new Date().toString());
            pw.println("Thread: " + Thread.currentThread().getName());
            pw.println("Exception: " + throwable.getClass().getName());
            pw.println("Message: " + throwable.getMessage());
            pw.println("Stack trace:");
            throwable.printStackTrace(pw);
            pw.close();

            Log.i(TAG, "Crash log saved: " + crashFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to write crash log", e);
        }
    }

    public static OkHttpClient getOkHttpClient() {
        if (mOkHttpClient == null) {
            mOkHttpClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
        }
        return mOkHttpClient;
    }
}
