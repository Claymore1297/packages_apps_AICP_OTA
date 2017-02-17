package co.copperhead.updater;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemProperties;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import android.os.UpdateEngine;
import android.os.UpdateEngine.ErrorCodeConstants;
import android.os.UpdateEngineCallback;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import co.copperhead.updater.PeriodicJob;
import co.copperhead.updater.TriggerUpdateReceiver;

public class Service extends IntentService {
    private static final String TAG = "Service";
    private static final int NOTIFICATION_ID = 1;
    private static final int PENDING_REBOOT_ID = 1;
    private static final int CONNECT_TIMEOUT = 60000;
    private static final int READ_TIMEOUT = 60000;
    private static final File UPDATE_PATH = new File("/data/ota_package/update.zip");
    private static final String REBOOT = "co.copperhead.intent.action.REBOOT";

    private boolean running = false;

    public Service() {
        super(TAG);
    }

    private InputStream fetchData(String path) throws IOException {
        final URL url = new URL(getString(R.string.url) + path);
        final URLConnection urlConnection = url.openConnection();
        urlConnection.setConnectTimeout(CONNECT_TIMEOUT);
        urlConnection.setReadTimeout(READ_TIMEOUT);
        return urlConnection.getInputStream();
    }

    private void onDownloadFinished(long buildDate) throws IOException {
        try {
            android.os.RecoverySystem.verifyPackage(UPDATE_PATH, null, null);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }

        final ZipFile zipFile = new ZipFile(UPDATE_PATH);

        ZipEntry entry = zipFile.getEntry("META-INF/com/android/metadata");
        if (entry == null) {
            throw new RuntimeException("missing file (!!!)");
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry)));
        String line;
        long timestamp = 0;
        while ((line = reader.readLine()) != null) {
            String[] pair = line.split("=");
            if ("post-timestamp".equals(pair[0])) {
                timestamp = Long.parseLong(pair[1]);
                break;
            }
        }
        if (timestamp != buildDate) {
            throw new RuntimeException("update older than the server claimed (!!!)");
        }

        final List<String> lines = new ArrayList<String>();
        long payloadOffset = 0;

        final Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        long offset = 0;
        while (zipEntries.hasMoreElements()) {
            entry = (ZipEntry) zipEntries.nextElement();
            long fileSize = 0;
            long extra = entry.getExtra() == null ? 0 : entry.getExtra().length;
            final long zipHeaderLength = 30;
            offset += zipHeaderLength + entry.getName().length() + extra;
            if (!entry.isDirectory()) {
                fileSize = entry.getCompressedSize();
                if ("payload.bin".equals(entry.getName())) {
                    payloadOffset = offset;
                } else if ("payload_properties.txt".equals(entry.getName())) {
                    reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry)));
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                }
            }
            offset += fileSize;
        }

        final UpdateEngine engine = new UpdateEngine();
        engine.bind(new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int status, float percent) {
                Log.v(TAG, "onStatusUpdate: " + status + ", " + percent);
            }

            @Override
            public void onPayloadApplicationComplete(int errorCode) {
                if (errorCode == ErrorCodeConstants.SUCCESS) {
                    Log.v(TAG, "onPayloadApplicationComplete success");
                    PeriodicJob.cancel(Service.this);
                    annoyUser();
                } else {
                    Log.v(TAG, "onPayloadApplicationComplete: " + errorCode);
                    running = false;
                }
                UPDATE_PATH.delete();
            }
        });

        engine.applyPayload("file://" + UPDATE_PATH, payloadOffset, 0, lines.toArray(new String[lines.size()]));
    }

    private void annoyUser() {
        final PendingIntent reboot = PendingIntent.getBroadcast(this, PENDING_REBOOT_ID, new Intent(REBOOT), 0);
        final NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, new Notification.Builder(this)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_update_white_24dp)
            .setOngoing(true)
            .addAction(R.drawable.ic_restart, "Reboot", reboot)
            .build());
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(TAG, "onHandleIntent");

        final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        final WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        try {
            wakeLock.acquire();

            if (running) {
                Log.d(TAG, "updating already, returning early");
                return;
            }
            running = true;

            final String device = SystemProperties.get("ro.product.device");

            InputStream input = fetchData(device);
            final BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            final String[] metadata = reader.readLine().split(" ");
            reader.close();

            final String targetIncremental = metadata[0];
            final long buildDate = Long.parseLong(metadata[1]);
            final long installedBuildDate = SystemProperties.getLong("ro.build.date.utc", 0);
            if (buildDate <= installedBuildDate) {
                Log.v(TAG, "buildDate: " + buildDate + " not higher than installedBuildDate: " + installedBuildDate);
                return;
            }

            if (UPDATE_PATH.exists()) {
                UPDATE_PATH.delete();
            }
            final OutputStream output = new FileOutputStream(UPDATE_PATH);

            try {
                Log.d(TAG, "fetch incremental");
                final String sourceIncremental = SystemProperties.get("ro.build.version.incremental");
                input = fetchData(device + "-incremental-" + sourceIncremental + "-" + targetIncremental + ".zip");
            } catch (IOException e) {
                Log.d(TAG, "incremental not found, fetch full update");
                input = fetchData(device + "-ota_update-" + targetIncremental + ".zip");
            }

            int n;
            byte[] buffer = new byte[8192];
            while ((n = input.read(buffer)) != -1) {
                output.write(buffer, 0, n);
            }
            output.close();
            input.close();

            UPDATE_PATH.setReadable(true, false);

            onDownloadFinished(buildDate);
        } catch (IOException e) {
            running = false;
            Log.e(TAG, "IOException", e);
        } finally {
            wakeLock.release();
            TriggerUpdateReceiver.completeWakefulIntent(intent);
        }
    }
}