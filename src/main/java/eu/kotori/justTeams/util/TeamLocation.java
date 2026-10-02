package eu.kotori.justTeams.util;

import java.util.Objects;

public class TeamLocation {

    private String worldName;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    public TeamLocation(String worldName, double x, double y, double z, float yaw, float pitch) {
        this.worldName = DimensionMapper.toFabricDimension(worldName);
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public TeamLocation(String worldName, double x, double y, double z) {
        this(worldName, x, y, z, 0.0f, 0.0f);
    }

    public String getWorldName() {
        return worldName;
    }

    public String getBukkitWorldName() {
        return DimensionMapper.toBukkitWorld(worldName);
    }

    public void setWorldName(String worldName) {
        this.worldName = DimensionMapper.toFabricDimension(worldName);
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    /**
     * Serialize location string formatted for database storage: "world_name,x,y,z,yaw,pitch"
     */
    public String serialize() {
        return getBukkitWorldName() + "," + x + "," + y + "," + z + "," + yaw + "," + pitch;
    }

    /**
     * Deserialize location string from database storage
     */
    public static TeamLocation deserialize(String str) {
        if (str == null || str.trim().isEmpty()) {
            return null;
        }
        String[] parts = str.split(",");
        if (parts.length < 4) {
            return null;
        }
        try {
            String world = parts[0].trim();
            double x = Double.parseDouble(parts[1].trim());
            double y = Double.parseDouble(parts[2].trim());
            double z = Double.parseDouble(parts[3].trim());
            float yaw = parts.length > 4 ? Float.parseFloat(parts[4].trim()) : 0.0f;
            float pitch = parts.length > 5 ? Float.parseFloat(parts[5].trim()) : 0.0f;
            return new TeamLocation(world, x, y, z, yaw, pitch);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TeamLocation that = (TeamLocation) o;
        return Double.compare(that.x, x) == 0 &&
                Double.compare(that.y, y) == 0 &&
                Double.compare(that.z, z) == 0 &&
                Float.compare(that.yaw, yaw) == 0 &&
                Float.compare(that.pitch, pitch) == 0 &&
                Objects.equals(worldName, that.worldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(worldName, x, y, z, yaw, pitch);
    }

    @Override
    public String toString() {
        return "TeamLocation{" +
                "worldName='" + worldName + '\'' +
                ", x=" + x +
                ", y=" + y +
                ", z=" + z +
                ", yaw=" + yaw +
                ", pitch=" + pitch +
                '}';
    }
}
