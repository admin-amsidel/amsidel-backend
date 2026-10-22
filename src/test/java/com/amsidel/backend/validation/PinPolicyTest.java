package com.amsidel.backend.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"ab@123", "1a2b3#", "Ams@2026", "x!y999", "Zz_000"})
    void acceptsTwoLettersOneSpecialThreeNumbers(String pin) {
        assertTrue(PinPolicy.isValid(pin));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a@123",     // one letter
            "ab1234",    // no special character
            "ab@12",     // two numbers
            "ab@ 123",   // space
            "@@@123",    // no letters
            ""
    })
    void rejectsWeakPins(String pin) {
        assertFalse(PinPolicy.isValid(pin));
    }
}
