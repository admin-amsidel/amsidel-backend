package com.amsidel.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A brand (business line) of the Amsidel group, e.g. "DW" = Amsidel Doors & Windows, with its business details.
 * Payslips of its people show its name, address, GST number and logo. The logo itself is in brand_logos.
 */
@Entity
@Table(name = "brands")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Brand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Short code, also the prefix of generated employee numbers (DW0001). */
    @Column(nullable = false, unique = true, length = 10)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 500)
    private String address;

    private LocalDate launchedDate;

    /** The owners' names, comma separated, e.g. "Sibin James, Anu Thomas, Ravi Kumar". */
    @Column(length = 500)
    private String owners;

    /** GSTIN, 15 characters, e.g. 32ABCDE1234F1Z5. */
    @Column(length = 15)
    private String gstNumber;

    /** What the brand does: Food, Home Appliances, Building Materials, Electronics, IT Solutions, Medical... */
    @Column(length = 60)
    private String category;

    /** Contact phone and email, on invoices and payslips. */
    @Column(length = 20)
    private String contactNumber;

    @Column(length = 150)
    private String email;

    /** The brand's bank account for invoices: account number, IFSC and UPI ID. */
    @Column(length = 30)
    private String bankAccountNumber;

    @Column(length = 11)
    private String ifscCode;

    @Column(length = 80)
    private String upiId;

    /** The brand's Razorpay account for bills paid online (cards and UPI); the money settles to its linked bank. */
    @Column(length = 40)
    private String razorpayKeyId;

    /** Never sent out: only saved and used to call Razorpay. */
    @JsonIgnore
    @Column(length = 100)
    private String razorpayKeySecret;

    /** When the logo was last uploaded; null = no logo. */
    private Instant logoUpdatedAt;

    /** When the UPI payment QR (printed on invoices) was uploaded; null = none (one is made from upiId). */
    private Instant upiQrUpdatedAt;

    /** The UPI ID the uploaded QR pays to, read from it. */
    @Column(length = 100)
    private String upiQrPayee;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    /** Bills of this brand can be paid online. */
    @JsonProperty("razorpayReady")
    public boolean isRazorpayReady() {
        return razorpayKeyId != null && razorpayKeySecret != null;
    }
}
