/*
According to apache license

This is fork of christocracy cordova-plugin-background-geolocation plugin
https://github.com/christocracy/cordova-plugin-background-geolocation

This is a new class
*/

package com.marianhello.bgloc;

import android.Manifest;
import android.accounts.Account;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.SQLException;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.marianhello.bgloc.data.BackgroundActivity;
import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.LocationDAO;
import com.marianhello.bgloc.sync.AccountHelper;
import com.marianhello.bgloc.sync.AuthenticatorService;
import com.marianhello.bgloc.sync.SyncService;
import com.marianhello.logging.LoggerManager;

import org.json.JSONArray;
import org.json.JSONException;

import java.net.HttpURLConnection;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;

public class LocationService extends Service {

    /** Keeps track of all current registered clients. */
    HashMap<Integer, Messenger> mClients = new HashMap();

    /**
     * Command sent by the service to
     * any registered clients with error.
     */
    public static final int MSG_ERROR = 1;

    /**
     * Command to the service to register a client, receiving callbacks
     * from the service.  The Message's replyTo field must be a Messenger of
     * the client where callbacks should be sent.
     */
    public static final int MSG_REGISTER_CLIENT = 2;

    /**
     * Command to the service to unregister a client, to stop receiving callbacks
     * from the service.  The Message's replyTo field must be a Messenger of
     * the client as previously given with MSG_REGISTER_CLIENT.
     */
    public static final int MSG_UNREGISTER_CLIENT = 3;

    /**
     * Command sent by the service to
     * any registered clients with the new position.
     */
    public static final int MSG_LOCATION_UPDATE = 4;

    /**
     * Command sent by the service to
     * any registered clients whenever the devices enters "stationary-mode"
     */
    public static final int MSG_ON_STATIONARY = 5;

    /**
     * Command to the service to indicate operation mode has been changed
     */
    public static final int MSG_SWITCH_MODE = 6;

    /**
     * Command to the service to that configuration has been changed
     */
    public static final int MSG_CONFIGURE = 7;

    /**
     * Command sent by the service to
     * any registered clients with new detected activity.
     */
    public static final int MSG_ON_ACTIVITY = 8;

    /** indicate if service is running */
    private static Boolean isRunning = false;
    /** notification id */
    private static int NOTIF_ID = 1;

    private static final int ONE_MINUTE_IN_MILLIS = 1000 * 60;

    private LocationDAO dao;
    private Config mConfig;
    private LocationProvider mProvider;
    private Account mSyncAccount;
    private Boolean hasConnectivity = true;

    private org.slf4j.Logger logger;

    private volatile HandlerThread handlerThread;
    private ServiceHandler serviceHandler;

    private LocationManager locationManager;
    private LocationListener locationListener;
    TelephonyManager telephonyManager;

