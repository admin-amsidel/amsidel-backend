/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.OnlinePayDtos.*;
import com.amsidel.backend.dto.TradeDtos.PaymentBody;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.*;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.amsidel.backend.service.CatalogService.blank;

/**
 * Pay bill: a customer pays an invoice online, by card or UPI, through the Razorpay account of the invoice's brand, so
 * the money settles to that brand's bank. A processing fee (a percent per way of paying, kept in online_fees) goes on
 * top of the amount paid. The browser opens Razorpay's window with the order made here; once paid, the payment is
 * checked with Razorpay (signature and amount) and recorded on the invoice, and where it came from (card network and
 * last 4 digits, or the payer's UPI ID) is kept for refunds. Card numbers, expiry dates and CVVs never reach the portal.
 */
@Slf4j
@Service
public class OnlinePayService {

    static final BigDecimal DEFAULT_FEE = new BigDecimal("3.00");

    private final OnlineFeeRepository feeRepository;
    private final OnlinePaymentRepository onlineRepository;
    private final PaymentSourceRepository sourceRepository;
    private final SalesDocRepository docRepository;
    private final CustomerRepository customerRepository;
    private final BrandUpiQrRepository qrRepository;
    private final SalesService sales;
    private final RazorpayGateway gateway;
    private final TradeAccess access;
    private final ZoneId zone;
    /** The company's default Razorpay keys (environment), for brands without their own; null when not set. */
    private final RazorpayGateway.Keys defaultKeys;
    /** How long what an account takes (cards, UPI) is remembered before Razorpay is asked again. */
    private final java.time.Duration methodsFor;

    public OnlinePayService(OnlineFeeRepository feeRepository, OnlinePaymentRepository onlineRepository, PaymentSourceRepository sourceRepository,
                            SalesDocRepository docRepository, CustomerRepository customerRepository, BrandUpiQrRepository qrRepository, SalesService sales,
                            RazorpayGateway gateway, TradeAccess access, @Value("${app.leave.zone}") ZoneId zone,
                            @Value("${app.razorpay.key-id:}") String defaultKeyId, @Value("${app.razorpay.key-secret:}") String defaultKeySecret,
                            @Value("${app.razorpay.methods-cache:PT10M}") java.time.Duration methodsFor) {
        this.methodsFor = methodsFor;
        this.feeRepository = feeRepository;
        this.onlineRepository = onlineRepository;
        this.sourceRepository = sourceRepository;
        this.docRepository = docRepository;
        this.customerRepository = customerRepository;
        this.qrRepository = qrRepository;
        this.sales = sales;
        this.gateway = gateway;
        this.access = access;
        this.zone = zone;
        this.defaultKeys = defaultKeyId.isBlank() || defaultKeySecret.isBlank() ? null
                : new RazorpayGateway.Keys(defaultKeyId.strip(), defaultKeySecret.strip());
    }

    /** The fees start at 3% for cards and for UPI; changed later in the portal (or the table). */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedFees() {
        for (OnlineMethod m : OnlineMethod.values()) {
            if (!feeRepository.existsById(m)) {
                OnlineFee f = new OnlineFee();
                f.setMethod(m);
                f.setPercent(defaultFee(m));
                f.setUpdatedAt(Instant.now());
                feeRepository.save(f);
            }
        }
    }

    /** 3% for cards and UPI through Razorpay; nothing for manual UPI (no gateway takes a cut). */
    static BigDecimal defaultFee(OnlineMethod m) {
        return m == OnlineMethod.MANUAL_UPI ? BigDecimal.ZERO.setScale(2) : DEFAULT_FEE;
    }

    private void canUse(AuthUser me) {
        access.require(me, "You cannot take bill payments", Menus.PROD_PAY);
    }

    // ---- setup ----

