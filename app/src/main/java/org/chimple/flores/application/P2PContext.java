package org.chimple.flores.application;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import static org.chimple.flores.multicast.MulticastManager.multiCastConnectionChangedEvent;

public class P2PContext {
    public static final String SHARED_PREF = "shardPref";
    public static final String USER_ID = "USER_ID";
    public static final String DEVICE_ID = "DEVICE_ID";
    public static final String NEW_MESSAGE_ADDED = "NEW_MESSAGE_ADDED";
    public static final String REFRESH_DEVICE = "REFRESH_DEVICE";
    public static final String messageEvent = "message-event";
    public static final String uiMessageEvent = "ui-message-event";
    public static final String newMessageAddedOnDevice = "new-message-added-event";
    public static final String refreshDevice = "refresh-device-event";
    public static final String MULTICAST_IP_ADDRESS = "235.1.1.0";
    public static final String MULTICAST_IP_PORT = "4450";
    public static final String CONSOLE_TYPE = "console";
    public static final String LOG_TYPE = "log";
    public static final String CLEAR_CONSOLE_TYPE = "clear-console";
    private static final String TAG = P2PContext.class.getName();
    private static P2PContext instance;

    private boolean initialized;
    private boolean isNetWorkConnected;

    public static P2PContext getInstance() {
        if (instance == null) {
            synchronized (P2PContext.class) {
                instance = new P2PContext();

            }
        }

        return instance;
    }

    private P2PContext() {
        // Singleton
    }

    public synchronized void initialize(final Context context) {
        if (initialized) {
            return;
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(networkChangeReceiver, intentFilter);
        initialized = true;

    }

    private BroadcastReceiver networkChangeReceiver = new BroadcastReceiver() {


        public void onReceive(Context context, Intent intent) {
            int status = NetworkUtil.getConnectivityStatusString(context);
            if ("android.net.conn.CONNECTIVITY_CHANGE".equals(intent.getAction())) {
                if (status == NetworkUtil.NETWORK_STATUS_NOT_CONNECTED) {
                    Log.d(TAG, "NETWORK_STATUS_NOT_CONNECTED");
                    isNetWorkConnected = false;
                } else {
                    Log.d(TAG, "NETWORK_STATUS_CONNECTED");
                    isNetWorkConnected = true;
                }
                this.notifyNetWorkChange(context, isNetWorkConnected);
            }
        }

        private void notifyNetWorkChange(Context context, boolean isNetWorkConnected) {
            Log.d(TAG, "Broadcasting message notifyNetWorkChange for MultiCast");
            Intent intent = new Intent(multiCastConnectionChangedEvent);
            intent.putExtra("isConnected", isNetWorkConnected);
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        }
    };


    public boolean isNetWorkConnected() {
        return isNetWorkConnected;
    }
}