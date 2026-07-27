package io.paperagent.v2.workspace;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface WorkspaceTreeDeleter {
    void delete(Path root) throws IOException;
}
