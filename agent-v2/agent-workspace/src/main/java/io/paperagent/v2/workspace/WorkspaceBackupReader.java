package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ProjectPath;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface WorkspaceBackupReader {
    byte[] read(Path source, long maximum, String operation, ProjectPath projectPath)
            throws IOException;
}
