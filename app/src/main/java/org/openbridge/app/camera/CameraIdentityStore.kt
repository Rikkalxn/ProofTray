package org.openbridge.app.camera

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import java.util.UUID

/** Host identities that cameras remember across connections; losing them forces the user to pair again. */
internal class CameraIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences("camera-identity", Context.MODE_PRIVATE)

    @Synchronized
    fun ptpGuid(): UUID = preferences.getString("ptpGuid", null)?.let(UUID::fromString)
        ?: UUID.randomUUID().also { preferences.edit().putString("ptpGuid", it.toString()).apply() }

    /** The Nikon BLE client identity, or null before the first pairing. */
    fun bleIdentity(): ByteArray? = preferences.getString("bleIdentity", null)?.let { encoded ->
        Base64.decode(encoded, Base64.DEFAULT).also { check(it.size == 8) { "蓝牙身份记录无效" } }
    }

    @Synchronized
    fun bleIdentityOrCreate(): ByteArray = bleIdentity() ?: ByteArray(8).also {
        SecureRandom().nextBytes(it)
        it[0] = 1
        preferences.edit().putString("bleIdentity", Base64.encodeToString(it, Base64.NO_WRAP)).apply()
    }
}
