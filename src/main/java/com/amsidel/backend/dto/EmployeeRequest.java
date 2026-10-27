package com.amsidel.backend.dto;

import com.amsidel.backend.entity.Role;
import com.amsidel.backend.validation.Pin;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

/** Create or update an employee. Blank optional fields are stored as empty (null). */
@Data
public class EmployeeRequest {

    private static final String PHONE = "^\\+?[0-9 ()-]{7,20}$";
    private static final String PHONE_MESSAGE = "Enter a valid phone number (7-20 digits, may start with +)";

    @NotBlank
    @Size(max = 150)
    private String name;

    /** Leave empty to generate one from the brand code, e.g. DW0007. */
    @Size(max = 30)
    @Pattern(regexp = "^[A-Za-z0-9-]*$", message = "Employee number may only use letters, numbers and dash")
    private String empNumber;

    @NotBlank
    @Size(min = 3, max = 50)
    @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "User ID may only use letters, numbers, dot, dash and underscore")
    private String userId;

    /** First PIN; required when creating, ignored on update (use reset-password instead). */
    @Pin
    private String password;

    @NotNull
    private Role role;

    /** The user type (portal access); its access level replaces role. Empty = the built-in type of role. */
    private Long userTypeId;

    /** Required for managers and employees; empty for group-level owners and admins. */
    private Long brandId;

    /** Job title and level; optional. */
    private Long designationId;

    /** Direct superior's employee id; optional. */
    private Long reportsToId;

    @Size(max = 500)
    private String address;

    @NotBlank
    @Pattern(regexp = PHONE, message = PHONE_MESSAGE)
    private String phone;

    /** Photo file in the web app's public/users folder, e.g. "users/DW0001.jpg". */
    @Pattern(regexp = "^$|^users/[A-Za-z0-9._-]+\\.(jpg|jpeg|png|webp)$", flags = Pattern.Flag.CASE_INSENSITIVE,
            message = "Photo must be a .jpg, .png or .webp file name inside users/, e.g. users/DW0001.jpg")
    private String photoPath;

    @NotNull
    private LocalDate joinedDate;

    @Past
    private LocalDate dob;

    @Pattern(regexp = "^$|^(A|B|AB|O)[+-]$", message = "Blood group must be one of A+, A-, B+, B-, AB+, AB-, O+, O-")
    private String bloodGroup;

    @Email
    @Size(max = 150)
    private String personalEmail;

    @Email
    @Size(max = 150)
    private String companyEmail;

    @Pattern(regexp = "^$|" + PHONE, message = PHONE_MESSAGE)
    private String emergencyContact;

    private Boolean active;
}
