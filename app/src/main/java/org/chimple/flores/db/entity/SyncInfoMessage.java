package org.chimple.flores.db.entity;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public class SyncInfoMessage {
    @Expose(serialize = true, deserialize = true)
    @SerializedName("message_type")
    String messageType;


    public String getMessageType() {
        return messageType;
    }

    public List<P2PSyncInfo> getInfos() {
        return infos;
    }

    @Expose(serialize = true, deserialize = true)
    @SerializedName("infos")
    List<P2PSyncInfo> infos;

    public SyncInfoMessage(String messageType, List<P2PSyncInfo> infos) {
        this.messageType = messageType;
        this.infos = infos;
    }


    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        final SyncInfoMessage info = (SyncInfoMessage) obj;
        if (this == info) {
            return true;
        } else {
            return (this.messageType.equals(info.messageType) && this.infos == info.infos);
        }
    }


    public int hashCode() {
        int hashno = 7;
        hashno = 13 * hashno + (messageType == null ? 0 : messageType.hashCode()) + (infos == null ? 0 : infos.hashCode());
        return hashno;
    }
}