    private class ServiceHandler extends Handler {

        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {

            String TAG = "handleMessage";
            Log.i(TAG, "handleMessage: ");

            if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.e("permission", "without permissions");
                return;
            }
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 120*1000, 0, locationListener);
        }
    }

    /**
     * Handler of incoming messages from clients.
     */
    private class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER_CLIENT:
                    mClients.put(msg.arg1, msg.replyTo);
                    break;
                case MSG_UNREGISTER_CLIENT:
                    mClients.remove(msg.arg1);
                    break;
                case MSG_SWITCH_MODE:
                    switchMode(msg.arg1);
                    break;
                case MSG_CONFIGURE:
                    configure(msg.getData());
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    /**
     * Target we publish for clients to send messages to IncomingHandler.
     */
    final Messenger messenger = new Messenger(new IncomingHandler());

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        logger = LoggerManager.getLogger(LocationService.class);
        logger.info("Creating LocationService");

        // An Android handler thread internally operates on a looper.
        handlerThread = new HandlerThread("LocationService.HandlerThread");
        handlerThread.start();
        // An Android service handler is a handler running on a specific background thread.
        serviceHandler = new ServiceHandler(handlerThread.getLooper());

        dao = (DAOFactory.createLocationDAO(this));
        mSyncAccount = AccountHelper.CreateSyncAccount(this,
                AuthenticatorService.getAccount(getStringResource(Config.ACCOUNT_TYPE_RESOURCE)));

        locationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        telephonyManager = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);

        registerReceiver(connectivityChangeReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        final String TAG = "locListener";

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                Log.i(TAG, "onLocationChanged: " + location.getAccuracy()+ ", "
                        + location.getLatitude() + "," + location.getLongitude());
                if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "without permissions");
                    return;
                }

                Config config = getConfig();
                String userID = config.getBmpUserID();
                Calendar calendar = new GregorianCalendar();
                Date time = new Date(location.getTime());

                calendar.setTime(time);

                FirebaseAuth firebaseAuth = FirebaseAuth.getInstance();

                DatabaseReference databaseReference = FirebaseDatabase.getInstance().getReference()
                        .child("DOMAINS")
                        .child(firebaseAuth.getUid())
                        .child("USERS")
                        .child(userID)
                        .child(String.valueOf(calendar.get(Calendar.YEAR)))
                        .child(String.valueOf(calendar.get(Calendar.MONTH) + 1))
                        .child(String.valueOf(calendar.get(Calendar.DAY_OF_MONTH)))
                        .child("POSITIONS")
                        .child(String.valueOf(time.getTime()));
                databaseReference.child("accuracy").setValue(location.getAccuracy());
                databaseReference.child("altitude").setValue(location.getAltitude());
                databaseReference.child("dateReceiveFB").setValue(ServerValue.TIMESTAMP);
                databaseReference.child("device").setValue(Build.MODEL);
                databaseReference.child("imei").setValue("" + telephonyManager.getDeviceId());
                databaseReference.child("isMainAppVisible").setValue(false);
                databaseReference.child("position").setValue(location.getLatitude()
                        + "," + location.getLongitude());
                databaseReference.child("provider").setValue(LocationManager.NETWORK_PROVIDER);
                String hours = (time.getHours()<10 || time.getHours() == 0)?"0"+time.getHours():String.valueOf(time.getHours());
                String minutes = (time.getMinutes()<10 || time.getMinutes() == 0)?"0"+time.getMinutes():String.valueOf(time.getMinutes());
                String seconds = (time.getSeconds()<10 || time.getSeconds() == 0)?"0"+time.getSeconds():String.valueOf(time.getSeconds());
                databaseReference.child("time").setValue(hours + ":" + minutes + ":" + seconds);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
                Log.i(TAG, "onStatusChanged");
            }

            @Override
            public void onProviderEnabled(String provider) {
                Log.i(TAG, "onProviderEnabled");
            }

            @Override
            public void onProviderDisabled(String provider) {
                Log.i(TAG, "onProviderDisabled");
            }
        };
    }

    @Override
    public void onDestroy() {
        logger.info("Destroying LocationService");
        mProvider.onDestroy();
        mProvider = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            handlerThread.quitSafely();
        } else {
            handlerThread.quit(); //sorry
        }
        unregisterReceiver(connectivityChangeReceiver);

        isRunning = false;

        locationManager.removeUpdates(locationListener);

        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        logger.debug("Task has been removed");
        if (mConfig.getStopOnTerminate()) {
            logger.info("Stopping self");
            stopSelf();
        } else {
            logger.info("Continue running in background");
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logger.info("Received start command startId: {} intent: {}", startId, intent);

        if (mProvider != null) {
            mProvider.onDestroy();
        }

        if (intent == null) {
            //service has been probably restarted so we need to load config from db
            ConfigurationDAO dao = DAOFactory.createConfigurationDAO(this);
            try {
                mConfig = dao.retrieveConfiguration();
            } catch (JSONException e) {
                logger.error("Config exception: {}", e.getMessage());
                mConfig = Config.getDefault(); //using default config
            }
        } else {
            if (intent.hasExtra("config")) {
                mConfig = intent.getParcelableExtra("config");
            } else {
                mConfig = Config.getDefault(); //using default config
            }
        }

        logger.debug("Will start service with: {}", mConfig.toString());

        LocationProviderFactory spf = new LocationProviderFactory(this, mConfig);
        mProvider = spf.getInstance(mConfig.getLocationProvider());

        if (mConfig.getStartForeground()) {
            Notification notification = new NotificationFactory().getNotification(
                    mConfig.getNotificationTitle(),
                    mConfig.getNotificationText(),
                    mConfig.getLargeNotificationIcon(),
                    mConfig.getSmallNotificationIcon(),
                    mConfig.getNotificationIconColor());
            startForeground(NOTIF_ID, notification);
        }

        mProvider.onCreate();
        mProvider.onStart();
        isRunning = true;

        //We want this service to continue running until it is explicitly stopped
        return START_STICKY;
    }

    private int getAppResource(String name, String type) {
        return getApplication().getResources().getIdentifier(name, type, getApplication().getPackageName());
    }

    private String getStringResource(String name) {
        return getApplication().getString(getAppResource(name, "string"));
    }

    private Integer getDrawableResource(String resourceName) {
        return getAppResource(resourceName, "drawable");
    }

    private class NotificationFactory {
        private Integer parseNotificationIconColor(String color) {
            int iconColor = 0;
            if (color != null) {
                try {
                    iconColor = Color.parseColor(color);
                } catch (IllegalArgumentException e) {
                    logger.error("Couldn't parse color from android options");
                }
            }
            return iconColor;
        }

        public Notification getNotification(String title, String text, String largeIcon, String smallIcon, String color) {
            // Build a Notification required for running service in foreground.
            NotificationCompat.Builder builder = new NotificationCompat.Builder(LocationService.this);
            builder.setContentTitle(title);
            builder.setContentText(text);
            if (smallIcon != null && !smallIcon.isEmpty()) {
                builder.setSmallIcon(getDrawableResource(smallIcon));
            } else {
                builder.setSmallIcon(android.R.drawable.ic_menu_mylocation);
            }
            if (largeIcon != null && !largeIcon.isEmpty()) {
                builder.setLargeIcon(BitmapFactory.decodeResource(getApplication().getResources(), getDrawableResource(largeIcon)));
            }
            if (color != null && !color.isEmpty()) {
                builder.setColor(this.parseNotificationIconColor(color));
            }

            // Add an onclick handler to the notification
            Context context = getApplicationContext();
            String packageName = context.getPackageName();
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_CANCEL_CURRENT);
            builder.setContentIntent(contentIntent);

            Notification notification = builder.build();
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_NO_CLEAR;

            return notification;
        }
    }

    private void switchMode(int mode) {
        mProvider.onSwitchMode(mode);
    }

    private void configure(Config config) {
        if (!isRunning()) {
            return; // do not configure stopped service it will be configured when started
        }

        Config currentConfig = mConfig;
        mConfig = config;

        if (currentConfig.getStartForeground() == true && mConfig.getStartForeground() == false) {
            stopForeground(true);
        }

        if (mConfig.getStartForeground() == true) {
            Notification notification = new NotificationFactory().getNotification(
                    mConfig.getNotificationTitle(),
                    mConfig.getNotificationText(),
                    mConfig.getLargeNotificationIcon(),
                    mConfig.getSmallNotificationIcon(),
                    mConfig.getNotificationIconColor());

            if (currentConfig.getStartForeground() == false) {
                startForeground(NOTIF_ID, notification);
            } else {
                NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.notify(NOTIF_ID, notification);
            }
        }

        if (currentConfig.getLocationProvider() != mConfig.getLocationProvider()) {
            boolean shouldStart = mProvider.isStarted();
            mProvider.onDestroy();
            LocationProviderFactory spf = new LocationProviderFactory(this, mConfig);
            mProvider = spf.getInstance(mConfig.getLocationProvider());
            mProvider.onCreate();
            if (shouldStart) {
                mProvider.onStart();
            }
        } else {
            mProvider.onConfigure(mConfig);
        }
    }

    private void configure(Bundle bundle) {
        Config config = bundle.getParcelable(Config.BUNDLE_KEY);
        configure(config);
    }

    /**
     * Handle location from location location mProvider
     *
     * All locations updates are recorded in local db at all times.
     * Also location is also send to all messenger clients.
     *
     * If option.url is defined, each location is also immediately posted.
     * If post is successful, the location is deleted from local db.
     * All failed to post locations are coalesced and send in some time later in one single batch.
     * Batch sync takes place only when number of failed to post locations reaches syncTreshold.
     *
     * If only option.syncUrl is defined, locations are send only in single batch,
     * when number of locations reaches syncTreshold.
     *
     * @param location
     */
    public void handleLocation(BackgroundLocation location) {
        logger.debug("New location {}", location.toString());

        location.setBatchStartMillis(System.currentTimeMillis() + ONE_MINUTE_IN_MILLIS); // prevent sync of not yet posted location
        persistLocation(location);
        syncLocation(location);
        postLocation(location);

        Bundle bundle = new Bundle();
        bundle.putParcelable(BackgroundLocation.BUNDLE_KEY, location);
        Message msg = Message.obtain(null, MSG_LOCATION_UPDATE);
        msg.setData(bundle);

        locationManager.removeUpdates(locationListener);

        sendClientMessage(msg);
    }

    public void handleStationary(BackgroundLocation location) {
        logger.debug("New stationary {}", location.toString());

        location.setBatchStartMillis(System.currentTimeMillis() + ONE_MINUTE_IN_MILLIS); // prevent sync of not yet posted location
        persistLocation(location);
        syncLocation(location);
        postLocation(location);

        Bundle bundle = new Bundle();
        bundle.putParcelable(BackgroundLocation.BUNDLE_KEY, location);
        Message msg = Message.obtain(null, MSG_ON_STATIONARY);
        msg.setData(bundle);

        Message msgStationary = serviceHandler.obtainMessage();
        serviceHandler.sendMessage(msgStationary);

        sendClientMessage(msg);
    }

    public void handleActivity(BackgroundActivity activity) {
        logger.debug("New activity {}", activity.toString());

        Bundle bundle = new Bundle();
        bundle.putParcelable(BackgroundActivity.BUNDLE_KEY, activity);
        Message msg = Message.obtain(null, MSG_ON_ACTIVITY);
        msg.setData(bundle);

        sendClientMessage(msg);
    }

    public void sendClientMessage(Message msg) {
        Iterator<Messenger> it = mClients.values().iterator();
        while (it.hasNext()) {
            try {
                Messenger client = it.next();
                client.send(msg);
            } catch (RemoteException e) {
                // The client is dead.  Remove it from the list;
                // we are going through the list from back to front
                // so this is safe to do inside the loop.
                it.remove();
            }
        }
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return super.registerReceiver(receiver, filter, null, serviceHandler);
    }

    @Override
    public void unregisterReceiver (BroadcastReceiver receiver) {
        super.unregisterReceiver(receiver);
    }

    public void handleError(PluginError error) {
        Message msg = Message.obtain(null, MSG_ERROR);
        msg.setData(error.toBundle());
        sendClientMessage(msg);
    }

    // method will mutate location
    public Long persistLocation (BackgroundLocation location) {
        Long locationId = -1L;
        try {
            locationId = dao.persistLocationWithLimit(location, mConfig.getMaxLocations());
            location.setLocationId(locationId);
            logger.debug("Persisted location: {}", location.toString());
        } catch (SQLException e) {
            logger.error("Failed to persist location: {} error: {}", location.toString(), e.getMessage());
        }

        return locationId;
    }

    public void syncLocation(BackgroundLocation location) {
        if (mConfig.hasValidSyncUrl()) {
            Long locationsCount = dao.locationsForSyncCount(System.currentTimeMillis());
            logger.debug("Location to sync: {} threshold: {}", locationsCount, mConfig.getSyncThreshold());
            if (locationsCount >= mConfig.getSyncThreshold()) {
                logger.debug("Attempt to sync locations: {} threshold: {}", locationsCount, mConfig.getSyncThreshold());
                SyncService.sync(mSyncAccount, getStringResource(Config.CONTENT_AUTHORITY_RESOURCE));
            }
        }
    }

    public void postLocation(BackgroundLocation location) {
        if (hasConnectivity && mConfig.hasValidUrl()) {
            PostLocationTask task = new LocationService.PostLocationTask();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, location);
            }
            else {
                task.execute(location);
            }
        }
    }

    public Config getConfig() {
        return this.mConfig;
    }

