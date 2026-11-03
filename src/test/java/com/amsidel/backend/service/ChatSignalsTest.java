/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatSignalsTest {

    @Test
    void aSecondSyncWithinTheGapIsRefused() {
        ChatSignals s = new ChatSignals(60_000);
        s.syncing(1L);
        assertThatThrownBy(() -> s.syncing(1L)).isInstanceOf(ApiException.class).hasMessageContaining("Too many");
        s.syncing(2L); // someone else is not held back
    }

    @Test
    void theVersionMovesOnlyForThoseConcerned() {
        ChatSignals s = new ChatSignals(0);
        String a = s.version(1L);
        String b = s.version(2L);
        s.changed(List.of(1L));
        assertThat(s.version(1L)).isNotEqualTo(a);
        assertThat(s.version(2L)).isEqualTo(b);
        s.changedForAll();
        assertThat(s.version(2L)).isNotEqualTo(b);
    }
}
