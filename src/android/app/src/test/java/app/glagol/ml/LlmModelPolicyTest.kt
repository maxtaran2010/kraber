package app.glagol.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmModelPolicyTest {
    private val gib = 1L shl 30

    @Test
    fun fourGigDevicesGetLite() {
        // Эмулятор/телефон «4 ГБ» сообщает 3.3–3.8 ГиБ
        assertEquals(LlmModel.LITE, LlmModelPolicy.auto((3.3 * gib).toLong(), lowRamDevice = false))
        assertEquals(LlmModel.LITE, LlmModelPolicy.auto((3.8 * gib).toLong(), lowRamDevice = false))
    }

    @Test
    fun sixGigAndMoreGetFull() {
        assertEquals(LlmModel.FULL, LlmModelPolicy.auto((5.2 * gib).toLong(), lowRamDevice = false))
        assertEquals(LlmModel.FULL, LlmModelPolicy.auto(12 * gib, lowRamDevice = false))
    }

    @Test
    fun lowRamFlagOrPastOomForcesLite() {
        assertEquals(LlmModel.LITE, LlmModelPolicy.auto(8 * gib, lowRamDevice = true))
        assertEquals(LlmModel.LITE, LlmModelPolicy.auto(8 * gib, lowRamDevice = false, fullTooHeavy = true))
    }

    @Test
    fun unknownRamKeepsFull() {
        assertEquals(LlmModel.FULL, LlmModelPolicy.auto(0, lowRamDevice = false))
    }

    @Test
    fun explicitChoiceWins() {
        assertEquals(LlmModel.FULL, LlmModelPolicy.resolve(LlmChoice.FULL, LlmModel.LITE))
        assertEquals(LlmModel.LITE, LlmModelPolicy.resolve(LlmChoice.LITE, LlmModel.FULL))
        assertEquals(LlmModel.LITE, LlmModelPolicy.resolve(LlmChoice.AUTO, LlmModel.LITE))
    }
}
