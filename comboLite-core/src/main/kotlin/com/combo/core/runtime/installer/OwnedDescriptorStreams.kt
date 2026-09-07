package com.combo.core.runtime.installer

import android.system.Os
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android streams constructed from a raw FileDescriptor do not own that descriptor. These
 * wrappers accept sole ownership of an Os.open result and close it even if stream closure fails.
 * The validity check also tolerates platforms which close/invalidate the descriptor themselves.
 */
internal class OwnedDescriptorInputStream(private val ownedDescriptor: FileDescriptor) : FileInputStream(ownedDescriptor) {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { super.close() } finally { if (ownedDescriptor.valid()) Os.close(ownedDescriptor) }
    }
}

internal class OwnedDescriptorOutputStream(private val ownedDescriptor: FileDescriptor) : FileOutputStream(ownedDescriptor) {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { super.close() } finally { if (ownedDescriptor.valid()) Os.close(ownedDescriptor) }
    }
}
