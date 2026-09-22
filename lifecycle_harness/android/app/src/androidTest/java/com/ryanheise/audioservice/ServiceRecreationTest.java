package com.ryanheise.audioservice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.media.MediaBrowserServiceCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import io.flutter.embedding.engine.FlutterEngineCache;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Android lifecycle harness for the shared FlutterEngine / AudioService
 * ownership rules. Runs in the example app's process, in the plugin's
 * package so it can read the package-private diagnostics
 * ({@link AudioServicePlugin#getFlutterEngineGeneration()},
 * {@link AudioService#instance}).
 *
 * <p>Run on a connected device or emulator:
 * <pre>
 *   cd audio_service/example
 *   flutter pub get
 *   cd android && ./gradlew :app:connectedDebugAndroidTest
 * </pre>
 *
 * <p><b>Status: written and syntax-checked, not executed.</b> No device or
 * emulator was available where this harness was built.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ServiceRecreationTest {
    private static final String TAG = "ServiceRecreationTest";
    /** Mirrors AudioServicePlugin.ENGINE_DISPOSAL_DELAY_MS. */
    private static final long ENGINE_DISPOSAL_DELAY_MS = 1000;

    private final Context context = ApplicationProvider.getApplicationContext();
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * The problematic sequence from production: the service is created for
     * a binding that is released while onCreate() is still booting the
     * engine, i.e. before the new engine's own MediaBrowser has bound. On the
     * old code this looped forever (a new engine roughly every 150 ms); now
     * the queued re-creation must reuse the engine and settle.
     */
    @Test
    public void earlyUnbindDuringServiceCreateDoesNotLoopEngines() throws Exception {
        ensureNoEngineAndNoService();
        final int generationBefore = AudioServicePlugin.getFlutterEngineGeneration();

        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {}
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        Intent intent = new Intent(MediaBrowserServiceCompat.SERVICE_INTERFACE)
                .setComponent(new ComponentName(context, AudioService.class));
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE));
        // onCreate() assigns AudioService.instance before it boots the engine,
        // which takes hundreds of milliseconds: release the binding in that
        // window, exactly as the loop entry did.
        await("service onCreate started", () -> AudioService.instance != null, 20_000);
        context.unbindService(connection);

        int generationAt3s = sampleGeneration(3_000);
        int generationAt6s = sampleGeneration(3_000);
        int generationAt10s = sampleGeneration(4_000);
        Log.i(TAG, "generation before=" + generationBefore + " 3s=" + generationAt3s
                + " 6s=" + generationAt6s + " 10s=" + generationAt10s);

        // Exactly one engine was created for the first instance; the
        // re-created instance reused it.
        assertEquals(generationBefore + 1, generationAt3s);
        assertEquals(generationAt3s, generationAt6s);
        assertEquals(generationAt6s, generationAt10s);
        // The re-created instance is alive, bound by that engine's own
        // MediaBrowser, and the engine is still cached.
        assertNotNull(AudioService.instance);
        assertNotNull(cachedEngine());
    }

    /**
     * A service instance destroyed while the engine survives is replaced by
     * one that uses the same engine and handler, and comes back with the
     * handler's current playback state, media item (with artwork), queue and
     * playback info without any new Dart emission. Afterwards, releasing the
     * last binding still disposes the engine.
     */
    @Test
    public void recreatedServiceReusesEngineAndRestoresState() throws Exception {
        ActivityScenario<AudioServiceActivity> scenario = ActivityScenario.launch(AudioServiceActivity.class);
        {
            // The harness handler publishes its media item once
            // AudioService.init completes; its artwork arrives a little later
            // once loaded from its local file.
            Snapshot before = Snapshot.connect(context, main);
            before.awaitTitle(60_000);
            before.awaitArt(20_000);
            before.read();
            Log.i(TAG, "before: " + before);
            final int generation = AudioServicePlugin.getFlutterEngineGeneration();
            final AudioService first = AudioService.instance;
            assertNotNull(first);
            final Object handlerBefore = AudioServicePlugin.audioHandlerInterface();
            final AudioProcessingState processingStateBefore = first.getProcessingState();
            final boolean playingBefore = first.isPlaying();
            before.disconnect();

            // Closing the activity unbinds the plugin's MediaBrowser; with no
            // binding left the service is destroyed and, as nothing is
            // playing, the engine's disposal is scheduled.
            scenario.close();
            await("service destroyed", () -> AudioService.instance == null, 10_000);
            assertNotNull("engine must survive service destruction", cachedEngine());

            // Bind again inside the disposal window, as a queued system bind
            // would: the new instance must reuse the engine and be restored.
            Snapshot after = Snapshot.connect(context, main);
            after.awaitConnected(10_000);
            AudioService second = AudioService.instance;
            assertNotNull(second);
            assertNotSame(first, second);
            assertEquals("engine generation must not grow", generation, AudioServicePlugin.getFlutterEngineGeneration());
            assertNotNull(cachedEngine());
            assertSame("recreated service must use the surviving handler", handlerBefore, AudioServicePlugin.audioHandlerInterface());

            // State replay: fields are restored synchronously in onCreate, the
            // media item (and its artwork) and queue through the same
            // background projection as live updates.
            assertEquals(processingStateBefore, second.getProcessingState());
            assertEquals(playingBefore, second.isPlaying());
            after.awaitTitle(10_000);
            if (before.hasArt) after.awaitArt(10_000);
            after.read();
            Log.i(TAG, "after: " + after);
            assertEquals(before.title, after.title);
            assertEquals(before.hasArt, after.hasArt);
            assertEquals(before.queueSize, after.queueSize);
            assertEquals(before.playbackState, after.playbackState);
            assertEquals(before.playbackType, after.playbackType);

            // The replay must not run transition-only behaviour: in
            // particular an idle state must not stop the service or dispose
            // the engine. Wait past the disposal delay to be sure.
            Thread.sleep(ENGINE_DISPOSAL_DELAY_MS + 500);
            assertSame(second, AudioService.instance);
            assertNotNull(cachedEngine());
            assertEquals(processingStateBefore, second.getProcessingState());

            // Normal terminal cleanup: releasing the last binding destroys the
            // service and, after the delay, disposes the engine.
            after.disconnect();
            await("service destroyed", () -> AudioService.instance == null, 10_000);
            await("engine disposed", () -> cachedEngine() == null, ENGINE_DISPOSAL_DELAY_MS + 5_000);
            try {
                AudioServicePlugin.audioHandlerInterface();
                fail("handler interface must be released with its engine");
            } catch (Exception expected) {
                // "Background audio task not running"
            }
        }
    }

    private static Object cachedEngine() {
        return FlutterEngineCache.getInstance().get(AudioServicePlugin.getFlutterEngineId());
    }

    private int sampleGeneration(long afterMs) throws InterruptedException {
        Thread.sleep(afterMs);
        return AudioServicePlugin.getFlutterEngineGeneration();
    }

    private void ensureNoEngineAndNoService() throws Exception {
        runOnMain(() -> { AudioServicePlugin.disposeFlutterEngine(); return null; });
        await("no cached engine", () -> cachedEngine() == null, 10_000);
        await("no service", () -> AudioService.instance == null, 10_000);
    }

    interface Condition {
        boolean holds() throws Exception;
    }

    private static void await(String what, Condition condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.holds()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(20);
        }
    }

    private <T> T runOnMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        main.post(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    /** A MediaBrowser client of AudioService plus what its controller sees. */
    private static final class Snapshot {
        private final Context context;
        private final Handler main;
        private final CountDownLatch connected = new CountDownLatch(1);
        private MediaBrowserCompat browser;
        private MediaControllerCompat controller;
        String title;
        boolean hasArt;
        int queueSize;
        int playbackState;
        int playbackType;

        private Snapshot(Context context, Handler main) {
            this.context = context;
            this.main = main;
        }

        static Snapshot connect(Context context, Handler main) throws Exception {
            Snapshot snapshot = new Snapshot(context, main);
            snapshot.runOnMain(() -> {
                snapshot.browser = new MediaBrowserCompat(context,
                        new ComponentName(context, AudioService.class),
                        new MediaBrowserCompat.ConnectionCallback() {
                            @Override
                            public void onConnected() {
                                try {
                                    snapshot.controller = new MediaControllerCompat(context, snapshot.browser.getSessionToken());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                                snapshot.connected.countDown();
                            }
                        }, null);
                snapshot.browser.connect();
                return null;
            });
            return snapshot;
        }

        void awaitConnected(long timeoutMs) throws Exception {
            assertTrue("MediaBrowser connected", connected.await(timeoutMs, TimeUnit.MILLISECONDS));
        }

        void awaitTitle(long timeoutMs) throws Exception {
            awaitConnected(timeoutMs);
            await("media item title", () -> currentTitle() != null, timeoutMs);
        }

        void awaitArt(long timeoutMs) throws Exception {
            awaitConnected(timeoutMs);
            try {
                await("media item artwork", this::currentHasArt, timeoutMs);
            } catch (AssertionError e) {
                Log.w(TAG, "artwork never arrived (no network?); artwork restore is checked as 'still absent'");
            }
        }

        void read() throws Exception {
            runOnMain(() -> {
                // Already on main: calling currentTitle()/currentHasArt()
                // here would post to main and wait on itself.
                title = titleOnMain();
                hasArt = hasArtOnMain();
                List<?> queue = controller.getQueue();
                queueSize = queue == null ? 0 : queue.size();
                PlaybackStateCompat state = controller.getPlaybackState();
                playbackState = state == null ? PlaybackStateCompat.STATE_NONE : state.getState();
                MediaControllerCompat.PlaybackInfo info = controller.getPlaybackInfo();
                playbackType = info == null ? -1 : info.getPlaybackType();
                return null;
            });
        }

        void disconnect() throws Exception {
            runOnMain(() -> { browser.disconnect(); return null; });
        }

        private String currentTitle() throws Exception {
            return runOnMain(this::titleOnMain);
        }

        private boolean currentHasArt() throws Exception {
            return runOnMain(this::hasArtOnMain);
        }

        private String titleOnMain() {
            MediaMetadataCompat metadata = controller.getMetadata();
            CharSequence t = metadata == null ? null : metadata.getDescription().getTitle();
            return t == null ? null : t.toString();
        }

        private boolean hasArtOnMain() {
            MediaMetadataCompat metadata = controller.getMetadata();
            Bitmap bitmap = metadata == null ? null : metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
            return bitmap != null;
        }

        private <T> T runOnMain(Callable<T> callable) throws Exception {
            FutureTask<T> task = new FutureTask<>(callable);
            main.post(task);
            return task.get(10, TimeUnit.SECONDS);
        }

        @Override
        public String toString() {
            return "title=" + title + " hasArt=" + hasArt + " queueSize=" + queueSize
                    + " playbackState=" + playbackState + " playbackType=" + playbackType;
        }
    }
}
