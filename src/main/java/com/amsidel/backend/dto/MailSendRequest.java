package com.amsidel.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * One personal mail to one employee. {name} in the subject, greeting or text becomes the employee's name.
 * The portal sends one of these per selected person, so every mail goes out on its own.
 */
@Data
public class MailSendRequest {

    @NotNull
    private Long employeeId;

    @NotBlank
    @Size(max = 200)
    private String subject;

    /** After "Dear": empty = the employee's name; or any text, e.g. "All" or "Team". */
    @Size(max = 100)
    private String greeting;

    @NotBlank
    @Size(max = 20000)
    private String body;
}
