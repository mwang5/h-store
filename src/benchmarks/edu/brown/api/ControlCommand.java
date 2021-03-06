package edu.brown.api;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * These are the commands that the BenchmarkController will send to each
 * BenchmarkComponent in order to coordinate the benchmark's execution
 */
public enum ControlCommand {
    START,
    POLL,
    CLEAR,
    PAUSE,
    /**
     * Stop this BenchmarkComponent instance
     */
    STOP,
    /**
     * This is the same as STOP except that the BenchmarkComponent will
     * tell the cluster to shutdown first before it exits 
     */
    SHUTDOWN,
    ;

    protected static final Map<Integer, ControlCommand> idx_lookup = new HashMap<Integer, ControlCommand>();
    protected static final Map<String, ControlCommand> name_lookup = new HashMap<String, ControlCommand>();
    static {
        for (ControlCommand vt : EnumSet.allOf(ControlCommand.class)) {
            ControlCommand.idx_lookup.put(vt.ordinal(), vt);
            ControlCommand.name_lookup.put(vt.name().toUpperCase(), vt);
        } // FOR
    }
    
    public static ControlCommand get(String name) {
        return (ControlCommand.name_lookup.get(name.trim().toUpperCase()));
    }
}