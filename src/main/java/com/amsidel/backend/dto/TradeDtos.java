package com.amsidel.backend.dto;

import com.amsidel.backend.entity.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The product portal: product types, items and stock, suppliers, customers, quotations, invoices, purchase bills
 * and payments. Money in rupees with 2 decimals; quantities with 2 decimals (pieces, sq ft…). Every record belongs
 * to a brand.
 */
public final class TradeDtos {

    private TradeDtos() {
    }

    private static final String PHONE = "^\\+?[0-9 ()-]{7,20}$";
    private static final String PHONE_OR_EMPTY = "^$|^\\+?[0-9 ()-]{7,20}$";
    private static final String GSTIN = "^$|^[0-9]{2}[A-Za-z]{5}[0-9]{4}[A-Za-z][0-9A-Za-z][Zz][0-9A-Za-z]$";
    private static final String PAN = "^$|^[A-Za-z]{5}[0-9]{4}[A-Za-z]$";
    private static final String CODE = "^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$";

    // ---- shared ----

    public record BrandRef(Long id, String code, String name) {
        public static BrandRef of(Brand b) {
            return new BrandRef(b.getId(), b.getCode(), b.getName());
        }
    }

    /** What is owed: billed (or bought), paid, still due. */
    public record Balance(BigDecimal billed, BigDecimal paid, BigDecimal due) {
    }

    // ---- product types ----

    @Data
    public static class TypeBody {
        private Long brandId;

        @NotBlank
        @Pattern(regexp = CODE, message = "Code: letters, numbers, - or _ (up to 30), e.g. UPVC")
        @Size(max = 20, message = "Code: at most 20 characters")
        private String code;

        @NotBlank
        @Size(max = 100)
        private String name;

        @Size(max = 300)
        private String description;

        private int sortOrder;

        private Boolean active;
    }

    public record TypeView(Long id, BrandRef brand, String code, String name, String description, int sortOrder,
                           boolean active, long itemCount) {
    }

    // ---- suppliers ----

    @Data
    public static class SupplierBody {
        private Long brandId;

        /** Empty = the next free one, e.g. DWS-001. */
        @Pattern(regexp = "^$|" + CODE, message = "Code: letters, numbers, - or _ (up to 20)")
        @Size(max = 20)
        private String code;

        @NotBlank
        @Size(max = 150)
        private String name;

        @Size(max = 100)
        private String contactPerson;

        @NotBlank(message = "Phone number is needed")
        @Pattern(regexp = PHONE, message = "Enter a valid phone number (7-20 digits, may start with +)")
        private String phone;

        @Pattern(regexp = PHONE_OR_EMPTY, message = "Enter a valid second phone number")
        private String phone2;

        @Email(message = "Enter a valid email address")
        @Size(max = 150)
        private String email;

        @Size(max = 200)
        private String website;

        @Size(max = 100)
        private String location;

        @Size(max = 500)
        private String address;

        @Pattern(regexp = GSTIN, message = "GSTIN is 15 characters, e.g. 32ABCDE1234F1Z5")
        private String gstNumber;

        @Pattern(regexp = PAN, message = "PAN is 5 letters, 4 digits and a letter, e.g. ABCDE1234F")
        private String panNumber;

        @Pattern(regexp = "^$|^[0-9]{6,20}$", message = "Bank account number: 6 to 20 digits")
        private String bankAccount;

        @Pattern(regexp = "^$|^[A-Za-z]{4}0[A-Za-z0-9]{6}$", message = "IFSC is 11 characters, e.g. HDFC0001234")
        private String ifsc;

        @Pattern(regexp = "^$|^[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}$", message = "UPI ID looks like name@bank")
        private String upi;

        @Size(max = 500)
        private String notes;

        private Boolean active;
    }

    public record SupplierView(Long id, BrandRef brand, String code, String name, String contactPerson, String phone,
                               String phone2, String email, String website, String location, String address,
                               String gstNumber, String panNumber, String bankAccount, String ifsc, String upi, String notes,
                               boolean active, Balance balance, int bills, int items) {
    }

    // ---- customers ----

    @Data
    public static class CustomerBody {
        private Long brandId;

        @NotBlank
        @Size(max = 150)
        private String name;