    @Transactional(readOnly = true)
    public PaySetup setup(AuthUser me) {
        canUse(me);
        List<FeeView> fees = Arrays.stream(OnlineMethod.values()).map(m -> feeRepository.findById(m)
                        .map(f -> new FeeView(m, f.getPercent(), f.getUpdatedAt(), f.getUpdatedBy() == null ? null : f.getUpdatedBy().getName()))
                        .orElse(new FeeView(m, defaultFee(m), null, null)))
                .toList();
        List<BrandPay> brands = access.brands(me).stream().map(b -> {
            RazorpayGateway.Keys k = keysOrNull(b);
            RazorpayGateway.Methods m = k == null ? null : methods(k.keyId());
            return new BrandPay(b.getId(), b.getCode(), b.getName(), k != null, k == null ? null : k.keyId().startsWith("rzp_live_") ? "LIVE" : "TEST",
                    k != null && !b.isRazorpayReady(), m == null ? null : m.card(), m == null ? null : m.upi(),
                    payee(b), payee(b) != null || b.getUpiQrUpdatedAt() != null);
        }).toList();
        return new PaySetup(fees, brands, access.admin(me), access.admin(me), access.has(me, Menus.PROD_PAY_MANUAL));
    }

    @Transactional
    public FeeView setFee(AuthUser me, FeeBody body) {
        canUse(me);
        access.requireAdmin(me, "Only owners and admins change the processing fee");
        OnlineFee f = feeRepository.findById(body.getMethod()).orElseGet(() -> {
            OnlineFee x = new OnlineFee();
            x.setMethod(body.getMethod());
            return x;
        });
        f.setPercent(body.getPercent().setScale(2, RoundingMode.HALF_UP));
        f.setUpdatedAt(Instant.now());
        f.setUpdatedBy(access.me(me));
        feeRepository.save(f);
        return new FeeView(f.getMethod(), f.getPercent(), f.getUpdatedAt(), null);
    }

    private BigDecimal feePercent(OnlineMethod m) {
        return feeRepository.findById(m).map(OnlineFee::getPercent).orElse(defaultFee(m));
    }

    // ---- choosing the bill ----

