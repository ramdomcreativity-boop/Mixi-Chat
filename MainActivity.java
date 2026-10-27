// ===== onCreate code =====


// ============================================================
// MIXI CHAT — MainActivity (compile-error corrected revision)
// Paste into: Main screen -> onCreate -> Add Source Directly
// ============================================================

try {
    com.google.firebase.database.FirebaseDatabase.getInstance().setPersistenceEnabled(true);
} catch (Throwable t) {}

final com.google.firebase.auth.FirebaseAuth firebaseAuth = com.google.firebase.auth.FirebaseAuth.getInstance();
final com.google.firebase.auth.FirebaseUser currentUser = firebaseAuth.getCurrentUser();

if (currentUser == null) {
    android.content.Intent toLogin = new android.content.Intent(MainActivity.this, LoginActivity.class);
    startActivity(toLogin);
    finish();
    return;
}

// Re-fetch on authenticated startup so tokens created before login are bound
// to this account; onNewToken handles subsequent rotations.
com.fahad.daud.mixichat.MyFirebaseMessagingService.registerCurrentToken(getApplicationContext());

// ---------- Battery optimization exemption (ask once) ----------
android.content.SharedPreferences batteryPrefs = getSharedPreferences("MixiPrefs", android.content.Context.MODE_PRIVATE);
if (!batteryPrefs.getBoolean("asked_battery_exemption", false)) {
    batteryPrefs.edit().putBoolean("asked_battery_exemption", true).apply();
    try {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            android.content.Intent intent = new android.content.Intent();
            intent.setAction(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    } catch (Throwable ignored) {}
}

// ---------- Notification permission (Android 13+) ----------
if (android.os.Build.VERSION.SDK_INT >= 33) {
    if (android.content.pm.PackageManager.PERMISSION_GRANTED != checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)) {
        requestPermissions(new String[]{ android.Manifest.permission.POST_NOTIFICATIONS }, 801);
    }
}

// ---------- FIX: core refs declared FIRST, before anything uses them ----------
final String myUid = currentUser.getUid();
final com.google.firebase.database.DatabaseReference rootRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference();
final android.content.SharedPreferences cachePrefs = getSharedPreferences("MixiCache", android.content.Context.MODE_PRIVATE);
final java.util.concurrent.ExecutorService cacheExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
final String CALL_NOTIF_CHANNEL_ID = "mixi_calls_v3";
final int CALL_NOTIFICATION_BASE_ID = 700000;
final android.net.Uri callDefaultRingtone = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE);
final android.media.AudioAttributes callAudioAttributes = new android.media.AudioAttributes.Builder()
    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build();

// Notification actions: calls and friend requests use MainActivity as a
// lightweight trampoline so every action is transactional and dismisses the
// originating notification before changing Firebase state.
String notificationAction = getIntent().getAction();
if ("DECLINE_FRIEND_REQUEST".equals(notificationAction) || "ACCEPT_FRIEND_REQUEST".equals(notificationAction)) {
    final String requestUid = getIntent().getStringExtra("otherUid");
    if (requestUid != null) {
        final String requestPairId = myUid.compareTo(requestUid) < 0 ? myUid + "_" + requestUid : requestUid + "_" + myUid;
        final int requestNotificationId = 710000 + Math.abs(requestPairId.hashCode());
        try { ((android.app.NotificationManager)getSystemService(android.content.Context.NOTIFICATION_SERVICE)).cancel(requestNotificationId); } catch (Throwable ignored) {}
        if ("ACCEPT_FRIEND_REQUEST".equals(notificationAction)) {
            rootRef.child("relationships").child(requestPairId).child("status").setValue("friends");
            rootRef.child("relationships").child(requestPairId).child("updatedAt").setValue(System.currentTimeMillis());
        } else {
            rootRef.child("relationships").child(requestPairId).removeValue();
        }
        getSharedPreferences("MixiCache", android.content.Context.MODE_PRIVATE).edit().putBoolean("friend_request_notified_" + requestPairId, false).apply();
    }
    finish(); return;
}

// Notification decline action: update the call atomically and close the
// trampoline activity. Accept opens CallActivity directly.
if ("DECLINE_CALL".equals(notificationAction) || "ACCEPT_CALL".equals(notificationAction)) {
    String actionCallId = getIntent().getStringExtra("callId");
    if (actionCallId != null) {
        int actionNotificationId = CALL_NOTIFICATION_BASE_ID + Math.abs(actionCallId.hashCode());
        try { ((android.app.NotificationManager)getSystemService(android.content.Context.NOTIFICATION_SERVICE)).cancel(actionNotificationId); } catch (Throwable ignored) {}
        if ("DECLINE_CALL".equals(notificationAction)) {
            rootRef.child("calls").child(actionCallId).child("status").setValue("declined");
            finish(); return;
        }
        try {
            android.content.Intent acceptedCall = new android.content.Intent(MainActivity.this, Class.forName(getPackageName() + ".CallActivity"));
            acceptedCall.putExtra("incoming", true); acceptedCall.putExtra("callId", actionCallId);
            acceptedCall.putExtra("otherUid", getIntent().getStringExtra("otherUid"));
            acceptedCall.putExtra("otherName", getIntent().getStringExtra("otherName"));
            acceptedCall.putExtra("otherPhoto", getIntent().getStringExtra("otherPhoto"));
            acceptedCall.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(acceptedCall);
        } catch (Throwable ignored) {}
    }
    finish(); return;
}

// ---------- Message watcher ----------
// IMPORTANT: receiving/delivering a message is NOT the same as reading it.
// This watcher never writes a read state. The unreadCount shown by the home
// list remains controlled by Firebase/ChatActivity, and ChatActivity should
// clear it only when the user actually opens/reads that chat.
// When this activity is visible (including the Home tab), the user is already
// in the app: keep the unread badge, but do not show an OS notification.
// Background behavior is limited by Android activity/process lifecycle.
// ---------- Compile fixes applied: FutureTarget instead of Target,
// explicit final on target/text used inside nested/background code. ----------
final String NOTIF_CHANNEL_ID = "mixi_messages";
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
    android.app.NotificationChannel channel = new android.app.NotificationChannel(
        NOTIF_CHANNEL_ID, "Messages", android.app.NotificationManager.IMPORTANCE_HIGH);
    android.app.NotificationManager channelMgr = (android.app.NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
    channelMgr.createNotificationChannel(channel);
    android.app.NotificationChannel callChannel = new android.app.NotificationChannel(CALL_NOTIF_CHANNEL_ID, "Incoming calls", android.app.NotificationManager.IMPORTANCE_MAX);
    callChannel.setSound(callDefaultRingtone, callAudioAttributes);
    callChannel.enableVibration(true);
    callChannel.setBypassDnd(true);
    channelMgr.createNotificationChannel(callChannel);
}

final java.util.Map<String, com.google.firebase.database.ValueEventListener> messageWatchListeners = new java.util.HashMap<>();

class AppForegroundState {
    boolean isForeground() {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) return false;
        for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
            if (getPackageName().equals(process.processName))
                return process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        }
        return false;
    }
}
final AppForegroundState appForegroundState = new AppForegroundState();

class MessageNotifier {
    void showNotification(final String otherUid, final String name, final String photoURL, final String message) {
        final String safeMessage = message == null ? "" : message;
        final String truncated = safeMessage.length() > 50 ? safeMessage.substring(0, 50) + "..." : safeMessage;

        final android.content.Intent openChat = new android.content.Intent();
        try {
            openChat.setClassName(getPackageName(), getPackageName() + ".ChatActivity");
            openChat.putExtra("otherUid", otherUid);
            openChat.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        } catch (Throwable ignored) {}

        final android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
            MainActivity.this, otherUid.hashCode(), openChat,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

        final androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(MainActivity.this, NOTIF_CHANNEL_ID)
            .setContentTitle(name)
            .setContentText(truncated)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH);

        final android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        final int notifyId = otherUid.hashCode();

        try {
            final com.bumptech.glide.request.FutureTarget<android.graphics.Bitmap> target = com.bumptech.glide.Glide.with(getApplicationContext())
                .asBitmap().load(photoURL).circleCrop().submit(128, 128);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try { builder.setLargeIcon(target.get()); } catch (Throwable ignored) {}
                    nm.notify(notifyId, builder.build());
                }
            }).start();
        } catch (Throwable t) {
            nm.notify(notifyId, builder.build());
        }
    }

    void cancelFor(final String otherUid) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (otherUid != null) nm.cancel(otherUid.hashCode());
        } catch (Throwable ignored) {}
    }

    void cancelAll() {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            nm.cancelAll();
        } catch (Throwable ignored) {}
    }
}
final MessageNotifier messageNotifier = new MessageNotifier();

class FriendRequestNotifier {
    void cancelFor(final String pairId) {
        try { ((android.app.NotificationManager)getSystemService(android.content.Context.NOTIFICATION_SERVICE)).cancel(710000 + Math.abs(pairId.hashCode())); } catch (Throwable ignored) {}
    }
    void show(final String otherUid, final String name, final String photoURL, final String pairId) {
        final android.content.Intent accept = new android.content.Intent(MainActivity.this, MainActivity.class);
        accept.setAction("ACCEPT_FRIEND_REQUEST"); accept.putExtra("otherUid", otherUid);
        accept.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final android.content.Intent decline = new android.content.Intent(MainActivity.this, MainActivity.class);
        decline.setAction("DECLINE_FRIEND_REQUEST"); decline.putExtra("otherUid", otherUid);
        decline.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE;
        android.app.PendingIntent acceptPi = android.app.PendingIntent.getActivity(MainActivity.this, (pairId + "_accept").hashCode(), accept, flags);
        android.app.PendingIntent declinePi = android.app.PendingIntent.getActivity(MainActivity.this, (pairId + "_decline").hashCode(), decline, flags);
        final androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(MainActivity.this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_add).setContentTitle("Friend request").setContentText(name)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH).setAutoCancel(false).setOngoing(true)
            .addAction(new androidx.core.app.NotificationCompat.Action(android.R.drawable.ic_menu_add, "Accept", acceptPi))
            .addAction(new androidx.core.app.NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePi));
        final android.app.NotificationManager nm = (android.app.NotificationManager)getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        final int notificationId = 710000 + Math.abs(pairId.hashCode());
        try {
            final com.bumptech.glide.request.FutureTarget<android.graphics.Bitmap> target = com.bumptech.glide.Glide.with(getApplicationContext()).asBitmap().load(photoURL).circleCrop().submit(128, 128);
            new Thread(new Runnable() { @Override public void run() { try { builder.setLargeIcon(target.get()); } catch (Throwable ignored) {} nm.notify(notificationId, builder.build()); } }).start();
        } catch (Throwable ignored) { nm.notify(notificationId, builder.build()); }
    }
}
final FriendRequestNotifier friendRequestNotifier = new FriendRequestNotifier();
// Home dismissal only removes the OS notification. It never changes
// messages/{pairId}/{messageId}/status and never changes unreadCount.
messageNotifier.cancelAll();

// ---------- FIX: ChatOpenReadHandler REMOVED ----------
// It previously wrote unreadCount=0 and read receipts (messages/*/status="read")
// directly from MainActivity when a conversation row was tapped. That is a
// read-state write happening BEFORE the chat screen is even opened, which is
// exactly what must NOT happen. Home now only ever dismisses the OS
// notification (messageNotifier.cancelFor) and launches ChatActivity.
// ChatActivity alone is responsible for read receipts / unreadCount reset,
// and only once the user has actually opened/viewed that chat.

rootRef.child("relationships").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
        for (com.google.firebase.database.DataSnapshot relSnap : snap.getChildren()) {
            String pairId = relSnap.getKey();
            if (pairId == null || !pairId.contains(myUid)) continue;
            String status = relSnap.child("status").getValue(String.class);
            if (!"friends".equals(status)) continue;
            if (messageWatchListeners.containsKey(pairId)) continue;

            String[] parts = pairId.split("_");
            if (parts.length < 2) continue;
            final String otherUid = parts[0].equals(myUid) ? parts[1] : parts[0];
            if (otherUid.equals(myUid)) continue;

            com.google.firebase.database.ValueEventListener msgListener = rootRef.child("conversations").child(pairId).child("lastMessage")
                .addValueEventListener(new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(com.google.firebase.database.DataSnapshot msgSnap) {
                        final String senderId = msgSnap.child("senderId").getValue(String.class);
                        final String rawText = msgSnap.child("text").getValue(String.class);
                        final String messageType = msgSnap.child("type").getValue(String.class);
                        final Long audioDuration = msgSnap.child("audioDurationSeconds").getValue(Long.class);
                        final Boolean deletedForEveryoneValue = msgSnap.child("isDeletedForEveryone").getValue(Boolean.class);
                        final boolean deletedForEveryone = deletedForEveryoneValue != null && deletedForEveryoneValue;
                        final String reactionEvent = msgSnap.child("eventType").getValue(String.class);
                        final String eventEmoji = msgSnap.child("eventEmoji").getValue(String.class);
                        final String eventMessageText = msgSnap.child("eventMessageText").getValue(String.class);
                        final String eventMessageType = msgSnap.child("eventMessageType").getValue(String.class);
                        final Long eventDuration = msgSnap.child("eventMessageDurationSeconds").getValue(Long.class);
                        final String eventActorId = msgSnap.child("eventActorId").getValue(String.class);
                        final String text = deletedForEveryone ? "This message was deleted." : ("reaction".equals(reactionEvent) ? "Reaction" : ("audio".equals(messageType) ? "Audio message (" + ((audioDuration == null ? 0 : audioDuration.intValue()) / 60) + ":" + ((audioDuration == null ? 0 : audioDuration.intValue()) % 60 < 10 ? "0" : "") + ((audioDuration == null ? 0 : audioDuration.intValue()) % 60) + ")" : rawText));
                        final Long ts = msgSnap.child("timestamp").getValue(Long.class);
                        // FIX (bug #1, part 2): also read the message's own status.
                        // If it is already "read" (e.g. you read it inside the chat,
                        // or it synced as read on reconnect), never fire a
                        // notification for it — not the first time, not on any
                        // later snapshot replay of the same lastMessage node.
                        final String msgStatus = msgSnap.child("status").getValue(String.class);
                        if (senderId == null || senderId.equals(myUid) || text == null || ts == null) return;
                        if ("read".equals(msgStatus)) return;

                        String activeChatUid = cachePrefs.getString("active_chat_uid", null);
                        long lastNotified = cachePrefs.getLong("last_notified_" + otherUid, 0);
                        String notificationSignature = String.valueOf(ts) + "|" + text + "|" + deletedForEveryone + "|" + reactionEvent + "|" + eventEmoji;
                        String lastNotificationSignature = cachePrefs.getString("last_notification_signature_" + otherUid, null);
                        if (ts < lastNotified) return;
                        if (ts == lastNotified && notificationSignature.equals(lastNotificationSignature)) return;

                        // Save both timestamp and content signature. The
                        // signature is important because editing/deleting a
                        // message can keep the original timestamp unchanged.
                        cachePrefs.edit()
                            .putLong("last_notified_" + otherUid, ts)
                            .putString("last_notification_signature_" + otherUid, notificationSignature)
                            .apply();

                        // Only suppress a notification when this exact chat
                        // is already open. Do not use general Activity visibility
                        // here: MainActivity being visible must not disable all
                        // incoming notifications.
                        if (otherUid.equals(activeChatUid)) return;

                        rootRef.child("users").child(otherUid).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                            @Override
                            public void onDataChange(com.google.firebase.database.DataSnapshot userSnap) {
                                String name = userSnap.child("name").getValue(String.class);
                                String photoURL = userSnap.child("photoURL").getValue(String.class);
                                String notificationText = text;
                                if ("reaction".equals(reactionEvent) && !myUid.equals(eventActorId)) {
                                    String reacted = "audio".equals(eventMessageType)
                                        ? "Audio message (" + ((eventDuration == null ? 0 : eventDuration.intValue()) / 60) + ":" + ((eventDuration == null ? 0 : eventDuration.intValue()) % 60 < 10 ? "0" : "") + ((eventDuration == null ? 0 : eventDuration.intValue()) % 60) + ")"
                                        : (eventMessageText == null ? "message" : eventMessageText);
                                    if (reacted.length() > 15) reacted = reacted.substring(0, 15).trim() + "…";
                                    notificationText = (name == null ? "Someone" : name) + " reacted " + (eventEmoji == null ? "" : eventEmoji) + " to " + reacted;
                                }
                                // MainActivity owns notifications only while the process is foregrounded.
                                // Background delivery belongs to FCM to avoid two notification sources.
                                if (appForegroundState.isForeground()) {
                                    messageNotifier.showNotification(otherUid, name == null ? "Someone" : name, photoURL, notificationText);
                                }
                            }
                            @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
                        });
                    }
                    @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
                });
            messageWatchListeners.put(pairId, msgListener);
        }
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

