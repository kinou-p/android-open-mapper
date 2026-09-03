package com.kinou.gameassist.injector

import android.hardware.input.IInputManager
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import java.io.FileDescriptor

class IInputManagerHelperTest {

    private class FakeInputManager(private val throwRemoteException: Boolean) : IInputManager {
        override fun asBinder(): IBinder? = null
        override fun injectInputEvent(ev: android.view.InputEvent?, mode: Int): Boolean {
            if (throwRemoteException) {
                throw RemoteException("Binder died during injection")
            }
            return true
        }
    }

    private class MockLocalBinder(private val fakeManager: FakeInputManager) : IBinder {
        override fun getInterfaceDescriptor(): String = "android.hardware.input.IInputManager"
        override fun pingBinder(): Boolean = true
        override fun isBinderAlive(): Boolean = true
        override fun queryLocalInterface(descriptor: String): IInterface = fakeManager
        override fun dump(fd: FileDescriptor, args: Array<out String>?) {}
        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {}
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = true
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
    }

    @Test
    fun testSuccessfulInjectionPropagatesTrue() {
        val fakeManager = FakeInputManager(throwRemoteException = false)
        val binder = MockLocalBinder(fakeManager)
        val helper = IInputManagerHelper(binder)
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)

        val result = helper.injectInputEvent(keyEvent, 0)
        assertTrue(result)
    }

    @Test
    fun testRemoteExceptionHandledGracefullyWithoutCrash() {
        val fakeManager = FakeInputManager(throwRemoteException = true)
        val binder = MockLocalBinder(fakeManager)
        val helper = IInputManagerHelper(binder)
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)

        val result = helper.injectInputEvent(keyEvent, 0)
        assertFalse(result)
    }
}
