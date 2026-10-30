/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import java.util.List;
import java.util.Map;

/**
 * The calls the portal makes to Razorpay, with one brand's keys (each brand has its own Razorpay account, so the money
 * goes to that brand's bank). Amounts are in paise. A refusal comes back as an ApiException with Razorpay's reason.
 */
public interface RazorpayGateway {

    record Keys(String keyId, String keySecret) {
    }

    record Order(String id, long amount, String currency, String status) {
    }

    /** A payment as Razorpay reports it; the card part is filled for card payments, vpa for UPI. */
    record Payment(String id, String orderId, String status, long amount, String currency, String method,
                   String cardNetwork, String cardLast4, String cardIssuer, String cardType, Boolean cardInternational,
                   String vpa, String bank, String wallet, String email, String contact, String errorDescription) {
    }

    record Refund(String id, long amount, String status) {
    }

    /** The ways of paying the account takes (Razorpay's public list for a key ID): cards, UPI (QR code / UPI ID), UPI apps on phones. */
    record Methods(boolean card, boolean upi, boolean upiIntent) {
    }

    Order createOrder(Keys keys, long amount, String receipt, Map<String, String> notes);

    /** One payment, with its card details. */
    Payment payment(Keys keys, String paymentId);

    /** Every attempt to pay an order (without card details). */
    List<Payment> orderPayments(Keys keys, String orderId);

    /** Takes an authorized payment (when the account does not capture by itself). */
    Payment capture(Keys keys, String paymentId, long amount, String currency);

    Refund refund(Keys keys, String paymentId, long amount, Map<String, String> notes);

    /** null when Razorpay could not be asked. */
    Methods methods(String keyId);
}
