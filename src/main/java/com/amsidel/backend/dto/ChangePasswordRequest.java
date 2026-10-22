package com.amsidel.backend.dto;

import com.amsidel.backend.validation.Pin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    @NotBlank
    @Size(max = 64)
    private String currentPassword;

    @NotBlank
    @Pin
    private String newPassword;
}
