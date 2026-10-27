package com.fahad.daud.mixichat;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** FCM data-message handling and per-installation token registration. */
public class MyFirebaseMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL_ID = "mixi_messages";
    private static final String PREFS = "MixiFcm";
    private static final String INSTALLATION_KEY = "installation_id";
    private static final String PENDING_TOKEN_KEY = "pending_token";
    private static final String SEEN_MESSAGES_KEY = "seen_message_ids";

    private static String installationId(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(INSTALLATION_KEY, null);
        if (id == null || id.length() == 0) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(INSTALLATION_KEY, id).apply();
        }
        return id;
    }

    /** Call after authentication (for example, from MainActivity.onCreate). */
    public static void registerCurrentToken(final Context context) {
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(
                new com.google.android.gms.tasks.OnSuccessListener<String>() {
                    @Override public void onSuccess(String token) {
                        if (token != null && !token.isEmpty()) saveToken(context, token);
                    }
                });
    }

    private static void saveToken(Context context, String token) {
        if (token == null || token.isEmpty()) return;
        com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String uid = user == null ? null : user.getUid();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PENDING_TOKEN_KEY, token).apply();
        if (uid == null || uid.isEmpty()) return;

        writeTokenRecord(context, uid, token, 0);
    }

    private static void writeTokenRecord(final Context context, String uid, final String token, final int attempt) {
        Map<String, Object> record = new HashMap<>();
        record.put("token", token);
        record.put("updatedAt", System.currentTimeMillis());
        FirebaseDatabase.getInstance().getReference("users").child(uid)
                .child("fcmTokens").child(installationId(context)).setValue(record)
                .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Void>() {
                    @Override public void onSuccess(Void unused) {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .remove(PENDING_TOKEN_KEY).apply();
                    }
                }).addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                    @Override public void onFailure(Exception error) {
                        if (attempt >= 5) return;
                        final long delay = Math.min(60000L, 2000L << attempt);
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() {
                                com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                                if (user != null) writeTokenRecord(context, user.getUid(), token, attempt + 1);
                            }
                        }, delay);
                    }
                });
    }

    /** Remove only this installation's token record before signing out. */
    public static com.google.android.gms.tasks.Task<Void> removeCurrentInstallationToken(Context context) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null || uid.isEmpty()) return com.google.android.gms.tasks.Tasks.forResult(null);
        return FirebaseDatabase.getInstance().getReference("users").child(uid)
                .child("fcmTokens").child(installationId(context)).removeValue();
    }

    @Override public void onNewToken(String token) {
        super.onNewToken(token);
        saveToken(getApplicationContext(), token);
    }

    @Override public void onMessageReceived(RemoteMessage remoteMessage) {
        Map<String, String> data = remoteMessage.getData();
        if (data == null || !"chat_message".equals(data.get("type"))) return;
        String pairId = data.get("pairId");
        String messageId = data.get("messageId");
        String senderId = data.get("senderId");
        String receiverId = data.get("receiverId");
        String myUid = FirebaseAuth.getInstance().getUid();
        if (empty(pairId) || empty(messageId) || empty(senderId) || empty(receiverId) ||
                empty(myUid) || !myUid.equals(receiverId) || myUid.equals(senderId)) return;

        // MainActivity's realtime watcher handles in-app foreground notices.
        // Let it own that state and use FCM notifications while backgrounded.
        if (isAppForeground()) return;
        if (alreadyDisplayed(messageId)) return;

        createNotificationChannel();
        Intent open = new Intent(this, ChatActivity.class);
        open.putExtra("otherUid", senderId);
        open.putExtra("pairId", pairId);
        open.putExtra("messageId", messageId);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int requestCode = messageId.hashCode();
        PendingIntent pendingIntent = PendingIntent.getActivity(this, requestCode, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("New message")
                .setContentText("You have a new message")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(requestCode, notification.build());
    }

    private boolean alreadyDisplayed(String messageId) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(SEEN_MESSAGES_KEY, null);
        HashSet<String> seen = saved == null ? new HashSet<>() : new HashSet<>(saved);
        if (seen.contains(messageId)) return true;
        seen.add(messageId);
        // Keep bounded local de-duplication state.
        while (seen.size() > 256) {
            String first = seen.iterator().next();
            seen.remove(first);
        }
        prefs.edit().putStringSet(SEEN_MESSAGES_KEY, seen).apply();
        return false;
    }

    private boolean isAppForeground() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return false;
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) return false;
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (getPackageName().equals(process.processName))
                return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        }
        return false;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Mixi Chat Messages", NotificationManager.IMPORTANCE_HIGH));
    }

    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
}
