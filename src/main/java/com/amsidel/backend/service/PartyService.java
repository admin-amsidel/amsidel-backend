/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.TradeDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.*;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.amsidel.backend.service.CatalogService.blank;

/**
 * Suppliers and customers, each with what is owed: for a customer, the issued invoices against the payments
 * received; for a supplier, the received purchase bills against the payments made. A customer's first phone number
 * is unique in the brand.
 */
@Service
@RequiredArgsConstructor
public class PartyService {

    static final LocalDate EVER_FROM = LocalDate.of(2000, 1, 1);
    static final LocalDate EVER_TO = LocalDate.of(2999, 12, 31);

    private final SupplierRepository supplierRepository;
    private final CustomerRepository customerRepository;
    private final ProductItemRepository itemRepository;
    private final SalesDocRepository salesRepository;
    private final PurchaseBillRepository purchaseRepository;
    private final TradeNumbers numbers;
    private final TradeAccess access;
    private final TradeHistoryLog history;

    // ---- suppliers ----

    @Transactional(readOnly = true)
    public List<SupplierView> suppliers(AuthUser me, Long brandId) {
        Long scope = access.scope(me, brandId);
        Map<Long, List<PurchaseBill>> bills = purchaseRepository.list(scope, EVER_FROM, EVER_TO).stream()
                .collect(Collectors.groupingBy(b -> b.getSupplier().getId()));
        Map<Long, Long> items = itemRepository.forBrand(scope).stream().filter(i -> i.getSupplier() != null)
                .collect(Collectors.groupingBy(i -> i.getSupplier().getId(), Collectors.counting()));
        return supplierRepository.forBrand(scope).stream()
                .map(s -> view(s, bills.getOrDefault(s.getId(), List.of()), items.getOrDefault(s.getId(), 0L).intValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SupplierView supplier(AuthUser me, Long id) {
        Supplier s = supplierFull(me, id);
        int items = (int) itemRepository.forBrand(s.getBrand().getId()).stream()
                .filter(i -> i.getSupplier() != null && i.getSupplier().getId().equals(id)).count();
        return view(s, purchaseRepository.forSupplier(id), items);
    }

    @Transactional
    public SupplierView saveSupplier(AuthUser me, Long id, SupplierBody body) {
        access.require(me, "You cannot change suppliers", Menus.PROD_SUPPLIERS);
        Supplier s;
        if (id == null) {
            s = new Supplier();
            s.setBrand(access.brandFor(me, body.getBrandId()));
        } else {
            s = supplierFull(me, id);
        }
        Long brandId = s.getBrand().getId();
        String code = blank(body.getCode());
        if (code == null) {
            code = id == null ? numbers.supplier(s.getBrand()) : s.getCode();
        }
        code = code.toUpperCase(Locale.ROOT);
        boolean taken = id == null ? supplierRepository.existsByBrandIdAndCodeIgnoreCase(brandId, code)
                : supplierRepository.existsByBrandIdAndCodeIgnoreCaseAndIdNot(brandId, code, id);
        if (taken) {
            throw ApiException.conflict("The supplier code " + code + " is already used");
        }
        String phone = Phones.normalize(body.getPhone(), "Phone");
        String phone2 = Phones.normalize(body.getPhone2(), "Second phone");
        TradeHistoryLog.Changes changes = new TradeHistoryLog.Changes();
        if (id != null) {
            changes.add("Code", s.getCode(), code).add("Name", s.getName(), body.getName().strip())
                    .add("Contact", s.getContactPerson(), blank(body.getContactPerson()))
                    .add("Phone", s.getPhone(), phone).add("Second phone", s.getPhone2(), phone2)
                    .add("Email", s.getEmail(), lower(body.getEmail())).add("Website", s.getWebsite(), blank(body.getWebsite()))
                    .add("Location", s.getLocation(), blank(body.getLocation())).add("Address", s.getAddress(), blank(body.getAddress()))
                    .add("GSTIN", s.getGstNumber(), upper(body.getGstNumber())).add("PAN", s.getPanNumber(), upper(body.getPanNumber()))
                    .add("Bank account", s.getBankAccountNumber(), blank(body.getBankAccount()))
                    .add("IFSC", s.getIfscCode(), upper(body.getIfsc())).add("UPI ID", s.getUpiId(), lower(body.getUpi()))
                    .add("Notes", s.getNotes(), blank(body.getNotes()))
                    .add("Active", s.isActive(), body.getActive() == null || body.getActive());
        }
        s.setCode(code);
        s.setName(body.getName().strip());
        s.setContactPerson(blank(body.getContactPerson()));
        s.setPhone(phone);
        s.setPhone2(phone2);
        s.setEmail(lower(body.getEmail()));
        s.setWebsite(blank(body.getWebsite()));
        s.setLocation(blank(body.getLocation()));
        s.setAddress(blank(body.getAddress()));
        s.setGstNumber(upper(body.getGstNumber()));
        s.setPanNumber(upper(body.getPanNumber()));
        s.setBankAccountNumber(blank(body.getBankAccount()));
        s.setIfscCode(upper(body.getIfsc()));
        s.setUpiId(lower(body.getUpi()));
        s.setNotes(blank(body.getNotes()));
        s.setActive(body.getActive() == null || body.getActive());
        s.setUpdatedBy(access.me(me));
        supplierRepository.saveAndFlush(s);
        if (id == null) {
            history.record(me, s.getBrand(), TradeSubject.SUPPLIER, s.getId(), s.getName() + " (" + s.getCode() + ")", "CREATED", s.getPhone());
        } else if (!changes.isEmpty()) {
            history.record(me, s.getBrand(), TradeSubject.SUPPLIER, s.getId(), s.getName() + " (" + s.getCode() + ")", "UPDATED", changes.toString());
        }
        return supplier(me, s.getId());
    }

    Supplier supplierFull(AuthUser me, Long id) {
        Supplier s = supplierRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Supplier not found: " + id));
        access.check(me, s.getBrand());
        return s;
    }

    private static SupplierView view(Supplier s, List<PurchaseBill> bills, int items) {
        List<PurchaseBill> live = bills.stream().filter(b -> b.getStatus() != PurchaseStatus.CANCELLED).toList();
        BigDecimal billed = sum(live.stream().filter(b -> b.getStatus() == PurchaseStatus.RECEIVED).map(PurchaseBill::getTotal).toList());
        BigDecimal paid = sum(live.stream().map(PurchaseBill::getPaid).toList());
        return new SupplierView(s.getId(), BrandRef.of(s.getBrand()), s.getCode(), s.getName(), s.getContactPerson(),
                s.getPhone(), s.getPhone2(), s.getEmail(), s.getWebsite(), s.getLocation(), s.getAddress(), s.getGstNumber(),
                s.getPanNumber(), s.getBankAccountNumber(), s.getIfscCode(), s.getUpiId(), s.getNotes(), s.isActive(),
                new Balance(billed, paid, billed.subtract(paid)), live.size(), items,
                s.getUpdatedBy() == null ? null : s.getUpdatedBy().getName(), s.getUpdatedAt());
    }

    // ---- customers ----

    @Transactional(readOnly = true)
    public List<CustomerView> customers(AuthUser me, Long brandId) {
        Long scope = access.scope(me, brandId);
        Map<Long, List<SalesDoc>> docs = java.util.stream.Stream.concat(
                        salesRepository.list(scope, SalesDocKind.INVOICE, EVER_FROM, EVER_TO).stream(),
                        salesRepository.list(scope, SalesDocKind.QUOTATION, EVER_FROM, EVER_TO).stream())
                .collect(Collectors.groupingBy(d -> d.getCustomer().getId()));
        return customerRepository.forBrand(scope).stream()
                .map(c -> view(c, docs.getOrDefault(c.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public CustomerView customer(AuthUser me, Long id) {
        Customer c = customerFull(me, id);
        return view(c, salesRepository.forCustomer(id));
    }

    /** Customers can also be added from a quotation or an invoice. */
    @Transactional
    public CustomerView saveCustomer(AuthUser me, Long id, CustomerBody body) {
        access.require(me, "You cannot change customers", Menus.PROD_CUSTOMERS, Menus.PROD_QUOTES, Menus.PROD_INVOICES);
        Customer c;
        if (id == null) {
            c = new Customer();
            c.setBrand(access.brandFor(me, body.getBrandId()));
        } else {
            c = customerFull(me, id);
        }
        String phone = Phones.normalize(body.getPhone(), "Phone");
        customerRepository.findByBrandIdAndPhone(c.getBrand().getId(), phone)
                .filter(other -> !other.getId().equals(c.getId()))
                .ifPresent(other -> {
                    throw ApiException.conflict("The phone number " + phone + " is already " + other.getName() + "'s (" + other.getCode() + ")");
                });
        if (id == null) {
            c.setCode(numbers.customer(c.getBrand()));
        }
        String phone2 = Phones.normalize(body.getPhone2(), "Second phone");
        TradeHistoryLog.Changes changes = new TradeHistoryLog.Changes();
        if (id != null) {
            changes.add("Name", c.getName(), body.getName().strip()).add("Phone", c.getPhone(), phone)
                    .add("Second phone", c.getPhone2(), phone2).add("Email", c.getEmail(), lower(body.getEmail()))
                    .add("Location", c.getLocation(), blank(body.getLocation())).add("Address", c.getAddress(), blank(body.getAddress()))
                    .add("GSTIN", c.getGstNumber(), upper(body.getGstNumber())).add("PAN", c.getPanNumber(), upper(body.getPanNumber()))
                    .add("Notes", c.getNotes(), blank(body.getNotes()))
                    .add("Active", c.isActive(), body.getActive() == null || body.getActive());
        }
        c.setName(body.getName().strip());
        c.setPhone(phone);
        c.setPhone2(phone2);
        c.setEmail(lower(body.getEmail()));
        c.setLocation(blank(body.getLocation()));
        c.setAddress(blank(body.getAddress()));
        c.setGstNumber(upper(body.getGstNumber()));
        c.setPanNumber(upper(body.getPanNumber()));
        c.setNotes(blank(body.getNotes()));
        c.setActive(body.getActive() == null || body.getActive());
        c.setUpdatedBy(access.me(me));
        customerRepository.saveAndFlush(c);
        if (id == null) {
            history.record(me, c.getBrand(), TradeSubject.CUSTOMER, c.getId(), c.getName() + " (" + c.getCode() + ")", "CREATED", c.getPhone());
        } else if (!changes.isEmpty()) {
            history.record(me, c.getBrand(), TradeSubject.CUSTOMER, c.getId(), c.getName() + " (" + c.getCode() + ")", "UPDATED", changes.toString());
        }
        return customer(me, c.getId());
    }

    Customer customerFull(AuthUser me, Long id) {
        Customer c = customerRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
        access.check(me, c.getBrand());
        return c;
    }

    private static CustomerView view(Customer c, List<SalesDoc> docs) {
        List<SalesDoc> invoices = docs.stream()
                .filter(d -> d.getKind() == SalesDocKind.INVOICE && d.getStatus() != SalesDocStatus.CANCELLED).toList();
        BigDecimal billed = sum(invoices.stream().filter(d -> d.getStatus() == SalesDocStatus.ISSUED).map(SalesDoc::getTotal).toList());
        BigDecimal paid = sum(invoices.stream().map(SalesDoc::getPaid).toList());
        BigDecimal writtenOff = sum(invoices.stream().map(SalesDoc::getWrittenOff).toList());
        int quotations = (int) docs.stream().filter(d -> d.getKind() == SalesDocKind.QUOTATION).count();
        LocalDate last = docs.stream().map(SalesDoc::getDocDate).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new CustomerView(c.getId(), BrandRef.of(c.getBrand()), c.getCode(), c.getName(), c.getPhone(), c.getPhone2(),
                c.getEmail(), c.getLocation(), c.getAddress(), c.getGstNumber(), c.getPanNumber(), c.getNotes(), c.isActive(),
                new Balance(billed, paid, billed.subtract(paid).subtract(writtenOff)), quotations, invoices.size(), last,
                c.getUpdatedBy() == null ? null : c.getUpdatedBy().getName(), c.getUpdatedAt());
    }

    static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private static String upper(String s) {
        String v = blank(s);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String lower(String s) {
        String v = blank(s);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }
}
