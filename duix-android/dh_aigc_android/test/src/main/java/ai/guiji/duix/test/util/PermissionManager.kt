package ai.guiji.duix.test.util

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限引导工具（Phase 1.5 骨架）
 *
 * 解决：当用户拒绝麦克风权限后，二次引导到系统设置开启
 * - 第一次拒绝：弹 rationale 对话框解释为什么需要
 * - 选了"不再询问"：直接弹"去设置"对话框
 *
 * 骨架阶段：仅工具类，**不接通**到 CallActivity.permissionsGet(get=false)
 * 等 Phase 1.1 [DIAG] 反馈出根因后再接通，避免改变现有拒绝时的 Toast 行为
 */
object PermissionManager {

    private const val TAG = "PermissionManager"

    /**
     * 检查是否已授予指定权限
     */
    fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * 是否应该显示 rationale 解释对话框
     * - 第一次拒绝后调用此方法会返回 true
     * - 用户选了"不再询问"后调用此方法会返回 false
     *
     * 注意：必须在 Activity 内调用（依赖 ActivityCompat）
     */
    fun shouldShowRationale(activity: Activity, permission: String): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    /**
     * 判断当前是否需要走"去设置"引导
     * - 权限被拒 + 不显示 rationale = 用户选了"不再询问"
     * - 此时只能引导到系统设置手动开启
     */
    fun needsSettingsGuide(activity: Activity, permission: String): Boolean {
        val denied = !isGranted(activity, permission)
        val noRationale = !shouldShowRationale(activity, permission)
        return denied && noRationale
    }

    /**
     * 判断当前是否需要走 rationale 解释
     * - 权限被拒 + 显示 rationale = 第一次拒绝，可以再解释一次
     */
    fun needsRationale(activity: Activity, permission: String): Boolean {
        val denied = !isGranted(activity, permission)
        val showRationale = shouldShowRationale(activity, permission)
        return denied && showRationale
    }

    /**
     * 记录权限引导诊断（Phase 1.5 骨架）
     * CallActivity.permissionsGet(get=false) 接通阶段会调用
     */
    fun logDiagnose(activity: Activity, permission: String, permissionCode: Int) {
        val granted = isGranted(activity, permission)
        val showRationale = shouldShowRationale(activity, permission)
        val needsSettings = needsSettingsGuide(activity, permission)
        val needsRat = needsRationale(activity, permission)
        Log.i(TAG, "[DIAG] 权限诊断: permission=$permission, code=$permissionCode, granted=$granted, showRationale=$showRationale, needsSettingsGuide=$needsSettings, needsRationale=$needsRat")
    }
}