        @NotBlank(message = "Phone number is needed")
        @Pattern(regexp = PHONE, message = "Enter a valid phone number (7-20 digits, may start with +)")
        private String phone;

        @Pattern(regexp = PHONE_OR_EMPTY, message = "Enter a valid second phone number")
        private String phone2;

        @Email(message = "Enter a valid email address")
        @Size(max = 150)
        private String email;

        @Size(max = 100)
        private String location;

        @Size(max = 500)
        private String address;

        @Pattern(regexp = GSTIN, message = "GSTIN is 15 characters, e.g. 32ABCDE1234F1Z5")
        private String gstNumber;

        @Pattern(regexp = PAN, message = "PAN is 5 letters, 4 digits and a letter, e.g. ABCDE1234F")
        private String panNumber;

        @Size(max = 500)
        private String notes;

        private Boolean active;
    }

    public record CustomerView(Long id, BrandRef brand, String code, String name, String phone, String phone2,
                               String email, String location, String address, String gstNumber, String panNumber,
                               String notes, boolean active, Balance balance, int quotations, int invoices,
                               LocalDate lastDocDate) {
    }

    // ---- items and stock ----

    @Data
    public static class ItemBody {
        private Long brandId;

        @NotNull(message = "Choose the product type")
        private Long typeId;

        private Long supplierId;

        @NotBlank
        @Pattern(regexp = CODE, message = "Item code: letters, numbers, - or _ (up to 30)")
        private String code;

        @NotBlank
        @Size(max = 150)
        private String name;

        @Size(max = 50)
        private String supplierItemCode;

        @Size(max = 1000)
        private String details;

        /** Comma separated: 6x4, 8x7. */
        @Size(max = 300)
        private String sizes;

        /** What one is counted in: piece, set, sq ft… (not a number). */
        @Pattern(regexp = "^$|^[A-Za-z][A-Za-z .]{0,19}$", message = "Unit is what one is counted in, e.g. piece, set or sq ft")
        private String unit;

        @Pattern(regexp = "^$|^[0-9]{4,8}$", message = "HSN / SAC code: 4 to 8 digits")
        private String hsnCode;

        @NotNull
        @DecimalMin("0")
        @Digits(integer = 10, fraction = 2, message = "Use at most 10 digits and 2 decimals")
        private BigDecimal supplierPrice;

        @NotNull
        @DecimalMin("0")
        @Digits(integer = 10, fraction = 2, message = "Use at most 10 digits and 2 decimals")
        private BigDecimal mrp;

        @NotNull
        @DecimalMin("0")
        @Digits(integer = 10, fraction = 2, message = "Use at most 10 digits and 2 decimals")
        private BigDecimal showroomPrice;

        @NotNull
        @DecimalMin("0")
        @Digits(integer = 10, fraction = 2, message = "Use at most 10 digits and 2 decimals")
        private BigDecimal minSellPrice;

        private Boolean trackStock;

        @DecimalMin("0")
        @Digits(integer = 10, fraction = 2, message = "Use at most 10 digits and 2 decimals")
        private BigDecimal reorderLevel;

        private Boolean active;

        /** New items only: stock already in hand. */
        @DecimalMin("0")
        @Digits(integer = 10, fraction = 2, message = "Use at most 10 digits and 2 decimals")
        private BigDecimal openingStock;
    }

    public record ItemView(Long id, BrandRef brand, Long typeId, String typeCode, String typeName, Long supplierId,
                           String supplierName, String code, String name, String supplierItemCode, String details,
                           String sizes, String unit, String hsnCode, BigDecimal supplierPrice, BigDecimal mrp,
                           BigDecimal showroomPrice, BigDecimal minSellPrice, boolean trackStock, BigDecimal reorderLevel,
                           BigDecimal totalIn, BigDecimal totalOut, BigDecimal onHand, BigDecimal stockValue,
                           boolean lowStock, boolean active) {
    }

    /** Opening stock, or stock added or removed by hand (counted, damaged, returned…). */
    @Data
    public static class StockBody {
        @NotNull
        private StockMoveKind kind;

        @NotNull
        @DecimalMin(value = "0.01", message = "Quantity must be more than 0")
        @Digits(integer = 10, fraction = 2, message = "Use at most 10 digits and 2 decimals")
        private BigDecimal quantity;

