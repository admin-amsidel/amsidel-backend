/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.StockScanDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.ProductItemRepository;
import com.amsidel.backend.repository.ProductTypeRepository;
import com.amsidel.backend.repository.StockReceiptRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * Item QR labels and stock received by scanning them. Anyone on Items & stock prints labels and sends what they
 * scanned (one line per item code, scanned again = one more); someone with "Approve scanned stock" checks it, may
 * correct the quantities, and approves: an item code the brand has gets the stock added, a new code becomes a new item
 * (from its label's details, its product type found by code or made) with that much opening stock.
 */
@Service
@RequiredArgsConstructor
public class StockScanService {

    private final StockReceiptRepository receipts;
    private final ProductItemRepository items;
    private final ProductTypeRepository types;
    private final CatalogService catalog;
    private final StockLedger ledger;
    private final TradeAccess access;
    private final TradeHistoryLog history;
    private final ItemLabelsPdf labelsPdf;

    // ---- labels ----

    @Transactional(readOnly = true)
    public QrView qr(AuthUser me, Long itemId) {
        access.require(me, "You cannot see items", Menus.PROD_ITEMS, Menus.PROD_QUOTES, Menus.PROD_INVOICES);
        return new QrView(ItemQr.payload(catalog.full(me, itemId)));
    }

    @Transactional(readOnly = true)
    public byte[] labels(AuthUser me, LabelsBody body) {
        access.require(me, "You cannot print item labels", Menus.PROD_ITEMS);
        List<ItemLabelsPdf.Copies> list = new ArrayList<>();
        int total = 0;
        for (LabelItem l : body.getItems()) {
            total += l.getCopies();
            list.add(new ItemLabelsPdf.Copies(catalog.full(me, l.getItemId()), l.getCopies()));
        }
        if (total > 2000) {
            throw ApiException.badRequest("At most 2000 labels at a time");
        }
        return labelsPdf.render(list, body.isShowPrice());
    }

    // ---- scanned stock ----

    @Transactional(readOnly = true)
    public List<ReceiptView> list(AuthUser me, Long brandId) {
        access.require(me, "You cannot see scanned stock", Menus.PROD_ITEMS, Menus.PROD_STOCK_APPROVE);
        return receipts.forBrand(access.scope(me, brandId)).stream().map(r -> view(me, r)).toList();
    }

    /** What was scanned, sent for approval: lines of the same code are put together. */
    @Transactional
    public ReceiptView submit(AuthUser me, ReceiptBody body) {
        access.require(me, "You cannot receive stock", Menus.PROD_ITEMS);
        StockReceipt r = new StockReceipt();
        r.setBrand(access.brandFor(me, body.getBrandId()));
        r.setNote(CatalogService.blank(body.getNote()));
        r.setCreatedBy(access.me(me));
        r.setCreatedAt(Instant.now());
        Map<String, StockReceiptLine> byCode = new LinkedHashMap<>();
        for (ReceiptLineBody b : body.getLines()) {
            ItemQr.Label label = ItemQr.read(b.getPayload());
            StockReceiptLine line = byCode.get(label.code());
            BigDecimal q = b.getQuantity().setScale(2, RoundingMode.HALF_UP);
            if (line != null) {
                line.setQuantity(line.getQuantity().add(q));
                continue;
            }
            line = new StockReceiptLine();
            line.setReceipt(r);
            line.setItemCode(label.code());
            line.setItemName(label.name());
            line.setPayload(b.getPayload().strip());
            line.setQuantity(q);
            line.setSortOrder(byCode.size());
            items.findByCode(r.getBrand().getId(), label.code()).ifPresent(line::setItem);
            byCode.put(label.code(), line);
        }
        r.getLines().addAll(byCode.values());
        receipts.saveAndFlush(r);
        return view(me, r);
    }

    /** Checked: each line's (possibly corrected) quantity goes into stock; new codes become items. */
    @Transactional
    public ReceiptView approve(AuthUser me, Long id, ApproveBody body) {
        access.require(me, "Approving scanned stock needs the \"Approve scanned stock\" access", Menus.PROD_STOCK_APPROVE);
        StockReceipt r = pending(me, id);
        Map<Long, BigDecimal> corrected = new HashMap<>();
        for (ApproveLine l : body.getLines()) {
            corrected.put(l.getId(), l.getQuantity().setScale(2, RoundingMode.HALF_UP));
        }
        Employee by = access.me(me);
        String ref = number(r);
        int taken = 0;
        for (StockReceiptLine line : r.getLines()) {
            BigDecimal q = corrected.getOrDefault(line.getId(), line.getQuantity());
            line.setApprovedQuantity(q);
            if (q.signum() <= 0) {
                continue;
            }
            taken++;
            ProductItem item = items.findByCode(r.getBrand().getId(), line.getItemCode()).orElse(null);
            if (item == null) {
                item = newItem(me, r.getBrand(), ItemQr.read(line.getPayload()), by);
                line.setCreatedItem(true);
                ledger.move(item.getId(), StockMoveKind.OPENING, q, access.today(), null, null, null,
                        "Opening stock from scanned receipt " + ref, by, false);
            } else {
                if (!item.isTrackStock()) {
                    throw ApiException.badRequest(item.getCode() + " does not track stock; turn on \"Track stock\" first, or set its quantity to 0");
                }
                ledger.move(item.getId(), StockMoveKind.ADJUST_IN, q, access.today(), null, null, null,
                        "Scanned stock receipt " + ref, by, false);
            }
            line.setItem(item);
            history.record(me, r.getBrand(), TradeSubject.ITEM, item.getId(), item.getCode() + " · " + item.getName(), "STOCK",
                    "+" + StockLedger.plain(q) + " " + item.getUnit() + " from scanned receipt " + ref
                            + (line.isCreatedItem() ? " (new item from its label)" : ""));
        }
        if (taken == 0) {
            throw ApiException.badRequest("Every quantity is 0: turn it down instead");
        }
        r.setStatus(StockReceiptStatus.APPROVED);
        r.setDecidedBy(by);
        r.setDecidedAt(Instant.now());
        r.setDecisionNote(CatalogService.blank(body.getNote()));
        receipts.saveAndFlush(r);
        return view(me, receipts.findFull(r.getId()).orElseThrow());
    }

    @Transactional
    public ReceiptView reject(AuthUser me, Long id, RejectBody body) {
        access.require(me, "Turning down scanned stock needs the \"Approve scanned stock\" access", Menus.PROD_STOCK_APPROVE);
        StockReceipt r = pending(me, id);
        r.setStatus(StockReceiptStatus.REJECTED);
        r.setDecidedBy(access.me(me));
        r.setDecidedAt(Instant.now());
        r.setDecisionNote(body.getNote().strip());
        return view(me, r);
    }

    // ---- helpers ----

    private StockReceipt pending(AuthUser me, Long id) {
        StockReceipt r = receipts.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Scanned receipt not found: " + id));
        access.check(me, r.getBrand());
        if (r.getStatus() != StockReceiptStatus.PENDING) {
            throw ApiException.conflict(number(r) + " is already " + r.getStatus().name().toLowerCase(Locale.ROOT));
        }
        return r;
    }

    /** An item made from its label: its product type found by code in the brand, or made from the label. */
    private ProductItem newItem(AuthUser me, Brand brand, ItemQr.Label l, Employee by) {
        String typeCode = l.type() == null ? "GENERAL" : l.type().toUpperCase(Locale.ROOT);
        ProductType type = types.findFirstByBrandIdAndCodeIgnoreCase(brand.getId(), typeCode).orElseGet(() -> {
            ProductType t = new ProductType();
            t.setBrand(brand);
            t.setCode(typeCode.length() > 20 ? typeCode.substring(0, 20) : typeCode);
            t.setName(l.typeName() == null ? typeCode : l.typeName());
            t.setSortOrder((int) types.countByBrandId(brand.getId()) + 1);
            t.setUpdatedBy(by);
            t.setCreatedAt(Instant.now());
            t.setUpdatedAt(Instant.now());
            ProductType saved = types.save(t);
            history.record(me, brand, TradeSubject.PRODUCT_TYPE, saved.getId(), saved.getCode() + " · " + saved.getName(), "CREATED",
                    "Made from a scanned item label");
            return saved;
        });
        ProductItem i = new ProductItem();
        i.setBrand(brand);
        i.setType(type);
        i.setCode(l.code());
        i.setName(l.name());
        i.setSupplierItemCode(l.supplierItemCode());
        i.setDetails(l.details());
        i.setSizes(CatalogService.sizes(l.sizes()));
        i.setUnit(l.unit() == null || l.unit().isBlank() ? "piece" : l.unit());
        i.setHsnCode(l.hsn() != null && l.hsn().matches("^[0-9]{4,8}$") ? l.hsn() : null);
        i.setSupplierPrice(l.supplierPrice());
        i.setMrp(l.mrp());
        i.setShowroomPrice(l.showroomPrice());
        i.setMinSellPrice(l.minSellPrice());
        i.setTrackStock(true);
        i.setActive(true);
        i.setUpdatedBy(by);
        items.saveAndFlush(i);
        history.record(me, brand, TradeSubject.ITEM, i.getId(), i.getCode() + " · " + i.getName(), "CREATED", "Made from its scanned QR label");
        return i;
    }

    private ReceiptView view(AuthUser me, StockReceipt r) {
        List<ReceiptLineView> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (StockReceiptLine l : r.getLines()) {
            ProductItem item = l.getItem() != null ? l.getItem()
                    : r.getStatus() == StockReceiptStatus.PENDING ? items.findByCode(r.getBrand().getId(), l.getItemCode()).orElse(null) : null;
            ItemQr.Label label = ItemQr.read(l.getPayload());
            lines.add(new ReceiptLineView(l.getId(), l.getItemCode(), l.getItemName(),
                    item != null ? item.getType().getName() : label.typeName(), item != null ? item.getUnit() : label.unit(),
                    l.getQuantity(), l.getApprovedQuantity(), item == null ? null : item.getId(), item != null && !l.isCreatedItem(),
                    item == null ? null : item.getOnHand(), item != null ? item.getShowroomPrice() : label.showroomPrice(), l.isCreatedItem()));
            total = total.add(l.getApprovedQuantity() != null ? l.getApprovedQuantity() : l.getQuantity());
        }
        return new ReceiptView(r.getId(), number(r), r.getBrand().getId(), r.getBrand().getCode(), r.getStatus(), r.getNote(), lines, total,
                r.getCreatedBy() == null ? null : r.getCreatedBy().getName(), r.getCreatedAt(),
                r.getDecidedBy() == null ? null : r.getDecidedBy().getName(), r.getDecidedAt(), r.getDecisionNote(),
                r.getStatus() == StockReceiptStatus.PENDING && access.has(me, Menus.PROD_STOCK_APPROVE));
    }

    /** "SR-12". */
    private static String number(StockReceipt r) {
        return "SR-" + r.getId();
    }
}
