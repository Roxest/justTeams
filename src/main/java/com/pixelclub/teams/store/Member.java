package com.pixelclub.teams.store;

import java.util.Map;
import java.util.UUID;

/** Stored authorization flags are independent of the role. */
public final class Member {
    private final Map<String, Object> fields;

    public Member(Map<String, Object> fields) { this.fields = RowData.copy(fields); }
    public Map<String, Object> fields() { return RowData.copy(fields); }
    public Object field(String column) { return RowData.copyValue(fields.get(column)); }
    public boolean flag(String column) { return RowData.flag(fields.get(column)); }
    public UUID uuid() { return UUID.fromString(RowData.text(fields.get("player_uuid"))); }
    public int teamId() { return RowData.integer(fields.get("team_id")); }
    public String role() { return RowData.text(fields.get("role")); }
    public String name() { return RowData.text(fields.get("player_name")); }
    public boolean owner() { return "OWNER".equals(role()); }
}
