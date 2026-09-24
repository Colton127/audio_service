package com.ryanheise.audioservice;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.LruCache;
import android.util.Size;
import android.view.KeyEvent;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.VolumeProviderCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.utils.MediaConstants;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.embedding.engine.FlutterEngine;

import static com.ryanheise.audioservice.AudioServiceLifecycleLog.hashOf;
import static com.ryanheise.audioservice.AudioServiceLifecycleLog.log;

public class AudioService extends MediaBrowserServiceCompat {
    public static final String CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED";
    public static final String CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    public static final String CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    public static final int CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1;
    public static final int CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 2;
    public static final int CONTENT_STYLE_CATEGORY_LIST_ITEM_HINT_VALUE = 3;
    public static final int CONTENT_STYLE_CATEGORY_GRID_ITEM_HINT_VALUE = 4;

    private static final String SHARED_PREFERENCES_NAME = "audio_service_preferences";

    static final int NOTIFICATION_ID = 1124;
    private static final int REQUEST_CONTENT_INTENT = 1000;
    public static final String NOTIFICATION_CLICK_ACTION = "com.ryanheise.audioservice.NOTIFICATION_CLICK";
    public static final String CUSTOM_ACTION_STOP = "com.ryanheise.audioservice.action.STOP";
    public static final String CUSTOM_ACTION_FAST_FORWARD = "com.ryanheise.audioservice.action.FAST_FORWARD";
    public static final String CUSTOM_ACTION_REWIND = "com.ryanheise.audioservice.action.REWIND";
    private static final String BROWSABLE_ROOT_ID = "root";
    private static final String RECENT_ROOT_ID = "recent";
    // See the comment in onMediaButtonEvent to understand how the BYPASS keycodes work.
    // We hijack KEYCODE_MUTE and KEYCODE_MEDIA_RECORD since the media session subsystem
    // considers these keycodes relevant to media playback and will pass them on to us.
    public static final int KEYCODE_BYPASS_PLAY = KeyEvent.KEYCODE_MUTE;
    public static final int KEYCODE_BYPASS_PAUSE = KeyEvent.KEYCODE_MEDIA_RECORD;
    public static final int MAX_COMPACT_ACTIONS = 3;
    private static final long AUTO_ENABLED_ACTIONS = PlaybackStateCompat.ACTION_STOP
            | PlaybackStateCompat.ACTION_PAUSE
            | PlaybackStateCompat.ACTION_PLAY
            | PlaybackStateCompat.ACTION_REWIND
            // Auto-enabling these is bad for Android Auto since it forces the
            // previous/next buttons to always show.
            //| PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            //| PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            | PlaybackStateCompat.ACTION_FAST_FORWARD
            | PlaybackStateCompat.ACTION_SET_RATING
            // "seek" is the exception because it's the only action that
            // affects the appearance of the media notification, so we leave it
            // up to the plugin user whether to enable it (via systemActions).
            //| PlaybackStateCompat.ACTION_SEEK_TO
            | PlaybackStateCompat.ACTION_PLAY_PAUSE
            | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
            | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
            | PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
            | PlaybackStateCompat.ACTION_PLAY_FROM_URI
            | PlaybackStateCompat.ACTION_PREPARE
            | PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
            | PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH
            | PlaybackStateCompat.ACTION_PREPARE_FROM_URI
            | PlaybackStateCompat.ACTION_SET_REPEAT_MODE
            | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
            | PlaybackStateCompat.ACTION_SET_CAPTIONING_ENABLED;

    static AudioService instance;
    private static PendingIntent contentIntent;
    private static ServiceListener listener;
    private static List<MediaSessionCompat.QueueItem> queue = new ArrayList<>();
    private static final Map<String, MediaMetadataCompat> mediaMetadataCache = new HashMap<>();

    public static void init(ServiceListener listener) {
        AudioService.listener = listener;
    }

    private interface ListenerCall {
        void call(ServiceListener listener);
    }

    /**
     * Calls into the plugin's listener, if one is registered. These calls run
     * from framework callbacks (media session commands, service lifecycle),
     * where an exception would crash the app, so it is reported instead.
     */
    private static void callListener(String where, ListenerCall call) {
        final ServiceListener listener = AudioService.listener;
        if (listener == null) return;
        try {
            call.call(listener);
        } catch (RuntimeException e) {
            AudioServiceErrors.report(where, e);
        }
    }

    /**
     * Answers a browse request (children, item or search) whose handling
     * failed, unless it was already answered. It is answered with null, which
     * MediaBrowser clients receive as an error: Result.sendError() throws
     * UnsupportedOperationException for these requests, it is only supported
     * for custom actions.
     */
    static void failIfUnanswered(Result<?> result) {
        try {
            result.sendResult(null);
        } catch (IllegalStateException alreadyAnswered) {
            // Nothing left to answer.
        }
    }

    public static int toKeyCode(long action) {
        if (action == PlaybackStateCompat.ACTION_PLAY) {
            return KEYCODE_BYPASS_PLAY;
        } else if (action == PlaybackStateCompat.ACTION_PAUSE) {
            return KEYCODE_BYPASS_PAUSE;
        } else {
            return PlaybackStateCompat.toKeyCode(action);
        }
    }

