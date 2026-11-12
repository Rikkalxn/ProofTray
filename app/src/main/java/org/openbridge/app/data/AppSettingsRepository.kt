package org.openbridge.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openbridge.app.domain.AppSettings
import org.openbridge.app.domain.PowerProfile
import org.openbridge.app.domain.BluetoothRole
import org.openbridge.app.domain.AutoDownloadKind
import org.openbridge.app.domain.DownloadVariant
import org.openbridge.app.domain.ExportPreferences

class AppSettingsRepository private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val value = change(mutableState.value)
        preferences.edit().putString("manualVariant", value.manualVariant.name).putBoolean("autoDownload", value.autoDownload)
            .putString("autoKind", value.autoKind.name).putString("powerProfile", value.powerProfile.name)
            .putBoolean("locationSync", value.locationSync).putBoolean("pairingTimeSync", value.pairingTimeSync)
            .putBoolean("requestedTimeSync", value.requestedTimeSync)
            .putString("controlAddress", value.controlAddress)
            .putString("controlName", value.controlName)
            .putBoolean("sonySelected", value.sonySelected).putString("sonyAddress", value.sonyAddress).putString("sonyName", value.sonyName)
            .putString("transferAddress", value.transferAddress).putString("bluetoothRole", value.bluetoothRole.name).also { editor ->
                if (value.exportPreferences != mutableState.value.exportPreferences) value.exportPreferences.toPreferences().forEach { (key, item) ->
                    when (item) {
                        is Int -> editor.putInt(key, item)
                        is String -> editor.putString(key, item)
                    }
                }
            }.apply()
        mutableState.value = value
    }

    private fun read() = AppSettings(
        manualVariant = enumValue("manualVariant", DownloadVariant.ORIGINAL),
        autoDownload = preferences.getBoolean("autoDownload", false),
        autoKind = enumValue("autoKind", AutoDownloadKind.CAMERA_JPEG), powerProfile = enumValue("powerProfile", PowerProfile.BALANCED),
        locationSync = preferences.getBoolean("locationSync", false), pairingTimeSync = preferences.getBoolean("pairingTimeSync", false),
        requestedTimeSync = preferences.getBoolean("requestedTimeSync", true),
        controlAddress = preferences.getString("controlAddress", "").orEmpty(),
        transferAddress = preferences.getString("transferAddress", "").orEmpty(),
        bluetoothRole = enumValue("bluetoothRole", BluetoothRole.AUTOMATIC), controlName = preferences.getString("controlName", "").orEmpty(),
        sonySelected = preferences.getBoolean("sonySelected", false), sonyAddress = preferences.getString("sonyAddress", "").orEmpty(),
        sonyName = preferences.getString("sonyName", "").orEmpty(), exportPreferences = ExportPreferences.fromPreferences(preferences.all)
    )

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(preferences.getString(key, fallback.name)!!) }.getOrDefault(fallback)

    companion object {
        @Volatile private var instance: AppSettingsRepository? = null
        fun get(context: Context): AppSettingsRepository = instance ?: synchronized(this) {
            instance ?: AppSettingsRepository(context.applicationContext).also { instance = it }
        }
    }
}
