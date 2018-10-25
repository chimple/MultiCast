package org.chimple.flores.db.entity;

import android.arch.persistence.room.util.StringUtil;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Type;

import static org.chimple.flores.db.AppDatabase.SYNC_NUMBER_OF_LAST_MESSAGES;

public class HandShakingInfoDeserializer implements JsonDeserializer<HandShakingInfo> {

    public HandShakingInfo deserialize(final JsonElement json, final Type typeOfT, final JsonDeserializationContext context)
            throws JsonParseException {

        final JsonObject jsonObject = json.getAsJsonObject();
        final JsonElement jsonUserId = jsonObject.get("userId");

        String userId = null;
        if (jsonUserId != null) {
            userId = jsonUserId.getAsString();
        }

        String deviceId = null;
        final JsonElement jsonDeviceId = jsonObject.get("deviceId");
        if (jsonDeviceId != null) {
            deviceId = jsonDeviceId.getAsString();
        }

        Long sequence = 0L;
        final JsonElement jsonSequence = jsonObject.get("sequence");
        if (jsonSequence != null) {
            sequence = jsonSequence.getAsLong();
        }

        StringBuffer missingMessages = new StringBuffer();
        String result = null;
        final JsonElement jsonMissingMessages = jsonObject.get("missingMessages");
        if (jsonMissingMessages != null) {
            String missingMessages1 = jsonMissingMessages.getAsString();
            char[] bits = missingMessages1.toCharArray();

//            for (int i = bits.length - 1; i > -1; i--) {
//                char b = bits[i];
//                if(b == '0') {
//                    if(sequence.intValue() > SYNC_NUMBER_OF_LAST_MESSAGES) {
//                        missingMessages.append(sequence.intValue() - SYNC_NUMBER_OF_LAST_MESSAGES + i);
//                    } else {
//                        missingMessages.append(sequence.intValue() -  i);
//                    }
//                    missingMessages.append(",");
//                }
//            }

            for (int i = 0; i < bits.length; i++) {
                char b = bits[i];
                if(b == '0' && i < sequence) {
                    if(sequence.intValue() > SYNC_NUMBER_OF_LAST_MESSAGES) {
                        missingMessages.append(sequence.intValue() + 1 - SYNC_NUMBER_OF_LAST_MESSAGES + i);
                    } else {
                        missingMessages.append(i+1);
                    }
                    missingMessages.append(",");
                }
            }
            if(missingMessages.length() > 0) {
                missingMessages.setLength(missingMessages.length() - 1);
            }
            result = missingMessages.toString();
        }

        final HandShakingInfo handShakingInfo = new HandShakingInfo(userId, deviceId, sequence, result);
        return handShakingInfo;
    }
}