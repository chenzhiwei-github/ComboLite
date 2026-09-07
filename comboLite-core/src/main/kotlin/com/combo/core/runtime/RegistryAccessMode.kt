package com.combo.core.runtime

/** Immutable for the lifetime of a process; only the installation authority is a writer. */
enum class RegistryAccessMode {
    READ_ONLY_FAIL_CLOSED,
    MUTABLE_INSTALLER;

    internal fun requireMutable(operation: String) {
        if (this != MUTABLE_INSTALLER) throw RegistryMutationDeniedException(operation)
    }
}

class RegistryMutationDeniedException(operation: String) :
    IllegalStateException("Registry mutation denied: $operation")

class RegistryRecoveryRequiredException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
