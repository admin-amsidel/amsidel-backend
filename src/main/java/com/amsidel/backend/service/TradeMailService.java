/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.MailResult;
import com.amsidel.backend.dto.PartyMailRequest;
import com.amsidel.backend.dto.TradeDtos.BrandRef;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.Customer;
import com.amsidel.backend.entity.Supplier;
import com.amsidel.backend.entity.TradeParty;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.CustomerRepository;
import com.amsidel.backend.repository.SupplierRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Customer and supplier mail (Product portal): the same personal mail as staff mail, to the email address of a
 * customer or a supplier of a brand I work with, with the office copy on every mail (see MailService.deliver).
 */
@Service
@RequiredArgsConstructor
public class TradeMailService {

    private final MailService mailService;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final TradeAccess access;

    /** Someone to mail: a customer or a supplier. */
    public record Recipient(Long id, TradeParty party, String code, String name, String email, String phone, Long brandId,
                            String brandCode, boolean active) {
    }

    public record Recipients(List<Recipient> customers, List<Recipient> suppliers, List<BrandRef> brands) {
    }

    private void canUse(AuthUser me) {
        access.require(me, "You cannot send customer and supplier mail", Menus.PROD_MAIL);
    }

    /** The customers and suppliers of my brands (one brand, or all of mine), active ones only. */
    @Transactional(readOnly = true)
    public Recipients recipients(AuthUser me, Long brandId) {
        canUse(me);
        Long scope = access.scope(me, brandId);
        // scope is null only for people who see every brand
        List<Recipient> customers = customerRepository.forBrand(scope).stream().filter(Customer::isActive)
                .map(c -> new Recipient(c.getId(), TradeParty.CUSTOMER, c.getCode(), c.getName(), c.getEmail(), c.getPhone(),
                        c.getBrand().getId(), c.getBrand().getCode(), c.isActive()))
                .toList();
        List<Recipient> suppliers = supplierRepository.forBrand(scope).stream().filter(Supplier::isActive)
                .map(s -> new Recipient(s.getId(), TradeParty.SUPPLIER, s.getCode(), s.getName(), s.getEmail(), s.getPhone(),
                        s.getBrand().getId(), s.getBrand().getCode(), s.isActive()))
                .toList();
        return new Recipients(customers, suppliers, access.brands(me).stream().map(BrandRef::of).toList());
    }

    /** One personal mail to one customer or supplier; problems with this one comes back as a failed result. */
    @Transactional(readOnly = true)
    public MailResult send(AuthUser me, PartyMailRequest request) {
        canUse(me);
        Long id = request.getPartyId();
        String name;
        String email;
        String code;
        Brand brand;
        if (request.getParty() == TradeParty.CUSTOMER) {
            Customer c = customerRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
            access.check(me, c.getBrand());
            name = c.getName().trim();
            email = c.getEmail();
            code = c.getCode();
            brand = c.getBrand();
        } else {
            Supplier s = supplierRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Supplier not found: " + id));
            access.check(me, s.getBrand());
            name = s.getName().trim();
            email = s.getEmail();
            code = s.getCode();
            brand = s.getBrand();
        }
        String kind = request.getParty() == TradeParty.CUSTOMER ? "Customer mail" : "Supplier mail";
        return mailService.deliver(me, request, id, name, email, "No email on record", kind, code, "Sent by " + brand.getName());
    }
}
