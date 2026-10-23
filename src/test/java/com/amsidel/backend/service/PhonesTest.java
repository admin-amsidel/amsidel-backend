package com.amsidel.backend.service;

import com.amsidel.backend.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhonesTest {

    @Test
    void oneFormForEveryWayOfTyping() {
        assertThat(Phones.normalize("9847012345", "Phone")).isEqualTo("+91 98470 12345");
        assertThat(Phones.normalize("+91 98470-12345", "Phone")).isEqualTo("+91 98470 12345");
        assertThat(Phones.normalize("09847012345", "Phone")).isEqualTo("+91 98470 12345");
        assertThat(Phones.normalize("0091 9847012345", "Phone")).isEqualTo("+91 98470 12345");
        assertThat(Phones.normalize("+91 484 240 0400", "Phone")).isEqualTo("+91 48424 00400");
        assertThat(Phones.normalize("+39 312 345 6789", "Phone")).isEqualTo("+39 3123456789");
        assertThat(Phones.normalize("+971 50 123 4567", "Phone")).isEqualTo("+971 501234567");
        assertThat(Phones.normalize("  ", "Phone")).isNull();
    }

    @Test
    void wrongLengthsAndCodesAreRefused() {
        assertThatThrownBy(() -> Phones.normalize("98470", "Phone")).isInstanceOf(ApiException.class)
                .hasMessageContaining("+91 number has 10 digits");
        assertThatThrownBy(() -> Phones.normalize("+971 50 12", "Second phone")).hasMessageStartingWith("Second phone:");
        assertThatThrownBy(() -> Phones.normalize("+999 1234567", "Phone")).hasMessageContaining("country code");
        assertThatThrownBy(() -> Phones.normalize("1847012345", "Phone")).hasMessageContaining("starts with 2 to 9");
        assertThatThrownBy(() -> Phones.normalize("98470abc45", "Phone")).hasMessageContaining("only digits");
    }
}
