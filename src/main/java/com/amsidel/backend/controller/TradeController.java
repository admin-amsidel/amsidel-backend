package com.amsidel.backend.controller;

import com.amsidel.backend.dto.TradeDtos.*;
import com.amsidel.backend.entity.SalesDocKind;
import com.amsidel.backend.entity.TradeParty;
import com.amsidel.backend.entity.TradePayment;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.BrandLogoRepository;
import com.amsidel.backend.repository.TradePaymentRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * The product portal's API (/api/trade, never public, unlike the website's /api/products): brands, product types,
 * items and stock, suppliers, customers, quotations, invoices, purchase bills, payments, PDFs, reports and Excel.
 * Every call needs one of the product portal's menus; the services check the menu each action needs and the brand.
 */
@RestController
@RequestMapping("/api/trade")
@RequiredArgsConstructor
@Checkpoint({Menus.PROD_HOME, Menus.PROD_ITEMS, Menus.PROD_TYPES, Menus.PROD_SUPPLIERS, Menus.PROD_PURCHASES,
        Menus.PROD_CUSTOMERS, Menus.PROD_QUOTES, Menus.PROD_INVOICES, Menus.PROD_REPORTS, Menus.PROD_PROJECTS,
        Menus.PROD_LEDGER, Menus.PROD_RECONCILE})
public class TradeController {

    private final TradeAccess access;
    private final CatalogService catalog;
    private final PartyService parties;
    private final SalesService sales;
    private final PurchaseService purchases;
    private final TradeReportService reports;
    private final SalesPdf pdf;
    private final BrandLogoRepository logos;
    private final com.amsidel.backend.repository.BrandUpiQrRepository upiQrs;
    private final TradePaymentRepository paymentRepository;
    private final TradeHistoryLog historyLog;
    private final ProjectService projects;
    @org.springframework.beans.factory.annotation.Value("${app.leave.zone}")
    private java.time.ZoneId zone;

    // ---- brands ----

    /** The brands I may work with: for every product-portal page (Customer & supplier mail and Pay bill too). */
    @GetMapping("/brands")
    @Checkpoint({Menus.PROD_HOME, Menus.PROD_ITEMS, Menus.PROD_TYPES, Menus.PROD_SUPPLIERS, Menus.PROD_PURCHASES,
            Menus.PROD_CUSTOMERS, Menus.PROD_QUOTES, Menus.PROD_INVOICES, Menus.PROD_REPORTS, Menus.PROD_PROJECTS,
            Menus.PROD_LEDGER, Menus.PROD_RECONCILE, Menus.PROD_MAIL, Menus.PROD_PAY, Menus.PROD_PROJECT_STATUS})
    @Transactional(readOnly = true)
    public List<BrandRef> brands(@AuthenticationPrincipal AuthUser me) {
        return access.brands(me).stream().map(BrandRef::of).toList();
    }

    // ---- product types and items ----

    @GetMapping("/types")
    public List<TypeView> types(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return catalog.types(me, brandId);
    }

    @PostMapping("/types")
    @ResponseStatus(HttpStatus.CREATED)
    public TypeView addType(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody TypeBody body) {
        return catalog.saveType(me, null, body);
    }

    @PutMapping("/types/{id}")
    public TypeView saveType(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody TypeBody body) {
        return catalog.saveType(me, id, body);
    }

    @GetMapping("/items")
    public List<ItemView> items(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return catalog.items(me, brandId);
    }

