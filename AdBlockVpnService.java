package com.kaua.adblock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

public class AdBlockVpnService extends VpnService {
    public static final String ACTION_STOP = "com.kaua.adblock.STOP";
    public static final String ACTION_RELOAD = "com.kaua.adblock.RELOAD";
    public static final String ACTION_RESTART = "com.kaua.adblock.RESTART";
    public static volatile boolean running = false;

    private static final String PREFS = "adblock_prefs";
    private static final String CHANNEL_ID = "adblock_protection";
    private static final int NOTIFICATION_ID = 1001;

    private ParcelFileDescriptor vpnInterface;
    private Thread worker;
    private volatile boolean stopping;
    private SharedPreferences prefs;
    private volatile Set<String> externalList = Collections.emptySet();
    private final Map<String, Long> lastCountedAt = new HashMap<>();
    private static final long COUNT_COOLDOWN_MS = 60_000L;

    // Redes de anúncios mais comuns em apps Android. Evitamos raízes muito amplas que
    // poderiam quebrar serviços legítimos do mesmo provedor.
    private static final Set<String> BUILTIN_ADS = new HashSet<>(Arrays.asList(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com", "googletagservices.com",
        "adservice.google.com", "admob.com", "adnxs.com", "adsrvr.org", "advertising.com",
        "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "pubmatic.com",
        "rubiconproject.com", "openx.net", "smartadserver.com", "amazon-adsystem.com",
        "moatads.com", "adsafeprotected.com", "casalemedia.com", "lijit.com", "yieldmo.com",
        "media.net", "adform.net", "adform.com", "adcolony.com", "applovin.com", "applvn.com",
        "chartboost.com", "vungle.com", "vungle.io", "vunglecloud.com", "inmobi.com", "inmobi.net",
        "startappservice.com", "start.io", "tapjoy.com", "fyber.com", "smaato.net", "smaato.com",
        "mopub.com", "adzerk.net", "quantserve.com", "exoclick.com", "propellerads.com",
        "supersonicads.com", "ironsrc.com", "unityads.unity3d.com", "inner-active.mobi",
        "pangleglobal.com", "pangle.io", "mintegral.com", "mtgglobals.com", "mbridge.com",
        "pubnative.net", "ogury.com", "ogury.io", "liftoff.io", "moloco.com", "moloco.cloud",
        "yandexadexchange.net", "indexww.com", "indexexchange.com", "triplelift.com", "teads.tv",
        "yieldlab.net", "adition.com", "adscale.de", "adscale.com", "bidswitch.net",
        "contextweb.com", "spotxchange.com", "spotx.tv", "improvedigital.com", "sharethrough.com"
    ));

    private static final Set<String> BUILTIN_TRACKERS = new HashSet<>(Arrays.asList(
        "google-analytics.com", "analytics.google.com", "app-measurement.com", "googletagmanager.com",
        "scorecardresearch.com", "hotjar.com", "fullstory.com", "mouseflow.com", "clarity.ms",
        "api.segment.io", "cdn.segment.com", "mixpanel.com", "amplitude.com", "newrelic.com",
        "nr-data.net", "mathtag.com", "demdex.net", "2o7.net", "omtrdc.net", "appsflyer.com",
        "appsflyersdk.com", "adjust.com", "adjust.io", "kochava.com", "kochava.io"
    ));

    // Quando ativado, dificulta que navegadores/apps ignorem o DNS local usando DoH por hostname.
    // Não cobre clientes que usem IP fixo ou que transportem o anúncio no mesmo domínio do conteúdo.
    private static final Set<String> SECURE_DNS_HOSTS = new HashSet<>(Arrays.asList(
        "dns.google", "cloudflare-dns.com", "dns.quad9.net", "doh.opendns.com",
        "dns.nextdns.io", "dns.adguard-dns.com", "doh.cleanbrowsing.org", "dns0.eu"
    ));

    // Resolvers públicos usados com frequência por apps que tentam ignorar o DNS do Android.
    // Quando a proteção reforçada está ativa, estes IPs entram no túnel. UDP/53 continua
    // sendo filtrado normalmente; DoT (853) e DoH/HTTP3 (443) são descartados, forçando
    // o app a voltar para o resolvedor do sistema quando ele oferece fallback.
    private static final String[] SECURE_DNS_IPV4 = new String[]{
        "1.1.1.1", "1.0.0.1",
        "8.8.8.8", "8.8.4.4",
        "9.9.9.9", "149.112.112.112",
        "208.67.222.222", "208.67.220.220",
        "94.140.14.14", "94.140.15.15"
    };

