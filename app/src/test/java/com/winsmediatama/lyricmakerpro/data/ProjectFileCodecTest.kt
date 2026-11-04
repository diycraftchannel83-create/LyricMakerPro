package com.winsmediatama.lyricmakerpro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProjectFileCodecTest {
    @Test
    fun defaultProjectRoundTripsThroughPortableJson() {
        val original = PresetLibrary.createDefaultProject()

        val json = ProjectFileCodec.encode(original)
        val restored = ProjectFileCodec.decode(json)

        assertNotNull(json)
        assertEquals(original, restored)
    }

    @Test
    fun projectWithLyricsAndMediaSettingsRoundTrips() {
        val original = PresetLibrary.sampleProjects.first()

        val restored = ProjectFileCodec.decode(ProjectFileCodec.encode(original))

        assertEquals(original, restored)
    }
}
