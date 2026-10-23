package com.amsidel.backend.dto;

import com.amsidel.backend.entity.Employee;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * An employee's PAN and salary account (bank account, IFSC, UPI ID). Kept out of the general employee API: read by
 * the person, their owners and admins, and payroll; changed by owners and admins (and payroll on the salary page).
 */
public final class BankDetails {

    private BankDetails() {
    }

    /** canEdit: the person asking may change them. */
    public record View(String pan, String bankAccount, String ifsc, String upi, boolean canEdit) {
        public static View of(Employee e, boolean canEdit) {
            return new View(e.getPanNumber(), e.getBankAccountNumber(), e.getIfscCode(), e.getUpiId(), canEdit);
        }
    }

    /** Blank clears a value. */
    @Data
    public static class Request {
        @Pattern(regexp = "|[A-Za-z]{5}[0-9]{4}[A-Za-z]", message = "PAN is 5 letters, 4 digits and a letter, e.g. ABCDE1234F")
        private String pan;

        @Pattern(regexp = "|[0-9]{6,20}", message = "Bank account number: 6 to 20 digits")
        private String bankAccount;

        @Pattern(regexp = "|[A-Za-z]{4}0[A-Za-z0-9]{6}", message = "IFSC is 11 characters: 4 letters, 0, then 6 letters or digits, e.g. HDFC0001234")
        private String ifsc;

        @Pattern(regexp = "|[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}", message = "UPI ID looks like name@bank, e.g. anu@okhdfcbank")
        private String upi;
    }
}