    /** Customers of a brand (or all mine) with issued bills still to pay. */
    @Transactional(readOnly = true)
    public List<CustomerDue> customers(AuthUser me, Long brandId) {
        canUse(me);
        Map<Customer, List<SalesDoc>> byCustomer = docRepository.openInvoices(access.scope(me, brandId)).stream()
                .filter(d -> d.getStatus() == SalesDocStatus.ISSUED && due(d).signum() > 0)
                .collect(Collectors.groupingBy(SalesDoc::getCustomer, LinkedHashMap::new, Collectors.toList()));
        return byCustomer.entrySet().stream().map(e -> {
                    Customer c = e.getKey();
                    BigDecimal due = e.getValue().stream().map(OnlinePayService::due).reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new CustomerDue(c.getId(), c.getCode(), c.getName(), c.getPhone(), c.getEmail(), e.getValue().getFirst().getBrand().getId(),
                            e.getValue().getFirst().getBrand().getCode(), e.getValue().size(), due);
                })
                .sorted(Comparator.comparing(CustomerDue::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** A customer's issued bills with money still due, oldest first. */
    @Transactional(readOnly = true)
    public List<OpenBill> bills(AuthUser me, Long customerId) {
        canUse(me);
        Customer c = customerRepository.findFull(customerId).orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));
        access.check(me, c.getBrand());
        return docRepository.forCustomer(customerId).stream()
                .filter(d -> d.getKind() == SalesDocKind.INVOICE && d.getStatus() == SalesDocStatus.ISSUED && due(d).signum() > 0)
                .sorted(Comparator.comparing(SalesDoc::getDocDate).thenComparing(SalesDoc::getId))
                .map(d -> new OpenBill(d.getId(), d.getNumber(), d.getDocDate(), d.getTotal(), d.getPaid(), d.getWrittenOff(), due(d),
                        c.getId(), c.getName(), d.getBrand().getId(), d.getBrand().getCode()))
                .toList();
    }

    private static BigDecimal due(SalesDoc d) {
        return d.getTotal().subtract(d.getCleared()).max(BigDecimal.ZERO);
    }

    // ---- paying ----

    /** Makes the Razorpay order for the amount chosen plus the fee; the browser then opens the payment window with it. */
    @Transactional
    public Checkout start(AuthUser me, StartBody body) {
        canUse(me);
        SalesDoc d = docRepository.findFull(body.getInvoiceId()).orElseThrow(() -> new ResourceNotFoundException("Bill not found: " + body.getInvoiceId()));
        access.check(me, d.getBrand());
        if (d.getKind() != SalesDocKind.INVOICE || d.getStatus() != SalesDocStatus.ISSUED) {
            throw ApiException.conflict("Only an issued invoice can be paid");
        }
        BigDecimal amount = body.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal due = due(d);
        if (amount.compareTo(due) > 0) {
            throw ApiException.badRequest("That is more than is still due (" + PayslipPdf.rupees(due) + ")");
        }
        Brand brand = d.getBrand();
        if (body.getMethod() == OnlineMethod.MANUAL_UPI) {
            return startManual(me, d, amount);
        }
        RazorpayGateway.Keys keys = keys(brand);
        RazorpayGateway.Methods takes = methods(keys.keyId());
        if (takes != null && (body.getMethod() == OnlineMethod.UPI ? !takes.upi() : !takes.card())) {
            throw ApiException.conflict(body.getMethod() == OnlineMethod.UPI
                    ? "UPI is not switched on in this Razorpay account yet (Razorpay Dashboard › Payment methods); pay by card meanwhile"
                    : "Cards are not switched on in this Razorpay account (Razorpay Dashboard › Payment methods)");
        }
        BigDecimal pct = feePercent(body.getMethod());
        BigDecimal fee = amount.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal total = amount.add(fee);
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("bill", d.getNumber());
        notes.put("customer", cut(d.getCustomer().getName(), 200));
        notes.put("brand", brand.getCode());
        notes.put("bill_amount", amount.toPlainString());
        notes.put("processing_fee", fee.toPlainString() + " (" + pct.stripTrailingZeros().toPlainString() + "%)");
        RazorpayGateway.Order order = gateway.createOrder(keys, paise(total), d.getNumber(), notes);

        OnlinePayment o = new OnlinePayment();
        o.setBrand(brand);
        o.setCustomer(d.getCustomer());
        o.setInvoice(d);
        o.setBillNumber(d.getNumber());
        o.setMethod(body.getMethod());
        o.setStatus(OnlinePayStatus.CREATED);
        o.setBillAmount(amount);
        o.setFeePercent(pct);
        o.setFee(fee);
        o.setTotal(total);
        o.setGatewayKeyId(keys.keyId());
        o.setGatewayOrderId(order.id());
        o.setRequestedBy(access.me(me));
        o.setUpdatedAt(Instant.now());
        onlineRepository.save(o);
        Customer c = d.getCustomer();
        return new Checkout(o.getId(), keys.keyId(), order.id(), paise(total), "INR", brand.getName(),
                "Bill " + d.getNumber(), c.getName(), c.getEmail(), c.getPhone(), body.getMethod(), amount, pct, fee, total, null);
    }

    // ---- manual UPI at the office ----

    static final String MANUAL_SOURCE = "upi_office";

    /** The UPI ID a manual payment goes to: the brand's UPI ID, else the one read from its uploaded QR code. */
    private static String payee(Brand b) {
        return b.getUpiId() != null ? b.getUpiId() : b.getUpiQrPayee();
    }

    private void canManual(AuthUser me) {
        access.require(me, "You cannot take manual UPI payments; ask your admin for the Manual UPI (office) access", Menus.PROD_PAY_MANUAL);
    }

    /** No gateway: the request is kept, and the staff show the brand's UPI QR code with the amount filled in. */
    private Checkout startManual(AuthUser me, SalesDoc d, BigDecimal amount) {
        canManual(me);
        Brand brand = d.getBrand();
        String payee = payee(brand);
        if (payee == null && brand.getUpiQrUpdatedAt() == null) {
            throw ApiException.conflict("Add " + brand.getName() + "'s UPI ID or UPI QR code in Brands to take UPI payments at the office");
        }
        BigDecimal pct = feePercent(OnlineMethod.MANUAL_UPI);
        BigDecimal fee = amount.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal total = amount.add(fee);
        OnlinePayment o = new OnlinePayment();
        o.setBrand(brand);
        o.setCustomer(d.getCustomer());
        o.setInvoice(d);
        o.setBillNumber(d.getNumber());
        o.setMethod(OnlineMethod.MANUAL_UPI);
        o.setStatus(OnlinePayStatus.CREATED);
        o.setBillAmount(amount);
        o.setFeePercent(pct);
        o.setFee(fee);
        o.setTotal(total);
        o.setPayeeUpiId(payee);
        o.setRequestedBy(access.me(me));
        o.setUpdatedAt(Instant.now());
        onlineRepository.save(o);
        Customer c = d.getCustomer();
        return new Checkout(o.getId(), null, null, paise(total), "INR", brand.getName(), "Bill " + d.getNumber(), c.getName(), c.getEmail(),
                c.getPhone(), OnlineMethod.MANUAL_UPI, amount, pct, fee, total, payee);
    }

    /** The QR code the customer scans: the brand's UPI ID with the amount and bill filled in, else the brand's uploaded QR. */
    @Transactional(readOnly = true)
    public byte[] manualQr(AuthUser me, Long id) {
        canUse(me);
        canManual(me);
        OnlinePayment o = full(me, id);
        if (o.getMethod() != OnlineMethod.MANUAL_UPI) {
            throw ApiException.badRequest("Only a manual UPI payment has a QR code here");
        }
        if (o.getPayeeUpiId() != null) {
            return UpiQr.png(UpiQr.link(o.getPayeeUpiId(), o.getBrand().getName(), o.getTotal(), o.getBillNumber()), 480);
        }
        return qrRepository.findById(o.getBrand().getId()).map(BrandUpiQr::getContent)
                .orElseThrow(() -> ApiException.conflict("The brand has no UPI QR code; add its UPI ID in Brands"));
    }

    /** The customer paid at the office: the staff record the UPI reference; it goes on the bill and into the history like any payment. */
    @Transactional
    public OnlineView confirmManual(AuthUser me, Long id, ManualBody body) {
        canUse(me);
        canManual(me);
        OnlinePayment o = full(me, id);
        if (o.getMethod() != OnlineMethod.MANUAL_UPI) {
            throw ApiException.badRequest("This is not a manual UPI payment");
        }
        if (o.getStatus() == OnlinePayStatus.PAID) {
            throw ApiException.conflict("This payment is already recorded (UPI reference " + o.getGatewayPaymentId() + ")");
        }
        String ref = body.getReference().strip().toUpperCase(Locale.ROOT);
        Optional<PaymentSource> used = sourceRepository.findByTransactionIdIgnoreCase(ref);
        if (used.isPresent()) {
            throw ApiException.conflict("UPI reference " + ref + " is already recorded, for bill " + used.get().getBillNumber());
        }
        if (body.getPaidOn().isAfter(access.today())) {
            throw ApiException.badRequest("The payment date cannot be in the future");
        }
        o.setStatus(OnlinePayStatus.PAID);
        o.setFailure(null);
        o.setGatewayPaymentId(ref);
        o.setPaidAt(Instant.now());
        o.setUpdatedAt(Instant.now());

        PaymentSource s = new PaymentSource();
        s.setOnlinePayment(o);
        s.setBrand(o.getBrand());
        s.setCustomer(o.getCustomer());
        s.setBillNumber(o.getBillNumber());
        s.setTransactionId(ref);
        s.setMethod(MANUAL_SOURCE);
        s.setAmount(o.getTotal());
        s.setVpa(blank(body.getPayerUpiId()) == null ? null : body.getPayerUpiId().strip().toLowerCase(Locale.ROOT));
        s.setPayerPhone(cut(o.getCustomer().getPhone(), 20));
        s.setPayerEmail(cut(o.getCustomer().getEmail(), 150));
        sourceRepository.save(s);

        String who = access.me(me).getName();
        String remarks = "UPI at the office to " + Objects.toString(o.getPayeeUpiId(), "the brand's UPI QR") + ", recorded by " + who
                + (o.getFee().signum() > 0 ? "; " + PayslipPdf.rupees(o.getFee()) + " fee on top, " + PayslipPdf.rupees(o.getTotal()) + " paid" : "")
                + (blank(body.getRemarks()) == null ? "" : "; " + body.getRemarks().strip());
        onBill(me, o, s, body.getPaidOn(), TradePayMode.UPI, ref, s.getVpa(), remarks);
        return view(o);
    }

    /** Puts a paid request on its bill, or, when the bill was settled meanwhile, keeps it aside with a note. */
    private void onBill(AuthUser me, OnlinePayment o, PaymentSource s, LocalDate paidOn, TradePayMode mode, String txn, String upiId, String remarks) {
        SalesDoc d = docRepository.findFull(o.getInvoice().getId()).orElseThrow();
        if (d.getStatus() != SalesDocStatus.ISSUED || o.getBillAmount().compareTo(due(d)) > 0) {
            // paid another way meanwhile: the money is in, so it is kept here for a refund instead of going on the bill
            o.setNote("Paid after the bill was settled or cancelled (due now " + PayslipPdf.rupees(due(d)) + "); refund it or record it yourself");
            sales.note(me, d, "ONLINE PAID", "Payment " + txn + " of " + PayslipPdf.rupees(o.getTotal()) + " could not go on the bill: "
                    + "only " + PayslipPdf.rupees(due(d)) + " was due");
            return;
        }
        PaymentBody pay = new PaymentBody();
        pay.setPaidOn(paidOn);
        pay.setAmount(o.getBillAmount());
        pay.setKind(TradePaymentKind.PAYMENT);
        pay.setMode(mode);
        pay.setTransactionId(cut(txn, 80));
        pay.setUpiId(cut(upiId, 80));
        pay.setBankDetails(cut(label(s), 200));
        pay.setRemarks(cut(remarks, 500));
        o.setTradePayment(sales.receive(me, d, pay));
    }

    /** The payment window says it was paid: checked with Razorpay, then recorded on the bill. */
    @Transactional
    public OnlineView verify(AuthUser me, Long id, VerifyBody body) {
        canUse(me);
        OnlinePayment o = full(me, id);
        if (o.getStatus() == OnlinePayStatus.PAID) {
            if (body.getPaymentId().equals(o.getGatewayPaymentId())) {
                return view(o);
            }
            throw ApiException.conflict("This payment was already paid with " + o.getGatewayPaymentId());
        }
        if (!o.getGatewayOrderId().equals(body.getOrderId())) {
            throw ApiException.badRequest("This payment is for another order");
        }
        if (!signatureOk(o.getGatewayOrderId() + "|" + body.getPaymentId(), body.getSignature(), keys(o).keySecret())) {
            log.warn("Online payment {}: the signature for {} did not match", o.getId(), body.getPaymentId());
            throw ApiException.badRequest("The payment could not be verified with Razorpay; use Check with Razorpay in the history");
        }
        o.setGatewaySignature(body.getSignature());
        settle(me, o, gateway.payment(keys(o), body.getPaymentId()));
        return view(o);
    }

    /** The window was closed, or the card or bank refused it. */
    @Transactional
    public OnlineView fail(AuthUser me, Long id, FailBody body) {
        canUse(me);
        OnlinePayment o = full(me, id);
        if (o.getStatus() == OnlinePayStatus.CREATED || (o.getStatus() == OnlinePayStatus.CANCELLED && !body.isCancelled())) {
            o.setStatus(body.isCancelled() ? OnlinePayStatus.CANCELLED : OnlinePayStatus.FAILED);
            o.setFailure(cut(blank(body.getReason()) == null ? (body.isCancelled() ? "The payment window was closed" : "The payment failed")
                    : body.getReason().strip(), 500));
            if (blank(body.getPaymentId()) != null) {
                o.setGatewayPaymentId(body.getPaymentId().strip());
            }
            o.setUpdatedAt(Instant.now());
            if (!body.isCancelled()) {
                sales.note(me, o.getInvoice(), "ONLINE FAILED", "Online payment of " + PayslipPdf.rupees(o.getTotal()) + " by "
                        + word(o.getMethod()) + " failed: " + o.getFailure());
            }
        }
        return view(o);
    }

    /** Asks Razorpay what happened to an order not marked paid (e.g. the browser was closed after paying), and records it if paid. */
    @Transactional
    public OnlineView refresh(AuthUser me, Long id) {
        canUse(me);
        OnlinePayment o = full(me, id);
        if (o.getStatus() == OnlinePayStatus.PAID || o.getMethod() == OnlineMethod.MANUAL_UPI) {
            return view(o);
        }
        RazorpayGateway.Keys keys = keys(o);
        List<RazorpayGateway.Payment> tries = gateway.orderPayments(keys, o.getGatewayOrderId());
        Optional<RazorpayGateway.Payment> paid = tries.stream()
                .filter(p -> "captured".equals(p.status()) || "authorized".equals(p.status())).findFirst();
        if (paid.isPresent()) {
            settle(me, o, gateway.payment(keys, paid.get().id()));
        } else if (!tries.isEmpty() && o.getStatus() == OnlinePayStatus.CREATED) {
            RazorpayGateway.Payment last = tries.getLast();
            if ("failed".equals(last.status())) {
                o.setStatus(OnlinePayStatus.FAILED);
                o.setGatewayPaymentId(last.id());
                o.setFailure(cut(last.errorDescription() == null ? "The payment failed" : last.errorDescription(), 500));
                o.setUpdatedAt(Instant.now());
            }
        }
        return view(o);
    }

    /** Takes the money if only authorized, checks the amount, then records it on the bill and keeps where it came from. */
    private void settle(AuthUser me, OnlinePayment o, RazorpayGateway.Payment p) {
        RazorpayGateway.Keys keys = keys(o);
        if (!o.getGatewayOrderId().equals(p.orderId())) {
            throw ApiException.badRequest("This payment is for another order");
        }
        if (p.amount() != paise(o.getTotal())) {
            log.warn("Online payment {}: Razorpay payment {} is for {} paise, expected {}", o.getId(), p.id(), p.amount(), paise(o.getTotal()));
            throw ApiException.conflict("Razorpay shows a different amount for this payment; it was not recorded");
        }
        if ("authorized".equals(p.status())) {
            gateway.capture(keys, p.id(), p.amount(), p.currency() == null ? "INR" : p.currency());
            p = gateway.payment(keys, p.id());
        }
        o.setGatewayPaymentId(p.id());
        o.setUpdatedAt(Instant.now());
        if ("failed".equals(p.status())) {
            o.setStatus(OnlinePayStatus.FAILED);
            o.setFailure(cut(p.errorDescription() == null ? "The payment failed" : p.errorDescription(), 500));
            return;
        }
        if (!"captured".equals(p.status())) {
            throw ApiException.conflict("Razorpay has not confirmed this payment yet (" + p.status() + "); check again in a minute");
        }
        o.setStatus(OnlinePayStatus.PAID);
        o.setFailure(null);
        o.setPaidAt(Instant.now());

        PaymentSource s = sourceRepository.findByOnlinePaymentId(o.getId()).orElseGet(PaymentSource::new);
        s.setOnlinePayment(o);
        s.setBrand(o.getBrand());
        s.setCustomer(o.getCustomer());
        s.setBillNumber(o.getBillNumber());
        s.setTransactionId(p.id());
        s.setMethod(p.method() == null ? o.getMethod().name().toLowerCase(Locale.ROOT) : cut(p.method(), 20));
        s.setAmount(BigDecimal.valueOf(p.amount(), 2));
        s.setCardNetwork(cut(p.cardNetwork(), 30));
        s.setCardLast4(cut(p.cardLast4(), 4));
        s.setCardIssuer(cut(p.cardIssuer(), 40));
        s.setCardType(cut(p.cardType(), 20));
        s.setCardInternational(p.cardInternational());
        s.setVpa(cut(p.vpa(), 100));
        s.setBank(cut(p.bank() != null ? p.bank() : p.wallet(), 40));
        s.setPayerEmail(cut(p.email(), 150));
        s.setPayerPhone(cut(p.contact(), 20));
        sourceRepository.save(s);

        TradePayMode mode = "upi".equals(p.method()) ? TradePayMode.UPI : "card".equals(p.method()) ? TradePayMode.CARD
                : p.method() == null ? (o.getMethod() == OnlineMethod.UPI ? TradePayMode.UPI : TradePayMode.CARD) : TradePayMode.BANK;
        onBill(me, o, s, access.today(), mode, p.id(), p.vpa(), "Paid online (Razorpay " + o.getGatewayOrderId() + "); " + PayslipPdf.rupees(o.getFee())
                + " processing fee (" + o.getFeePercent().stripTrailingZeros().toPlainString() + "%) on top, " + PayslipPdf.rupees(o.getTotal()) + " charged");
    }

    // ---- refunds ----

    /** Owners and admins: gives money back through Razorpay to the card or UPI ID it came from, and records the refund on the bill. */
    @Transactional
    public OnlineView refund(AuthUser me, Long id, RefundBody body) {
        canUse(me);
        access.requireAdmin(me, "Only owners and admins give refunds");
        OnlinePayment o = full(me, id);
        if (o.getStatus() != OnlinePayStatus.PAID) {
            throw ApiException.conflict("Only a paid online payment can be refunded");
        }
        if (o.getMethod() == OnlineMethod.MANUAL_UPI) {
            throw ApiException.conflict("Paid by UPI at the office: give the money back by UPI or bank, then record the refund on the invoice");
        }
        BigDecimal amount = body.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal left = o.getBillAmount().subtract(o.getRefunded());
        if (amount.compareTo(left) > 0) {
            throw ApiException.badRequest("At most " + PayslipPdf.rupees(left) + " can still be refunded (the processing fee is not refunded)");
        }
        SalesDoc d = docRepository.findFull(o.getInvoice().getId()).orElseThrow();
        boolean onBill = o.getTradePayment() != null;
        if (onBill && (d.getStatus() == SalesDocStatus.CANCELLED || amount.compareTo(d.getPaid()) > 0)) {
            throw ApiException.conflict("The bill shows only " + PayslipPdf.rupees(d.getPaid()) + " paid; check its payments first");
        }
        PaymentSource s = sourceRepository.findByOnlinePaymentId(o.getId()).orElse(null);
        RazorpayGateway.Refund r = gateway.refund(keys(o), o.getGatewayPaymentId(), paise(amount),
                Map.of("bill", o.getBillNumber(), "reason", cut(body.getReason().strip(), 250)));
        o.setRefunded(o.getRefunded().add(amount));
        o.setUpdatedAt(Instant.now());
        String to = s == null ? "the payer" : label(s);
        if (onBill) {
            PaymentBody pay = new PaymentBody();
            pay.setPaidOn(access.today());
            pay.setAmount(amount);
            pay.setKind(TradePaymentKind.REFUND);
            pay.setMode(o.getTradePayment().getMode());
            pay.setTransactionId(cut(r.id(), 80));
            pay.setUpiId(s == null ? null : cut(s.getVpa(), 80));
            pay.setBankDetails(cut(to, 200));
            pay.setRemarks(cut("Refunded online (Razorpay, payment " + o.getGatewayPaymentId() + "): " + body.getReason().strip(), 500));
            sales.receive(me, d, pay);
        } else {
            sales.note(me, d, "ONLINE REFUND", "Refunded " + PayslipPdf.rupees(amount) + " of online payment " + o.getGatewayPaymentId()
                    + " to " + to + " (" + r.id() + "): " + body.getReason().strip());
        }
        return view(o);
    }

    // ---- history ----

    @Transactional(readOnly = true)
    public List<OnlineView> history(AuthUser me, Long brandId, LocalDate from, LocalDate to) {
        canUse(me);
        range(from, to);
        List<OnlinePayment> list = onlineRepository.inRange(access.scope(me, brandId), start(from), start(to.plusDays(1)));
        Map<Long, PaymentSource> sources = list.isEmpty() ? Map.of() : sourceRepository.findByOnlinePaymentIdIn(list.stream().map(OnlinePayment::getId).toList())
                .stream().collect(Collectors.toMap(s -> s.getOnlinePayment().getId(), Function.identity()));
        return list.stream().map(o -> view(o, sources.get(o.getId()))).toList();
    }

    /** Where the money came from (card or UPI ID), with the transaction ID, customer and bill, for refunds. */
    @Transactional(readOnly = true)
    public List<SourceView> sources(AuthUser me, Long brandId, LocalDate from, LocalDate to) {
        canUse(me);
        range(from, to);
        return sourceRepository.inRange(access.scope(me, brandId), start(from), start(to.plusDays(1))).stream().map(OnlinePayService::view).toList();
    }

    private void range(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 400) {
            throw ApiException.badRequest("Ask for at most a year at a time");
        }
    }

