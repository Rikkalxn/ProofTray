package org.openbridge.app.camera

internal data class CameraLocationFix(
    val latitude: Double, val longitude: Double, val altitude: Double, val timeMillis: Long, val elapsedRealtimeNanos: Long
) {
    fun fresh(nowNanos: Long, maxAgeNanos: Long): Boolean =
        elapsedRealtimeNanos > 0 && nowNanos - elapsedRealtimeNanos in 0 until maxAgeNanos
}

/** A sample and its cleanup stay bound to the same camera session, including after a service restart. */
internal abstract class CameraLocationChannel(val owner: Any, val sony: Boolean = false) {
    val maxAgeNanos: Long get() = if (sony) SonyLocationProtocol.MAX_AGE_NANOS else 60_000_000_000L
    abstract fun isCurrent(): Boolean
    abstract fun requested(): Boolean
    open suspend fun prepare(resume: Boolean) = Unit
    abstract suspend fun send(fix: CameraLocationFix): String
    abstract suspend fun clear()
}