final float density = getResources().getDisplayMetrics().density;
class Utils {
    int dp(float val) { return (int)(val * density); }
}
final Utils utils = new Utils();

class MainEmojiHelper {
    final android.graphics.Typeface font;
    MainEmojiHelper() {
        android.graphics.Typeface loaded;
        try { loaded = android.graphics.Typeface.createFromAsset(getAssets(), "twemoji/iOS_Emojis.ttf"); }
        catch (Throwable ignored) { loaded = android.graphics.Typeface.DEFAULT; }
        font = loaded;
    }
    void style(android.widget.TextView view) {
        view.setTypeface(font);
    }
}
final MainEmojiHelper emojiHelper = new MainEmojiHelper();
final android.graphics.Typeface emojiTypeface = emojiHelper.font;

final int COLOR_BLUE = android.graphics.Color.parseColor("#0076FF");
final int COLOR_TEXT = android.graphics.Color.parseColor("#1E293B");
final int COLOR_MUTED = android.graphics.Color.parseColor("#64748B");
final int COLOR_GREEN = android.graphics.Color.parseColor("#10B981");
final int COLOR_RED = android.graphics.Color.parseColor("#EF4444");
final int COLOR_AMBER = android.graphics.Color.parseColor("#F59E0B");
final int COLOR_BG_LIGHT_BLUE = android.graphics.Color.parseColor("#EBF4FF");
final int COLOR_DIVIDER = android.graphics.Color.parseColor("#F1F5F9");

class UiKit {
    android.graphics.drawable.GradientDrawable rounded(int colorInt, float radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(colorInt);
        d.setCornerRadius(radiusDp * density);
        return d;
    }
    android.graphics.drawable.GradientDrawable outline(int strokeColor, float radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(android.graphics.Color.WHITE);
        d.setStroke((int)(1.5f * density), strokeColor);
        d.setCornerRadius(radiusDp * density);
        return d;
    }
}
final UiKit ui = new UiKit();

class DefaultAvatarDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final String letters;
    DefaultAvatarDrawable(String name) {
        paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        String n = name == null ? "" : name.trim();
        if (n.length() == 0) n = "User";
        String[] parts = n.split("\\s+");
        String a = parts.length > 0 && parts[0].length() > 0 ? parts[0].substring(0, 1) : "U";
        String b = parts.length > 1 && parts[parts.length - 1].length() > 0 ? parts[parts.length - 1].substring(0, 1) : "";
        letters = (a + b).toUpperCase(java.util.Locale.getDefault());
    }
    @Override public void draw(android.graphics.Canvas canvas) {
        android.graphics.Rect b = getBounds();
        float cx=b.exactCenterX(), cy=b.exactCenterY();
        float r=Math.min(b.width(), b.height())*0.5f;
        paint.setStyle(android.graphics.Paint.Style.FILL); paint.setColor(COLOR_BLUE);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setColor(android.graphics.Color.WHITE); paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        paint.setTextSize(r * (letters.length() > 1 ? 0.72f : 0.9f));
        android.graphics.Paint.FontMetrics fm=paint.getFontMetrics();
        canvas.drawText(letters, cx, cy - (fm.ascent + fm.descent)/2f, paint);
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
    @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
}

class AvatarLoader {
    void load(String url, String name, final android.widget.ImageView target) {
        boolean isDefault = url == null || url.trim().length() == 0 || url.contains("ui-avatars.com");
        if (isDefault) {
            target.setImageDrawable(new DefaultAvatarDrawable(name));
            return;
        }
        try {
            com.bumptech.glide.Glide.with(target.getContext())
                .load(url).placeholder(new DefaultAvatarDrawable(name)).error(new DefaultAvatarDrawable(name))
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL).circleCrop().into(target);
        } catch (Throwable ignored) { target.setImageDrawable(new DefaultAvatarDrawable(name)); }
    }
}
final AvatarLoader avatarLoader = new AvatarLoader();

class GearDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    GearDrawable(int color) { paint.setColor(color); paint.setStyle(android.graphics.Paint.Style.FILL); }
    @Override
    public void draw(android.graphics.Canvas canvas) {
        android.graphics.Rect bounds = getBounds();
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        float rOuter = Math.min(bounds.width(), bounds.height()) * 0.44f;
        float rInner = rOuter * 0.74f;
        float rHole = rOuter * 0.36f;
        android.graphics.Path path = new android.graphics.Path();
        for (int i = 0; i < 6; i++) {
            double a1 = Math.toRadians(i * 60 - 12);
            double a2 = Math.toRadians(i * 60 + 12);
            double a3 = Math.toRadians(i * 60 + 20);
            double a4 = Math.toRadians((i + 1) * 60 - 20);
            float x1 = (float)(cx + rOuter * Math.cos(a1));
            float y1 = (float)(cy + rOuter * Math.sin(a1));
            float x2 = (float)(cx + rOuter * Math.cos(a2));
            float y2 = (float)(cy + rOuter * Math.sin(a2));
            float x3 = (float)(cx + rInner * Math.cos(a3));
            float y3 = (float)(cy + rInner * Math.sin(a3));
            float x4 = (float)(cx + rInner * Math.cos(a4));
            float y4 = (float)(cy + rInner * Math.sin(a4));
            if (i == 0) path.moveTo(x1, y1); else path.lineTo(x1, y1);
            path.lineTo(x2, y2); path.lineTo(x3, y3); path.lineTo(x4, y4);
        }
        path.close();
        path.addCircle(cx, cy, rHole, android.graphics.Path.Direction.CW);
        path.setFillType(android.graphics.Path.FillType.EVEN_ODD);
        canvas.drawPath(path, paint);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}

class CallDirectionArrowDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final boolean outgoing;
    CallDirectionArrowDrawable(boolean isOutgoing, int color) {
        outgoing = isOutgoing; paint.setColor(color); paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f * density); paint.setStrokeCap(android.graphics.Paint.Cap.ROUND); paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
    }
    @Override public void draw(android.graphics.Canvas canvas) {
        android.graphics.Rect b = getBounds(); float w=b.width(), h=b.height();
        float x1=outgoing ? b.left+w*.22f : b.left+w*.78f, x2=outgoing ? b.right-w*.22f : b.right-w*.78f;
        float y1=outgoing ? b.bottom-h*.22f : b.top+h*.22f, y2=outgoing ? b.top+h*.22f : b.bottom-h*.22f;
        canvas.drawLine(x1,y1,x2,y2,paint); android.graphics.Path head=new android.graphics.Path();
        if(outgoing){head.moveTo(x2,y2);head.lineTo(x2-w*.24f,y2);head.moveTo(x2,y2);head.lineTo(x2,y2+h*.24f);}
        else{head.moveTo(x2,y2);head.lineTo(x2+w*.24f,y2);head.moveTo(x2,y2);head.lineTo(x2,y2-h*.24f);}
        canvas.drawPath(head,paint);
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
    @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
}

class PinDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    PinDrawable(int color) { paint.setColor(color); paint.setStyle(android.graphics.Paint.Style.FILL); }
    @Override public void draw(android.graphics.Canvas canvas) {
        android.graphics.Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float r = Math.min(b.width(), b.height()) * 0.26f;
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(cx, cy + r * 2.0f);
        p.lineTo(cx - r * 0.55f, cy + r * 0.45f);
        p.cubicTo(cx - r * 1.8f, cy + r * 0.05f, cx - r * 1.5f, cy - r * 1.15f, cx - r * 0.65f, cy - r * 1.6f);
        p.lineTo(cx + r * 0.65f, cy - r * 1.6f);
        p.cubicTo(cx + r * 1.5f, cy - r * 1.15f, cx + r * 1.8f, cy + r * 0.05f, cx + r * 0.55f, cy + r * 0.45f);
        p.close();
        canvas.drawPath(p, paint);
        canvas.drawRect(cx - r * 0.8f, cy - r * 1.85f, cx + r * 0.8f, cy - r * 1.45f, paint);
        paint.setStrokeWidth(Math.max(1f, r * 0.28f));
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        canvas.drawLine(cx, cy + r * 0.25f, cx, cy + r * 2.4f, paint);
        paint.setStyle(android.graphics.Paint.Style.FILL);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}

class SubtabStyler {
    void style(android.widget.TextView tv, String text, boolean active) {
        tv.setText(text);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(0, ((int)(12 * density)), 0, ((int)(12 * density)));
        tv.setTextSize(13);
        tv.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        tv.setTextColor(active ? android.graphics.Color.parseColor("#0076FF") : android.graphics.Color.parseColor("#64748B"));
        if (active) {
            android.graphics.drawable.GradientDrawable underline = new android.graphics.drawable.GradientDrawable();
            underline.setColor(android.graphics.Color.parseColor("#0076FF"));
            underline.setCornerRadius(3 * density);
            android.graphics.drawable.LayerDrawable layered = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{ underline });
            layered.setLayerInset(0, 0, ((int)(38 * density)), 0, 0);
            tv.setBackground(layered);
        } else {
            tv.setBackground(null);
        }
    }
}
final SubtabStyler subtabStyler = new SubtabStyler();

class ConversationItem {
    String otherUid;
    String name;
    String photoURL;
    String lastMessageText;
    long lastMessageTimestamp;
    String lastMessageStatus;
    String lastMessageId;
    boolean lastMessageIsMine;
    boolean isDeleted;
    int unreadCount;
    boolean isOnline;
    boolean isTyping;
    boolean isPinned;
    boolean isSelf;
    String lastMessageType;
    int lastMessageDurationSeconds;
    String eventType;
    String eventActorId;
    String eventActorName;
    String eventEmoji;
    String eventMessageText;
    String eventMessageType;
    int eventMessageDurationSeconds;
}

class CallLogItem {
    String callId;
    String otherUid;
    String otherName;
    String otherPhoto;
    String type;
    String direction;
    String status;
    long duration;
    long timestamp;
}

class FriendEntryItem {
    String otherUid;
    String name;
    String photoURL;
    String username;
    String subtabType;
}

final java.util.List<ConversationItem> conversationList = new java.util.ArrayList<>();
final java.util.Set<String> selectedConversationUids = new java.util.HashSet<>();
final java.util.Set<String> selectedCallLogIds = new java.util.HashSet<>();
final String[] homeSearchQuery = { "" };
final java.util.List<CallLogItem> callHistoryList = new java.util.ArrayList<>();
final java.util.List<FriendEntryItem> masterFriendsList = new java.util.ArrayList<>();
final java.util.List<FriendEntryItem> friendsDisplayList = new java.util.ArrayList<>();
final String[] currentFriendsSubtab = { "friends" };
final java.util.Set<String> pinnedChatUids = new java.util.HashSet<>();
final java.util.Map<String, Long> deletedChatUntil = new java.util.HashMap<>();
final boolean[] myShowOnlinePriv = { true };

final String[] selectedConversationUid = { null };
final String[] selectedCallLogId = { null };

// FIX #14/#15: adapter holder arrays declared EARLY, before any listener uses them
final android.widget.BaseAdapter[] conversationsAdapterRef = new android.widget.BaseAdapter[1];
final android.widget.BaseAdapter[] callsAdapterRef = new android.widget.BaseAdapter[1];

// ---------- STEP 1: UI shell ----------

final android.widget.FrameLayout rootOverlay = new android.widget.FrameLayout(MainActivity.this);
rootOverlay.setBackgroundColor(android.graphics.Color.WHITE);

final android.widget.LinearLayout rootLayout = new android.widget.LinearLayout(MainActivity.this);
rootLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
rootLayout.setBackgroundColor(android.graphics.Color.WHITE);

final android.widget.LinearLayout normalHeader = new android.widget.LinearLayout(MainActivity.this);
normalHeader.setOrientation(android.widget.LinearLayout.HORIZONTAL);
normalHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
normalHeader.setPadding(utils.dp(16), utils.dp(12), utils.dp(16), utils.dp(12));
normalHeader.setBackgroundColor(android.graphics.Color.WHITE);

android.widget.ImageView logoView = new android.widget.ImageView(MainActivity.this);
android.widget.LinearLayout.LayoutParams logoParams = new android.widget.LinearLayout.LayoutParams(utils.dp(36), utils.dp(36));
logoParams.rightMargin = utils.dp(8);
logoView.setLayoutParams(logoParams);
logoView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
try {
    int resId = getResources().getIdentifier("favicon", "drawable", getPackageName());
    if (resId != 0) logoView.setImageResource(resId); else logoView.setImageResource(android.R.drawable.ic_menu_gallery);
} catch (Throwable t) {}

android.widget.TextView brandTitle = new android.widget.TextView(MainActivity.this);
brandTitle.setText("Mixi Chat");
brandTitle.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
brandTitle.setTextSize(20);
brandTitle.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
brandTitle.setTextColor(COLOR_BLUE);
android.widget.LinearLayout.LayoutParams titleParams = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);

final android.widget.FrameLayout settingsButton = new android.widget.FrameLayout(MainActivity.this);
settingsButton.setBackground(ui.rounded(COLOR_BG_LIGHT_BLUE, 24));
android.widget.LinearLayout.LayoutParams setParams = new android.widget.LinearLayout.LayoutParams(utils.dp(44), utils.dp(44));
settingsButton.setLayoutParams(setParams);

android.widget.ImageView settingsIcon = new android.widget.ImageView(MainActivity.this);
try {
    int resId = getResources().getIdentifier("ic_gear", "drawable", getPackageName());
    if (resId == 0) resId = getResources().getIdentifier("ic_settings", "drawable", getPackageName());
    if (resId != 0) { settingsIcon.setImageResource(resId); settingsIcon.setColorFilter(COLOR_BLUE, android.graphics.PorterDuff.Mode.SRC_IN); }
    else settingsIcon.setImageDrawable(new GearDrawable(COLOR_BLUE));
} catch (Throwable t) { settingsIcon.setImageDrawable(new GearDrawable(COLOR_BLUE)); }
android.widget.FrameLayout.LayoutParams iconParams = new android.widget.FrameLayout.LayoutParams(utils.dp(24), utils.dp(24));
iconParams.gravity = android.view.Gravity.CENTER;
settingsButton.addView(settingsIcon, iconParams);

normalHeader.addView(logoView);
normalHeader.addView(brandTitle, titleParams);
normalHeader.addView(settingsButton);

final android.widget.LinearLayout selectionHeader = new android.widget.LinearLayout(MainActivity.this);
selectionHeader.setOrientation(android.widget.LinearLayout.HORIZONTAL);
selectionHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
selectionHeader.setPadding(utils.dp(16), utils.dp(12), utils.dp(16), utils.dp(12));
selectionHeader.setBackgroundColor(COLOR_BG_LIGHT_BLUE);
selectionHeader.setVisibility(android.view.View.GONE);

final android.widget.TextView selectionCancelBtn = new android.widget.TextView(MainActivity.this);
selectionCancelBtn.setText("\u2715");
selectionCancelBtn.setTextSize(18);
selectionCancelBtn.setTextColor(COLOR_TEXT);
selectionCancelBtn.setPadding(utils.dp(8), utils.dp(8), utils.dp(16), utils.dp(8));

final android.widget.TextView selectionTitle = new android.widget.TextView(MainActivity.this);
selectionTitle.setText("1 selected");
selectionTitle.setTextSize(16);
selectionTitle.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
selectionTitle.setTextColor(COLOR_TEXT);

final android.widget.LinearLayout selectionActionsConvo = new android.widget.LinearLayout(MainActivity.this);
selectionActionsConvo.setOrientation(android.widget.LinearLayout.HORIZONTAL);
selectionActionsConvo.setGravity(android.view.Gravity.CENTER_VERTICAL);

final android.widget.TextView pinActionBtn = new android.widget.TextView(MainActivity.this);
pinActionBtn.setText("Pin");
pinActionBtn.setTextSize(14);
pinActionBtn.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
pinActionBtn.setTextColor(COLOR_BLUE);
pinActionBtn.setPadding(utils.dp(12), utils.dp(6), utils.dp(12), utils.dp(6));

final android.widget.TextView deleteActionBtn = new android.widget.TextView(MainActivity.this);
deleteActionBtn.setText("Delete");
deleteActionBtn.setTextSize(14);
deleteActionBtn.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
deleteActionBtn.setTextColor(COLOR_RED);
deleteActionBtn.setPadding(utils.dp(12), utils.dp(6), utils.dp(12), utils.dp(6));

final android.widget.TextView selectAllCallsBtn = new android.widget.TextView(MainActivity.this);
selectAllCallsBtn.setText("Select all");
selectAllCallsBtn.setTextSize(14);
selectAllCallsBtn.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
selectAllCallsBtn.setTextColor(COLOR_BLUE);
selectAllCallsBtn.setPadding(utils.dp(12), utils.dp(6), utils.dp(12), utils.dp(6));
selectAllCallsBtn.setVisibility(android.view.View.GONE);