    private Instant start(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }

    // ---- helpers ----

    private OnlinePayment full(AuthUser me, Long id) {
        OnlinePayment o = onlineRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Online payment not found: " + id));
        access.check(me, o.getBrand());
        return o;
    }

    private record Known(RazorpayGateway.Methods methods, Instant at) {
    }

    /** What each Razorpay account takes, asked again after app.razorpay.methods-cache (so switching UPI on shows up soon). */
    private final Map<String, Known> methodCache = new java.util.concurrent.ConcurrentHashMap<>();

    private RazorpayGateway.Methods methods(String keyId) {
        Known k = methodCache.get(keyId);
        if (k != null && k.at().isAfter(Instant.now().minus(methodsFor))) {
            return k.methods();
        }
        RazorpayGateway.Methods m = gateway.methods(keyId);
        if (m != null) {
            methodCache.put(keyId, new Known(m, Instant.now()));
        }
        return m;
    }

    /** The brand's own Razorpay keys, else the company's default ones, else null. */
    private RazorpayGateway.Keys keysOrNull(Brand b) {
        return b.isRazorpayReady() ? new RazorpayGateway.Keys(b.getRazorpayKeyId(), b.getRazorpayKeySecret()) : defaultKeys;
    }

    private RazorpayGateway.Keys keys(Brand b) {
        RazorpayGateway.Keys k = keysOrNull(b);
        if (k == null) {
            throw ApiException.conflict("Online payments are not set up for " + b.getName() + ": add its Razorpay keys in Brands");
        }
        return k;
    }

