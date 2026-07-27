package io.paperagent.v2.workspace;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface WorkspaceDirectoryPublisher {
    void publish(Path pending, Path target) throws IOException;
}