selectionActionsConvo.addView(selectAllCallsBtn);
selectionActionsConvo.addView(pinActionBtn);
selectionActionsConvo.addView(deleteActionBtn);

selectionHeader.addView(selectionCancelBtn);
selectionHeader.addView(selectionTitle, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
selectionHeader.addView(selectionActionsConvo);

android.widget.FrameLayout headerStack = new android.widget.FrameLayout(MainActivity.this);
headerStack.addView(normalHeader, new android.widget.FrameLayout.LayoutParams(-1, -2));
headerStack.addView(selectionHeader, new android.widget.FrameLayout.LayoutParams(-1, -2));

android.view.View headerDivider = new android.view.View(MainActivity.this);
headerDivider.setBackgroundColor(COLOR_DIVIDER);

final android.widget.FrameLayout contentFrame = new android.widget.FrameLayout(MainActivity.this);
android.widget.LinearLayout.LayoutParams contentParams = new android.widget.LinearLayout.LayoutParams(-1, 0, 1f);

final android.widget.LinearLayout homeContainer = new android.widget.LinearLayout(MainActivity.this);
homeContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
homeContainer.setBackgroundColor(android.graphics.Color.WHITE);

final android.widget.EditText homeSearchInput = new android.widget.EditText(MainActivity.this);
homeSearchInput.setHint("Search chats by name");
homeSearchInput.setTypeface(emojiTypeface);
homeSearchInput.setSingleLine(true);
homeSearchInput.setTextSize(14);
homeSearchInput.setTextColor(COLOR_TEXT);
homeSearchInput.setHintTextColor(COLOR_MUTED);
homeSearchInput.setPadding(utils.dp(16), utils.dp(10), utils.dp(16), utils.dp(10));
homeSearchInput.setBackground(ui.outline(COLOR_DIVIDER, 20));
android.widget.LinearLayout.LayoutParams homeSearchParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
homeSearchParams.setMargins(utils.dp(16), utils.dp(12), utils.dp(16), utils.dp(8));
homeContainer.addView(homeSearchInput, homeSearchParams);

final android.widget.FrameLayout homeContentWrap = new android.widget.FrameLayout(MainActivity.this);
final android.widget.ListView conversationsListView = new android.widget.ListView(MainActivity.this);
conversationsListView.setDivider(null);
conversationsListView.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
homeContentWrap.addView(conversationsListView, new android.widget.FrameLayout.LayoutParams(-1, -1));

final android.widget.TextView emptyHomeLabel = new android.widget.TextView(MainActivity.this);
emptyHomeLabel.setText("No conversations yet. Tap + to find friends!");
emptyHomeLabel.setGravity(android.view.Gravity.CENTER);
emptyHomeLabel.setTextColor(COLOR_MUTED);
emptyHomeLabel.setVisibility(android.view.View.GONE);
android.widget.FrameLayout.LayoutParams emptyHomeParams = new android.widget.FrameLayout.LayoutParams(-1, -2);
emptyHomeParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
emptyHomeParams.topMargin = utils.dp(50);
homeContentWrap.addView(emptyHomeLabel, emptyHomeParams);
homeContainer.addView(homeContentWrap, new android.widget.LinearLayout.LayoutParams(-1, -1));

final android.widget.LinearLayout callsContainer = new android.widget.LinearLayout(MainActivity.this);
callsContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
callsContainer.setVisibility(android.view.View.GONE);
callsContainer.setBackgroundColor(android.graphics.Color.WHITE);

final android.widget.FrameLayout callsContentWrap = new android.widget.FrameLayout(MainActivity.this);
final android.widget.ListView callsListView = new android.widget.ListView(MainActivity.this);
callsListView.setDivider(null);
callsListView.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
callsContentWrap.addView(callsListView, new android.widget.FrameLayout.LayoutParams(-1, -1));

final android.widget.TextView emptyCallsLabel = new android.widget.TextView(MainActivity.this);
emptyCallsLabel.setText("No call history. Audio calls with friends will appear here.");
emptyCallsLabel.setGravity(android.view.Gravity.CENTER);
emptyCallsLabel.setTextColor(COLOR_MUTED);
emptyCallsLabel.setVisibility(android.view.View.GONE);
android.widget.FrameLayout.LayoutParams emptyCallsParams = new android.widget.FrameLayout.LayoutParams(-1, -2);
emptyCallsParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
emptyCallsParams.topMargin = utils.dp(50);
callsContentWrap.addView(emptyCallsLabel, emptyCallsParams);

final android.widget.FrameLayout fabCallPicker = new android.widget.FrameLayout(MainActivity.this);
fabCallPicker.setBackground(ui.rounded(COLOR_BLUE, 28));
fabCallPicker.setElevation(utils.dp(6));
android.widget.FrameLayout.LayoutParams fabCallParams = new android.widget.FrameLayout.LayoutParams(utils.dp(56), utils.dp(56));
fabCallParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
fabCallParams.setMargins(0, 0, utils.dp(20), utils.dp(20));
android.widget.TextView fabCallIconLabel = new android.widget.TextView(MainActivity.this);
fabCallIconLabel.setText("+");
fabCallIconLabel.setTextColor(android.graphics.Color.WHITE);
fabCallIconLabel.setTextSize(26);
fabCallIconLabel.setGravity(android.view.Gravity.CENTER);
fabCallPicker.addView(fabCallIconLabel, new android.widget.FrameLayout.LayoutParams(-1, -1));

callsContentWrap.addView(fabCallPicker, fabCallParams);
callsContainer.addView(callsContentWrap, new android.widget.LinearLayout.LayoutParams(-1, -1));

final android.widget.LinearLayout friendsContainer = new android.widget.LinearLayout(MainActivity.this);
friendsContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
friendsContainer.setVisibility(android.view.View.GONE);
friendsContainer.setBackgroundColor(android.graphics.Color.WHITE);

android.widget.LinearLayout friendsSubtabBar = new android.widget.LinearLayout(MainActivity.this);
friendsSubtabBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
friendsSubtabBar.setBackgroundColor(android.graphics.Color.WHITE);

final android.widget.TextView subtabFriendsBtn = new android.widget.TextView(MainActivity.this);
final android.widget.TextView subtabReceivedBtn = new android.widget.TextView(MainActivity.this);
final android.widget.TextView subtabSentBtn = new android.widget.TextView(MainActivity.this);
subtabStyler.style(subtabFriendsBtn, "Friends", true);
subtabStyler.style(subtabReceivedBtn, "Received", false);
subtabStyler.style(subtabSentBtn, "Sent", false);
friendsSubtabBar.addView(subtabFriendsBtn, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
friendsSubtabBar.addView(subtabReceivedBtn, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
friendsSubtabBar.addView(subtabSentBtn, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

android.view.View friendsSubtabDivider = new android.view.View(MainActivity.this);
friendsSubtabDivider.setBackgroundColor(COLOR_DIVIDER);

friendsContainer.addView(friendsSubtabBar, new android.widget.LinearLayout.LayoutParams(-1, -2));
friendsContainer.addView(friendsSubtabDivider, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(1)));

final android.widget.FrameLayout friendsContentWrap = new android.widget.FrameLayout(MainActivity.this);
final android.widget.ListView friendsListView = new android.widget.ListView(MainActivity.this);
friendsListView.setDivider(null);
friendsListView.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
friendsContentWrap.addView(friendsListView, new android.widget.FrameLayout.LayoutParams(-1, -1));

final android.widget.TextView emptyFriendsLabel = new android.widget.TextView(MainActivity.this);
emptyFriendsLabel.setText("No friends yet. Discover people to connect with!");
emptyFriendsLabel.setGravity(android.view.Gravity.CENTER);
emptyFriendsLabel.setTextColor(COLOR_MUTED);
emptyFriendsLabel.setVisibility(android.view.View.GONE);
android.widget.FrameLayout.LayoutParams emptyFriendsParams = new android.widget.FrameLayout.LayoutParams(-1, -2);
emptyFriendsParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
emptyFriendsParams.topMargin = utils.dp(50);
friendsContentWrap.addView(emptyFriendsLabel, emptyFriendsParams);
friendsContainer.addView(friendsContentWrap, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));

contentFrame.addView(homeContainer);
contentFrame.addView(callsContainer);
contentFrame.addView(friendsContainer);

final android.widget.FrameLayout fabDiscover = new android.widget.FrameLayout(MainActivity.this);
fabDiscover.setBackground(ui.rounded(COLOR_BLUE, 28));
fabDiscover.setElevation(utils.dp(6));
android.widget.FrameLayout.LayoutParams fabParams = new android.widget.FrameLayout.LayoutParams(utils.dp(56), utils.dp(56));
fabParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
fabParams.setMargins(0, 0, utils.dp(20), utils.dp(20));
android.widget.TextView fabIconLabel = new android.widget.TextView(MainActivity.this);
fabIconLabel.setText("+");
fabIconLabel.setTextColor(android.graphics.Color.WHITE);
fabIconLabel.setTextSize(26);
fabIconLabel.setGravity(android.view.Gravity.CENTER);
fabDiscover.addView(fabIconLabel, new android.widget.FrameLayout.LayoutParams(-1, -1));
contentFrame.addView(fabDiscover, fabParams);

android.widget.LinearLayout bottomNav = new android.widget.LinearLayout(MainActivity.this);
bottomNav.setOrientation(android.widget.LinearLayout.HORIZONTAL);
bottomNav.setGravity(android.view.Gravity.CENTER);
bottomNav.setElevation(utils.dp(8));
bottomNav.setBackgroundColor(android.graphics.Color.WHITE);

class NavItemBuilder {
    android.widget.LinearLayout create(String text, int iconRes) {
        android.widget.LinearLayout tab = new android.widget.LinearLayout(MainActivity.this);
        tab.setOrientation(android.widget.LinearLayout.VERTICAL);
        tab.setGravity(android.view.Gravity.CENTER);
        tab.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -1, 1f));
        tab.setPadding(0, utils.dp(8), 0, utils.dp(8));

        android.widget.LinearLayout inner = new android.widget.LinearLayout(MainActivity.this);
        inner.setOrientation(android.widget.LinearLayout.VERTICAL);
        inner.setGravity(android.view.Gravity.CENTER);
        inner.setPadding(utils.dp(24), utils.dp(8), utils.dp(24), utils.dp(8));
        inner.setTag("inner");

        android.widget.ImageView icon = new android.widget.ImageView(MainActivity.this);
        icon.setImageResource(iconRes);
        icon.setTag("icon");
        android.widget.LinearLayout.LayoutParams icP = new android.widget.LinearLayout.LayoutParams(utils.dp(24), utils.dp(24));
        icP.bottomMargin = utils.dp(4);
        inner.addView(icon, icP);

        android.widget.TextView label = new android.widget.TextView(MainActivity.this);
        label.setText(text);
        label.setTextSize(12);
        label.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        label.setTag("label");
        inner.addView(label, new android.widget.LinearLayout.LayoutParams(-2, -2));

        tab.addView(inner, new android.widget.LinearLayout.LayoutParams(-2, -2));
        return tab;
    }
    void update(android.widget.LinearLayout tab, boolean active) {
        android.widget.LinearLayout inner = (android.widget.LinearLayout) tab.findViewWithTag("inner");
        android.widget.ImageView icon = (android.widget.ImageView) inner.findViewWithTag("icon");
        android.widget.TextView label = (android.widget.TextView) inner.findViewWithTag("label");
        if (active) {
            inner.setBackground(ui.rounded(COLOR_BG_LIGHT_BLUE, 20));
            icon.setColorFilter(COLOR_BLUE, android.graphics.PorterDuff.Mode.SRC_IN);
            label.setTextColor(COLOR_BLUE);
        } else {
            inner.setBackground(null);
            icon.setColorFilter(COLOR_MUTED, android.graphics.PorterDuff.Mode.SRC_IN);
            label.setTextColor(COLOR_MUTED);
        }
    }
}
final NavItemBuilder navBuilder = new NavItemBuilder();

final android.widget.LinearLayout navHome = navBuilder.create("Home", android.R.drawable.sym_action_chat);
final android.widget.LinearLayout navCalls = navBuilder.create("Calls", android.R.drawable.stat_sys_phone_call);
final android.widget.LinearLayout navFriends = navBuilder.create("Friends", android.R.drawable.ic_menu_myplaces);

final android.widget.TextView friendsNavBadge = new android.widget.TextView(MainActivity.this);
friendsNavBadge.setBackground(ui.rounded(COLOR_RED, 8));
friendsNavBadge.setTextColor(android.graphics.Color.WHITE);
friendsNavBadge.setTextSize(9);
friendsNavBadge.setGravity(android.view.Gravity.CENTER);
friendsNavBadge.setVisibility(android.view.View.GONE);
final android.widget.FrameLayout navFriendsWrap = new android.widget.FrameLayout(MainActivity.this);
navFriendsWrap.addView(navFriends, new android.widget.FrameLayout.LayoutParams(-1, -1));
android.widget.FrameLayout.LayoutParams badgeParamsNav = new android.widget.FrameLayout.LayoutParams(utils.dp(16), utils.dp(16));
badgeParamsNav.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
badgeParamsNav.setMargins(0, utils.dp(2), utils.dp(18), 0);
navFriendsWrap.addView(friendsNavBadge, badgeParamsNav);

bottomNav.addView(navHome);
bottomNav.addView(navCalls);
bottomNav.addView(navFriendsWrap, new android.widget.LinearLayout.LayoutParams(0, -1, 1f));

rootLayout.addView(headerStack, new android.widget.LinearLayout.LayoutParams(-1, -2));
rootLayout.addView(headerDivider, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(1)));
rootLayout.addView(contentFrame, contentParams);
rootLayout.addView(bottomNav, new android.widget.LinearLayout.LayoutParams(-1, -2));

rootOverlay.addView(rootLayout, new android.widget.FrameLayout.LayoutParams(-1, -1));
setContentView(rootOverlay);

final int[] currentTabIndex = { 0 };
final android.widget.LinearLayout[] tabContainers = { homeContainer, callsContainer, friendsContainer };
// Assigned after rebuildFriends is declared; allows early tab/swipe handlers
// to refresh the selected friends sub-tab without violating Java local scope.
final Runnable[] rebuildFriendsRef = new Runnable[1];
final android.widget.LinearLayout[] navTabs = { navHome, navCalls, navFriends };
final long[] lastBackPressTime = { 0L };

class TabSwitcher {
    void switchTab(final int newIndex, boolean animate) {
        if (newIndex == 0) messageNotifier.cancelAll();
        if (newIndex != 2 && !"friends".equals(currentFriendsSubtab[0])) {
            currentFriendsSubtab[0] = "friends";
            subtabStyler.style(subtabFriendsBtn, "Friends", true);
            subtabStyler.style(subtabReceivedBtn, "Received", false);
            subtabStyler.style(subtabSentBtn, "Sent", false);
            if (rebuildFriendsRef[0] != null) rebuildFriendsRef[0].run();
        }
        if (newIndex == currentTabIndex[0]) return;
        final int oldIndex = currentTabIndex[0];
        final android.widget.LinearLayout oldView = tabContainers[oldIndex];
        final android.widget.LinearLayout newView = tabContainers[newIndex];
        final int screenWidth = contentFrame.getWidth() > 0 ? contentFrame.getWidth() : getResources().getDisplayMetrics().widthPixels;
        final boolean movingRight = newIndex > oldIndex;

        newView.setTranslationX(movingRight ? screenWidth : -screenWidth);
        newView.setVisibility(android.view.View.VISIBLE);

        if (animate) {
            oldView.animate().translationX(movingRight ? -screenWidth : screenWidth).setDuration(220).withEndAction(new Runnable() {
                @Override public void run() { oldView.setVisibility(android.view.View.GONE); oldView.setTranslationX(0); }
            }).start();
            newView.animate().translationX(0).setDuration(220).start();
        } else {
            oldView.setVisibility(android.view.View.GONE);
            oldView.setTranslationX(0);
            newView.setTranslationX(0);
        }

        fabDiscover.setVisibility(newIndex == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        fabCallPicker.setVisibility(newIndex == 1 ? android.view.View.VISIBLE : android.view.View.GONE);

        for (int i = 0; i < navTabs.length; i++) navBuilder.update(navTabs[i], i == newIndex);
        currentTabIndex[0] = newIndex;

        if (newIndex == 2) friendsNavBadge.setVisibility(android.view.View.GONE);
    }
}
final TabSwitcher tabSwitcher = new TabSwitcher();

navBuilder.update(navHome, true);
navBuilder.update(navCalls, false);
navBuilder.update(navFriends, false);
android.view.View.OnClickListener navClickListener = new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        if (v == navHome) tabSwitcher.switchTab(0, true);
        else if (v == navCalls) tabSwitcher.switchTab(1, true);
        else tabSwitcher.switchTab(2, true);
    }
};
navHome.setOnClickListener(navClickListener);
navCalls.setOnClickListener(navClickListener);
navFriendsWrap.setOnClickListener(navClickListener);