        @NotNull
        private LocalDate movedOn;

        @NotBlank(message = "Say why the stock changes")
        @Size(max = 300)
        private String note;
    }

    public record MoveView(Long id, LocalDate movedOn, StockMoveKind kind, BigDecimal quantity, BigDecimal balanceAfter,
                           BigDecimal unitCost, Long docId, String docNumber, String docType, String note,
                           String recordedByName, Instant createdAt) {
    }

    public record ItemDetail(ItemView item, List<MoveView> moves) {
    }

    // ---- quotations and invoices ----

    @Data
    public static class SalesLineBody {
        /** Empty for a free line (installation, transport…). */
        private Long itemId;

        @NotBlank(message = "Each line needs a name")
        @Size(max = 200)
        private String name;

        @Size(max = 50)
        private String size;

        @Size(max = 500)
        private String description;

        @Size(max = 20)
        private String unit;

        @Pattern(regexp = "^$|^[0-9]{4,8}$", message = "HSN / SAC code: 4 to 8 digits")
        private String hsnCode;

        @NotNull
        @DecimalMin(value = "0.01", message = "Quantity must be more than 0")
        @Digits(integer = 10, fraction = 2, message = "Use at most 10 digits and 2 decimals")
        private BigDecimal quantity;

        @NotNull
        @DecimalMin("0")
        @Digits(integer = 10, fraction = 2, message = "Use at most 10 digits and 2 decimals")
        private BigDecimal rate;
    }

    @Data
    public static class SalesBody {
        @NotNull
        private SalesDocKind kind;

        private Long brandId;

        @NotNull(message = "Choose the customer")
        private Long customerId;

        @NotNull
        private LocalDate docDate;

        private LocalDate validUntil;

        @NotNull
        @Size(min = 1, max = 200, message = "Add at least one line")
        private List<@Valid SalesLineBody> lines;

        @DecimalMin("0")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal discount;

        @Size(max = 100)
        private String miscLabel;

        @DecimalMin("0")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal miscAmount;

        @NotNull
        @DecimalMin("0")
        @DecimalMax(value = "40", message = "GST %: 0 to 40")
        private BigDecimal gstRate;

        private boolean interState;

        @Size(max = 1000)
        private String remarks;

        @Size(max = 2000)
        private String terms;
    }

    /** Money of a quotation, invoice or purchase bill. cgst/sgst/igst split the GST for the document. */
    public record Totals(BigDecimal subtotal, BigDecimal discount, String miscLabel, BigDecimal miscAmount,
                         BigDecimal taxable, BigDecimal gstRate, boolean interState, BigDecimal gstAmount,
                         BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal roundOff, BigDecimal total,
                         BigDecimal paid, BigDecimal due) {
    }

    public record SalesLineView(Long id, Long itemId, String itemCode, String name, String typeName, String hsnCode,
                                String size, String description, String unit, BigDecimal quantity, BigDecimal rate,
                                BigDecimal amount, BigDecimal unitCost, boolean belowMinPrice) {
    }

    public record PaymentView(Long id, TradeParty party, TradePaymentKind kind, LocalDate paidOn, BigDecimal amount,
                              TradePayMode mode, String transactionId, String bankDetails, String upiId, String remarks,
                              String recordedByName, Instant createdAt, boolean voided, Instant voidedAt,
                              String voidedByName, String voidReason, Long docId, String docNumber, String partyName,
                              String brandCode) {
    }

    /** The brand as printed on documents. */
    public record Company(Long brandId, String code, String name, String address, String gstNumber, String phone,
                          String email, String bankAccount, String ifsc, String upi, Instant logoUpdatedAt,
                          Instant upiQrUpdatedAt) {
        public static Company of(Brand b) {
            return new Company(b.getId(), b.getCode(), b.getName(), b.getAddress(), b.getGstNumber(), b.getContactNumber(),
                    b.getEmail(), b.getBankAccountNumber(), b.getIfscCode(), b.getUpiId(), b.getLogoUpdatedAt(),
                    b.getUpiQrUpdatedAt());
        }
    }

    public record PartyRef(Long id, String code, String name, String phone, String email, String address,
                           String gstNumber) {
    }

