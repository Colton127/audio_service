package com.ryanheise.audioservice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Process;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import io.flutter.embedding.engine.FlutterEngineCache;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Regression coverage for FlutterEngine ownership across Activity/service churn.
 *
 * <p>The instrumentation and target application deliberately share a process. Closing the
 * Activity releases the plugin's final MediaBrowser client, which lets Android destroy the
 * unstarted, unbound AudioService without killing that process.</p>
 *
 * <p>Contract under test: after an AudioService instance is destroyed while not playing, the
 * shared FlutterEngine is kept for {@code AudioServicePlugin.ENGINE_DISPOSAL_DELAY_MS}. A client
 * that returns within that window reuses the engine; if none returns, the engine is disposed.</p>
 */
@RunWith(AndroidJUnit4.class)
public class AudioServiceLifecycleTest {
    private static final long LIFECYCLE_TIMEOUT_MS = 30_000;
    private static final int REGRESSION_RECREATIONS = 3;
    private static final int STRESS_RECREATIONS = 100;
    /** Margin past the disposal delay before disposal is expected to have run. */
    private static final long DISPOSAL_MARGIN_MS = 500;

    private ActivityScenario<AudioServiceActivity> openScenario;

    @Before
    public void startWithoutEngineOrService() throws Exception {
        // Tests in other classes share this process and may leave an engine or a service
        // (bound by the engine's own MediaBrowser) behind.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                AudioServicePlugin.disposeFlutterEngine();
            }
        });
        waitUntil("A cached FlutterEngine survived test setup", new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return cachedEngine() == null && AudioService.instance == null;
            }
        });
    }

    @After
    public void closeOpenScenario() throws Exception {
        if (openScenario != null) {
            openScenario.close();
            openScenario = null;
        }
        if (AudioService.instance != null) {
            waitUntil("AudioService was not destroyed during test cleanup",
                    new Callable<Boolean>() {
                        @Override
                        public Boolean call() {
                            return AudioService.instance == null;
                        }
                    });
        }
    }

    @Test
    @MediumTest
    public void flutterEngineSurvivesServiceRecreationWithinDisposalDelay() throws Exception {
        exerciseServiceRecreations(REGRESSION_RECREATIONS);
    }

    @Test
    @LargeTest
    public void flutterEngineSurvivesOneHundredServiceRecreationsWithinDisposalDelay()
            throws Exception {
        exerciseServiceRecreations(STRESS_RECREATIONS);
    }

    @Test
    @MediumTest
    public void flutterEngineIsDisposedWhenNoClientReturnsWithinDisposalDelay() throws Exception {
        final int initialPid = Process.myPid();
        final long disposalDelayMs = engineDisposalDelayMs();

        final AudioService firstService = launchAndAwaitNewService(null);
        final int initialEngineGeneration = AudioServicePlugin.getFlutterEngineGeneration();
        final Object initialEngine = cachedEngine();
        assertNotNull("Launching AudioServiceActivity must cache a FlutterEngine", initialEngine);

        closeActivityAndAwaitServiceDestruction(firstService);
        final long destroyedAt = SystemClock.elapsedRealtime();

        waitUntil("FlutterEngine was not disposed after its last client left",
                new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return cachedEngine() == null;
                    }
                }, disposalDelayMs + LIFECYCLE_TIMEOUT_MS);
        final long disposedAfterMs = SystemClock.elapsedRealtime() - destroyedAt;
        // Polling granularity makes the lower bound approximate; allow one poll interval.
        assertTrue("FlutterEngine was disposed " + disposedAfterMs
                        + " ms after service destruction, before the " + disposalDelayMs
                        + " ms disposal delay",
                disposedAfterMs + 100 >= disposalDelayMs);

        final AudioService secondService = launchAndAwaitNewService(firstService);
        assertNotSame(firstService, secondService);
        assertNotSame("A client returning after disposal must get a new FlutterEngine",
                initialEngine, cachedEngine());
        assertEquals("A client returning after disposal must create exactly one new engine",
                initialEngineGeneration + 1, AudioServicePlugin.getFlutterEngineGeneration());
        assertEquals("Application PID changed", initialPid, Process.myPid());

        closeActivityAndAwaitServiceDestruction(secondService);
    }

    private void exerciseServiceRecreations(int recreationCount) throws Exception {
        final int initialPid = Process.myPid();
        final long disposalDelayMs = engineDisposalDelayMs();
        final Set<AudioService> instances =
                Collections.newSetFromMap(new IdentityHashMap<AudioService, Boolean>());

        AudioService previousService = launchAndAwaitNewService(null);
        assertEquals("Instrumentation unexpectedly left the application process", initialPid,
                Process.myPid());
        assertTrue("The initial AudioService instance was already observed",
                instances.add(previousService));

        final int initialEngineGeneration = AudioServicePlugin.getFlutterEngineGeneration();
        assertTrue("Launching AudioServiceActivity must create a FlutterEngine",
                initialEngineGeneration > 0);

        for (int cycle = 1; cycle <= recreationCount; cycle++) {
            closeActivityAndAwaitServiceDestruction(previousService);
            final long destroyedAt = SystemClock.elapsedRealtime();

            assertEquals("Application PID changed after closing cycle " + cycle,
                    initialPid, Process.myPid());

            final AudioService currentService = launchAndAwaitNewService(previousService);
            final long relaunchedAfterMs = SystemClock.elapsedRealtime() - destroyedAt;
            assertNotSame("Cycle " + cycle + " reused the destroyed AudioService instance",
                    previousService, currentService);
            assertTrue("Cycle " + cycle + " did not create a distinct AudioService instance",
                    instances.add(currentService));
            assertEquals("Application PID changed while launching cycle " + cycle,
                    initialPid, Process.myPid());

            // A relaunch that only completed after the disposal delay is outside this test's
            // contract; report it as a harness timing problem rather than a lifecycle failure.
            // Without deferred disposal (delay 0) there is no window, so no relaunch is excused.
            if (disposalDelayMs > 0 && relaunchedAfterMs >= disposalDelayMs
                    && AudioServicePlugin.getFlutterEngineGeneration() != initialEngineGeneration) {
                fail("Cycle " + cycle + " relaunched " + relaunchedAfterMs
                        + " ms after service destruction, outside the " + disposalDelayMs
                        + " ms disposal delay; the engine was legitimately disposed");
            }

            // Service churn inside the disposal delay must not churn the engine.
            assertEquals("FlutterEngine generation changed during service recreation cycle "
                            + cycle + " (relaunched " + relaunchedAfterMs + " ms after destroy)",
                    initialEngineGeneration,
                    AudioServicePlugin.getFlutterEngineGeneration());

            previousService = currentService;
        }

        assertEquals("Every cycle must create a distinct AudioService instance",
                recreationCount + 1, instances.size());
        assertEquals("The application process must survive the complete churn run",
                initialPid, Process.myPid());
        assertEquals("Churn within the disposal delay must keep one FlutterEngine generation",
                initialEngineGeneration, AudioServicePlugin.getFlutterEngineGeneration());

        closeActivityAndAwaitServiceDestruction(previousService);
    }

    private AudioService launchAndAwaitNewService(final AudioService previousService)
            throws Exception {
        assertNull("The previous AudioService must be fully destroyed before relaunch",
                AudioService.instance);

        openScenario = ActivityScenario.launch(AudioServiceActivity.class);
        waitUntil("AudioService was not created after launching AudioServiceActivity",
                new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return AudioService.instance != null
                                && AudioService.instance != previousService;
                    }
                });
        return AudioService.instance;
    }

    private void closeActivityAndAwaitServiceDestruction(final AudioService service)
            throws Exception {
        assertSame("The service being closed is no longer the current AudioService",
                service, AudioService.instance);
        openScenario.close();
        openScenario = null;

        waitUntil("AudioService was not destroyed after its final Activity client closed",
                new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return AudioService.instance == null;
                    }
                });
        assertNull("Destroyed AudioService remained process-global", AudioService.instance);
    }

    private static Object cachedEngine() {
        return FlutterEngineCache.getInstance().get(AudioServicePlugin.getFlutterEngineId());
    }

    /**
     * Reads {@code AudioServicePlugin.ENGINE_DISPOSAL_DELAY_MS} so the test follows the
     * production value. Revisions without deferred disposal dispose inline, i.e. with no delay.
     */
    private static long engineDisposalDelayMs() throws Exception {
        final Field field;
        try {
            field = AudioServicePlugin.class.getDeclaredField("ENGINE_DISPOSAL_DELAY_MS");
        } catch (NoSuchFieldException e) {
            return 0;
        }
        field.setAccessible(true);
        return field.getLong(null);
    }

    private static void waitUntil(String failureMessage, Callable<Boolean> condition)
            throws Exception {
        waitUntil(failureMessage, condition, LIFECYCLE_TIMEOUT_MS);
    }

    private static void waitUntil(String failureMessage, Callable<Boolean> condition,
            long timeoutMs) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (condition.call()) {
                return;
            }
            SystemClock.sleep(25);
        }
        fail(failureMessage
                + "; pid=" + Process.myPid()
                + ", service=" + AudioService.instance
                + ", engineGeneration=" + AudioServicePlugin.getFlutterEngineGeneration());
    }
}
