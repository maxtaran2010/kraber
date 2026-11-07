package app.glagol.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeThresholdTest {
    private val p = SpeakerClustering.Params()

    @Test
    fun lowForLittleSpeech() {
        assertEquals(0.65f, SpeakerClustering.mergeThresholdFor(5f, p), 1e-6f)
        assertEquals(0.65f, SpeakerClustering.mergeThresholdFor(20f, p), 1e-6f)
    }

    @Test
    fun strictForMinutesOfSpeech() {
        assertEquals(0.9f, SpeakerClustering.mergeThresholdFor(120f, p), 1e-6f)
        assertEquals(0.9f, SpeakerClustering.mergeThresholdFor(3000f, p), 1e-6f)
    }

    @Test
    fun linearInBetween() {
        assertEquals(0.775f, SpeakerClustering.mergeThresholdFor(70f, p), 1e-5f)
    }

    @Test
    fun lowNotAboveHighMeansConstant() {
        val q = p.copy(mergeThresholdLow = 0.95f)
        assertEquals(0.9f, SpeakerClustering.mergeThresholdFor(5f, q), 1e-6f)
    }
}
