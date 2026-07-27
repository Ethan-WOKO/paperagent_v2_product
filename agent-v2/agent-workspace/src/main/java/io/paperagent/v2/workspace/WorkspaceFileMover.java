package io.paperagent.v2.workspace;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface WorkspaceFileMover {
    void move(Path source, Path target, boolean replace) throws IOException;
}
