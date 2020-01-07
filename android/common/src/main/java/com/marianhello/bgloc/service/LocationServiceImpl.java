/*
According to apache license

This is fork of christocracy cordova-plugin-background-geolocation plugin
https://github.com/christocracy/cordova-plugin-background-geolocation

This is a new class
*/

package com.marianhello.bgloc.service;

import android.Manifest;
import android.accounts.Account;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.app.ActivityCompat;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.ConnectivityListener;
import com.marianhello.bgloc.sync.NotificationHelper;
import com.marianhello.bgloc.PluginException;
import com.marianhello.bgloc.PostLocationTask;
import com.marianhello.bgloc.ResourceResolver;
import com.marianhello.bgloc.data.BackgroundActivity;
import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.LocationDAO;
import com.marianhello.bgloc.data.LocationTransform;
import com.marianhello.bgloc.headless.AbstractTaskRunner;
import com.marianhello.bgloc.headless.ActivityTask;
import com.marianhello.bgloc.headless.LocationTask;
import com.marianhello.bgloc.headless.StationaryTask;
import com.marianhello.bgloc.headless.Task;
import com.marianhello.bgloc.headless.TaskRunner;
import com.marianhello.bgloc.headless.TaskRunnerFactory;
import com.marianhello.bgloc.provider.LocationProvider;
import com.marianhello.bgloc.provider.LocationProviderFactory;
import com.marianhello.bgloc.provider.ProviderDelegate;
import com.marianhello.bgloc.sync.AccountHelper;
import com.marianhello.bgloc.sync.SyncService;
import com.marianhello.logging.LoggerManager;
import com.marianhello.logging.UncaughtExceptionLogger;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import org.chromium.content.browser.ThreadUtils;
import org.json.JSONException;

import static com.marianhello.bgloc.service.LocationServiceIntentBuilder.containsCommand;
import static com.marianhello.bgloc.service.LocationServiceIntentBuilder.containsMessage;
import static com.marianhello.bgloc.service.LocationServiceIntentBuilder.getCommand;
import static com.marianhello.bgloc.service.LocationServiceIntentBuilder.getMessage;

public class LocationServiceImpl extends Service implements ProviderDelegate, LocationService {

    public static final String ACTION_BROADCAST = ".broadcast";

    /**
     * CommandId sent by the service to
     * any registered clients with error.
     */
    public static final int MSG_ON_ERROR = 100;

    /**
     * CommandId sent by the service to
     * any registered clients with the new position.
     */
    public static final int MSG_ON_LOCATION = 101;

    /**
     * CommandId sent by the service to
     * any registered clients whenever the devices enters "stationary-mode"
     */
    public static final int MSG_ON_STATIONARY = 102;

    /**
     * CommandId sent by the service to
     * any registered clients with new detected activity.
     */
    public static final int MSG_ON_ACTIVITY = 103;

    public static final int MSG_ON_SERVICE_STARTED = 104;

    public static final int MSG_ON_SERVICE_STOPPED = 105;

    public static final int MSG_ON_ABORT_REQUESTED = 106;

    public static final int MSG_ON_HTTP_AUTHORIZATION = 107;

    /** notification id */
    private static int NOTIFICATION_ID = 1;

    private ResourceResolver mResolver;
    private Config mConfig;
    private LocationProvider mProvider;
    private Account mSyncAccount;

    private org.slf4j.Logger logger;

    private final IBinder mBinder = new LocalBinder();
    private HandlerThread mHandlerThread;
    private ServiceHandler mServiceHandler;
    private LocationDAO mLocationDAO;
    private PostLocationTask mPostLocationTask;
    private String mHeadlessTaskRunnerClass;
    private TaskRunner mHeadlessTaskRunner;

    private long mServiceId = -1;
    private static boolean sIsRunning = false;
    private boolean mIsInForeground = false;

    private static LocationTransform sLocationTransform;
    private static LocationProviderFactory sLocationProviderFactory;

    private LocationManager locationManager;
    private LocationListener locationListener;
    TelephonyManager telephonyManager;