//    public void setConfig(Config config) {
//        this.mConfig = config;
//    }

    private class PostLocationTask extends AsyncTask<BackgroundLocation, Integer, Boolean> {

        @Override
        protected Boolean doInBackground(BackgroundLocation... locations) {
            logger.debug("Executing PostLocationTask#doInBackground");
            JSONArray jsonLocations = new JSONArray();
            for (BackgroundLocation location : locations) {
                Config config = getConfig();
                try {
                    jsonLocations.put(config.getTemplate().locationToJson(location));
                } catch (JSONException e) {
                    logger.warn("Location to json failed: {}", location.toString());
                    return false;
                }
            }

            String url = mConfig.getUrl();
            logger.debug("Posting json to url: {} headers: {}", url, mConfig.getHttpHeaders());
            int responseCode;

            try {
                responseCode = HttpPostService.postJSON(url, jsonLocations, mConfig.getHttpHeaders());
            } catch (Exception e) {
                hasConnectivity = isNetworkAvailable();
                logger.warn("Error while posting locations: {}", e.getMessage());
                return false;
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                logger.warn("Server error while posting locations responseCode: {}", responseCode);
                return false;
            }

            for (BackgroundLocation location : locations) {
                Long locationId = location.getLocationId();
                if (locationId != null) {
                    dao.deleteLocation(locationId);
                }
            }

            return true;
        }
    }

    /**
     * Broadcast receiver which detects connectivity change condition
     */
    private BroadcastReceiver connectivityChangeReceiver = new BroadcastReceiver() {
        private static final String LOG_TAG = "NetworkChangeReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            hasConnectivity = isNetworkAvailable();
            logger.info("Network condition changed hasConnectivity: {}", hasConnectivity);
        }
    };

    private boolean isNetworkAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    public static boolean isRunning() {
        return LocationService.isRunning;
    }
}
