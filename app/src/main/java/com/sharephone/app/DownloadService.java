package com.sharephone.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.Os;

import androidx.core.app.NotificationCompat;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads files itself (not the browser), so the notification comes from this app.
 * Uses several parallel connections (HTTP Range) like a download manager for more speed.
 */
public class DownloadService extends Service {

    static final String CHANNEL = "sharephone_downloads";
    static final String ACTION_CANCEL = "com.sharephone.app.CANCEL";
    private static final int FG_ID = 1;
    private static final int MAX_CONN = 6;

    private static volatile boolean cancelRequested = false;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger(0);
    private int doneId = 100;
    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Download progress from your PC");
        nm.createNotificationChannel(ch);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelRequested = true;
            return START_NOT_STICKY;
        }
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String url = intent.getStringExtra("url");
        final String name = intent.getStringExtra("name");
        final String mime = intent.getStringExtra("mime");
        final String cookie = intent.getStringExtra("cookie");
        final String ua = intent.getStringExtra("ua");

        pending.incrementAndGet();
        startForeground(FG_ID, progressNotification(name, 0, "Starting...", true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        pool.execute(() -> {
            try {
                download(url, name, mime, cookie, ua);
            } finally {
                if (pending.decrementAndGet() == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }
        });
        return START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- notifications
    private Notification progressNotification(String name, int pct, String text, boolean indeterminate) {
        Intent cancel = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 0, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        int queued = pending.get() - 1;
        String title = queued > 0 ? name + "  (+" + queued + " queued)" : name;

        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, indeterminate)
                .setContentIntent(openPi)
                .addAction(0, "Cancel", cancelPi)
                .build();
    }

    private void notifyDone(String name, Uri uri, String mime, boolean ok, String error) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle(name)
                .setAutoCancel(true);
        if (ok) {
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentText("Download complete - tap to open");
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, (mime == null || mime.length() == 0) ? "*/*" : mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            b.setContentIntent(PendingIntent.getActivity(this, doneId, view,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            b.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentText("Download failed" + (error != null ? ": " + error : ""));
        }
        nm.notify(doneId++, b.build());
    }

    // ---------------------------------------------------------------- text helpers
    private static String size(double n) {
        if (n < 1024) return (long) n + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do {
            n /= 1024;
            i++;
        } while (n >= 1024 && i < u.length - 1);
        return String.format(java.util.Locale.US, n >= 100 ? "%.0f %s" : "%.1f %s", n, u[i]);
    }

    private static String eta(double sec) {
        if (sec < 60) return Math.max(1, Math.round(sec)) + "s left";
        if (sec < 3600) return (long) (sec / 60) + "m " + Math.round(sec % 60) + "s left";
        return (long) (sec / 3600) + "h " + Math.round((sec % 3600) / 60) + "m left";
    }

    // ---------------------------------------------------------------- network helpers
    private HttpURLConnection open(String url, String cookie, String ua) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        if (ua != null) c.setRequestProperty("User-Agent", ua);
        return c;
    }

    private static void writeFully(FileChannel ch, byte[] buf, int n, long pos) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
        long p = pos;
        while (bb.hasRemaining()) {
            p += ch.write(bb, p);
        }
    }

    /** Downloads bytes start..end into the file; resumes from where it stopped if the connection drops. */
    private void fetchChunk(String url, String cookie, String ua, FileChannel ch,
                            long start, long end, AtomicLong done, AtomicBoolean abort) throws IOException {
        long pos = start;
        int attempts = 0;
        byte[] buf = new byte[128 * 1024];
        while (pos <= end) {
            if (abort.get() || cancelRequested) throw new IOException("cancelled");
            long before = pos;
            HttpURLConnection c = null;
            try {
                c = open(url, cookie, ua);
                c.setRequestProperty("Range", "bytes=" + pos + "-" + end);
                int code = c.getResponseCode();
                if (code != 206) throw new IOException("server said " + code);
                InputStream in = c.getInputStream();
                int n;
                while (pos <= end && (n = in.read(buf)) != -1) {
                    if (abort.get() || cancelRequested) throw new IOException("cancelled");
                    writeFully(ch, buf, n, pos);
                    pos += n;
                    done.addAndGet(n);
                }
            } catch (IOException e) {
                if (abort.get() || cancelRequested || ++attempts > 4) throw e;
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ie) {
                    throw new IOException("interrupted");
                }
                continue;
            } finally {
                if (c != null) c.disconnect();
            }
            if (pos == before) {
                if (++attempts > 4) throw new IOException("no data received");
            } else {
                attempts = 0;
            }
        }
    }

    /** Fallback when the server cannot do ranges: one plain connection. */
    private void fetchSingle(String url, String cookie, String ua, FileChannel ch,
                             AtomicLong done, AtomicBoolean abort) throws IOException {
        HttpURLConnection c = open(url, cookie, ua);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("server said " + code);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[256 * 1024];
            long pos = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (abort.get() || cancelRequested) throw new IOException("cancelled");
                writeFully(ch, buf, n, pos);
                pos += n;
                done.addAndGet(n);
            }
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- the download itself
    private void download(final String url, final String name, String mime, final String cookie, final String ua) {
        cancelRequested = false;
        ContentResolver cr = getContentResolver();
        Uri uri = null;
        ParcelFileDescriptor pfd = null;
        FileOutputStream fos = null;
        ExecutorService workers = null;
        try {
            // 1) ask the server for the size and whether it supports ranges
            long total = -1;
            boolean ranges = false;
            HttpURLConnection probe = open(url, cookie, ua);
            try {
                probe.setRequestProperty("Range", "bytes=0-0");
                int pc = probe.getResponseCode();
                if (pc == 206) {
                    String crange = probe.getHeaderField("Content-Range");
                    if (crange != null && crange.indexOf('/') >= 0) {
                        try {
                            total = Long.parseLong(crange.substring(crange.lastIndexOf('/') + 1).trim());
                            ranges = total > 0;
                        } catch (NumberFormatException nfe) {
                            total = -1;
                        }
                    }
                } else if (pc == 200) {
                    total = probe.getContentLengthLong();
                } else if (pc == 416) {
                    total = 0;
                } else {
                    throw new IOException("server said " + pc);
                }
            } finally {
                probe.disconnect();
            }

            // 2) create the file in Downloads/Share to Phone
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            if (mime != null && mime.length() > 0) v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Share to Phone");
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("cannot create file");
            pfd = cr.openFileDescriptor(uri, "rw");
            if (pfd == null) throw new IOException("cannot write file");
            fos = new FileOutputStream(pfd.getFileDescriptor());
            final FileChannel ch = fos.getChannel();
            if (total > 0) {
                try {
                    Os.ftruncate(pfd.getFileDescriptor(), total);
                } catch (Exception ignored) {
                }
            }

            // 3) start the workers
            final long totalF = total;
            final AtomicLong done = new AtomicLong(0);
            final AtomicBoolean abort = new AtomicBoolean(false);
            List<Future<?>> futures = new ArrayList<>();
            int connections = 1;

            if (total == 0) {
                // empty file, nothing to fetch
            } else if (ranges && total >= 2L * 1024 * 1024) {
                final long chunk = Math.max(1024L * 1024, Math.min(8L * 1024 * 1024, total / (MAX_CONN * 3)));
                final int chunks = (int) ((total + chunk - 1) / chunk);
                connections = Math.min(MAX_CONN, chunks);
                final AtomicInteger next = new AtomicInteger(0);
                workers = Executors.newFixedThreadPool(connections);
                for (int i = 0; i < connections; i++) {
                    futures.add(workers.submit(() -> {
                        while (!abort.get()) {
                            int idx = next.getAndIncrement();
                            if (idx >= chunks) break;
                            long s = idx * chunk;
                            long e = Math.min(totalF - 1, s + chunk - 1);
                            fetchChunk(url, cookie, ua, ch, s, e, done, abort);
                        }
                        return null;
                    }));
                }
            } else {
                workers = Executors.newFixedThreadPool(1);
                futures.add(workers.submit(() -> {
                    fetchSingle(url, cookie, ua, ch, done, abort);
                    return null;
                }));
            }

            // 4) watch progress, update the notification
            long lastBytes = 0;
            long lastT = System.currentTimeMillis();
            double speed = 0;
            while (true) {
                boolean allDone = true;
                for (Future<?> f : futures) {
                    if (f.isDone()) {
                        try {
                            f.get();
                        } catch (ExecutionException ee) {
                            abort.set(true);
                            Throwable cause = ee.getCause();
                            throw new IOException(cause != null && cause.getMessage() != null ? cause.getMessage() : "failed");
                        }
                    } else {
                        allDone = false;
                    }
                }
                if (cancelRequested) {
                    abort.set(true);
                    throw new IOException("cancelled");
                }

                long now = System.currentTimeMillis();
                if (now - lastT >= 600) {
                    long d = done.get();
                    double inst = (d - lastBytes) * 1000.0 / (now - lastT);
                    speed = speed == 0 ? inst : speed * 0.6 + inst * 0.4;
                    lastT = now;
                    lastBytes = d;
                    int pct = totalF > 0 ? (int) (d * 100 / totalF) : 0;
                    String text;
                    if (totalF > 0) {
                        text = pct + "%  -  " + size(speed) + "/s"
                                + (speed > 1024 ? "  -  " + eta((totalF - d) / speed) : "")
                                + (connections > 1 ? "  -  x" + connections : "");
                    } else {
                        text = size(d) + "  -  " + size(speed) + "/s";
                    }
                    nm.notify(FG_ID, progressNotification(name, pct, text, totalF <= 0));
                }
                if (allDone) break;
                Thread.sleep(250);
            }

            if (total > 0 && done.get() < total) throw new IOException("incomplete download");

            // 5) finish
            fos.close();
            fos = null;
            pfd.close();
            pfd = null;
            ContentValues fin = new ContentValues();
            fin.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, fin, null, null);
            notifyDone(name, uri, mime, true, null);
        } catch (Exception e) {
            try {
                if (fos != null) fos.close();
            } catch (Exception ignored) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
            fos = null;
            pfd = null;
            if (uri != null) {
                try {
                    cr.delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            notifyDone(name, null, mime, false, e.getMessage());
        } finally {
            if (workers != null) workers.shutdownNow();
            try {
                if (fos != null) fos.close();
            } catch (Exception ignored) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
        }
    }
}
