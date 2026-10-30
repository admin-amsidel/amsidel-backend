/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class UpiQrTest {

    @Test
    void aQrMadeForAnInvoiceReadsBackAsItsUpiLink() {
        String link = UpiQr.link("amsidel@ybl", "Amsidel Doors & Windows", new BigDecimal("5502"), "DWSLD-102026-001");
        assertThat(link).isEqualTo("upi://pay?pa=amsidel%40ybl&pn=Amsidel%20Doors%20%26%20Windows&am=5502.00&cu=INR&tn=DWSLD-102026-001");
        String read = UpiQr.read(UpiQr.png(link, 300));
        assertThat(read).isEqualTo(link);
        assertThat(UpiQr.payee(read)).isEqualTo("amsidel@ybl");
    }

    @Test
    void notAQr() {
        assertThat(UpiQr.read(new byte[]{1, 2, 3})).isNull();
        assertThat(UpiQr.payee("https://example.com")).isNull();
    }
}
