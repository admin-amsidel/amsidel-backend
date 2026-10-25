package com.amsidel.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * One personal mail to one employee (staff mail). The portal sends one of these per selected person, so every mail
 * goes out on its own. The content is in MailContent.
 */
@Getter
@Setter
public class MailSendRequest extends MailContent {

    @NotNull
    private Long employeeId;

    /** Saving my mail signature (used for staff mail and for customer and supplier mail). */
    @Data
    public static class Signature {
        @Size(max = 1000, message = "The signature can be at most 1000 characters")
        private String signature;
    }
}
