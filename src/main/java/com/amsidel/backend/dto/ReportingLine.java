package com.amsidel.backend.dto;

import java.util.List;

/**
 * A person's place in the hierarchy: chain = from the top (e.g. CEO) down to the person, the person last;
 * reports = the people who report directly to them.
 */
public record ReportingLine(List<OrgNode> chain, List<OrgNode> reports) {
}
