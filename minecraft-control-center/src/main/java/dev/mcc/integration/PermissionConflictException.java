package dev.mcc.integration;

/** The holder changed after the editor loaded it; refresh before retrying. */
public final class PermissionConflictException extends IllegalStateException {
    public PermissionConflictException(String message) { super(message); }
}
