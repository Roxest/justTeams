package com.pixelclub.teams;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;

/**
 * X4 neutral text (Kraus 10-17): a pxc-xfer refusal reason as the Thai a player sees, never the raw English.
 * pxc-xfer &gt;= 0.3.50-rig.x4nt1 has PxcXferApi.reasonText; this mod compiles against an older pxc-xfer, so it is
 * looked up by reflection and, when missing, this local copy of pxc-xfer ReasonText.REASONS is used (keep in sync).
 */
final class XferReason {
    private XferReason() {}

    static final String FALLBACK = "ทำไม่ได้ตอนนี้";

    private static final MethodHandle API = find();

    private static MethodHandle find() {
        try {
            Class<?> c = Class.forName("com.pixelclub.pxcxfer.PxcXferApi");
            return MethodHandles.publicLookup().findStatic(c, "reasonText", MethodType.methodType(String.class, String.class));
        } catch (Throwable t) {
            return null;
        }
    }

    static final Map<String, String> REASONS = Map.ofEntries(
            // pxc-xfer Freeze
            Map.entry("held by staff", "ทีมงานพักการเดินทางของคุณไว้ชั่วคราว"),
            Map.entry("dead", "ตัวละครตายอยู่ — เกิดใหม่ก่อน"),
            Map.entry("riding — dismount first", "กำลังขี่อยู่ — ลงก่อน"),
            Map.entry("a pet is on your shoulder", "มีสัตว์เกาะไหล่อยู่ — ปล่อยลงก่อน"),
            Map.entry("something you threw is still flying", "ของที่ขว้างออกไปยังลอยอยู่ — รอสักครู่"),
            Map.entry("busy", "ยังทำอย่างอื่นค้างอยู่ — ลองใหม่อีกครั้ง"),
            // pxc-xfer CobbleBridge / CobbleRaceBridge / GkCompat
            Map.entry("in a battle", "กำลังต่อสู้อยู่ — จบการต่อสู้ก่อน"),
            Map.entry("in a trade", "กำลังเทรดอยู่ — จบการเทรดก่อน"),
            Map.entry("a Pokémon is sent out — recall it first", "มีโปเกมอนปล่อยออกมาอยู่ — เก็บเข้าบอลก่อน"),
            Map.entry("in a race — /race leave first", "กำลังแข่ง race อยู่ — พิมพ์ /race leave ก่อน"),
            Map.entry("race inventory is being restored", "กำลังคืนของจาก race — รอสักครู่"),
            Map.entry("you are in an event here — finish or leave it first", "กำลังอยู่ในอีเวนต์ — จบหรือออกจากอีเวนต์ก่อน"),
            // pxc-xfer Handoff.begin / HomeOnQuit.goOrigin
            Map.entry("xfer off", "ระบบนี้ปิดอยู่ชั่วคราว"),
            Map.entry("already here", "คุณอยู่ที่นี่แล้ว"),
            Map.entry("not owned here", "ข้อมูลของคุณยังไม่พร้อม — กรุณาเข้าเกมใหม่อีกครั้ง"),
            Map.entry("transfer already running", "กำลังพาไปอยู่แล้ว — รอสักครู่"),
            Map.entry("capture failed", "บันทึกข้อมูลไม่สำเร็จ — ลองใหม่อีกครั้ง"),
            Map.entry("transfer timed out, you stay here", "ใช้เวลานานเกินไป — คุณยังอยู่ที่เดิม"),
            Map.entry("transfer failed, you stay here", "ไปที่นั่นไม่สำเร็จ — คุณยังอยู่ที่เดิม"),
            // pxc-xfer-compat
            Map.entry("daycare is saving - try again in a moment", "daycare กำลังบันทึก — รอสักครู่"),
            // pixel-tower 1.6.19.3
            Map.entry("in the Battle Tower - /tower quit first", "อยู่ใน Battle Tower — พิมพ์ /tower quit ก่อน"),
            // pxcrlmonguard
            Map.entry("rlmon is saving your survival inventory", "กำลังบันทึกของ survival ก่อนเข้าดันเจี้ยน — รอสักครู่"),
            Map.entry("in an rlmon run - /rlmon pause or /rlmon leave first", "อยู่ในดันเจี้ยน rlmon — พิมพ์ /rlmon pause หรือ /rlmon leave ก่อน"),
            Map.entry("an rlmon run is starting", "ดันเจี้ยน rlmon กำลังเริ่ม — รอสักครู่"),
            Map.entry("rlmon is restoring your survival inventory", "กำลังคืนของ survival จากดันเจี้ยน — รอสักครู่"),
            // proxy
            Map.entry("timed out", "ระบบตอบช้าเกินไป — ลองใหม่อีกครั้ง"),
            Map.entry("the server you are on did not answer", "ระบบไม่ตอบ — ลองใหม่อีกครั้ง"));

    static String thai(String raw) {
        if (API != null) {
            try {
                String t = (String) API.invokeExact(raw);
                if (t != null) return t;
            } catch (Throwable ignored) {
                // fall through to the local copy
            }
        }
        return local(raw);
    }

    static String local(String raw) {
        if (raw == null) return FALLBACK;
        String t = REASONS.get(raw.trim());
        return t != null ? t : FALLBACK;
    }
}
