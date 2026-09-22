package com.ryanheise.audioservice;

import static org.junit.Assert.assertEquals;
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

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

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
 */
@RunWith(AndroidJUnit4.class)
public class AudioServiceLifecycleTest {
    private static final long LIFECYCLE_TIMEOUT_MS = 30_000;
    private static final int REGRESSION_RECREATIONS = 3;
    private static final int STRESS_RECREATIONS = 100;

    private ActivityScenario<AudioServiceActivity> openScenario;

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
    public void flutterEngineGenerationSurvivesServiceRecreationInSameProcess() throws Exception {
        exerciseServiceRecreations(REGRESSION_RECREATIONS);
    }

    @Test
    @LargeTest
    public void flutterEngineGenerationSurvivesOneHundredServiceRecreationsInSameProcess()
            throws Exception {
        exerciseServiceRecreations(STRESS_RECREATIONS);
    }

    private void exerciseServiceRecreations(int recreationCount) throws Exception {
        final int initialPid = Process.myPid();
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

            assertEquals("Application PID changed after closing cycle " + cycle,
                    initialPid, Process.myPid());

            final AudioService currentService = launchAndAwaitNewService(previousService);
            assertNotSame("Cycle " + cycle + " reused the destroyed AudioService instance",
                    previousService, currentService);
            assertTrue("Cycle " + cycle + " did not create a distinct AudioService instance",
                    instances.add(currentService));
            assertEquals("Application PID changed while launching cycle " + cycle,
                    initialPid, Process.myPid());

            // Desired invariant: service churn inside one process must not churn the engine.
            assertEquals("FlutterEngine generation changed during service recreation cycle " + cycle,
                    initialEngineGeneration,
                    AudioServicePlugin.getFlutterEngineGeneration());

            previousService = currentService;
        }

        assertEquals("Every cycle must create a distinct AudioService instance",
                recreationCount + 1, instances.size());
        assertEquals("The application process must survive the complete churn run",
                initialPid, Process.myPid());
        assertEquals("Exactly one FlutterEngine generation is allowed in the process",
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

    private static void waitUntil(String failureMessage, Callable<Boolean> condition)
            throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS;
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
