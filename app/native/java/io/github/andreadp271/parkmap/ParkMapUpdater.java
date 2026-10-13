package io.github.andreadp271.parkmap;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Aggiornamenti dalle Releases di GitHub, come in Capotreno: controllo (con
 * ripiego sulla pagina delle release se l'API è esaurita), download dell'APK
 * con avanzamento, verifica di firma e versione, installazione tramite
 * PackageInstaller (Android chiede conferma). Se l'app è stata installata da
 * F-Droid gli aggiornamenti restano a quello store.
 *
 * Lo stato è statico: la pagina lo legge a intervalli con updateStatus.
 */
public final class ParkMapUpdater {
    private static final String REPO = "AndreaDp271/Park_finder";
    private static final String PAGE = "https://github.com/" + REPO;
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=10";
    private static final String TAG_PREFIX = "android-v";

    /** Client F-Droid noti: se uno di questi ha installato l'app, gli aggiornamenti passano da lì. */
    private static final Set<String> STORES = new HashSet<>(Arrays.asList(
        "org.fdroid.fdroid", "org.fdroid.basic", "org.fdroid.fdroid.privileged",
        "com.looker.droidify", "com.machiav3lli.fdroid", "nya.kitsunyan.foxydroid", "eu.bubu1.fdroidclassic"));

    /** idle | downloading | installing | failed */
    public static volatile String phase = "idle";
    /** 0..1, oppure -1 se la dimensione non è nota */
    public static volatile float progress = -1f;
    /** Codice dell'errore (la pagina lo traduce): download, invalid, signature, notnewer, downgrade, storage, incompatible, failed */
    public static volatile String error = null;
    public static volatile String detail = null;

    private ParkMapUpdater() {}

    static final class UpdateException extends IOException {
        final String code;
        final String detail;
        UpdateException(String code, String detail) { super(code + (detail != null ? " " + detail : "")); this.code = code; this.detail = detail; }
    }

    // ─── Informazioni sull'installazione ───

    @SuppressWarnings("deprecation")
    public static String installer(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return ctx.getPackageManager().getInstallSourceInfo(ctx.getPackageName()).getInstallingPackageName();
            return ctx.getPackageManager().getInstallerPackageName(ctx.getPackageName());
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean fromFDroid(Context ctx) {
        String i = installer(ctx);
        return i != null && STORES.contains(i);
    }

    public static boolean canInstall(Context ctx) {
        return Build.VERSION.SDK_INT < 26 || ctx.getPackageManager().canRequestPackageInstalls();
    }

    // ─── Ultima release ───

    private static HttpURLConnection open(String url, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestProperty("User-Agent", "ParkMap-Android (+" + PAGE + ")");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    /** {version, apkUrl, notes, page, publishedAt}. L'API ha un limite di richieste: se fallisce si ripiega sulla pagina. */
    public static JSONObject latest() throws Exception {
        try {
            return fromApi();
        } catch (UnknownHostException e) {
            throw e;
        } catch (Exception e) {
            try {
                return fromPage();
            } catch (Exception ignored) {
                throw e;
            }
        }
    }

    private static JSONObject fromApi() throws Exception {
        HttpURLConnection c = open(API, 20000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        if (c.getResponseCode() != 200) throw new IOException("GitHub ha risposto " + c.getResponseCode());
        JSONArray list;
        try (InputStream in = c.getInputStream()) { list = new JSONArray(readAll(in)); }
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.getJSONObject(i);
            String tag = r.optString("tag_name");
            if (r.optBoolean("draft") || r.optBoolean("prerelease") || !tag.startsWith(TAG_PREFIX)) continue;
            JSONArray assets = r.optJSONArray("assets");
            String apk = null;
            for (int j = 0; assets != null && j < assets.length(); j++) {
                JSONObject a = assets.getJSONObject(j);
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break; }
            }
            if (apk == null) continue;
            JSONObject out = new JSONObject();
            out.put("version", tag.substring(TAG_PREFIX.length()));
            out.put("apkUrl", apk);
            out.put("notes", r.optString("body", "").trim());
            out.put("page", r.optString("html_url"));
            out.put("publishedAt", r.optString("published_at"));
            return out;
        }
        throw new IOException("Nessuna release Android trovata");
    }

    /** Senza API: il tag arriva dal redirect di /releases/latest. */
    private static JSONObject fromPage() throws Exception {
        HttpURLConnection c = open(PAGE + "/releases/latest", 20000);
        c.setInstanceFollowRedirects(false);
        String location = c.getHeaderField("Location");
        if (location == null || !location.contains("/tag/")) throw new IOException("Nessuna release trovata");
        String tag = location.substring(location.lastIndexOf("/tag/") + 5);
        if (!tag.startsWith(TAG_PREFIX)) throw new IOException("Nessuna release Android trovata");
        String version = tag.substring(TAG_PREFIX.length());
        JSONObject out = new JSONObject();
        out.put("version", version);
        out.put("apkUrl", PAGE + "/releases/download/" + tag + "/ParkMap-" + version + ".apk");
        out.put("notes", "");
        out.put("page", location);
        return out;
    }

    // ─── Download e installazione ───

    /** Avvia il download e l'installazione in background; lo stato si legge dalle variabili statiche. */
    public static synchronized void start(final Context ctx, final String url, final String version) {
        if ("downloading".equals(phase)) return;
        phase = "downloading";
        progress = -1f;
        error = null;
        detail = null;
        new Thread(() -> {
            try {
                if (url == null || !url.startsWith(PAGE + "/releases/download/")) throw new UpdateException("invalid", null);
                File file = download(ctx, url, version);
                phase = "installing";
                install(ctx, file);
            } catch (UpdateException e) {
                finish(e.code, e.detail);
            } catch (Exception e) {
                finish("failed", e.getMessage());
            }
        }, "parkmap-update").start();
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }

    private static File download(Context ctx, String url, String version) throws Exception {
        File dir = new File(ctx.getCacheDir(), "update");
        deleteRecursively(dir);
        dir.mkdirs();
        File file = new File(dir, "ParkMap-" + version + ".apk");
        HttpURLConnection c = open(url, 60000);
        int code = c.getResponseCode();
        if (code != 200) throw new UpdateException("download", String.valueOf(code));
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) progress = (float) done / total;
            }
        }
        verify(ctx, file);
        return file;
    }

    private static Set<String> signers(PackageInfo p) throws Exception {
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28) sigs = p.signingInfo != null ? p.signingInfo.getApkContentsSigners() : null;
        else sigs = p.signatures;
        Set<String> out = new HashSet<>();
        if (sigs == null) return out;
        for (Signature s : sigs) {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            out.add(sb.toString());
        }
        return out;
    }

    /** L'APK deve essere della stessa app, più recente e firmato con la stessa chiave. */
    @SuppressWarnings("deprecation")
    private static void verify(Context ctx, File file) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo apk = pm.getPackageArchiveInfo(file.getPath(), flags);
        if (apk == null) { file.delete(); throw new UpdateException("invalid", null); }
        PackageInfo me = pm.getPackageInfo(ctx.getPackageName(), flags);
        Set<String> mine = signers(me);
        if (!apk.packageName.equals(ctx.getPackageName()) || mine.isEmpty() || !mine.equals(signers(apk))) {
            file.delete();
            throw new UpdateException("signature", null);
        }
        long theirs = Build.VERSION.SDK_INT >= 28 ? apk.getLongVersionCode() : apk.versionCode;
        long ours = Build.VERSION.SDK_INT >= 28 ? me.getLongVersionCode() : me.versionCode;
        if (theirs <= ours) { file.delete(); throw new UpdateException("notnewer", null); }
    }

    private static void install(Context ctx, File file) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("base.apk", 0, file.length()); InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent intent = new Intent(ctx, UpdateReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).getIntentSender());
        }
    }

    /** Fine dell'installazione: code null = riuscita o annullata. */
    static void finish(String code, String det) {
        if (code == null) {
            phase = "idle";
            error = null;
            detail = null;
        } else {
            phase = "failed";
            error = code;
            detail = det;
        }
    }
}
