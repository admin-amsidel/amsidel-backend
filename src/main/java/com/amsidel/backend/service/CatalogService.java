package com.amsidel.backend.service;

import com.amsidel.backend.dto.TradeDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.ProductItemRepository;
import com.amsidel.backend.repository.ProductTypeRepository;
import com.amsidel.backend.repository.StockMoveRepository;
import com.amsidel.backend.repository.SupplierRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Product types and items: prices (supplier, MRP, showroom, minimum), sizes, the usual supplier, and stock. Stock
 * only changes through StockLedger: opening stock, additions and removals by hand here; purchases and sales from
 * their bills.
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    private final ProductTypeRepository typeRepository;
    private final ProductItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final StockMoveRepository moveRepository;
    private final StockLedger ledger;
    private final TradeAccess access;
    private final TradeHistoryLog history;

    // ---- types ----

    @Transactional(readOnly = true)
    public List<TypeView> types(AuthUser me, Long brandId) {
        Long scope = access.scope(me, brandId);
        Map<Long, Long> counts = itemRepository.forBrand(scope).stream()
                .collect(Collectors.groupingBy(i -> i.getType().getId(), Collectors.counting()));
        return typeRepository.forBrand(scope).stream().map(t -> view(t, counts.getOrDefault(t.getId(), 0L))).toList();
    }

    @Transactional
    public TypeView saveType(AuthUser me, Long id, TypeBody body) {
        access.require(me, "You cannot change product types", Menus.PROD_TYPES);
        ProductType t;
        if (id == null) {
            t = new ProductType();
            t.setBrand(access.brandFor(me, body.getBrandId()));
        } else {
            t = typeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product type not found: " + id));
            access.check(me, t.getBrand());
        }
        String code = body.getCode().strip().toUpperCase(Locale.ROOT);
        Long brandId = t.getBrand().getId();
        boolean taken = id == null ? typeRepository.existsByBrandIdAndCodeIgnoreCase(brandId, code)
                : typeRepository.existsByBrandIdAndCodeIgnoreCaseAndIdNot(brandId, code, id);
        if (taken) {
            throw ApiException.conflict("The code " + code + " is already used by another product type");
        }
        TradeHistoryLog.Changes changes = new TradeHistoryLog.Changes()
                .add("Code", t.getCode(), code).add("Name", t.getName(), body.getName().strip())
                .add("Description", t.getDescription(), blank(body.getDescription()))
                .add("In use", id == null ? null : t.isActive(), body.getActive() == null || body.getActive());
        t.setCode(code);
        t.setName(body.getName().strip());
        t.setDescription(blank(body.getDescription()));
        t.setSortOrder(body.getSortOrder());
        t.setActive(body.getActive() == null || body.getActive());
        t.setUpdatedBy(access.me(me));
        typeRepository.saveAndFlush(t);
        if (id == null) {
            history.record(me, t.getBrand(), TradeSubject.PRODUCT_TYPE, t.getId(), t.getCode() + " · " + t.getName(), "CREATED", null);
        } else if (!changes.isEmpty()) {
            history.record(me, t.getBrand(), TradeSubject.PRODUCT_TYPE, t.getId(), t.getCode() + " · " + t.getName(), "UPDATED", changes.toString());
        }
        return view(t, 0);
    }

    private static TypeView view(ProductType t, long items) {
        return new TypeView(t.getId(), BrandRef.of(t.getBrand()), t.getCode(), t.getName(), t.getDescription(),
                t.getSortOrder(), t.isActive(), items, t.getUpdatedBy() == null ? null : t.getUpdatedBy().getName(), t.getUpdatedAt());
    }

    // ---- items ----

    @Transactional(readOnly = true)
    public List<ItemView> items(AuthUser me, Long brandId) {
        return itemRepository.forBrand(access.scope(me, brandId)).stream().map(CatalogService::view).toList();
    }

    @Transactional(readOnly = true)
    public ItemDetail item(AuthUser me, Long id) {
        ProductItem i = full(me, id);
        List<MoveView> moves = moveRepository.forItem(id).stream().map(CatalogService::move).toList();
        return new ItemDetail(view(i), moves, history.of(List.of(TradeSubject.ITEM), id));
    }

    @Transactional
    public ItemView saveItem(AuthUser me, Long id, ItemBody body) {
        access.require(me, "You cannot change items", Menus.PROD_ITEMS);
        ProductItem i;
        if (id == null) {
            i = new ProductItem();
            i.setBrand(access.brandFor(me, body.getBrandId()));
        } else {
            i = full(me, id);
        }
        Long brandId = i.getBrand().getId();
        ProductType type = typeRepository.findById(body.getTypeId())
                .filter(t -> t.getBrand().getId().equals(brandId))
                .orElseThrow(() -> ApiException.badRequest("Choose a product type of " + i.getBrand().getName()));
        Supplier supplier = body.getSupplierId() == null ? null : supplierRepository.findById(body.getSupplierId())
                .filter(s -> s.getBrand().getId().equals(brandId))
                .orElseThrow(() -> ApiException.badRequest("Choose a supplier of " + i.getBrand().getName()));
        String code = body.getCode().strip().toUpperCase(Locale.ROOT);
        boolean taken = id == null ? itemRepository.existsByBrandIdAndCodeIgnoreCase(brandId, code)
                : itemRepository.existsByBrandIdAndCodeIgnoreCaseAndIdNot(brandId, code, id);
        if (taken) {
            throw ApiException.conflict("The item code " + code + " is already used");
        }
        if (body.getMinSellPrice().compareTo(body.getMrp()) > 0 && body.getMrp().signum() > 0) {
            throw ApiException.badRequest("The minimum selling price is more than the MRP");
        }
        TradeHistoryLog.Changes changes = new TradeHistoryLog.Changes();
        if (id != null) {
            changes.add("Type", i.getType().getName(), type.getName())
                    .add("Supplier", i.getSupplier() == null ? null : i.getSupplier().getName(), supplier == null ? null : supplier.getName())
                    .add("Code", i.getCode(), code).add("Name", i.getName(), body.getName().strip())
                    .add("Supplier's code", i.getSupplierItemCode(), blank(body.getSupplierItemCode()))
                    .add("Sizes", i.getSizes(), sizes(body.getSizes()))
                    .add("Unit", i.getUnit(), StringUtils.hasText(body.getUnit()) ? body.getUnit().strip() : "piece")
                    .add("HSN", i.getHsnCode(), blank(body.getHsnCode()))
                    .add("Supplier price", i.getSupplierPrice(), money(body.getSupplierPrice()))
                    .add("MRP", i.getMrp(), money(body.getMrp()))
                    .add("Showroom price", i.getShowroomPrice(), money(body.getShowroomPrice()))
                    .add("Minimum price", i.getMinSellPrice(), money(body.getMinSellPrice()))
                    .add("Track stock", i.isTrackStock(), body.getTrackStock() == null || body.getTrackStock())
                    .add("Re-order level", i.getReorderLevel() == null ? null : StockLedger.plain(i.getReorderLevel()),
                            body.getReorderLevel() == null ? null : StockLedger.plain(body.getReorderLevel()))
                    .add("Sold now", i.isActive(), body.getActive() == null || body.getActive())
                    .add("Details", i.getDetails(), blank(body.getDetails()));
        }
        i.setType(type);
        i.setSupplier(supplier);
        i.setCode(code);
        i.setName(body.getName().strip());
        i.setSupplierItemCode(blank(body.getSupplierItemCode()));
        i.setDetails(blank(body.getDetails()));
        i.setSizes(sizes(body.getSizes()));
        i.setUnit(StringUtils.hasText(body.getUnit()) ? body.getUnit().strip() : "piece");
        i.setHsnCode(blank(body.getHsnCode()));
        i.setSupplierPrice(money(body.getSupplierPrice()));
        i.setMrp(money(body.getMrp()));
        i.setShowroomPrice(money(body.getShowroomPrice()));
        i.setMinSellPrice(money(body.getMinSellPrice()));
        i.setTrackStock(body.getTrackStock() == null || body.getTrackStock());
        i.setReorderLevel(body.getReorderLevel() == null ? null : body.getReorderLevel().setScale(2, RoundingMode.HALF_UP));
        i.setActive(body.getActive() == null || body.getActive());
        i.setUpdatedBy(access.me(me));
        itemRepository.saveAndFlush(i);
        String label = i.getCode() + " · " + i.getName();
        if (id == null) {
            history.record(me, i.getBrand(), TradeSubject.ITEM, i.getId(), label, "CREATED",
                    "Supplier price " + PayslipPdf.rupees(i.getSupplierPrice()) + ", showroom " + PayslipPdf.rupees(i.getShowroomPrice())
                            + (body.getOpeningStock() != null && body.getOpeningStock().signum() > 0
                            ? ", opening stock " + StockLedger.plain(body.getOpeningStock()) + " " + i.getUnit() : ""));
        } else if (!changes.isEmpty()) {
            history.record(me, i.getBrand(), TradeSubject.ITEM, i.getId(), label, "UPDATED", changes.toString());
        }
        if (id == null && body.getOpeningStock() != null && body.getOpeningStock().signum() > 0) {
            ledger.move(i.getId(), StockMoveKind.OPENING, body.getOpeningStock(), access.today(), null, null, null,
                    "Opening stock", access.me(me), false);
        }
        return view(itemRepository.findFull(i.getId()).orElseThrow());
    }

    /** Opening stock, or stock added / removed by hand. */
    @Transactional
    public ItemDetail adjust(AuthUser me, Long id, StockBody body) {
        access.require(me, "You cannot change stock", Menus.PROD_ITEMS);
        ProductItem i = full(me, id);
        if (!i.isTrackStock()) {
            throw ApiException.badRequest(i.getCode() + " does not track stock; turn on \"Track stock\" first");
        }
        BigDecimal q = switch (body.getKind()) {
            case OPENING, ADJUST_IN -> body.getQuantity();
            case ADJUST_OUT -> body.getQuantity().negate();
            default -> throw ApiException.badRequest("Purchases and sales change stock through their bills");
        };
        StockMove m = ledger.move(i.getId(), body.getKind(), q, body.getMovedOn(), null, null, null, body.getNote().strip(), access.me(me), false);
        history.record(me, i.getBrand(), TradeSubject.ITEM, i.getId(), i.getCode() + " · " + i.getName(), "STOCK",
                (q.signum() > 0 ? "+" : "") + StockLedger.plain(q) + " " + i.getUnit() + " (" + body.getKind().name().toLowerCase().replace('_', ' ')
                        + "), now " + StockLedger.plain(m.getBalanceAfter()) + ": " + body.getNote().strip());
        return item(me, id);
    }

    ProductItem full(AuthUser me, Long id) {
        ProductItem i = itemRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Item not found: " + id));
        access.check(me, i.getBrand());
        return i;
    }

    static ItemView view(ProductItem i) {
        // low = some left, at or below the re-order level; none left is "out of stock", counted on its own
        boolean low = i.isTrackStock() && i.getReorderLevel() != null && i.getOnHand().signum() > 0
                && i.getOnHand().compareTo(i.getReorderLevel()) <= 0;
        BigDecimal value = i.getOnHand().signum() <= 0 ? BigDecimal.ZERO.setScale(2)
                : i.getOnHand().multiply(i.getSupplierPrice()).setScale(2, RoundingMode.HALF_UP);
        return new ItemView(i.getId(), BrandRef.of(i.getBrand()), i.getType().getId(), i.getType().getCode(), i.getType().getName(),
                i.getSupplier() == null ? null : i.getSupplier().getId(), i.getSupplier() == null ? null : i.getSupplier().getName(),
                i.getCode(), i.getName(), i.getSupplierItemCode(), i.getDetails(), i.getSizes(), i.getUnit(), i.getHsnCode(),
                i.getSupplierPrice(), i.getMrp(), i.getShowroomPrice(), i.getMinSellPrice(), i.isTrackStock(), i.getReorderLevel(),
                i.getTotalIn(), i.getTotalOut(), i.getOnHand(), value, low, i.isActive(),
                i.getUpdatedBy() == null ? null : i.getUpdatedBy().getName(), i.getUpdatedAt());
    }

    private static MoveView move(StockMove m) {
        Long docId = m.getSalesDoc() != null ? m.getSalesDoc().getId() : m.getPurchaseBill() != null ? m.getPurchaseBill().getId() : null;
        String number = m.getSalesDoc() != null ? m.getSalesDoc().getNumber() : m.getPurchaseBill() != null ? m.getPurchaseBill().getNumber() : null;
        String type = m.getSalesDoc() != null ? "SALE" : m.getPurchaseBill() != null ? "PURCHASE" : null;
        return new MoveView(m.getId(), m.getMovedOn(), m.getKind(), m.getQuantity(), m.getBalanceAfter(), m.getUnitCost(),
                docId, number, type, m.getNote(), m.getRecordedBy() == null ? null : m.getRecordedBy().getName(), m.getCreatedAt());
    }

    /** "6 * 4 , 8x7" -> "6x4, 8x7". */
    static String sizes(String sizes) {
        if (!StringUtils.hasText(sizes)) {
            return null;
        }
        return java.util.Arrays.stream(sizes.split(","))
                .map(s -> s.strip().replaceAll("\\s*[*xX×]\\s*", "x"))
                .filter(s -> !s.isEmpty()).distinct().collect(Collectors.joining(", "));
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    static String blank(String s) {
        return StringUtils.hasText(s) ? s.strip() : null;
    }
}