final boolean[] suppressNextRowClick = { false };
final android.view.GestureDetector swipeDetector = new android.view.GestureDetector(MainActivity.this, new android.view.GestureDetector.SimpleOnGestureListener() {
    @Override public boolean onDown(android.view.MotionEvent e) { return true; }
    @Override
    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float velocityX, float velocityY) {
        if (e1 == null) return false;
        float dx = e2.getX() - e1.getX();
        float dy = e2.getY() - e1.getY();
        if (Math.abs(dx) > Math.abs(dy) * 1.35f && Math.abs(dx) > utils.dp(80) && Math.abs(velocityX) > 250) {
            suppressNextRowClick[0] = true;
            // Swipe direction follows the finger: left moves toward the
            // preceding screen/sub-tab; right moves toward the next one.
            if (currentTabIndex[0] == 2) {
                int subIndex = "friends".equals(currentFriendsSubtab[0]) ? 0
                    : ("received".equals(currentFriendsSubtab[0]) ? 1 : 2);
                int nextSubIndex = dx < 0 ? subIndex + 1 : subIndex - 1;
                if (nextSubIndex >= 0 && nextSubIndex < 3) {
                    currentFriendsSubtab[0] = nextSubIndex == 0 ? "friends" : (nextSubIndex == 1 ? "received" : "sent");
                    subtabStyler.style(subtabFriendsBtn, "Friends", nextSubIndex == 0);
                    subtabStyler.style(subtabReceivedBtn, "Received", nextSubIndex == 1);
                    subtabStyler.style(subtabSentBtn, "Sent", nextSubIndex == 2);
                    if (rebuildFriendsRef[0] != null) rebuildFriendsRef[0].run();
                } else if (dx < 0 || (dx > 0 && subIndex == 0)) {
                    tabSwitcher.switchTab(1, true);
                }
            } else if (dx < 0 && currentTabIndex[0] < 2) {
                tabSwitcher.switchTab(currentTabIndex[0] + 1, true);
            } else if (dx > 0 && currentTabIndex[0] > 0) {
                tabSwitcher.switchTab(currentTabIndex[0] - 1, true);
            }
            return true;
        }
        return false;
    }
});
contentFrame.setOnTouchListener(new android.view.View.OnTouchListener() {
    @Override
    public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
        swipeDetector.onTouchEvent(event);
        return false;
    }
});
android.view.View.OnTouchListener childSwipeListener = new android.view.View.OnTouchListener() {
    @Override
    public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
        swipeDetector.onTouchEvent(event);
        return false;
    }
};
homeContainer.setOnTouchListener(childSwipeListener);
callsContainer.setOnTouchListener(childSwipeListener);
friendsContainer.setOnTouchListener(childSwipeListener);
conversationsListView.setOnTouchListener(childSwipeListener);
callsListView.setOnTouchListener(childSwipeListener);
friendsListView.setOnTouchListener(childSwipeListener);

settingsButton.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        try { startActivity(new android.content.Intent(MainActivity.this, Class.forName(getPackageName() + ".SettingsActivity"))); }
        catch (ClassNotFoundException e) { android.widget.Toast.makeText(MainActivity.this, "SettingsActivity not created yet", android.widget.Toast.LENGTH_SHORT).show(); }
    }
});

fabDiscover.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        try { startActivity(new android.content.Intent(MainActivity.this, Class.forName(getPackageName() + ".DiscoverActivity"))); }
        catch (ClassNotFoundException e) { android.widget.Toast.makeText(MainActivity.this, "DiscoverActivity not created yet", android.widget.Toast.LENGTH_SHORT).show(); }
    }
});

final com.google.firebase.database.DatabaseReference myStatusRef = rootRef.child("users").child(myUid).child("status");
rootRef.child(".info/connected").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        Boolean connected = snapshot.getValue(Boolean.class);
        if (connected != null && connected) {
            java.util.Map<String, Object> offlineMap = new java.util.HashMap<>();
            offlineMap.put("state", "offline");
            offlineMap.put("lastChanged", com.google.firebase.database.ServerValue.TIMESTAMP);
            myStatusRef.onDisconnect().setValue(offlineMap);

            java.util.Map<String, Object> onlineMap = new java.util.HashMap<>();
            onlineMap.put("state", "online");
            onlineMap.put("lastChanged", com.google.firebase.database.ServerValue.TIMESTAMP);
            myStatusRef.setValue(onlineMap);
        }
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

rootRef.child("users").child(myUid).child("privacy").child("showOnline").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        Boolean v = snapshot.getValue(Boolean.class);
        myShowOnlinePriv[0] = (v == null || v);
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

rootRef.child("users").child(myUid).child("pinnedChats").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        pinnedChatUids.clear();
        for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) {
            Boolean pinned = child.getValue(Boolean.class);
            if (pinned != null && pinned) pinnedChatUids.add(child.getKey());
        }
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

rootRef.child("users").child(myUid).child("deletedChats").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        deletedChatUntil.clear();
        for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) {
            Long ts = child.getValue(Long.class);
            if (ts != null) deletedChatUntil.put(child.getKey(), ts);
        }
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

class ProfilePreviewHelper {
    void show(String photoURL, String displayName) {
        final android.app.Dialog dialog = new android.app.Dialog(MainActivity.this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout dialogRoot = new android.widget.FrameLayout(MainActivity.this);
        dialogRoot.setBackgroundColor(android.graphics.Color.parseColor("#DD000000"));
        android.widget.ImageView bigAvatar = new android.widget.ImageView(MainActivity.this);
        android.widget.FrameLayout.LayoutParams p = new android.widget.FrameLayout.LayoutParams(utils.dp(250), utils.dp(250));
        p.gravity = android.view.Gravity.CENTER;
        bigAvatar.setLayoutParams(p);
        bigAvatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        avatarLoader.load(photoURL, displayName, bigAvatar);
        dialogRoot.addView(bigAvatar);
        dialogRoot.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { dialog.dismiss(); }
        });
        dialog.setContentView(dialogRoot);
        dialog.show();
    }
}
final ProfilePreviewHelper profilePreview = new ProfilePreviewHelper();

class DurationFormatter {
    String mmss(int totalSeconds) {
        int mins = totalSeconds / 60, secs = totalSeconds % 60;
        return mins + ":" + (secs < 10 ? "0" + secs : "" + secs);
    }
    String format(long totalSeconds) {
        if (totalSeconds <= 0) return "";
        long mins = totalSeconds / 60;
        long secs = totalSeconds % 60;
        if (mins <= 0) return secs + " sec";
        if (secs == 0) return mins + " min";
        return mins + " min " + secs + " sec";
    }
}
final DurationFormatter durationFormatter = new DurationFormatter();

class CallLauncher {
    void launch(String otherUid, String otherName, String otherPhoto) {
        try {
            android.content.Intent callIntent = new android.content.Intent(MainActivity.this, Class.forName(getPackageName() + ".CallActivity"));
            callIntent.putExtra("otherUid", otherUid);
            callIntent.putExtra("otherName", otherName);
            callIntent.putExtra("otherPhoto", otherPhoto);
            startActivity(callIntent);
        } catch (ClassNotFoundException e) {
            android.widget.Toast.makeText(MainActivity.this, "CallActivity not created yet", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
final CallLauncher callLauncher = new CallLauncher();

class FriendActionsHelper {
    String pairId(String a, String b) { return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a; }
    void sendAccept(String otherUid) {
        rootRef.child("relationships").child(pairId(myUid, otherUid)).child("status").setValue("friends");
        rootRef.child("relationships").child(pairId(myUid, otherUid)).child("updatedAt").setValue(System.currentTimeMillis());
    }
    void cancelOrDecline(String otherUid) { rootRef.child("relationships").child(pairId(myUid, otherUid)).removeValue(); }
    void confirmCancelOrDecline(final String otherUid, final boolean received) {
        new android.app.AlertDialog.Builder(MainActivity.this)
            .setTitle(received ? "Decline friend request?" : "Cancel friend request?")
            .setMessage(received ? "Are you sure you want to decline this friend request?" : "Are you sure you want to cancel this friend request?")
            .setNegativeButton("No", null)
            .setPositiveButton("Yes", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface dialog, int which) { cancelOrDecline(otherUid); }
            }).show();
    }
    void openChat(String otherUid) {
        try {
            android.content.Intent chatIntent = new android.content.Intent(MainActivity.this, Class.forName(getPackageName() + ".ChatActivity"));
            chatIntent.putExtra("otherUid", otherUid);
            startActivity(chatIntent);
        } catch (ClassNotFoundException e) { android.widget.Toast.makeText(MainActivity.this, "ChatActivity not created yet", android.widget.Toast.LENGTH_SHORT).show(); }
    }
}
final FriendActionsHelper friendActions = new FriendActionsHelper();

class ConversationSelectionHelper {
    void refreshToolbar() {
        int count = selectedConversationUids.size();
        if (count == 0) { exit(); return; }
        selectedConversationUid[0] = count == 1 ? selectedConversationUids.iterator().next() : null;
        selectAllCallsBtn.setVisibility(android.view.View.GONE);
        normalHeader.setVisibility(android.view.View.GONE);
        selectionHeader.setVisibility(android.view.View.VISIBLE);
        selectionTitle.setText(count + " selected");
        pinActionBtn.setVisibility(count == 1 ? android.view.View.VISIBLE : android.view.View.GONE);
    }
    void enter(String uid) {
        selectedConversationUids.clear();
        selectedConversationUids.add(uid);
        refreshToolbar();
    }
    void toggle(String uid) {
        if (selectedConversationUids.contains(uid)) selectedConversationUids.remove(uid);
        else selectedConversationUids.add(uid);
        refreshToolbar();
    }
    void exit() {
        selectedConversationUids.clear();
        selectedConversationUid[0] = null;
        normalHeader.setVisibility(android.view.View.VISIBLE);
        selectionHeader.setVisibility(android.view.View.GONE);
        pinActionBtn.setVisibility(android.view.View.VISIBLE);
    }
}
final ConversationSelectionHelper convoSelection = new ConversationSelectionHelper();

selectionCancelBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override public void onClick(android.view.View v) {
        selectedConversationUids.clear(); selectedConversationUid[0] = null;
        selectedCallLogIds.clear(); selectedCallLogId[0] = null;
        normalHeader.setVisibility(android.view.View.VISIBLE);
        selectionHeader.setVisibility(android.view.View.GONE);
        pinActionBtn.setVisibility(android.view.View.VISIBLE);
        selectAllCallsBtn.setVisibility(android.view.View.GONE);
        conversationsAdapterRef[0].notifyDataSetChanged(); callsAdapterRef[0].notifyDataSetChanged();
    }
});

pinActionBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        if (selectedConversationUids.size() != 1) return;
        String uid = selectedConversationUids.iterator().next();
        boolean nowPinned = !pinnedChatUids.contains(uid);
        rootRef.child("users").child(myUid).child("pinnedChats").child(uid).setValue(nowPinned ? true : null);
        convoSelection.exit();
    }
});

class CallSelectionHelper {
    void refreshToolbar() {
        int count = selectedCallLogIds.size();
        if (count == 0) { exit(); return; }
        selectedCallLogId[0] = count == 1 ? selectedCallLogIds.iterator().next() : null;
        normalHeader.setVisibility(android.view.View.GONE);
        selectionHeader.setVisibility(android.view.View.VISIBLE);
        selectionTitle.setText(count + " selected");
        pinActionBtn.setVisibility(android.view.View.GONE);
        selectAllCallsBtn.setVisibility(android.view.View.VISIBLE);
        selectAllCallsBtn.setText(callHistoryList.size() > 0 && count == callHistoryList.size() ? "Clear all" : "Select all");
    }
    void enter(String callId) {
        selectedCallLogIds.clear();
        selectedCallLogIds.add(callId);
        refreshToolbar();
    }
    void toggle(String callId) {
        if (selectedCallLogIds.contains(callId)) selectedCallLogIds.remove(callId); else selectedCallLogIds.add(callId);
        refreshToolbar();
    }
    void selectAll() {
        if (selectedCallLogIds.size() == callHistoryList.size()) selectedCallLogIds.clear();
        else { selectedCallLogIds.clear(); for (CallLogItem c : callHistoryList) if (c.callId != null) selectedCallLogIds.add(c.callId); }
        refreshToolbar();
    }
    void exit() {
        selectedCallLogIds.clear();
        selectedCallLogId[0] = null;
        normalHeader.setVisibility(android.view.View.VISIBLE);
        selectionHeader.setVisibility(android.view.View.GONE);
        pinActionBtn.setVisibility(android.view.View.VISIBLE);
        selectAllCallsBtn.setVisibility(android.view.View.GONE);
    }
}
final CallSelectionHelper callSelection = new CallSelectionHelper();
selectAllCallsBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override public void onClick(android.view.View v) { callSelection.selectAll(); callsAdapterRef[0].notifyDataSetChanged(); }
});

// Delete button handles BOTH conversation-delete and call-log-delete,
// branching on which selection is currently active.
deleteActionBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        if (!selectedCallLogIds.isEmpty()) {
            for (String callId : new java.util.HashSet<String>(selectedCallLogIds)) rootRef.child("callHistory").child(myUid).child(callId).removeValue();
            for (int i = callHistoryList.size() - 1; i >= 0; i--) if (selectedCallLogIds.contains(callHistoryList.get(i).callId)) callHistoryList.remove(i);
            callsAdapterRef[0].notifyDataSetChanged();
            callSelection.exit();
            return;
        }

        if (selectedConversationUids.isEmpty()) return;
        final java.util.Set<String> deleteUids = new java.util.HashSet<String>(selectedConversationUids);
        new android.app.AlertDialog.Builder(MainActivity.this)
            .setTitle(deleteUids.size() == 1 ? "Delete Chat" : "Delete Chats")
            .setMessage("Are you sure you want to delete the selected chat" + (deleteUids.size() == 1 ? "?" : "s?") + " This will remove all messages for you only.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    for (final String uid : deleteUids) {
                        final String pairId = myUid.compareTo(uid) < 0 ? myUid + "_" + uid : uid + "_" + myUid;
                        final long deleteTs = System.currentTimeMillis();

                    rootRef.child("messages").child(pairId).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                        @Override
                        public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                            java.util.Map<String, Object> updates = new java.util.HashMap<>();
                            for (com.google.firebase.database.DataSnapshot msg : snap.getChildren()) {
                                updates.put(msg.getKey() + "/deletedFor/" + myUid, true);
                            }
                            if (!updates.isEmpty()) rootRef.child("messages").child(pairId).updateChildren(updates);
                        }
                        @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
                    });

                    rootRef.child("users").child(myUid).child("deletedChats").child(uid).setValue(deleteTs);
                    rootRef.child("users").child(myUid).child("pinnedChats").child(uid).removeValue();

                    for (int i = conversationList.size() - 1; i >= 0; i--) {
                        if (uid.equals(conversationList.get(i).otherUid)) conversationList.remove(i);
                    }
                    }
                    conversationsAdapterRef[0].notifyDataSetChanged();
                    convoSelection.exit();
                }
            }).show();
    }
});

