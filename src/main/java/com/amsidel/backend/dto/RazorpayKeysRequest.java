package com.amsidel.backend.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** A brand's Razorpay keys (Dashboard › Account & Settings › API keys). Both blank = online payments off. */
@Data
public class RazorpayKeysRequest {

    @Pattern(regexp = "|rzp_(test|live)_[A-Za-z0-9]{6,30}", message = "The key ID starts with rzp_live_ (or rzp_test_ for trying out)")
    private String keyId;

    /** Blank = keep the secret already saved. */
    @Pattern(regexp = "|[A-Za-z0-9]{10,60}", message = "The key secret is 10 to 60 letters and digits")
    private String keySecret;
}
