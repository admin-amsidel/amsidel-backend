package com.amsidel.backend.service;

import com.amsidel.backend.dto.TradeDtos.PaymentBody;
import com.amsidel.backend.dto.TradeDtos.PaymentView;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.TradePaymentRepository;
import com.amsidel.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import static com.amsidel.backend.service.CatalogService.blank;

/**
 * Recording money for an invoice or a purchase bill, and voiding a mistake. A payment cannot be more than is still
 * due, nor a refund more than was paid. The document's paid amount is worked out again from its payments each time.
 */
@Component
@RequiredArgsConstructor
public class TradePayments {

    private final TradePaymentRepository repository;
    private final TradeAccess access;

    @Transactional(propagation = Propagation.MANDATORY)
    public TradePayment record(AuthUser me, Brand brand, TradeParty party, BigDecimal total, BigDecimal paid,
                               PaymentBody body, java.util.function.Consumer<TradePayment> link) {
        TradePaymentKind kind = body.getKind() == null ? TradePaymentKind.PAYMENT : body.getKind();
        BigDecimal amount = body.getAmount().setScale(2, RoundingMode.HALF_UP);
        if (kind == TradePaymentKind.PAYMENT && amount.compareTo(total.subtract(paid)) > 0) {
            throw ApiException.badRequest("That is more than is still due (" + PayslipPdf.rupees(total.subtract(paid).max(BigDecimal.ZERO)) + ")");
        }
        if (kind == TradePaymentKind.REFUND && amount.compareTo(paid) > 0) {
            throw ApiException.badRequest("A refund cannot be more than was paid (" + PayslipPdf.rupees(paid) + ")");
        }
        if (body.getPaidOn().isAfter(access.today())) {
            throw ApiException.badRequest("The payment date cannot be in the future");
        }
        TradePayment p = new TradePayment();
        p.setBrand(brand);
        p.setParty(party);
        p.setKind(kind);
        p.setPaidOn(body.getPaidOn());
        p.setAmount(amount);
        p.setMode(body.getMode());
        p.setTransactionId(blank(body.getTransactionId()));
        p.setBankDetails(blank(body.getBankDetails()));
        p.setUpiId(blank(body.getUpiId()));
        p.setRemarks(blank(body.getRemarks()));
        p.setRecordedBy(access.me(me));
        link.accept(p);
        return repository.save(p);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TradePayment voidIt(AuthUser me, TradePayment p, String reason) {
        if (p.isVoided()) {
            throw ApiException.conflict("This payment is already voided");
        }
        p.setVoided(true);
        p.setVoidedAt(Instant.now());
        p.setVoidedBy(access.me(me));
        p.setVoidReason(reason.strip());
        return repository.save(p);
    }

    /** Payments less refunds, leaving out voided ones. */
    public static BigDecimal paid(List<TradePayment> payments) {
        return payments.stream().filter(p -> !p.isVoided())
                .map(p -> p.getKind() == TradePaymentKind.REFUND ? p.getAmount().negate() : p.getAmount())
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    public static PaymentView view(TradePayment p) {
        Long docId = p.getInvoice() != null ? p.getInvoice().getId() : p.getPurchaseBill() != null ? p.getPurchaseBill().getId() : null;
        String number = p.getInvoice() != null ? p.getInvoice().getNumber() : p.getPurchaseBill() != null ? p.getPurchaseBill().getNumber() : null;
        String partyName = p.getCustomer() != null ? p.getCustomer().getName() : p.getSupplier() != null ? p.getSupplier().getName() : null;
        return new PaymentView(p.getId(), p.getParty(), p.getKind(), p.getPaidOn(), p.getAmount(), p.getMode(), p.getTransactionId(),
                p.getBankDetails(), p.getUpiId(), p.getRemarks(), p.getRecordedBy() == null ? null : p.getRecordedBy().getName(),
                p.getCreatedAt(), p.isVoided(), p.getVoidedAt(), p.getVoidedBy() == null ? null : p.getVoidedBy().getName(),
                p.getVoidReason(), docId, number, partyName, p.getBrand() == null ? null : p.getBrand().getCode());
    }
}
