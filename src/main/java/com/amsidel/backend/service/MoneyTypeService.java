package com.amsidel.backend.service;

import com.amsidel.backend.dto.AccountDtos.TypeBody;
import com.amsidel.backend.dto.AccountDtos.TypeView;
import com.amsidel.backend.entity.MoneyCategory;
import com.amsidel.backend.entity.MoneyType;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.MoneyEntryRepository;
import com.amsidel.backend.repository.MoneyTypeRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The kinds of other income and expense (Income & expenses): everyone with the accounts menus sees them; owners and
 * admins add, rename, switch off and (when never used) delete them. The ones the portal started with are added once;
 * the discount (invoice write-off) always stays an expense, outside funding, and in use.
 */
@Service
@RequiredArgsConstructor
public class MoneyTypeService {

    private final MoneyTypeRepository repository;
    private final MoneyEntryRepository entryRepository;
    private final TradeAccess access;

    /** The kinds the portal started with, with their codes (entries made before keep them). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Transactional
    public void seed() {
        int i = 0;
        for (MoneyCategory c : MoneyCategory.values()) {
            i += 10;
            if (!repository.existsById(c.name())) {
                MoneyType t = new MoneyType();
                t.setCode(c.name());
                t.setLabel(c.getLabel());
                t.setIncome(c.isIncome());
                t.setFunding(c.isFunding());
                t.setBuiltIn(true);
                t.setSortOrder(i);
                t.setUpdatedAt(Instant.now());
                repository.save(t);
            }
        }
    }

    private void canSee(AuthUser me) {
        access.require(me, "You cannot see income and expenses", Menus.PROD_LEDGER, Menus.PROD_RECONCILE);
    }

    private void canChange(AuthUser me) {
        canSee(me);
        access.requireAdmin(me, "Only owners and admins change the income and expense categories");
    }

    /** Money in first, then money out, each in their order. */
    @Transactional(readOnly = true)
    public List<TypeView> list(AuthUser me) {
        canSee(me);
        return repository.findAllByOrderByIncomeDescSortOrderAscLabelAsc().stream().map(this::view).toList();
    }

    /** The kind an entry is saved with: it must exist, and a new entry needs an active one. */
    @Transactional(readOnly = true)
    public MoneyType forEntry(String code, MoneyType current) {
        MoneyType t = repository.findById(code).orElseThrow(() -> ApiException.badRequest("Choose what it is for"));
        if (!t.isActive() && (current == null || !current.getCode().equals(t.getCode()))) {
            throw ApiException.badRequest("\"" + t.getLabel() + "\" is switched off; choose another category");
        }
        return t;
    }

    @Transactional
    public TypeView create(AuthUser me, TypeBody body) {
        canChange(me);
        if (body.getIncome() == null) {
            throw ApiException.badRequest("Say whether it is income or an expense");
        }
        String label = body.getLabel().strip();
        if (repository.existsByLabelIgnoreCaseAndIncome(label, body.getIncome())) {
            throw ApiException.conflict("There is already " + (body.getIncome() ? "an income" : "an expense") + " category \"" + label + "\"");
        }
        MoneyType t = new MoneyType();
        t.setCode(newCode(label));
        t.setLabel(label);
        t.setIncome(body.getIncome());
        t.setFunding(Boolean.TRUE.equals(body.getFunding()));
        t.setActive(body.getActive() == null || body.getActive());
        t.setSortOrder(body.getSortOrder() != null ? body.getSortOrder()
                : repository.findAll().stream().filter(x -> x.isIncome() == t.isIncome()).mapToInt(MoneyType::getSortOrder).max().orElse(0) + 10);
        t.setUpdatedAt(Instant.now());
        t.setUpdatedBy(access.me(me));
        return view(repository.save(t));
    }

    @Transactional
    public TypeView update(AuthUser me, String code, TypeBody body) {
        canChange(me);
        MoneyType t = repository.findById(code).orElseThrow(() -> new ResourceNotFoundException("Category not found: " + code));
        String label = body.getLabel().strip();
        boolean income = body.getIncome() == null ? t.isIncome() : body.getIncome();
        if (!label.equalsIgnoreCase(t.getLabel()) || income != t.isIncome()) {
            if (repository.existsByLabelIgnoreCaseAndIncome(label, income)) {
                throw ApiException.conflict("There is already " + (income ? "an income" : "an expense") + " category \"" + label + "\"");
            }
        }
        boolean used = entryRepository.usesCategory(code);
        if (income != t.isIncome() && used) {
            throw ApiException.conflict("\"" + t.getLabel() + "\" is used by entries, so it stays " + (t.isIncome() ? "income" : "an expense")
                    + "; add a new category instead");
        }
        boolean funding = body.getFunding() == null ? t.isFunding() : body.getFunding();
        boolean active = body.getActive() == null ? t.isActive() : body.getActive();
        if (t.isDiscount() && (income || funding || !active)) {
            throw ApiException.badRequest("The discount category is used for invoice write-offs: it stays an active expense (only its name can change)");
        }
        t.setLabel(label);
        t.setIncome(income);
        t.setFunding(funding);
        t.setActive(active);
        if (body.getSortOrder() != null) {
            t.setSortOrder(body.getSortOrder());
        }
        t.setUpdatedAt(Instant.now());
        t.setUpdatedBy(access.me(me));
        return view(t);
    }

    /** Only when no entry uses it; otherwise switch it off. */
    @Transactional
    public void delete(AuthUser me, String code) {
        canChange(me);
        MoneyType t = repository.findById(code).orElseThrow(() -> new ResourceNotFoundException("Category not found: " + code));
        if (t.isDiscount()) {
            throw ApiException.badRequest("The discount category is used for invoice write-offs and cannot be deleted");
        }
        if (entryRepository.usesCategory(code)) {
            throw ApiException.conflict("\"" + t.getLabel() + "\" is used by entries: switch it off instead, so it is not offered any more");
        }
        repository.delete(t);
    }

    /** "Office supplies" -> OFFICE_SUPPLIES (at most 20 letters), made unique. */
    private String newCode(String label) {
        String base = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
        if (base.isEmpty()) {
            base = "CATEGORY";
        }
        base = base.length() > 16 ? base.substring(0, 16).replaceAll("_+$", "") : base;
        String code = base;
        for (int n = 2; repository.existsById(code); n++) {
            code = base + "_" + n;
        }
        return code;
    }

    private TypeView view(MoneyType t) {
        return new TypeView(t.getCode(), t.getLabel(), t.isIncome(), t.isFunding(), t.isActive(), t.isBuiltIn(), t.isDiscount(),
                entryRepository.usesCategory(t.getCode()), t.getSortOrder(), t.getUpdatedAt(), t.getUpdatedBy() == null ? null : t.getUpdatedBy().getName());
    }
}
