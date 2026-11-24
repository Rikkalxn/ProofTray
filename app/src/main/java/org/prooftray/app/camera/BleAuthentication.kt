package org.prooftray.app.camera

/** The authentication record is 17 bytes; some implementations append data after these standard fields. */
internal fun authenticationRecord(value: ByteArray, expectedStage: Int): ByteArray {
    require(value.size in 17..512) { "身份验证阶段 $expectedStage：响应长度 ${value.size} 无效（至少需要 17 字节）" }
    val stage = value[0].toInt() and 255
    check(stage == expectedStage) { "身份验证阶段 $expectedStage：收到阶段 $stage，长度 ${value.size}" }
    return value.copyOfRange(0, 17)
}

