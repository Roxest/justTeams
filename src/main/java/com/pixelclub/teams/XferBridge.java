package com.pixelclub.teams;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** pxc-xfer is optional: its API is reached by reflection, so this mod loads without it (V1). */
final class XferBridge {
    private static volatile Method go;

    private XferBridge() {}

    /** PxcXferApi.go: null = the hand-off started, else why pxc-xfer refused it. Server thread. */
    static String go(Object player, String destination, String afterArrival) throws Exception {
        Method method = go;
        if (method == null) {
            method = findGo(player);
            go = method;
        }
        try {
            return (String) method.invoke(null, player, destination, afterArrival);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw e;
        }
    }

    private static Method findGo(Object player) throws ReflectiveOperationException {
        Class<?> api = Class.forName("com.pixelclub.pxcxfer.PxcXferApi");
        for (Method method : api.getMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (method.getName().equals("go") && Modifier.isStatic(method.getModifiers()) && p.length == 3
                    && p[0].isInstance(player) && p[1] == String.class && p[2] == String.class
                    && method.getReturnType() == String.class) {
                return method;
            }
        }
        throw new NoSuchMethodException("PxcXferApi.go(ServerPlayer,String,String)");
    }
}
