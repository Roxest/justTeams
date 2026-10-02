package eu.kotori.justTeams.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.logging.Logger;

public final class BukkitItemStackCodec {

    private static final Logger LOGGER = Logger.getLogger(BukkitItemStackCodec.class.getName());

    private BukkitItemStackCodec() {}

    /**
     * Decode Base64 string from donut_team_enderchest table to NBT or raw byte array.
     */
    public static byte[] decodeBase64(String base64Data) {
        if (base64Data == null || base64Data.trim().isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(base64Data.trim());
        } catch (Exception e) {
            LOGGER.warning("Failed to decode EnderChest Base64 data: " + e.getMessage());
            return new byte[0];
        }
    }

    /**
     * Encode raw NBT byte array to Base64 string for database persistence.
     */
    public static String encodeBase64(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try {
            return Base64.getEncoder().encodeToString(data);
        } catch (Exception e) {
            LOGGER.warning("Failed to encode EnderChest NBT to Base64: " + e.getMessage());
            return "";
        }
    }
}
