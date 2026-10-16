package __PACKAGE__;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Capacitor bridge to the native Media3 player used by Android APK builds. */
@CapacitorPlugin(name = "NativePlayback")
public class NativePlaybackPlugin extends Plugin {
    private static final String PREFS = "plusmusic_native_playback";

    @PluginMethod
    public void beginLocalTrack(PluginCall call) {
        try {
            File file = partialFile(call.getString("trackId"));
            File dir = file.getParentFile();
            if (dir == null || (!dir.exists() && !dir.mkdirs())) throw new IOException("No se pudo crear la carpeta de audio");
            if (file.exists() && !file.delete()) throw new IOException("No se pudo reiniciar el archivo temporal");
            if (!file.createNewFile()) throw new IOException("No se pudo preparar el archivo temporal");
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo preparar el audio local para Android", e);
        }
    }

    @PluginMethod
    public void writeLocalTrackChunk(PluginCall call) {
        try {
            String data = call.getString("data");
            if (data == null) throw new IOException("El bloque de audio está vacío");
            // Keep bridge payloads bounded; the web side sends chunks of about 192 KiB.
            if (data.length() > 800_000) throw new IOException("El bloque de audio supera el tamaño permitido");
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            File file = partialFile(call.getString("trackId"));
            if (!file.exists()) throw new IOException("No se ha iniciado la transferencia del audio");
            try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(bytes); }
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo guardar un bloque del audio local", e);
        }
    }

    @PluginMethod
    public void finishLocalTrack(PluginCall call) {
        try {
            String id = safeId(call.getString("trackId"));
            File part = new File(audioDir(), id + ".part");
            File done = new File(audioDir(), id + ".audio");
            if (!part.isFile() || part.length() <= 0) throw new IOException("El archivo de audio está vacío o incompleto");
            if (done.exists() && !done.delete()) throw new IOException("No se pudo sustituir el audio almacenado");
            if (!part.renameTo(done)) throw new IOException("No se pudo completar el archivo de audio");
            JSObject ret = new JSObject();
            ret.put("bytes", done.length());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("No se pudo finalizar el archivo de audio local", e);
        }
    }

    @PluginMethod
    public void hasLocalTrack(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            File f = completeFile(call.getString("trackId"));
            ret.put("exists", f.isFile() && f.length() > 0);
            ret.put("bytes", f.isFile() ? f.length() : 0);
            call.resolve(ret);
        } catch (Exception e) {
            ret.put("exists", false);
            call.resolve(ret);
        }
    }

    @PluginMethod
    public void deleteLocalTrack(PluginCall call) {
        try {
            String id = safeId(call.getString("trackId"));
            File dir = audioDir();
            File complete = new File(dir, id + ".audio");
            File partial = new File(dir, id + ".part");
            if (complete.exists() && !complete.delete()) throw new IOException("No se pudo borrar el audio nativo almacenado");
            if (partial.exists() && !partial.delete()) throw new IOException("No se pudo borrar el audio temporal");
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo eliminar la copia nativa del audio", e);
        }
    }

    @PluginMethod
    public void play(PluginCall call) {
        try {
            String trackId = call.getString("trackId");
            String url = call.getString("url");
            if ((trackId == null || trackId.trim().isEmpty()) && (url == null || url.trim().isEmpty())) {
                call.reject("Falta la dirección o el identificador del audio");
                return;
            }
            if (trackId != null && !trackId.trim().isEmpty()) {
                File f = completeFile(trackId);
                if (!f.isFile() || f.length() <= 0) {
                    call.reject("El archivo local aún no está disponible en el reproductor nativo");
                    return;
                }
            }
            Intent intent = new Intent(getContext(), PlusMusicPlaybackService.class);
            intent.setAction(PlusMusicPlaybackService.ACTION_PLAY);
            putIfNotNull(intent, PlusMusicPlaybackService.EXTRA_TRACK_ID, trackId);
            putIfNotNull(intent, PlusMusicPlaybackService.EXTRA_URL, url);
            putIfNotNull(intent, PlusMusicPlaybackService.EXTRA_TITLE, call.getString("title"));
            putIfNotNull(intent, PlusMusicPlaybackService.EXTRA_ARTIST, call.getString("artist"));
            putIfNotNull(intent, PlusMusicPlaybackService.EXTRA_ALBUM, call.getString("album"));
            putIfNotNull(intent, PlusMusicPlaybackService.EXTRA_COVER, call.getString("cover"));
            intent.putExtra(PlusMusicPlaybackService.EXTRA_POSITION, Math.max(0L, call.getLong("position", 0L)));
            intent.putExtra(PlusMusicPlaybackService.EXTRA_VOLUME, (float) Math.max(0d, Math.min(1d, call.getDouble("volume", 0.8d))));
            intent.putExtra(PlusMusicPlaybackService.EXTRA_SPEED, (float) Math.max(0.5d, Math.min(2d, call.getDouble("playbackRate", 1d))));
            startServiceCompat(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo iniciar el reproductor nativo", e);
        }
    }

    @PluginMethod public void pause(PluginCall call) { command(PlusMusicPlaybackService.ACTION_PAUSE, call); }
    @PluginMethod public void resume(PluginCall call) { command(PlusMusicPlaybackService.ACTION_RESUME, call); }
    @PluginMethod public void stop(PluginCall call) { command(PlusMusicPlaybackService.ACTION_STOP, call); }

    @PluginMethod
    public void seek(PluginCall call) {
        try {
            long position = Math.max(0L, call.getLong("position", 0L));
            if (!PlusMusicPlaybackService.seek(position)) { call.reject("El reproductor nativo no está preparado para buscar"); return; }
            call.resolve();
        } catch (Exception e) { call.reject("No se pudo cambiar la posición de reproducción", e); }
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        try {
            float value = (float) Math.max(0d, Math.min(1d, call.getDouble("volume", 0.8d)));
            getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("volume", value).apply();
            PlusMusicPlaybackService.setUserVolume(value);
            call.resolve();
        } catch (Exception e) { call.reject("No se pudo cambiar el volumen", e); }
    }

    @PluginMethod
    public void setPlaybackRate(PluginCall call) {
        try {
            float value = (float) Math.max(0.5d, Math.min(2d, call.getDouble("playbackRate", 1d)));
            getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("speed", value).apply();
            PlusMusicPlaybackService.setPlaybackRate(value);
            call.resolve();
        } catch (Exception e) { call.reject("No se pudo cambiar la velocidad", e); }
    }

    @PluginMethod
    public void setEqualizer(PluginCall call) {
        try {
            boolean enabled = call.getBoolean("enabled", true);
            float preamp = (float) Math.max(-6d, Math.min(6d, call.getDouble("preamp", 0d)));
            JSArray array = call.getArray("bands");
            float[] bands = new float[10];
            for (int i = 0; i < bands.length; i++) {
                double value = array != null && i < array.length() ? array.optDouble(i, 0d) : 0d;
                bands[i] = (float) Math.max(-12d, Math.min(12d, value));
            }
            android.content.SharedPreferences.Editor editor = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            editor.putBoolean("eq_enabled", enabled).putFloat("eq_preamp", preamp);
            for (int i = 0; i < bands.length; i++) editor.putFloat("eq_band_" + i, bands[i]);
            editor.apply();
            PlusMusicPlaybackService.setEqualizerSettings(enabled, preamp, bands);
            JSObject ret = new JSObject();
            ret.put("applied", PlusMusicPlaybackService.isEqualizerApplied());
            call.resolve(ret);
        } catch (Exception e) { call.reject("No se pudo aplicar el ecualizador nativo", e); }
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        try { call.resolve(PlusMusicPlaybackService.getStatus()); }
        catch (Exception e) { call.reject("No se pudo consultar el estado del audio nativo", e); }
    }

    private void command(String action, PluginCall call) {
        try {
            if (!PlusMusicPlaybackService.isRunning()) {
                call.reject("El servicio de reproducción no está activo");
                return;
            }
            Intent intent = new Intent(getContext(), PlusMusicPlaybackService.class).setAction(action);
            getContext().startService(intent);
            call.resolve();
        } catch (Exception e) { call.reject("No se pudo enviar el control al reproductor nativo", e); }
    }

    private void startServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getContext().startForegroundService(intent);
        else getContext().startService(intent);
    }

    private File audioDir() throws IOException {
        File dir = new File(getContext().getFilesDir(), "plusmusic-audio");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("No se pudo crear el almacenamiento de audio");
        return dir;
    }
    private File partialFile(String rawId) throws IOException { return new File(audioDir(), safeId(rawId) + ".part"); }
    private File completeFile(String rawId) throws IOException { return new File(audioDir(), safeId(rawId) + ".audio"); }
    private String safeId(String raw) throws IOException {
        if (raw == null || raw.trim().isEmpty()) throw new IOException("Falta el identificador de la canción");
        String value = raw.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (value.length() > 120) value = value.substring(0, 120);
        if (value.isEmpty()) throw new IOException("El identificador de la canción no es válido");
        return value;
    }
    private void putIfNotNull(Intent intent, String key, String value) { if (value != null) intent.putExtra(key, value); }
}
