/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import java.util.List;

/** Configuration codes the portal reads, as the Configuration page shows and checks them. */
public interface KnownSettings {

    boolean reads(String code);

    /** The value in use now. */
    String inEffect(String code);

    /** What applies with no active row. */
    String fallback(String code);

    /** Refuses a value that cannot work. */
    void check(String code, String value);

    /** Why what is in effect differs from the row. */
    default String whyDifferent(String code) {
        return "from the properties file";
    }

    /** More about what is in effect, shown with it. */
    default String detail(String code) {
        return null;
    }

    /** How to write the value, shown on the form. */
    default String hint(String code) {
        return null;
    }

    /** The only values it takes, to choose from; null for any value check allows. */
    default List<String> choices(String code) {
        return null;
    }
}
