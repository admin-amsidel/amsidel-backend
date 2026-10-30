/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.config;

import com.amsidel.backend.entity.AccessSubject;
import com.amsidel.backend.entity.MenuAccess;
import com.amsidel.backend.entity.MenuItem;
import com.amsidel.backend.repository.DesignationRepository;
import com.amsidel.backend.repository.MenuAccessRepository;
import com.amsidel.backend.repository.MenuItemRepository;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.MenuAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps the menus table in step with {@link Menus} on every start (names, checkpoints, paths), and on the first
 * start gives access as the portal worked before menu access existed: admins every menu; managers their own
 * pages plus leave approvals and team attendance; employees their own pages. Owners always have every menu.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuBootstrap implements ApplicationRunner {

    private final MenuItemRepository menuRepository;
    private final MenuAccessRepository accessRepository;
    private final MenuAccessService menuAccessService;
    private final DesignationRepository designationRepository;
    private final TransactionTemplate transactions;

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(status -> {
            boolean firstStart = accessRepository.count() == 0;
            Set<String> added = syncMenus();
            if (firstStart) {
                defaults();
            } else {
                grantNewMenus(added);
            }
        });
        menuAccessService.reload();
    }

    /** Returns the codes of menus that were not in the table before. */
    private Set<String> syncMenus() {
        Map<String, MenuItem> existing = menuRepository.findAll().stream()
                .collect(Collectors.toMap(MenuItem::getCode, m -> m));
        Set<String> added = new java.util.HashSet<>();
        Set<String> known = Menus.ALL.stream().map(Menus.Def::code).collect(Collectors.toSet());
        existing.values().stream().filter(m -> !known.contains(m.getCode())).forEach(menuRepository::delete);
        menuRepository.flush();
        int order = 0;
        for (Menus.Def d : Menus.ALL) {
            MenuItem m = existing.get(d.code());
            if (m == null) {
                m = new MenuItem();
                added.add(d.code());
            }
            m.setCode(d.code());
            m.setName(d.name());
            m.setDescription(d.description());
            m.setCheckpoint(d.checkpoint());
            m.setParentCode(d.parent());
            m.setPath(d.path());
            m.setIcon(d.icon());
            m.setSortOrder(order += 10);
            menuRepository.save(m);
        }
        return added;
    }

    /** A menu new to this database goes to the user types listed in Menus.NEW_MENU_ROLES. */
    private void grantNewMenus(Set<String> added) {
        for (String code : added) {
            Menus.ALL.stream().filter(d -> d.code().equals(code)).findFirst().ifPresent(d -> {
                for (String role : Menus.NEW_MENU_ROLES.getOrDefault(code, List.of())) {
                    accessRepository.save(MenuAccess.of(AccessSubject.ROLE, role, String.valueOf(d.checkpoint())));
                    log.info("New menu {} given to {}", d.name(), role);
                }
                grantDesignations(d);
            });
        }
    }

    /** Gives the menu to the designations listed in Menus.NEW_MENU_DESIGNATIONS that exist here (key = designation id). */
    private void grantDesignations(Menus.Def d) {
        for (String levelCode : Menus.NEW_MENU_DESIGNATIONS.getOrDefault(d.code(), List.of())) {
            designationRepository.findAll().stream().filter(x -> levelCode.equals(x.getLevelCode())).findFirst().ifPresent(x -> {
                accessRepository.save(MenuAccess.of(AccessSubject.DESIGNATION, String.valueOf(x.getId()), String.valueOf(d.checkpoint())));
                log.info("New menu {} given to {}", d.name(), x.getName());
            });
        }
    }

    private void defaults() {
        grant("OWNER", List.of(MenuAccess.ALL));
        grant("ADMIN", List.of(MenuAccess.ALL));
        grant("MANAGER", List.of("1001", "1002", "2001", "2002", "3001", "6001", "6002", "8000"));
        grant("EMPLOYEE", List.of("1001", "2001", "3001", "6001", "8000"));
        Menus.ALL.forEach(this::grantDesignations);
        log.info("Created the default menu access for each user type");
    }

    private void grant(String role, List<String> checkpoints) {
        checkpoints.forEach(c -> accessRepository.save(MenuAccess.of(AccessSubject.ROLE, role, c)));
    }
}
