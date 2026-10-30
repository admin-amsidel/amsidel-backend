/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.UserType;
import com.amsidel.backend.dto.MenuAccessEntry;
import com.amsidel.backend.dto.MenuNode;
import com.amsidel.backend.dto.MyAccess;
import com.amsidel.backend.entity.AccessSubject;
import com.amsidel.backend.entity.MenuAccess;
import com.amsidel.backend.entity.MenuItem;
import com.amsidel.backend.entity.Role;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.repository.DesignationRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.MenuAccessRepository;
import com.amsidel.backend.repository.MenuItemRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.function.Predicate;

/**
 * Which menus each person may open, from the menus and menu_access tables. A person with rows of their own gets
 * exactly those; anyone else gets everything given to their user type (role) plus everything given to their
 * designation. A menu's checkpoint also gives its sub-menus; "*" gives all. Anyone with people reporting to them
 * also gets the reporting-line team menus (Menus.FOR_TEAM_LEADS). Owners always have everything; someone with
 * nothing at all is told no permission was granted. Both tables are small and kept in memory, so checking access costs no database
 * round trip; saving through this service refreshes the copy. (With more than one server running, the others
 * pick up a change when they restart.)
 */
@Service
@RequiredArgsConstructor
public class MenuAccessService {

    private final MenuItemRepository menuRepository;
    private final MenuAccessRepository accessRepository;
    private final DesignationRepository designationRepository;
    private final EmployeeRepository employeeRepository;
    private final com.amsidel.backend.repository.UserTypeRepository userTypeRepository;

    private volatile Snapshot snapshot;

    /** The tables as loaded: menus in display order, children per code, and grants per "TYPE:key". */
    private record Snapshot(List<MenuItem> menus, Map<Integer, MenuItem> byCheckpoint, Map<String, List<MenuItem>> children,
                            Map<String, Set<String>> grants) {
    }

    /** The menu codes a person may open, or null for every menu. */
    public Set<String> codesOf(AuthUser me) {
        if (me.role() == Role.OWNER) {
            return null;
        }
        Snapshot s = snapshot();
        Set<String> own = s.grants().get(key(AccessSubject.EMPLOYEE, me.id().toString()));
        Set<String> given;
        if (own != null && !own.isEmpty()) {
            // set for this person: only that counts
            given = own;
        } else {
            // the user type's menus (a built-in type's key is the role name)
            given = new HashSet<>(s.grants().getOrDefault(key(AccessSubject.ROLE, me.userType() != null ? me.userType() : me.role().name()), Set.of()));
            if (me.designationId() != null) {
                given.addAll(s.grants().getOrDefault(key(AccessSubject.DESIGNATION, me.designationId().toString()), Set.of()));
            }
        }
        if (given.contains(MenuAccess.ALL)) {
            return null;
        }
        Set<String> codes = new HashSet<>();
        for (String checkpoint : given) {
            MenuItem menu = s.byCheckpoint().get(parse(checkpoint));
            if (menu != null) {
                addWithChildren(s, menu, codes);
            }
        }
        if (me.leadsTeam()) {
            // whoever has people reporting to them sets their goals and rates them, whatever their user type
            codes.addAll(Menus.FOR_TEAM_LEADS);
        }
        return codes;
    }

    /** May this person open any of these menus? */
    public boolean can(AuthUser me, String... codes) {
        Set<String> mine = codesOf(me);
        return mine == null || Arrays.stream(codes).anyMatch(mine::contains);
    }

    /** My menus, or 403 "No permission granted" when nothing is given to me, my user type or my designation. */
    public MyAccess myAccess(AuthUser me) {
        Set<String> codes = codesOf(me);
        if (codes != null && codes.isEmpty()) {
            throw ApiException.forbidden("No permission granted. Ask your admin to give you access to the portal menus.");
        }
        Snapshot s = snapshot();
        Predicate<MenuItem> allowed = m -> codes == null || codes.contains(m.getCode());
        List<String> list = s.menus().stream().filter(allowed).map(MenuItem::getCode).toList();
        return new MyAccess(codes == null, list, tree(s, allowed));
    }

    /** Every menu, for the access screen. */
    public List<MenuNode> menus() {
        return tree(snapshot(), m -> true);
    }

    /** Every person, user type and designation that has access rows. */
    @Transactional(readOnly = true)
    public List<MenuAccessEntry> entries() {
        Map<String, MenuAccessEntry> byKey = new TreeMap<>();
        for (MenuAccess a : accessRepository.findAll()) {
            byKey.computeIfAbsent(key(a.getSubjectType(), a.getSubjectKey()),
                    k -> new MenuAccessEntry(a.getSubjectType(), a.getSubjectKey(), label(a.getSubjectType(), a.getSubjectKey()),
                            new ArrayList<>())).checkpoints().add(a.getCheckpoint());
        }
        byKey.values().forEach(e -> e.checkpoints().sort(Comparator.naturalOrder()));
        return List.copyOf(byKey.values());
    }

