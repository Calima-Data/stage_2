package com.calima.search.common;

/**
 * Deterministic crash points for the recovery checks. When the environment variable FAULT_POINT
 * names a point, the process halts there without any cleanup, as a killed process would.
 * Production deployments leave the variable unset, which makes each call a string comparison.
 */
public final class Faults {

    private static final String ACTIVE = System.getenv("FAULT_POINT");
    private static final int HALT_STATUS = 137;

    private Faults() {
    }

    public static void point(String name) {
        if (name.equals(ACTIVE)) {
            Runtime.getRuntime().halt(HALT_STATUS);
        }
    }
}
