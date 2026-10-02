package com.pixelclub.teams.store;

import java.util.Map;
import java.util.UUID;

/** A defensive, lossless snapshot of a donor team row, including nullable columns. */
public final class Team {
    private final Map<String, Object> fields;

    public Team(Map<String, Object> fields) { this.fields = RowData.copy(fields); }
    public Map<String, Object> fields() { return RowData.copy(fields); }
    public Object field(String column) { return RowData.copyValue(fields.get(column)); }
    public boolean flag(String column) { return RowData.flag(fields.get(column)); }
    public int id() { return RowData.integer(fields.get("id")); }
    public int teamId() { return id(); }
    public String name() { return RowData.text(fields.get("name")); }
    public String tag() { return RowData.text(fields.get("tag")); }
    public UUID owner() { return UUID.fromString(RowData.text(fields.get("owner_uuid"))); }
    public double balance() { return RowData.decimal(fields.get("balance")); }
    public int tier() { return RowData.integer(fields.get("tier")); }
    public boolean pvp() { return flag("pvp_enabled"); }
    public boolean isPublic() { return flag("is_public"); }
    public String location() { return RowData.text(fields.get("home_location")); }
}
