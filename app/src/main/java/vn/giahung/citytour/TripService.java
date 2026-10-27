package vn.giahung.citytour;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import androidx.core.app.NotificationCompat;

public class TripService extends Service {
    public static final String ACTION_START = "vn.giahung.citytour.START_GPS";
    public static final String ACTION_PAUSE = "vn.giahung.citytour.PAUSE_GPS";
    public static final String ACTION_RESUME = "vn.giahung.citytour.RESUME_GPS";
    public static final String ACTION_STOP = "vn.giahung.citytour.STOP_GPS";
    public static final String ACTION_LOCATION = "vn.giahung.citytour.GPS_UPDATE";
    public static final String ACTION_GET_STATE = "vn.giahung.citytour.GET_GPS_STATE";

    private static final String CHANNEL_ID = "trip_gps";
    private static final String PREFS = "trip_gps_state";
    private static final int NOTIFICATION_ID = 101;

    private LocationManager manager;
    private LocationListener listener;
    private Location lastLocation;
    private double distanceMeters;
    private boolean active;
    private boolean paused;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
            CHANNEL_ID, "GPS Gia Hưng City Tour", NotificationManager.IMPORTANCE_LOW));
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        distanceMeters = Double.longBitsToDouble(p.getLong("distance", Double.doubleToLongBits(0)));
        active = p.getBoolean("active", false);
        paused = p.getBoolean("paused", false);
    }

    private void saveState() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong("distance", Double.doubleToLongBits(distanceMeters))
            .putBoolean("active", active)
            .putBoolean("paused", paused)
            .apply();
    }

    private Notification notification() {
        String status = paused ? "Chuyến xe đang tạm dừng" :
            "GPS đang theo dõi chuyến xe";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gia Hưng City Tour")
            .setContentText(status + " - " +
                String.format(java.util.Locale.US, "%.2f km", distanceMeters / 1000.0))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            active = false;
            paused = false;
            saveState();
            stopTracking();
            broadcastState();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_GET_STATE.equals(action)) {
            broadcastState();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            distanceMeters = 0;
            lastLocation = null;
            active = true;
            paused = false;
            saveState();
        } else if (ACTION_PAUSE.equals(action)) {
            paused = true;
            lastLocation = null;
            saveState();
        } else if (ACTION_RESUME.equals(action)) {
            paused = false;
            lastLocation = null;
            saveState();
        }

        if (!active) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startForeground(NOTIFICATION_ID, notification());
        } catch (RuntimeException e) {
            android.util.Log.e("GiaHungGPS", "Foreground service error", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (paused) {
            stopTracking();
        } else {
            startTracking();
        }
        broadcastState();
        return START_STICKY;
    }

    private void startTracking() {
        if (listener != null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (!active || paused || location == null) return;
                if (!location.hasAccuracy() || location.getAccuracy() > 35f) return;

                if (lastLocation != null) {
                    long elapsed = location.getElapsedRealtimeNanos()
                        - lastLocation.getElapsedRealtimeNanos();
                    double delta = lastLocation.distanceTo(location);
                    if (elapsed > 0) {
                        double seconds = elapsed / 1000000000.0;
                        double speed = delta / seconds;
                        if (delta >= 3 && speed <= 45 && seconds <= 30) {
                            distanceMeters += delta;
                            saveState();
                        }
                    }
                }
                lastLocation = location;
                broadcastLocation(location);
            }
        };
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                1000L, 2f, listener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            listener = null;
            android.util.Log.e("GiaHungGPS", "GPS unavailable", e);
            stopSelf();
        }
    }

    private void broadcastLocation(Location location) {
        Intent update = new Intent(ACTION_LOCATION);
        update.setPackage(getPackageName());
        update.putExtra("latitude", location.getLatitude());
        update.putExtra("longitude", location.getLongitude());
        update.putExtra("accuracy", location.getAccuracy());
        update.putExtra("distanceMeters", distanceMeters);
        update.putExtra("active", active);
        update.putExtra("paused", paused);
        sendBroadcast(update);
    }

    private void broadcastState() {
        Intent update = new Intent(ACTION_LOCATION);
        update.setPackage(getPackageName());
        update.putExtra("distanceMeters", distanceMeters);
        update.putExtra("active", active);
        update.putExtra("paused", paused);
        sendBroadcast(update);
    }

    private void stopTracking() {
        if (manager != null && listener != null) {
            manager.removeUpdates(listener);
            listener = null;
        }
        lastLocation = null;
    }

    @Override public void onDestroy() {
        stopTracking();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
                    }
