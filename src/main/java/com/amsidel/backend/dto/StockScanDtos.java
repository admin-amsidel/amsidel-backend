/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.StockReceiptStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Item QR labels, and stock received by scanning them. */
public final class StockScanDtos {

    private StockScanDtos() {
    }

    /** Labels to print: each item, how many copies; showPrice adds the showroom price under the name. */
    @Data
    public static class LabelsBody {
        @NotEmpty(message = "Choose the items")
        @Size(max = 200)
        private List<@Valid LabelItem> items = new ArrayList<>();
        private boolean showPrice = true;
    }

    @Data
    public static class LabelItem {
        @NotNull
        private Long itemId;
        @NotNull @Min(value = 1, message = "At least one copy") @Max(value = 500, message = "At most 500 copies of one item")
        private Integer copies;
    }

    /** What was scanned: one line per label, scanned again = a higher quantity. */
    @Data
    public static class ReceiptBody {
        private Long brandId;
        @Size(max = 500)
        private String note;
        @NotEmpty(message = "Scan at least one item")
        @Size(max = 300)
        private List<@Valid ReceiptLineBody> lines = new ArrayList<>();
    }

    @Data
    public static class ReceiptLineBody {
        /** The label's text, as read. */
        @NotBlank @Size(max = 4000)
        private String payload;
        @NotNull @DecimalMin(value = "0.01", message = "Quantity must be more than 0")
        @Digits(integer = 10, fraction = 2)
        private BigDecimal quantity;
    }

    /** The approver may correct a line's quantity (0 leaves it out). */
    @Data
    public static class ApproveBody {
        @Size(max = 300)
        private List<@Valid ApproveLine> lines = new ArrayList<>();
        @Size(max = 500)
        private String note;
    }

    @Data
    public static class ApproveLine {
        @NotNull
        private Long id;
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2)
        private BigDecimal quantity;
    }

    @Data
    public static class RejectBody {
        @NotBlank(message = "Say why it is turned down") @Size(max = 500)
        private String note;
    }

    /** One item of a receipt; exists: the brand has this code (stock is added), else a new item is made on approval. */
    public record ReceiptLineView(Long id, String code, String name, String typeName, String unit, BigDecimal quantity,
                                  BigDecimal approvedQuantity, Long itemId, boolean exists, BigDecimal onHand,
                                  BigDecimal showroomPrice, boolean createdItem) {
    }

    public record ReceiptView(Long id, String number, Long brandId, String brandCode, StockReceiptStatus status, String note,
                              List<ReceiptLineView> lines, BigDecimal totalQuantity, String createdByName, Instant createdAt,
                              String decidedByName, Instant decidedAt, String decisionNote, boolean canDecide) {
    }

    /** An item's label text (what its QR code holds). */
    public record QrView(String payload) {
    }
}
