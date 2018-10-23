package org.chimple.flores.multicast;

import android.arch.persistence.room.util.StringUtil;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.chimple.flores.application.P2PContext;
import org.chimple.flores.db.DBSyncManager;
import org.chimple.flores.db.P2PDBApiImpl;
import org.chimple.flores.db.entity.HandShakingInfo;
import org.chimple.flores.db.entity.HandShakingMessage;
import org.chimple.flores.db.entity.P2PSyncInfo;
import org.chimple.flores.db.entity.SyncInfoItem;
import org.chimple.flores.db.entity.SyncInfoRequestMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.chimple.flores.application.P2PContext.messageEvent;
import static org.chimple.flores.application.P2PContext.newMessageAddedOnDevice;
import static org.chimple.flores.application.P2PContext.refreshDevice;
import static org.chimple.flores.application.P2PContext.CLEAR_CONSOLE_TYPE;
import static org.chimple.flores.application.P2PContext.CONSOLE_TYPE;
import static org.chimple.flores.application.P2PContext.LOG_TYPE;
import static org.chimple.flores.application.P2PContext.MULTICAST_IP_ADDRESS;
import static org.chimple.flores.application.P2PContext.MULTICAST_IP_PORT;
import static org.chimple.flores.application.P2PContext.NEW_MESSAGE_ADDED;
import static org.chimple.flores.application.P2PContext.uiMessageEvent;

public class MulticastManager {

    private static final String TAG = MulticastManager.class.getSimpleName();
    private Context context;
    private static MulticastManager instance;
    private boolean isListening = false;
    private MulticastListenerThread multicastListenerThread;
    private MulticastSenderThread multicastSenderThread;
    private WifiManager.MulticastLock wifiLock;
    private String multicastIpAddress;
    private int multicastPort;
    private P2PDBApiImpl p2PDBApiImpl;
    private DBSyncManager dbSyncManager;
    private Map<String, HandShakingMessage> handShakingMessagesInCurrentLoop = new ConcurrentHashMap<>();
    private Set<String> allSyncInfosReceived = new HashSet<String>();

    public static final String multiCastConnectionChangedEvent = "multicast-connection-changed-event";

    private CountDownTimer waitForHandShakingMessagesTimer = null;
    private CountDownTimer stopMulticastTimer = null;
    private CountDownTimer startMulticastTimer = null;

    private static final int WAIT_FOR_HAND_SHAKING_MESSAGES = 5 * 1000; // 5 sec
    private static final int STOP_MULTICAST_TIMER = 1 * 1000; // 1 sec
    private static final int START_MULTICAST_TIMER = 3 * 1000; // 3 sec


    public static MulticastManager getInstance(Context context) {
        if (instance == null) {
            synchronized (MulticastManager.class) {
                instance = new MulticastManager(context);
                instance.setMulticastIpAddress(MULTICAST_IP_ADDRESS);
                instance.setMulticastPort(MULTICAST_IP_PORT);
                instance.registerMulticastBroadcasts();
                instance.dbSyncManager = DBSyncManager.getInstance(context);
                instance.p2PDBApiImpl = P2PDBApiImpl.getInstance(context);

                instance.broadCastRefreshDevice();
            }

        }
        return instance;
    }

    private MulticastManager(Context context) {
        this.context = context;
    }

    public void onCleanUp() {
        stopListening();
        stopThreads();
        if (instance != null) {
            instance.unregisterMulticastBroadcasts();
        }
        instance = null;
    }

    public void startListening() {
        if (!isListening) {
            setWifiLockAcquired(true);
            this.multicastListenerThread = new MulticastListenerThread(this.context, getMulticastIP(), getMulticastPort());
            multicastListenerThread.start();
            isListening = true;
        }
    }

    public boolean isListening() {
        return isListening;
    }

    public void stopListening() {
        if (isListening) {
            Log.d(TAG, "stopListening called");
            isListening = false;
            stopThreads();
            setWifiLockAcquired(false);
        }
    }

