package com.amsidel.backend.dto;

import com.amsidel.backend.validation.Pin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Admin sets a temporary PIN; the employee must change it at next login. */
@Data
public class ResetPasswordRequest {

    @NotBlank
    @Pin
    private String newPassword;
}