    /** UNPAID, PARTLY_PAID, PAID; null for quotations and drafts with nothing paid. */
    public record SalesView(Long id, SalesDocKind kind, String number, SalesDocStatus status, String payStatus,
                            Company company, PartyRef customer, LocalDate docDate, LocalDate validUntil,
                            Long quotationId, String quotationNumber, Long invoiceId, String invoiceNumber,
                            List<SalesLineView> lines, Totals totals, BigDecimal costTotal, BigDecimal margin,
                            String remarks, String terms, List<PaymentView> payments, String createdByName,
                            Instant createdAt, Instant issuedAt, String issuedByName, Instant cancelledAt,
                            String cancelReason, boolean editable) {
    }

    public record SalesRow(Long id, SalesDocKind kind, String number, SalesDocStatus status, String payStatus,
                           String brandCode, Long customerId, String customerName, String customerPhone,
                           LocalDate docDate, LocalDate validUntil, BigDecimal total, BigDecimal paid, BigDecimal due) {
    }

    @Data
    public static class StatusBody {
        @NotNull
        private SalesDocStatus status;
    }

    @Data
    public static class ReasonBody {
        @NotBlank(message = "Give a reason")
        @Size(max = 300)
        private String reason;
    }

    @Data
    public static class PaymentBody {
        @NotNull
        private LocalDate paidOn;

        @NotNull
        @DecimalMin(value = "0.01", message = "Amount must be more than 0")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal amount;

        private TradePaymentKind kind;

        @NotNull
        private TradePayMode mode;

        @Size(max = 80)
        private String transactionId;

        @Size(max = 200)
        private String bankDetails;

        @Size(max = 80)
        private String upiId;

        @Size(max = 500)
        private String remarks;
    }

    // ---- purchase bills ----

    @Data
    public static class PurchaseLineBody {
        @NotNull(message = "Choose the item on each line")
        private Long itemId;

        @NotNull
        @DecimalMin(value = "0.01", message = "Quantity must be more than 0")
        @Digits(integer = 10, fraction = 2, message = "Use at most 10 digits and 2 decimals")
        private BigDecimal quantity;

        @NotNull
        @DecimalMin("0")
        @Digits(integer = 10, fraction = 2, message = "Use at most 10 digits and 2 decimals")
        private BigDecimal rate;
    }

    @Data
    public static class PurchaseBody {
        private Long brandId;

        @NotNull(message = "Choose the supplier")
        private Long supplierId;

        @Size(max = 50)
        private String supplierBillNo;

        @NotNull
        private LocalDate billDate;

        @NotNull
        @Size(min = 1, max = 200, message = "Add at least one item")
        private List<@Valid PurchaseLineBody> lines;

        @DecimalMin("0")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal discount;

        @Size(max = 100)
        private String miscLabel;

        @DecimalMin("0")
        @Digits(integer = 12, fraction = 2, message = "Use at most 12 digits and 2 decimals")
        private BigDecimal miscAmount;

        @NotNull
        @DecimalMin("0")
        @DecimalMax(value = "40", message = "GST %: 0 to 40")
        private BigDecimal gstRate;

        private boolean interState;

        @Size(max = 1000)
        private String remarks;
    }

    public record PurchaseLineView(Long id, Long itemId, String itemCode, String name, String unit, BigDecimal quantity,
                                   BigDecimal rate, BigDecimal amount, BigDecimal itemSupplierPrice) {
    }

    public record PurchaseView(Long id, String number, PurchaseStatus status, String payStatus, Company company,
                               PartyRef supplier, String supplierBillNo, LocalDate billDate, List<PurchaseLineView> lines,
                               Totals totals, String remarks, List<PaymentView> payments, String createdByName,
                               Instant createdAt, Instant receivedAt, String receivedByName, Instant cancelledAt,
                               String cancelReason, boolean editable) {
    }

    public record PurchaseRow(Long id, String number, PurchaseStatus status, String payStatus, String brandCode,
                              Long supplierId, String supplierName, String supplierBillNo, LocalDate billDate,
                              BigDecimal total, BigDecimal paid, BigDecimal due, int lines) {
    }

    /** Receiving a bill: also make each line's price the item's supplier price (default yes). */
    @Data
    public static class ReceiveBody {
        private Boolean updatePrices;
    }
}