    MediaMetadataCompat createMediaMetadata(String mediaId, String title, String album, String artist, String genre, Long duration, String artUri, Boolean playable, String displayTitle, String displaySubtitle, String displayDescription, RatingCompat rating, Map<?, ?> extras) {
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title);
        if (album != null)
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album);
        if (artist != null)
            builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
        if (genre != null)
            builder.putString(MediaMetadataCompat.METADATA_KEY_GENRE, genre);
        if (duration != null)
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        if (artUri != null) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUri);
        }
        if (playable != null)
            builder.putLong("playable_long", playable ? 1 : 0);
        if (displayTitle != null)
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle);
        if (displaySubtitle != null)
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displaySubtitle);
        if (displayDescription != null)
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, displayDescription);
        if (rating != null) {
            builder.putRating(MediaMetadataCompat.METADATA_KEY_RATING, rating);
        }
        if (extras != null) {
            for (Object o : extras.keySet()) {
                String key = (String)o;
                Object value = extras.get(key);
                if (value instanceof Long) {
                    builder.putLong(key, (Long)value);
                } else if (value instanceof Integer) {
                    builder.putLong(key, (long)((Integer)value));
                } else if (value instanceof String) {
                    builder.putString(key, (String)value);
                } else if (value instanceof Boolean) {
                    builder.putLong(key, (Boolean)value ? 1 : 0);
                } else if (value instanceof Double) {
                    builder.putString(key, value.toString());
                }
            }
        }
        MediaMetadataCompat mediaMetadata = builder.build();
        mediaMetadataCache.put(mediaId, mediaMetadata);
        return mediaMetadata;
    }

    static MediaMetadataCompat getMediaMetadata(String mediaId) {
        return mediaMetadataCache.get(mediaId);
    }

    Bitmap loadArtBitmap(String artUriString, String loadThumbnailUri) {
        Bitmap bitmap = artBitmapCache.get(artUriString);
        if (bitmap != null) return bitmap;
        try {
            // There are 3 cases handled by this function:
            //   1. content URI with openFileDescriptor
            //   2. content URI with loadThumbnail (when Android >= Q and specified by the config)
            //   3. not content URI - loading from the file, or cache file created by the Dart side
            Uri artUri = Uri.parse(artUriString);
            boolean usesContentScheme = "content".equals(artUri.getScheme());
            FileDescriptor fileDescriptor = null;
            if (usesContentScheme) {
                try {
                    if (loadThumbnailUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Size defaultSize = new Size(192, 192);
                        bitmap = getContentResolver().loadThumbnail(
                                artUri,
                                new Size(config.artDownscaleWidth == -1
                                                ? defaultSize.getWidth()
                                                : config.artDownscaleWidth,
                                        config.artDownscaleHeight == -1
                                                ? defaultSize.getHeight()
                                                : config.artDownscaleHeight),
                                null);
                        if (bitmap == null) {
                            return null;
                        }
                    } else {
                        ParcelFileDescriptor parcelFileDescriptor = getContentResolver().openFileDescriptor(artUri, "r");
                        if (parcelFileDescriptor != null) {
                            fileDescriptor = parcelFileDescriptor.getFileDescriptor();
                        } else {
                            return null;
                        }
                    }
                } catch (FileNotFoundException ex) {
                    return null;
                } catch (IOException ex) {
                    return null;
                }
            }
            // Decode the image ourselves for scenarios 1 and 3 (see the comment above).
            if (!usesContentScheme || fileDescriptor != null) {
                if (config.artDownscaleWidth != -1) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    if (fileDescriptor != null) {
                        BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);
                    } else {
                        BitmapFactory.decodeFile(artUri.getPath(), options);
                    }
                    options.inSampleSize = calculateInSampleSize(options, config.artDownscaleWidth, config.artDownscaleHeight);
                    options.inJustDecodeBounds = false;

                    if (fileDescriptor != null) {
                        bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);
                    } else {
                        bitmap = BitmapFactory.decodeFile(artUri.getPath(), options);
                    }
                } else {
                    if (fileDescriptor != null) {
                        bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor);
                    } else {
                        bitmap = BitmapFactory.decodeFile(artUri.getPath());
                    }
                }
            }
            artBitmapCache.put(artUriString, bitmap);
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    private FlutterEngine flutterEngine;
    private AudioServiceConfig config;
    private PowerManager.WakeLock wakeLock;
    private MediaSessionCompat mediaSession;
    private MediaSessionCallback mediaSessionCallback;
    private List<MediaControl> controls = new ArrayList<>();
    private List<NotificationCompat.Action> nativeActions = new ArrayList<>();
    private List<PlaybackStateCompat.CustomAction> customActions = new ArrayList<>();
    private int[] compactActionIndices;
    private MediaMetadataCompat mediaMetadata;
    private Bitmap artBitmap;
    private String notificationChannelId;
    private LruCache<String, Bitmap> artBitmapCache;
    private boolean playing = false;
    /**
     * Whether this instance is in the playing state: started in the
     * foreground with its notification, holding the wake lock. Set only once
     * startForeground() has succeeded, and cleared by exitPlayingState().
     * Distinct from {@link #playing}, which is what the handler reports: a
     * replayed state or a refused start leaves playing without it.
     */
    private boolean playingStateEntered = false;
    /**
     * Why entering the playing state last failed while the handler kept
     * playing. Live state updates do not retry a failure, since a playing
     * handler can send several per second; a new play does, and a REFUSED
     * start is also retried where Android is likely to allow it again (see
     * retryForegroundIfPlaying).
     */
    private ForegroundFailure foregroundFailure = ForegroundFailure.NONE;
    /** A refusal from a live state update that has not been reported to Dart yet. */
    private boolean foregroundRefusalUnreported;
    private AudioProcessingState processingState = AudioProcessingState.idle;
    private int repeatMode;
    private int shuffleMode;
    private boolean notificationCreated;
    /**
     * Whether this instance is a foreground service: set once startForeground()
     * has succeeded, cleared when it leaves the foreground. stopForeground() is
     * a blocking call into system_server, which ignores it for a service that
     * is not in the foreground, so it is only made when there is a foreground
     * state to leave. Distinct from {@link #notificationCreated}: leaving the
     * foreground with STOP_FOREGROUND_LEGACY keeps the notification.
     */
    private boolean inForeground;
    /**
     * Whether this instance asked Android to start it (startForegroundService())
     * and has not stopped itself since: Android then keeps it alive with no
     * binding left. Tracked so that a refused promotion undoes only a start that
     * the same attempt made.
     */
    private boolean startRequested;
    /** Process-local counter of AudioService instances. Diagnostic only. */
    private static int serviceGenerationCounter;
    private final int serviceGeneration = ++serviceGenerationCounter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VolumeProviderCompat volumeProvider;

    public AudioProcessingState getProcessingState() {
        return processingState;
    }

    public boolean isPlaying() {
        return playing;
    }

    /** Diagnostic only: this instance's serviceGeneration. */
    int getServiceGeneration() {
        return serviceGeneration;
    }

    /** Diagnostic only: whether this instance is in the playing state. */
    boolean isPlayingStateEntered() {
        return playingStateEntered;
    }

    /** Diagnostic only: whether this instance holds its partial wake lock. */
    boolean isWakeLockHeld() {
        return wakeLock.isHeld();
    }

    /** Diagnostic only: whether this instance is a foreground service. */
    boolean isInForeground() {
        return inForeground;
    }

    /** Diagnostic only: whether this instance's media session is active. */
    boolean isMediaSessionActive() {
        return mediaSession != null && mediaSession.isActive();
    }

    public int getRepeatMode() {
        return repeatMode;
    }

    public int getShuffleMode() {
        return shuffleMode;
    }

    @Override
    public void onCreate() {
        log("service_create_begin", "serviceGeneration=" + serviceGeneration);
        super.onCreate();
        instance = this;
        repeatMode = 0;
        shuffleMode = 0;
        notificationCreated = false;
        inForeground = false;
        startRequested = false;
        playing = false;
        processingState = AudioProcessingState.idle;
        mediaSession = new MediaSessionCompat(this, "media-session");

        configure(new AudioServiceConfig(getApplicationContext()));

        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS);
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(AUTO_ENABLED_ACTIONS);
        mediaSession.setPlaybackState(stateBuilder.build());
        mediaSession.setCallback(mediaSessionCallback = new MediaSessionCallback());
        setSessionToken(mediaSession.getSessionToken());
        mediaSession.setQueue(queue);

        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AudioService.class.getName());

        // Get max available VM memory, exceeding this amount will throw an
        // OutOfMemory exception. Stored in kilobytes as LruCache takes an
        // int in its constructor.
        final int maxMemory = (int)(Runtime.getRuntime().maxMemory() / 1024);

        // Use 1/8th of the available memory for this memory cache.
        final int cacheSize = maxMemory / 8;

        artBitmapCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // The cache size will be measured in kilobytes rather than
                // number of items.
                return bitmap.getByteCount() / 1024;
            }
        };

        log("service_engine_request", "serviceGeneration=" + serviceGeneration);
        flutterEngine = AudioServicePlugin.getFlutterEngine(this);
        // If this instance replaces a destroyed one while the engine (and the
        // Dart AudioHandler) survived, the listener projects the current state
        // into this fresh MediaSession.
        callListener("AudioService.onCreate", ServiceListener::onCreate);
        log("service_create_end", "serviceGeneration=" + serviceGeneration
                + " engineGeneration=" + AudioServicePlugin.getFlutterEngineGeneration()
                + " engineHash=" + hashOf(flutterEngine));
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        log("service_start_command", "serviceGeneration=" + serviceGeneration
                + " startId=" + startId + " flags=" + flags
                + " action=" + (intent != null ? intent.getAction() : "none"));
        try {
            MediaButtonReceiver.handleIntent(mediaSession, intent);
        } catch (RuntimeException e) {
            AudioServiceErrors.report("AudioService.onStartCommand", e);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // The system calls onBind once per distinct intent, not per client:
        // later clients with an equal intent reuse the binder without a call.
        log("service_bind", "serviceGeneration=" + serviceGeneration
                + " action=" + (intent != null ? intent.getAction() : "none"));
        return super.onBind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Called once all clients bound with this intent have unbound.
        log("service_unbind", "serviceGeneration=" + serviceGeneration
                + " action=" + (intent != null ? intent.getAction() : "none"));
        return super.onUnbind(intent);
    }

    public void stop() {
        log("service_stop_requested", "serviceGeneration=" + serviceGeneration);
        deactivateMediaSession();
        startRequested = false;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        log("service_destroy_begin", "serviceGeneration=" + serviceGeneration
                + " engineGeneration=" + AudioServicePlugin.getFlutterEngineGeneration()
                + " engineHash=" + hashOf(flutterEngine)
                + " inForeground=" + inForeground);
        super.onDestroy();
        // The listener (the plugin's AudioHandlerInterface) belongs to the
        // shared FlutterEngine, which outlives this service instance. It stays
        // registered so that a recreated service keeps dispatching to the same
        // AudioHandler; the plugin clears it when the engine that hosts it is
        // detached. A failure there must not skip the cleanup below.
        callListener("AudioService.onDestroy", ServiceListener::onDestroy);
        mediaMetadata = null;
        artBitmap = null;
        queue.clear();
        mediaMetadataCache.clear();
        controls.clear();
        artBitmapCache.evictAll();
        compactActionIndices = null;
        // releaseMediaSession() also cancels the notification. There is no
        // stopForeground() here: before calling onDestroy() the system has
        // already taken this service out of the foreground and cancelled a
        // notification still attached to it (ActiveServices.
        // bringDownServiceLocked), so the call would only be a blocking round
        // trip into system_server (RELIEFMIX-3R5).
        // A failure here must not skip the rest: a stale instance would make
        // the scheduled engine disposal take this service for a recreated one
        // and keep the engine, and the wake lock must not outlive the service.
        try {
            releaseMediaSession();
        } catch (RuntimeException e) {
            AudioServiceErrors.report("AudioService.onDestroy", e);
        }
        inForeground = false;
        startRequested = false;
        releaseWakeLock();
        instance = null;
        notificationCreated = false;
        log("service_destroy_end", "serviceGeneration=" + serviceGeneration);
    }

    /**
     * Leaves the foreground, removing the notification or keeping it attached
     * to the service (STOP_FOREGROUND_LEGACY: the system still removes it when
     * the service is destroyed, unlike STOP_FOREGROUND_DETACH). Skipped if the
     * service is not in the foreground, e.g. already left on pause.
     */
    private void legacyStopForeground(boolean removeNotification, String reason) {
        final String fields = "serviceGeneration=" + serviceGeneration
                + " reason=" + reason + " removeNotification=" + removeNotification;
        if (!inForeground) {
            log("foreground_stop_skipped", fields);
            return;
        }
        log("foreground_stop_requested", fields);
        inForeground = false;
        foregroundPromoter.stopForeground(this,
                removeNotification ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_LEGACY);
        log("foreground_stopped", fields);
    }

    public AudioServiceConfig getConfig() {
        return config;
    }

    public void configure(AudioServiceConfig config) {
        this.config = config;
        notificationChannelId = (config.androidNotificationChannelId != null)
            ? config.androidNotificationChannelId
            : getApplication().getPackageName() + ".channel";

        if (config.activityClassName != null) {
            Context context = getApplicationContext();
            Intent intent = new Intent((String)null);
            intent.setComponent(new ComponentName(context, config.activityClassName));
            //Intent intent = new Intent(context, config.activityClassName);
            intent.setAction(NOTIFICATION_CLICK_ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            contentIntent = PendingIntent.getActivity(context, REQUEST_CONTENT_INTENT, intent, flags);
        } else {
            contentIntent = null;
        }
        if (!config.androidResumeOnClick) {
            mediaSession.setMediaButtonReceiver(null);
        }
    }

    int getResourceId(String resource) {
        String[] parts = resource.split("/");
        String resourceType = parts[0];
        String resourceName = parts[1];
        return getResources().getIdentifier(resourceName, resourceType, getApplicationContext().getPackageName());
    }

    NotificationCompat.Action createAction(String resource, String label, long actionCode) {
        int iconId = getResourceId(resource);
        return new NotificationCompat.Action(iconId, label,
                buildMediaButtonPendingIntent(actionCode));
    }

    private boolean needCustomMediaControl(MediaControl control) {
        return control.customAction != null;
    }

    private Bundle mapToBundle(Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        Bundle bundle = new Bundle();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = entry.getKey().toString();
            Object value = entry.getValue();
            if (value instanceof Integer) {
                bundle.putInt(key, (Integer)value);
            } else if (value instanceof Long) {
                bundle.putLong(key, (Long)value);
            } else {
                bundle.putString(key, value.toString());
            }
        }
        return bundle;
    }

    PlaybackStateCompat.CustomAction createCustomAction(MediaControl control) {
        int iconId = getResourceId(control.icon);
        if (control.customAction != null) {
            return new PlaybackStateCompat.CustomAction.Builder(control.customAction.name, control.label, iconId)
                .setExtras(mapToBundle(control.customAction.extras))
                .build();
        } else if (Build.VERSION.SDK_INT >= 33) {
            // Android 13 changes MediaControl behavior as documented here:
            // https://developer.android.com/about/versions/13/behavior-changes-13
            // The below actions will be added to slots 1-3, if included.
            // 1 - ACTION_PLAY, ACTION_PLAY
            // 2 - ACTION_SKIP_TO_PREVIOUS
            // 3 - ACTION_SKIP_TO_NEXT
            // Custom actions will use slots 2-5 if included.
            // - ACTION_STOP
            // - ACTION_FAST_FORWARD
            // - ACTION_REWIND
            if (control.actionCode == PlaybackStateCompat.ACTION_STOP) {
                return new PlaybackStateCompat.CustomAction.Builder(CUSTOM_ACTION_STOP, control.label, iconId).build();
            } else if (control.actionCode == PlaybackStateCompat.ACTION_FAST_FORWARD) {
                return new PlaybackStateCompat.CustomAction.Builder(CUSTOM_ACTION_FAST_FORWARD, control.label, iconId).build();
            } else if (control.actionCode == PlaybackStateCompat.ACTION_REWIND) {
                return new PlaybackStateCompat.CustomAction.Builder(CUSTOM_ACTION_REWIND, control.label, iconId).build();
            }
        }
        return null;
    }

    PendingIntent buildMediaButtonPendingIntent(long action) {
        int keyCode = toKeyCode(action);
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN)
            return null;
        Intent intent = new Intent(this, MediaButtonReceiver.class);
        intent.setAction(Intent.ACTION_MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        int flags = 0;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(this, keyCode, intent, flags);
    }

    PendingIntent buildDeletePendingIntent() {
        Intent intent = new Intent(this, MediaButtonReceiver.class);
        intent.setAction(MediaButtonReceiver.ACTION_NOTIFICATION_DELETE);
        int flags = 0;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(this, 0, intent, flags);
    }

    /**
     * Applies a playback state from the Dart AudioHandler.
     *
     * @param replay false for a live state update, which also runs the
     *               transition-specific side effects (entering/leaving the
     *               playing state, the idle and completed transitions, the
     *               notification refresh). true when re-projecting the
     *               handler's current state into a freshly created service
     *               instance: only the native state (fields, MediaSession
     *               playback state, modes) is restored and no transition is
     *               assumed to have happened.
     */
    void setState(List<MediaControl> controls, long actionBits, int[] compactActionIndices, AudioProcessingState processingState, boolean playing, long position, long bufferedPosition, float speed, long updateTime, Integer errorCode, String errorMessage, int repeatMode, int shuffleMode, boolean captioningEnabled, Long queueIndex, boolean replay) {
        boolean notificationChanged = false;
        if (!Arrays.equals(compactActionIndices, this.compactActionIndices)) {
            notificationChanged = true;
        }
        if (!controls.equals(this.controls)) {
            notificationChanged = true;
        }
        this.controls = controls;
        this.nativeActions.clear();
        this.customActions.clear();
        for (MediaControl control : controls) {
            final PlaybackStateCompat.CustomAction customAction = createCustomAction(control);
            if (customAction != null) {
                customActions.add(customAction);
            } else {
                nativeActions.add(createAction(control.icon, control.label, control.actionCode));
            }
        }
        this.compactActionIndices = compactActionIndices;
        AudioProcessingState oldProcessingState = this.processingState;
        final boolean wasPlaying = this.playing;
        this.processingState = processingState;
        this.playing = playing;
        this.repeatMode = repeatMode;
        this.shuffleMode = shuffleMode;

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(AUTO_ENABLED_ACTIONS | actionBits)
                .setState(getPlaybackState(), position, speed, updateTime)
                .setBufferedPosition(bufferedPosition);

        for (PlaybackStateCompat.CustomAction action : this.customActions) {
            stateBuilder.addCustomAction(action);
        }

        if (queueIndex != null)
            stateBuilder.setActiveQueueItemId(queueIndex);
        if (errorCode != null && errorMessage != null)
            stateBuilder.setErrorMessage(errorCode, errorMessage);
        else if (errorMessage != null)
            stateBuilder.setErrorMessage(-987654, errorMessage);

        if (mediaMetadata != null) {
            // Update the progress bar in the browse view as content is playing as explained
            // here: https://developer.android.com/training/cars/media#browse-progress-bar
            Bundle extras = new Bundle();
            extras.putString(MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_MEDIA_ID, mediaMetadata.getDescription().getMediaId());
            stateBuilder.setExtras(extras);
        }

        mediaSession.setPlaybackState(stateBuilder.build());
        mediaSession.setRepeatMode(repeatMode);
        mediaSession.setShuffleMode(shuffleMode);
        mediaSession.setCaptioningEnabled(captioningEnabled);

        if (replay) {
            // Restoring, not transitioning: the idle/completed transitions
            // are not re-run, the old instance already ran them. A playing
            // handler does get its foreground service, notification and wake
            // lock back now, since a handler that keeps playing sends no
            // further live update that would establish them.
            if (playing) {
                activateMediaSession();
                retryForegroundIfPlaying(REASON_STATE_REPLAY);
            }
            return;
        }

        if (playing) {
            // Once an attempt has failed, only a new play retries it here:
            // repeating it on every update of a handler that keeps playing
            // would not change Android's answer.
            if (!playingStateEntered
                    && (!wasPlaying || foregroundFailure == ForegroundFailure.NONE)
                    && enterPlayingState() != null) {
                foregroundRefusalUnreported = true;
            }
        } else {
            foregroundFailure = ForegroundFailure.NONE;
            if (playingStateEntered) {
                exitPlayingState();
            }
        }


        if (oldProcessingState != processingState) {
            if (processingState == AudioProcessingState.idle) {
                legacyStopForeground(true, "processing_state_idle");
                releaseWakeLock();
                stop();
            } else if (processingState == AudioProcessingState.completed) {
                if (config.androidStopForegroundOnCompleted) {
                    legacyStopForeground(false, "processing_state_completed");
                }
                releaseWakeLock();
            }
        }

        if (processingState != AudioProcessingState.idle && notificationChanged) {
            updateNotification();
        }
    }

    public void setPlaybackInfo(int playbackType, Integer volumeControlType, Integer maxVolume, Integer volume) {
        if (playbackType == MediaControllerCompat.PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            // We have to wait 'til media2 before we can use AudioAttributes.
            mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
            volumeProvider = null;
        } else if (playbackType == MediaControllerCompat.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
            if (volumeProvider == null || volumeControlType != volumeProvider.getVolumeControl() || maxVolume != volumeProvider.getMaxVolume()) {
                volumeProvider = new VolumeProviderCompat(volumeControlType, maxVolume, volume) {
                    @Override
                    public void onSetVolumeTo(int volumeIndex) {
                        callListener("VolumeProvider.onSetVolumeTo", l -> l.onSetVolumeTo(volumeIndex));
                    }
                    @Override
                    public void onAdjustVolume(int direction) {
                        callListener("VolumeProvider.onAdjustVolume", l -> l.onAdjustVolume(direction));
                    }
                };
            } else {
                volumeProvider.setCurrentVolume(volume);
            }
            mediaSession.setPlaybackToRemote(volumeProvider);
        } else {
            // silently ignore
        }
    }

    public int getPlaybackState() {
        switch (processingState) {
        case idle: return PlaybackStateCompat.STATE_NONE;
        case loading: return PlaybackStateCompat.STATE_CONNECTING;
        case buffering: return PlaybackStateCompat.STATE_BUFFERING;
        case ready: return playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        case completed: return playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        case error: return PlaybackStateCompat.STATE_ERROR;
        default: return PlaybackStateCompat.STATE_NONE;
        }
    }

    private Notification buildNotification() {
        int[] compactActionIndices = this.compactActionIndices;
        if (compactActionIndices == null) {
            compactActionIndices = new int[Math.min(MAX_COMPACT_ACTIONS, nativeActions.size())];
            for (int i = 0; i < compactActionIndices.length; i++) compactActionIndices[i] = i;
        }
        NotificationCompat.Builder builder = getNotificationBuilder();
        if (mediaMetadata != null) {
            MediaDescriptionCompat description = mediaMetadata.getDescription();
            if (description.getTitle() != null)
                builder.setContentTitle(description.getTitle());
            if (description.getSubtitle() != null)
                builder.setContentText(description.getSubtitle());
            if (description.getDescription() != null)
                builder.setSubText(description.getDescription());
            synchronized (this) {
                if (artBitmap != null)
                    builder.setLargeIcon(artBitmap);
            }
        }
        if (config.androidNotificationClickStartsActivity)
            builder.setContentIntent(mediaSession.getController().getSessionActivity());
        // TODO: Look at setColorized
        if (config.notificationColor != -1)
            builder.setColor(config.notificationColor);
        for (NotificationCompat.Action action : nativeActions) {
            builder.addAction(action);
        }
        final MediaStyle style = new MediaStyle()
            .setMediaSession(mediaSession.getSessionToken());
        if (Build.VERSION.SDK_INT < 33) {
            style.setShowActionsInCompactView(compactActionIndices);
        }
        if (config.androidNotificationOngoing) {
            style.setShowCancelButton(true);
            style.setCancelButtonIntent(buildMediaButtonPendingIntent(PlaybackStateCompat.ACTION_STOP));
            builder.setOngoing(true);
        }
        builder.setStyle(style);
        return builder.build();
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private /*synchronized*/ NotificationCompat.Builder getNotificationBuilder() {
        // This local variable could be commented out and replaced by an
        // instance variable if we want to reuse the builder instance. However,
        // there doesn't turn out to be much benefit to this since we don't
        // actually reuse any of the previous notification values when setting
        // a new notification.
        NotificationCompat.Builder notificationBuilder = null;
        if (notificationBuilder == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                createChannel();
            notificationBuilder = new NotificationCompat.Builder(this, notificationChannelId)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setShowWhen(false)
                    .setDeleteIntent(buildDeletePendingIntent())
            ;
        }
        int iconId = getResourceId(config.androidNotificationIcon);
        notificationBuilder.setSmallIcon(iconId);
        return notificationBuilder;
    }

    public void handleDeleteNotification() {
        callListener("AudioService.handleDeleteNotification", ServiceListener::onClose);
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private void createChannel() {
        NotificationManager notificationManager = getNotificationManager();
        NotificationChannel channel = notificationManager.getNotificationChannel(notificationChannelId);
        if (channel == null) {
            channel = new NotificationChannel(notificationChannelId, config.androidNotificationChannelName, NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(config.androidShowNotificationBadge);
            if (config.androidNotificationChannelDescription != null)
                channel.setDescription(config.androidNotificationChannelDescription);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void updateNotification() {
        if (notificationCreated) {
            getNotificationManager().notify(NOTIFICATION_ID, buildNotification());
        }
    }

    /**
     * Whether the last live state update was refused a foreground-service
     * start. Answers true once per refusal, so that it is reported to Dart
     * once per play rather than on every update.
     */
    boolean consumeForegroundStartRefusal() {
        final boolean refused = foregroundRefusalUnreported;
        foregroundRefusalUnreported = false;
        return refused;
    }

    /**
     * Enters the playing state if the handler reports playing but this
     * instance has not established it, at a point where Android is likely to
     * allow the foreground-service start (an Activity resumed, a state
     * replay). A refusal is logged and left for the next such point; a refused
     * replay is also reported to Dart as FOREGROUND_START_REFUSED, since this
     * instance's playing state reached Dart through no live update that could
     * report it. Any other failure is reported (see AudioServiceErrors) and not
     * retried here until playback restarts. Never throws: it runs from
     * lifecycle callbacks, where an exception would crash the app.
     */
    void retryForegroundIfPlaying(String reason) {
        if (!playing || playingStateEntered || foregroundFailure == ForegroundFailure.FAILED) return;
        log("foreground_retry", "serviceGeneration=" + serviceGeneration + " reason=" + reason);
        try {
            final RuntimeException refusal = enterPlayingState();
            if (refusal != null && REASON_STATE_REPLAY.equals(reason)) {
                AudioServiceErrors.report(FOREGROUND_START_REFUSED, refusal);
            }
        } catch (ForegroundStartFailedException e) {
            // enterPlayingState() recorded it as FAILED, so it is not retried
            // here again; a new play attempts it again from a live update.
            AudioServiceErrors.report(FOREGROUND_START_FAILED, e.getCause());
        } catch (RuntimeException e) {
            AudioServiceErrors.report("AudioService.retryForegroundIfPlaying", e);
        }
    }

    /** The error code with which a refused foreground start reaches Dart. */
    static final String FOREGROUND_START_REFUSED = "FOREGROUND_START_REFUSED";
    /**
     * The error code with which any other foreground start failure reaches
     * Dart. Unlike a refusal it is not expected to go away by itself.
     */
    static final String FOREGROUND_START_FAILED = "FOREGROUND_START_FAILED";

    /** A foreground start failed for a reason other than a refusal; see {@link #getCause()}. */
    static final class ForegroundStartFailedException extends RuntimeException {
        ForegroundStartFailedException(RuntimeException cause) {
            super(cause.getClass().getName() + ": " + cause.getMessage(), cause);
        }
    }
    private static final String REASON_STATE_REPLAY = "state_replay";

    /**
     * Starts the service in the foreground and only then takes the wake lock
     * and marks the playing state entered, so a failure leaves nothing half
     * established. A service still in the foreground, as a pause leaves it
     * when androidStopForegroundOnPause is false, is not promoted again: that
     * would only give Android a chance to refuse a foreground service this
     * instance already has. Returns the refusal if Android refused the start
     * from the background (Android 12+), which is retryable, and null on
     * success. Any other failure, such as a missing or invalid foreground
     * service type, propagates as a {@link ForegroundStartFailedException}. A
     * start that this attempt made is undone on failure.
     */
    private RuntimeException enterPlayingState() {
        // Neither is a held resource. The session stays active while playing
        // even if the start is refused, so that media buttons keep reaching
        // the handler; buildNotification() reads the session activity.
        activateMediaSession();
        mediaSession.setSessionActivity(contentIntent);
        if (inForeground) {
            log("foreground_start_skipped", "serviceGeneration=" + serviceGeneration
                    + " reason=already_in_foreground");
        } else {
            final boolean wasStartRequested = startRequested;
            boolean startedNow = false;
            try {
                foregroundPromoter.startForegroundService(this);
                startedNow = !wasStartRequested;
                startRequested = true;
                internalStartForeground();
            } catch (RuntimeException e) {
                final String fields = "serviceGeneration=" + serviceGeneration
                        + " error=" + e.getClass().getSimpleName();
                if (startedNow) {
                    // Android 12L refuses startForeground() after
                    // startForegroundService() went through, which left this
                    // service started: it would outlive its bindings, playing
                    // without a foreground service until Android stops it.
                    // Undo that start, so that it lives as long as a refusal
                    // of the first call would leave it (its bindings).
                    startRequested = false;
                    stopSelf();
                    log("service_start_rolled_back", fields);
                }
                if (isForegroundServiceStartNotAllowed(e)) {
                    foregroundFailure = ForegroundFailure.REFUSED;
                    log("foreground_start_refused", fields);
                    return e;
                }
                foregroundFailure = ForegroundFailure.FAILED;
                log("foreground_start_failed", fields);
                throw new ForegroundStartFailedException(e);
            }
        }
        // Last, so that a failure to take the wake lock leaves the playing
        // state unentered and the next attempt tries again (reusing the
        // foreground service).
        acquireWakeLock();
        playingStateEntered = true;
        foregroundFailure = ForegroundFailure.NONE;
        return null;
    }

    private static boolean isForegroundServiceStartNotAllowed(RuntimeException e) {
        return Build.VERSION.SDK_INT >= 31 && Api31.isForegroundServiceStartNotAllowed(e);
    }

    @RequiresApi(31)
    private static final class Api31 {
        static boolean isForegroundServiceStartNotAllowed(RuntimeException e) {
            return e instanceof ForegroundServiceStartNotAllowedException;
        }
    }

    private enum ForegroundFailure {
        NONE,
        /** Android refused a start from the background; retryable. */
        REFUSED,
        /** Any other failure; it is not expected to succeed on a retry. */
        FAILED
    }

    /**
     * The framework calls that put the service in the foreground and take it
     * out. Tests replace {@link #foregroundPromoter} to make them fail as
     * Android would, or to observe them.
     */
    interface ForegroundPromoter {
        void startForegroundService(AudioService service);

        void startForeground(AudioService service, int id, Notification notification);

        /** @param flags STOP_FOREGROUND_REMOVE or STOP_FOREGROUND_LEGACY */
        void stopForeground(AudioService service, int flags);
    }

    static final ForegroundPromoter FRAMEWORK_FOREGROUND_PROMOTER = new ForegroundPromoter() {
        @Override
        public void startForegroundService(AudioService service) {
            ContextCompat.startForegroundService(service, new Intent(service, AudioService.class));
        }

        @Override
        public void startForeground(AudioService service, int id, Notification notification) {
            service.startForeground(id, notification);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void stopForeground(AudioService service, int flags) {
            if (Build.VERSION.SDK_INT >= 24) {
                service.stopForeground(flags);
            } else {
                service.stopForeground((flags & STOP_FOREGROUND_REMOVE) != 0);
            }
        }
    };

    static volatile ForegroundPromoter foregroundPromoter = FRAMEWORK_FOREGROUND_PROMOTER;

    private void exitPlayingState() {
        playingStateEntered = false;
        if (config.androidStopForegroundOnPause) {
            exitForegroundState();
        }
    }

    private void exitForegroundState() {
        legacyStopForeground(false, "exit_playing_state");
        releaseWakeLock();
    }

    private void internalStartForeground() {
        final String fields = "serviceGeneration=" + serviceGeneration
                + " reason=enter_playing_state";
        log("foreground_start_requested", fields);
        foregroundPromoter.startForeground(this, NOTIFICATION_ID, buildNotification());
        log("foreground_started", fields);
        inForeground = true;
        notificationCreated = true;
    }

    private void acquireWakeLock() {
        if (!wakeLock.isHeld())
            wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock.isHeld())
            wakeLock.release();
    }

    private void activateMediaSession() {
        if (!mediaSession.isActive())
            mediaSession.setActive(true);
    }

    private void deactivateMediaSession() {
        if (mediaSession.isActive()) {
            mediaSession.setActive(false);
        }
        // Force cancellation of the notification
        getNotificationManager().cancel(NOTIFICATION_ID);
    }

    private void releaseMediaSession() {
        if (mediaSession == null) return;
        deactivateMediaSession();
        mediaSession.release();
        mediaSession = null;
    }

    /**
     * Updates queue.
     * Gets called from background thread.
     */
    synchronized void setQueue(List<MediaSessionCompat.QueueItem> queue) {
        AudioService.queue = queue;
        mediaSession.setQueue(queue);
    }

    void playMediaItem(MediaDescriptionCompat description) {
        mediaSessionCallback.onPlayMediaItem(description);
    }

    /**
     * Updates metadata, loads the art and updates the notification.
     * Gets called from background thread.
     * <p>
     * Also adds the loaded art bitmap to the MediaMetadata.
     * This is needed to display art in lock screen in versions
     * prior Android 11, in which this feature was removed.
     * <p>
     * See:
     *  - https://developer.android.com/guide/topics/media-apps/working-with-a-media-session#album_artwork
     *  - https://9to5google.com/2020/08/02/android-11-lockscreen-art/
     */
    synchronized void setMetadata(MediaMetadataCompat mediaMetadata) {
        String artCacheFilePath = mediaMetadata.getString("artCacheFile");
        if (artCacheFilePath != null) {
            // Load local files and network images, cached in files
            artBitmap = loadArtBitmap(artCacheFilePath, null);
            mediaMetadata = putArtToMetadata(mediaMetadata);
        } else {
            // Load content:// URIs
            String artUri = mediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI);
            if (artUri != null && artUri.startsWith("content:")) {
                String loadThumbnailUri = mediaMetadata.getString("loadThumbnailUri");
                artBitmap = loadArtBitmap(artUri, loadThumbnailUri);
                mediaMetadata = putArtToMetadata(mediaMetadata);
            } else {
                artBitmap = null;
            }
        }
        this.mediaMetadata = mediaMetadata;
        mediaSession.setMetadata(mediaMetadata);
        handler.removeCallbacksAndMessages(null);
        handler.post(() -> {
            // Posted, so nothing up the stack would catch a failure.
            try {
                updateNotification();
            } catch (RuntimeException e) {
                AudioServiceErrors.report("AudioService.updateNotification", e);
            }
        });
    }

    private MediaMetadataCompat putArtToMetadata(MediaMetadataCompat mediaMetadata) {
        return new MediaMetadataCompat.Builder(mediaMetadata)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artBitmap)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artBitmap)
                .build();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        // Unlike onBind, this runs for every MediaBrowser client and names it.
        log("service_browser_client_connected", "serviceGeneration=" + serviceGeneration
                + " clientPackage=" + clientPackageName + " clientUid=" + clientUid);
        Boolean isRecentRequest = rootHints == null ? null : (Boolean)rootHints.getBoolean(BrowserRoot.EXTRA_RECENT);
        if (isRecentRequest == null) isRecentRequest = false;
        Bundle extras = config.getBrowsableRootExtras();
        return new BrowserRoot(isRecentRequest ? RECENT_ROOT_ID : BROWSABLE_ROOT_ID, extras);
        // The response must be given synchronously, and we can't get a
        // synchronous response from the Dart layer. For now, we hardcode
        // the root to "root". This may improve in media2.
        //return listener.onGetRoot(clientPackageName, clientUid, rootHints);
    }

    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaBrowserCompat.MediaItem>> result) {
        onLoadChildren(parentMediaId, result, null);
    }

    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaBrowserCompat.MediaItem>> result, Bundle options) {
        final ServiceListener listener = AudioService.listener;
        if (listener == null) {
            result.sendResult(new ArrayList<>());
            return;
        }
        try {
            listener.onLoadChildren(parentMediaId, result, options);
        } catch (RuntimeException e) {
            AudioServiceErrors.report("AudioService.onLoadChildren", e);
            failIfUnanswered(result);
        }
    }

    @Override
    public void onLoadItem(String itemId, Result<MediaBrowserCompat.MediaItem> result) {
        final ServiceListener listener = AudioService.listener;
        if (listener == null) {
            result.sendResult(null);
            return;
        }
        try {
            listener.onLoadItem(itemId, result);
        } catch (RuntimeException e) {
            AudioServiceErrors.report("AudioService.onLoadItem", e);
            failIfUnanswered(result);
        }
    }

    @Override
    public void onSearch(String query, Bundle extras, Result<List<MediaBrowserCompat.MediaItem>> result) {
        final ServiceListener listener = AudioService.listener;
        if (listener == null) {
            result.sendResult(new ArrayList<>());
            return;
        }
        try {
            listener.onSearch(query, extras, result);
        } catch (RuntimeException e) {
            AudioServiceErrors.report("AudioService.onSearch", e);
            failIfUnanswered(result);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        log("service_task_removed", "serviceGeneration=" + serviceGeneration);
        callListener("AudioService.onTaskRemoved", ServiceListener::onTaskRemoved);
        super.onTaskRemoved(rootIntent);
    }

    /**
     * Called by the framework for commands from any media controller
     * (notification, lock screen, Bluetooth, Android Auto, other apps), so every
     * call into the listener goes through callListener().
     */
    public class MediaSessionCallback extends MediaSessionCompat.Callback {
        @Override
        public void onAddQueueItem(MediaDescriptionCompat description) {
            callListener("MediaSessionCallback.onAddQueueItem",
                    l -> l.onAddQueueItem(getMediaMetadata(description.getMediaId())));
        }

        @Override
        public void onAddQueueItem(MediaDescriptionCompat description, int index) {
            callListener("MediaSessionCallback.onAddQueueItem",
                    l -> l.onAddQueueItemAt(getMediaMetadata(description.getMediaId()), index));
        }

        @Override
        public void onRemoveQueueItem(MediaDescriptionCompat description) {
            callListener("MediaSessionCallback.onRemoveQueueItem",
                    l -> l.onRemoveQueueItem(getMediaMetadata(description.getMediaId())));
        }

        @Override
        public void onPrepare() {
            callListener("MediaSessionCallback.onPrepare", l -> {
                activateMediaSession();
                l.onPrepare();
            });
        }

        @Override
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
            callListener("MediaSessionCallback.onPrepareFromMediaId", l -> {
                activateMediaSession();
                l.onPrepareFromMediaId(mediaId, extras);
            });
        }

        @Override
        public void onPrepareFromSearch(String query, Bundle extras) {
            callListener("MediaSessionCallback.onPrepareFromSearch", l -> {
                activateMediaSession();
                l.onPrepareFromSearch(query, extras);
            });
        }

        @Override
        public void onPrepareFromUri(Uri uri, Bundle extras) {
            callListener("MediaSessionCallback.onPrepareFromUri", l -> {
                activateMediaSession();
                l.onPrepareFromUri(uri, extras);
            });
        }

        @Override
        public void onPlay() {
            callListener("MediaSessionCallback.onPlay", ServiceListener::onPlay);
        }

        @Override
        public void onPlayFromMediaId(final String mediaId, final Bundle extras) {
            callListener("MediaSessionCallback.onPlayFromMediaId", l -> l.onPlayFromMediaId(mediaId, extras));
        }

        @Override
        public void onPlayFromSearch(final String query, final Bundle extras) {
            callListener("MediaSessionCallback.onPlayFromSearch", l -> l.onPlayFromSearch(query, extras));
        }

        @Override
        public void onPlayFromUri(final Uri uri, final Bundle extras) {
            callListener("MediaSessionCallback.onPlayFromUri", l -> l.onPlayFromUri(uri, extras));
        }

        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
            if (listener == null) return false;
            final KeyEvent event;
            try {
                final Bundle extras = mediaButtonEvent != null ? mediaButtonEvent.getExtras() : null;
                // TODO: use typesafe version once SDK 33 is released.
                @SuppressWarnings("deprecation")
                final KeyEvent extra = extras != null ? (KeyEvent)extras.getParcelable(Intent.EXTRA_KEY_EVENT) : null;
                event = extra;
            } catch (RuntimeException e) {
                // The intent can come from any app; its extras may not unparcel.
                AudioServiceErrors.report("MediaSessionCallback.onMediaButtonEvent", e);
                return true;
            }
            if (event == null) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (event.getKeyCode()) {
                case KEYCODE_BYPASS_PLAY:
                    onPlay();
                    break;
                case KEYCODE_BYPASS_PAUSE:
                    onPause();
                    break;
                case KeyEvent.KEYCODE_MEDIA_STOP:
                    onStop();
                    break;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    onFastForward();
                    break;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    onRewind();
                    break;
                // Android unfortunately reroutes media button clicks to
                // KEYCODE_MEDIA_PLAY/PAUSE instead of the expected KEYCODE_HEADSETHOOK
                // or KEYCODE_MEDIA_PLAY_PAUSE. As a result, we can't genuinely tell if
                // onMediaButtonEvent was called because a media button was actually
                // pressed or because a PLAY/PAUSE action was pressed instead! To get
                // around this, we make PLAY and PAUSE actions use different keycodes:
                // KEYCODE_BYPASS_PLAY/PAUSE. Now if we get KEYCODE_MEDIA_PLAY/PUASE
                // we know it is actually a media button press.
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    // These are the "genuine" media button click events
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                    callListener("MediaSessionCallback.onMediaButtonEvent", l -> l.onClick(eventToButton(event)));
                    break;
                }
            }
            return true;
        }

        private MediaButton eventToButton(KeyEvent event) {
            switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return MediaButton.media;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return MediaButton.next;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return MediaButton.previous;
            default:
                return MediaButton.media;
            }
        }

        @Override
        public void onPause() {
            callListener("MediaSessionCallback.onPause", ServiceListener::onPause);
        }

        @Override
        public void onStop() {
            callListener("MediaSessionCallback.onStop", ServiceListener::onStop);
        }

        @Override
        public void onSkipToNext() {
            callListener("MediaSessionCallback.onSkipToNext", ServiceListener::onSkipToNext);
        }

        @Override
        public void onSkipToPrevious() {
            callListener("MediaSessionCallback.onSkipToPrevious", ServiceListener::onSkipToPrevious);
        }

        @Override
        public void onFastForward() {
            callListener("MediaSessionCallback.onFastForward", ServiceListener::onFastForward);
        }

        @Override
        public void onRewind() {
            callListener("MediaSessionCallback.onRewind", ServiceListener::onRewind);
        }

        @Override
        public void onSkipToQueueItem(long id) {
            callListener("MediaSessionCallback.onSkipToQueueItem", l -> l.onSkipToQueueItem(id));
        }

        @Override
        public void onSeekTo(long pos) {
            callListener("MediaSessionCallback.onSeekTo", l -> l.onSeekTo(pos));
        }

        @Override
        public void onSetRating(RatingCompat rating) {
            callListener("MediaSessionCallback.onSetRating", l -> l.onSetRating(rating));
        }

        @Override
        public void onSetPlaybackSpeed(float speed) {
            callListener("MediaSessionCallback.onSetPlaybackSpeed", l -> l.onSetPlaybackSpeed(speed));
        }

        @Override
        public void onSetCaptioningEnabled(boolean enabled) {
            callListener("MediaSessionCallback.onSetCaptioningEnabled", l -> l.onSetCaptioningEnabled(enabled));
        }

        @Override
        public void onSetRepeatMode(int repeatMode) {
            callListener("MediaSessionCallback.onSetRepeatMode", l -> l.onSetRepeatMode(repeatMode));
        }

        @Override
        public void onSetShuffleMode(int shuffleMode) {
            callListener("MediaSessionCallback.onSetShuffleMode", l -> l.onSetShuffleMode(shuffleMode));
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            callListener("MediaSessionCallback.onCustomAction", l -> {
                if (CUSTOM_ACTION_STOP.equals(action)) {
                    l.onStop();
                } else if (CUSTOM_ACTION_FAST_FORWARD.equals(action)) {
                    l.onFastForward();
                } else if (CUSTOM_ACTION_REWIND.equals(action)) {
                    l.onRewind();
                } else {
                    l.onCustomAction(action, extras);
                }
            });
        }

        @Override
        public void onSetRating(RatingCompat rating, Bundle extras) {
            callListener("MediaSessionCallback.onSetRating", l -> l.onSetRating(rating, extras));
        }

        //
        // NON-STANDARD METHODS
        //

        public void onPlayMediaItem(final MediaDescriptionCompat description) {
            callListener("MediaSessionCallback.onPlayMediaItem",
                    l -> l.onPlayMediaItem(getMediaMetadata(description.getMediaId())));
        }
    }

    public interface ServiceListener {
        //BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints);
        void onLoadChildren(String parentMediaId, Result<List<MediaBrowserCompat.MediaItem>> result, Bundle options);
        void onLoadItem(String itemId, Result<MediaBrowserCompat.MediaItem> result);
        void onSearch(String query, Bundle extras, Result<List<MediaBrowserCompat.MediaItem>> result);
        void onClick(MediaButton mediaButton);
        void onPrepare();
        void onPrepareFromMediaId(String mediaId, Bundle extras);
        void onPrepareFromSearch(String query, Bundle extras);
        void onPrepareFromUri(Uri uri, Bundle extras);
        void onPlay();
        void onPlayFromMediaId(String mediaId, Bundle extras);
        void onPlayFromSearch(String query, Bundle extras);
        void onPlayFromUri(Uri uri, Bundle extras);
        void onSkipToQueueItem(long id);
        void onPause();
        void onSkipToNext();
        void onSkipToPrevious();
        void onFastForward();
        void onRewind();
        void onStop();
        void onSeekTo(long pos);
        void onSetRating(RatingCompat rating);
        void onSetRating(RatingCompat rating, Bundle extras);
        void onSetRepeatMode(int repeatMode);
        void onSetShuffleMode(int shuffleMode);
        void onCustomAction(String action, Bundle extras);
        void onAddQueueItem(MediaMetadataCompat metadata);
        void onAddQueueItemAt(MediaMetadataCompat metadata, int index);
        void onRemoveQueueItem(MediaMetadataCompat metadata);
        void onRemoveQueueItemAt(int index);
        void onSetPlaybackSpeed(float speed);
        void onSetCaptioningEnabled(boolean enabled);
        void onSetVolumeTo(int volumeIndex);
        void onAdjustVolume(int direction);

        //
        // NON-STANDARD METHODS
        //

        void onPlayMediaItem(MediaMetadataCompat metadata);
        void onTaskRemoved();
        void onClose();
        /** A service instance was created (see AudioService.onCreate). */
        void onCreate();
        void onDestroy();
    }
}
