package com.kaua.adblock;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 1001;
    private static final int NOTIFICATION_REQUEST = 1002;
    private static final String PREFS = "adblock_prefs";
    private static final String LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";

    private WebView webView;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF667EEA);
        getWindow().setNavigationBarColor(0xFF0B1020);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.contains("trackers")) prefs.edit().putBoolean("trackers", true).apply();
        if (!prefs.contains("dns")) prefs.edit().putString("dns", "1.1.1.1").apply();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new Bridge(this), "AdBlock");
        webView.loadUrl("file:///android_asset/index.html");
    }

    public class Bridge {
        private final Context context;
        Bridge(Context c) { context = c; }

        @JavascriptInterface
        public String getState() {
            try {
                JSONObject o = new JSONObject();
                String today = todayKey();
                String storedDay = prefs.getString("stats_day", today);
                long todayCount = storedDay.equals(today) ? prefs.getLong("blocked_today", 0) : 0;
                o.put("running", AdBlockVpnService.running);
                o.put("total", prefs.getLong("blocked_total", 0));
                o.put("today", todayCount);
                o.put("trackers", prefs.getBoolean("trackers", true));
                o.put("dns", prefs.getString("dns", "1.1.1.1"));
                o.put("listCount", prefs.getInt("external_count", 0));
                o.put("listUpdated", prefs.getString("external_updated", "Nunca"));
                o.put("listStatus", prefs.getString("list_status", ""));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void start() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
                }
                Intent prepare = VpnService.prepare(MainActivity.this);
                if (prepare != null) {
                    startActivityForResult(prepare, VPN_REQUEST);
                } else {
                    startVpnService();
                }
            });
        }

        @JavascriptInterface
        public void stop() {
            Intent i = new Intent(MainActivity.this, AdBlockVpnService.class);
            i.setAction(AdBlockVpnService.ACTION_STOP);
            startService(i);
        }

        @JavascriptInterface
        public void openVpnSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
                catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void setTrackers(boolean enabled) {
            prefs.edit().putBoolean("trackers", enabled).apply();
        }

        @JavascriptInterface
        public void setDns(String dns) {
            if (!"1.1.1.1".equals(dns) && !"8.8.8.8".equals(dns) && !"9.9.9.9".equals(dns)) return;
            prefs.edit().putString("dns", dns).apply();
        }

        @JavascriptInterface
        public boolean addBlocked(String domain) {
            domain = normalizeDomain(domain);
            if (domain.isEmpty()) return false;
            Set<String> set = readSet("custom_blocked");
            set.add(domain);
            saveSet("custom_blocked", set);
            return true;
        }

        @JavascriptInterface
        public boolean removeBlocked(String domain) {
            Set<String> set = readSet("custom_blocked");
            boolean changed = set.remove(normalizeDomain(domain));
            saveSet("custom_blocked", set);
            return changed;
        }

        @JavascriptInterface
        public boolean addAllowed(String domain) {
            domain = normalizeDomain(domain);
            if (domain.isEmpty()) return false;
            Set<String> set = readSet("allowed");
            set.add(domain);
            saveSet("allowed", set);
            return true;
        }

        @JavascriptInterface
        public boolean removeAllowed(String domain) {
            Set<String> set = readSet("allowed");
            boolean changed = set.remove(normalizeDomain(domain));
            saveSet("allowed", set);
            return changed;
        }

        @JavascriptInterface
        public String getBlocked() { return setAsJson("custom_blocked"); }

        @JavascriptInterface
        public String getAllowed() { return setAsJson("allowed"); }

        @JavascriptInterface
        public String getRecent() {
            JSONArray a = new JSONArray();
            String raw = prefs.getString("recent_domains", "");
            for (String line : raw.split("\\n")) if (!line.trim().isEmpty()) a.put(line.trim());
            return a.toString();
        }

        @JavascriptInterface
        public void resetStats() {
            prefs.edit()
                .putLong("blocked_total", 0)
                .putLong("blocked_today", 0)
                .putString("stats_day", todayKey())
                .putString("recent_domains", "")
                .apply();
        }

        @JavascriptInterface
        public void updateList() {
            prefs.edit().putString("list_status", "Baixando lista...").apply();
            new Thread(() -> {
                HttpURLConnection connection = null;
                File temp = new File(getFilesDir(), "blocklist.tmp");
                File dest = new File(getFilesDir(), "blocklist.txt");
                int count = 0;
                try {
                    URL url = new URL(LIST_URL);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(20000);
                    connection.setRequestProperty("User-Agent", "KauaAdBlock/1.0");
                    connection.connect();
                    if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode());

                    try (BufferedReader br = new BufferedReader(new InputStreamReader(new BufferedInputStream(connection.getInputStream())));
                         FileOutputStream out = new FileOutputStream(temp)) {
                        String line;
                        StringBuilder buffer = new StringBuilder(65536);
                        while ((line = br.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith("#")) continue;
                            String[] parts = line.split("\\s+");
                            if (parts.length < 2) continue;
                            String host = normalizeDomain(parts[1]);
                            if (host.isEmpty() || "localhost".equals(host) || host.endsWith(".local")) continue;
                            buffer.append(host).append('\n');
                            count++;
                            if (buffer.length() > 60000) {
                                out.write(buffer.toString().getBytes("UTF-8"));
                                buffer.setLength(0);
                            }
                        }
                        if (buffer.length() > 0) out.write(buffer.toString().getBytes("UTF-8"));
                    }

                    if (dest.exists() && !dest.delete()) throw new Exception("Não foi possível substituir a lista antiga");
                    if (!temp.renameTo(dest)) throw new Exception("Não foi possível salvar a nova lista");

                    String stamp = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date());
                    prefs.edit()
                        .putInt("external_count", count)
                        .putString("external_updated", stamp)
                        .putString("list_status", "Lista atualizada")
                        .apply();

                    if (AdBlockVpnService.running) {
                        Intent reload = new Intent(MainActivity.this, AdBlockVpnService.class);
                        reload.setAction(AdBlockVpnService.ACTION_RELOAD);
                        startService(reload);
                    }
                } catch (Exception e) {
                    if (temp.exists()) temp.delete();
                    prefs.edit().putString("list_status", "Falha: " + e.getMessage()).apply();
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }, "filter-update").start();
        }

        private String setAsJson(String key) {
            JSONArray a = new JSONArray();
            for (String s : readSet(key)) a.put(s);
            return a.toString();
        }
    }

    private void startVpnService() {
        Intent i = new Intent(this, AdBlockVpnService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) startVpnService();
    }

    private Set<String> readSet(String key) {
        Set<String> result = new LinkedHashSet<>();
        String raw = prefs.getString(key, "");
        for (String s : raw.split("\\n")) {
            s = normalizeDomain(s);
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    private void saveSet(String key, Set<String> set) {
        StringBuilder b = new StringBuilder();
        for (String s : set) b.append(s).append('\n');
        prefs.edit().putString(key, b.toString()).apply();
    }

    private static String normalizeDomain(String d) {
        if (d == null) return "";
        d = d.trim().toLowerCase(Locale.ROOT);
        d = d.replaceFirst("^https?://", "");
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        int colon = d.indexOf(':');
        if (colon >= 0) d = d.substring(0, colon);
        while (d.startsWith(".")) d = d.substring(1);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        if (!d.matches("^[a-z0-9.-]+$") || !d.contains(".")) return "";
        return d;
    }

    private static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
