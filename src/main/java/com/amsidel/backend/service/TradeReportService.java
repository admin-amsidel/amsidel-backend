/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.TradeDtos.ItemView;
import com.amsidel.backend.dto.TradeDtos.PaymentView;
import com.amsidel.backend.dto.TradeDtos.SalesRow;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.*;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.math.BigDecimal.ZERO;

/**
 * Reports of the product portal, for a brand (or all mine) and a date range: sales by customer, invoice, product
 * type and item; purchases by supplier, product type and item; stock; payments in and out; what is still owed; and
 * where the money paid to suppliers went (for the charts). The same data as Excel files.
 */
@Service
@RequiredArgsConstructor
public class TradeReportService {

    private static final int MAX_DAYS = 3 * 366;
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final SalesDocRepository salesRepository;
    private final PurchaseBillRepository purchaseRepository;
    private final ProductItemRepository itemRepository;
    private final StockMoveRepository moveRepository;
    private final TradePaymentRepository paymentRepository;
    private final TradeAccess access;

    /** A group of a report: e.g. one customer, with how many bills, the quantity and the money. */
    public record Group(String key, String label, int count, BigDecimal quantity, BigDecimal amount, BigDecimal cost,
                        BigDecimal margin) {
    }

    // ---- sales ----

    public record SalesLine(LocalDate date, Long docId, String number, String brandCode, Long customerId, String customerCode,
                            String customerName, String customerPhone, Long itemId, String itemCode, String itemName,
                            String typeName, String size, BigDecimal quantity, String unit, BigDecimal rate, BigDecimal amount,
                            BigDecimal cost, BigDecimal margin) {
    }

    public record SalesTotals(int invoices, BigDecimal subtotal, BigDecimal discount, BigDecimal misc, BigDecimal taxable,
                              BigDecimal gst, BigDecimal total, BigDecimal paid, BigDecimal due, BigDecimal cost,
                              BigDecimal margin) {
    }

    public record SalesReport(LocalDate from, LocalDate to, List<SalesLine> lines, List<SalesRow> invoices,
                              List<Group> byCustomer, List<Group> byType, List<Group> byItem, SalesTotals totals) {
    }

    @Transactional(readOnly = true)
    public SalesReport sales(AuthUser me, Long brandId, LocalDate from, LocalDate to, Long customerId, Long itemId,
                             String typeCode, String number) {
        canRead(me);
        range(from, to);
        List<SalesDoc> docs = salesRepository.issued(access.scope(me, brandId), from, to).stream()
                .filter(d -> customerId == null || d.getCustomer().getId().equals(customerId))
                .filter(d -> number == null || number.isBlank() || (d.getNumber() != null
                        && d.getNumber().toUpperCase(Locale.ROOT).contains(number.strip().toUpperCase(Locale.ROOT))))
                .toList();
        List<SalesLine> lines = new ArrayList<>();
        for (SalesDoc d : docs) {
            for (SalesDocLine l : d.getLines()) {
                if (itemId != null && (l.getItem() == null || !l.getItem().getId().equals(itemId))) {
                    continue;
                }
                if (typeCode != null && !typeCode.isBlank() && (l.getItem() == null || !l.getItem().getType().getCode().equalsIgnoreCase(typeCode))) {
                    continue;
                }
                BigDecimal cost = l.getUnitCost().multiply(l.getQuantity()).setScale(2, RoundingMode.HALF_UP);
                lines.add(new SalesLine(d.getDocDate(), d.getId(), d.getNumber(), d.getBrand().getCode(), d.getCustomer().getId(),
                        d.getCustomer().getCode(), d.getCustomer().getName(), d.getCustomer().getPhone(),
                        l.getItem() == null ? null : l.getItem().getId(), l.getItemCode(), l.getName(),
                        l.getTypeName() == null ? "Other charges" : l.getTypeName(), l.getSize(), l.getQuantity(), l.getUnit(),
                        l.getRate(), l.getAmount(), cost, l.getAmount().subtract(cost)));
            }
        }
        boolean wholeBills = itemId == null && (typeCode == null || typeCode.isBlank());
        Set<Long> shown = lines.stream().map(SalesLine::docId).collect(Collectors.toSet());
        List<SalesDoc> billed = docs.stream().filter(d -> wholeBills || shown.contains(d.getId())).toList();
        SalesTotals totals = new SalesTotals(billed.size(), sum(billed, SalesDoc::getSubtotal), sum(billed, SalesDoc::getDiscount),
                sum(billed, SalesDoc::getMiscAmount), sum(billed, SalesDoc::getTaxable), sum(billed, SalesDoc::getGstAmount),
                sum(billed, SalesDoc::getTotal), sum(billed, SalesDoc::getPaid),
                billed.stream().map(d -> d.getTotal().subtract(d.getCleared()).max(ZERO)).reduce(ZERO, BigDecimal::add),
                sumOf(lines, SalesLine::cost), sumOf(lines, SalesLine::margin));
        return new SalesReport(from, to, lines, billed.stream().map(SalesService::row).toList(),
                groups(lines, l -> String.valueOf(l.customerId()), l -> l.customerName() + " (" + l.customerCode() + ")",
                        SalesLine::docId, SalesLine::quantity, SalesLine::amount, SalesLine::cost),
                groups(lines, SalesLine::typeName, SalesLine::typeName, SalesLine::docId, SalesLine::quantity, SalesLine::amount, SalesLine::cost),
                groups(lines, l -> l.itemCode() == null ? l.itemName() : l.itemCode(),
                        l -> l.itemCode() == null ? l.itemName() : l.itemCode() + " · " + l.itemName(),
                        SalesLine::docId, SalesLine::quantity, SalesLine::amount, SalesLine::cost),
                totals);
    }

