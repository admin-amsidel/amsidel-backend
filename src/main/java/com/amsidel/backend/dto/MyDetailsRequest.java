/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

/**
 * The personal details everyone may change on My profile, checked like the same fields of {@link EmployeeRequest}.
 * Name, designation, reporting line, user ID, employee number, joining date and company email stay with the admins.
 */
@Data
public class MyDetailsRequest {

    private static final String PHONE = "^\\+?[0-9 ()-]{7,20}$";
    private static final String PHONE_MESSAGE = "Enter a valid phone number (7-20 digits, may start with +)";

    @NotBlank(message = "Phone number is needed")
    @Pattern(regexp = PHONE, message = PHONE_MESSAGE)
    private String phone;

    @Email(message = "Enter a valid email address")
    @Size(max = 150)
    private String personalEmail;

    @Pattern(regexp = "^$|" + PHONE, message = PHONE_MESSAGE)
    private String emergencyContact;

    @Size(max = 500)
    private String address;

    @Past(message = "Date of birth must be in the past")
    private LocalDate dob;

    @Pattern(regexp = "^$|^(A|B|AB|O)[+-]$", message = "Blood group must be one of A+, A-, B+, B-, AB+, AB-, O+, O-")
    private String bloodGroup;
}
