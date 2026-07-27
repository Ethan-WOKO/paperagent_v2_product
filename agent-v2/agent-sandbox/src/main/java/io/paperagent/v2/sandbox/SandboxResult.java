package io.paperagent.v2.sandbox;

public sealed interface SandboxResult permits ExecutedCommand, SandboxFailure {
}
