package io.paperagent.v2.sandbox;

/**
 * Performs exactly one Sandbox execution attempt. A backend must return
 * {@link SandboxFailureCode#UNSUPPORTED_PROFILE} before side effects when it
 * cannot enforce any requested profile dimension.
 */
@FunctionalInterface
public interface SandboxPort {
    SandboxResult execute(SandboxRequest request);
}
