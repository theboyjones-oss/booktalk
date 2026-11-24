package com.booktalk.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps BookTalk alive and reading while the screen is off, shows the
 * playback notification and answers lock-screen / headset buttons.
 */
public class PlaybackService extends Service {

    static final String CHANNEL = "playback";
    static final int NOTIF_ID = 7;
    static final String A_UPDATE = "update", A_TOGGLE = "toggle", A_NEXT = "next", A_PREV = "prev", A_STOP = "stop";

    static volatile PlaybackService instance;

    private MediaSession session;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private boolean playing;
    private String title = "BookTalk", sub = "";
    private boolean noisyRegistered;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) MainActivity.sendMedia("pause");
        }
    };

    /** Called from the WebView bridge. */
    static void update(Context ctx, boolean active, boolean playing, String title, String sub) {
        new Handler(Looper.getMainLooper()).post(() -> updateOnMain(ctx, active, playing, title, sub));
    }

    private static void updateOnMain(Context ctx, boolean active, boolean playing, String title, String sub) {
        PlaybackService s = instance;
        if (!active) {
            if (s != null) s.shutdown();
            return;
        }
        if (s != null) {
            s.apply(playing, title, sub);
            return;
        }
        if (!playing) return; // only start the service when reading actually begins
        Intent i = new Intent(ctx, PlaybackService.class).setAction(A_UPDATE)
                .putExtra("playing", true).putExtra("title", title).putExtra("sub", sub);
        try {
            ctx.startForegroundService(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Reading aloud", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "BookTalk");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MainActivity.sendMedia("play"); }
            @Override public void onPause() { MainActivity.sendMedia("pause"); }
            @Override public void onSkipToNext() { MainActivity.sendMedia("next"); }
            @Override public void onSkipToPrevious() { MainActivity.sendMedia("prev"); }
            @Override public void onFastForward() { MainActivity.sendMedia("next"); }
            @Override public void onRewind() { MainActivity.sendMedia("prev"); }
            @Override public void onStop() { MainActivity.sendMedia("stop"); }
        });
        session.setSessionActivity(openAppIntent());
        session.setActive(true);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BookTalk:reading");
        wake.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BookTalk:wifi");
            wifi.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always go foreground straight away (required after startForegroundService)
        goForeground();
        String a = intent != null ? intent.getAction() : null;
        if (a == null || A_UPDATE.equals(a)) {
            if (intent != null) apply(intent.getBooleanExtra("playing", true), intent.getStringExtra("title"), intent.getStringExtra("sub"));
        } else if (A_STOP.equals(a)) {
            if (!MainActivity.sendMedia("stop")) shutdown();
        } else if (A_TOGGLE.equals(a)) {
            if (!MainActivity.sendMedia("toggle")) shutdown();
        } else if (A_NEXT.equals(a)) {
            MainActivity.sendMedia("next");
        } else if (A_PREV.equals(a)) {
            MainActivity.sendMedia("prev");
        }
        return START_NOT_STICKY;
    }

    private void goForeground() {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    void apply(boolean playing, String title, String sub) {
        this.playing = playing;
        if (title != null && !title.isEmpty()) this.title = title;
        if (sub != null) this.sub = sub;

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, this.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, this.sub)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "BookTalk")
                .build());

        if (playing) {
            try { wake.acquire(4 * 60 * 60 * 1000L); } catch (Exception ignored) { }
            try { if (wifi != null) wifi.acquire(); } catch (Exception ignored) { }
            if (!noisyRegistered) {
                IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED);
                else registerReceiver(noisy, f);
                noisyRegistered = true;
            }
        } else {
            releaseLocks();
        }
        getSystemService(NotificationManager.class).notify(NOTIF_ID, build());
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(a);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build() {
        Notification.Action prev = new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_n_prev), "Back", action(A_PREV, 1)).build();
        Notification.Action tog = new Notification.Action.Builder(Icon.createWithResource(this, playing ? R.drawable.ic_n_pause : R.drawable.ic_n_play), playing ? "Pause" : "Play", action(A_TOGGLE, 2)).build();
        Notification.Action next = new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_n_next), "Forward", action(A_NEXT, 3)).build();
        Notification.Action stop = new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_n_close), "Stop", action(A_STOP, 4)).build();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_n_book)
                .setContentTitle(title)
                .setContentText(sub)
                .setContentIntent(openAppIntent())
                .setDeleteIntent(action(A_STOP, 5))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(prev).addAction(tog).addAction(next).addAction(stop)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private void releaseLocks() {
        try { if (wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) { }
    }

    void shutdown() {
        releaseLocks();
        if (noisyRegistered) { try { unregisterReceiver(noisy); } catch (Exception ignored) { } noisyRegistered = false; }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // App swiped away from recents: stop reading.
        MainActivity.sendMedia("pause");
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        if (noisyRegistered) { try { unregisterReceiver(noisy); } catch (Exception ignored) { } }
        if (session != null) { session.setActive(false); session.release(); }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
