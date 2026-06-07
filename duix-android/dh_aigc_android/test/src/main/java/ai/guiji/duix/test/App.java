package ai.guiji.duix.test;

import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

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

        // 全局异常处理：防止未捕获异常导致闪退
        setupCrashHandler();
    }

    private void setupCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            private final Handler mainHandler = new Handler(Looper.getMainLooper());

            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                Log.e(TAG, "未捕获异常 (线程: " + thread.getName() + ")", throwable);

                // 在主线程显示错误信息
                mainHandler.post(() -> {
                    try {
                        String errorMsg = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                        // 截取前100字符避免toast过长
                        if (errorMsg.length() > 100) {
                            errorMsg = errorMsg.substring(0, 100) + "...";
                        }
                        Toast.makeText(mApp, "发生错误: " + errorMsg, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Log.e(TAG, "显示错误Toast失败", e);
                    }
                });

                // 延迟2秒后重启应用（而不是闪退）
                mainHandler.postDelayed(() -> {
                    try {
                        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(intent);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "重启应用失败", e);
                    }
                    // 如果重启也失败，则让默认处理器处理（进程终止）
                    System.exit(1);
                }, 2000);
            }
        });
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
