package jp.mk.batteryhub;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * アプリ内アップデート確認（v1.6〜）。
 *
 * 確認先は2段構え:
 * 1. GitHub Releases の最新リリース（タグ名＝バージョン、APKアセットがあれば直接DL可能）
 * 2. リポジトリ直下の version.json（Releases が無い場合のフォールバック。
 *    バージョン情報だけを持ち、取得は GitHub ページへ誘導する）
 *
 * リポジトリは公開なので認証なしで読める。未認証 API の制限（60回/時）に
 * 当たらないよう、自動チェックは MainActivity 側で6時間に1回へ絞っている。
 */
public class UpdateChecker {

    public static final String OWNER = "munou-hub";
    public static final String REPO = "BatteryHub";
    public static final String RELEASES_PAGE_URL =
            "https://github.com/" + OWNER + "/" + REPO + "/releases";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";
    private static final String VERSION_JSON_URL =
            "https://raw.githubusercontent.com/" + OWNER + "/" + REPO + "/main/version.json";

    public static class Info {
        public String versionName;
        public long versionCode; // 不明な場合は -1（Releases経由）
        public String apkUrl;    // 直接ダウンロードできるAPK。なければ null
        public String pageUrl;   // GitHub側のページ
        public String notes;     // リリースノート等（短く切る）
        public String source;    // "release" / "version.json"
    }

    /** 直近のチェック失敗理由（手動チェック時の表示用）。 */
    public static volatile String lastError = "";

    /** インストール済みバージョンより新しいものがあれば Info、なければ null。ワーカースレッド専用。 */
    public static Info check(Context ctx) {
        lastError = "";
        long installedCode = installedVersionCode(ctx);
        String installedName = installedVersionName(ctx);

        // 1) GitHub Releases
        try {
            JSONObject o = getJson(LATEST_RELEASE_API);
            if (o != null) {
                String tag = o.optString("tag_name", "");
                String name = tag.startsWith("v") || tag.startsWith("V")
                        ? tag.substring(1) : tag;
                if (!name.isEmpty() && compareVersionNames(name, installedName) > 0) {
                    Info info = new Info();
                    info.versionName = name;
                    info.versionCode = -1;
                    info.pageUrl = o.optString("html_url", RELEASES_PAGE_URL);
                    info.notes = truncate(o.optString("body", ""), 400);
                    info.source = "release";
                    JSONArray assets = o.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.optJSONObject(i);
                            if (a == null) continue;
                            String an = a.optString("name", "");
                            if (an.toLowerCase().endsWith(".apk")) {
                                info.apkUrl = a.optString("browser_download_url", null);
                                break;
                            }
                        }
                    }
                    return info;
                }
                // Releases が最新を指している場合は version.json を見るまでもない、とは
                // 限らない（version.json の方が新しい運用もあり得る）ため次へ進む
            }
        } catch (Exception e) {
            lastError = "Releases確認: " + e.getMessage();
        }

        // 2) version.json
        try {
            JSONObject o = getJson(VERSION_JSON_URL);
            if (o != null) {
                long code = o.optLong("versionCode", -1);
                if (code > installedCode) {
                    Info info = new Info();
                    info.versionName = o.optString("versionName", "");
                    info.versionCode = code;
                    info.apkUrl = o.optString("apkUrl", null);
                    if (info.apkUrl != null && info.apkUrl.isEmpty()) info.apkUrl = null;
                    info.pageUrl = o.optString("pageUrl", RELEASES_PAGE_URL);
                    info.notes = truncate(o.optString("notes", ""), 400);
                    info.source = "version.json";
                    return info;
                }
            }
        } catch (Exception e) {
            if (lastError.isEmpty()) lastError = "version.json確認: " + e.getMessage();
        }
        return null;
    }

    private static JSONObject getJson(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "BatteryHub-Android");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static long installedVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String installedVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** "1.6" と "1.10" のようなドット区切り数値比較。新しい方が正。 */
    static int compareVersionNames(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return va - vb;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            // "6-beta" のような接尾辞は数値部分だけ見る
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) sb.append(c); else break;
            }
            return sb.length() == 0 ? 0 : Integer.parseInt(sb.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
