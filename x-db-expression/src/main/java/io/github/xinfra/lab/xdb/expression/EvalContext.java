package io.github.xinfra.lab.xdb.expression;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

public final class EvalContext {
    private final LocalDateTime currentTime;
    private final ZoneId timeZone;
    private final Map<String, Datum> variables;

    public EvalContext() { this(LocalDateTime.now(), ZoneId.systemDefault()); }

    public EvalContext(LocalDateTime currentTime, ZoneId timeZone) {
        this.currentTime = currentTime;
        this.timeZone = timeZone;
        this.variables = new HashMap<>();
    }

    public LocalDateTime currentTime() { return currentTime; }
    public ZoneId timeZone() { return timeZone; }

    public void setVariable(String name, Datum value) {
        variables.put(name.toLowerCase(), value);
    }

    public Datum getVariable(String name) {
        return variables.getOrDefault(name.toLowerCase(), Datum.nil());
    }
}
