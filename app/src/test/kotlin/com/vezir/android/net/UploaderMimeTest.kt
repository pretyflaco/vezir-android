package com.vezir.android.net

import org.junit.Assert.assertEquals
import org.junit.Test

class UploaderMimeTest {

    @Test
    fun `video extensions map to video MIME types`() {
        assertEquals("video/mp4", Uploader.mediaTypeForFileName("vezir-screen-20260910-120000.mp4").toString())
        assertEquals("video/quicktime", Uploader.mediaTypeForFileName("demo.mov").toString())
    }

    @Test
    fun `audio extensions keep their MIME types`() {
        assertEquals("audio/ogg", Uploader.mediaTypeForFileName("vezir-20260910-120000.ogg").toString())
        assertEquals("audio/wav", Uploader.mediaTypeForFileName("a.wav").toString())
        assertEquals("audio/mpeg", Uploader.mediaTypeForFileName("b.mp3").toString())
    }

    @Test
    fun `extension matching is case insensitive`() {
        assertEquals("video/mp4", Uploader.mediaTypeForFileName("DEMO.MP4").toString())
    }

    @Test
    fun `unknown or missing extension defaults to ogg`() {
        assertEquals("audio/ogg", Uploader.mediaTypeForFileName("recording").toString())
        assertEquals("audio/ogg", Uploader.mediaTypeForFileName("notes.txt").toString())
    }
}
