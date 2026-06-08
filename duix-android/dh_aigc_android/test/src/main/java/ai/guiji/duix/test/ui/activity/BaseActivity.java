package ai.guiji.duix.test.ui.activity;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;


public abstract class BaseActivity extends AppCompatActivity implements Handler.Callback {

    public final String TAG = getClass().getName();
    protected BaseActivity mContext;
    protected Handler mHandler;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        HandlerThread mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper(), this);

        // 简单稳定的窗口设置 - 不依赖任何新API
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                getWindow().setStatusBarColor(0xFF0099CC);
            }
        } catch (Exception e) {
            Log.w(TAG, "设置状态栏失败", e);
        }
    }

    /**
     * 显示错误对话框
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
                    Log.e(TAG, "显示错误对话框失败", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "showErrorDialog失败", e);
        }
    }

    /**
     * 显示回退界面
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