conversationsAdapterRef[0] = new android.widget.BaseAdapter() {
    java.util.List<ConversationItem> visibleItems() {
        String q = homeSearchQuery[0] == null ? "" : homeSearchQuery[0].trim().toLowerCase(java.util.Locale.getDefault());
        if (q.length() == 0) return conversationList;
        java.util.List<ConversationItem> result = new java.util.ArrayList<>();
        for (ConversationItem item : conversationList) {
            String name = item.name == null ? "" : item.name.toLowerCase(java.util.Locale.getDefault());
            if (name.contains(q)) result.add(item);
        }
        return result;
    }
    @Override public int getCount() { return visibleItems().size(); }
    @Override public Object getItem(int position) { return visibleItems().get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public android.view.View getView(final int position, android.view.View convertView, android.view.ViewGroup parent) {
        final ConversationItem item = visibleItems().get(position);

        android.widget.LinearLayout mainBlock = new android.widget.LinearLayout(MainActivity.this);
        mainBlock.setOrientation(android.widget.LinearLayout.VERTICAL);
        mainBlock.setBackgroundColor(selectedConversationUids.contains(item.otherUid) ? COLOR_BG_LIGHT_BLUE : android.graphics.Color.WHITE);

        android.widget.LinearLayout row = new android.widget.LinearLayout(MainActivity.this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(utils.dp(16), utils.dp(12), utils.dp(16), utils.dp(12));

        android.widget.FrameLayout avatarWrap = new android.widget.FrameLayout(MainActivity.this);
        android.widget.LinearLayout.LayoutParams avatarWrapParams = new android.widget.LinearLayout.LayoutParams(utils.dp(50), utils.dp(50));
        avatarWrapParams.setMargins(0, 0, utils.dp(14), 0);
        avatarWrap.setLayoutParams(avatarWrapParams);

        final android.widget.ImageView avatar = new android.widget.ImageView(MainActivity.this);
        avatar.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
        avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        avatarLoader.load(item.photoURL, item.name, avatar);
        avatarWrap.addView(avatar);

        if (item.isOnline) {
            android.view.View onlineDot = new android.view.View(MainActivity.this);
            android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
            dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dotBg.setColor(COLOR_GREEN);
            dotBg.setStroke(utils.dp(2), android.graphics.Color.WHITE);
            onlineDot.setBackground(dotBg);
            android.widget.FrameLayout.LayoutParams dotParams = new android.widget.FrameLayout.LayoutParams(utils.dp(14), utils.dp(14));
            dotParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
            avatarWrap.addView(onlineDot, dotParams);
        }

        avatarWrap.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { profilePreview.show(item.photoURL, item.name); }
        });

        android.widget.LinearLayout textCol = new android.widget.LinearLayout(MainActivity.this);
        textCol.setOrientation(android.widget.LinearLayout.VERTICAL);

        android.widget.LinearLayout topTextRow = new android.widget.LinearLayout(MainActivity.this);
        topTextRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);

        android.widget.TextView nameView = new android.widget.TextView(MainActivity.this);
        nameView.setText(item.isSelf ? item.name + " (You)" : item.name);
        nameView.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        nameView.setTextSize(16);
        nameView.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        nameView.setTextColor(COLOR_TEXT);
        topTextRow.addView(nameView, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

        if (item.isPinned) {
            android.widget.ImageView pinIcon = new android.widget.ImageView(MainActivity.this);
            pinIcon.setImageDrawable(new PinDrawable(COLOR_BLUE));
            pinIcon.setPadding(0, 0, utils.dp(6), 0);
            topTextRow.addView(pinIcon, 0, new android.widget.LinearLayout.LayoutParams(utils.dp(20), utils.dp(20)));
        }

        android.widget.TextView timeView = new android.widget.TextView(MainActivity.this);
        if (item.lastMessageTimestamp > 0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault());
            timeView.setText(sdf.format(new java.util.Date(item.lastMessageTimestamp)));
        }
        timeView.setTextSize(12);
        timeView.setTextColor(COLOR_MUTED);
        topTextRow.addView(timeView, new android.widget.LinearLayout.LayoutParams(-2, -2));

        android.widget.LinearLayout bottomTextRow = new android.widget.LinearLayout(MainActivity.this);
        bottomTextRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bottomTextRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams bottomParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
        bottomParams.topMargin = utils.dp(4);

        android.widget.TextView lastMsgView = new android.widget.TextView(MainActivity.this);
        if (item.isTyping) {
            lastMsgView.setText("Typing...");
            lastMsgView.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
            lastMsgView.setTextColor(COLOR_BLUE);
            lastMsgView.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        } else {
            String previewText;
            if (item.eventType != null && "reaction".equals(item.eventType)) {
                String actor = myUid.equals(item.eventActorId) ? "You" : (item.eventActorName == null ? "Someone" : item.eventActorName);
                String reacted = "audio".equals(item.eventMessageType)
                    ? "Audio message (" + durationFormatter.mmss(item.eventMessageDurationSeconds) + ")"
                    : (item.eventMessageText == null ? "message" : item.eventMessageText);
                if (reacted.length() > 15) reacted = reacted.substring(0, 15).trim() + "…";
                previewText = actor + " reacted " + (item.eventEmoji == null ? "" : item.eventEmoji) + " to " + reacted;
            } else if (item.isDeleted) {
                previewText = "This message was deleted.";
            } else if ("audio".equals(item.lastMessageType)) {
                previewText = "Audio message (" + durationFormatter.mmss(item.lastMessageDurationSeconds) + ")";
            } else {
                previewText = item.lastMessageText;
            }
            boolean hasPreview = item.isDeleted || (previewText != null && previewText.trim().length() > 0);
            if (!hasPreview) {
                bottomTextRow.setVisibility(android.view.View.GONE);
            }
            lastMsgView.setText(hasPreview ? previewText : "");
            lastMsgView.setTextColor(COLOR_MUTED);
            if (item.lastMessageIsMine && item.eventType == null && !item.isDeleted && previewText != null) {
                String status = item.lastMessageStatus == null ? "sent" : item.lastMessageStatus;
                String mark = "sent".equals(status) ? "✓" : "read".equals(status) ? "✓✓" : "✓✓";
                if ("pending".equals(status)) mark = "⏱";
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(mark + "  " + previewText);
                int markColor = "pending".equals(status) ? COLOR_AMBER : ("read".equals(status) ? COLOR_BLUE : COLOR_MUTED);
                sb.setSpan(new android.text.style.ForegroundColorSpan(markColor), 0, mark.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                lastMsgView.setText(sb);
            }
            lastMsgView.setTypeface(emojiTypeface, android.graphics.Typeface.NORMAL);
        }
        lastMsgView.setTypeface(emojiTypeface, android.graphics.Typeface.NORMAL);
        lastMsgView.setTextSize(14);
        lastMsgView.setMaxLines(1);
        lastMsgView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottomTextRow.addView(lastMsgView, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

        if (item.unreadCount > 0) {
            android.widget.TextView unreadBadge = new android.widget.TextView(MainActivity.this);
            unreadBadge.setText(String.valueOf(item.unreadCount));
            unreadBadge.setTextColor(android.graphics.Color.WHITE);
            unreadBadge.setBackground(ui.rounded(COLOR_BLUE, 12));
            unreadBadge.setTextSize(11);
            unreadBadge.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
            unreadBadge.setGravity(android.view.Gravity.CENTER);
            unreadBadge.setPadding(utils.dp(6), 0, utils.dp(6), 0);
            unreadBadge.setMinimumWidth(utils.dp(22));
            android.widget.LinearLayout.LayoutParams badgeParams = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, utils.dp(22));
            badgeParams.leftMargin = utils.dp(8);
            bottomTextRow.addView(unreadBadge, badgeParams);
        }

        textCol.addView(topTextRow);
        textCol.addView(bottomTextRow, bottomParams);

        row.addView(avatarWrap);
        row.addView(textCol, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

        row.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (suppressNextRowClick[0]) { suppressNextRowClick[0] = false; return; }
                if (!selectedConversationUids.isEmpty()) {
                    convoSelection.toggle(item.otherUid);
                    conversationsAdapterRef[0].notifyDataSetChanged();
                    return;
                }
                try {
                    // Opening the chat from Home only dismisses the OS
                    // notification for this chat. It does NOT touch read
                    // state (unreadCount / message status) in any way.
                    // ChatActivity is solely responsible for marking
                    // messages as read once the user is actually viewing them.
                    messageNotifier.cancelFor(item.otherUid);
                    android.content.Intent chatIntent = new android.content.Intent(MainActivity.this, Class.forName(getPackageName() + ".ChatActivity"));
                    chatIntent.putExtra("otherUid", item.otherUid);
                    startActivity(chatIntent);
                } catch (ClassNotFoundException e) {}
            }
        });

        row.setOnLongClickListener(new android.view.View.OnLongClickListener() {
            @Override
            public boolean onLongClick(android.view.View v) {
                if (selectedConversationUids.isEmpty()) convoSelection.enter(item.otherUid);
                else convoSelection.toggle(item.otherUid);
                pinActionBtn.setText(item.isPinned ? "Unpin" : "Pin");
                notifyDataSetChanged();
                return true;
            }
        });

        mainBlock.addView(row);
        android.view.View line = new android.view.View(MainActivity.this);
        line.setBackgroundColor(COLOR_DIVIDER);
        mainBlock.addView(line, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(1)));
        return mainBlock;
    }
};
conversationsListView.setAdapter(conversationsAdapterRef[0]);
homeSearchInput.addTextChangedListener(new android.text.TextWatcher() {
    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    @Override public void afterTextChanged(android.text.Editable e) {
        homeSearchQuery[0] = e.toString();
        conversationsAdapterRef[0].notifyDataSetChanged();
    }
});

callsAdapterRef[0] = new android.widget.BaseAdapter() {
    @Override public int getCount() { return callHistoryList.size(); }
    @Override public Object getItem(int position) { return callHistoryList.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public android.view.View getView(final int position, android.view.View convertView, android.view.ViewGroup parent) {
        final CallLogItem call = callHistoryList.get(position);
        boolean isMissed = "missed".equals(call.status)
            || "not_answered".equals(call.status)
            || "declined".equals(call.status)
            // An unanswered incoming call can be finalized as "ended" when
            // the caller hangs up before the recipient answers; keep it red.
            || ("incoming".equals(call.direction) && "ended".equals(call.status) && call.duration <= 0);

        android.widget.LinearLayout mainBlock = new android.widget.LinearLayout(MainActivity.this);
        mainBlock.setOrientation(android.widget.LinearLayout.VERTICAL);
        mainBlock.setBackgroundColor(call.callId != null && selectedCallLogIds.contains(call.callId) ? COLOR_BG_LIGHT_BLUE : android.graphics.Color.WHITE);

        android.widget.LinearLayout row = new android.widget.LinearLayout(MainActivity.this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(utils.dp(16), utils.dp(12), utils.dp(16), utils.dp(12));

        android.widget.ImageView avatar = new android.widget.ImageView(MainActivity.this);
        android.widget.LinearLayout.LayoutParams avatarParams = new android.widget.LinearLayout.LayoutParams(utils.dp(46), utils.dp(46));
        avatarParams.rightMargin = utils.dp(14);
        avatar.setLayoutParams(avatarParams);
        avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        avatarLoader.load(call.otherPhoto, call.otherName, avatar);
        avatar.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { profilePreview.show(call.otherPhoto, call.otherName); }
        });

        android.widget.LinearLayout textCol = new android.widget.LinearLayout(MainActivity.this);
        textCol.setOrientation(android.widget.LinearLayout.VERTICAL);

        android.widget.TextView nameView = new android.widget.TextView(MainActivity.this);
        nameView.setText(call.otherName);
        nameView.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        nameView.setTextSize(16);
        nameView.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        nameView.setTextColor(COLOR_TEXT);

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault());
        String subText = call.direction + " \u2022 " + call.status
            + (call.duration > 0 ? " (" + durationFormatter.format(call.duration) + ")" : "")
            + " \u2022 " + sdf.format(new java.util.Date(call.timestamp));
        android.widget.LinearLayout subRow = new android.widget.LinearLayout(MainActivity.this);
        subRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        subRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageView directionIcon = new android.widget.ImageView(MainActivity.this);
        directionIcon.setImageDrawable(new CallDirectionArrowDrawable("outgoing".equals(call.direction), isMissed ? COLOR_RED : COLOR_MUTED));
        subRow.addView(directionIcon, new android.widget.LinearLayout.LayoutParams(utils.dp(15), utils.dp(15)));
        android.widget.TextView subView = new android.widget.TextView(MainActivity.this);
        subView.setText(subText);
        subView.setTypeface(emojiTypeface);
        subView.setTextSize(12);
        subView.setTextColor(isMissed ? COLOR_RED : COLOR_MUTED);
        if (isMissed) subView.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        textCol.addView(nameView);
        subRow.addView(subView, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        textCol.addView(subRow);

        final android.widget.FrameLayout callBtn = new android.widget.FrameLayout(MainActivity.this);
        callBtn.setBackground(ui.rounded(COLOR_BG_LIGHT_BLUE, 20));
        android.widget.LinearLayout.LayoutParams callBtnParams = new android.widget.LinearLayout.LayoutParams(utils.dp(38), utils.dp(38));
        android.widget.ImageView callIcon = new android.widget.ImageView(MainActivity.this);
        callIcon.setImageResource(android.R.drawable.stat_sys_phone_call);
        callIcon.setColorFilter(COLOR_BLUE, android.graphics.PorterDuff.Mode.SRC_IN);
        android.widget.FrameLayout.LayoutParams callIconParams = new android.widget.FrameLayout.LayoutParams(utils.dp(20), utils.dp(20));
        callIconParams.gravity = android.view.Gravity.CENTER;
        callBtn.addView(callIcon, callIconParams);
        callBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { callLauncher.launch(call.otherUid, call.otherName, call.otherPhoto); }
        });

        row.addView(avatar);
        row.addView(textCol, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(callBtn, callBtnParams);

        row.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (!selectedCallLogIds.isEmpty()) { callSelection.toggle(call.callId); callsAdapterRef[0].notifyDataSetChanged(); return; }
            }
        });
        row.setOnLongClickListener(new android.view.View.OnLongClickListener() {
            @Override
            public boolean onLongClick(android.view.View v) {
                if (selectedCallLogIds.isEmpty()) callSelection.enter(call.callId); else callSelection.toggle(call.callId);
                notifyDataSetChanged();
                return true;
            }
        });

        mainBlock.addView(row);
        android.view.View line = new android.view.View(MainActivity.this);
        line.setBackgroundColor(COLOR_DIVIDER);
        mainBlock.addView(line, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(1)));
        return mainBlock;
    }
};
callsListView.setAdapter(callsAdapterRef[0]);

final android.widget.BaseAdapter friendsAdapter = new android.widget.BaseAdapter() {
    @Override public int getCount() { return friendsDisplayList.size(); }
    @Override public Object getItem(int position) { return friendsDisplayList.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public android.view.View getView(final int position, android.view.View convertView, android.view.ViewGroup parent) {
        final FriendEntryItem item = friendsDisplayList.get(position);

        android.widget.LinearLayout mainBlock = new android.widget.LinearLayout(MainActivity.this);
        mainBlock.setOrientation(android.widget.LinearLayout.VERTICAL);

        android.widget.LinearLayout row = new android.widget.LinearLayout(MainActivity.this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(utils.dp(16), utils.dp(12), utils.dp(16), utils.dp(12));

        android.widget.ImageView avatar = new android.widget.ImageView(MainActivity.this);
        android.widget.LinearLayout.LayoutParams avatarParams = new android.widget.LinearLayout.LayoutParams(utils.dp(46), utils.dp(46));
        avatarParams.rightMargin = utils.dp(14);
        avatar.setLayoutParams(avatarParams);
        avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        avatarLoader.load(item.photoURL, item.name, avatar);
        avatar.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { profilePreview.show(item.photoURL, item.name); }
        });

        android.widget.LinearLayout textCol = new android.widget.LinearLayout(MainActivity.this);
        textCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView nameView = new android.widget.TextView(MainActivity.this);
        nameView.setText(item.name);
        nameView.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        nameView.setTextSize(15);
        nameView.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        nameView.setTextColor(COLOR_TEXT);
        android.widget.TextView usernameView = new android.widget.TextView(MainActivity.this);
        usernameView.setText("@" + item.username);
        usernameView.setTypeface(emojiTypeface);
        usernameView.setTextSize(12);
        usernameView.setTextColor(COLOR_MUTED);
        textCol.addView(nameView);
        textCol.addView(usernameView);

        row.addView(avatar);
        row.addView(textCol, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

        android.widget.LinearLayout actionsRow = new android.widget.LinearLayout(MainActivity.this);
        actionsRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actionsRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        if ("friends".equals(item.subtabType)) {
            android.widget.Button msgBtn = new android.widget.Button(MainActivity.this);
            msgBtn.setText("Message");
            msgBtn.setAllCaps(false);
            msgBtn.setTextColor(android.graphics.Color.WHITE);
            msgBtn.setBackground(ui.rounded(COLOR_BLUE, 16));
            msgBtn.setTextSize(12);
            msgBtn.setPadding(utils.dp(14), 0, utils.dp(14), 0);
            msgBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { friendActions.openChat(item.otherUid); }
            });
            actionsRow.addView(msgBtn);
        } else if ("received".equals(item.subtabType)) {
            android.widget.Button acceptBtn = new android.widget.Button(MainActivity.this);
            acceptBtn.setText("Accept");
            acceptBtn.setAllCaps(false);
            acceptBtn.setTextColor(android.graphics.Color.WHITE);
            acceptBtn.setBackground(ui.rounded(COLOR_BLUE, 16));
            acceptBtn.setTextSize(12);
            acceptBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { friendActions.sendAccept(item.otherUid); }
            });
            android.widget.Button declineBtn = new android.widget.Button(MainActivity.this);
            declineBtn.setText("Decline");
            declineBtn.setAllCaps(false);
            declineBtn.setTextColor(COLOR_RED);
            declineBtn.setBackground(ui.outline(android.graphics.Color.parseColor("#FCA5A5"), 16));
            declineBtn.setTextSize(12);
            android.widget.LinearLayout.LayoutParams declineParams = new android.widget.LinearLayout.LayoutParams(-2, -2);
            declineParams.leftMargin = utils.dp(6);
            declineBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { friendActions.confirmCancelOrDecline(item.otherUid, true); }
            });
            actionsRow.addView(acceptBtn);
            actionsRow.addView(declineBtn, declineParams);
        } else if ("sent".equals(item.subtabType)) {
            android.widget.TextView pendingLabel = new android.widget.TextView(MainActivity.this);
            pendingLabel.setText("Pending");
            pendingLabel.setTextColor(COLOR_AMBER);
            pendingLabel.setTextSize(12);
            pendingLabel.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
            android.widget.Button cancelBtn = new android.widget.Button(MainActivity.this);
            cancelBtn.setText("Cancel");
            cancelBtn.setAllCaps(false);
            cancelBtn.setTextColor(COLOR_RED);
            cancelBtn.setBackground(ui.outline(android.graphics.Color.parseColor("#FCA5A5"), 16));
            cancelBtn.setTextSize(12);
            android.widget.LinearLayout.LayoutParams cancelParams = new android.widget.LinearLayout.LayoutParams(-2, -2);
            cancelParams.leftMargin = utils.dp(8);
            cancelBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { friendActions.confirmCancelOrDecline(item.otherUid, false); }
            });
            actionsRow.addView(pendingLabel);
            actionsRow.addView(cancelBtn, cancelParams);
        }

        row.addView(actionsRow);
        mainBlock.addView(row);
        android.view.View line = new android.view.View(MainActivity.this);
        line.setBackgroundColor(COLOR_DIVIDER);
        mainBlock.addView(line, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(1)));
        return mainBlock;
    }
};
friendsListView.setAdapter(friendsAdapter);

