package com.amsidel.backend.config;

import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.BrandUpiQr;
import com.amsidel.backend.entity.DocSequence;
import com.amsidel.backend.entity.ProductType;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.BrandUpiQrRepository;
import com.amsidel.backend.repository.DocSequenceRepository;
import com.amsidel.backend.repository.CustomerRepository;
import com.amsidel.backend.repository.SupplierRepository;
import com.amsidel.backend.service.Phones;
import com.amsidel.backend.service.UpiQr;
import com.amsidel.backend.repository.ProductItemRepository;
import com.amsidel.backend.repository.ProductTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * First start of the product portal: the Doors & Windows brand (DW) gets its product types (more are added on screen).
 * Every start: an item whose unit is not a word (a number typed there) gets "piece"; customer and supplier phone
 * numbers are put in the one form (+91 98470 12345). Once: DW gets the shop's UPI QR on its invoices.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeBootstrap implements ApplicationRunner {

    private static final List<String[]> DW_TYPES = List.of(
            new String[]{"UPVC", "uPVC Doors & Windows"},
            new String[]{"ALU", "Aluminium Doors & Windows"},
            new String[]{"WPC", "WPC Doors & Windows"},
            new String[]{"STEEL", "Steel Doors & Windows"},
            new String[]{"SAFETY", "Safety Doors"},
            new String[]{"ABS", "ABS Doors"},
            new String[]{"MEMBRANE", "Membrane Doors"},
            new String[]{"LAMINATED", "Laminated Doors"},
            new String[]{"SKIN", "Skin Doors"},
            new String[]{"FRP", "Fibre (FRP) Doors"},
            new String[]{"WOOD", "Wood"},
            new String[]{"PLYWOOD", "Plywood"});

    private final BrandRepository brandRepository;
    private final ProductTypeRepository typeRepository;
    private final ProductItemRepository itemRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final BrandUpiQrRepository upiQrRepository;
    private final DocSequenceRepository markers;

    /** DW's UPI payment QR (the shop's PhonePe QR), put on DW's invoices once. */
    private static final String DW_UPI_QR = "brand/dw-upi-qr.png";
    /** Kept in doc_sequences so it is done once: an owner's later change or removal stays. */
    private static final String DW_UPI_QR_DONE = "SETUP-DW-UPI-QR";

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        brandRepository.findAll().stream().filter(b -> "DW".equalsIgnoreCase(b.getCode())).findFirst().ifPresent(dw -> {
            seed(dw);
            dwUpiQr(dw);
        });
        // a number typed as the unit (e.g. "10") by mistake: the unit is "piece"
        itemRepository.findAll().stream().filter(i -> i.getUnit() == null || !i.getUnit().matches(".*[A-Za-z].*")).forEach(i -> {
            log.info("Item {}: unit \"{}\" is not a unit, now \"piece\"", i.getCode(), i.getUnit());
            i.setUnit("piece");
        });
        // phone numbers saved before they were kept in one form (so "one customer per number" compares like with like)
        customerRepository.findAll().forEach(c -> {
            c.setPhone(tidy(c.getPhone()));
            c.setPhone2(tidy(c.getPhone2()));
        });
        supplierRepository.findAll().forEach(x -> {
            x.setPhone(tidy(x.getPhone()));
            x.setPhone2(tidy(x.getPhone2()));
        });
    }

    /** The number in the one form, or as it was when it cannot be read. */
    private static String tidy(String phone) {
        try {
            String v = Phones.normalize(phone, "Phone");
            return v == null ? phone : v;
        } catch (RuntimeException e) {
            return phone;
        }
    }

    /** Once per database: DW's UPI QR on its invoices, and its UPI ID from the QR when it has none. */
    private void dwUpiQr(Brand dw) {
        if (markers.existsById(DW_UPI_QR_DONE)) {
            return;
        }
        DocSequence done = new DocSequence();
        done.setPrefix(DW_UPI_QR_DONE);
        done.setLastNumber(1);
        markers.save(done);
        if (dw.getUpiQrUpdatedAt() != null) {
            return;
        }
        byte[] bytes;
        try (InputStream in = new ClassPathResource(DW_UPI_QR).getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            log.warn("DW's UPI QR could not be read: {}", e.getMessage());
            return;
        }
        String payload = UpiQr.read(bytes);
        String payee = UpiQr.payee(payload);
        BrandUpiQr qr = new BrandUpiQr();
        qr.setBrandId(dw.getId());
        qr.setContent(bytes);
        qr.setContentType("image/png");
        qr.setPayload(payload);
        qr.setUpdatedAt(Instant.now());
        upiQrRepository.save(qr);
        dw.setUpiQrUpdatedAt(qr.getUpdatedAt());
        dw.setUpiQrPayee(payee);
        if (payee != null && (dw.getUpiId() == null || dw.getUpiId().isBlank())) {
            dw.setUpiId(payee.toLowerCase(Locale.ROOT));
        }
        log.info("UPI QR added to {} invoices (pays to {})", dw.getName(), payee);
    }

    private void seed(Brand dw) {
        if (typeRepository.countByBrandId(dw.getId()) > 0) {
            return;
        }
        int order = 0;
        for (String[] t : DW_TYPES) {
            ProductType type = new ProductType();
            type.setBrand(dw);
            type.setCode(t[0]);
            type.setName(t[1]);
            type.setSortOrder(order += 10);
            typeRepository.save(type);
        }
        log.info("Created the product types of {}", dw.getName());
    }
}
