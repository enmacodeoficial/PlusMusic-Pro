```java
package __PACKAGE__;

import android.content.Context;
import android.content.Intent;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.getcapacitor.JSObject;

import java.io.File;

/** Real Android media player: ExoPlayer + MediaSession live outside the WebView lifecycle. */
@UnstableApi
public class PlusMusicPlaybackService extends MediaSessionService {
    public static final String ACTION_PLAY = "__PACKAGE__.PLAYBACK.PLAY";
    public static final String ACTION_PAUSE = "__PACKAGE__.PLAYBACK.PAUSE";
    public static final String ACTION_RESUME = "__PACKAGE__.PLAYBACK.RESUME";
    public static final String ACTION_STOP = "__PACKAGE__.PLAYBACK.STOP";
    public static final String EXTRA_TRACK_ID = "trackId";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_ALBUM = "album";
    public static final String EXTRA_COVER = "cover";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_VOLUME = "volume";
    public static final String EXTRA_SPEED = "playbackRate";

    private static final String TAG = "PlusMusicPlayback";
    private static final String PREFS = "plusmusic_native_playback";
    private static final float[] EQ_FREQS = new float[]{
        31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    };

    private static volatile PlusMusicPlaybackService instance;
    private ExoPlayer player;
    private MediaSession mediaSession;
    private Equalizer equalizer;
    private int equalizerSessionId = -1;
    private boolean eqEnabled = true;
    private float eqPreamp = 0f;
    private final float[] eqBands = new float[10];
    private float userVolume = 0.8f;
    private float playbackRate = 1f;
    private String lastError = "";
    private String currentTrackId = "";

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        loadSettings();

        AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build();

        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(attributes, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        player.setVolume(effectiveVolume());
        player.setPlaybackParameters(new PlaybackParameters(playbackRate, 1f));

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                    attachOrUpdateEqualizer();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    lastError = "";
                    attachOrUpdateEqualizer();
                } else if (player != null
                        && player.getPlaybackState() == Player.STATE_ENDED) {
                    releaseEqualizer();
                }
            }

            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                releaseEqualizer();
                attachOrUpdateEqualizer();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                // Compatible with Media3 versions that do not expose errorCodeName.
                lastError = error.getMessage() == null
                    ? "error de reproducción"
                    : error.getMessage();
                Log.e(TAG, lastError, error);
            }
        });

        mediaSession = new MediaSession.Builder(this, player).build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int result = super.onStartCommand(intent, flags, startId);
        if (intent == null || player == null) return result;

        String action = intent.getAction();

        try {
            if (ACTION_PLAY.equals(action)) {
                playFromIntent(intent);
            } else if (ACTION_PAUSE.equals(action)) {
                player.pause();
            } else if (ACTION_RESUME.equals(action)) {
                player.play();
            } else if (ACTION_STOP.equals(action)) {
                player.stop();
                player.clearMediaItems();
                currentTrackId = "";
                releaseEqualizer();
            }
        } catch (Exception e) {
            lastError = e.getMessage() == null
                ? e.getClass().getSimpleName()
                : e.getMessage();
            Log.e(TAG, "Error procesando la orden de reproducción", e);
        }

        return result;
    }

    private void playFromIntent(Intent intent) {
        String trackId = intent.getStringExtra(EXTRA_TRACK_ID);
        String url = intent.getStringExtra(EXTRA_URL);
        String title = value(intent.getStringExtra(EXTRA_TITLE), "PlusMusic");
        String artist = value(intent.getStringExtra(EXTRA_ARTIST), "Artista desconocido");
        String album = value(intent.getStringExtra(EXTRA_ALBUM), "PlusMusic");
        String cover = intent.getStringExtra(EXTRA_COVER);

        long position = Math.max(0L, intent.getLongExtra(EXTRA_POSITION, 0L));
        userVolume = clamp(intent.getFloatExtra(EXTRA_VOLUME, userVolume), 0f, 1f);
        playbackRate = clamp(intent.getFloatExtra(EXTRA_SPEED, playbackRate), 0.5f, 2f);

        player.setVolume(effectiveVolume());
        player.setPlaybackParameters(new PlaybackParameters(playbackRate, 1f));

        Uri mediaUri;

        if (!TextUtils.isEmpty(trackId)) {
            File audioDir = new File(getFilesDir(), "plusmusic-audio");
            String safeId = trackId.trim().replaceAll("[^A-Za-z0-9_-]", "_");

            if (safeId.length() > 120) {
                safeId = safeId.substring(0, 120);
            }

            File file = new File(audioDir, safeId + ".audio");

            if (!file.isFile() || file.length() == 0) {
                throw new IllegalStateException(
                    "No se encontró el archivo local " + safeId
                );
            }

            mediaUri = Uri.fromFile(file);
            currentTrackId = trackId;
        } else if (!TextUtils.isEmpty(url)) {
            mediaUri = Uri.parse(url);
            currentTrackId = "";
        } else {
            throw new IllegalArgumentException(
                "No se recibió ninguna fuente de audio"
            );
        }

        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album);

        if (!TextUtils.isEmpty(cover)
                && (cover.startsWith("https://")
                || cover.startsWith("http://")
                || cover.startsWith("content://"))) {
            metadata.setArtworkUri(Uri.parse(cover));
        }

        MediaItem item = new MediaItem.Builder()
            .setMediaId(TextUtils.isEmpty(trackId) ? mediaUri.toString() : trackId)
            .setUri(mediaUri)
            .setMediaMetadata(metadata.build())
            .build();

        lastError = "";
        player.setMediaItem(item, position);
        player.prepare();
        player.play();
        attachOrUpdateEqualizer();
    }

    private void loadSettings() {
        android.content.SharedPreferences prefs =
            getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        userVolume = clamp(prefs.getFloat("volume", 0.8f), 0f, 1f);
        playbackRate = clamp(prefs.getFloat("speed", 1f), 0.5f, 2f);
        eqEnabled = prefs.getBoolean("eq_enabled", true);
        eqPreamp = clamp(prefs.getFloat("eq_preamp", 0f), -6f, 6f);

        for (int i = 0; i < eqBands.length; i++) {
            eqBands[i] = clamp(
                prefs.getFloat("eq_band_" + i, 0f), -12f, 12f
            );
        }
    }

    private float effectiveVolume() {
        double preamp = Math.pow(10d, eqEnabled ? eqPreamp / 20d : 0d);
        return (float) Math.max(0d, Math.min(1d, userVolume * preamp));
    }

    private void attachOrUpdateEqualizer() {
        if (player == null) return;

        try {
            int sessionId = player.getAudioSessionId();
            if (sessionId <= 0) return;

            if (equalizer == null || equalizerSessionId != sessionId) {
                releaseEqualizer();
                equalizer = new Equalizer(0, sessionId);
                equalizerSessionId = sessionId;
            }

            short[] range = equalizer.getBandLevelRange();
            short count = equalizer.getNumberOfBands();

            for (short nativeBand = 0; nativeBand < count; nativeBand++) {
                int[] hzRange = equalizer.getBandFreqRange(nativeBand);
                double low = Math.max(1d, hzRange[0] / 1000d);
                double high = Math.max(low, hzRange[1] / 1000d);
                float centre = (float) Math.sqrt(low * high);

                int nearest = 0;
                double nearestDistance = Double.MAX_VALUE;

                for (int i = 0; i < EQ_FREQS.length; i++) {
                    double distance = Math.abs(Math.log(centre / EQ_FREQS[i]));

                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = i;
                    }
                }

                int targetMillibels = Math.round(
                    eqEnabled ? eqBands[nearest] * 100f : 0f
                );

                targetMillibels = Math.max(
                    range[0], Math.min(range[1], targetMillibels)
                );

                equalizer.setBandLevel(nativeBand, (short) targetMillibels);
            }

            equalizer.setEnabled(eqEnabled);
            player.setVolume(effectiveVolume());
        } catch (Throwable e) {
            // Playback must continue if the device does not expose Equalizer.
            Log.w(
                TAG,
                "El ecualizador nativo no está disponible en este dispositivo",
                e
            );
            releaseEqualizer();
        }
    }

    private void releaseEqualizer() {
        try {
            if (equalizer != null) {
                equalizer.setEnabled(false);
                equalizer.release();
            }
        } catch (Exception ignored) {
        }

        equalizer = null;
        equalizerSessionId = -1;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static boolean seek(long positionMs) {
        PlusMusicPlaybackService s = instance;

        if (s == null || s.player == null || s.player.getMediaItemCount() == 0) {
            return false;
        }

        s.player.seekTo(Math.max(0L, positionMs));
        return true;
    }

    public static void setUserVolume(float volume) {
        PlusMusicPlaybackService s = instance;

        if (s != null && s.player != null) {
            s.userVolume = clamp(volume, 0f, 1f);
            s.player.setVolume(s.effectiveVolume());
        }
    }

    public static void setPlaybackRate(float rate) {
        PlusMusicPlaybackService s = instance;

        if (s != null && s.player != null) {
            s.playbackRate = clamp(rate, 0.5f, 2f);
            s.player.setPlaybackParameters(
                new PlaybackParameters(s.playbackRate, 1f)
            );
        }
    }

    public static void setEqualizerSettings(
            boolean enabled, float preamp, float[] bands) {
        PlusMusicPlaybackService s = instance;
        if (s == null) return;

        s.eqEnabled = enabled;
        s.eqPreamp = clamp(preamp, -6f, 6f);

        if (bands != null) {
            for (int i = 0; i < Math.min(bands.length, s.eqBands.length); i++) {
                s.eqBands[i] = clamp(bands[i], -12f, 12f);
            }
        }

        s.attachOrUpdateEqualizer();
    }

    public static boolean isEqualizerApplied() {
        PlusMusicPlaybackService s = instance;
        return s != null && s.equalizer != null && s.equalizerSessionId > 0;
    }

    public static JSObject getStatus() {
        JSObject result = new JSObject();
        PlusMusicPlaybackService s = instance;

        if (s == null || s.player == null) {
            result.put("isPlaying", false);
            result.put("position", 0);
            result.put("duration", 0);
            result.put("state", "idle");
            result.put("trackId", "");
            result.put("error", "");
            result.put("equalizerApplied", false);
            return result;
        }

        int state = s.player.getPlaybackState();

        String stateName =
            state == Player.STATE_BUFFERING ? "buffering"
            : state == Player.STATE_READY ? "ready"
            : state == Player.STATE_ENDED ? "ended"
            : "idle";

        long duration = s.player.getDuration();

        result.put("isPlaying", s.player.isPlaying());
        result.put("position", Math.max(0L, s.player.getCurrentPosition()));
        result.put(
            "duration",
            duration < 0 || duration == C.TIME_UNSET ? 0L : duration
        );
        result.put("state", stateName);
        result.put(
            "trackId",
            s.player.getCurrentMediaItem() == null
                ? ""
                : s.player.getCurrentMediaItem().mediaId
        );
        result.put("error", s.lastError);
        result.put("equalizerApplied", isEqualizerApplied());

        return result;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String value(String input, String fallback) {
        return input == null || input.trim().isEmpty()
            ? fallback
            : input.trim();
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        releaseEqualizer();

        if (mediaSession != null) {
            if (mediaSession.getPlayer() != null) {
                mediaSession.getPlayer().release();
            }

            mediaSession.release();
            mediaSession = null;
        }

        player = null;

        if (instance == this) {
            instance = null;
        }

        super.onDestroy();
    }
}
