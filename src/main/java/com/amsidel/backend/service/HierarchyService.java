package com.amsidel.backend.service;

import com.amsidel.backend.dto.OrgNode;
import com.amsidel.backend.dto.ReportingLine;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.EmployeePhoto;
import com.amsidel.backend.entity.Role;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeePhotoRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The reporting hierarchy built from "reports to": a person's line up to the top, their direct reports, and
 * the whole org chart. Everyone sees their own line; managers also see the lines of people below them;
 * owners and admins see everyone in their scope.
 */
@Service
@RequiredArgsConstructor
public class HierarchyService {

    private final EmployeeRepository employeeRepository;
    private final EmployeePhotoRepository photoRepository;

    @Transactional(readOnly = true)
    public ReportingLine line(AuthUser me, Long employeeId) {
        // 3 queries in all (the line, its people, the direct reports), however long the line is
        List<Employee> up = lineOf(employeeId);
        Employee person = up.getFirst();
        if (!canSee(me, person, up)) {
            throw ApiException.forbidden("You can only see your own reporting line and those of your team");
        }
        List<OrgNode> chain = new ArrayList<>();
        for (Employee e : up) {
            chain.add(OrgNode.from(e));
        }
        Collections.reverse(chain); // top first, the person last
        List<OrgNode> reports = employeeRepository.directReports(person.getId()).stream().map(OrgNode::from).toList();
        return new ReportingLine(chain, reports);
    }

    /**
     * Everyone for owners and group admins; anyone else with a brand gets their brand's people plus everyone above
     * them, so the chart still reaches the top. Who may open it is the Org chart menu's checkpoint (MenuAccess).
     * The web app builds the tree from reportsToId.
     */
    @Transactional(readOnly = true)
    public List<OrgNode> orgChart(AuthUser me) {
        Long brandId = me.isOwner() || me.isGroupAdmin() ? null : me.brandId();
        Map<Long, Employee> people = new LinkedHashMap<>();
        for (Employee e : employeeRepository.search(brandId)) {
            if (!e.isActive()) {
                continue;
            }
            for (Employee up : chainUp(e)) {
                people.putIfAbsent(up.getId(), up);
            }
        }
        return people.values().stream().map(OrgNode::from).toList();
    }

    /** Photos of colleagues are visible to everyone signed in, for charts and lists. */
    @Transactional(readOnly = true)
    public EmployeePhoto photo(Long employeeId) {
        return photoRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("No photo for employee " + employeeId));
    }

    private boolean canSee(AuthUser me, Employee person, List<Employee> up) {
        if (me.id().equals(person.getId()) || me.isOwner()) {
            return true;
        }
        Long brandId = person.getBrand() == null ? null : person.getBrand().getId();
        if (me.role() == Role.ADMIN && (me.brandId() == null || me.brandId().equals(brandId))) {
            return true;
        }
        // anyone directly or indirectly below me
        return up.stream().skip(1).anyMatch(e -> e.getId().equals(me.id()));
    }

    /** The person, their manager, their manager's manager ... up to the top (stops if data ever loops). */
    private static List<Employee> chainUp(Employee person) {
        List<Employee> chain = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Employee e = person; e != null && seen.add(e.getId()); e = e.getReportsTo()) {
            chain.add(e);
        }
        return chain;
    }

    /** The person, their manager, their manager's manager ... up to the top, loaded together. */
    private List<Employee> lineOf(Long id) {
        List<Long> ids = employeeRepository.lineIds(id);
        if (ids.isEmpty()) {
            throw new ResourceNotFoundException("Employee not found: " + id);
        }
        Map<Long, Employee> byId = new HashMap<>();
        employeeRepository.withDetails(ids).forEach(e -> byId.put(e.getId(), e));
        List<Employee> chain = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Employee e = byId.get(id); e != null && seen.add(e.getId());
             e = e.getReportsTo() == null ? null : byId.get(e.getReportsTo().getId())) {
            chain.add(e);
        }
        return chain;
    }
}
