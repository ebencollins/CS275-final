package edu.uvm.cs275.conversationanalysis;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import cz.msebera.android.httpclient.Header;
import edu.uvm.cs275.conversationanalysis.api.ConversationAPIClient;
import edu.uvm.cs275.conversationanalysis.db.ConversationBaseHelper;
import edu.uvm.cs275.conversationanalysis.db.ConversationCursorWrapper;
import edu.uvm.cs275.conversationanalysis.db.ConversationSchema.ConversationTable;

public class ConversationManager {
    private static final String TAG = "ConversationManager";
    private static final String IMAGE_DIR_NAME = "images";
    public static final String IMAGE_EXT = ".png";
    private static final String PREFS_NAME = "CONVERSATION_PREFS";
    private static final String DEVICE_UUID = "DEVICE_UUID";

    private static ConversationManager sInstance;
    private Context mContext;
    private SQLiteDatabase mDatabase;
    private UUID mDeviceUUID;

    private ConversationManager(Context context) {
        mContext = context.getApplicationContext();
        mDatabase = new ConversationBaseHelper(mContext).getWritableDatabase();

        SharedPreferences settings = context.getSharedPreferences(PREFS_NAME, 0);
        if (settings.getString(DEVICE_UUID, null) == null) {
            SharedPreferences.Editor editor = settings.edit();
            editor.putString(DEVICE_UUID, UUID.randomUUID().toString());
            editor.apply();
        }
        mDeviceUUID = UUID.fromString(settings.getString(DEVICE_UUID, null));
    }

    public static ConversationManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ConversationManager(context);
        }
        return sInstance;
    }

    private static ContentValues getContentValues(Conversation c) {
        ContentValues values = new ContentValues();
        values.put(ConversationTable.Cols.UUID, c.getUUID().toString());
        values.put(ConversationTable.Cols.DATE, c.getDate().getTime());
        return values;
    }

    public void addConversation(Conversation c) {
        ContentValues values = getContentValues(c);
        mDatabase.insert(ConversationTable.NAME, null, values);
    }

    public void updateConversation(Conversation c) {
        String uuidString = c.getUUID().toString();
        ContentValues values = getContentValues(c);
        mDatabase.update(ConversationTable.NAME, values, ConversationTable.Cols.UUID + "=?", new String[]{uuidString});
    }

    private ConversationCursorWrapper queryConversations(String whereClause, String[] whereArgs) {
        return new ConversationCursorWrapper(mDatabase.query(
                ConversationTable.NAME,
                null,
                whereClause,
                whereArgs,
                null,
                null,
                null
        ));
    }

    public List<Conversation> getConversations() {
        List<Conversation> conversations = new ArrayList<>();

        try (ConversationCursorWrapper cursor = queryConversations(null, null)) {
            cursor.moveToFirst();
            while (!cursor.isAfterLast()) {
                conversations.add(cursor.getConversation());
                cursor.moveToNext();
            }
        }
        return conversations;
    }

    public Conversation getConversation(UUID uuid) {
        try (ConversationCursorWrapper cursor = queryConversations(
                ConversationTable.Cols.UUID + "=?",
                new String[]{uuid.toString()}
        )) {
            if (cursor.getCount() == 0) {
                return null;
            }
            cursor.moveToFirst();
            return cursor.getConversation();
        }
    }

    public Path getImageDir() {
        return mContext.getFilesDir().toPath().resolve(IMAGE_DIR_NAME);
    }

    // returns true if conversation has been successfully uploaded, either now, or in the past
    public boolean uploadConversation(final Conversation conversation) {
        if (conversation.isUploaded()) {
            return true;
        }

        // set params
        File gammatone = conversation.getImageFile(mContext).toFile();
        RequestParams params = new RequestParams();
        params.put("device", mDeviceUUID.toString());
        params.put("uuid", conversation.getUUID().toString());

        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ"); // Quoted "Z" to indicate UTC, no timezone offset
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        params.put("date", df.format(conversation.getDate()));
        try {
            params.put("gammatone", gammatone);
        } catch (FileNotFoundException e) {
            return false;
        }

        // make request and handle response
        ConversationAPIClient.post("/conversations/", params, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                conversation.setUploaded(true);
                updateConversation(conversation);
                Log.i(TAG, "Successfully uploaded conversation w/ status code" + statusCode);
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                Log.e(TAG, "Failed to upload conversation, got status " + statusCode + "; errors:");
                for (Iterator<String> it = errorResponse.keys(); it.hasNext(); ) {
                    String key = it.next();
                    try {
                        Log.e(TAG, key + ": " + errorResponse.getString(key));
                    } catch (JSONException e) {
                    }
                }
            }

        });

        return conversation.isUploaded();
    }

}