final android.widget.FrameLayout callPickerOverlay = new android.widget.FrameLayout(MainActivity.this);
callPickerOverlay.setBackgroundColor(android.graphics.Color.WHITE);
callPickerOverlay.setVisibility(android.view.View.GONE);
callPickerOverlay.setElevation(utils.dp(20));

android.widget.LinearLayout pickerRoot = new android.widget.LinearLayout(MainActivity.this);
pickerRoot.setOrientation(android.widget.LinearLayout.VERTICAL);
callPickerOverlay.addView(pickerRoot, new android.widget.FrameLayout.LayoutParams(-1, -1));

android.widget.LinearLayout pickerHeader = new android.widget.LinearLayout(MainActivity.this);
pickerHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
pickerHeader.setPadding(utils.dp(16), utils.dp(12), utils.dp(16), utils.dp(12));
pickerHeader.setBackgroundColor(android.graphics.Color.WHITE);

android.widget.TextView pickerBack = new android.widget.TextView(MainActivity.this);
pickerBack.setText("\u2039");
pickerBack.setTextSize(30);
pickerBack.setTextColor(COLOR_TEXT);
pickerBack.setPadding(0, 0, utils.dp(16), utils.dp(4));
pickerHeader.addView(pickerBack);

android.widget.TextView pickerTitle = new android.widget.TextView(MainActivity.this);
pickerTitle.setText("Call a Friend");
pickerTitle.setTextSize(18);
pickerTitle.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
pickerTitle.setTextColor(COLOR_TEXT);
pickerHeader.addView(pickerTitle);

pickerRoot.addView(pickerHeader, new android.widget.LinearLayout.LayoutParams(-1, -2));

final android.widget.ListView pickerList = new android.widget.ListView(MainActivity.this);
pickerList.setDivider(null);
pickerRoot.addView(pickerList, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));

final java.util.List<FriendEntryItem> callPickerList = new java.util.ArrayList<>();
final android.widget.BaseAdapter callPickerAdapter = new android.widget.BaseAdapter() {
    @Override public int getCount() { return callPickerList.size(); }
    @Override public Object getItem(int position) { return callPickerList.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
        final FriendEntryItem f = callPickerList.get(position);
        android.widget.LinearLayout row = new android.widget.LinearLayout(MainActivity.this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(utils.dp(16), utils.dp(12), utils.dp(16), utils.dp(12));

        android.widget.ImageView avatar = new android.widget.ImageView(MainActivity.this);
        android.widget.LinearLayout.LayoutParams ap = new android.widget.LinearLayout.LayoutParams(utils.dp(46), utils.dp(46));
        ap.rightMargin = utils.dp(14);
        avatar.setLayoutParams(ap);
        avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        avatarLoader.load(f.photoURL, f.name, avatar);

        android.widget.TextView nameView = new android.widget.TextView(MainActivity.this);
        nameView.setText(f.name);
        nameView.setTextSize(15);
        nameView.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        nameView.setTextColor(COLOR_TEXT);

        android.widget.FrameLayout callBtn = new android.widget.FrameLayout(MainActivity.this);
        callBtn.setBackground(ui.rounded(COLOR_BG_LIGHT_BLUE, 20));
        android.widget.LinearLayout.LayoutParams cbp = new android.widget.LinearLayout.LayoutParams(utils.dp(38), utils.dp(38));
        android.widget.ImageView callIcon = new android.widget.ImageView(MainActivity.this);
        callIcon.setImageResource(android.R.drawable.stat_sys_phone_call);
        callIcon.setColorFilter(COLOR_BLUE, android.graphics.PorterDuff.Mode.SRC_IN);
        android.widget.FrameLayout.LayoutParams cip = new android.widget.FrameLayout.LayoutParams(utils.dp(20), utils.dp(20));
        cip.gravity = android.view.Gravity.CENTER;
        callBtn.addView(callIcon, cip);
        callBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                callPickerOverlay.setVisibility(android.view.View.GONE);
                callLauncher.launch(f.otherUid, f.name, f.photoURL);
            }
        });

        row.addView(avatar);
        row.addView(nameView, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(callBtn, cbp);
        return row;
    }
};
pickerList.setAdapter(callPickerAdapter);

pickerBack.setOnClickListener(new android.view.View.OnClickListener() {
    @Override public void onClick(android.view.View v) { callPickerOverlay.setVisibility(android.view.View.GONE); }
});

fabCallPicker.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        callPickerList.clear();
        for (FriendEntryItem f : masterFriendsList) {
            if ("friends".equals(f.subtabType)) callPickerList.add(f);
        }
        callPickerAdapter.notifyDataSetChanged();
        callPickerOverlay.setVisibility(android.view.View.VISIBLE);
    }
});

rootOverlay.addView(callPickerOverlay, new android.widget.FrameLayout.LayoutParams(-1, -1));

try {
    String cachedJson = cachePrefs.getString("conversations_cache", null);
    if (cachedJson != null) {
        org.json.JSONArray arr = new org.json.JSONArray(cachedJson);
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject obj = arr.getJSONObject(i);
            ConversationItem item = new ConversationItem();
            item.otherUid = obj.optString("otherUid", null);
            item.name = obj.optString("name", null);
            item.photoURL = obj.optString("photoURL", null);
            item.lastMessageText = obj.isNull("lastMessageText") ? null : obj.optString("lastMessageText");
            item.lastMessageTimestamp = obj.optLong("lastMessageTimestamp");
            item.lastMessageStatus = obj.isNull("lastMessageStatus") ? null : obj.optString("lastMessageStatus");
            item.lastMessageIsMine = obj.optBoolean("lastMessageIsMine");
            item.isDeleted = obj.optBoolean("isDeleted");
            item.unreadCount = obj.optInt("unreadCount");
            item.isPinned = obj.optBoolean("isPinned");
            item.isSelf = obj.optBoolean("isSelf");
            item.lastMessageId = obj.optString("lastMessageId", null);
            item.isOnline = false;
            conversationList.add(item);
        }
        conversationsAdapterRef[0].notifyDataSetChanged();
    }
} catch (Exception e) {}

try {
    String cachedCallsJson = cachePrefs.getString("calls_cache", null);
    if (cachedCallsJson != null) {
        org.json.JSONArray arr = new org.json.JSONArray(cachedCallsJson);
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject obj = arr.getJSONObject(i);
            CallLogItem item = new CallLogItem();
            item.callId = obj.optString("callId", null);
            item.otherUid = obj.optString("otherUid", null);
            item.otherName = obj.optString("otherName", null);
            item.otherPhoto = obj.optString("otherPhoto", null);
            item.type = obj.optString("type", null);
            item.direction = obj.optString("direction", null);
            item.status = obj.optString("status", null);
            item.duration = obj.optLong("duration");
            item.timestamp = obj.optLong("timestamp");
            callHistoryList.add(item);
        }
        callsAdapterRef[0].notifyDataSetChanged();
    }
} catch (Exception e) {}

try {
    String cachedFriendsJson = cachePrefs.getString("friends_cache", null);
    if (cachedFriendsJson != null) {
        org.json.JSONArray arr = new org.json.JSONArray(cachedFriendsJson);
        masterFriendsList.clear();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject obj = arr.getJSONObject(i);
            FriendEntryItem item = new FriendEntryItem();
            item.otherUid = obj.optString("otherUid", null);
            item.name = obj.optString("name", null);
            item.photoURL = obj.optString("photoURL", null);
            item.username = obj.optString("username", null);
            item.subtabType = obj.optString("subtabType", "friends");
            masterFriendsList.add(item);
            if (currentFriendsSubtab[0].equals(item.subtabType)) friendsDisplayList.add(item);
        }
        friendsAdapter.notifyDataSetChanged();
    }
} catch (Exception e) {}

final com.google.firebase.database.DataSnapshot[] usersSnapHolder = new com.google.firebase.database.DataSnapshot[1];
final com.google.firebase.database.DataSnapshot[] relationshipsSnapHolder = new com.google.firebase.database.DataSnapshot[1];
final com.google.firebase.database.DataSnapshot[] conversationsSnapHolder = new com.google.firebase.database.DataSnapshot[1];
final com.google.firebase.database.DataSnapshot[] messagesSnapHolder = new com.google.firebase.database.DataSnapshot[1];
final com.google.firebase.database.DataSnapshot[] callHistorySnapHolder = new com.google.firebase.database.DataSnapshot[1];

class ScrollPreserver {
    int savedTop = 0;
    int savedPosition = 0;
    void save(android.widget.ListView lv) {
        savedPosition = lv.getFirstVisiblePosition();
        android.view.View c = lv.getChildAt(0);
        savedTop = (c == null) ? 0 : c.getTop();
    }
    void restore(final android.widget.ListView lv) {
        lv.post(new Runnable() {
            @Override public void run() { lv.setSelectionFromTop(savedPosition, savedTop); }
        });
    }
}
final ScrollPreserver scrollPreserver = new ScrollPreserver();

class MainMessageState {
    com.google.firebase.database.DataSnapshot latestVisible(com.google.firebase.database.DataSnapshot pair, String uid) {
        com.google.firebase.database.DataSnapshot latest = null;
        for (com.google.firebase.database.DataSnapshot msg : pair.getChildren()) {
            Boolean hidden = msg.child("deletedFor").child(uid).getValue(Boolean.class);
            if (hidden != null && hidden) continue;
            Long ts = msg.child("timestamp").getValue(Long.class);
            if (ts == null) continue;
            if (latest == null || ts > latest.child("timestamp").getValue(Long.class)) latest = msg;
        }
        return latest;
    }
    String statusFor(com.google.firebase.database.DataSnapshot msg, android.content.SharedPreferences prefs, String pairId) {
        String id = msg.getKey();
        try {
            String raw = prefs.getString("chat_pending_" + pairId, "[]");
            org.json.JSONArray a = new org.json.JSONArray(raw);
            for (int i = 0; i < a.length(); i++) if (id != null && id.equals(a.optString(i))) return "pending";
        } catch (Exception ignored) {}
        String status = msg.child("status").getValue(String.class);
        return status == null ? "sent" : status;
    }
}

class LocalChatCacheHelper {
    // Reads the truest "last message" for a pair straight from the chat
    // screen's own local cache (chat_cache_<pairId>), which ChatActivity
    // writes synchronously the instant a message is composed/sent — before
    // any network call. This is what lets Home show the correct preview
    // and clock icon immediately, online or offline, with no dependency
    // on whether Firebase's messages snapshot has synced that pair yet.
    ConversationItem readLatest(String pairId) {
        try {
            String cachedJson = cachePrefs.getString("chat_cache_" + pairId, null);
            if (cachedJson == null) return null;
            org.json.JSONArray arr = new org.json.JSONArray(cachedJson);

            org.json.JSONObject bestObj = null;
            long bestTs = -1;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                if (obj.optBoolean("deletedForMe")) continue;
                long ts = obj.optLong("timestamp");
                if (ts > bestTs) { bestTs = ts; bestObj = obj; }
            }
            if (bestObj == null) return null;

            ConversationItem out = new ConversationItem();
            out.lastMessageId = bestObj.optString("id", null);
            String senderId = bestObj.optString("senderId", null);
            boolean isDeletedEveryone = bestObj.optBoolean("isDeletedForEveryone");
            out.isDeleted = isDeletedEveryone;
            out.lastMessageText = isDeletedEveryone ? "This message was deleted." : (bestObj.isNull("text") ? null : bestObj.optString("text"));
            out.lastMessageTimestamp = bestTs;
            out.lastMessageIsMine = myUid.equals(senderId);
            out.lastMessageType = bestObj.optString("type", null);
            out.lastMessageDurationSeconds = bestObj.optInt("audioDurationSeconds", 0);

            String status = bestObj.isNull("status") ? "sent" : bestObj.optString("status");
            try {
                String pendingRaw = cachePrefs.getString("chat_pending_" + pairId, "[]");
                org.json.JSONArray pendingArr = new org.json.JSONArray(pendingRaw);
                for (int i = 0; i < pendingArr.length(); i++) {
                    if (out.lastMessageId != null && out.lastMessageId.equals(pendingArr.optString(i))) { status = "pending"; break; }
                }
            } catch (Exception ignored) {}
            out.lastMessageStatus = status;
            return out;
        } catch (Exception e) { return null; }
    }
}
final LocalChatCacheHelper localChatCache = new LocalChatCacheHelper();