    // ---- purchases ----

    public record PurchaseLine(LocalDate date, Long billId, String number, String supplierBillNo, String brandCode,
                               Long supplierId, String supplierCode, String supplierName, Long itemId, String itemCode,
                               String itemName, String typeName, BigDecimal quantity, String unit, BigDecimal rate,
                               BigDecimal amount) {
    }

    public record PurchaseTotals(int bills, BigDecimal subtotal, BigDecimal discount, BigDecimal misc, BigDecimal gst,
                                 BigDecimal total, BigDecimal paid, BigDecimal due) {
    }

    public record PurchaseReport(LocalDate from, LocalDate to, List<PurchaseLine> lines, List<Group> bySupplier,
                                 List<Group> byType, List<Group> byItem, List<Group> byMonth, PurchaseTotals totals) {
    }

    @Transactional(readOnly = true)
    public PurchaseReport purchases(AuthUser me, Long brandId, LocalDate from, LocalDate to, Long supplierId, Long itemId,
                                    String typeCode, String number) {
        canRead(me);
        range(from, to);
        List<PurchaseBill> bills = purchaseRepository.received(access.scope(me, brandId), from, to).stream()
                .filter(b -> supplierId == null || b.getSupplier().getId().equals(supplierId))
                .filter(b -> number == null || number.isBlank() || b.getNumber().toUpperCase(Locale.ROOT).contains(number.strip().toUpperCase(Locale.ROOT))
                        || (b.getSupplierBillNo() != null && b.getSupplierBillNo().toUpperCase(Locale.ROOT).contains(number.strip().toUpperCase(Locale.ROOT))))
                .toList();
        List<PurchaseLine> lines = new ArrayList<>();
        for (PurchaseBill b : bills) {
            for (PurchaseBillLine l : b.getLines()) {
                ProductItem i = l.getItem();
                if ((itemId != null && !i.getId().equals(itemId))
                        || (typeCode != null && !typeCode.isBlank() && !i.getType().getCode().equalsIgnoreCase(typeCode))) {
                    continue;
                }
                lines.add(new PurchaseLine(b.getBillDate(), b.getId(), b.getNumber(), b.getSupplierBillNo(), b.getBrand().getCode(),
                        b.getSupplier().getId(), b.getSupplier().getCode(), b.getSupplier().getName(), i.getId(), l.getItemCode(),
                        l.getName(), i.getType().getName(), l.getQuantity(), i.getUnit(), l.getRate(), l.getAmount()));
            }
        }
        boolean wholeBills = itemId == null && (typeCode == null || typeCode.isBlank());
        Set<Long> shown = lines.stream().map(PurchaseLine::billId).collect(Collectors.toSet());
        List<PurchaseBill> counted = bills.stream().filter(b -> wholeBills || shown.contains(b.getId())).toList();
        PurchaseTotals totals = new PurchaseTotals(counted.size(), sumB(counted, PurchaseBill::getSubtotal), sumB(counted, PurchaseBill::getDiscount),
                sumB(counted, PurchaseBill::getMiscAmount), sumB(counted, PurchaseBill::getGstAmount), sumB(counted, PurchaseBill::getTotal),
                sumB(counted, PurchaseBill::getPaid),
                counted.stream().map(b -> b.getTotal().subtract(b.getPaid()).max(ZERO)).reduce(ZERO, BigDecimal::add));
        Function<PurchaseLine, BigDecimal> none = l -> null;
        List<Group> byMonth = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            YearMonth month = m;
            List<PurchaseLine> in = lines.stream().filter(l -> YearMonth.from(l.date()).equals(month)).toList();
            byMonth.add(new Group(month.toString(), MONTH.format(month), (int) in.stream().map(PurchaseLine::billId).distinct().count(),
                    sumOf(in, PurchaseLine::quantity), sumOf(in, PurchaseLine::amount), null, null));
        }
        return new PurchaseReport(from, to, lines,
                groups(lines, l -> String.valueOf(l.supplierId()), l -> l.supplierName() + " (" + l.supplierCode() + ")",
                        PurchaseLine::billId, PurchaseLine::quantity, PurchaseLine::amount, none),
                groups(lines, PurchaseLine::typeName, PurchaseLine::typeName, PurchaseLine::billId, PurchaseLine::quantity, PurchaseLine::amount, none),
                groups(lines, PurchaseLine::itemCode, l -> l.itemCode() + " · " + l.itemName(), PurchaseLine::billId,
                        PurchaseLine::quantity, PurchaseLine::amount, none),
                byMonth, totals);
    }

    // ---- stock ----

    public record StockLine(ItemView item, BigDecimal boughtInRange, BigDecimal soldInRange, BigDecimal adjustedInRange,
                            BigDecimal retailValue) {
    }

    public record StockReport(LocalDate from, LocalDate to, List<StockLine> items, BigDecimal stockValue,
                              BigDecimal retailValue, int lowStock, int outOfStock) {
    }

    @Transactional(readOnly = true)
    public StockReport stock(AuthUser me, Long brandId, LocalDate from, LocalDate to) {
        access.require(me, "You cannot see stock reports", Menus.PROD_REPORTS, Menus.PROD_ITEMS, Menus.PROD_HOME);
        range(from, to);
        Long scope = access.scope(me, brandId);
        Map<Long, List<StockMove>> moves = moveRepository.inRange(scope, from, to).stream()
                .collect(Collectors.groupingBy(m -> m.getItem().getId()));
        List<StockLine> items = itemRepository.forBrand(scope).stream().map(i -> {
            List<StockMove> ms = moves.getOrDefault(i.getId(), List.of());
            BigDecimal bought = ms.stream().filter(m -> m.getKind() == StockMoveKind.PURCHASE || m.getKind() == StockMoveKind.PURCHASE_CANCEL)
                    .map(StockMove::getQuantity).reduce(ZERO, BigDecimal::add);
            BigDecimal sold = ms.stream().filter(m -> m.getKind() == StockMoveKind.SALE || m.getKind() == StockMoveKind.SALE_CANCEL)
                    .map(StockMove::getQuantity).reduce(ZERO, BigDecimal::add).negate();
            BigDecimal adjusted = ms.stream().filter(m -> m.getKind() == StockMoveKind.OPENING || m.getKind() == StockMoveKind.ADJUST_IN
                    || m.getKind() == StockMoveKind.ADJUST_OUT || m.getKind() == StockMoveKind.RETURN_OUT || m.getKind() == StockMoveKind.RETURN_IN).map(StockMove::getQuantity).reduce(ZERO, BigDecimal::add);
            BigDecimal retail = i.getOnHand().signum() <= 0 ? ZERO : i.getOnHand().multiply(i.getShowroomPrice()).setScale(2, RoundingMode.HALF_UP);
            return new StockLine(CatalogService.view(i), bought, sold, adjusted, retail);
        }).toList();
        return new StockReport(from, to, items, items.stream().map(s -> s.item().stockValue()).reduce(ZERO, BigDecimal::add),
                items.stream().map(StockLine::retailValue).reduce(ZERO, BigDecimal::add),
                (int) items.stream().filter(s -> s.item().lowStock()).count(),
                (int) items.stream().filter(s -> s.item().trackStock() && s.item().onHand().signum() <= 0 && s.item().active()).count());
    }

    // ---- payments ----

    public record PaymentReport(LocalDate from, LocalDate to, List<PaymentView> payments, BigDecimal received,
                                BigDecimal refundedToCustomers, BigDecimal paidToSuppliers, BigDecimal refundedBySuppliers) {
    }

    @Transactional(readOnly = true)
    public PaymentReport payments(AuthUser me, Long brandId, LocalDate from, LocalDate to, TradeParty party) {
        canRead(me);
        range(from, to);
        List<TradePayment> all = paymentRepository.inRange(access.scope(me, brandId), from, to).stream()
                .filter(p -> party == null || p.getParty() == party).toList();
        List<TradePayment> live = all.stream().filter(p -> !p.isVoided()).toList();
        return new PaymentReport(from, to, all.stream().map(TradePayments::view).toList(),
                total(live, TradeParty.CUSTOMER, TradePaymentKind.PAYMENT), total(live, TradeParty.CUSTOMER, TradePaymentKind.REFUND),
                total(live, TradeParty.SUPPLIER, TradePaymentKind.PAYMENT), total(live, TradeParty.SUPPLIER, TradePaymentKind.REFUND));
    }

    // ---- what is owed ----

    public record Owed(Long id, String code, String name, String phone, String brandCode, int bills, BigDecimal billed,
                       BigDecimal paid, BigDecimal due, LocalDate oldestBill) {
    }

    public record OwedReport(List<Owed> customers, BigDecimal receivable, List<Owed> suppliers, BigDecimal payable) {
    }

    @Transactional(readOnly = true)
    public OwedReport owed(AuthUser me, Long brandId) {
        access.require(me, "You cannot see this report", Menus.PROD_REPORTS, Menus.PROD_HOME);
        Long scope = access.scope(me, brandId);
        List<Owed> customers = salesRepository.openInvoices(scope).stream()
                .filter(d -> d.getTotal().compareTo(d.getCleared()) > 0)
                .collect(Collectors.groupingBy(d -> d.getCustomer().getId())).values().stream().map(ds -> {
                    Customer c = ds.getFirst().getCustomer();
                    return owed(c.getId(), c.getCode(), c.getName(), c.getPhone(), ds.getFirst().getBrand().getCode(), ds.size(),
                            sum(ds, SalesDoc::getTotal), sum(ds, SalesDoc::getCleared),
                            ds.stream().map(SalesDoc::getDocDate).min(Comparator.naturalOrder()).orElse(null));
                }).sorted(Comparator.comparing(Owed::due).reversed()).toList();
        List<Owed> suppliers = purchaseRepository.openBills(scope).stream()
                .filter(b -> b.getTotal().compareTo(b.getPaid()) > 0)
                .collect(Collectors.groupingBy(b -> b.getSupplier().getId())).values().stream().map(bs -> {
                    Supplier s = bs.getFirst().getSupplier();
                    return owed(s.getId(), s.getCode(), s.getName(), s.getPhone(), bs.getFirst().getBrand().getCode(), bs.size(),
                            sumB(bs, PurchaseBill::getTotal), sumB(bs, PurchaseBill::getPaid),
                            bs.stream().map(PurchaseBill::getBillDate).min(Comparator.naturalOrder()).orElse(null));
                }).sorted(Comparator.comparing(Owed::due).reversed()).toList();
        return new OwedReport(customers, customers.stream().map(Owed::due).reduce(ZERO, BigDecimal::add),
                suppliers, suppliers.stream().map(Owed::due).reduce(ZERO, BigDecimal::add));
    }

    private static Owed owed(Long id, String code, String name, String phone, String brand, int n, BigDecimal billed,
                             BigDecimal paid, LocalDate oldest) {
        return new Owed(id, code, name, phone, brand, n, billed, paid, billed.subtract(paid), oldest);
    }

    // ---- overview ----

    public record MonthBar(String month, String label, BigDecimal sales, BigDecimal purchases, BigDecimal received,
                           BigDecimal paidOut) {
    }

    public record Overview(LocalDate today, BigDecimal salesThisMonth, int invoicesThisMonth, BigDecimal purchasesThisMonth,
                           BigDecimal receivedThisMonth, BigDecimal paidOutThisMonth, BigDecimal receivable, BigDecimal payable,
                           BigDecimal stockValue, int items, int lowStock, int outOfStock, int openQuotations,
                           List<ItemView> lowItems, List<SalesRow> recentInvoices, List<SalesRow> openQuotationList,
                           List<MonthBar> months, List<Group> topSuppliers, List<Group> topItems) {
    }

    @Transactional(readOnly = true)
    public Overview overview(AuthUser me, Long brandId) {
        access.require(me, "You cannot see the overview", Menus.PROD_HOME);
        Long scope = access.scope(me, brandId);
        LocalDate today = access.today();
        YearMonth now = YearMonth.from(today);
        LocalDate start = now.minusMonths(5).atDay(1);
        List<SalesDoc> invoices = salesRepository.issued(scope, start, now.atEndOfMonth());
        List<PurchaseBill> bills = purchaseRepository.received(scope, start, now.atEndOfMonth());
        List<TradePayment> pays = paymentRepository.inRange(scope, start, now.atEndOfMonth()).stream().filter(p -> !p.isVoided()).toList();
        List<MonthBar> months = new ArrayList<>();
        for (YearMonth m = now.minusMonths(5); !m.isAfter(now); m = m.plusMonths(1)) {
            YearMonth month = m;
            months.add(new MonthBar(month.toString(), MONTH.format(month),
                    sum(invoices.stream().filter(d -> YearMonth.from(d.getDocDate()).equals(month)).toList(), SalesDoc::getTotal),
                    sumB(bills.stream().filter(b -> YearMonth.from(b.getBillDate()).equals(month)).toList(), PurchaseBill::getTotal),
                    net(pays.stream().filter(p -> p.getParty() == TradeParty.CUSTOMER && YearMonth.from(p.getPaidOn()).equals(month)).toList()),
                    net(pays.stream().filter(p -> p.getParty() == TradeParty.SUPPLIER && YearMonth.from(p.getPaidOn()).equals(month)).toList())));
        }
        MonthBar thisMonth = months.getLast();
        List<ProductItem> items = itemRepository.forBrand(scope).stream().filter(ProductItem::isActive).toList();
        List<ItemView> views = items.stream().map(CatalogService::view).toList();
        List<ItemView> low = views.stream().filter(v -> v.lowStock() || (v.trackStock() && v.onHand().signum() <= 0))
                .sorted(Comparator.comparing(ItemView::onHand)).limit(8).toList();
        List<SalesDoc> quotes = salesRepository.list(scope, SalesDocKind.QUOTATION, today.minusMonths(6), today.plusYears(1)).stream()
                .filter(d -> d.getStatus() == SalesDocStatus.DRAFT || d.getStatus() == SalesDocStatus.SENT).toList();
        OwedReport owed = owed(me, brandId);
        List<SalesRow> recent = salesRepository.list(scope, SalesDocKind.INVOICE, today.minusMonths(3), today.plusYears(1)).stream()
                .limit(6).map(SalesService::row).toList();
        // where the money for goods went in these six months
        List<PurchaseLine> plines = bills.stream().flatMap(b -> b.getLines().stream().map(l -> new PurchaseLine(b.getBillDate(), b.getId(),
                b.getNumber(), b.getSupplierBillNo(), b.getBrand().getCode(), b.getSupplier().getId(), b.getSupplier().getCode(),
                b.getSupplier().getName(), l.getItem().getId(), l.getItemCode(), l.getName(), l.getItem().getType().getName(),
                l.getQuantity(), l.getItem().getUnit(), l.getRate(), l.getAmount()))).toList();
        Function<PurchaseLine, BigDecimal> none = l -> null;
        return new Overview(today, thisMonth.sales(), (int) invoices.stream().filter(d -> YearMonth.from(d.getDocDate()).equals(now)).count(),
                thisMonth.purchases(), thisMonth.received(), thisMonth.paidOut(), owed.receivable(), owed.payable(),
                views.stream().map(ItemView::stockValue).reduce(ZERO, BigDecimal::add), items.size(),
                (int) views.stream().filter(ItemView::lowStock).count(),
                (int) views.stream().filter(v -> v.trackStock() && v.onHand().signum() <= 0).count(), quotes.size(), low, recent,
                quotes.stream().limit(6).map(SalesService::row).toList(), months,
                groups(plines, l -> String.valueOf(l.supplierId()), PurchaseLine::supplierName, PurchaseLine::billId,
                        PurchaseLine::quantity, PurchaseLine::amount, none).stream().limit(6).toList(),
                groups(plines, PurchaseLine::itemCode, l -> l.itemCode() + " · " + l.itemName(), PurchaseLine::billId,
                        PurchaseLine::quantity, PurchaseLine::amount, none).stream().limit(8).toList());
    }

    // ---- Excel ----

    @Transactional(readOnly = true)
    public ExportService.Export excel(AuthUser me, String report, Long brandId, LocalDate from, LocalDate to, Long partyId,
                                      Long itemId, String typeCode, String number) {
        String range = LABEL.format(from) + " to " + LABEL.format(to);
        String note = "Amsidel Company Portal · " + LABEL.format(access.today());
        try (ExcelBook book = new ExcelBook()) {
            switch (report) {
                case "sales" -> {
                    SalesReport r = sales(me, brandId, from, to, partyId, itemId, typeCode, number);
                    ExcelBook.Table t = book.sheet("Invoices", "Invoices, " + range, note,
                            new String[]{"Date", "Invoice", "Brand", "Customer", "Phone", "Total", "Paid", "Due", "Payment"},
                            new int[]{13, 20, 7, 26, 15, 13, 13, 13, 12});
                    r.invoices().forEach(i -> t.row(i.docDate(), i.number(), i.brandCode(), i.customerName(), i.customerPhone(),
                            i.total(), i.paid(), i.due(), i.payStatus()));
                    t.totals(null, r.totals().invoices() + " invoices", null, null, null, r.totals().total(), r.totals().paid(), r.totals().due());
                    ExcelBook.Table l = book.sheet("Lines", "Items sold, " + range, "Line amounts before the bill's discount and GST",
                            new String[]{"Date", "Invoice", "Customer", "Item code", "Item", "Product type", "Size", "Qty", "Unit",
                                    "Rate", "Amount", "Supplier cost", "Margin"},
                            new int[]{13, 20, 24, 14, -28, 22, 10, 8, 8, 12, 13, 13, 13});
                    r.lines().forEach(x -> l.row(x.date(), x.number(), x.customerName(), x.itemCode(), x.itemName(), x.typeName(),
                            x.size(), x.quantity(), x.unit(), x.rate(), x.amount(), x.cost(), x.margin()));
                    l.totals(null, null, null, null, null, null, null, null, null, null, sumOf(r.lines(), SalesLine::amount),
                            r.totals().cost(), r.totals().margin());
                    groupSheet(book, "By customer", "Sales by customer, " + range, "Customer", r.byCustomer(), true);
                    groupSheet(book, "By product type", "Sales by product type, " + range, "Product type", r.byType(), true);
                    groupSheet(book, "By item", "Sales by item, " + range, "Item", r.byItem(), true);
                }
                case "purchases" -> {
                    PurchaseReport r = purchases(me, brandId, from, to, partyId, itemId, typeCode, number);
                    ExcelBook.Table l = book.sheet("Lines", "Items bought, " + range, note,
                            new String[]{"Date", "Bill", "Supplier bill", "Supplier", "Item code", "Item", "Product type", "Qty",
                                    "Unit", "Rate", "Amount"},
                            new int[]{13, 20, 14, 24, 14, -28, 22, 8, 8, 12, 13});
                    r.lines().forEach(x -> l.row(x.date(), x.number(), x.supplierBillNo(), x.supplierName(), x.itemCode(), x.itemName(),
                            x.typeName(), x.quantity(), x.unit(), x.rate(), x.amount()));
                    l.totals(null, r.totals().bills() + " bills", null, null, null, null, null, null, null, null, sumOf(r.lines(), PurchaseLine::amount));
                    groupSheet(book, "By supplier", "Bought by supplier, " + range, "Supplier", r.bySupplier(), false);
                    groupSheet(book, "By product type", "Bought by product type, " + range, "Product type", r.byType(), false);
                    groupSheet(book, "By item", "Bought by item, " + range, "Item", r.byItem(), false);
                    groupSheet(book, "By month", "Bought by month, " + range, "Month", r.byMonth(), false);
                }
                case "stock" -> {
                    StockReport r = stock(me, brandId, from, to);
                    ExcelBook.Table t = book.sheet("Stock", "Stock on " + LABEL.format(access.today()), "Bought / sold / adjusted: " + range,
                            new String[]{"Brand", "Item code", "Item", "Product type", "Supplier", "Unit", "Total in", "Total out",
                                    "On hand", "Bought", "Sold", "Adjusted", "Supplier price", "Stock value", "Showroom price",
                                    "Retail value", "Low stock"},
                            new int[]{7, 14, -28, 20, 22, 8, 10, 10, 10, 10, 10, 10, 12, 13, 12, 13, 9});
                    r.items().forEach(s -> t.row(s.item().brand().code(), s.item().code(), s.item().name(), s.item().typeName(),
                            s.item().supplierName(), s.item().unit(), s.item().totalIn(), s.item().totalOut(), s.item().onHand(),
                            s.boughtInRange(), s.soldInRange(), s.adjustedInRange(), s.item().supplierPrice(), s.item().stockValue(),
                            s.item().showroomPrice(), s.retailValue(), s.item().lowStock() ? "Yes" : ""));
                    t.totals(null, null, r.items().size() + " items", null, null, null, null, null, null, null, null, null, null,
                            r.stockValue(), null, r.retailValue());
                }
                case "payments" -> {
                    PaymentReport r = payments(me, brandId, from, to, null);
                    ExcelBook.Table t = book.sheet("Payments", "Payments, " + range, note,
                            new String[]{"Date", "Brand", "With", "Name", "Bill", "Kind", "Mode", "Amount", "Transaction ID",
                                    "Bank details", "UPI ID", "Remarks", "Recorded by", "Voided", "Void reason"},
                            new int[]{13, 7, 10, 24, 20, 9, 8, 13, 22, -24, 18, -30, 18, 8, -24});
                    r.payments().forEach(p -> t.row(p.paidOn(), p.brandCode(), p.party() == TradeParty.CUSTOMER ? "Customer" : "Supplier",
                            p.partyName(), p.docNumber(), p.kind() == TradePaymentKind.REFUND ? "Refund" : "Payment", p.mode().name(),
                            p.amount(), p.transactionId(), p.bankDetails(), p.upiId(), p.remarks(), p.recordedByName(),
                            p.voided() ? "Yes" : "", p.voidReason()));
                    t.done();
                }
                case "owed" -> {
                    OwedReport r = owed(me, brandId);
                    ExcelBook.Table c = book.sheet("Customers owe", "Customers: still to receive", note,
                            new String[]{"Brand", "Code", "Customer", "Phone", "Invoices", "Billed", "Paid", "Due", "Oldest invoice"},
                            new int[]{7, 12, 26, 15, 9, 13, 13, 13, 13});
                    r.customers().forEach(o -> c.row(o.brandCode(), o.code(), o.name(), o.phone(), o.bills(), o.billed(), o.paid(), o.due(), o.oldestBill()));
                    c.totals(null, null, null, null, null, null, null, r.receivable());
                    ExcelBook.Table s = book.sheet("Owed to suppliers", "Suppliers: still to pay", note,
                            new String[]{"Brand", "Code", "Supplier", "Phone", "Bills", "Billed", "Paid", "Due", "Oldest bill"},
                            new int[]{7, 12, 26, 15, 9, 13, 13, 13, 13});
                    r.suppliers().forEach(o -> s.row(o.brandCode(), o.code(), o.name(), o.phone(), o.bills(), o.billed(), o.paid(), o.due(), o.oldestBill()));
                    s.totals(null, null, null, null, null, null, null, r.payable());
                }
                default -> throw ApiException.badRequest("Unknown report: " + report);
            }
            return new ExportService.Export("Report_" + report + "_" + from + "_to_" + to + ".xlsx", book.bytes());
        }
    }

    private static void groupSheet(ExcelBook book, String name, String title, String what, List<Group> groups, boolean margin) {
        ExcelBook.Table t = margin
                ? book.sheet(name, title, "", new String[]{what, "Bills", "Quantity", "Amount", "Supplier cost", "Margin"}, new int[]{-34, 8, 10, 14, 14, 14})
                : book.sheet(name, title, "", new String[]{what, "Bills", "Quantity", "Amount"}, new int[]{-34, 8, 10, 14});
        for (Group g : groups) {
            if (margin) {
                t.row(g.label(), g.count(), g.quantity(), g.amount(), g.cost(), g.margin());
            } else {
                t.row(g.label(), g.count(), g.quantity(), g.amount());
            }
        }
        t.totals("All", null, null, groups.stream().map(Group::amount).reduce(ZERO, BigDecimal::add));
    }

    // ---- helpers ----

    private void canRead(AuthUser me) {
        access.require(me, "You cannot see reports", Menus.PROD_REPORTS);
    }

    private static void range(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The end date is before the start date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw ApiException.badRequest("Ask for at most three years at a time");
        }
    }

    /** Groups, biggest amount first. */
    private static <T> List<Group> groups(List<T> rows, Function<T, String> key, Function<T, String> label, Function<T, Long> doc,
                                          Function<T, BigDecimal> qty, Function<T, BigDecimal> amount, Function<T, BigDecimal> cost) {
        Map<String, List<T>> by = rows.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
        return by.entrySet().stream().map(e -> {
            List<T> g = e.getValue();
            BigDecimal a = sumOf(g, amount);
            BigDecimal c = g.stream().map(cost).anyMatch(Objects::isNull) ? null : sumOf(g, cost);
            return new Group(e.getKey(), label.apply(g.getFirst()), (int) g.stream().map(doc).distinct().count(), sumOf(g, qty), a,
                    c, c == null ? null : a.subtract(c));
        }).sorted(Comparator.comparing(Group::amount).reversed()).toList();
    }

    private static <T> BigDecimal sumOf(List<T> rows, Function<T, BigDecimal> f) {
        return rows.stream().map(f).filter(Objects::nonNull).reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<SalesDoc> docs, Function<SalesDoc, BigDecimal> f) {
        return sumOf(docs, f);
    }

    private static BigDecimal sumB(List<PurchaseBill> bills, Function<PurchaseBill, BigDecimal> f) {
        return sumOf(bills, f);
    }

    private static BigDecimal net(List<TradePayment> pays) {
        return pays.stream().map(p -> p.getKind() == TradePaymentKind.REFUND ? p.getAmount().negate() : p.getAmount())
                .reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal total(List<TradePayment> pays, TradeParty party, TradePaymentKind kind) {
        return pays.stream().filter(p -> p.getParty() == party && p.getKind() == kind).map(TradePayment::getAmount)
                .reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }
}