    private class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
        }
    }

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        logger.debug("Client binds to service");
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        logger.debug("Client rebinds to service");
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // All clients have unbound with unbindService()
        logger.debug("All clients have been unbound from service");

        return true; // Ensures onRebind() is called when a client re-binds.
    }

    @Override
    public void onCreate() {
        super.onCreate();

        sIsRunning = false;

        UncaughtExceptionLogger.register(this);

        logger = LoggerManager.getLogger(LocationServiceImpl.class);
        logger.info("Creating LocationServiceImpl");

        mServiceId = System.currentTimeMillis();

        // Start up the thread running the service.  Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block.  We also make it
        // background priority so CPU-intensive work will not disrupt our UI.
        if (mHandlerThread == null) {
            mHandlerThread = new HandlerThread("LocationServiceImpl.Thread", Process.THREAD_PRIORITY_BACKGROUND);
        }
        mHandlerThread.start();
        // An Android service handler is a handler running on a specific background thread.
        mServiceHandler = new ServiceHandler(mHandlerThread.getLooper());

        mResolver = ResourceResolver.newInstance(this);

        mSyncAccount = AccountHelper.CreateSyncAccount(this, mResolver.getAccountName(),
                mResolver.getAccountType());

        String authority = mResolver.getAuthority();
        ContentResolver.setIsSyncable(mSyncAccount, authority, 1);
        ContentResolver.setSyncAutomatically(mSyncAccount, authority, true);

        mLocationDAO = DAOFactory.createLocationDAO(this);

        locationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        mPostLocationTask = new PostLocationTask(mLocationDAO,
                new PostLocationTask.PostLocationTaskListener() {
                    @Override
                    public void onRequestedAbortUpdates() {
                        handleRequestedAbortUpdates();
                    }

                    @Override
                    public void onHttpAuthorizationUpdates() {
                        handleHttpAuthorizationUpdates();
                    }

                    @Override
                    public void onSyncRequested() {
                        SyncService.sync(mSyncAccount, mResolver.getAuthority(), false);
                    }
                }, new ConnectivityListener() {
            @Override
            public boolean hasConnectivity() {
                return isNetworkAvailable();
            }
        });
        
        registerReceiver(connectivityChangeReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        NotificationHelper.registerServiceChannel(this);
    }

    @Override
    public void onDestroy() {
        logger.info("Destroying LocationServiceImpl");

        // workaround for issue #276
        if (mProvider != null) {
            mProvider.onDestroy();
        }

        if (mHandlerThread != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                mHandlerThread.quitSafely();
            } else {
                mHandlerThread.quit(); //sorry
            }
        }

        if (mPostLocationTask != null) {
            mPostLocationTask.shutdown();
        }


        unregisterReceiver(connectivityChangeReceiver);

        sIsRunning = false;
        locationManager.removeUpdates(locationListener);
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        logger.debug("Task has been removed");
        // workaround for issue #276
        Config config = getConfig();
        if (config.getStopOnTerminate()) {
            logger.info("Stopping self");
            stopSelf();
        } else {
            logger.info("Continue running in background");
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // when service was killed and restarted we will restart service
            start();
            return START_STICKY;
        }

        boolean containsCommand = containsCommand(intent);
        logger.debug(
                String.format("Service in [%s] state. cmdId: [%s]. startId: [%d]",
                        sIsRunning ? "STARTED" : "NOT STARTED",
                        containsCommand ? getCommand(intent).getId() : "N/A",
                        startId)
        );

        if (containsCommand) {
            LocationServiceIntentBuilder.Command cmd = getCommand(intent);
            processCommand(cmd.getId(), cmd.getArgument());
        }

        if (containsMessage(intent)) {
            processMessage(getMessage(intent));
        }

        final String TAG = "LocationService";

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                Log.i(TAG, "onLocationChanged: " + location.getAccuracy() + ", " + location.getLatitude() + ","
                        + location.getLongitude() + ", " + location.getProvider());
                if (ActivityCompat.checkSelfPermission(getApplicationContext(),
                        Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "without permissions");
                    return;
                }

                Config config = getConfig();
                String userID = config.getBmpUserID();
                Calendar calendar = new GregorianCalendar();
                Date time = new Date(location.getTime());

                calendar.setTime(time);

                FirebaseAuth firebaseAuth = FirebaseAuth.getInstance();

                DatabaseReference databaseReference = FirebaseDatabase.getInstance().getReference().child("DOMAINS")
                        .child(firebaseAuth.getUid()).child("USERS").child(userID)
                        .child(String.valueOf(calendar.get(Calendar.YEAR)))
                        .child(String.valueOf(calendar.get(Calendar.MONTH) + 1))
                        .child(String.valueOf(calendar.get(Calendar.DAY_OF_MONTH))).child("POSITIONS")
                        .child(String.valueOf(time.getTime()));
                databaseReference.child("accuracy").setValue(location.getAccuracy());
                databaseReference.child("altitude").setValue(location.getAltitude());
                databaseReference.child("dateReceiveFB").setValue(ServerValue.TIMESTAMP);
                databaseReference.child("device").setValue(Build.MODEL);
                databaseReference.child("imei").setValue("" + telephonyManager.getDeviceId());
                databaseReference.child("isMainAppVisible").setValue(false);
                databaseReference.child("position").setValue(location.getLatitude() + "," + location.getLongitude());
                databaseReference.child("provider").setValue(location.getProvider());
                String hours = (time.getHours() < 10 || time.getHours() == 0) ? "0" + time.getHours()
                        : String.valueOf(time.getHours());
                String minutes = (time.getMinutes() < 10 || time.getMinutes() == 0) ? "0" + time.getMinutes()
                        : String.valueOf(time.getMinutes());
                String seconds = (time.getSeconds() < 10 || time.getSeconds() == 0) ? "0" + time.getSeconds()
                        : String.valueOf(time.getSeconds());
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
                Log.i(TAG, "onProviderDisabled: " + provider);
                Toast.makeText(getApplicationContext(), "Proveedor deshabilitado", Toast.LENGTH_LONG).show();
            }
        };

        return START_STICKY;
    }

    private void processMessage(String message) {
        // currently we do not process any message
    }

    private void processCommand(int command, Object arg) {
        try {
            switch (command) {
                case CommandId.START:
                    start();
                    break;
                case CommandId.START_FOREGROUND_SERVICE:
                    startForegroundService();
                    break;
                case CommandId.STOP:
                    stop();
                    break;
                case CommandId.CONFIGURE:
                    configure((Config) arg);
                    break;
                case CommandId.STOP_FOREGROUND:
                    stopForeground();
                    break;
                case CommandId.START_FOREGROUND:
                    startForeground();
                    break;
                case CommandId.REGISTER_HEADLESS_TASK:
                    registerHeadlessTask((String) arg);
                    break;
                case CommandId.START_HEADLESS_TASK:
                    startHeadlessTask();
                    break;
                case CommandId.STOP_HEADLESS_TASK:
                    stopHeadlessTask();
                    break;
            }
        } catch (Exception e) {
            logger.error("processCommand: exception", e);
        }
    }

    @Override
    public synchronized void start() {
        if (sIsRunning) {
            return;
        }

        if (mConfig == null) {
            logger.warn("Attempt to start unconfigured service. Will use stored or default.");
            mConfig = getConfig();
            // TODO: throw JSONException if config cannot be obtained from db
        }

        logger.debug("Will start service with: {}", mConfig.toString());

        mPostLocationTask.setConfig(mConfig);
        mPostLocationTask.clearQueue();

        LocationProviderFactory spf = sLocationProviderFactory != null
                ? sLocationProviderFactory : new LocationProviderFactory(this);
        mProvider = spf.getInstance(mConfig.getLocationProvider());
        mProvider.setDelegate(this);
        mProvider.onCreate();
        mProvider.onConfigure(mConfig);

        sIsRunning = true;
        ThreadUtils.runOnUiThreadBlocking(new Runnable() {
            @Override
            public void run() {
                mProvider.onStart();
                if (mConfig.getStartForeground()) {
                    startForeground();
                }
            }
        });

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_SERVICE_STARTED);
        bundle.putLong("serviceId", mServiceId);
        broadcastMessage(bundle);
    }

    @Override
    public synchronized void startForegroundService() {
        start();
        startForeground();
    }

    @Override
    public synchronized void stop() {
        if (!sIsRunning) {
            return;
        }

        if (mProvider != null) {
            mProvider.onStop();
        }

        stopForeground(true);
        stopSelf();

        broadcastMessage(MSG_ON_SERVICE_STOPPED);
        sIsRunning = false;
    }

    @Override
    public void startForeground() {
        if (sIsRunning && !mIsInForeground) {
            Config config = getConfig();
            Notification notification = new NotificationHelper.NotificationFactory(this).getNotification(
                    config.getNotificationTitle(),
                    config.getNotificationText(),
                    config.getLargeNotificationIcon(),
                    config.getSmallNotificationIcon(),
                    config.getNotificationIconColor());

            if (mProvider != null) {
                mProvider.onCommand(LocationProvider.CMD_SWITCH_MODE,
                        LocationProvider.FOREGROUND_MODE);
            }
            super.startForeground(NOTIFICATION_ID, notification);
            mIsInForeground = true;
        }
    }

    @Override
    public synchronized void stopForeground() {
        if (sIsRunning && mIsInForeground) {
            stopForeground(true);
            if (mProvider != null) {
                mProvider.onCommand(LocationProvider.CMD_SWITCH_MODE,
                        LocationProvider.BACKGROUND_MODE);
            }
            mIsInForeground = false;
        }
    }

    @Override
    public synchronized void configure(Config config) {
        if (mConfig == null) {
            mConfig = config;
            return;
        }

        final Config currentConfig = mConfig;
        mConfig = config;

        mPostLocationTask.setConfig(mConfig);

        ThreadUtils.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (sIsRunning) {
                    if (currentConfig.getStartForeground() == true && mConfig.getStartForeground() == false) {
                        stopForeground(true);
                    }

                    if (mConfig.getStartForeground() == true) {
                        if (currentConfig.getStartForeground() == false) {
                            // was not running in foreground, so start in foreground
                            startForeground();
                        } else {
                            // was running in foreground, so just update existing notification
                            Notification notification = new NotificationHelper.NotificationFactory(LocationServiceImpl.this).getNotification(
                                    mConfig.getNotificationTitle(),
                                    mConfig.getNotificationText(),
                                    mConfig.getLargeNotificationIcon(),
                                    mConfig.getSmallNotificationIcon(),
                                    mConfig.getNotificationIconColor());

                            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                            notificationManager.notify(NOTIFICATION_ID, notification);
                        }
                    }
                }

                if (currentConfig.getLocationProvider() != mConfig.getLocationProvider()) {
                    boolean shouldStart = mProvider.isStarted();
                    mProvider.onDestroy();
                    LocationProviderFactory spf = new LocationProviderFactory(LocationServiceImpl.this);
                    mProvider = spf.getInstance(mConfig.getLocationProvider());
                    mProvider.setDelegate(LocationServiceImpl.this);
                    mProvider.onCreate();
                    mProvider.onConfigure(mConfig);
                    if (shouldStart) {
                        mProvider.onStart();
                    }
                } else {
                    mProvider.onConfigure(mConfig);
                }
            }
        });
    }

    @Override
    public synchronized void registerHeadlessTask(String taskRunnerClass) {
        logger.debug("Registering headless task");
        mHeadlessTaskRunnerClass = taskRunnerClass;
    }

    @Override
    public synchronized void startHeadlessTask() {
        if (mHeadlessTaskRunnerClass != null) {
            TaskRunnerFactory trf = new TaskRunnerFactory();
            try {
                mHeadlessTaskRunner = trf.getTaskRunner(mHeadlessTaskRunnerClass);
                ((AbstractTaskRunner) mHeadlessTaskRunner).setContext(this);
            } catch (Exception e) {
                logger.error("Headless task start failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public synchronized void stopHeadlessTask() {
        mHeadlessTaskRunner = null;
    }

    @Override
    public synchronized void executeProviderCommand(final int command, final int arg1) {
        if (mProvider == null) {
            return;
        }

        ThreadUtils.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mProvider.onCommand(command, arg1);
            }
        });
    }

    @Override
    public void onLocation(BackgroundLocation location) {

        location = transformLocation(location);
        if (location == null) {
            logger.debug("Skipping location as requested by the locationTransform");
            return;
        }

        String TAG = "LocationService";
        Log.i(TAG, "New location: " + location.toString());

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_LOCATION);
        bundle.putParcelable("payload", location);
        broadcastMessage(bundle);

        runHeadlessTask(new LocationTask(location) {
            @Override
            public void onError(String errorMessage) {
                logger.error("Location task error: {}", errorMessage);
            }

            @Override
            public void onResult(String value) {
                logger.debug("Location task result: {}", value);
            }
        });

        postLocation(location);

        locationManager.removeUpdates(locationListener);
    }

    @Override
    public void onStationary(BackgroundLocation location) {

        location = transformLocation(location);
        if (location == null) {
            logger.debug("Skipping location as requested by the locationTransform");
            return;
        }

        String TAG = "LocationService";
        Log.i(TAG, "New stationary: " + location.toString());

        //if user gets stationary mode, the previews locationListener is removed in order to not to create more than one location listener
        locationManager.removeUpdates(locationListener);

        //create location listener
        if (ActivityCompat.checkSelfPermission(getApplicationContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(getApplicationContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "without permissions");
        }
        locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 300 * 1000, 0, locationListener);


        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_STATIONARY);
        bundle.putParcelable("payload", location);
        broadcastMessage(bundle);

        runHeadlessTask(new StationaryTask(location){
            @Override
            public void onError(String errorMessage) {
                logger.error("Stationary task error: {}", errorMessage);
            }

            @Override
            public void onResult(String value) {
                logger.debug("Stationary task result: {}", value);
            }
        });

        postLocation(location);

        Message msgStationary = mServiceHandler.obtainMessage();
        mServiceHandler.sendMessage(msgStationary);
    }

    @Override
    public void onActivity(BackgroundActivity activity) {

        String TAG = "LocationService";
        Log.i(TAG, "New Activity: " + activity.toString());

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_ACTIVITY);
        bundle.putParcelable("payload", activity);
        broadcastMessage(bundle);

        runHeadlessTask(new ActivityTask(activity){
            @Override
            public void onError(String errorMessage) {
                logger.error("Activity task error: {}", errorMessage);
            }

            @Override
            public void onResult(String value) {
                logger.debug("Activity task result: {}", value);
            }
        });
    }

    @Override
    public void onError(PluginException error) {
        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_ERROR);
        bundle.putBundle("payload", error.toBundle());
        broadcastMessage(bundle);
    }

    private void broadcastMessage(int msgId) {
        Bundle bundle = new Bundle();
        bundle.putInt("action", msgId);
        broadcastMessage(bundle);
    }

    private void broadcastMessage(Bundle bundle) {
        Intent intent = new Intent(ACTION_BROADCAST);
        intent.putExtras(bundle);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return super.registerReceiver(receiver, filter, null, mServiceHandler);
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        try {
            super.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ex) {
            // if was not registered ignore exception
        }
    }

    public Config getConfig() {
        Config config = mConfig;
        if (config == null) {
            ConfigurationDAO dao = DAOFactory.createConfigurationDAO(this);
            try {
                config = dao.retrieveConfiguration();
            } catch (JSONException e) {
                logger.error("Config exception: {}", e.getMessage());
            }
        }

        if (config == null) {
            config = Config.getDefault();
        }

        mConfig = config;
        return mConfig;
    }

    public static void setLocationProviderFactory(LocationProviderFactory factory) {
        sLocationProviderFactory = factory;
    }

    private void runHeadlessTask(Task task) {
        if (mHeadlessTaskRunner == null) {
            return;
        }

        logger.debug("Running headless task: {}", task);
        mHeadlessTaskRunner.runTask(task);
    }

    /**
     * Class used for the client Binder.  Since this service runs in the same process as its
     * clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        public LocationServiceImpl getService() {
            return LocationServiceImpl.this;
        }
    }

    private BackgroundLocation transformLocation(BackgroundLocation location) {
        if (sLocationTransform != null) {
            return sLocationTransform.transformLocationBeforeCommit(this, location);
        }

        return location;
    }

    private void postLocation(BackgroundLocation location) {
        mPostLocationTask.add(location);
    }

    public void handleRequestedAbortUpdates() {
        broadcastMessage(MSG_ON_ABORT_REQUESTED);
    }

    public void handleHttpAuthorizationUpdates() {
        broadcastMessage(MSG_ON_HTTP_AUTHORIZATION);
    }

    /**
     * Broadcast receiver which detects connectivity change condition
     */
    private BroadcastReceiver connectivityChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean hasConnectivity = isNetworkAvailable();
            mPostLocationTask.setHasConnectivity(hasConnectivity);
            logger.info("Network condition changed has connectivity: {}", hasConnectivity);
        }
    };

    private boolean isNetworkAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    public long getServiceId() {
        return mServiceId;
    }

    public boolean isBound() {
        LocationServiceInfo info = new LocationServiceInfoImpl(this);
        return info.isBound();
    }

    public static boolean isRunning() {
        return sIsRunning;
    }

    public static void setLocationTransform(@Nullable LocationTransform transform) {
        sLocationTransform = transform;
    }

    public static @Nullable LocationTransform getLocationTransform() {
        return sLocationTransform;
    }
}
