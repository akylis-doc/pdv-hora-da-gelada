package br.com.bebidaspdv.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccountSwitchSafetyTest {
    @Test fun `an uncertain payment keeps its owner signed in without draining`() = runBlocking {
        var drained = false
        var signedOut = false
        try {
            confirmAccountSwitch({ true }, { drained = true }, { signedOut = true })
            fail("An unresolved payment must preserve the current authentication")
        } catch (_: IllegalStateException) { }
        assertFalse(drained)
        assertFalse(signedOut)
    }

    @Test fun `an operation appearing during synchronization prevents the switch`() = runBlocking {
        var pending = false
        var signedOut = false
        try {
            confirmAccountSwitch({ pending }, { pending = true }, { signedOut = true })
            fail("The journal must be checked again after synchronization")
        } catch (_: IllegalStateException) { }
        assertFalse(signedOut)
    }

    @Test fun `failed synchronization leaves authentication and account state untouched`() = runBlocking {
        var signedOut = false
        val failure = IllegalStateException("Offline")
        try {
            confirmAccountSwitch({ false }, { throw failure }, { signedOut = true })
            fail("Synchronization must complete before signing out")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertFalse(signedOut)
    }

    @Test fun `cancellation while waiting never signs out`() = runBlocking {
        var signedOut = false
        try {
            confirmAccountSwitch({ false }, { throw CancellationException("screen left") }, { signedOut = true })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(signedOut)
    }

    @Test fun `a reconciled account signs out only after writes have drained`() = runBlocking {
        val actions = mutableListOf<String>()
        confirmAccountSwitch({ false }, { actions += "drained" }, { actions += "signed out and cleared" })
        assertEquals(listOf("drained", "signed out and cleared"), actions)
    }
}
