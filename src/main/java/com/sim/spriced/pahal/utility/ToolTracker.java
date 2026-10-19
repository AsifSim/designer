package com.sim.spriced.pahal.utility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolTracker {
    private static final ThreadLocal<List<Map<String, String>>> TRACKER = ThreadLocal.withInitial(ArrayList::new);

    public static void track(String toolName, String detail) {
        Map<String, String> execution = new HashMap<>();
        execution.put("tool", toolName);
        execution.put("detail", detail);
        TRACKER.get().add(execution);
    }

    public static List<Map<String, String>> getAndClear() {
        List<Map<String, String>> executions = new ArrayList<>(TRACKER.get());
        TRACKER.remove();
        return executions;
    }
}