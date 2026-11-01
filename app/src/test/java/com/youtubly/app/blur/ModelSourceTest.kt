package com.youtubly.app.blur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSourceTest {
    private val base = "https://huggingface.co"

    @Test fun repoShorthandUsesDefaultFile() {
        assertEquals("$base/me/gender/resolve/main/gender.tflite", ModelSource.resolveUrl("me/gender"))
        assertEquals("$base/me/gender/resolve/main/gender.tflite", ModelSource.resolveUrl("  me/gender/  "))
    }

    @Test fun repoWithExplicitFile() {
        assertEquals("$base/me/g/resolve/main/models/face.tflite", ModelSource.resolveUrl("me/g/models/face.tflite"))
    }

    @Test fun blobLinkBecomesResolveLink() {
        assertEquals("$base/me/g/resolve/main/gender.tflite",
            ModelSource.resolveUrl("https://huggingface.co/me/g/blob/main/gender.tflite"))
        assertEquals("$base/me/g/resolve/v2/sub/x.tflite",
            ModelSource.resolveUrl("https://huggingface.co/me/g/resolve/v2/sub/x.tflite?download=true"))
    }

    @Test fun bareRepoLinkAndTreeLink() {
        assertEquals("$base/me/g/resolve/main/gender.tflite", ModelSource.resolveUrl("https://huggingface.co/me/g"))
        assertEquals("$base/me/g/resolve/dev/gender.tflite", ModelSource.resolveUrl("https://huggingface.co/me/g/tree/dev"))
    }

    @Test fun rejectsOtherHostsAndInsecureLinks() {
        assertNull(ModelSource.resolveUrl("https://evil.example/me/g/resolve/main/gender.tflite"))
        assertNull(ModelSource.resolveUrl("http://huggingface.co/me/g/resolve/main/gender.tflite"))
        assertNull(ModelSource.resolveUrl("https://huggingface.co.evil.example/me/g"))
    }

    @Test fun rejectsNonTfliteAndJunk() {
        assertNull(ModelSource.resolveUrl("me/g/best.pt"))
        assertNull(ModelSource.resolveUrl("https://huggingface.co/me/g/resolve/main/model.onnx"))
        assertNull(ModelSource.resolveUrl(""))
        assertNull(ModelSource.resolveUrl("justoneword"))
        assertNull(ModelSource.resolveUrl("me/g/../../etc/x.tflite"))
        assertNull(ModelSource.resolveUrl("me/g h/gender.tflite"))
    }

    @Test fun recognisesTfliteHeader() {
        val ok = byteArrayOf(0x1C, 0, 0, 0, 'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte(), 0)
        assertTrue(ModelSource.looksLikeTflite(ok))
        assertFalse(ModelSource.looksLikeTflite("PK\u0003\u0004 zip file".toByteArray()))
        assertFalse(ModelSource.looksLikeTflite("<html><body>".toByteArray()))
        assertFalse(ModelSource.looksLikeTflite(ByteArray(3)))
    }
}
