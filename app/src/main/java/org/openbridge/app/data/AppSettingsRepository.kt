package org.openbridge.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openbridge.app.domain.AutoDownloadKind
import org.openbridge.app.domain.DownloadVariant

enum class PowerProfile(val pollSeconds: Int, val locationSeconds: Int) { SAVER(60, 120), BALANCED(30, 60), RESPONSIVE(10, 30) }
enum class BluetoothRole { AUTOMATIC, CONNECT_TO_CAMERA, WAIT_FOR_CAMERA }

data class AppSettings(
    val manualVariant: DownloadVariant = DownloadVariant.ORIGINAL,
    val autoDownload: Boolean = false, val autoKind: AutoDownloadKind = AutoDownloadKind.CAMERA_JPEG,
    val powerProfile: PowerProfile = PowerProfile.BALANCED, val locationSync: Boolean = false,
    val controlAddress: String = "", val transferAddress: String = "", val bluetoothRole: BluetoothRole = BluetoothRole.AUTOMATIC,
    val controlName: String = ""
)

class AppSettingsRepository private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val value = change(mutableState.value)
        preferences.edit().putString("manualVariant", value.manualVariant.name).putBoolean("autoDownload", value.autoDownload)
            .putString("autoKind", value.autoKind.name).putString("powerProfile", value.powerProfile.name)
            .putBoolean("locationSync", value.locationSync).putString("controlAddress", value.controlAddress)
            .putString("controlName", value.controlName)
            .putString("transferAddress", value.transferAddress).putString("bluetoothRole", value.bluetoothRole.name).apply()
        mutableState.value = value
    }

    private fun read() = AppSettings(
        manualVariant = enumValue("manualVariant", DownloadVariant.ORIGINAL),
        autoDownload = preferences.getBoolean("autoDownload", false),
        autoKind = enumValue("autoKind", AutoDownloadKind.CAMERA_JPEG), powerProfile = enumValue("powerProfile", PowerProfile.BALANCED),
        locationSync = preferences.getBoolean("locationSync", false), controlAddress = preferences.getString("controlAddress", "").orEmpty(),
        transferAddress = preferences.getString("transferAddress", "").orEmpty(),
        bluetoothRole = enumValue("bluetoothRole", BluetoothRole.AUTOMATIC), controlName = preferences.getString("controlName", "").orEmpty()
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