final Runnable rebuildConversations = new Runnable() {
    @Override
    public void run() {
        if (usersSnapHolder[0] == null || relationshipsSnapHolder[0] == null) {
            // Case-one offline startup: Firebase may not have delivered the
            // users/relationships snapshots yet, but the chat screen has
            // already persisted the optimistic message locally. Keep the
            // cached home rows and merge their chat_cache_* values directly.
            boolean changed = false;
            for (ConversationItem cachedItem : conversationList) {
                if (cachedItem == null || cachedItem.otherUid == null) continue;
                String cachedPairId = cachedItem.isSelf
                    ? myUid + "_" + myUid
                    : (myUid.compareTo(cachedItem.otherUid) < 0
                        ? myUid + "_" + cachedItem.otherUid
                        : cachedItem.otherUid + "_" + myUid);
                ConversationItem localLatest = localChatCache.readLatest(cachedPairId);
                if (localLatest != null && localLatest.lastMessageTimestamp >= cachedItem.lastMessageTimestamp) {
                    cachedItem.lastMessageId = localLatest.lastMessageId;
                    cachedItem.lastMessageText = localLatest.lastMessageText;
                    cachedItem.lastMessageTimestamp = localLatest.lastMessageTimestamp;
                    cachedItem.lastMessageStatus = localLatest.lastMessageStatus;
                    cachedItem.lastMessageIsMine = localLatest.lastMessageIsMine;
                    cachedItem.lastMessageType = localLatest.lastMessageType;
                    cachedItem.lastMessageDurationSeconds = localLatest.lastMessageDurationSeconds;
                    cachedItem.eventType = localLatest.eventType;
                    cachedItem.eventActorId = localLatest.eventActorId;
                    cachedItem.eventActorName = localLatest.eventActorName;
                    cachedItem.eventEmoji = localLatest.eventEmoji;
                    cachedItem.eventMessageText = localLatest.eventMessageText;
                    cachedItem.eventMessageType = localLatest.eventMessageType;
                    cachedItem.eventMessageDurationSeconds = localLatest.eventMessageDurationSeconds;
                    cachedItem.isDeleted = localLatest.isDeleted;
                    changed = true;
                }
            }
            if (changed) conversationsAdapterRef[0].notifyDataSetChanged();
            return;
        }
        scrollPreserver.save(conversationsListView);
        final java.util.List<ConversationItem> previousConversationList = new java.util.ArrayList<>(conversationList);
        conversationList.clear();

        // Build the self-chat from Firebase when available, then merge the chat
        // screen's local cache so it remains visible while offline or during a
        // transient Firebase snapshot.
        String selfPairId = myUid + "_" + myUid;
        ConversationItem selfItem = null;
        if (messagesSnapHolder[0] != null) {
            com.google.firebase.database.DataSnapshot selfLatest = new MainMessageState().latestVisible(messagesSnapHolder[0].child(selfPairId), myUid);
            if (selfLatest != null && (selfLatest.child("text").getValue(String.class) != null || Boolean.TRUE.equals(selfLatest.child("isDeletedForEveryone").getValue(Boolean.class)))) {
                selfItem = new ConversationItem();
                selfItem.otherUid = myUid;
                selfItem.isSelf = true;
                selfItem.name = usersSnapHolder[0].child(myUid).child("name").getValue(String.class);
                selfItem.photoURL = usersSnapHolder[0].child(myUid).child("photoURL").getValue(String.class);
                selfItem.lastMessageText = Boolean.TRUE.equals(selfLatest.child("isDeletedForEveryone").getValue(Boolean.class)) ? "This message was deleted." : selfLatest.child("text").getValue(String.class);
                Long selfTs = selfLatest.child("timestamp").getValue(Long.class);
                selfItem.lastMessageTimestamp = selfTs == null ? 0 : selfTs;
                selfItem.lastMessageId = selfLatest.getKey();
                selfItem.lastMessageStatus = new MainMessageState().statusFor(selfLatest, cachePrefs, selfPairId);
                selfItem.lastMessageIsMine = true;
            }
        }
        ConversationItem localSelf = localChatCache.readLatest(selfPairId);
        if (localSelf != null && (selfItem == null || localSelf.lastMessageTimestamp >= selfItem.lastMessageTimestamp)) {
            if (selfItem == null) {
                selfItem = localSelf;
                selfItem.otherUid = myUid;
                selfItem.isSelf = true;
                selfItem.name = usersSnapHolder[0].child(myUid).child("name").getValue(String.class);
                selfItem.photoURL = usersSnapHolder[0].child(myUid).child("photoURL").getValue(String.class);
            } else {
                selfItem.lastMessageId = localSelf.lastMessageId;
                selfItem.lastMessageText = localSelf.lastMessageText;
                selfItem.lastMessageTimestamp = localSelf.lastMessageTimestamp;
                selfItem.lastMessageStatus = localSelf.lastMessageStatus;
                selfItem.lastMessageIsMine = true;
                selfItem.isDeleted = localSelf.isDeleted;
            }
        }
        if (selfItem != null) conversationList.add(selfItem);
        for (com.google.firebase.database.DataSnapshot relSnap : relationshipsSnapHolder[0].getChildren()) {
            String pairId = relSnap.getKey();
            if (pairId == null || !pairId.contains(myUid)) continue;
            String status = relSnap.child("status").getValue(String.class);
            if (!"friends".equals(status)) continue;

            String[] parts = pairId.split("_");
            if (parts.length < 2) continue;
            String otherUid = parts[0].equals(myUid) ? parts[1] : parts[0];
            if (otherUid.equals(myUid)) continue;

            com.google.firebase.database.DataSnapshot otherUserSnap = usersSnapHolder[0].child(otherUid);
            if (!otherUserSnap.exists()) continue;

            ConversationItem item = new ConversationItem();
            item.otherUid = otherUid;
            item.name = otherUserSnap.child("name").getValue(String.class);
            item.photoURL = otherUserSnap.child("photoURL").getValue(String.class);
            item.isPinned = pinnedChatUids.contains(otherUid);

            Boolean theirShowOnline = otherUserSnap.child("privacy").child("showOnline").getValue(Boolean.class);
            String stateVal = otherUserSnap.child("status").child("state").getValue(String.class);
            boolean theirShow = (theirShowOnline == null || theirShowOnline);
            item.isOnline = theirShow && myShowOnlinePriv[0] && "online".equals(stateVal);

            if (conversationsSnapHolder[0] != null) {
                com.google.firebase.database.DataSnapshot convSnap = conversationsSnapHolder[0].child(pairId);
                com.google.firebase.database.DataSnapshot lastMsgSnap = convSnap.child("lastMessage");

                item.lastMessageText = lastMsgSnap.child("text").getValue(String.class);
                Long ts = lastMsgSnap.child("timestamp").getValue(Long.class);
                item.lastMessageTimestamp = ts == null ? 0 : ts;
                item.lastMessageStatus = lastMsgSnap.child("status").getValue(String.class);
                item.lastMessageId = lastMsgSnap.child("messageId").getValue(String.class);
                item.lastMessageType = lastMsgSnap.child("type").getValue(String.class);
                Long lastDuration = lastMsgSnap.child("audioDurationSeconds").getValue(Long.class);
                item.lastMessageDurationSeconds = lastDuration == null ? 0 : lastDuration.intValue();
                item.eventType = lastMsgSnap.child("eventType").getValue(String.class);
                item.eventActorId = lastMsgSnap.child("eventActorId").getValue(String.class);
                item.eventActorName = item.eventActorId == null ? null : (myUid.equals(item.eventActorId)
                    ? usersSnapHolder[0].child(myUid).child("name").getValue(String.class) : item.name);
                item.eventEmoji = lastMsgSnap.child("eventEmoji").getValue(String.class);
                item.eventMessageText = lastMsgSnap.child("eventMessageText").getValue(String.class);
                item.eventMessageType = lastMsgSnap.child("eventMessageType").getValue(String.class);
                Long eventDuration = lastMsgSnap.child("eventMessageDurationSeconds").getValue(Long.class);
                item.eventMessageDurationSeconds = eventDuration == null ? 0 : eventDuration.intValue();
                String senderId = lastMsgSnap.child("senderId").getValue(String.class);
                item.lastMessageIsMine = myUid.equals(senderId);
                Boolean deletedFlag = lastMsgSnap.child("isDeletedForEveryone").getValue(Boolean.class);
                item.isDeleted = deletedFlag != null && deletedFlag;

                Long unread = convSnap.child("unreadCount").child(myUid).getValue(Long.class);
                item.unreadCount = unread == null ? 0 : unread.intValue();

                Boolean typingFlag = convSnap.child("typing").child(otherUid).getValue(Boolean.class);
                item.isTyping = typingFlag != null && typingFlag;
            }

            if (messagesSnapHolder[0] != null) {
                com.google.firebase.database.DataSnapshot latest = new MainMessageState().latestVisible(messagesSnapHolder[0].child(pairId), myUid);
                if (latest != null) {
                    item.lastMessageId = latest.getKey();
                    item.lastMessageText = latest.child("text").getValue(String.class);
                    Long latestTs = latest.child("timestamp").getValue(Long.class);
                    if (latestTs != null && !"reaction".equals(item.eventType)) item.lastMessageTimestamp = latestTs;
                    String latestSender = latest.child("senderId").getValue(String.class);
                    item.lastMessageIsMine = myUid.equals(latestSender);
                    item.lastMessageStatus = new MainMessageState().statusFor(latest, cachePrefs, pairId);
                    item.lastMessageType = latest.child("type").getValue(String.class);
                    Long latestDuration = latest.child("audioDurationSeconds").getValue(Long.class);
                    item.lastMessageDurationSeconds = latestDuration == null ? 0 : latestDuration.intValue();
                    Boolean latestDeleted = latest.child("isDeletedForEveryone").getValue(Boolean.class);
                    item.isDeleted = latestDeleted != null && latestDeleted;
                }
            }

            // This merge is intentionally outside the Firebase conversations
            // check. ChatActivity writes chat_cache_<pairId> and
            // chat_pending_<pairId> immediately, so Home can show an outgoing
            // offline message and its clock even before Firebase has a snapshot.
            ConversationItem localLatest = localChatCache.readLatest(pairId);
            if (localLatest != null && localLatest.lastMessageTimestamp >= item.lastMessageTimestamp) {
                item.lastMessageId = localLatest.lastMessageId;
                item.lastMessageText = localLatest.lastMessageText;
                item.lastMessageTimestamp = localLatest.lastMessageTimestamp;
                item.lastMessageStatus = localLatest.lastMessageStatus;
                item.lastMessageIsMine = localLatest.lastMessageIsMine;
                item.lastMessageType = localLatest.lastMessageType;
                item.lastMessageDurationSeconds = localLatest.lastMessageDurationSeconds;
                item.eventType = localLatest.eventType;
                item.eventActorId = localLatest.eventActorId;
                item.eventActorName = localLatest.eventActorName;
                item.eventEmoji = localLatest.eventEmoji;
                item.eventMessageText = localLatest.eventMessageText;
                item.eventMessageType = localLatest.eventMessageType;
                item.eventMessageDurationSeconds = localLatest.eventMessageDurationSeconds;
                item.isDeleted = localLatest.isDeleted;
            }

            // Delivery is separate from reading. Showing the Home list may
            // change sent -> delivered, but it must never write read.
            if (!item.lastMessageIsMine && "sent".equals(item.lastMessageStatus) && item.lastMessageId != null) {
                rootRef.child("messages").child(pairId).child(item.lastMessageId).child("status").setValue("delivered");
            }

            Long hideUntil = deletedChatUntil.get(otherUid);
            if (hideUntil != null && item.lastMessageTimestamp <= hideUntil) continue;

            conversationList.add(item);
        }

        // Firebase can briefly publish an empty cached snapshot while the
        // device is offline/reconnecting. Do not make Home flash empty: retain
        // the last rendered rows until a real friend/message snapshot replaces
        // them. This also prevents the self-chat row from disappearing.
        if (conversationList.isEmpty() && !previousConversationList.isEmpty()
                && relationshipsSnapHolder[0] != null
                && relationshipsSnapHolder[0].getChildrenCount() == 0) {
            conversationList.addAll(previousConversationList);
        }

        java.util.Collections.sort(conversationList, new java.util.Comparator<ConversationItem>() {
            @Override
            public int compare(ConversationItem a, ConversationItem b) {
                if (a.isPinned != b.isPinned) return a.isPinned ? -1 : 1;
                return Long.compare(b.lastMessageTimestamp, a.lastMessageTimestamp);
            }
        });

        conversationsAdapterRef[0].notifyDataSetChanged();
        scrollPreserver.restore(conversationsListView);

        int realCount = 0;
        for (ConversationItem c : conversationList) if (!c.isSelf) realCount++;
        emptyHomeLabel.setVisibility(realCount == 0 ? android.view.View.VISIBLE : android.view.View.GONE);

        cacheExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (ConversationItem item : conversationList) {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("otherUid", item.otherUid);
                        obj.put("name", item.name);
                        obj.put("photoURL", item.photoURL);
                        obj.put("lastMessageText", item.lastMessageText);
                        obj.put("lastMessageTimestamp", item.lastMessageTimestamp);
                        obj.put("lastMessageStatus", item.lastMessageStatus);
                        obj.put("lastMessageIsMine", item.lastMessageIsMine);
                        obj.put("lastMessageType", item.lastMessageType);
                        obj.put("lastMessageDurationSeconds", item.lastMessageDurationSeconds);
                        obj.put("eventType", item.eventType);
                        obj.put("eventActorId", item.eventActorId);
                        obj.put("eventActorName", item.eventActorName);
                        obj.put("eventEmoji", item.eventEmoji);
                        obj.put("eventMessageText", item.eventMessageText);
                        obj.put("eventMessageType", item.eventMessageType);
                        obj.put("eventMessageDurationSeconds", item.eventMessageDurationSeconds);
                        obj.put("lastMessageId", item.lastMessageId);
                        obj.put("isDeleted", item.isDeleted);
                        obj.put("isPinned", item.isPinned);
                        obj.put("isSelf", item.isSelf);
                        obj.put("unreadCount", item.unreadCount);
                        arr.put(obj);
                    }
                    cachePrefs.edit().putString("conversations_cache", arr.toString()).apply();
                } catch (Exception e) {}
            }
        });
    }
};

final Runnable rebuildFriends = new Runnable() {
    @Override
    public void run() {
        friendsDisplayList.clear();
        int receivedCount = 0;

        if (usersSnapHolder[0] != null && relationshipsSnapHolder[0] != null) {
            masterFriendsList.clear();
            for (com.google.firebase.database.DataSnapshot relSnap : relationshipsSnapHolder[0].getChildren()) {
                String pairId = relSnap.getKey();
                if (pairId == null || !pairId.contains(myUid)) continue;
                String status = relSnap.child("status").getValue(String.class);
                String requester = relSnap.child("requester").getValue(String.class);
                String recipient = relSnap.child("recipient").getValue(String.class);
                String[] parts = pairId.split("_");
                if (parts.length < 2) continue;
                String otherUid = parts[0].equals(myUid) ? parts[1] : parts[0];
                if ("pending".equals(status) && myUid.equals(recipient)) receivedCount++;

                com.google.firebase.database.DataSnapshot otherUserSnap = usersSnapHolder[0].child(otherUid);
                if (!otherUserSnap.exists()) continue;

                String derivedSubtab = null;
                if ("friends".equals(status)) derivedSubtab = "friends";
                else if ("pending".equals(status) && myUid.equals(recipient)) derivedSubtab = "received";
                else if ("pending".equals(status) && myUid.equals(requester)) derivedSubtab = "sent";

                if (derivedSubtab != null) {
                    FriendEntryItem item = new FriendEntryItem();
                    item.otherUid = otherUid;
                    item.name = otherUserSnap.child("name").getValue(String.class);
                    item.photoURL = otherUserSnap.child("photoURL").getValue(String.class);
                    item.username = otherUserSnap.child("username").getValue(String.class);
                    item.subtabType = derivedSubtab;
                    masterFriendsList.add(item);
                }
            }
            cacheExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        org.json.JSONArray arr = new org.json.JSONArray();
                        for (FriendEntryItem item : masterFriendsList) {
                            org.json.JSONObject obj = new org.json.JSONObject();
                            obj.put("otherUid", item.otherUid);
                            obj.put("name", item.name);
                            obj.put("photoURL", item.photoURL);
                            obj.put("username", item.username);
                            obj.put("subtabType", item.subtabType);
                            arr.put(obj);
                        }
                        cachePrefs.edit().putString("friends_cache", arr.toString()).apply();
                    } catch (Exception e) {}
                }
            });
        } else {
            for (FriendEntryItem item : masterFriendsList) if ("received".equals(item.subtabType)) receivedCount++;
        }

        for (FriendEntryItem item : masterFriendsList) {
            if (currentFriendsSubtab[0].equals(item.subtabType)) friendsDisplayList.add(item);
        }

        friendsAdapter.notifyDataSetChanged();
        String emptyMsg = "friends".equals(currentFriendsSubtab[0]) ? "No friends yet. Discover people to connect with!"
            : "received".equals(currentFriendsSubtab[0]) ? "No pending requests received." : "No pending requests sent.";
        emptyFriendsLabel.setText(emptyMsg);
        emptyFriendsLabel.setVisibility(friendsDisplayList.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);

        if (receivedCount > 0 && currentTabIndex[0] != 2) {
            friendsNavBadge.setText(receivedCount > 9 ? "9+" : String.valueOf(receivedCount));
            friendsNavBadge.setVisibility(android.view.View.VISIBLE);
        } else {
            friendsNavBadge.setVisibility(android.view.View.GONE);
        }
    }
};
rebuildFriendsRef[0] = rebuildFriends;

final Runnable rebuildCallHistory = new Runnable() {
    @Override
    public void run() {
        scrollPreserver.save(callsListView);
        callHistoryList.clear();
        if (callHistorySnapHolder[0] != null) {
            for (com.google.firebase.database.DataSnapshot callSnap : callHistorySnapHolder[0].getChildren()) {
                CallLogItem item = new CallLogItem();
                item.callId = callSnap.getKey();
                item.otherUid = callSnap.child("otherUserId").getValue(String.class);
                item.otherName = callSnap.child("otherUserName").getValue(String.class);
                if (item.otherName == null || item.otherName.trim().length() == 0) {
                    item.otherName = callSnap.child("otherName").getValue(String.class);
                }
                // Older CallActivity versions sometimes wrote only the UID.
                // Resolve the display name from the current users snapshot so
                // those records do not appear as the generic Mixi Chat row.
                if ((item.otherName == null || item.otherName.trim().length() == 0)
                        && item.otherUid != null && usersSnapHolder[0] != null) {
                    item.otherName = usersSnapHolder[0].child(item.otherUid).child("name").getValue(String.class);
                }
                if (item.otherName == null || item.otherName.trim().length() == 0) item.otherName = "User";
                item.type = callSnap.child("type").getValue(String.class);
                item.direction = callSnap.child("direction").getValue(String.class);
                item.status = callSnap.child("status").getValue(String.class);
                Long dur = callSnap.child("duration").getValue(Long.class);
                item.duration = dur == null ? 0 : dur;
                Long ts = callSnap.child("timestamp").getValue(Long.class);
                item.timestamp = ts == null ? 0 : ts;
                if (usersSnapHolder[0] != null && item.otherUid != null) {
                    com.google.firebase.database.DataSnapshot userSnap = usersSnapHolder[0].child(item.otherUid);
                    if (item.otherPhoto == null || item.otherPhoto.trim().length() == 0) item.otherPhoto = userSnap.child("photoURL").getValue(String.class);
                    if (item.otherName == null || item.otherName.trim().length() == 0) item.otherName = userSnap.child("name").getValue(String.class);
                }
                callHistoryList.add(item);
            }
        }
        java.util.Collections.sort(callHistoryList, new java.util.Comparator<CallLogItem>() {
            @Override public int compare(CallLogItem a, CallLogItem b) { return Long.compare(b.timestamp, a.timestamp); }
        });
        callsAdapterRef[0].notifyDataSetChanged();
        scrollPreserver.restore(callsListView);
        emptyCallsLabel.setVisibility(callHistoryList.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);

        cacheExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (CallLogItem item : callHistoryList) {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("callId", item.callId);
                        obj.put("otherUid", item.otherUid);
                        obj.put("otherName", item.otherName);
                        obj.put("otherPhoto", item.otherPhoto);
                        obj.put("type", item.type);
                        obj.put("direction", item.direction);
                        obj.put("status", item.status);
                        obj.put("duration", item.duration);
                        obj.put("timestamp", item.timestamp);
                        arr.put(obj);
                    }
                    cachePrefs.edit().putString("calls_cache", arr.toString()).apply();
                } catch (Exception e) {}
            }
        });
    }
};

