package me.zhanghai.android.files.vault

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class VaultUnlockGenerationTest {
    @Test fun lateAuthenticationCannotReopenAfterLockAndErasesCredential() {
        val before = VaultSession.generation()
        VaultSession.lock()
        val password = "temporary credential".toByteArray()
        assertThrows(IOException::class.java) {
            VaultSession.unlock(File("unused-vault"), password, false, before)
        }
        assertFalse(VaultSession.isOpen())
        assertTrue(password.all { it == 0.toByte() })
    }
}
