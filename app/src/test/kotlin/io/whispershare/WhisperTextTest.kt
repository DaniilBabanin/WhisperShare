package io.whispershare

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperTextTest {
    @Test
    fun pureArtifactsBecomeEmpty() {
        for (a in listOf("[BLANK_AUDIO]", "[MUSIC PLAYING] [MUSIC PLAYING]", "[BLANK_AUDIO].", "(music)", "*laughs*", "   "))
            assertEquals("artifact=$a", "", stripNonSpeech(a))
    }

    @Test
    fun realSpeechSurvivesArtifactRemoval() {
        assertEquals("Buy milk and eggs", stripNonSpeech("Buy milk [BLANK_AUDIO] and eggs"))
        assertEquals("Hello there", stripNonSpeech("Hello *laughs* there"))
        assertEquals("Remember the meeting", stripNonSpeech("  Remember the meeting  "))
    }

    @Test
    fun genuineParentheticalsKept() {
        // "later" isn't a non-speech cue → the aside stays.
        assertEquals("Call me (later)", stripNonSpeech("Call me (later)"))
    }
}