    private static final String[] SECURE_DNS_IPV6 = new String[]{
        "2606:4700:4700::1111", "2606:4700:4700::1001",
        "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2620:fe::fe", "2620:fe::9"
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createNotificationChannel();
        loadExternalList();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD.equals(action)) {
            loadExternalList();
            return START_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            stopVpn();
            startAsForeground();
            startVpn();
            return START_STICKY;
        }

        startAsForeground();
        if (!running) startVpn();
        return START_STICKY;
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, AdBlockVpnService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long blocked = prefs != null ? prefs.getLong("blocked_today", 0) : 0;
        Notification.Action stopAction = new Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Desativar", stopPi).build();
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle("Bloqueador de anúncios ativo")
            .setContentText(blocked + " consultas DNS bloqueadas hoje")
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(stopAction)
            .build();
    }

    private void startVpn() {
        stopping = false;
        try {
            Builder builder = new Builder()
                .setSession("Bloqueador de Anúncios")
                .setMtu(1500)
                .addAddress("10.10.10.1", 32)
                .addDnsServer("10.10.10.2")
                .addRoute("10.10.10.2", 32);

            if (prefs.getBoolean("block_secure_dns", true)) {
                for (String ip : SECURE_DNS_IPV4) builder.addRoute(ip, 32);
                try {
                    builder.addAddress("fd00:1:fd00:1::1", 128);
                    for (String ip : SECURE_DNS_IPV6) builder.addRoute(ip, 128);
                } catch (Exception ignored) { }
            }

            builder.setBlocking(true);

            Intent configure = new Intent(this, MainActivity.class);
            PendingIntent configPi = PendingIntent.getActivity(this, 10, configure,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setConfigureIntent(configPi);

            vpnInterface = builder.establish();
            if (vpnInterface == null) throw new IllegalStateException("VPN não autorizada");

            running = true;
            prefs.edit().putBoolean("running", true).apply();
            worker = new Thread(this::runLoop, "adblock-vpn-v2");
            worker.start();
        } catch (Exception e) {
            running = false;
            prefs.edit().putBoolean("running", false).putString("last_error", e.getMessage()).apply();
            stopSelf();
        }
    }

    private void runLoop() {
        try (FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor())) {
            byte[] packet = new byte[32767];
            while (!stopping) {
                int length = in.read(packet);
                if (length <= 0) continue;
                byte[] response = handlePacket(packet, length);
                if (response != null) out.write(response);
            }
        } catch (Exception ignored) {
        } finally {
            if (!stopping) {
                running = false;
                prefs.edit().putBoolean("running", false).apply();
                stopSelf();
            }
        }
    }

    private byte[] handlePacket(byte[] packet, int length) {
        try {
            if (length < 28) return null;
            int version = (packet[0] >> 4) & 0xF;
            if (version != 4) return null;
            int ihl = (packet[0] & 0x0F) * 4;
            if (ihl < 20 || length < ihl + 8) return null;
            int protocol = packet[9] & 0xFF;
            if (protocol != 17) return null; // consultas UDP/53 nesta implementação

            int srcPort = u16(packet, ihl);
            int dstPort = u16(packet, ihl + 2);
            if (dstPort != 53) return null;

            int udpLen = u16(packet, ihl + 4);
            int dnsStart = ihl + 8;
            int dnsLen = Math.min(length - dnsStart, Math.max(0, udpLen - 8));
            if (dnsLen < 12) return null;
            byte[] dnsQuery = Arrays.copyOfRange(packet, dnsStart, dnsStart + dnsLen);
            String domain = parseDomain(dnsQuery);
            if (domain == null || domain.isEmpty()) return null;

            byte[] dnsResponse;
            if (shouldBlock(domain)) {
                dnsResponse = nxdomain(dnsQuery);
                incrementStats(domain);
            } else {
                dnsResponse = forwardDns(dnsQuery);
                if (dnsResponse == null) return null;

                // V2: bloqueia também quando um domínio permitido apenas redireciona por CNAME/SVCB
                // para uma rede que consta nos filtros (CNAME cloaking).
                if (responsePointsToBlockedTarget(dnsResponse)) {
                    dnsResponse = nxdomain(dnsQuery);
                    incrementStats(domain);
                }
            }
            return buildIpv4UdpResponse(packet, ihl, srcPort, dnsResponse);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean shouldBlock(String domain) {
        domain = domain.toLowerCase(Locale.ROOT);
        Set<String> allowed = readSetPref("allowed");
        if (matchesAny(domain, allowed)) return false;

        Set<String> custom = readSetPref("custom_blocked");
        if (matchesAny(domain, custom)) return true;
        if (matchesAny(domain, BUILTIN_ADS)) return true;
        if (matchesAny(domain, externalList)) return true;
        if (prefs.getBoolean("trackers", true) && matchesAny(domain, BUILTIN_TRACKERS)) return true;
        return prefs.getBoolean("block_secure_dns", true) && matchesAny(domain, SECURE_DNS_HOSTS);
    }

    private boolean matchesAny(String domain, Set<String> set) {
        if (set.contains(domain)) return true;
        int dot = domain.indexOf('.');
        while (dot >= 0 && dot + 1 < domain.length()) {
            String parent = domain.substring(dot + 1);
            if (set.contains(parent)) return true;
            dot = domain.indexOf('.', dot + 1);
        }
        return false;
    }

    private String parseDomain(byte[] dns) {
        if (dns.length < 13 || u16(dns, 4) < 1) return null;
        NameRead nr = readDnsName(dns, 12);
        return nr == null ? null : nr.name.toLowerCase(Locale.ROOT);
    }

    private byte[] nxdomain(byte[] query) {
        byte[] r = Arrays.copyOf(query, query.length);
        boolean rd = (query[2] & 0x01) != 0;
        r[2] = (byte)(0x80 | (rd ? 0x01 : 0x00));
        r[3] = (byte)0x83; // RA + NXDOMAIN
        r[6] = r[7] = 0; // answers
        r[8] = r[9] = 0; // authority
        r[10] = r[11] = 0; // additional
        return r;
    }

    private byte[] forwardDns(byte[] query) {
        String upstream = prefs.getString("dns", "1.1.1.1");
        try (DatagramSocket socket = new DatagramSocket()) {
            protect(socket);
            socket.setSoTimeout(3500);
            DatagramPacket q = new DatagramPacket(query, query.length, InetAddress.getByName(upstream), 53);
            socket.send(q);
            byte[] buf = new byte[8192];
            DatagramPacket r = new DatagramPacket(buf, buf.length);
            socket.receive(r);
            return Arrays.copyOf(r.getData(), r.getLength());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean responsePointsToBlockedTarget(byte[] dns) {
        try {
            if (dns == null || dns.length < 12) return false;
            int qd = u16(dns, 4);
            int an = u16(dns, 6);
            int p = 12;

            for (int i = 0; i < qd; i++) {
                NameRead qname = readDnsName(dns, p);
                if (qname == null || qname.nextOffset + 4 > dns.length) return false;
                p = qname.nextOffset + 4;
            }

            for (int i = 0; i < an; i++) {
                NameRead owner = readDnsName(dns, p);
                if (owner == null) return false;
                p = owner.nextOffset;
                if (p + 10 > dns.length) return false;

                int type = u16(dns, p);
                int rdlen = u16(dns, p + 8);
                int rdata = p + 10;
                int next = rdata + rdlen;
                if (next > dns.length) return false;

                if (type == 5) { // CNAME
                    NameRead target = readDnsName(dns, rdata);
                    if (target != null && !target.name.isEmpty() && shouldBlock(target.name)) return true;
                } else if ((type == 64 || type == 65) && rdlen > 2) { // SVCB / HTTPS
                    NameRead target = readDnsName(dns, rdata + 2);
                    if (target != null && !target.name.isEmpty() && !".".equals(target.name) && shouldBlock(target.name)) return true;
                }
                p = next;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static class NameRead {
        final String name;
        final int nextOffset;
        NameRead(String name, int nextOffset) {
            this.name = name;
            this.nextOffset = nextOffset;
        }
    }

    private NameRead readDnsName(byte[] dns, int offset) {
        if (dns == null || offset < 0 || offset >= dns.length) return null;
        StringBuilder name = new StringBuilder();
        int p = offset;
        int next = -1;
        int jumps = 0;
        int labels = 0;

        while (p < dns.length && jumps < 32 && labels < 128) {
            int len = dns[p] & 0xFF;
            if (len == 0) {
                if (next < 0) next = p + 1;
                break;
            }

            if ((len & 0xC0) == 0xC0) {
                if (p + 1 >= dns.length) return null;
                int pointer = ((len & 0x3F) << 8) | (dns[p + 1] & 0xFF);
                if (pointer < 0 || pointer >= dns.length) return null;
                if (next < 0) next = p + 2;
                p = pointer;
                jumps++;
                continue;
            }

            if ((len & 0xC0) != 0 || len > 63 || p + 1 + len > dns.length) return null;
            p++;
            if (name.length() > 0) name.append('.');
            for (int i = 0; i < len; i++) {
                int c = dns[p++] & 0xFF;
                if (c >= 'A' && c <= 'Z') c += 32;
                name.append((char)c);
            }
            labels++;
        }

        if (next < 0) return null;
        return new NameRead(name.toString(), next);
    }

    private byte[] buildIpv4UdpResponse(byte[] original, int originalIhl, int originalSrcPort, byte[] dns) {
        int total = 20 + 8 + dns.length;
        byte[] out = new byte[total];
        out[0] = 0x45;
        out[1] = 0;
        put16(out, 2, total);
        out[4] = original[4]; out[5] = original[5];
        out[6] = 0x40; out[7] = 0;
        out[8] = 64;
        out[9] = 17;
        out[10] = out[11] = 0;
        System.arraycopy(original, 16, out, 12, 4); // original dst -> response src
        System.arraycopy(original, 12, out, 16, 4); // original src -> response dst

        put16(out, 20, 53);
        put16(out, 22, originalSrcPort);
        put16(out, 24, 8 + dns.length);
        out[26] = out[27] = 0; // UDP checksum opcional em IPv4
        System.arraycopy(dns, 0, out, 28, dns.length);

        int checksum = ipChecksum(out, 0, 20);
        put16(out, 10, checksum);
        return out;
    }

    private int ipChecksum(byte[] data, int off, int len) {
        long sum = 0;
        for (int i = off; i < off + len; i += 2) {
            int word = (data[i] & 0xFF) << 8;
            if (i + 1 < off + len) word |= (data[i + 1] & 0xFF);
            sum += word;
            while ((sum & 0xFFFF0000L) != 0) sum = (sum & 0xFFFFL) + (sum >>> 16);
        }
        return (int)(~sum) & 0xFFFF;
    }

    private int u16(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private void put16(byte[] b, int p, int v) {
        b[p] = (byte)((v >>> 8) & 0xFF);
        b[p + 1] = (byte)(v & 0xFF);
    }

    private void incrementStats(String domain) {
        long now = System.currentTimeMillis();
        Long previous = lastCountedAt.get(domain);
        if (previous != null && now - previous < COUNT_COOLDOWN_MS) return;
        lastCountedAt.put(domain, now);
        if (lastCountedAt.size() > 512) {
            long cutoff = now - (COUNT_COOLDOWN_MS * 5);
            lastCountedAt.entrySet().removeIf(e -> e.getValue() < cutoff);
        }

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String stored = prefs.getString("stats_day", today);
        long todayCount = stored.equals(today) ? prefs.getLong("blocked_today", 0) : 0;
        long total = prefs.getLong("blocked_total", 0);

        StringBuilder recent = new StringBuilder(domain).append('\n');
        String old = prefs.getString("recent_domains", "");
        int kept = 0;
        for (String line : old.split("\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.equals(domain)) continue;
            recent.append(line).append('\n');
            if (++kept >= 11) break;
        }

        prefs.edit()
            .putString("stats_day", today)
            .putLong("blocked_today", todayCount + 1)
            .putLong("blocked_total", total + 1)
            .putString("recent_domains", recent.toString())
            .apply();

        if (((todayCount + 1) % 20) == 0) {
            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Set<String> readSetPref(String key) {
        Set<String> s = new LinkedHashSet<>();
        String raw = prefs.getString(key, "");
        for (String x : raw.split("\\n")) {
            x = x.trim().toLowerCase(Locale.ROOT);
            if (!x.isEmpty()) s.add(x);
        }
        return s;
    }

    private void loadExternalList() {
        File file = new File(getFilesDir(), "blocklist.txt");
        if (!file.exists()) {
            externalList = Collections.emptySet();
            return;
        }
        HashSet<String> set = new HashSet<>(120000);
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim().toLowerCase(Locale.ROOT);
                if (!line.isEmpty()) set.add(line);
            }
            externalList = set;
            prefs.edit().putInt("external_count", set.size()).apply();
        } catch (Exception e) {
            externalList = Collections.emptySet();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Proteção contra anúncios", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Mantém o bloqueio DNS ativo");
            c.setShowBadge(false);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private void stopVpn() {
        stopping = true;
        running = false;
        prefs.edit().putBoolean("running", false).apply();
        try { if (vpnInterface != null) vpnInterface.close(); } catch (Exception ignored) {}
        vpnInterface = null;
        if (worker != null) worker.interrupt();
        worker = null;
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
