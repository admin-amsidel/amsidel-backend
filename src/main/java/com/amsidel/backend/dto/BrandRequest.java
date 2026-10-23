package com.amsidel.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class BrandRequest {

    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9]{2,10}$", message = "Code must be 2-10 letters or numbers, e.g. DW")
    private String code;

    @NotBlank
    @Size(max = 150)
    private String name;

    private Boolean active;

    @Size(max = 500)
    private String address;

    private LocalDate launchedDate;

    /** Comma separated names. */
    @Size(max = 500)
    private String owners;

    @Pattern(regexp = "|[0-9]{2}[A-Za-z]{5}[0-9]{4}[A-Za-z][0-9A-Za-z][Zz][0-9A-Za-z]",
            message = "GSTIN is 15 characters, e.g. 32ABCDE1234F1Z5")
    private String gstNumber;

    @Size(max = 60)
    private String category;

    @Pattern(regexp = "^$|^\\+?[0-9 ()-]{7,20}$", message ="Enter a valid phone number (7-20 digits, may start with +)")
    private String contactNumber;

    @jakarta.validation.constraints.Email(message = "Enter a valid email address")
    @Size(max = 150)
    private String email;

    /** For invoices. */
    @Pattern(regexp = "|[0-9]{6,20}", message = "Bank account number: 6 to 20 digits")
    private String bankAccount;

    @Pattern(regexp = "|[A-Za-z]{4}0[A-Za-z0-9]{6}", message = "IFSC is 11 characters: 4 letters, 0, then 6 letters or digits, e.g. HDFC0001234")
    private String ifsc;

    @Pattern(regexp = "|[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}", message = "UPI ID looks like name@bank, e.g. amsidel@okhdfcbank")
    private String upi;
}