    /** Replaces one person's, user type's or designation's checkpoints (a person with none falls back to the others). */
    @Transactional
    public MenuAccessEntry save(AccessSubject type, String key, List<String> checkpoints) {
        String subject = checkSubject(type, key);
        TreeSet<String> clean = new TreeSet<>();
        for (String c : checkpoints) {
            String v = c.strip();
            if (!MenuAccess.ALL.equals(v) && !snapshot().byCheckpoint().containsKey(parse(v))) {
                throw ApiException.badRequest("There is no menu with checkpoint " + v);
            }
            clean.add(v);
        }
        if (clean.contains(MenuAccess.ALL)) {
            clean.retainAll(Set.of(MenuAccess.ALL));
        }
        accessRepository.deleteSubject(type, subject);
        accessRepository.flush();
        accessRepository.saveAll(clean.stream().map(c -> MenuAccess.of(type, subject, c)).toList());
        refreshAfterCommit();
        return new MenuAccessEntry(type, subject, label(type, subject), new ArrayList<>(clean));
    }

    /** A user type or designation removed: its menu access goes too. */
    @Transactional
    public void forget(AccessSubject type, String key) {
        accessRepository.deleteSubject(type, key);
        refreshAfterCommit();
    }

    /** Reads both tables again (after start-up sync or a change). */
    @Transactional(readOnly = true)

    public synchronized void reload() {
        List<MenuItem> menus = new ArrayList<>(menuRepository.findAll());
        menus.sort(Comparator.comparingInt(MenuItem::getSortOrder));
        Map<Integer, MenuItem> byCheckpoint = new HashMap<>();
        Map<String, List<MenuItem>> children = new HashMap<>();
        for (MenuItem m : menus) {
            byCheckpoint.put(m.getCheckpoint(), m);
            if (m.getParentCode() != null) {
                children.computeIfAbsent(m.getParentCode(), k -> new ArrayList<>()).add(m);
            }
        }
        Map<String, Set<String>> grants = new HashMap<>();
        for (MenuAccess a : accessRepository.findAll()) {
            grants.computeIfAbsent(key(a.getSubjectType(), a.getSubjectKey()), k -> new HashSet<>()).add(a.getCheckpoint());
        }
        snapshot = new Snapshot(List.copyOf(menus), Map.copyOf(byCheckpoint), Map.copyOf(children), Map.copyOf(grants));
    }

    // ---- helpers ----

    private Snapshot snapshot() {
        Snapshot s = snapshot;
        if (s == null) {
            reload();
            s = snapshot;
        }
        return s;
    }

    private String checkSubject(AccessSubject type, String key) {
        if (type == AccessSubject.ROLE) {
            String code = key.strip().toUpperCase(Locale.ROOT);
            UserType t = userTypeRepository.findByCode(code).orElse(null);
            if (t == null) {
                try {
                    Role.valueOf(code);
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("Unknown user type: " + key);
                }
            }
            if (Role.OWNER.name().equals(code) || t != null && t.getBaseRole() == Role.OWNER) {
                throw ApiException.badRequest("Owners always have every menu");
            }
            return code;
        }
        String what = type == AccessSubject.EMPLOYEE ? "employee" : "designation";
        long id;
        try {
            id = Long.parseLong(key.strip());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Unknown " + what + ": " + key);
        }
        if (type == AccessSubject.EMPLOYEE) {
            Employee e = employeeRepository.findById(id)
                    .orElseThrow(() -> ApiException.badRequest("Unknown employee: " + key));
            if (e.getRole() == Role.OWNER) {
                throw ApiException.badRequest("Owners always have every menu");
            }
        } else if (!designationRepository.existsById(id)) {
            throw ApiException.badRequest("Unknown designation: " + key);
        }
        return Long.toString(id);
    }

    /** "Name · user ID · employee number" for a person; null otherwise. */
    private String label(AccessSubject type, String key) {
        if (type != AccessSubject.EMPLOYEE) {
            return null;
        }
        try {
            return employeeRepository.findById(Long.parseLong(key))
                    .map(e -> e.getName() + " · " + e.getUserId() + " · " + e.getEmpNumber())
                    .orElse("Employee " + key);
        } catch (NumberFormatException e) {
            return key;
        }
    }

    private void refreshAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reload();
                }
            });
        } else {
            reload();
        }
    }

    private static void addWithChildren(Snapshot s, MenuItem menu, Set<String> codes) {
        if (codes.add(menu.getCode())) {
            for (MenuItem child : s.children().getOrDefault(menu.getCode(), List.of())) {
                addWithChildren(s, child, codes);
            }
        }
    }

    /** Top-level menus with their allowed sub-menus; a menu shows when it or one of its sub-menus is allowed. */
    private static List<MenuNode> tree(Snapshot s, Predicate<MenuItem> allowed) {
        List<MenuNode> out = new ArrayList<>();
        for (MenuItem m : s.menus()) {
            if (m.getParentCode() == null) {
                MenuNode node = node(s, m, allowed);
                if (node != null) {
                    out.add(node);
                }
            }
        }
        return out;
    }

    private static MenuNode node(Snapshot s, MenuItem m, Predicate<MenuItem> allowed) {
        List<MenuNode> children = new ArrayList<>();
        for (MenuItem c : s.children().getOrDefault(m.getCode(), List.of())) {
            MenuNode child = node(s, c, allowed);
            if (child != null) {
                children.add(child);
            }
        }
        if (!allowed.test(m) && children.isEmpty()) {
            return null;
        }
        return new MenuNode(m.getCode(), m.getName(), m.getDescription(), m.getCheckpoint(), m.getPath(), m.getIcon(), children);
    }

    private static String key(AccessSubject type, String key) {
        return type.name() + ":" + key;
    }

    private static Integer parse(String checkpoint) {
        try {
            return Integer.valueOf(checkpoint.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
