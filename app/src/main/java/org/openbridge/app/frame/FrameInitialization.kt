package org.openbridge.app.frame

import org.openbridge.app.domain.FrameTemplate

/** Only feature initialization boundaries use this guard; VM failures and unrelated Errors must still propagate. */
internal fun <T> withFrameInitialization(load: () -> T): T = try {
    load()
} catch (error: ExceptionInInitializerError) {
    throw frameInitializationFailure(error)
} catch (error: NoClassDefFoundError) {
    throw frameInitializationFailure(error)
}

private fun frameInitializationFailure(error: LinkageError): IllegalStateException {
    val cause = generateSequence<Throwable>(error) { it.cause }.take(6).last()
    val detail = cause.message?.lineSequence()?.firstOrNull().orEmpty().take(200)
    return IllegalStateException("边框组件初始化失败，请重启应用后重试" + if (detail.isBlank()) "" else "：" + detail, error)
}

internal fun initialFrameTemplate(initial: FrameTemplate?, load: () -> List<FrameTemplate> = { FrameTemplates.all }): FrameTemplate =
    withFrameInitialization {
        val templates = load()
        val chosen = initial ?: templates.firstOrNull() ?: error("没有可用的边框模板")
        FrameCodec.validate(chosen)
        chosen
    }
