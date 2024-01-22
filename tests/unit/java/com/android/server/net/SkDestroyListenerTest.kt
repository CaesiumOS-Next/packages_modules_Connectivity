package com.android.server.net

import android.os.Handler
import android.os.HandlerThread
import com.android.net.module.util.SharedLog
import java.io.PrintWriter
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class SkDestroyListenerTest {
    @Mock lateinit var sharedLog: SharedLog

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testDump() {
        doReturn(sharedLog).`when`(sharedLog).forSubComponent(any())

        val handlerThread = HandlerThread("SkDestroyListenerTest")
        handlerThread.start()
        val handler = Handler(handlerThread.looper)
        val skDestroylistener = SkDestroyListener(null, handler, sharedLog)

        val pw = PrintWriter(System.out)
        skDestroylistener.dump(pw)

        verify(sharedLog).reverseDump(pw)
    }
}