    @GetMapping("/items/{id}")
    public ItemDetail item(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return catalog.item(me, id);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemView addItem(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody ItemBody body) {
        return catalog.saveItem(me, null, body);
    }

    @PutMapping("/items/{id}")
    public ItemView saveItem(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody ItemBody body) {
        return catalog.saveItem(me, id, body);
    }

    /** Opening stock, or stock added / removed by hand. */
    @PostMapping("/items/{id}/stock")
    public ItemDetail adjustStock(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody StockBody body) {
        return catalog.adjust(me, id, body);
    }

    // ---- suppliers ----

    @GetMapping("/suppliers")
    public List<SupplierView> suppliers(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return parties.suppliers(me, brandId);
    }

    @GetMapping("/suppliers/{id}")
    public SupplierView supplier(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return parties.supplier(me, id);
    }

    @GetMapping("/suppliers/{id}/bills")
    public List<PurchaseRow> supplierBills(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return purchases.forSupplier(me, id);
    }

    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierView addSupplier(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody SupplierBody body) {
        return parties.saveSupplier(me, null, body);
    }

    @PutMapping("/suppliers/{id}")
    public SupplierView saveSupplier(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody SupplierBody body) {
        return parties.saveSupplier(me, id, body);
    }

    // ---- customers ----

    @GetMapping("/customers")
    public List<CustomerView> customers(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return parties.customers(me, brandId);
    }

    @GetMapping("/customers/{id}")
    public CustomerView customer(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return parties.customer(me, id);
    }

    /** The customer's quotations and invoices, newest first. */
    @GetMapping("/customers/{id}/documents")
    public List<SalesRow> customerDocuments(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return sales.forCustomer(me, id);
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerView addCustomer(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CustomerBody body) {
        return parties.saveCustomer(me, null, body);
    }

    @PutMapping("/customers/{id}")
    public CustomerView saveCustomer(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody CustomerBody body) {
        return parties.saveCustomer(me, id, body);
    }

    // ---- quotations and invoices ----

    @GetMapping("/sales")
    public List<SalesRow> salesList(@AuthenticationPrincipal AuthUser me, @RequestParam SalesDocKind kind,
                                    @RequestParam(required = false) Long brandId,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return sales.list(me, kind, brandId, from, to);
    }

    @GetMapping("/sales/{id}")
    public SalesView salesDoc(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return sales.get(me, id);
    }

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.CREATED)
    public SalesView addSalesDoc(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody SalesBody body) {
        return sales.create(me, body);
    }

    @PutMapping("/sales/{id}")
    public SalesView saveSalesDoc(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody SalesBody body) {
        return sales.update(me, id, body);
    }

    @DeleteMapping("/sales/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSalesDoc(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        sales.delete(me, id);
    }

    /** Quotations: SENT, ACCEPTED, REJECTED or DRAFT. */
    @PostMapping("/sales/{id}/status")
    public SalesView status(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody StatusBody body) {
        return sales.setStatus(me, id, body.getStatus());
    }

    /** A draft invoice from this quotation. */
    @PostMapping("/sales/{id}/invoice")
    @ResponseStatus(HttpStatus.CREATED)
    public SalesView makeInvoice(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return sales.makeInvoice(me, id);
    }

    @PostMapping("/sales/{id}/issue")
    public SalesView issue(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return sales.issue(me, id);
    }

    @PostMapping("/sales/{id}/cancel")
    public SalesView cancelInvoice(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody ReasonBody body) {
        return sales.cancel(me, id, body.getReason());
    }

    @PostMapping("/sales/{id}/payments")
    public SalesView receive(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody PaymentBody body) {
        return sales.addPayment(me, id, body);
    }

    @GetMapping("/sales/{id}/pdf")
    public ResponseEntity<byte[]> salesPdf(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        SalesView d = sales.get(me, id);
        byte[] logo = d.company().logoUpdatedAt() == null ? null
                : logos.findById(d.company().brandId()).map(l -> l.getContent()).orElse(null);
        // the brand's uploaded UPI QR: printed as a clean black-on-white QR of the same link when it could be read
        // (some app QRs are white on black or have a logo, which not every scanner or printer handles), else as uploaded
        byte[] qr = d.company().upiQrUpdatedAt() == null ? null
                : upiQrs.findById(d.company().brandId())
                        .map(q -> q.getPayload() != null ? UpiQr.png(q.getPayload(), 360) : q.getContent()).orElse(null);
        String name = (d.kind() == SalesDocKind.INVOICE ? "Invoice-" : "Quotation-")
                + (d.number() == null ? "draft-" + d.id() : d.number()) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(pdf.render(d, logo, qr));
    }

    // ---- purchase bills ----

    @GetMapping("/purchases")
    public List<PurchaseRow> purchaseList(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return purchases.list(me, brandId, from, to);
    }

    @GetMapping("/purchases/{id}")
    public PurchaseView purchase(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return purchases.get(me, id);
    }

    @PostMapping("/purchases")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseView addPurchase(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody PurchaseBody body) {
        return purchases.create(me, body);
    }

    @PutMapping("/purchases/{id}")
    public PurchaseView savePurchase(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody PurchaseBody body) {
        return purchases.update(me, id, body);
    }

    @DeleteMapping("/purchases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePurchase(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        purchases.delete(me, id);
    }

    @PostMapping("/purchases/{id}/receive")
    public PurchaseView receiveBill(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                    @RequestBody(required = false) ReceiveBody body) {
        return purchases.receive(me, id, body == null || body.getUpdatePrices() == null || body.getUpdatePrices());
    }

    @PostMapping("/purchases/{id}/cancel")
    public PurchaseView cancelBill(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody ReasonBody body) {
        return purchases.cancel(me, id, body.getReason());
    }

    @PostMapping("/purchases/{id}/payments")
    public PurchaseView pay(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody PaymentBody body) {
        return purchases.addPayment(me, id, body);
    }

    // ---- payments ----

    /** Voids a payment recorded by mistake; returns the invoice or purchase bill it was on. */
    @PostMapping("/payments/{id}/void")
    @Transactional
    public Object voidPayment(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody ReasonBody body) {
        TradePayment p = paymentRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id));
        return p.getParty() == TradeParty.CUSTOMER ? sales.voidPayment(me, p, body.getReason())
                : purchases.voidPayment(me, p, body.getReason());
    }

    // ---- projects ----

    @GetMapping("/projects")
    public List<ProjectRow> projectList(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return projects.list(me, brandId);
    }

    @GetMapping("/projects/{id}")
    public ProjectView project(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return projects.get(me, id);
    }

    /** Staff who can own or work on a project of the brand. */
    @GetMapping("/people")
    public List<PersonRef> people(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return projects.people(me, brandId);
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView addProject(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody ProjectBody body) {
        return projects.create(me, body);
    }

    @PutMapping("/projects/{id}")
    public ProjectView saveProject(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody ProjectBody body) {
        return projects.update(me, id, body);
    }

    @PostMapping("/projects/{id}/status")
    public ProjectView projectStatus(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody ProjectStatusBody body) {
        return projects.setStatus(me, id, body.getStatus(), body.getNote());
    }

    @PostMapping("/projects/{id}/settle")
    public ProjectView settleProject(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @RequestBody(required = false) CommentBody body) {
        return projects.settle(me, id, body == null ? null : body.getText());
    }

    @PostMapping("/projects/{id}/reopen")
    public ProjectView reopenProject(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @RequestBody(required = false) CommentBody body) {
        return projects.reopen(me, id, body == null ? null : body.getText());
    }

    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        projects.delete(me, id);
    }

    @PostMapping("/projects/{id}/comments")
    public ProjectView commentProject(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody CommentBody body) {
        return projects.comment(me, id, body.getText());
    }

    @PostMapping("/projects/{id}/tasks")
    public ProjectView addTask(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody TaskBody body) {
        return projects.addTask(me, id, body);
    }

    @PutMapping("/project-tasks/{taskId}")
    public ProjectView saveTask(@AuthenticationPrincipal AuthUser me, @PathVariable Long taskId, @Valid @RequestBody TaskBody body) {
        return projects.updateTask(me, taskId, body);
    }

    @PostMapping("/project-tasks/{taskId}/status")
    public ProjectView taskStatus(@AuthenticationPrincipal AuthUser me, @PathVariable Long taskId, @Valid @RequestBody ProjectStatusBody body) {
        return projects.setTaskStatus(me, taskId, body.getStatus(), body.getNote());
    }

    @DeleteMapping("/project-tasks/{taskId}")
    public ProjectView deleteTask(@AuthenticationPrincipal AuthUser me, @PathVariable Long taskId) {
        return projects.deleteTask(me, taskId);
    }

    // ---- history ----

    /** One record's history (QUOTATION, INVOICE, PURCHASE, ITEM, CUSTOMER, SUPPLIER, PRODUCT_TYPE), newest first. */
    @GetMapping("/history/{type}/{id}")
    public List<HistoryView> history(@AuthenticationPrincipal AuthUser me, @PathVariable com.amsidel.backend.entity.TradeSubject type,
                                     @PathVariable Long id) {
        return historyLog.forRecord(me, type, id);
    }

    /** Who did what in the product portal, from..to: the Activity report. */
    @GetMapping("/history")
    public List<HistoryView> activity(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        access.require(me, "You cannot see the activity report", com.amsidel.backend.security.Menus.PROD_REPORTS);
        return historyLog.activity(me, brandId, from, to, zone);
    }

    // ---- overview and reports ----

    @GetMapping("/overview")
    public TradeReportService.Overview overview(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return reports.overview(me, brandId);
    }

    @GetMapping("/reports/sales")
    public TradeReportService.SalesReport salesReport(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId,
                                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @RequestParam(required = false) Long customerId,
                                                      @RequestParam(required = false) Long itemId,
                                                      @RequestParam(required = false) String type,
                                                      @RequestParam(required = false) String number) {
        return reports.sales(me, brandId, from, to, customerId, itemId, type, number);
    }

    @GetMapping("/reports/purchases")
    public TradeReportService.PurchaseReport purchaseReport(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId,
                                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                            @RequestParam(required = false) Long supplierId,
                                                            @RequestParam(required = false) Long itemId,
                                                            @RequestParam(required = false) String type,
                                                            @RequestParam(required = false) String number) {
        return reports.purchases(me, brandId, from, to, supplierId, itemId, type, number);
    }

    @GetMapping("/reports/stock")
    public TradeReportService.StockReport stockReport(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId,
                                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.stock(me, brandId, from, to);
    }

    @GetMapping("/reports/payments")
    public TradeReportService.PaymentReport paymentReport(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId,
                                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                          @RequestParam(required = false) TradeParty party) {
        return reports.payments(me, brandId, from, to, party);
    }

    @GetMapping("/reports/owed")
    public TradeReportService.OwedReport owedReport(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return reports.owed(me, brandId);
    }

    /** sales, purchases, stock, payments or owed, as an Excel file (partyId = the customer or supplier). */
    @GetMapping("/reports/{report}/excel")
    public ResponseEntity<byte[]> reportExcel(@AuthenticationPrincipal AuthUser me, @PathVariable String report,
                                              @RequestParam(required = false) Long brandId,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(required = false) Long partyId,
                                              @RequestParam(required = false) Long itemId,
                                              @RequestParam(required = false) String type,
                                              @RequestParam(required = false) String number) {
        return AttendanceController.excel(reports.excel(me, report, brandId, from, to, partyId, itemId, type, number));
    }
}
