package com.sinch.vqprobe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AudioInjectionOptionsTest {
    @Test fun omittedModePreservesNormalPlaybackOptions() {
        assertEquals(TxEngineeringMode.NORMAL, TxEngineeringMode.parse(null, null))
        assertEquals(TxEngineeringMode.NORMAL, TxEngineeringMode.parse("NORMAL", true))
        assertEquals(TxEngineeringMode.NORMAL, TxEngineeringMode.parse("NORMAL", false))
    }

    @Test fun validationAllowsOnlyDefaultOrExplicitlyEnabledMicrophoneMute() {
        assertEquals(TxEngineeringMode.DIGITAL_TX_VALIDATION,
            TxEngineeringMode.parse("DIGITAL_TX_VALIDATION", null))
        assertEquals(TxEngineeringMode.DIGITAL_TX_VALIDATION,
            TxEngineeringMode.parse("DIGITAL_TX_VALIDATION", true))
        rejection("DIGITAL_TX_VALIDATION_REQUIRES_MICROPHONE_MUTE") {
            TxEngineeringMode.parse("DIGITAL_TX_VALIDATION", false)
        }
    }

    @Test fun rejectsUnknownModesInsteadOfSilentlyWeakeningValidation() {
        for (mode in listOf("", "digital_tx_validation", "SPEAKER", "NORMAL ", "TEST")) {
            rejection("INVALID_AUDIO_MODE") { TxEngineeringMode.parse(mode, true) }
        }
    }

    private fun rejection(code: String, action: () -> Unit) {
        try {
            action()
            fail("Expected rejection: $code")
        } catch (e: IllegalArgumentException) {
            assertEquals(code, e.message)
        }
    }
}
