package com.ryanheise.audioservice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import io.flutter.embedding.engine.FlutterEngineCache;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * The plugin's unified error handling (AudioServiceErrors): an error it catches instead of letting
 * it crash the app is logged and, when an AudioHandler's engine is attached, delivered to Dart's
 * AudioService.asyncError as a PlatformException whose code names where it was caught.
 *
 * <p>The harness handler counts asyncError errors in its media item's extras
 * ({@code asyncErrorCount}, and {@code lastAsyncError} holding the code), which these tests read
 * through a MediaController.</p>
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class PlatformErrorTest {
    private static final long TIMEOUT_MS = 30_000;
    /** Mirror the harness handler's parent media ids (lib/main.dart). */
    private static final String SLOW_PARENT_MEDIA_ID = "slow";
    private static final String FAILING_PARENT_MEDIA_ID = "failing";

    private final Context context = ApplicationProvider.getApplicationContext();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ActivityScenario<AudioServiceActivity> scenario;
    private MediaBrowserCompat browser;

    @Before
    public void startWithoutEngineOrService() throws Exception {
        runOnMain(() -> {
            AudioServicePlugin.disposeFlutterEngine();
            return null;
        });
        await("no cached engine or service", () -> cachedEngine() == null && AudioService.instance == null, TIMEOUT_MS);
    }

    @After
    public void cleanUp() throws Exception {
        if (browser != null) {
            runOnMain(() -> {
                browser.disconnect();
                return null;
            });
            browser = null;
        }
        if (scenario != null) {
            scenario.close();
            scenario = null;
        }
        context.stopService(new Intent(context, AudioService.class));
        runOnMain(() -> {
            AudioServicePlugin.disposeFlutterEngine();
            return null;
        });
        await("AudioService destroyed during cleanup", () -> AudioService.instance == null, TIMEOUT_MS);
    }

    /**
     * Reported from the main thread and from another thread, both reach AudioService.asyncError.
     * Flutter only sends platform messages from the main thread, so the second one must be handed
     * over to it.
     */
    @Test
    public void reportedErrorsReachAsyncErrorFromAnyThread() throws Exception {
        final MediaControllerCompat controller = launchAndAwaitHandler();

        runOnMain(() -> {
            AudioServiceErrors.report("PlatformErrorTest.mainThread", new IllegalStateException("simulated"));
            return null;
        });
        await("The error reported on the main thread did not reach AudioService.asyncError",
                () -> asyncErrorCount(controller) == 1, TIMEOUT_MS);
        assertEquals("PlatformErrorTest.mainThread", lastAsyncError(controller));

        assertTrue("The instrumentation thread must not be the main thread",
                Looper.myLooper() != Looper.getMainLooper());
        AudioServiceErrors.report("PlatformErrorTest.otherThread", new IllegalStateException("simulated"));
        await("The error reported on another thread did not reach AudioService.asyncError",
                () -> asyncErrorCount(controller) == 2, TIMEOUT_MS);
        assertEquals("PlatformErrorTest.otherThread", lastAsyncError(controller));
    }

    /**
     * Without an engine hosting an AudioHandler there is nowhere to deliver an error: it is only
     * logged, without failing, and not handed to an engine created later.
     */
    @Test
    public void reportedErrorWithoutAnEngineIsOnlyLogged() throws Exception {
        assertNull(cachedEngine());
        final int pid = Process.myPid();
        runOnMain(() -> {
            AudioServiceErrors.report("PlatformErrorTest.noEngine", new IllegalStateException("simulated"));
            return null;
        });
        AudioServiceErrors.report("PlatformErrorTest.noEngine", new IllegalStateException("simulated"));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals("Application PID changed", pid, Process.myPid());

        final MediaControllerCompat controller = launchAndAwaitHandler();
        assertHoldsFor("An error reported without an engine reached a later one",
                () -> asyncErrorCount(controller) == 0, 1_000);
    }

    /**
     * The Dart handler's getChildren throws. The request used to be answered with
     * Result.sendError(), which MediaBrowserServiceCompat only supports for custom actions: the
     * UnsupportedOperationException crashed the app. The client must get an error instead.
     */
    @Test
    public void failingBrowseRequestIsAnsweredWithAnError() throws Exception {
        final int pid = Process.myPid();
        launchAndAwaitHandler();
        connectBrowser();

        final CountDownLatch failed = new CountDownLatch(1);
        runOnMain(() -> {
            browser.subscribe(FAILING_PARENT_MEDIA_ID, new MediaBrowserCompat.SubscriptionCallback() {
                @Override
                public void onError(@NonNull String parentId) {
                    failed.countDown();
                }
            });
            return null;
        });
        assertTrue("The client was not told that loading the children failed",
                failed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals("Application PID changed", pid, Process.myPid());
    }

    /**
     * Dart answers a browse request after AudioService was destroyed. Building the answer's media
     * items needs the service, so this used to throw on the main thread when the answer arrived,
     * crashing the app. It must be reported instead, and reach Dart.
     */
    @Test
    public void browseAnswerAfterServiceDestroyedIsReportedNotThrown() throws Exception {
        final int pid = Process.myPid();
        launchAndAwaitHandler();
        shell("logcat -c");
        connectBrowser();

        // The harness handler answers these children after five seconds.
        runOnMain(() -> {
            browser.subscribe(SLOW_PARENT_MEDIA_ID, new MediaBrowserCompat.SubscriptionCallback() {});
            return null;
        });
        await("The browse request did not reach the handler",
                () -> lifecycleLog().contains("method=getChildren flutterReady=true"),
                TIMEOUT_MS);

        // Destroy the service while the answer is pending. Requesting the engine cancels its
        // scheduled disposal, so the Dart handler survives to answer and to receive the error.
        runOnMain(() -> {
            browser.disconnect();
            return null;
        });
        browser = null;
        scenario.close();
        scenario = null;
        await("AudioService was not destroyed", () -> AudioService.instance == null, TIMEOUT_MS);
        runOnMain(() -> {
            AudioServicePlugin.getFlutterEngine(context);
            return null;
        });

        await("The failure to build the answer was not reported",
                () -> lifecycleLog().contains("AudioHandlerInterface.getChildren failed"), TIMEOUT_MS);
        await("The report did not reach Dart",
                () -> lifecycleLog().contains("method=onPlatformError outcome=success"), TIMEOUT_MS);
        assertNull("The service must not have been recreated", AudioService.instance);
        assertEquals("Application PID changed", pid, Process.myPid());
    }

    /** Launches the Activity and waits until the harness handler has published its media item. */
    private MediaControllerCompat launchAndAwaitHandler() throws Exception {
        scenario = ActivityScenario.launch(AudioServiceActivity.class);
        await("AudioService was not created", () -> AudioService.instance != null, TIMEOUT_MS);
        final AudioService service = AudioService.instance;
        final MediaControllerCompat controller =
                runOnMain(() -> new MediaControllerCompat(context, service.getSessionToken()));
        await("The harness handler did not publish its media item",
                () -> runOnMain(() -> controller.getMetadata() != null), 60_000);
        return controller;
    }

    private void connectBrowser() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        runOnMain(() -> {
            browser = new MediaBrowserCompat(context, new ComponentName(context, AudioService.class),
                    new MediaBrowserCompat.ConnectionCallback() {
                        @Override
                        public void onConnected() {
                            connected.countDown();
                        }
                    }, null);
            browser.connect();
            return null;
        });
        assertTrue("MediaBrowser did not connect", connected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    /** Errors that reached AudioService.asyncError, as counted by the harness handler. */
    private long asyncErrorCount(MediaControllerCompat controller) throws Exception {
        return runOnMain(() -> {
            final MediaMetadataCompat metadata = controller.getMetadata();
            return metadata == null ? 0L : metadata.getLong("asyncErrorCount");
        });
    }

    private String lastAsyncError(MediaControllerCompat controller) throws Exception {
        return runOnMain(() -> controller.getMetadata().getString("lastAsyncError"));
    }

    private static Object cachedEngine() {
        return FlutterEngineCache.getInstance().get(AudioServicePlugin.getFlutterEngineId());
    }

    private static String lifecycleLog() throws IOException {
        return shell("logcat -d -s " + AudioServiceLifecycleLog.TAG);
    }

    private static String shell(String command) throws IOException {
        final ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        final StringBuilder out = new StringBuilder();
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            final byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.append(new String(buffer, 0, n));
        } finally {
            pfd.close();
        }
        return out.toString();
    }

    private <T> T runOnMain(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        main.post(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    interface Condition {
        boolean holds() throws Exception;
    }

    private static void await(String failureMessage, Condition condition, long timeoutMs) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.holds()) return;
            SystemClock.sleep(25);
        }
        fail(failureMessage + "; pid=" + Process.myPid() + ", service=" + AudioService.instance
                + ", engineGeneration=" + AudioServicePlugin.getFlutterEngineGeneration());
    }

    private static void assertHoldsFor(String failureMessage, Condition condition, long durationMs) throws Exception {
        final long end = SystemClock.elapsedRealtime() + durationMs;
        while (SystemClock.elapsedRealtime() < end) {
            if (!condition.holds()) fail(failureMessage);
            SystemClock.sleep(25);
        }
    }
}
