/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.CurrencyCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** A brand's money on its documents: the symbol, the grouping and the words follow its currency. */
class MoneyTextTest {

    @Test
    void rupeesStayTheIndianWay() {
        assertThat(MoneyText.format(new BigDecimal("214945"), CurrencyCode.INR)).isEqualTo("₹2,14,945.00");
        assertThat(MoneyText.words(new BigDecimal("214945"), CurrencyCode.INR))
                .isEqualTo("Indian Rupee Two Lakh Fourteen Thousand Nine Hundred Forty-Five Only");
    }

    @Test
    void otherCurrenciesUseTheirSymbolAndInternationalGrouping() {
        assertThat(MoneyText.format(new BigDecimal("1234567.5"), CurrencyCode.USD)).isEqualTo("$1,234,567.50");
        assertThat(MoneyText.format(new BigDecimal("950"), CurrencyCode.EUR)).isEqualTo("€950.00");
        assertThat(MoneyText.format(new BigDecimal("-2500"), CurrencyCode.GBP)).isEqualTo("-£2,500.00");
        assertThat(MoneyText.words(new BigDecimal("1234567.50"), CurrencyCode.USD))
                .isEqualTo("US Dollar One Million Two Hundred Thirty-Four Thousand Five Hundred Sixty-Seven and Fifty Cents Only");
        assertThat(MoneyText.words(new BigDecimal("2000.05"), CurrencyCode.GBP)).isEqualTo("British Pound Two Thousand and Five Pence Only");
        assertThat(MoneyText.words(new BigDecimal("0"), CurrencyCode.EUR)).isEqualTo("Euro Zero Only");
    }

    @Test
    void theOtherCurrencies() {
        assertThat(MoneyText.format(new BigDecimal("1234567.5"), CurrencyCode.JPY)).isEqualTo("¥1,234,568");
        assertThat(MoneyText.words(new BigDecimal("1500"), CurrencyCode.JPY)).isEqualTo("Japanese Yen One Thousand Five Hundred Only");
        assertThat(MoneyText.format(new BigDecimal("2500"), CurrencyCode.AED)).isEqualTo("AED 2,500.00");
        assertThat(MoneyText.format(new BigDecimal("2500"), CurrencyCode.CHF)).isEqualTo("CHF 2,500.00");
        assertThat(MoneyText.format(new BigDecimal("2500"), CurrencyCode.AUD)).isEqualTo("A$2,500.00");
        assertThat(MoneyText.format(new BigDecimal("2500"), CurrencyCode.NZD)).isEqualTo("NZ$2,500.00");
        assertThat(MoneyText.format(new BigDecimal("2500"), CurrencyCode.CNY)).isEqualTo("CN¥2,500.00");
        assertThat(MoneyText.format(new BigDecimal("2500"), CurrencyCode.CAD)).isEqualTo("CA$2,500.00");
        assertThat(MoneyText.words(new BigDecimal("75.25"), CurrencyCode.AED)).isEqualTo("UAE Dirham Seventy-Five and Twenty-Five Fils Only");
    }

    @Test
    void unknownOrMissingIsRupees() {
        assertThat(CurrencyCode.of(null)).isEqualTo(CurrencyCode.INR);
        assertThat(CurrencyCode.of("xyz")).isEqualTo(CurrencyCode.INR);
        assertThat(CurrencyCode.of("eur")).isEqualTo(CurrencyCode.EUR);
    }
}