    /** The keys an order was made with (the brand's keys may have changed since): its money and signature belong to that account. */
    private RazorpayGateway.Keys keys(OnlinePayment o) {
        Brand b = o.getBrand();
        if (b.isRazorpayReady() && b.getRazorpayKeyId().equals(o.getGatewayKeyId())) {
            return new RazorpayGateway.Keys(b.getRazorpayKeyId(), b.getRazorpayKeySecret());
        }
        if (defaultKeys != null && defaultKeys.keyId().equals(o.getGatewayKeyId())) {
            return defaultKeys;
        }
        throw ApiException.conflict("The Razorpay keys this payment was made with (" + o.getGatewayKeyId() + ") are no longer set up");
    }

    static long paise(BigDecimal rupees) {
        return rupees.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    /** Razorpay's checkout signature: HMAC-SHA256 of "order_id|payment_id" with the key secret, in hex. */
    static boolean signatureOk(String payload, String signature, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "Visa credit card •••• 1111 · HDFC" or "UPI anu@okhdfcbank". */
    static String label(PaymentSource s) {
        if (MANUAL_SOURCE.equals(s.getMethod())) {
            return "UPI at the office" + (s.getVpa() == null ? "" : " from " + s.getVpa());
        }
        if (s.getCardLast4() != null) {
            return String.join(" ", Objects.toString(s.getCardNetwork(), "Card"), s.getCardType() == null ? "" : s.getCardType(), "card •••• " + s.getCardLast4())
                    .replaceAll(" +", " ") + (s.getCardIssuer() == null ? "" : " · " + s.getCardIssuer())
                    + (Boolean.TRUE.equals(s.getCardInternational()) ? " · international" : "");
        }
        if (s.getVpa() != null) {
            return "UPI " + s.getVpa();
        }
        return s.getBank() != null ? s.getMethod() + " · " + s.getBank() : s.getMethod();
    }

    private static String word(OnlineMethod m) {
        return m == OnlineMethod.UPI ? "UPI" : "card";
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private OnlineView view(OnlinePayment o) {
        return view(o, sourceRepository.findByOnlinePaymentId(o.getId()).orElse(null));
    }

    private static OnlineView view(OnlinePayment o, PaymentSource s) {
        return new OnlineView(o.getId(), o.getBrand().getId(), o.getBrand().getCode(), o.getCustomer().getId(), o.getCustomer().getName(),
                o.getInvoice().getId(), o.getBillNumber(), o.getMethod(), o.getStatus(), o.getBillAmount(), o.getFeePercent(), o.getFee(),
                o.getTotal(), o.getCurrency(), o.getGatewayOrderId(), o.getGatewayPaymentId(), o.getFailure(), o.getNote(), o.getRefunded(),
                o.getTradePayment() == null ? null : o.getTradePayment().getId(), o.getRequestedBy() == null ? null : o.getRequestedBy().getName(),
                o.getCreatedAt(), o.getPaidAt(), s == null ? null : view(s), o.getPayeeUpiId());
    }

    private static SourceView view(PaymentSource s) {
        return new SourceView(s.getId(), s.getOnlinePayment().getId(), s.getTransactionId(), s.getMethod(), s.getAmount(), s.getCardNetwork(),
                s.getCardLast4(), s.getCardIssuer(), s.getCardType(), s.getCardInternational(), s.getVpa(), s.getBank(), s.getPayerEmail(),
                s.getPayerPhone(), s.getCustomer().getId(), s.getCustomer().getName(), s.getBillNumber(), s.getBrand().getCode(), s.getCreatedAt(), label(s));
    }
}
