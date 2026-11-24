package org.prooftray.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDeletionPolicyTest {
    @Test fun acceptsConcreteMediaItems() {
        listOf("content://media/external_primary/images/media/7", "content://media/external/video/media/8",
            "content://media/1234-ABCD/file/9", "content://media/external_primary/downloads/10",
            "content://media/external/audio/media/11").forEach { assertTrue(it, isMediaItemUri(it)) }
    }

    @Test fun rejectsCollectionsAndInvalidIds() {
        listOf("content://media/external/images/media", "content://media/external/file", "content://media/external/file/0",
            "content://media/external/file/-1", "content://media/external/file/9223372036854775808",
            "content://media/external/images/7", "content://media/external/file/all").forEach { assertFalse(it, isMediaItemUri(it)) }
    }

    @Test fun rejectsCameraCachesAndOtherProviders() {
        listOf("openbridge-remote://camera/1", "file:///data/user/0/org.openbridge.app/cache/7.jpg",
            "content://cloud.photos/external/images/media/7", "content://media.evil/external/file/7",
            "content://com.android.providers.media.documents/document/image:7", "https://media/external/file/7")
            .forEach { assertFalse(it, isMediaItemUri(it)) }
    }

    @Test fun rejectsAmbiguousOrDecoratedPaths() {
        listOf("content://media/external/file/7?selection=all", "content://media/external/file/7#fragment",
            "content://media/external/file/7/", "content://media/external//file/7", "content://media/external/file/%37",
            "content://media/../file/7", "content://user@media/external/file/7", "not a uri")
            .forEach { assertFalse(it, isMediaItemUri(it)) }
    }
}
