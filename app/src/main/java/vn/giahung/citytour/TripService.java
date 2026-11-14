package vn.giahung.citytour;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class TripService extends Service {
    public static final String ACTION_START = "vn.giahung.citytour.START_GPS";
    public static final String ACTION_STOP = "vn.giahung.citytour.STOP_GPS";
    public static final String ACTION_PAUSE = "vn.giahung.citytour.PAUSE_GPS";
    public static final String ACTION_RESUME = "vn.giahung.citytour.RESUME_GPS";
    public static final String ACTION_LOCATION = "vn.giahung.citytour.GPS_UPDATE";
    private static final String TAG = "GiaHungTripService";
    private static final String CHANNEL_ID = "trip_gps";
    private static final int NOTIFICATION_ID = 101;
    private LocationManager locationManager;
    private LocationListener listener;
    private Location lastAccepted;
    private double distanceMeters = 0;
    private boolean active = false;
    private boolean paused = false;
    private SharedPreferences preferences;

    @Override public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences("trip_gps_state", Context.MODE_PRIVATE);
        distanceMeters = Double.longBitsToDouble(preferences.getLong("distance", Double.doubleToLongBits(0)));
        active = preferences.getBoolean("active", false);
        paused = preferences.getBoolean("paused", false);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
            "GPS Gia Hưng City Tour", NotificationManager.IMPORTANCE_LOW));
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    private Notification notification(String message) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Gia Hưng City Tour · GPS")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopUpdates(); active = false; paused = false;
            persist();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            stopUpdates();
            active = true; paused = false;
            distanceMeters = 0; lastAccepted = null;
            persist();
        } else if (ACTION_RESUME.equals(action)) {
            active = true; paused = false;
            lastAccepted = null; // Do not count the distance travelled while paused.
            persist();
        } else if (ACTION_PAUSE.equals(action)) {
            paused = true;
            stopUpdates();
            persist();
        } else if (!active) {
            // Unexpected restart without an active trip: do not invent one.
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID,
                notification(paused ? "Đã tạm dừng tính quãng đường" : "Đang theo dõi hành trình"));
            if (active && !paused) startUpdates();
        } catch (Exception e) {
            Log.e(TAG, "Cannot start foreground location", e);
            stopUpdates(); active = false; paused = false; persist(); stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startUpdates() {
        if (listener != null) return;
        if (locationManager == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No precise location permission or manager");
            return;
        }
        lastAccepted = null;
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location loc) {
                if (loc == null || !active || paused || !loc.hasAccuracy()) return;
                if (loc.getAccuracy() <= 0 || loc.getAccuracy() > 35f) return;
                if (loc.getElapsedRealtimeNanos() <= 0) return;
                long ageMs = (SystemClock.elapsedRealtimeNanos() - loc.getElapsedRealtimeNanos()) / 1_000_000;
                if (ageMs < 0 || ageMs > 15000) return;
                if (lastAccepted != null) {
                    long dtNanos = loc.getElapsedRealtimeNanos() - lastAccepted.getElapsedRealtimeNanos();
                    if (dtNanos <= 0) return;
                    if (dtNanos > 30_000_000_000L) { lastAccepted = loc; return; }
                    double delta = lastAccepted.distanceTo(loc);
                    double speed = delta / (dtNanos / 1e9);
                    if (speed > 40.0) return; // >144 km/h, likely a GPS jump.
                    double noise = Math.max(4d, Math.min(15d, (loc.getAccuracy() + lastAccepted.getAccuracy()) * 0.25d));
                    if (delta > noise) { distanceMeters += delta; lastAccepted = loc; }
                } else {
                    lastAccepted = loc;
                }
                persist();
            }
        };
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f,
                listener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "Location updates unavailable", e);
            stopUpdates();
        }
    }

    private void stopUpdates() {
        if (listener != null && locationManager != null) {
            try { locationManager.removeUpdates(listener); } catch (Exception ignored) {}
        }
        listener = null;
    }

    private void persist() {
        if (preferences == null) return;
        preferences.edit()
            .putLong("distance", Double.doubleToLongBits(distanceMeters))
            .putBoolean("active", active)
            .putBoolean("paused", paused)
            .putLong("updated", System.currentTimeMillis())
            .apply();
        Intent update = new Intent(ACTION_LOCATION);
        update.setPackage(getPackageName());
        sendBroadcast(update);
    }
    @Override public void onDestroy() { stopUpdates(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