    public void sendMulticastMessage(String message) {
        if (this.isListening) {
            Log.d(TAG, "sending message: " + message);
            this.multicastSenderThread = new MulticastSenderThread(this.context, getMulticastIP(), getMulticastPort(), message);
            multicastSenderThread.start();
        }
    }

    private void stopThreads() {
        if (this.multicastListenerThread != null) {
            this.multicastListenerThread.stopRunning();
            this.multicastListenerThread.cleanUp();
        }
        if (this.multicastSenderThread != null) {
            this.multicastSenderThread.interrupt();
            this.multicastSenderThread.cleanUp();
        }
    }

    private void setWifiLockAcquired(boolean acquired) {
        if (acquired) {
            if (wifiLock != null && wifiLock.isHeld())
                wifiLock.release();

            WifiManager wifi = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                this.wifiLock = wifi.createMulticastLock(TAG);
                wifiLock.acquire();
            }
        } else {
            if (wifiLock != null && wifiLock.isHeld())
                wifiLock.release();
        }
    }

    public void setMulticastIpAddress(String address) {
        this.multicastIpAddress = address;
    }

    public void setMulticastPort(String port) {
        this.multicastPort = Integer.parseInt(port);
    }

    public String getMulticastIP() {
        return this.multicastIpAddress;
    }

    public int getMulticastPort() {
        return this.multicastPort;
    }

    private void unregisterMulticastBroadcasts() {
        if (netWorkChangerReceiver != null) {
            LocalBroadcastManager.getInstance(this.context).unregisterReceiver(netWorkChangerReceiver);
            netWorkChangerReceiver = null;
        }

        if (mMessageEventReceiver != null) {
            LocalBroadcastManager.getInstance(this.context).unregisterReceiver(mMessageEventReceiver);
            mMessageEventReceiver = null;
        }

        if (newMessageAddedReceiver != null) {
            LocalBroadcastManager.getInstance(this.context).unregisterReceiver(newMessageAddedReceiver);
            newMessageAddedReceiver = null;
        }

        if (refreshDeviceReceiver != null) {
            LocalBroadcastManager.getInstance(this.context).unregisterReceiver(refreshDeviceReceiver);
            refreshDeviceReceiver = null;
        }

    }

    private void registerMulticastBroadcasts() {
        LocalBroadcastManager.getInstance(this.context).registerReceiver(netWorkChangerReceiver, new IntentFilter(multiCastConnectionChangedEvent));
        LocalBroadcastManager.getInstance(this.context).registerReceiver(mMessageEventReceiver, new IntentFilter(messageEvent));
        LocalBroadcastManager.getInstance(this.context).registerReceiver(newMessageAddedReceiver, new IntentFilter(newMessageAddedOnDevice));
        LocalBroadcastManager.getInstance(this.context).registerReceiver(refreshDeviceReceiver, new IntentFilter(refreshDevice));
    }


    private void stopMultiCastOperations() {

        instance.stopListening();
    }

    public void startMultiCastOperations() {
        instance.startListening();
        if (P2PContext.getCurrentDevice() != null && P2PContext.getLoggedInUser() != null) {
            Log.d(TAG, "in sendFindBuddyMessage");
            Log.d(TAG, "startMultiCastOperations getCurrentDevice ----> " + P2PContext.getCurrentDevice());
            Log.d(TAG, "startMultiCastOperations getLoggedInUser ----> " + P2PContext.getLoggedInUser());
            instance.sendFindBuddyMessage();
        }
    }


    private BroadcastReceiver netWorkChangerReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            synchronized (MulticastManager.class) {
                boolean isConnected = intent.getBooleanExtra("isConnected", false);
                if (!isConnected) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            instance.stopMulticastTimer = new CountDownTimer(STOP_MULTICAST_TIMER, 1000) {
                                @Override
                                public void onTick(long millisUntilFinished) {

                                }

                                @Override
                                public void onFinish() {
                                    notifyUI("stopping multicast operations", " ------> ", LOG_TYPE);
                                    instance.stopMultiCastOperations();
                                }
                            }.start();

                        }
                    });
                } else {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            instance.startMulticastTimer = new CountDownTimer(START_MULTICAST_TIMER, 1000) {
                                @Override
                                public void onTick(long millisUntilFinished) {

                                }

                                @Override
                                public void onFinish() {
                                    notifyUI("starting multicast operations", " ------> ", LOG_TYPE);
                                    instance.startMultiCastOperations();
                                }
                            }.start();
                        }
                    });
                }
            }
        }
    };

    private void broadCastRefreshDevice() {
        Intent intent = new Intent(refreshDevice);
        LocalBroadcastManager.getInstance(this.context).sendBroadcast(intent);
    }

    private BroadcastReceiver refreshDeviceReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            synchronized (MulticastManager.class) {
                AsyncTask.execute(new Runnable() {
                    @Override
                    public void run() {
                        notifyUI("Clear ALL...", " ------> ", CLEAR_CONSOLE_TYPE);
                        List<P2PSyncInfo> allInfos = p2PDBApiImpl.refreshAllMessages();
                        if (allInfos != null) {
                            Iterator<P2PSyncInfo> allInfosIt = allInfos.iterator();
                            while (allInfosIt.hasNext()) {
                                P2PSyncInfo p = allInfosIt.next();
                                instance.getAllSyncInfosReceived().add(p.getDeviceId() + "_" + p.getUserId() + "_" + Long.valueOf(p.getSequence().longValue()));
                                String sender = p.getSender().equals(P2PContext.getCurrentDevice()) ? "You" : p.getSender();
                                //notifyUI(p.message, sender, CONSOLE_TYPE);
                            }
                        }
                        Log.d(TAG, "rebuild sync info received cache and updated UI");
                    }

                });
            }
        }
    };

    private BroadcastReceiver newMessageAddedReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            P2PSyncInfo info = (P2PSyncInfo) intent.getSerializableExtra(NEW_MESSAGE_ADDED);
            if (info != null) {
                String syncMessage = p2PDBApiImpl.convertSingleP2PSyncInfoToJsonUsingStreaming(info);
                instance.sendMulticastMessage(syncMessage);
            }
        }
    };

    private BroadcastReceiver mMessageEventReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            String message = intent.getStringExtra("message");
            String fromIP = intent.getStringExtra("fromIP");
            processInComingMessage(message, fromIP);
        }
    };


    public void notifyUI(String message, String fromIP, String type) {

        final String consoleMessage = "[" + fromIP + "]: " + message + "\n";
        Log.d(TAG, "got message: " + consoleMessage);
        Intent intent = new Intent(uiMessageEvent);
        intent.putExtra("message", consoleMessage);
        intent.putExtra("type", type);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }


    private boolean isHandShakingMessage(String message) {
        boolean isHandShakingMessage = false;
        if (message != null) {
            String handShakeMessage = "\"message_type\":\"handshaking\"";
            isHandShakingMessage = message.contains(handShakeMessage);
        }
        return isHandShakingMessage;
    }

    private boolean isSyncInfoMessage(String message) {
        boolean isSyncInfoMessage = false;
        if (message != null) {
            String syncInfoMessage = "\"message_type\":\"syncInfoMessage\"";
            isSyncInfoMessage = message.contains(syncInfoMessage);
        }
        return isSyncInfoMessage;
    }

    private boolean isSyncRequestMessage(String message) {
        String messageType = "\"message_type\":\"syncInfoRequestMessage\"";
        String messageType_1 = "\"mt\":\"syncInfoRequestMessage\"";
        return message != null && (message.contains(messageType) || message.contains(messageType_1)) ? true : false;
    }


    private void sendInitialHandShakingMessage(boolean needAck) {
        // construct handshaking message(s)
        // put in queue - TBD
        // send one by one from queue - TBD
        String serializedHandShakingMessage = instance.p2PDBApiImpl.serializeHandShakingMessage(needAck);
        Log.d(TAG, "sending initial handshaking message: " + serializedHandShakingMessage);
        instance.sendMulticastMessage(serializedHandShakingMessage);
    }

    public void processInComingMessage(String message, String fromIP) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                if (instance.isHandShakingMessage(message)) {
                    instance.processInComingHandShakingMessage(message);
                } else if (instance.isSyncRequestMessage(message)) {
                    List<String> syncInfoMessages = instance.processInComingSyncRequestMessage(message);
                    instance.sendMessages(syncInfoMessages);
                } else if (instance.isSyncInfoMessage(message)) {
                    instance.processInComingSyncInfoMessage(message, fromIP);
                }
            }
        });

    }


    public void addNewMessage(String message) {
        dbSyncManager.addMessage(P2PContext.getLoggedInUser(), null, "Chat", message);
    }

    public void processInComingHandShakingMessage(String message) {

        Log.d(TAG, "processInComingHandShakingMessage: " + message);
        notifyUI("handshaking message received", " ------> ", LOG_TYPE);
        //parse message and add to all messages
        HandShakingMessage handShakingMessage = instance.parseHandShakingMessage(message);
        boolean shouldSendAck = shouldSendAckForHandShakingMessage(handShakingMessage);

        // send handshaking information if message received "from" first time
        if (shouldSendAck) {
            Log.d(TAG, "replying back with initial hand shaking message with needAck => false");
            notifyUI("handshaking message sent with ack false", " ------> ", LOG_TYPE);
            sendInitialHandShakingMessage(false);
        }

        synchronized (MulticastManager.class) {
            if (waitForHandShakingMessagesTimer == null) {
                Log.d(TAG, "waitForHandShakingMessagesTimer => created to process Incoming handshaking requests");
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        waitForHandShakingMessagesTimer = new CountDownTimer(WAIT_FOR_HAND_SHAKING_MESSAGES, 1000) {
                            public void onTick(long millisUntilFinished) {
                            }

                            public void onFinish() {
                                Log.d(TAG, "waitForHandShakingMessagesTimer finished ... processing sync information ...");
                                instance.generateSyncInfoPullRequest(instance.getAllHandShakeMessagesInCurrentLoop());
                                if (waitForHandShakingMessagesTimer != null) {
                                    waitForHandShakingMessagesTimer.cancel();
                                    Log.d(TAG, "waitForHandShakingMessagesTimer => reset to cancelled");
                                    waitForHandShakingMessagesTimer = null;
                                }
                            }
                        }.start();
                    }
                });

            } else {
                Log.d(TAG, "waitForHandShakingMessagesTimer => already started ...");
            }
        }
    }

    public List<String> generateSyncInfoPullRequest(final Map<String, HandShakingMessage> messages) {
        List<String> jsons = new ArrayList<String>();
        final Collection<HandShakingInfo> pullSyncInfo = instance.computeSyncInfoRequired(messages);
        Log.d(TAG, "generateSyncInfoPullRequest -> computeSyncInfoRequired ->" + pullSyncInfo.size());
        notifyUI("generateSyncInfoPullRequest -> computeSyncInfoRequired ->" + pullSyncInfo.size(), " ------> ", LOG_TYPE);
        if (pullSyncInfo != null) {
            jsons = p2PDBApiImpl.serializeSyncRequestMessages(pullSyncInfo);
            instance.sendMessages(jsons);
        }
        return jsons;
    }

    private MessageStatus validIncomingSyncMessage(P2PSyncInfo info, MessageStatus status) {
        // DON'T reject out of order message, send handshaking request for only missing data
        // reject duplicate messages if any
        boolean isValid = true;
        String iKey = info.getDeviceId() + "_" + info.getUserId() + "_" + Long.valueOf(info.getSequence().longValue());
        String iPreviousKey = info.getDeviceId() + "_" + info.getUserId() + "_" + Long.valueOf(info.getSequence().longValue() - 1);
        Log.d(TAG, "validIncomingSyncMessage previousKey" + iPreviousKey);
        // remove duplicates
        if (allSyncInfosReceived.contains(iKey)) {
            Log.d(TAG, "sync data message as key already found" + iKey);
            status.setDuplicateMessage(true);
            status.setOutOfSyncMessage(false);
            isValid = false;
        } else if (info.getSequence().longValue() != 1
                && !allSyncInfosReceived.contains(iPreviousKey)) {
            Log.d(TAG, "found sync data message as out of sequence => previous key not found " + iPreviousKey + " for key:" + iKey);
            isValid = false;
            status.setDuplicateMessage(false);
            status.setOutOfSyncMessage(true);
        }

        if (isValid) {
            Log.d(TAG, "validIncomingSyncMessage adding to allSyncInfosReceived for key:" + iKey);
            allSyncInfosReceived.add(iKey);
        }

        return status;
    }

    public void processInComingSyncInfoMessage(String message, String fromIP) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "processInComingSyncInfoMessage -> " + message + " fromIP -> " + fromIP);
                Iterator<P2PSyncInfo> infos = p2PDBApiImpl.deSerializeP2PSyncInfoFromJson(message).iterator();
                while (infos.hasNext()) {
                    P2PSyncInfo info = infos.next();
                    MessageStatus status = new MessageStatus(false, false);
                    status = instance.validIncomingSyncMessage(info, status);
                    if (status.isDuplicateMessage()) {
                        notifyUI(info.message + " ---------> duplicate - rejected ", info.getSender(), LOG_TYPE);
                        infos.remove();
                    } else if (status.isOutOfSyncMessage()) {
                        notifyUI(info.message + " with sequence " + info.getSequence() + " ---------> out of sync processed with filling Missing type message ", info.getSender(), LOG_TYPE);
                        String key = info.getDeviceId() + "_" + info.getUserId() + "_" + Long.valueOf(info.getSequence().longValue());
                        Log.d(TAG, "processing out of sync data message for key:" + key + " and message:" + info.message);
                        String rMessage = p2PDBApiImpl.persistOutOfSyncP2PSyncMessage(info);
                        // generate handshaking request
                        if (status.isOutOfSyncMessage()) {
                            Log.d(TAG, "validIncomingSyncMessage -> out of order -> sendInitialHandShakingMessage");
                            sendInitialHandShakingMessage(true);
                        }
                    } else if (!status.isOutOfSyncMessage() && !status.isDuplicateMessage()) {
                        String key = info.getDeviceId() + "_" + info.getUserId() + "_" + Long.valueOf(info.getSequence().longValue());
                        Log.d(TAG, "processing sync data message for key:" + key + " and message:" + info.message);
                        String rMessage = p2PDBApiImpl.persistP2PSyncInfo(info);
                    } else {
                        infos.remove();
                    }
                }
            }
        });

    }

    public List<String> processInComingSyncRequestMessage(String message) {
        Log.d(TAG, "processInComingSyncRequestMessage => " + message);
        List<String> jsonRequests = new CopyOnWriteArrayList<String>();
        SyncInfoRequestMessage request = p2PDBApiImpl.buildSyncRequstMessage(message);
        // process only if matching current device id
        if (request != null && request.getmDeviceId().equalsIgnoreCase(P2PContext.getCurrentDevice())) {
            Log.d(TAG, "processInComingSyncRequestMessage => device id matches with: " + P2PContext.getCurrentDevice());
            notifyUI("sync request message received", " ------> ", LOG_TYPE);
            List<SyncInfoItem> items = request.getItems();
            for (SyncInfoItem a : items) {
                Log.d(TAG, "processInComingSyncRequestMessage => adding to jsonRequest for sync messages");
                jsonRequests.addAll(p2PDBApiImpl.fetchP2PSyncInfoBySyncRequest(a));
            }
        }

        return jsonRequests;
    }


    private Set<HandShakingInfo> sortHandShakingInfos(final Map<String, HandShakingMessage> messages) {
        final Set<HandShakingInfo> allHandShakingInfos = new TreeSet<HandShakingInfo>(new Comparator<HandShakingInfo>() {
            @Override
            public int compare(HandShakingInfo o1, HandShakingInfo o2) {
                if (o1.getDeviceId().equalsIgnoreCase(o2.getDeviceId())) {
                    if (o1.getSequence().longValue() > o2.getSequence().longValue()) {
                        return 1;
                    } else {
                        return -1;
                    }
                }
                return o1.getDeviceId().compareToIgnoreCase(o2.getDeviceId());
            }
        });

        Iterator<Map.Entry<String, HandShakingMessage>> entries = messages.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, HandShakingMessage> entry = entries.next();
            Iterator<HandShakingInfo> it = entry.getValue().getInfos().iterator();
            while (it.hasNext()) {
                HandShakingInfo i = it.next();
                i.setFrom(entry.getKey());
            }

            allHandShakingInfos.addAll(entry.getValue().getInfos());
        }
        return allHandShakingInfos;
    }


    private Collection<HandShakingInfo> computeSyncInfoRequired(final Map<String, HandShakingMessage> messages) {
        // sort by device id and sequence desc order
        synchronized (MulticastManager.class) {
            final Set<HandShakingInfo> allHandShakingInfos = sortHandShakingInfos(messages);
            Iterator<HandShakingInfo> itReceived = allHandShakingInfos.iterator();
            final Map<String, HandShakingInfo> uniqueHandShakeInfosReceived = new ConcurrentHashMap<String, HandShakingInfo>();
            while (itReceived.hasNext()) {
                HandShakingInfo info = itReceived.next();
                HandShakingInfo existingInfo = uniqueHandShakeInfosReceived.get(info.getUserId());
                if (existingInfo == null) {
                    uniqueHandShakeInfosReceived.put(info.getUserId(), info);
                } else {
                    if (existingInfo.getSequence().longValue() < info.getSequence().longValue()) {
                        uniqueHandShakeInfosReceived.put(info.getUserId(), info);
                    } else if (existingInfo.getSequence().longValue() == info.getSequence().longValue()) {

                        String myMissingMessageSequences = existingInfo.getMissingMessages();
                        String otherDeviceMissingMessageSequences = info.getMissingMessages();
                        List<String> list1 = new ArrayList<String>();
                        List<String> list2 = new ArrayList<String>();
                        if (myMissingMessageSequences != null) {
                            list1 = Lists.newArrayList(Splitter.on(",").split(myMissingMessageSequences));
                        }
                        if (otherDeviceMissingMessageSequences != null) {
                            list2 = Lists.newArrayList(Splitter.on(",").split(otherDeviceMissingMessageSequences));
                        }
                        if (list1.size() > list2.size()) {
                            uniqueHandShakeInfosReceived.put(info.getUserId(), info);
                        }
                    }
                }
            }

            final Map<String, HandShakingInfo> myHandShakingMessages = p2PDBApiImpl.handShakingInformationFromCurrentDevice();

            Iterator<String> keys = uniqueHandShakeInfosReceived.keySet().iterator();
            while (keys.hasNext()) {
                String userKey = keys.next();
                Log.d(TAG, "computeSyncInfoRequired user key:" + userKey);
                if (myHandShakingMessages.keySet().contains(userKey)) {
                    HandShakingInfo infoFromOtherDevice = uniqueHandShakeInfosReceived.get(userKey);
                    HandShakingInfo infoFromMyDevice = myHandShakingMessages.get(userKey);
                    if (infoFromMyDevice.getSequence().longValue() > infoFromOtherDevice.getSequence().longValue()) {
                        Log.d(TAG, "removing from uniqueHandShakeInfosReceived for key:" + userKey + " as infoFromMyDevice.getSequence()" + infoFromMyDevice.getSequence() + " infoFromOtherDevice.getSequence()" + infoFromOtherDevice.getSequence());
                        uniqueHandShakeInfosReceived.remove(userKey);
                    } else if (infoFromMyDevice.getSequence().longValue() == infoFromOtherDevice.getSequence().longValue()) {
                        //check for missing keys, if the same then remove otherwise only add missing key for infoFromMyDevice
                        String myMissingMessageSequences = infoFromMyDevice.getMissingMessages();
                        String otherDeviceMissingMessageSequences = infoFromOtherDevice.getMissingMessages();
                        List<String> list1 = new ArrayList<String>();
                        List<String> list2 = new ArrayList<String>();
                        if (myMissingMessageSequences != null) {
                            list1 = Lists.newArrayList(Splitter.on(",").split(myMissingMessageSequences));
                        }
                        if (otherDeviceMissingMessageSequences != null) {
                            list2 = Lists.newArrayList(Splitter.on(",").split(otherDeviceMissingMessageSequences));
                        }
                        List<String> missingSequencesToAsk = new ArrayList<>(CollectionUtils.subtract(list1, list2));
                        if (missingSequencesToAsk != null && missingSequencesToAsk.size() > 0) {
                            infoFromOtherDevice.setMissingMessages(StringUtils.join(missingSequencesToAsk, ","));
                            infoFromOtherDevice.setStartingSequence(infoFromOtherDevice.getSequence() + 1);
                        } else {
                            Log.d(TAG, "removing from uniqueHandShakeInfosReceived for key:" + userKey + " as infoFromMyDevice.getSequence()" + infoFromMyDevice.getSequence() + " infoFromOtherDevice.getSequence()" + infoFromOtherDevice.getSequence());
                            uniqueHandShakeInfosReceived.remove(userKey);
                        }
                    } else {
                        Log.d(TAG, "uniqueHandShakeInfosReceived for key:" + userKey + " as infoFromOtherDevice.setStartingSequence" + infoFromMyDevice.getSequence().longValue());
                        // take other device's missing keys remove
                        // take my missing keys and remove if the same as other device's missing keys
                        // ask for all messages my sequence + 1
                        // ask for all my missing keys messages also

                        String myMissingMessageSequences = infoFromMyDevice.getMissingMessages();
                        String otherDeviceMissingMessageSequences = infoFromOtherDevice.getMissingMessages();
                        List<String> list1 = new ArrayList<String>();
                        List<String> list2 = new ArrayList<String>();
                        if (myMissingMessageSequences != null) {
                            list1 = Lists.newArrayList(Splitter.on(",").split(myMissingMessageSequences));
                        }
                        if (otherDeviceMissingMessageSequences != null) {
                            list2 = Lists.newArrayList(Splitter.on(",").split(otherDeviceMissingMessageSequences));
                        }
                        List<String> missingSequencesToAsk = new ArrayList<>(CollectionUtils.subtract(list1, list2));
                        if (missingSequencesToAsk != null && missingSequencesToAsk.size() > 0) {
                            infoFromOtherDevice.setMissingMessages(StringUtils.join(missingSequencesToAsk, ","));
                        }
                        infoFromOtherDevice.setStartingSequence(infoFromMyDevice.getSequence().longValue() + 1);
                    }
                }
            }


            List<HandShakingInfo> valuesToSend = new ArrayList<HandShakingInfo>();

            Collection<HandShakingInfo> values = uniqueHandShakeInfosReceived.values();
            Iterator itValues = values.iterator();
            while (itValues.hasNext()) {
                HandShakingInfo t = (HandShakingInfo) itValues.next();
                Log.d(TAG, "validating : " + t.getUserId() + " " + t.getDeviceId() + " " + t.getStartingSequence() + " " + t.getSequence());

                if (t.getMissingMessages() != null && t.getMissingMessages().length() > 0) {

                    List<String> missingMessages = Lists.newArrayList(Splitter.on(",").split(t.getMissingMessages()));
                    for (String m : missingMessages) {
                        HandShakingInfo n = new HandShakingInfo(t.getUserId(), t.getDeviceId(), t.getSequence(), null);
                        n.setFrom(t.getFrom());
                        n.setStartingSequence(Long.valueOf(m));
                        n.setSequence(Long.valueOf(m));
                        valuesToSend.add(n);
                    }
                }


                if (t.getStartingSequence() == null) {
                    t.setMissingMessages(null);
                    valuesToSend.add(t);
                } else if (t.getStartingSequence() != null && t.getStartingSequence().longValue() <= t.getSequence().longValue()) {
                    t.setMissingMessages(null);
                    valuesToSend.add(t);
                }
            }
            return valuesToSend;
        }
    }

    /*
        only for testing
     */
    public List<String> computeSyncInformation() {
        List<String> computedMessages = new CopyOnWriteArrayList<String>();

        final Map<String, HandShakingMessage> messages = Collections.unmodifiableMap(handShakingMessagesInCurrentLoop);

        final Set<HandShakingInfo> allHandShakingInfos = new TreeSet<HandShakingInfo>(new Comparator<HandShakingInfo>() {
            @Override
            public int compare(HandShakingInfo o1, HandShakingInfo o2) {
                if (o1.getDeviceId().equalsIgnoreCase(o2.getDeviceId())) {
                    if (o1.getSequence().longValue() > o2.getSequence().longValue()) {
                        return 1;
                    } else {
                        return -1;
                    }
                }
                return 1;
            }
        });

        Iterator<Map.Entry<String, HandShakingMessage>> entries = messages.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, HandShakingMessage> entry = entries.next();
            Log.i(TAG, "processing message for: " + entry.getKey());
            allHandShakingInfos.addAll(entry.getValue().getInfos());
        }

        Iterator<HandShakingInfo> itR = allHandShakingInfos.iterator();
        long minSequenceForSameDeviceId = -1;
        while (itR.hasNext()) {
            HandShakingInfo info = itR.next();
            if (info.getDeviceId().equalsIgnoreCase(P2PContext.getCurrentDevice())) {
                long curSequence = info.getSequence().longValue();
                if (minSequenceForSameDeviceId == -1) {
                    minSequenceForSameDeviceId = curSequence;
                }

                if (curSequence > minSequenceForSameDeviceId) {
                    itR.remove();
                }

            }
        }

        List<P2PSyncInfo> allSyncInfos = p2PDBApiImpl.buildSyncInformation(new CopyOnWriteArrayList(allHandShakingInfos));
        Iterator<P2PSyncInfo> it = allSyncInfos.iterator();
        while (it.hasNext()) {
            P2PSyncInfo p = it.next();
            String syncMessage = p2PDBApiImpl.convertSingleP2PSyncInfoToJsonUsingStreaming(p);
            computedMessages.add(syncMessage);
        }
        return computedMessages;
    }

    private void sendMessages(List<String> computedMessages) {
        if (computedMessages != null && computedMessages.size() > 0) {
            Iterator<String> it = computedMessages.iterator();
            while (it.hasNext()) {
                String p = it.next();
                instance.sendMulticastMessage(p);
            }
        }
    }

    private boolean shouldSendAckForHandShakingMessage(HandShakingMessage handShakingMessage) {
        boolean sendAck = handShakingMessage.getReply().equalsIgnoreCase("true");
        Log.d(TAG, "shouldSendAckForHandShaking: " + handShakingMessage.getFrom() + " sendAck:" + sendAck);
        return sendAck;
    }


    public HandShakingMessage parseHandShakingMessage(String message) {
        HandShakingMessage handShakingMessage = p2PDBApiImpl.deSerializeHandShakingInformationFromJson(message);
        if (handShakingMessage != null) {
            Log.d(TAG, "storing handShakingMessage from : " + handShakingMessage.getFrom() + " in handShakingMessagesInCurrentLoop");
            instance.handShakingMessagesInCurrentLoop.put(handShakingMessage.getFrom(), handShakingMessage);
        }
        return handShakingMessage;
    }

    public Set<String> getAllSyncInfosReceived() {
        return allSyncInfosReceived;
    }

    public void sendFindBuddyMessage() {
        instance.sendInitialHandShakingMessage(true);
    }

    public Map<String, HandShakingMessage> getAllHandShakeMessagesInCurrentLoop() {
        synchronized (MulticastManager.class) {
            Map<String, HandShakingMessage> messagesTillNow = Collections.unmodifiableMap(handShakingMessagesInCurrentLoop);
            CollectionUtils.subtract(handShakingMessagesInCurrentLoop.keySet(), messagesTillNow.keySet());
            return messagesTillNow;
        }
    }
}
