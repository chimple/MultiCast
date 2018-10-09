package org.chimple.flores.multicast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.CountDownTimer;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.Predicate;
import org.chimple.flores.application.NetworkUtil;
import org.chimple.flores.application.P2PApplication;
import org.chimple.flores.db.DBSyncManager;
import org.chimple.flores.db.P2PDBApiImpl;
import org.chimple.flores.db.entity.HandShakingInfo;
import org.chimple.flores.db.entity.HandShakingMessage;
import org.chimple.flores.db.entity.P2PSyncInfo;
import org.chimple.flores.db.entity.SyncInfoItem;
import org.chimple.flores.db.entity.SyncInfoMessage;
import org.chimple.flores.db.entity.SyncInfoRequestMessage;

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

import static org.chimple.flores.application.P2PApplication.MULTICAST_IP_ADDRESS;
import static org.chimple.flores.application.P2PApplication.MULTICAST_IP_PORT;
import static org.chimple.flores.application.P2PApplication.NEW_MESSAGE_ADDED;
import static org.chimple.flores.application.P2PApplication.uiMessageEvent;

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
    private static final int WAIT_FOR_HAND_SHAKING_MESSAGES = 5 * 1000; // 5 sec


    public static MulticastManager getInstance(Context context) {
        if (instance == null) {
            synchronized (MulticastManager.class) {
                instance = new MulticastManager(context);
                instance.setMulticastIpAddress(MULTICAST_IP_ADDRESS);
                instance.setMulticastPort(MULTICAST_IP_PORT);
                instance.registerMulticastBroadcasts();
                instance.dbSyncManager = DBSyncManager.getInstance(context);
                instance.p2PDBApiImpl = P2PDBApiImpl.getInstance(context);
            }

        }
        return instance;
    }

    private MulticastManager(Context context) {
        this.context = context;
    }

    public void onCleanUp() {
        if (waitForHandShakingMessagesTimer != null) {
            waitForHandShakingMessagesTimer.cancel();
            waitForHandShakingMessagesTimer = null;
        }
        stopListening();
        stopThreads();
        instance.unregisterMulticastBroadcasts();
        instance = null;
    }

    public void startListening() {
        if (!isListening) {
            int status = NetworkUtil.getConnectivityStatusString(this.context);
            if (status != NetworkUtil.NETWORK_STATUS_NOT_CONNECTED) {
                setWifiLockAcquired(true);
                this.multicastListenerThread = new MulticastListenerThread(this.context, getMulticastIP(), getMulticastPort());
                multicastListenerThread.start();
                isListening = true;
            }
        }
    }

    public boolean isListening() {
        return isListening;
    }

    public void stopListening() {
        if (isListening) {
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
        if (this.multicastListenerThread != null)
            this.multicastListenerThread.stopRunning();
        if (this.multicastSenderThread != null)
            this.multicastSenderThread.interrupt();
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

        if (mMessageReceiver != null) {
            LocalBroadcastManager.getInstance(this.context).unregisterReceiver(mMessageReceiver);
            mMessageReceiver = null;
        }

        if (newMessageAddedReceiver != null) {
            LocalBroadcastManager.getInstance(this.context).unregisterReceiver(newMessageAddedReceiver);
            newMessageAddedReceiver = null;
        }

    }

    private void registerMulticastBroadcasts() {
        LocalBroadcastManager.getInstance(this.context).registerReceiver(netWorkChangerReceiver, new IntentFilter(multiCastConnectionChangedEvent));
        LocalBroadcastManager.getInstance(this.context).registerReceiver(mMessageReceiver, new IntentFilter(P2PApplication.messageEvent));
        LocalBroadcastManager.getInstance(this.context).registerReceiver(newMessageAddedReceiver, new IntentFilter(P2PApplication.newMessageAddedOnDevice));
    }


    private void stopMultiCastOperations() {
        instance.stopListening();
    }

    private void startMultiCastOperations() {
        instance.startListening();
        instance.sendFindBuddyMessage();
    }


    private BroadcastReceiver netWorkChangerReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            boolean isConnected = intent.getBooleanExtra("isConnected", false);
            if (!isConnected) {
                instance.stopMultiCastOperations();
            } else {
                instance.startMultiCastOperations();
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

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            String message = intent.getStringExtra("message");
            String fromIP = intent.getStringExtra("fromIP");
            boolean isLoopBack = intent.getBooleanExtra("loopback", false);
            if (!isLoopBack) {
                synchronized (MulticastManager.class) {
                    processInComingMessage(message, fromIP);
                }
            }
        }
    };


    public void notifyUI(String message, String fromIP) {

        final String consoleMessage = "[" + fromIP + "]: " + message + "\n";
        Log.d(TAG, "got message: " + consoleMessage);
        Intent intent = new Intent(uiMessageEvent);
        intent.putExtra("message", consoleMessage);
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
        return message != null && message.contains(messageType) ? true : false;
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
        if (instance.isHandShakingMessage(message)) {
            instance.processInComingHandShakingMessage(message);
        } else if (instance.isSyncRequestMessage(message)) {
            List<String> syncInfoMessages = instance.processInComingSyncRequestMessage(message);
            instance.sendMessages(syncInfoMessages);
        } else if (instance.isSyncInfoMessage(message)) {
            instance.processInComingSyncInfoMessage(message, fromIP);
        }
    }


    public void addNewMessage(String message) {
        dbSyncManager.addMessage(P2PApplication.getLoggedInUser(), null, "Chat", message);
    }

    public void processInComingHandShakingMessage(String message) {

        Log.d(TAG, "processInComingHandShakingMessage: " + message);
        //parse message and add to all messages
        HandShakingMessage handShakingMessage = instance.parseHandShakingMessage(message);
        boolean shouldSendAck = shouldSendAckForHandShakingMessage(handShakingMessage);

        // send handshaking information if message received "from" first time
        if (shouldSendAck) {
            Log.d(TAG, "replying back with initial hand shaking message");
            sendInitialHandShakingMessage(false);
        }

        waitForHandShakingMessagesTimer = new CountDownTimer(WAIT_FOR_HAND_SHAKING_MESSAGES, 1000) {
            public void onTick(long millisUntilFinished) {
            }

            public void onFinish() {
                Log.d(TAG, "waitForHandShakingMessagesTimer finished ... processing sync information ...");
                instance.generateSyncInfoPullRequest(instance.getAllHandShakeMessagesInCurrentLoop());
            }
        }.start();

    }

    public List<String> generateSyncInfoPullRequest(final Map<String, HandShakingMessage> messages) {
        final Collection<HandShakingInfo> pullSyncInfo = instance.computeSyncInfoRequired(messages);
        List<String> jsons = p2PDBApiImpl.serializeSyncRequestMessages(pullSyncInfo);
        instance.sendMessages(jsons);
        return jsons;
    }

    private boolean validIncomingSyncMessage(P2PSyncInfo info) {
        // reject out of order message, send handshaking request
        // reject duplicate messages if any

        boolean isValid = true;
        String iKey = info.getDeviceId() + "_" + info.getUserId() + "_" + Long.valueOf(info.getSequence().longValue());
        String iPreviousKey = info.getDeviceId() + "_" + info.getUserId() + "_" + Long.valueOf(info.getSequence().longValue() - 1);

        // remove duplicates
        if (allSyncInfosReceived.contains(iKey)) {
            Log.d(TAG, "Rejecting sync data message as key already found" + iKey);
            isValid = false;
        }

        // process out of sync
        if (info.getSequence().longValue() != 1
                && !allSyncInfosReceived.contains(iPreviousKey)) {
            Log.d(TAG, "Rejecting sync data message as out of sequence => previous key not found " + iPreviousKey + " for key:" + iKey);
            isValid = false;
            // generate handshaking request
            sendInitialHandShakingMessage(true);
        }
        return isValid;
    }

    public void processInComingSyncInfoMessage(String message, String fromIP) {
        Iterator<P2PSyncInfo> infos = p2PDBApiImpl.deSerializeP2PSyncInfoFromJson(message).iterator();
        while (infos.hasNext()) {
            P2PSyncInfo info = infos.next();
            boolean isValidMessage = instance.validIncomingSyncMessage(info);
            if (!isValidMessage) {
                infos.remove();
                return;
            }
            String key = info.getDeviceId() + "_" + info.getUserId() + "_" + Long.valueOf(info.getSequence().longValue());
            Log.d(TAG, "processing sync data message for key:" + key);
            String rMessage = p2PDBApiImpl.persistP2PSyncInfo(info);
            if(rMessage != null) {
                allSyncInfosReceived.add(info.getDeviceId() + "_" + info.getUserId() + "_" + Long.valueOf(info.getSequence().longValue()));
                Log.d(TAG, "notifying UI for data message for key:" + key + " with message:" + rMessage);
                instance.notifyUI(rMessage, fromIP);
            }
        }
    }

    public List<String> processInComingSyncRequestMessage(String message) {
        List<String> jsonRequests = new CopyOnWriteArrayList<String>();
        SyncInfoRequestMessage request = p2PDBApiImpl.buildSyncRequstMessage(message);
        // process only if matching current device id
        if (request != null && request.getmDeviceId().equalsIgnoreCase(P2PApplication.getCurrentDevice())) {
            List<SyncInfoItem> items = request.getItems();
            for (SyncInfoItem a : items) {
                jsonRequests.addAll(p2PDBApiImpl.fetchP2PSyncInfoBySyncRequest(a));
            }
        }

        return jsonRequests;
    }

    private void reset() {
        instance.handShakingMessagesInCurrentLoop = null;
        instance.handShakingMessagesInCurrentLoop = new ConcurrentHashMap<String, HandShakingMessage>();
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
        instance.reset();
        // sort by device id and sequence desc order
        final Set<HandShakingInfo> allHandShakingInfos = sortHandShakingInfos(messages);
        Iterator<HandShakingInfo> itReceived = allHandShakingInfos.iterator();
        final Map<String, HandShakingInfo> uniqueHandShakeInfosReceived = new ConcurrentHashMap<String, HandShakingInfo>();
        while (itReceived.hasNext()) {
            HandShakingInfo info = itReceived.next();
            uniqueHandShakeInfosReceived.put(info.getUserId(), info);
        }

        final Map<String, HandShakingInfo> myHandShakingMessages = p2PDBApiImpl.handShakingInformationFromCurrentDevice();

        Iterator<String> keys = uniqueHandShakeInfosReceived.keySet().iterator();
        while (keys.hasNext()) {
            String userKey = keys.next();
            if (myHandShakingMessages.keySet().contains(userKey)) {
                HandShakingInfo infoFromOtherDevice = uniqueHandShakeInfosReceived.get(userKey);
                HandShakingInfo infoFromMyDevice = myHandShakingMessages.get(userKey);
                if (infoFromMyDevice.getSequence() > infoFromOtherDevice.getSequence()) {
                    uniqueHandShakeInfosReceived.remove(userKey);
                } else {
                    infoFromOtherDevice.setStartingSequence(infoFromMyDevice.getSequence().longValue() + 1);
                }
            }
        }
        return uniqueHandShakeInfosReceived.values();
    }

    public List<String> computeSyncInformation() {
        List<String> computedMessages = new CopyOnWriteArrayList<String>();

        final Map<String, HandShakingMessage> messages = Collections.unmodifiableMap(handShakingMessagesInCurrentLoop);
        instance.reset();

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
            if (info.getDeviceId().equalsIgnoreCase(P2PApplication.getCurrentDevice())) {
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
        Iterator<String> it = computedMessages.iterator();
        while (it.hasNext()) {
            String p = it.next();
            instance.sendMulticastMessage(p);
        }
    }

    private boolean shouldSendAckForHandShakingMessage(HandShakingMessage handShakingMessage) {
        boolean sendAck = handShakingMessage.getReply().equalsIgnoreCase("true");
        Log.d(TAG, "shouldSendAckForHandShaking: " + handShakingMessage.getFrom() + " sendAck:" + sendAck);
        return sendAck;
    }


    public HandShakingMessage parseHandShakingMessage(String message) {
        boolean repliedToHandShakingMessage = false;
        HandShakingMessage handShakingMessage = p2PDBApiImpl.deSerializeHandShakingInformationFromJson(message);

        if (handShakingMessage != null) {
            instance.handShakingMessagesInCurrentLoop.put(handShakingMessage.getFrom(), handShakingMessage);

//            if (handShakingMessage.getInfos() != null) {
//                Iterator<HandShakingInfo> infos = handShakingMessage.getInfos().iterator();
//                while (infos.hasNext()) {
//                    HandShakingInfo h = (HandShakingInfo) infos.next();
//                    instance.allHandShakingMessagesCached.put(handShakingMessage.getFrom(), h.getSequence());
//                }
//            }
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
        return Collections.unmodifiableMap(handShakingMessagesInCurrentLoop);
    }

}