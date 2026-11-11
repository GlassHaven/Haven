package sh.haven.app.update

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.update.UpdateChecker
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateNotifierTest {

    @Volatile
    private var mainThread: Thread? = null

    private val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TestMain").apply {
            isDaemon = true
            mainThread = this
        }
    }
    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        mockkObject(ProcessLifecycleOwner.Companion)
    }

    @After
    fun tearDown() {
        unmockkObject(ProcessLifecycleOwner.Companion)
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    @Test
    fun `start registers ProcessLifecycleOwner observer on main thread even when called from Dispatchers IO`() = runBlocking {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val ioScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> uncaught.add(t) },
        )
        try {
            val addedLatch = CountDownLatch(1)
            val checkThreadLatch = CountDownLatch(1)
            var capturedObserver: LifecycleObserver? = null
            var checkThreadName: String? = null

            val lifecycle = mockk<Lifecycle> {
                every { addObserver(any()) } answers {
                    check(Thread.currentThread() === mainThread) {
                        "Method addObserver must be called on the main thread (was ${Thread.currentThread().name})"
                    }
                    capturedObserver = firstArg()
                    addedLatch.countDown()
                }
            }
            val lifecycleOwner = mockk<LifecycleOwner> {
                every { this@mockk.lifecycle } returns lifecycle
            }
            every { ProcessLifecycleOwner.get() } returns lifecycleOwner

            val context = mockk<Context>(relaxed = true)
            val updateChecker = mockk<UpdateChecker> {
                io.mockk.coEvery { checkOnLaunch(any()) } coAnswers {
                    checkThreadName = Thread.currentThread().name
                    checkThreadLatch.countDown()
                    null
                }
            }

            var checkerInstantiated = false
            val notifier = UpdateNotifier(
                context,
                dagger.Lazy {
                    checkerInstantiated = true
                    updateChecker
                },
            )

            // Reproduce HavenApp.onCreate()'s background appScope.launch { updateNotifier.get().start(appScope) } (#830)
            withContext(Dispatchers.IO) {
                notifier.start(ioScope)
            }

            val completed = addedLatch.await(3, TimeUnit.SECONDS)
            val childrenState = ioScope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()
            assertTrue(
                "Expected ProcessLifecycleOwner.lifecycle.addObserver to run on TestMain (uncaught=$uncaught, children=$childrenState)",
                completed,
            )
            assertEquals("Expected zero uncaught exceptions", emptyList<Throwable>(), uncaught.toList())
            org.junit.Assert.assertFalse(
                "UpdateChecker should stay uninstantiated until ProcessLifecycleOwner fires onStart",
                checkerInstantiated,
            )

            val defaultObserver = capturedObserver as? DefaultLifecycleObserver
            assertNotNull("Expected DefaultLifecycleObserver registered on ProcessLifecycleOwner", defaultObserver)

            withContext(mainDispatcher) {
                defaultObserver!!.onStart(lifecycleOwner)
            }
            assertTrue("Expected UpdateChecker.checkOnLaunch to run on background scope", checkThreadLatch.await(3, TimeUnit.SECONDS))
            assertTrue(
                "Expected checkOnLaunch off TestMain, ran on $checkThreadName",
                checkThreadName?.startsWith("TestMain") == false,
            )
            assertTrue("Expected UpdateChecker Lazy evaluated once onStart fired", checkerInstantiated)
            coVerify(exactly = 1) { updateChecker.checkOnLaunch(any()) }
        } finally {
            ioScope.cancel()
        }
    }
}
