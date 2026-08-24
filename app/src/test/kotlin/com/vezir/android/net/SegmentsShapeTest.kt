package com.vezir.android.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lock the `GET /label/{id}/segments/{speaker}` response deserialiser
 * against the JSON vezir server >= 0.15.0 sends (v0.11.1 "More" feature).
 */
class SegmentsShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesSegmentsResponse() {
        val raw = """
            {
              "speaker_id": "SPEAKER_00",
              "total": 2,
              "segments": [
                {"start": 0.0, "end": 4.0, "text": "Welcome everyone to the sync."},
                {"start": 8.0, "end": 14.5, "text": "Let's talk about the roadmap."}
              ]
            }
        """.trimIndent()
        val r = json.decodeFromString(LabelApi.SegmentsData.serializer(), raw)
        assertEquals("SPEAKER_00", r.speaker_id)
        assertEquals(2, r.total)
        assertEquals(2, r.segments.size)
        assertEquals("Welcome everyone to the sync.", r.segments[0].text)
        assertEquals(14.5, r.segments[1].end, 0.001)
    }

    @Test
    fun ignoresUnknownFields() {
        val raw = """
            {"speaker_id":"Juan Pablo","total":1,
             "segments":[{"start":1.0,"end":2.0,"text":"Hola","future":true}]}
        """.trimIndent()
        val r = json.decodeFromString(LabelApi.SegmentsData.serializer(), raw)
        assertEquals("Juan Pablo", r.speaker_id)
        assertEquals("Hola", r.segments[0].text)
    }

    // v0.11.2: imported sessions (vezir server >= 0.16.0 backfills) are
    // terminal — the detail screen must not poll/refresh them forever.
    @Test
    fun importedStatusIsTerminal() {
        val raw = """
            {"id":"01IMP","status":"imported","artifacts":"{}"}
        """.trimIndent()
        val s = json.decodeFromString(SessionApi.Session.serializer(), raw)
        org.junit.Assert.assertTrue(s.isTerminal)
    }
}
