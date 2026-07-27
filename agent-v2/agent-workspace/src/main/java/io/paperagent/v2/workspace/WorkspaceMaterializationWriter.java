package io.paperagent.v2.workspace;

import java.io.IOException;
import java.nio.file.OpenOption;
import java.nio.file.Path;

@FunctionalInterface
interface WorkspaceMaterializationWriter {
    void write(Path target, byte[] content, OpenOption... options) throws IOException;
}
