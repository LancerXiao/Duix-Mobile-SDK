package ai.guiji.duix.test.ui.activity;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import java.util.ArrayList;
import java.util.List;


public abstract class BaseActivity extends AppCompatActivity implements Handler.Callback {

    public final String TAG = getClass().getName();
    protected BaseActivity mContext;
    protected Handler mHandler;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // 第一步：在super.onCreate之前强制设置窗口属性
        // 这是最关键的一步 - 不依赖XML主题，直接在代码中设置
        forceWindowSettings();

        super.onCreate(savedInstanceState);
        mContext = this;
        HandlerThread mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper(), this);
    }

    /**
     * 强制设置窗口属性 - 不依赖XML主题配置
     * 这是解决Android 15白屏的核心方法
     */
    private void forceWindowSettings() {
        try {
            // 1. 强制退出edge-to-edge模式（最关键！）
            // WindowCompat.setDecorFitsSystemWindows 是程序化方式，
            // 比XML的 windowOptOutEdgeToEdgeEnforcement 更可靠
            WindowCompat.setDecorFitsSystemWindows(getWindow(), true);

            // 2. 设置窗口背景色（防止白屏）
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFFF5F6FA));

            // 3. 设置状态栏和导航栏
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                getWindow().setStatusBarColor(0xFF5A52D5);
                getWindow().setNavigationBarColor(0xFFF5F6FA);
            }

            // 4. Android 15+ 额外处理
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                try {
                    // 尝试设置状态栏对比度
                    getWindow().setStatusBarContrastEnforced(false);
                    getWindow().setNavigationBarContrastEnforced(false);
                } catch (Exception e) {
                    Log.w(TAG, "设置对比度失败: " + e.getMessage());
                }
            }

            Log.i(TAG, "窗口属性设置成功: SDK=" + Build.VERSION.SDK_INT);
        } catch (Exception e) {
            Log.e(TAG, "设置窗口属性失败", e);
        }
    }

    /**
     * 显示错误对话框 - 确保用户能看到错误信息
     */
    protected void showErrorDialog(String title, String message) {
        try {
            runOnUiThread(() -> {
                try {
                    new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton("确定", null)
                        .setCancelable(true)
                        .show();
                } catch (Exception e) {
                    // 如果对话框也失败了，用Toast
                    try {
                        Toast.makeText(this, title + ": " + message, Toast.LENGTH_LONG).show();
                    } catch (Exception e2) {
                        Log.e(TAG, "显示错误失败", e2);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "显示错误对话框失败", e);
        }
    }

    /**
     * 显示回退界面 - 当主布局加载失败时使用
     */
    protected void showFallbackUI(String errorMessage) {
        try {
            runOnUiThread(() -> {
                try {
                    FrameLayout fallbackLayout = new FrameLayout(this);
                    fallbackLayout.setBackgroundColor(0xFFF5F6FA);

                    TextView errorView = new TextView(this);
                    errorView.setText("加载失败: " + errorMessage + "\n\n请尝试重新打开应用");
                    errorView.setTextColor(0xFF1A1A2E);
                    errorView.setTextSize(16);
                    errorView.setPadding(48, 48, 48, 48);

                    fallbackLayout.addView(errorView);
                    setContentView(fallbackLayout);
                } catch (Exception e) {
                    Log.e(TAG, "显示回退界面失败", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "showFallbackUI失败", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        if (mHandler != null && mHandler.getLooper() != null) {
            mHandler.getLooper().quit();
        }
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        onMessage(msg);
        return false;
    }

    protected void onMessage(@NonNull Message msg) {
    }

    protected void keepScreenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private String[] mRequestPermissions;
    private int mRequestPermissionCode;
    ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                boolean hasDeny = false;
                for (String permission : mRequestPermissions) {
                    if (null == permission) {
                        continue;
                    }
                    if (ContextCompat.checkSelfPermission(mContext, permission) !=
                            PackageManager.PERMISSION_GRANTED) {
                        hasDeny = true;
                    }
                }
                if (hasDeny) {
                    permissionsGet(false, mRequestPermissionCode);
                } else {
                    permissionsGet(true, mRequestPermissionCode);
                }
            });

    public void requestPermission(String[] permissions, int code) {
        if (null == permissions) {
            permissionsGet(true, code);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            permissionsGet(true, code);
            return;
        }
        mRequestPermissions = permissions;
        mRequestPermissionCode = code;
        List<String> requestPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(mContext, permission) !=
                    PackageManager.PERMISSION_GRANTED) {
                requestPermissions.add(permission);
            }
        }
        if (0 != requestPermissions.size()) {
            String[] permissionArray = new String[requestPermissions.size()];
            for (int i = 0; i < requestPermissions.size(); i++) {
                permissionArray[i] = requestPermissions.get(i);
            }
            permissionLauncher.launch(permissionArray);
        } else {
            permissionsGet(true, mRequestPermissionCode);
        }
    }

    public void permissionsGet(boolean get, int code) {
    }
}
