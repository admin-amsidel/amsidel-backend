package com.amsidel.backend.validation;

/**
 * Login PIN: at least 2 letters, 1 special character and 3 numbers (6 characters minimum),
 * no spaces. The web app checks the same rule while the user types (portal/pin-policy.ts).
 */
public final class PinPolicy {

    public static final String RULE =
            "PIN must have at least 2 letters, 1 special character and 3 numbers (no spaces)";

    public static final int MIN_LENGTH = 6;
    public static final int MAX_LENGTH = 64;

    private PinPolicy() {
    }

    public static boolean isValid(String pin) {
        if (pin == null || pin.length() < MIN_LENGTH || pin.length() > MAX_LENGTH) {
            return false;
        }
        int letters = 0;
        int digits = 0;
        int specials = 0;
        for (char c : pin.toCharArray()) {
            if (Character.isWhitespace(c)) {
                return false;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                letters++;
            } else if (c >= '0' && c <= '9') {
                digits++;
            } else {
                specials++;
            }
        }
        return letters >= 2 && digits >= 3 && specials >= 1;
    }
}
