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

    // 防止无限重启循环
    private static long lastCrashTime = 0;
    private static int crashCount = 0;
    private static final long CRASH_WINDOW_MS = 10000; // 10秒内的崩溃算连续崩溃
    private static final int MAX_CRASH_COUNT = 3; // 连续崩溃3次后不再重启

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

                // 打印完整的异常堆栈
                Log.e(TAG, "异常类: " + throwable.getClass().getName());
                Log.e(TAG, "异常消息: " + throwable.getMessage());
                for (StackTraceElement element : throwable.getStackTrace()) {
                    Log.e(TAG, "  at " + element.toString());
                }
                if (throwable.getCause() != null) {
                    Log.e(TAG, "原因: " + throwable.getCause().getMessage());
                }

                long now = System.currentTimeMillis();
                if (now - lastCrashTime < CRASH_WINDOW_MS) {
                    crashCount++;
                } else {
                    crashCount = 1;
                }
                lastCrashTime = now;

                // 在主线程显示错误信息
                mainHandler.post(() -> {
                    try {
                        String errorMsg = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                        if (errorMsg.length() > 200) {
                            errorMsg = errorMsg.substring(0, 200) + "...";
                        }
                        String crashInfo = "发生错误(第" + crashCount + "次): " + errorMsg;
                        Toast.makeText(mApp, crashInfo, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Log.e(TAG, "显示错误Toast失败", e);
                    }
                });

                // 如果连续崩溃次数超过阈值，不再重启，让进程自然终止
                if (crashCount >= MAX_CRASH_COUNT) {
                    Log.e(TAG, "连续崩溃" + crashCount + "次，不再自动重启");
                    mainHandler.postDelayed(() -> {
                        // 直接终止进程，不再重启
                        System.exit(1);
                    }, 3000);
                    return;
                }

                // 延迟3秒后重启应用
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
                    System.exit(1);
                }, 3000);
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
