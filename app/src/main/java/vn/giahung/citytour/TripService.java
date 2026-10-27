
package vn.giahung.citytour;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import java.util.List;

public class TripService extends Service {
    public static final String ACTION_START =
        "vn.giahung.citytour.START_GPS";
    public static final String ACTION_STOP =
        "vn.giahung.citytour.STOP_GPS";
    public static final String ACTION_LOCATION =
        "vn.giahung.citytour.GPS_UPDATE";

    private static final String CHANNEL_ID = "trip_gps";
    private LocationManager manager;
    private LocationListener listener;
    private Location lastLocation;
    private double distanceMeters = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationChannel channel =
            new NotificationChannel(
                CHANNEL_ID,
                "GPS Gia Hung City Tour",
                NotificationManager.IMPORTANCE_LOW
            );

        NotificationManager nm =
            getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);

        manager = (LocationManager)
            getSystemService(LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopTracking();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification =
            new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Gia Hung City Tour")
                .setContentText("Dang theo doi GPS chuyen xe")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();

        startForeground(101, notification);

        if (listener == null) {
            startTracking();
        }

        return START_NOT_STICKY;
    }

    private void startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }

        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location == null) return;

                if (location.hasAccuracy()
                        && location.getAccuracy() > 35f) return;

                if (lastLocation != null) {
                    double delta = lastLocation.distanceTo(location);
                    long elapsed = location.getElapsedRealtimeNanos()
                        - lastLocation.getElapsedRealtimeNanos();

                    if (elapsed > 0 && delta >= 3) {
                        double speed = delta /
                            (elapsed / 1000000000.0);

                        if (speed <= 45) {
                            distanceMeters += delta;
                        }
                    }
                }

                lastLocation = location;

                Intent update = new Intent(ACTION_LOCATION);
                update.setPackage(getPackageName());
                update.putExtra("latitude", location.getLatitude());
                update.putExtra("longitude", location.getLongitude());
                update.putExtra("distanceMeters", distanceMeters);
                update.putExtra("accuracy", location.getAccuracy());
                sendBroadcast(update);
            }
        };

        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                2f,
                listener,
                Looper.getMainLooper()
            );
        } catch (SecurityException e) {
            stopSelf();
        }
    }

    private void stopTracking() {
        if (manager != null && listener != null) {
            manager.removeUpdates(listener);
            listener = null;
        }
    }

    @Override
    public void onDestroy() {
        stopTracking();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
