package com.pixelclub.teams;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * A resource from this mod's own jar. On Fabric the class loader is shared by every mod, so
 * {@code getResourceAsStream("config.yml")} answers with the first jar that has that path: on PRD (10-05) config.yml is
 * in six mod jars and messages.yml in two (legheld). A missing config/JustTeams file would have been re-extracted from
 * whichever came first. Asks the mod container first; off Fabric (unit tests) the class path is ours alone.
 */
public final class OwnResource {

    public static final String MOD_ID = "justteams";

    private OwnResource() {
    }

    /** null when this jar does not have it. */
    public static InputStream open(String path) throws IOException {
        String rel = path.startsWith("/") ? path.substring(1) : path;
        try {
            var container = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID);
            if (container.isPresent()) {
                var found = container.get().findPath(rel);
                return found.isPresent() && Files.isRegularFile(found.get()) ? Files.newInputStream(found.get()) : null;
            }
        } catch (LinkageError | IllegalStateException notOnFabric) {
            // unit tests: no loader
        }
        return OwnResource.class.getResourceAsStream("/" + rel);
    }
}
