package com.amsidel.backend.service;

import com.amsidel.backend.exception.ApiException;

import java.util.Comparator;
import java.util.List;

/**
 * Phone numbers in one form everywhere: the country code, a space, then the number ("+91 98470 12345" for India,
 * "+39 3123456789" elsewhere). A number typed without a country code is taken as Indian; a leading 0 or 00 is
 * dropped. Each country's number must have its own length (India: 10 digits, not starting with 0 or 1).
 * The same countries are offered by the portal's phone field.
 */
public final class Phones {

    /** Country code, shortest and longest number without it. */
    private record Country(String code, int min, int max) {
    }

    private static final List<Country> COUNTRIES = java.util.stream.Stream.of(
                    new Country("91", 10, 10), new Country("971", 8, 9), new Country("966", 9, 9), new Country("974", 8, 8),
                    new Country("965", 8, 8), new Country("968", 8, 8), new Country("973", 8, 8), new Country("1", 10, 10),
                    new Country("44", 10, 10), new Country("353", 7, 9), new Country("49", 6, 11), new Country("33", 9, 9),
                    new Country("39", 6, 11), new Country("34", 9, 9), new Country("31", 9, 9), new Country("41", 9, 9),
                    new Country("61", 9, 9), new Country("64", 8, 10), new Country("65", 8, 8), new Country("60", 9, 10),
                    new Country("94", 9, 9), new Country("977", 8, 10), new Country("880", 10, 10), new Country("960", 7, 7),
                    new Country("86", 11, 11), new Country("81", 9, 10), new Country("27", 9, 9))
            // the longest code first, so +971 is not read as +97…
            .sorted(Comparator.comparingInt((Country c) -> c.code().length()).reversed()).toList();

    private Phones() {
    }

    /** null or blank -> null; otherwise the number in the one form, or 400 saying what is wrong (label: "Phone"). */
    public static String normalize(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        if (!s.matches("^\\+?[0-9 ()\\-.]+$")) {
            throw ApiException.badRequest(label + ": use only digits, spaces and a + before the country code");
        }
        String digits = s.replaceAll("\\D", "");
        boolean international = s.startsWith("+") || digits.startsWith("00");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        Country country;
        String national;
        if (international) {
            final String all = digits;
            country = COUNTRIES.stream().filter(c -> all.startsWith(c.code())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest(label + ": the country code is not one the portal knows"));
            national = all.substring(country.code().length());
        } else {
            country = COUNTRIES.stream().filter(c -> c.code().equals("91")).findFirst().orElseThrow();
            national = digits;
        }
        national = national.replaceFirst("^0+", "");
        if (national.length() < country.min() || national.length() > country.max()) {
            throw ApiException.badRequest(label + ": a +" + country.code() + " number has "
                    + (country.min() == country.max() ? country.min() : country.min() + " to " + country.max()) + " digits after the country code");
        }
        if (country.code().equals("91")) {
            if (national.charAt(0) == '1') {
                throw ApiException.badRequest(label + ": an Indian number starts with 2 to 9");
            }
            return "+91 " + national.substring(0, 5) + " " + national.substring(5);
        }
        return "+" + country.code() + " " + national;
    }
}