rootRef.child("users").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        usersSnapHolder[0] = snapshot;
        rebuildConversations.run();
        rebuildFriends.run();
        rebuildCallHistory.run();
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

rootRef.child("relationships").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        relationshipsSnapHolder[0] = snapshot;
        for (com.google.firebase.database.DataSnapshot relSnap : snapshot.getChildren()) {
            String pairId = relSnap.getKey();
            if (pairId == null || !pairId.contains(myUid)) continue;
            String status = relSnap.child("status").getValue(String.class);
            String recipient = relSnap.child("recipient").getValue(String.class);
            String requester = relSnap.child("requester").getValue(String.class);
            String[] pairParts = pairId.split("_");
            if (pairParts.length < 2) continue;
            String otherUid = pairParts[0].equals(myUid) ? pairParts[1] : pairParts[0];
            if ("pending".equals(status) && myUid.equals(recipient)) {
                boolean alreadyNotified = cachePrefs.getBoolean("friend_request_notified_" + pairId, false);
                if (!alreadyNotified) {
                    com.google.firebase.database.DataSnapshot userSnap = usersSnapHolder[0] == null ? null : usersSnapHolder[0].child(otherUid);
                    String senderName = userSnap == null ? "Someone" : userSnap.child("name").getValue(String.class);
                    String senderPhoto = userSnap == null ? null : userSnap.child("photoURL").getValue(String.class);
                    friendRequestNotifier.show(otherUid, senderName == null ? "Someone" : senderName, senderPhoto, pairId);
                    cachePrefs.edit().putBoolean("friend_request_notified_" + pairId, true).apply();
                }
            } else {
                friendRequestNotifier.cancelFor(pairId);
                cachePrefs.edit().putBoolean("friend_request_notified_" + pairId, false).apply();
            }
        }
        rebuildConversations.run();
        rebuildFriends.run();
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

rootRef.child("conversations").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        conversationsSnapHolder[0] = snapshot;
        rebuildConversations.run();
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

// Fast unread badge refresh. A child event is delivered as soon as Firebase
// changes conversations/<pairId>/unreadCount, so Home does not wait for a
// slower full conversations snapshot/rebuild.
rootRef.child("conversations").addChildEventListener(new com.google.firebase.database.ChildEventListener() {
    void refreshUnread(com.google.firebase.database.DataSnapshot pairSnap) {
        String pairId = pairSnap.getKey();
        if (pairId == null || !pairId.contains(myUid)) return;
        String[] parts = pairId.split("_");
        if (parts.length < 2) return;
        String otherUid = parts[0].equals(myUid) ? parts[1] : parts[0];
        Long unread = pairSnap.child("unreadCount").child(myUid).getValue(Long.class);
        int count = unread == null ? 0 : unread.intValue();
        for (ConversationItem item : conversationList) {
            if (otherUid.equals(item.otherUid)) {
                item.unreadCount = count;
                conversationsAdapterRef[0].notifyDataSetChanged();
                return;
            }
        }
    }
    @Override public void onChildAdded(com.google.firebase.database.DataSnapshot snap, String prev) { refreshUnread(snap); }
    @Override public void onChildChanged(com.google.firebase.database.DataSnapshot snap, String prev) { refreshUnread(snap); }
    @Override public void onChildRemoved(com.google.firebase.database.DataSnapshot snap) {}
    @Override public void onChildMoved(com.google.firebase.database.DataSnapshot snap, String prev) {}
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

rootRef.child("messages").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        messagesSnapHolder[0] = snapshot;
        rebuildConversations.run();
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

// ---------- FIX: live-refresh the Home list when ChatActivity flips a
// message from "pending" (clock icon) to sent, so the clock icon updates
// immediately instead of waiting for the next Firebase snapshot. This does
// NOT touch read state — it only re-reads the local chat_pending_* keys
// that statusFor() already checks. ----------
final android.content.SharedPreferences.OnSharedPreferenceChangeListener pendingStatusListener =
    new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(android.content.SharedPreferences prefs, String key) {
            if (key != null && (key.startsWith("chat_pending_") || key.startsWith("chat_cache_"))) {
                // ChatActivity persists the optimistic message and its pending
                // id separately. Listening to both keys is required when the
                // app was launched already offline: in that case Firebase may
                // never emit a conversation/message snapshot to trigger a
                // normal rebuild.
                rebuildConversations.run();
            }
        }
    };
cachePrefs.registerOnSharedPreferenceChangeListener(pendingStatusListener);

final com.google.firebase.auth.FirebaseUser incomingCallUser = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
if (incomingCallUser != null) {
    final String incomingCallUid = incomingCallUser.getUid();
    final java.util.Set<String> mainHandledCallIds = new java.util.HashSet<>();
    rootRef.child("calls").orderByChild("receiverId").equalTo(incomingCallUid)
        .addChildEventListener(new com.google.firebase.database.ChildEventListener() {
            @Override public void onChildAdded(com.google.firebase.database.DataSnapshot snap, String prev) { handle(snap); }
            @Override public void onChildChanged(com.google.firebase.database.DataSnapshot snap, String prev) { handle(snap); }
            void handle(com.google.firebase.database.DataSnapshot snap) {
                String status = snap.child("status").getValue(String.class);
                String id = snap.getKey();
                if (id == null) return;
                if (!"ringing".equals(status) && !"calling".equals(status)) {
                    try { ((android.app.NotificationManager)getSystemService(android.content.Context.NOTIFICATION_SERVICE)).cancel(CALL_NOTIFICATION_BASE_ID + Math.abs(id.hashCode())); } catch (Throwable ignored) {}
                    mainHandledCallIds.remove(id); return;
                }
                // A call may first be observed while the app is foreground,
                // then continue ringing after the user switches to YouTube or
                // another app. Re-enter the handler while backgrounded so the
                // notification cannot be lost after the first Firebase event.
                // Window focus is the reliable signal for this activity:
                // when the user switches to YouTube or another app it becomes
                // false, so the call notification path is always retained.
                boolean appVisibleNow = getWindow().getDecorView().hasWindowFocus();
                try {
                    android.app.ActivityManager am = (android.app.ActivityManager)getSystemService(android.content.Context.ACTIVITY_SERVICE);
                    android.app.ActivityManager.RunningAppProcessInfo processInfo = new android.app.ActivityManager.RunningAppProcessInfo();
                    android.app.ActivityManager.getMyMemoryState(processInfo);
                    appVisibleNow = appVisibleNow || processInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                } catch (Throwable ignored) {}
                if (mainHandledCallIds.add(id) || !appVisibleNow) {
                    final String callerUid = snap.child("callerId").getValue(String.class);
                    String callerNameValue = snap.child("callerName").getValue(String.class);
                    if (callerNameValue == null || callerNameValue.trim().length() == 0) callerNameValue = snap.child("otherUserName").getValue(String.class);
                    if ((callerNameValue == null || callerNameValue.trim().length() == 0) && callerUid != null) {
                        callerNameValue = snap.child("name").getValue(String.class);
                    }
                    // The call payload may not contain a name when Firebase Auth
                    // displayName is empty. Resolve the caller from the already
                    // synced users snapshot before falling back to User.
                    if ((callerNameValue == null || callerNameValue.trim().length() == 0)
                            && callerUid != null && usersSnapHolder[0] != null) {
                        callerNameValue = usersSnapHolder[0].child(callerUid).child("name").getValue(String.class);
                    }
                    final String callerName = (callerNameValue == null || callerNameValue.trim().length() == 0) ? "User" : callerNameValue;
                    String callerPhotoValue = snap.child("callerPhoto").getValue(String.class);
                    if (callerPhotoValue == null || callerPhotoValue.trim().length() == 0) callerPhotoValue = snap.child("otherUserPhoto").getValue(String.class);
                    final String callerPhoto = callerPhotoValue;
                    try {
                        android.content.Intent accept = new android.content.Intent(MainActivity.this, MainActivity.class);
                        accept.setAction("ACCEPT_CALL");
                        accept.putExtra("callId", id);
                        accept.putExtra("otherUid", callerUid);
                        accept.putExtra("otherName", callerName); accept.putExtra("otherPhoto", callerPhoto);
                        accept.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);

                        android.content.Intent decline = new android.content.Intent(MainActivity.this, MainActivity.class);
                        decline.setAction("DECLINE_CALL");
                        decline.putExtra("callId", id);
                        decline.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);

                        int acceptFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE;
                        android.app.PendingIntent acceptPi = android.app.PendingIntent.getActivity(MainActivity.this, (id + "_accept").hashCode(), accept, acceptFlags);
                        android.app.PendingIntent declinePi = android.app.PendingIntent.getActivity(MainActivity.this, (id + "_decline").hashCode(), decline, acceptFlags);
                        androidx.core.app.NotificationCompat.Builder callBuilder = new androidx.core.app.NotificationCompat.Builder(MainActivity.this, CALL_NOTIF_CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.sym_action_call)
                            .setContentTitle(callerName == null ? "Incoming call" : callerName)
                            .setContentText("Incoming call")
                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
                            .setOngoing(true).setAutoCancel(false)
                            .setFullScreenIntent(acceptPi, true)
                            .addAction(new androidx.core.app.NotificationCompat.Action(android.R.drawable.ic_menu_call, "Accept", acceptPi))
                            .addAction(new androidx.core.app.NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePi));
                        // Treat every activity in this application (Home, Calls,
                        // Friends, Settings, etc.) as foreground. In that case the
                        // call UI is the notification surface, not an OS notification.
                        boolean appIsVisible = appVisibleNow;
                        if (!appIsVisible) {
                            android.app.NotificationManager callNm = (android.app.NotificationManager)getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                            callNm.notify(CALL_NOTIFICATION_BASE_ID + Math.abs(id.hashCode()), callBuilder.build());
                        } else {
                            try {
                                android.app.NotificationManager callNm = (android.app.NotificationManager)getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                                callNm.cancel(CALL_NOTIFICATION_BASE_ID + Math.abs(id.hashCode()));
                            } catch (Throwable ignored) {}
                        }

                        // Preserve foreground behavior only when Home is actually
                        // visible; background calls remain notification-only.
                        if (appIsVisible) {
                            try { ((android.app.NotificationManager)getSystemService(android.content.Context.NOTIFICATION_SERVICE)).cancel(CALL_NOTIFICATION_BASE_ID + Math.abs(id.hashCode())); } catch (Throwable ignored) {}
                            android.content.Intent foregroundCall = new android.content.Intent(MainActivity.this, Class.forName(getPackageName() + ".CallActivity"));
                            foregroundCall.putExtra("incoming", true); foregroundCall.putExtra("callId", id);
                            foregroundCall.putExtra("otherUid", callerUid); foregroundCall.putExtra("otherName", callerName); foregroundCall.putExtra("otherPhoto", callerPhoto);
                            foregroundCall.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(foregroundCall);
                        }
                    } catch (Exception ignored) {}
                }
            }
            @Override public void onChildRemoved(com.google.firebase.database.DataSnapshot snap) {}
            @Override public void onChildMoved(com.google.firebase.database.DataSnapshot snap, String prev) {}
            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
        });
}

rootRef.child("callHistory").child(myUid).addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        callHistorySnapHolder[0] = snapshot;
        rebuildCallHistory.run();
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

android.view.View.OnClickListener friendsSubtabSwitcher = new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        if (v == subtabFriendsBtn) currentFriendsSubtab[0] = "friends";
        else if (v == subtabReceivedBtn) currentFriendsSubtab[0] = "received";
        else currentFriendsSubtab[0] = "sent";

        subtabStyler.style(subtabFriendsBtn, "Friends", "friends".equals(currentFriendsSubtab[0]));
        subtabStyler.style(subtabReceivedBtn, "Received", "received".equals(currentFriendsSubtab[0]));
        subtabStyler.style(subtabSentBtn, "Sent", "sent".equals(currentFriendsSubtab[0]));
        rebuildFriends.run();
    }
};
subtabFriendsBtn.setOnClickListener(friendsSubtabSwitcher);
subtabReceivedBtn.setOnClickListener(friendsSubtabSwitcher);
subtabSentBtn.setOnClickListener(friendsSubtabSwitcher);

// ---------- FIX #16-23: stash cross-method state on the decor view so
// onBackPressed (a separate method) can reach it. This is the standard
// Android workaround for sharing dynamically-created onCreate state
// with other lifecycle callbacks when no class field can be declared. ----------
final java.util.Map<String, Object> backStateBag = new java.util.HashMap<>();
backStateBag.put("selectedConversationUid", selectedConversationUid);
backStateBag.put("selectedCallLogId", selectedCallLogId);
backStateBag.put("currentTabIndex", currentTabIndex);
backStateBag.put("currentFriendsSubtab", currentFriendsSubtab);
backStateBag.put("resetFriendsSubtab", new Runnable() {
    @Override public void run() {
        currentFriendsSubtab[0] = "friends";
        subtabStyler.style(subtabFriendsBtn, "Friends", true);
        subtabStyler.style(subtabReceivedBtn, "Received", false);
        subtabStyler.style(subtabSentBtn, "Sent", false);
        rebuildFriends.run();
    }
});
backStateBag.put("lastBackPressTime", lastBackPressTime);
backStateBag.put("exitConvoSelection", new Runnable() { @Override public void run() { convoSelection.exit(); } });
backStateBag.put("exitCallSelection", new Runnable() { @Override public void run() { callSelection.exit(); } });
backStateBag.put("goHomeTab", new Runnable() { @Override public void run() { tabSwitcher.switchTab(0, true); } });
backStateBag.put("callPickerOverlay", callPickerOverlay);
// Keep a strong reference so the SharedPreferences listener isn't garbage
// collected (Android holds only a weak reference internally).
backStateBag.put("pendingStatusListener", pendingStatusListener);
getWindow().getDecorView().setTag(backStateBag);





// ===== onBackPressed code =====

Object tagObj = getWindow().getDecorView().getTag();
if (tagObj instanceof java.util.Map) {
    java.util.Map<String, Object> bag = (java.util.Map<String, Object>) tagObj;

    android.view.View callPickerOverlay = (android.view.View) bag.get("callPickerOverlay");
    if (callPickerOverlay != null && callPickerOverlay.getVisibility() == android.view.View.VISIBLE) {
        callPickerOverlay.setVisibility(android.view.View.GONE);
        return;
    }

    String[] selectedConversationUid = (String[]) bag.get("selectedConversationUid");
    String[] selectedCallLogId = (String[]) bag.get("selectedCallLogId");
    int[] currentTabIndex = (int[]) bag.get("currentTabIndex");
    long[] lastBackPressTime = (long[]) bag.get("lastBackPressTime");
    Runnable exitConvoSelection = (Runnable) bag.get("exitConvoSelection");
    Runnable exitCallSelection = (Runnable) bag.get("exitCallSelection");
    Runnable goHomeTab = (Runnable) bag.get("goHomeTab");

    if (selectedConversationUid[0] != null) {
        exitConvoSelection.run();
    } else if (selectedCallLogId[0] != null) {
        exitCallSelection.run();
    } else if (currentTabIndex[0] != 0) {
        goHomeTab.run();
    } else {
        long now = System.currentTimeMillis();
        if (now - lastBackPressTime[0] < 2000) {
            finishAffinity();
        } else {
            lastBackPressTime[0] = now;
            android.widget.Toast.makeText(MainActivity.this, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
} else {
    super.onBackPressed();
}
