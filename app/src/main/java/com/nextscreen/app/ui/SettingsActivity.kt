package com.nextscreen.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.nextscreen.app.Prefs
import com.nextscreen.app.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<EditTextPreference>(Prefs.KEY_DEVICE_NAME)?.apply {
                setOnBindEditTextListener { it.setSingleLine(); it.selectAll() }
                setOnPreferenceChangeListener { _, v ->
                    val ok = (v as? String)?.isNotBlank() == true
                    if (!ok) Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(requireContext(), "已更新，手机端重新搜索设备即可看到新名称", Toast.LENGTH_SHORT).show()
                    ok
                }
            }

            findPreference<Preference>("overlay")?.setOnPreferenceClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
                    )
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "请在系统设置中开启悬浮窗权限", Toast.LENGTH_LONG).show()
                }
                true
            }

            findPreference<Preference>("battery")?.setOnPreferenceClickListener {
                val ctx = requireContext()
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    Toast.makeText(ctx, "已忽略电池优化", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        @Suppress("BatteryLife")
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        )
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                true
            }

            findPreference<Preference>("about")?.summary =
                "版本 ${appVersion()} · DLNA/UPnP 渲染器 + AirPlay 屏幕镜像\n设备 UUID：${Prefs.uuid}"
        }

        override fun onResume() {
            super.onResume()
            val ctx = requireContext()
            val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
            findPreference<Preference>("overlay")?.summary =
                if (overlayOk) "已开启：后台收到投屏会自动打开播放器"
                else "未开启：点击开启「显示在其他应用上层」，后台收到投屏时可自动弹出播放画面"
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            findPreference<Preference>("battery")?.summary =
                if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) "已忽略：投屏服务可在后台长期运行"
                else "建议开启，防止系统在后台关闭投屏服务"
        }

        private fun appVersion(): String = try {
            val ctx = requireContext()
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }
}
