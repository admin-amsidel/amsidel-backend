package com.amsidel.backend.entity;

/**
 * How a customer pays a bill: CARD and UPI online, through the brand's Razorpay account; MANUAL_UPI at the office,
 * to the brand's own UPI ID (its QR code shown by the staff, who then record the UPI reference).
 */
public enum OnlineMethod {
    CARD,
    UPI,
    MANUAL_UPI
}
