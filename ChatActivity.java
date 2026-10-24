// ===== onCreate code =====




// ============================================================
// MIXI CHAT — ChatActivity (Delta Sync + Send-First build)
// Paste into: Chat screen -> onCreate -> Add Source Directly
//
// CHANGES IN THIS VERSION (everything else is unchanged):
//  1. keepSynced(true) on the FULL message history is REMOVED.
//  2. DELTA SYNC: every write to a message now also sets "updatedAt"
//     (server time). On open, the chat shows the local cache instantly and
//     asks Firebase ONLY for messages with updatedAt >= lastSync
//     (new messages, reactions, edits, deletes, status changes).
//     First-ever open (no cache) falls back to limitToLast(60) once.
//  3. lastSync is saved together with the cache (same commit), and only
//     after the cache has been merged, so cache and lastSync can never
//     disagree.
//  4. relationships listener now reads only this pair (not the whole node).
//  5. users/{otherUid} is now read as small separate fields, not the whole node.
//  6. Secondary listeners attach AFTER the message listeners so nothing
//     competes with sending.
//  7. FIX: onChildRemoved on a limitToLast query also fires when an old
//     message is just pushed out of the window; it is now verified against
//     the server before marking a message deleted.
//  8. FIX: markRead waits until your privacy settings are loaded.
//
// REQUIRED in Firebase Realtime Database rules (under "messages"):
//   "messages": { "$pairId": { ".indexOn": ["updatedAt"] } }
// ============================================================

try {
    com.google.firebase.database.FirebaseDatabase.getInstance().setPersistenceEnabled(true);
} catch (Throwable t) {}

final com.google.firebase.auth.FirebaseAuth firebaseAuth = com.google.firebase.auth.FirebaseAuth.getInstance();
final com.google.firebase.auth.FirebaseUser currentUser = firebaseAuth.getCurrentUser();

if (currentUser == null) {
    finish();
    return;
}

final String myUid = currentUser.getUid();
final String otherUid = getIntent().getStringExtra("otherUid");

if (otherUid == null || otherUid.trim().length() == 0) {
    android.widget.Toast.makeText(ChatActivity.this, "No user specified for this chat.", android.widget.Toast.LENGTH_SHORT).show();
    finish();
    return;
}

final String pairId = myUid.compareTo(otherUid) < 0 ? (myUid + "_" + otherUid) : (otherUid + "_" + myUid);

final com.google.firebase.database.DatabaseReference rootRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference();
final com.google.firebase.database.DatabaseReference chatMessagesRef = rootRef.child("messages").child(pairId);
// Fallback query used only when there is no usable local cache yet.
final com.google.firebase.database.Query chatMessagesRecentQuery = chatMessagesRef.limitToLast(60);
// NOTE: keepSynced(true) on the full history was removed on purpose — it forced
// the whole conversation to download and made every send wait behind it.
final android.content.SharedPreferences cachePrefs = getSharedPreferences("MixiCache", android.content.Context.MODE_PRIVATE);
final String cacheKey = "chat_cache_" + pairId;
final String pendingCacheKey = "chat_pending_" + pairId;
final String profileCacheKey = "profile_cache_" + otherUid;

// ---------- DELTA SYNC: decide how this open will sync ----------
final String lastSyncKey = "chat_lastsync_" + pairId;
final long lastSyncAtOpen = cachePrefs.getLong(lastSyncKey, 0L);
final boolean useDeltaSync = lastSyncAtOpen > 0L && cachePrefs.contains(cacheKey);
final com.google.firebase.database.Query activeMessagesQuery = useDeltaSync
    ? chatMessagesRef.orderByChild("updatedAt").startAt((double) lastSyncAtOpen)
    : chatMessagesRecentQuery;
// True only after the local cache has been merged into messageList. The cache
// is never written before this, so a half-loaded list can't overwrite it.
final boolean[] cacheMerged = { false };
final boolean[] cacheLoadFailed = { false };
final boolean[] myPrivacyLoaded = { false };
final Runnable[] fullRecoveryHolder = new Runnable[1];

// ---------- FIX (bugs #1 and #2): chat-visibility tracking ----------
// isChatVisible reflects whether THIS chat screen is actually on-screen
// right now (true after onCreate/onResume, false after onPause). Both
// markRead() calls below, and the unreadCount reset, are gated on this so
// that messages are only ever marked "read" while the user is genuinely
// looking at this conversation — never in the background, and never after
// the user has navigated away (e.g. back to Home).
//
// active_chat_uid in cachePrefs is what MainActivity's notification watcher
// checks to decide whether to suppress a notification because the chat is
// already open. It is claimed here in onCreate and refreshed/cleared by the
// lifecycle callbacks below.
final boolean[] isChatVisible = { true };

final java.util.Map<String, Object> lifecycleBag = new java.util.HashMap<>();
lifecycleBag.put("isChatVisible", isChatVisible);
lifecycleBag.put("cachePrefs", cachePrefs);
lifecycleBag.put("otherUid", otherUid);
getWindow().getDecorView().setTag(lifecycleBag);

cachePrefs.edit().putString("active_chat_uid", otherUid).apply();
final android.app.Application.ActivityLifecycleCallbacks activeChatLifecycle = new android.app.Application.ActivityLifecycleCallbacks() {
    @Override public void onActivityResumed(android.app.Activity activity) {
        if (activity == ChatActivity.this) {
            isChatVisible[0] = true;
            cachePrefs.edit().putString("active_chat_uid", otherUid).apply();
        }
    }
    @Override public void onActivityPaused(android.app.Activity activity) {
        if (activity == ChatActivity.this) {
            isChatVisible[0] = false;
            String active = cachePrefs.getString("active_chat_uid", "");
            if (otherUid.equals(active)) cachePrefs.edit().remove("active_chat_uid").apply();
        }
    }
    @Override public void onActivityDestroyed(android.app.Activity activity) {
        if (activity == ChatActivity.this) {
            isChatVisible[0] = false;
            String active = cachePrefs.getString("active_chat_uid", "");
            if (otherUid.equals(active)) cachePrefs.edit().remove("active_chat_uid").apply();
            try { getApplication().unregisterActivityLifecycleCallbacks(this); } catch (Throwable ignored) {}
        }
    }
    @Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) {}
    @Override public void onActivityStarted(android.app.Activity activity) {}
    @Override public void onActivityStopped(android.app.Activity activity) {}
    @Override public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) {}
};
try { getApplication().registerActivityLifecycleCallbacks(activeChatLifecycle); } catch (Throwable ignored) {}

final float density = getResources().getDisplayMetrics().density;
class Utils {
    int dp(float val) { return (int)(val * density); }
}
final Utils utils = new Utils();

final int COLOR_BLUE = android.graphics.Color.parseColor("#0076FF");
final int COLOR_TEXT = android.graphics.Color.parseColor("#1E293B");
final int COLOR_MUTED = android.graphics.Color.parseColor("#64748B");
final int COLOR_LIGHT_MUTED = android.graphics.Color.parseColor("#94A3B8");
final int COLOR_GREEN = android.graphics.Color.parseColor("#10B981");
final int COLOR_RED = android.graphics.Color.parseColor("#EF4444");
final int COLOR_BG = android.graphics.Color.parseColor("#F8FAFC");
final int COLOR_BG_LIGHT_BLUE = android.graphics.Color.parseColor("#EBF4FF");
final int COLOR_BORDER = android.graphics.Color.parseColor("#E2E8F0");
final int COLOR_DIVIDER = android.graphics.Color.parseColor("#F1F5F9");
final int COLOR_TICK_READ = android.graphics.Color.parseColor("#5EEAD4");
final int COLOR_TICK_PLAYED = android.graphics.Color.parseColor("#F59E0B");

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
    android.graphics.drawable.GradientDrawable dashedOutline(int strokeColor, int fillColor, float radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fillColor);
        d.setStroke((int)(1.5f * density), strokeColor, 6 * density, 4 * density);
        d.setCornerRadius(radiusDp * density);
        return d;
    }
}
final UiKit ui = new UiKit();

// ---------- Consistent custom emoji rendering ----------
// The project must contain iOS_Emojis.ttf at app/src/main/assets/twemoji/iOS_Emojis.ttf.
// This is the only emoji typeface used by Mixi Chat; there is intentionally no fallback.
class EmojiRenderHelper {
    android.graphics.Typeface emojiTypeface() {
        return android.graphics.Typeface.createFromAsset(getAssets(), "twemoji/iOS_Emojis.ttf");
    }

    boolean isEmojiBase(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
            || (cp >= 0x2600 && cp <= 0x27BF)
            || (cp >= 0x2300 && cp <= 0x23FF)
            || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049
            || cp == 0x2122 || cp == 0x2139 || cp == 0x3030 || cp == 0x303D
            || cp == 0x3297 || cp == 0x3299;
    }

    boolean isVariationOrModifier(int cp) {
        return (cp >= 0xFE00 && cp <= 0xFE0F)
            || (cp >= 0x1F3FB && cp <= 0x1F3FF)
            || cp == 0x200D || cp == 0x20E3;
    }

    boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    boolean isSingleEmoji(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.length() == 0) return false;

        int emojiBases = 0;
        int regionalCount = 0;
        boolean hasEmoji = false;
        boolean hasNonEmojiContent = false;
        boolean hasJoiner = false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x200D) { hasJoiner = true; continue; }
            if (isVariationOrModifier(cp)) continue;
            if (isRegionalIndicator(cp)) {
                regionalCount++;
                hasEmoji = true;
                continue;
            }
            if (isEmojiBase(cp)) {
                emojiBases++;
                hasEmoji = true;
            } else {
                hasNonEmojiContent = true;
            }
        }

        // A flag is two regional indicators but one visual emoji. ZWJ
        // sequences and skin-tone sequences are likewise one visual unit.
        if (hasNonEmojiContent || !hasEmoji) return false;
        if (regionalCount > 0) return regionalCount == 2 && emojiBases == 0;
        if (hasJoiner) return emojiBases > 0;
        return emojiBases == 1;
    }

    void styleMessageText(android.widget.TextView target, String value, int color, android.graphics.Typeface emojiTypeface) {
        target.setText(value == null ? "" : value);
        target.setTextSize(isSingleEmoji(value) ? 24f : 14.5f);
        target.setTextColor(color);
        target.setTypeface(emojiTypeface);
    }
}
final EmojiRenderHelper emojiRenderHelper = new EmojiRenderHelper();
final android.graphics.Typeface emojiTypeface = emojiRenderHelper.emojiTypeface();

// ---------- Data model ----------
class ChatMessageItem {
    String id;
    String senderId;
    String receiverId;
    String text;
    long timestamp;
    // DELTA SYNC: server time of the last change to this message.
    long updatedAt;
    boolean isEdited;
    long editedAt;
    boolean editedWatermark;
    java.util.List<String> editHistory = new java.util.ArrayList<String>();
    java.util.Map<String, String> reactions = new java.util.HashMap<String, String>();
    String replyToMessageId;
    String replyToSenderId;
    String replyToText;
    boolean isDeletedForEveryone;
    String status;
    boolean deletedForMe;
    // STAGE 1 (voice notes): "text" or "audio". Everything above still applies
    // to an audio message (reactions, delete, status); edit/reply do not.
    String type = "text";
    String localAudioPath;
    int audioDurationSeconds;
    int[] waveformSamples;
}

class UnreadDividerMarker {
    int count;
    UnreadDividerMarker(int c) { count = c; }
}

class DateDividerMarker {
    String label;
    DateDividerMarker(String l) { label = l; }
}

final java.util.List<ChatMessageItem> messageList = new java.util.ArrayList<>();
final java.util.List<Object> renderItems = new java.util.ArrayList<>();
final Runnable[] rebuildMessagesHolder = new Runnable[1];
// Load-older-messages-on-scroll-up. Declared here (Holder pattern) so the
// scroll listener in STEP 7 can trigger it even though its real implementation
// is only assigned later in STEP 13, once fastMessageUpdates.parse() exists.
final Runnable[] loadOlderMessagesHolder = new Runnable[1];
final boolean[] isLoadingOlderMessages = { false };
final boolean[] reachedConversationStart = { false };

final ChatMessageItem[] messageToEditHolder = new ChatMessageItem[1];
final android.app.Dialog[] activeReactionDialog = new android.app.Dialog[1];
final android.app.Dialog[] activeContextDialog = new android.app.Dialog[1];
final ChatMessageItem[] messageToReplyHolder = new ChatMessageItem[1];
final String[] undoDeleteMsgIdHolder = new String[1];

// Privacy & State Holders
final boolean[] myShowOnlineHolder = { true };
final boolean[] myShowTypingHolder = { true };
final boolean[] myReadReceiptsHolder = { true };

final boolean[] otherUserShowOnlineHolder = { true };
final boolean[] otherUserShowTypingHolder = { true };
final boolean[] otherUserReadReceiptsHolder = { true };

final boolean[] otherUserOnlineHolder = { false };
final boolean[] otherUserIsTypingHolder = { false };
final boolean[] isTypingLocally = { false };
// Tracks whether THIS device currently has a live connection to Firebase.
// Online/typing status shown in the header comes from data that was last
// synced while connected — once we go offline, that data is stale and
// shouldn't be shown as if it were current.
final boolean[] isDeviceConnected = { true };

final String[] otherUserNameHolder = { "" };
final String[] otherUserPhotoHolder = { "" };
final String[] otherUserUsernameHolder = { "" };

// Unread Barrier State
final String[] sessionFirstUnreadId = { null };
final boolean[] sessionUnreadActive = { true };
final int[] sessionUnreadCount = { 0 };
final boolean[] hasInitializedBarrier = { false };
final boolean[] isInitialScroll = { true };
// Tracks actual manual scrolling, so the view keeps snapping to the latest
// message across as many rebuild passes as it takes, and only stops once the
// user has genuinely scrolled themselves.
final boolean[] userHasScrolled = { false };
final boolean[] messagesLoaded = { false };
final int[] initialUnreadCount = { -1 };

final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
final Runnable[] typingStopRunnableHolder = new Runnable[1];
final Runnable[] undoHideRunnableHolder = new Runnable[1];

final int screenWidth = getResources().getDisplayMetrics().widthPixels;
final int maxBubbleWidth = (int)(screenWidth * 0.78f);
final java.util.Map<String, Integer> expandedMessageLengths = new java.util.HashMap<>();
final java.util.Set<String> pendingSendIds = new java.util.HashSet<>();
final java.util.Map<String, java.util.Map<String, Object>> pendingPayloads = new java.util.HashMap<>();
// A message deleted-for-everyone while it was still mid-send: it stays visible
// as "This message was deleted" immediately, but keeps a clock icon next to
// the timestamp until the cancel/removeValue write is actually confirmed —
// once confirmed, its id comes out of this set and the clock disappears.
final java.util.Set<String> pendingDeleteIds = new java.util.HashSet<>();
// STAGE 2: ids of audio messages currently being fetched from /audio_payloads.
// Guards against double-taps and drives the loading-spinner state in the bubble.
final java.util.Set<String> downloadingAudioIds = new java.util.HashSet<>();
// Message IDs viewed while this user had read receipts disabled. These remain
// non-read permanently; enabling receipts later must not retroactively mark them.
final java.util.Set<String> viewedWithoutReadReceiptIds = new java.util.HashSet<>();
try {
    java.util.Set<String> savedViewed = cachePrefs.getStringSet("viewed_without_read_receipts_" + pairId, null);
    if (savedViewed != null) viewedWithoutReadReceiptIds.addAll(savedViewed);
} catch (Exception ignored) {}
final Runnable persistViewedWithoutReadReceipts = new Runnable() {
    @Override public void run() {
        try { cachePrefs.edit().putStringSet("viewed_without_read_receipts_" + pairId,
            new java.util.HashSet<String>(viewedWithoutReadReceiptIds)).apply(); } catch (Exception ignored) {}
    }
};
final java.util.concurrent.ExecutorService persistenceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

// ---------- External push-notification webhook ----------
// Runs only after the corresponding /messages/{pairId}/{messageId} write
// has completed successfully. No message text or audio payload is sent.
class NotificationWebhookHelper {
    private final String webhookUrl = "https://script.google.com/macros/s/AKfycbyLFL6xyb1Yzy_u-MzJP5iRTaUYYlbUKXPwUAPAee9YIXrxMPEpb7ZrQIWdYkbAvCHi/exec";
    private final String webhookSecret = "MixiChat_Notify_9xK7pQ2m_2026";

    void send(final String senderId, final String receiverId, final String pairId, final String messageId) {
        persistenceExecutor.execute(new Runnable() {
            @Override public void run() {
                java.net.HttpURLConnection connection = null;
                java.io.OutputStream output = null;
                java.io.InputStream input = null;
                try {
                    org.json.JSONObject payload = new org.json.JSONObject();
                    payload.put("secret", webhookSecret);
                    payload.put("senderId", senderId);
                    payload.put("receiverId", receiverId);
                    payload.put("pairId", pairId);
                    payload.put("messageId", messageId);

                    byte[] body = payload.toString().getBytes("UTF-8");
                    java.net.URL url = new java.net.URL(webhookUrl);
                    connection = (java.net.HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(15000);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    connection.setFixedLengthStreamingMode(body.length);

                    output = connection.getOutputStream();
                    output.write(body);
                    output.flush();

                    int responseCode = connection.getResponseCode();
                    input = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    if (input != null) {
                        byte[] buffer = new byte[1024];
                        while (input.read(buffer) != -1) {}
                    }
                } catch (Throwable ignored) {
                    // Notification failure must never affect message delivery or the UI.
                } finally {
                    try { if (input != null) input.close(); } catch (Throwable ignored) {}
                    try { if (output != null) output.close(); } catch (Throwable ignored) {}
                    if (connection != null) connection.disconnect();
                }
            }
        });
    }
}
final NotificationWebhookHelper notificationWebhook = new NotificationWebhookHelper();

class PendingPersistHelper {
    void save() {
        final java.util.HashSet<String> snapshot = new java.util.HashSet<String>(pendingSendIds);
        persistenceExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (String id : snapshot) arr.put(id);
                    cachePrefs.edit().putString(pendingCacheKey, arr.toString()).commit();
                } catch (Throwable ignored) {}
            }
        });
    }

    void load() {
        try {
            String cached = cachePrefs.getString(pendingCacheKey, null);
            if (cached != null) {
                org.json.JSONArray arr = new org.json.JSONArray(cached);
                for (int i = 0; i < arr.length(); i++) pendingSendIds.add(arr.getString(i));
            }
        } catch (Exception e) {}
    }
}
final PendingPersistHelper pendingPersist = new PendingPersistHelper();
final Runnable[] retryPendingSends = new Runnable[1];
final Runnable[] persistChatCache = new Runnable[1];

// ---------- Voice-note icons (custom-drawn, no font/emoji dependency) ----------
class MicIconDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint fillPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint strokePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    MicIconDrawable(int colorInt) {
        fillPaint.setColor(colorInt);
        fillPaint.setStyle(android.graphics.Paint.Style.FILL);
        strokePaint.setColor(colorInt);
        strokePaint.setStyle(android.graphics.Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.6f * density);
        strokePaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
    }
    @Override public void draw(android.graphics.Canvas c) {
        android.graphics.Rect b = getBounds();
        float w = b.width(), h = b.height();
        float cx = b.exactCenterX();
        float capsuleW = w * 0.34f, capsuleH = h * 0.5f;
        float capsuleTop = b.top + h * 0.06f;
        android.graphics.RectF capsule = new android.graphics.RectF(cx - capsuleW / 2f, capsuleTop, cx + capsuleW / 2f, capsuleTop + capsuleH);
        c.drawRoundRect(capsule, capsuleW / 2f, capsuleW / 2f, fillPaint);
        float standY = capsuleTop + capsuleH + h * 0.10f;
        android.graphics.RectF arcBounds = new android.graphics.RectF(cx - w * 0.30f, capsuleTop + capsuleH * 0.35f, cx + w * 0.30f, standY);
        c.drawArc(arcBounds, 0f, 180f, false, strokePaint);
        c.drawLine(cx, standY, cx, standY + h * 0.14f, strokePaint);
        c.drawLine(cx - w * 0.16f, standY + h * 0.14f, cx + w * 0.16f, standY + h * 0.14f, strokePaint);
    }
    @Override public void setAlpha(int a) { fillPaint.setAlpha(a); strokePaint.setAlpha(a); }
    @Override public void setColorFilter(android.graphics.ColorFilter f) { fillPaint.setColorFilter(f); strokePaint.setColorFilter(f); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
class BinIconDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    BinIconDrawable(int colorInt) {
        paint.setColor(colorInt);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * density);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
    }
    @Override public void draw(android.graphics.Canvas c) {
        android.graphics.Rect b = getBounds();
        float w = b.width(), h = b.height();
        float lidY = b.top + h * 0.22f;
        c.drawLine(b.left + w * 0.16f, lidY, b.right - w * 0.16f, lidY, paint);
        c.drawLine(cxOf(b, 0.36f), lidY, cxOf(b, 0.36f), b.top + h * 0.10f, paint);
        c.drawLine(cxOf(b, 0.64f), lidY, cxOf(b, 0.64f), b.top + h * 0.10f, paint);
        c.drawLine(cxOf(b, 0.36f), b.top + h * 0.10f, cxOf(b, 0.64f), b.top + h * 0.10f, paint);
        android.graphics.Path body = new android.graphics.Path();
        body.moveTo(b.left + w * 0.22f, lidY);
        body.lineTo(b.left + w * 0.28f, b.bottom - h * 0.08f);
        body.lineTo(b.right - w * 0.28f, b.bottom - h * 0.08f);
        body.lineTo(b.right - w * 0.22f, lidY);
        c.drawPath(body, paint);
        c.drawLine(cxOf(b, 0.40f), lidY + h * 0.10f, cxOf(b, 0.40f), b.bottom - h * 0.18f, paint);
        c.drawLine(cxOf(b, 0.60f), lidY + h * 0.10f, cxOf(b, 0.60f), b.bottom - h * 0.18f, paint);
    }
    private float cxOf(android.graphics.Rect b, float frac) { return b.left + b.width() * frac; }
    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
class LockIconDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint fillPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint strokePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    LockIconDrawable(int colorInt) {
        fillPaint.setColor(colorInt);
        fillPaint.setStyle(android.graphics.Paint.Style.FILL);
        strokePaint.setColor(colorInt);
        strokePaint.setStyle(android.graphics.Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.6f * density);
        strokePaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
    }
    @Override public void draw(android.graphics.Canvas c) {
        android.graphics.Rect b = getBounds();
        float w = b.width(), h = b.height();
        float cx = b.exactCenterX();
        float bodyTop = b.top + h * 0.42f;
        android.graphics.RectF body = new android.graphics.RectF(b.left + w * 0.20f, bodyTop, b.right - w * 0.20f, b.bottom - h * 0.08f);
        c.drawRoundRect(body, w * 0.10f, w * 0.10f, fillPaint);
        android.graphics.RectF shackle = new android.graphics.RectF(cx - w * 0.22f, b.top + h * 0.08f, cx + w * 0.22f, bodyTop + h * 0.14f);
        c.drawArc(shackle, 180f, 180f, false, strokePaint);
    }
    @Override public void setAlpha(int a) { fillPaint.setAlpha(a); strokePaint.setAlpha(a); }
    @Override public void setColorFilter(android.graphics.ColorFilter f) { fillPaint.setColorFilter(f); strokePaint.setColorFilter(f); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
class PlayIconDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    PlayIconDrawable(int colorInt) { paint.setColor(colorInt); paint.setStyle(android.graphics.Paint.Style.FILL); }
    @Override public void draw(android.graphics.Canvas c) {
        android.graphics.Rect b = getBounds();
        float w = b.width(), h = b.height();
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(b.left + w * 0.28f, b.top + h * 0.16f);
        p.lineTo(b.left + w * 0.28f, b.bottom - h * 0.16f);
        p.lineTo(b.right - w * 0.20f, b.exactCenterY());
        p.close();
        c.drawPath(p, paint);
    }
    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
class PauseIconDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    PauseIconDrawable(int colorInt) { paint.setColor(colorInt); paint.setStyle(android.graphics.Paint.Style.FILL); }
    @Override public void draw(android.graphics.Canvas c) {
        android.graphics.Rect b = getBounds();
        float w = b.width(), h = b.height();
        float barW = w * 0.20f;
        c.drawRect(b.left + w * 0.22f, b.top + h * 0.16f, b.left + w * 0.22f + barW, b.bottom - h * 0.16f, paint);
        c.drawRect(b.right - w * 0.22f - barW, b.top + h * 0.16f, b.right - w * 0.22f, b.bottom - h * 0.16f, paint);
    }
    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
class DownloadIconDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    DownloadIconDrawable(int colorInt) {
        paint.setColor(colorInt);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f * density);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
    }
    @Override public void draw(android.graphics.Canvas c) {
        android.graphics.Rect b = getBounds();
        float w = b.width(), h = b.height();
        float cx = b.exactCenterX();
        c.drawLine(cx, b.top + h * 0.10f, cx, b.top + h * 0.62f, paint);
        android.graphics.Path arrow = new android.graphics.Path();
        arrow.moveTo(cx - w * 0.22f, b.top + h * 0.40f);
        arrow.lineTo(cx, b.top + h * 0.62f);
        arrow.lineTo(cx + w * 0.22f, b.top + h * 0.40f);
        c.drawPath(arrow, paint);
        c.drawLine(b.left + w * 0.18f, b.bottom - h * 0.14f, b.right - w * 0.18f, b.bottom - h * 0.14f, paint);
    }
    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}

// A growing, live-updating waveform shown while actively recording. Amplitude
// samples are appended as they're captured; drawing always downsamples the
// full history to fit the view's width, so the bar width stays constant no
// matter how long the recording runs.
class LiveWaveformView extends android.view.View {
    private final java.util.List<Float> samples = new java.util.ArrayList<Float>();
    private final android.graphics.Paint barPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF barRect = new android.graphics.RectF();
    LiveWaveformView(android.content.Context ctx, int colorInt) {
        super(ctx);
        barPaint.setColor(colorInt);
        barPaint.setStyle(android.graphics.Paint.Style.FILL);
    }
    void addAmplitude(float normalized0to1) {
        samples.add(Math.max(0.04f, Math.min(1f, normalized0to1)));
        invalidate();
    }
    java.util.List<Float> getSamples() { return samples; }
    void reset() { samples.clear(); invalidate(); }
    @Override protected void onDraw(android.graphics.Canvas c) {
        super.onDraw(c);
        int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0 || samples.isEmpty()) return;
        float barW = 3f * density, gap = 2f * density;
        int barCount = Math.max(1, (int) (width / (barW + gap)));
        int total = samples.size();
        for (int i = 0; i < barCount; i++) {
            int startIdx = total * i / barCount;
            int endIdx = Math.max(startIdx + 1, total * (i + 1) / barCount);
            float sum = 0f; int n = 0;
            for (int j = startIdx; j < endIdx && j < total; j++) { sum += samples.get(j); n++; }
            float amp = n > 0 ? sum / n : 0.05f;
            float barH = Math.max(3f * density, amp * height);
            float x = i * (barW + gap);
            float top = (height - barH) / 2f;
            // RectF overload (API 1) instead of the API 21+ float overload.
            barRect.set(x, top, x + barW, top + barH);
            c.drawRoundRect(barRect, barW / 2f, barW / 2f, barPaint);
        }
    }
}

// A fixed-size waveform for a finished/sent voice-note bubble. Always renders
// the same bar count regardless of the clip's actual duration, which is how
// the bubble keeps a constant max width even for a full 60-second note.
class StaticWaveformView extends android.view.View {
    private int[] levels = new int[0];
    private float playedFraction = 0f;
    private final android.graphics.Paint playedPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint unplayedPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF barRect = new android.graphics.RectF();
    StaticWaveformView(android.content.Context ctx, int playedColor, int unplayedColor) {
        super(ctx);
        playedPaint.setColor(playedColor);
        playedPaint.setStyle(android.graphics.Paint.Style.FILL);
        unplayedPaint.setColor(unplayedColor);
        unplayedPaint.setStyle(android.graphics.Paint.Style.FILL);
    }
    void setLevels(int[] newLevels) { levels = newLevels != null ? newLevels : new int[0]; invalidate(); }
    void setPlayedFraction(float f) { playedFraction = Math.max(0f, Math.min(1f, f)); invalidate(); }
    @Override protected void onDraw(android.graphics.Canvas c) {
        super.onDraw(c);
        int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0 || levels.length == 0) return;
        float gap = 2f * density;
        float barW = (width - gap * (levels.length - 1)) / levels.length;
        if (barW < 1.5f * density) barW = 1.5f * density;
        int playedBars = Math.round(levels.length * playedFraction);
        for (int i = 0; i < levels.length; i++) {
            float amp = Math.max(0.06f, levels[i] / 100f);
            float barH = Math.max(2.5f * density, amp * height);
            float x = i * (barW + gap);
            float top = (height - barH) / 2f;
            android.graphics.Paint p = i < playedBars ? playedPaint : unplayedPaint;
            barRect.set(x, top, x + barW, top + barH);
            c.drawRoundRect(barRect, barW / 2f, barW / 2f, p);
        }
    }
}


// ---------- STEP 1: UI shell ----------

final android.widget.FrameLayout overlayRoot = new android.widget.FrameLayout(ChatActivity.this);
overlayRoot.setBackgroundColor(android.graphics.Color.WHITE);

final android.widget.LinearLayout rootLayout = new android.widget.LinearLayout(ChatActivity.this);
rootLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
rootLayout.setBackgroundColor(android.graphics.Color.WHITE);

// --- Header ---
android.widget.LinearLayout chatHeader = new android.widget.LinearLayout(ChatActivity.this);
chatHeader.setOrientation(android.widget.LinearLayout.HORIZONTAL);
chatHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
chatHeader.setPadding(utils.dp(8), utils.dp(10), utils.dp(16), utils.dp(10));
chatHeader.setBackgroundColor(android.graphics.Color.WHITE);

final android.widget.TextView backBtn = new android.widget.TextView(ChatActivity.this);
backBtn.setText("\u2039");
backBtn.setTextSize(30);
backBtn.setTextColor(COLOR_TEXT);
backBtn.setPadding(utils.dp(10), 0, utils.dp(10), utils.dp(4));
backBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) { finish(); }
});
chatHeader.addView(backBtn, new android.widget.LinearLayout.LayoutParams(-2, -2));

final android.widget.FrameLayout headerAvatarWrap = new android.widget.FrameLayout(ChatActivity.this);
android.widget.LinearLayout.LayoutParams headerAvatarWrapParams = new android.widget.LinearLayout.LayoutParams(utils.dp(42), utils.dp(42));
headerAvatarWrapParams.setMargins(utils.dp(4), 0, utils.dp(10), 0);
headerAvatarWrap.setLayoutParams(headerAvatarWrapParams);

final android.widget.ImageView headerAvatar = new android.widget.ImageView(ChatActivity.this);
headerAvatar.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
headerAvatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
headerAvatarWrap.addView(headerAvatar);

final android.view.View headerOnlineDot = new android.view.View(ChatActivity.this);
android.graphics.drawable.GradientDrawable headerDotBg = new android.graphics.drawable.GradientDrawable();
headerDotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
headerDotBg.setColor(COLOR_GREEN);
headerDotBg.setStroke(utils.dp(2), android.graphics.Color.WHITE);
headerOnlineDot.setBackground(headerDotBg);
headerOnlineDot.setVisibility(android.view.View.GONE);
android.widget.FrameLayout.LayoutParams headerDotParams = new android.widget.FrameLayout.LayoutParams(utils.dp(12), utils.dp(12));
headerDotParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
headerAvatarWrap.addView(headerOnlineDot, headerDotParams);

chatHeader.addView(headerAvatarWrap);

android.widget.LinearLayout headerTitleCol = new android.widget.LinearLayout(ChatActivity.this);
headerTitleCol.setOrientation(android.widget.LinearLayout.VERTICAL);

final android.widget.TextView headerName = new android.widget.TextView(ChatActivity.this);
headerName.setTextSize(16);
headerName.setTypeface(null, android.graphics.Typeface.BOLD);
headerName.setTextColor(COLOR_TEXT);

final android.widget.TextView headerSub = new android.widget.TextView(ChatActivity.this);
headerSub.setTextSize(12);
headerSub.setTextColor(COLOR_LIGHT_MUTED);
headerSub.setText("offline");

headerName.setTypeface(emojiTypeface);
headerSub.setTypeface(emojiTypeface);
headerTitleCol.addView(headerName);
headerTitleCol.addView(headerSub);
chatHeader.addView(headerTitleCol, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

final android.widget.FrameLayout callBtn = new android.widget.FrameLayout(ChatActivity.this);
callBtn.setBackground(ui.rounded(COLOR_BG_LIGHT_BLUE, 20));
android.widget.LinearLayout.LayoutParams callBtnParams = new android.widget.LinearLayout.LayoutParams(utils.dp(40), utils.dp(40));
android.widget.ImageView callIcon = new android.widget.ImageView(ChatActivity.this);
callIcon.setImageResource(android.R.drawable.stat_sys_phone_call);
callIcon.setColorFilter(COLOR_BLUE, android.graphics.PorterDuff.Mode.SRC_IN);
android.widget.FrameLayout.LayoutParams callIconParams = new android.widget.FrameLayout.LayoutParams(utils.dp(20), utils.dp(20));
callIconParams.gravity = android.view.Gravity.CENTER;
callBtn.addView(callIcon, callIconParams);
chatHeader.addView(callBtn, callBtnParams);

android.view.View headerDivider = new android.view.View(ChatActivity.this);
headerDivider.setBackgroundColor(COLOR_DIVIDER);

// --- Messages list ---
final android.widget.ListView messagesListView = new android.widget.ListView(ChatActivity.this);
messagesListView.setDivider(null);
messagesListView.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
messagesListView.setBackgroundColor(COLOR_BG);
messagesListView.setTranscriptMode(android.widget.ListView.TRANSCRIPT_MODE_NORMAL);

final android.widget.FrameLayout messagesFrame = new android.widget.FrameLayout(ChatActivity.this);
messagesFrame.addView(messagesListView, new android.widget.FrameLayout.LayoutParams(-1, -1));

final android.widget.FrameLayout scrollToBottomBtn = new android.widget.FrameLayout(ChatActivity.this);
scrollToBottomBtn.setBackground(ui.rounded(android.graphics.Color.WHITE, 20));
scrollToBottomBtn.setElevation(utils.dp(4));
scrollToBottomBtn.setVisibility(android.view.View.GONE);
android.widget.TextView scrollToBottomIcon = new android.widget.TextView(ChatActivity.this);
scrollToBottomIcon.setText("\u2193");
scrollToBottomIcon.setTextColor(COLOR_BLUE);
scrollToBottomIcon.setTextSize(20);
scrollToBottomIcon.setTypeface(null, android.graphics.Typeface.BOLD);
scrollToBottomIcon.setGravity(android.view.Gravity.CENTER);
scrollToBottomBtn.addView(scrollToBottomIcon, new android.widget.FrameLayout.LayoutParams(-1, -1));
android.widget.FrameLayout.LayoutParams scrollToBottomParams = new android.widget.FrameLayout.LayoutParams(utils.dp(40), utils.dp(40));
scrollToBottomParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
scrollToBottomParams.setMargins(0, 0, utils.dp(14), utils.dp(14));
messagesFrame.addView(scrollToBottomBtn, scrollToBottomParams);

// --- Blocked banner ---
final android.widget.TextView blockedBanner = new android.widget.TextView(ChatActivity.this);
blockedBanner.setText("You can't send messages until you become friends.");
blockedBanner.setTextColor(android.graphics.Color.parseColor("#B91C1C"));
blockedBanner.setBackgroundColor(android.graphics.Color.parseColor("#FEF2F2"));
blockedBanner.setGravity(android.view.Gravity.CENTER);
blockedBanner.setPadding(utils.dp(18), utils.dp(14), utils.dp(18), utils.dp(14));
blockedBanner.setTextSize(13.5f);
blockedBanner.setVisibility(android.view.View.GONE);

// --- Edit-mode indicator bar ---
final android.widget.LinearLayout editIndicatorBar = new android.widget.LinearLayout(ChatActivity.this);
editIndicatorBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
editIndicatorBar.setBackgroundColor(android.graphics.Color.parseColor("#FEF3C7"));
editIndicatorBar.setPadding(utils.dp(16), utils.dp(8), utils.dp(16), utils.dp(8));
editIndicatorBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
editIndicatorBar.setVisibility(android.view.View.GONE);

android.widget.TextView editIndicatorLabel = new android.widget.TextView(ChatActivity.this);
editIndicatorLabel.setText("Editing message...");
editIndicatorLabel.setTextColor(android.graphics.Color.parseColor("#92400E"));
editIndicatorLabel.setTextSize(12);
editIndicatorBar.addView(editIndicatorLabel, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

final android.widget.TextView editCancelBtn = new android.widget.TextView(ChatActivity.this);
editCancelBtn.setText("Cancel");
editCancelBtn.setTextColor(android.graphics.Color.parseColor("#92400E"));
editCancelBtn.setTextSize(12);
editCancelBtn.setPaintFlags(editCancelBtn.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
editIndicatorBar.addView(editCancelBtn);

// --- Reply preview bar ---
final android.widget.LinearLayout replyPreviewBar = new android.widget.LinearLayout(ChatActivity.this);
replyPreviewBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
replyPreviewBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
replyPreviewBar.setPadding(utils.dp(16), utils.dp(7), utils.dp(12), utils.dp(7));
replyPreviewBar.setBackgroundColor(COLOR_BG_LIGHT_BLUE);
replyPreviewBar.setVisibility(android.view.View.GONE);
final android.widget.TextView replyPreviewText = new android.widget.TextView(ChatActivity.this);
replyPreviewText.setTextSize(12); replyPreviewText.setTextColor(COLOR_TEXT);
replyPreviewText.setTypeface(emojiTypeface);
replyPreviewBar.addView(replyPreviewText, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
final android.widget.TextView replyCancelBtn = new android.widget.TextView(ChatActivity.this);
replyCancelBtn.setText("×"); replyCancelBtn.setTextSize(22); replyCancelBtn.setTextColor(COLOR_MUTED);
replyPreviewBar.addView(replyCancelBtn, new android.widget.LinearLayout.LayoutParams(utils.dp(32), -2));
replyCancelBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override public void onClick(android.view.View v) { messageToReplyHolder[0] = null; replyPreviewBar.setVisibility(android.view.View.GONE); }
});

// --- Input bar ---
android.widget.LinearLayout inputBar = new android.widget.LinearLayout(ChatActivity.this);
inputBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
inputBar.setPadding(utils.dp(12), utils.dp(8), utils.dp(12), utils.dp(8));
inputBar.setGravity(android.view.Gravity.BOTTOM);
// The recording gesture moves only the mic button left/up. Disable clipping so
// the button remains visible while it travels above the input bar.
inputBar.setClipChildren(false);
inputBar.setClipToPadding(false);
inputBar.setBackgroundColor(android.graphics.Color.WHITE);

final android.widget.EditText chatInput = new android.widget.EditText(ChatActivity.this);
chatInput.setHint("Type a message...");
chatInput.setBackground(ui.rounded(COLOR_BG, 20));
chatInput.setPadding(utils.dp(16), utils.dp(10), utils.dp(16), utils.dp(10));
chatInput.setTextSize(15);
chatInput.setTypeface(emojiTypeface);
chatInput.setMaxLines(6);
// Disable the system spell-checker's red/blue underline while typing.
chatInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
final int MAX_MESSAGE_LENGTH = 2860;
chatInput.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter() {
    @Override public CharSequence filter(CharSequence source, int start, int end, android.text.Spanned dest, int dstart, int dend) {
        int existing = dest == null ? 0 : dest.length() - (dend - dstart);
        int room = MAX_MESSAGE_LENGTH - existing;
        if (room <= 0) {
            android.widget.Toast.makeText(ChatActivity.this, "You can't type more than 2860 characters.", android.widget.Toast.LENGTH_SHORT).show();
            return "";
        }
        int incoming = end - start;
        if (incoming > room) {
            android.widget.Toast.makeText(ChatActivity.this, "You can't type more than 2860 characters.", android.widget.Toast.LENGTH_SHORT).show();
            return source.subSequence(start, start + room);
        }
        return null;
    }
} });
android.widget.LinearLayout.LayoutParams chatInputParams = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
chatInputParams.rightMargin = utils.dp(10);

final android.widget.FrameLayout sendBtn = new android.widget.FrameLayout(ChatActivity.this);
sendBtn.setBackground(ui.rounded(COLOR_BLUE, 22));
android.widget.LinearLayout.LayoutParams sendBtnParams = new android.widget.LinearLayout.LayoutParams(utils.dp(44), utils.dp(44));
android.widget.TextView sendIcon = new android.widget.TextView(ChatActivity.this);
sendIcon.setText("\u27A4");
sendIcon.setTextColor(android.graphics.Color.WHITE);
sendIcon.setTextSize(18);
sendIcon.setGravity(android.view.Gravity.CENTER);
sendBtn.addView(sendIcon, new android.widget.FrameLayout.LayoutParams(-1, -1));

// ---------- Voice note: mic button (shown instead of send when the box is empty) ----------
final android.widget.FrameLayout micBtn = new android.widget.FrameLayout(ChatActivity.this);
micBtn.setBackground(ui.rounded(COLOR_BLUE, 22));
android.widget.LinearLayout.LayoutParams micBtnParams = new android.widget.LinearLayout.LayoutParams(utils.dp(44), utils.dp(44));
android.widget.ImageView micIconView = new android.widget.ImageView(ChatActivity.this);
micIconView.setImageDrawable(new MicIconDrawable(android.graphics.Color.WHITE));
micBtn.addView(micIconView, new android.widget.FrameLayout.LayoutParams(-1, -1));

// Recording info row occupies the same weight-1 slot chatInput normally does.
final android.widget.LinearLayout recordingInfoRow = new android.widget.LinearLayout(ChatActivity.this);
recordingInfoRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
recordingInfoRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
recordingInfoRow.setVisibility(android.view.View.GONE);

final android.widget.ImageView binIconView = new android.widget.ImageView(ChatActivity.this);
binIconView.setImageDrawable(new BinIconDrawable(COLOR_RED));
android.widget.LinearLayout.LayoutParams binIconParams = new android.widget.LinearLayout.LayoutParams(utils.dp(22), utils.dp(22));
binIconParams.rightMargin = utils.dp(10);
recordingInfoRow.addView(binIconView, binIconParams);

final android.widget.LinearLayout waveformAndTimerRow = new android.widget.LinearLayout(ChatActivity.this);
waveformAndTimerRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
waveformAndTimerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
final LiveWaveformView liveWaveform = new LiveWaveformView(ChatActivity.this, COLOR_BLUE);
waveformAndTimerRow.addView(liveWaveform, new android.widget.LinearLayout.LayoutParams(0, utils.dp(28), 1f));
final android.widget.TextView recordTimerText = new android.widget.TextView(ChatActivity.this);
recordTimerText.setTextColor(COLOR_RED);
recordTimerText.setTextSize(13);
recordTimerText.setTypeface(null, android.graphics.Typeface.BOLD);
recordTimerText.setPadding(utils.dp(8), 0, 0, 0);
recordTimerText.setText("0:00");
waveformAndTimerRow.addView(recordTimerText, new android.widget.LinearLayout.LayoutParams(-2, -2));

final android.widget.TextView cancelHintText = new android.widget.TextView(ChatActivity.this);
cancelHintText.setText("\u25C1 Release to cancel");
cancelHintText.setTextColor(COLOR_RED);
cancelHintText.setTextSize(13);
cancelHintText.setTypeface(null, android.graphics.Typeface.BOLD);
cancelHintText.setGravity(android.view.Gravity.CENTER);
cancelHintText.setVisibility(android.view.View.GONE);

recordingInfoRow.addView(waveformAndTimerRow, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
recordingInfoRow.addView(cancelHintText, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

inputBar.addView(chatInput, chatInputParams);
inputBar.addView(recordingInfoRow, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
inputBar.addView(sendBtn, sendBtnParams);
inputBar.addView(micBtn, micBtnParams);
sendBtn.setVisibility(android.view.View.GONE);

final boolean[] editWatermarkChoice = { false };
final android.widget.LinearLayout watermarkOptions = new android.widget.LinearLayout(ChatActivity.this);
watermarkOptions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
watermarkOptions.setPadding(utils.dp(12), utils.dp(6), utils.dp(12), utils.dp(2));
watermarkOptions.setVisibility(android.view.View.GONE);

final android.widget.TextView withoutWatermarkOption = new android.widget.TextView(ChatActivity.this);
withoutWatermarkOption.setText("Without watermark");
withoutWatermarkOption.setTextSize(12);
withoutWatermarkOption.setGravity(android.view.Gravity.CENTER);
withoutWatermarkOption.setPadding(utils.dp(12), utils.dp(8), utils.dp(12), utils.dp(8));

final android.widget.TextView withWatermarkOption = new android.widget.TextView(ChatActivity.this);
withWatermarkOption.setText("Edited watermark");
withWatermarkOption.setTextSize(12);
withWatermarkOption.setGravity(android.view.Gravity.CENTER);
withWatermarkOption.setPadding(utils.dp(12), utils.dp(8), utils.dp(12), utils.dp(8));

watermarkOptions.addView(withoutWatermarkOption, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
android.widget.LinearLayout.LayoutParams watermarkOptionParams = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
watermarkOptionParams.leftMargin = utils.dp(8);
watermarkOptions.addView(withWatermarkOption, watermarkOptionParams);

final Runnable refreshWatermarkOptions = new Runnable() {
    @Override public void run() {
        withoutWatermarkOption.setTextColor(editWatermarkChoice[0] ? COLOR_MUTED : COLOR_BLUE);
        withoutWatermarkOption.setBackground(ui.rounded(editWatermarkChoice[0] ? COLOR_BG : COLOR_BG_LIGHT_BLUE, 16));
        withWatermarkOption.setTextColor(editWatermarkChoice[0] ? COLOR_BLUE : COLOR_MUTED);
        withWatermarkOption.setBackground(ui.rounded(editWatermarkChoice[0] ? COLOR_BG_LIGHT_BLUE : COLOR_BG, 16));
    }
};
withoutWatermarkOption.setOnClickListener(new android.view.View.OnClickListener() {
    @Override public void onClick(android.view.View v) { editWatermarkChoice[0] = false; refreshWatermarkOptions.run(); }
});
withWatermarkOption.setOnClickListener(new android.view.View.OnClickListener() {
    @Override public void onClick(android.view.View v) { editWatermarkChoice[0] = true; refreshWatermarkOptions.run(); }
});
refreshWatermarkOptions.run();

final android.widget.LinearLayout inputWrap = new android.widget.LinearLayout(ChatActivity.this);
inputWrap.setOrientation(android.widget.LinearLayout.VERTICAL);
inputWrap.setBackgroundColor(android.graphics.Color.WHITE);
inputWrap.addView(replyPreviewBar, new android.widget.LinearLayout.LayoutParams(-1, -2));
inputWrap.addView(editIndicatorBar, new android.widget.LinearLayout.LayoutParams(-1, -2));
inputWrap.addView(watermarkOptions, new android.widget.LinearLayout.LayoutParams(-1, -2));
inputWrap.addView(inputBar, new android.widget.LinearLayout.LayoutParams(-1, -2));

rootLayout.addView(chatHeader, new android.widget.LinearLayout.LayoutParams(-1, -2));
rootLayout.addView(headerDivider, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(1)));
rootLayout.addView(messagesFrame, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
rootLayout.addView(blockedBanner, new android.widget.LinearLayout.LayoutParams(-1, -2));
rootLayout.addView(inputWrap, new android.widget.LinearLayout.LayoutParams(-1, -2));

overlayRoot.addView(rootLayout, new android.widget.FrameLayout.LayoutParams(-1, -1));
overlayRoot.setClipChildren(false);
rootLayout.setClipChildren(false);
micBtn.bringToFront();

// --- Undo toast bar (overlay) ---
final android.widget.LinearLayout undoToastBar = new android.widget.LinearLayout(ChatActivity.this);
undoToastBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
undoToastBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
undoToastBar.setBackground(ui.rounded(android.graphics.Color.parseColor("#1E293B"), 24));
undoToastBar.setPadding(utils.dp(18), utils.dp(10), utils.dp(18), utils.dp(10));
undoToastBar.setVisibility(android.view.View.GONE);
undoToastBar.setElevation(utils.dp(8));

final android.widget.TextView undoToastText = new android.widget.TextView(ChatActivity.this);
undoToastText.setText("Message deleted for you");
undoToastText.setTextColor(android.graphics.Color.WHITE);
undoToastText.setTextSize(13);
undoToastBar.addView(undoToastText, new android.widget.LinearLayout.LayoutParams(-2, -2));

final android.widget.TextView undoToastAction = new android.widget.TextView(ChatActivity.this);
undoToastAction.setText("Undo");
undoToastAction.setTextColor(android.graphics.Color.parseColor("#38BDF8"));
undoToastAction.setTextSize(13);
undoToastAction.setTypeface(null, android.graphics.Typeface.BOLD);
android.widget.LinearLayout.LayoutParams undoActionParams = new android.widget.LinearLayout.LayoutParams(-2, -2);
undoActionParams.leftMargin = utils.dp(16);
undoToastBar.addView(undoToastAction, undoActionParams);

android.widget.FrameLayout.LayoutParams undoBarParams = new android.widget.FrameLayout.LayoutParams(-2, -2);
undoBarParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
undoBarParams.bottomMargin = utils.dp(90);
overlayRoot.addView(undoToastBar, undoBarParams);

// ---------- Voice note: floating lock icon, hovers above the mic button ----------
final android.widget.FrameLayout lockIconWrap = new android.widget.FrameLayout(ChatActivity.this);
lockIconWrap.setBackground(ui.rounded(android.graphics.Color.WHITE, 18));
lockIconWrap.setElevation(utils.dp(4));
lockIconWrap.setVisibility(android.view.View.GONE);
final android.widget.ImageView lockIconView = new android.widget.ImageView(ChatActivity.this);
lockIconView.setImageDrawable(new LockIconDrawable(COLOR_MUTED));
android.widget.FrameLayout.LayoutParams lockIconInnerParams = new android.widget.FrameLayout.LayoutParams(utils.dp(20), utils.dp(20));
lockIconInnerParams.gravity = android.view.Gravity.CENTER;
lockIconWrap.addView(lockIconView, lockIconInnerParams);
android.widget.FrameLayout.LayoutParams lockIconWrapParams = new android.widget.FrameLayout.LayoutParams(utils.dp(36), utils.dp(36));
lockIconWrapParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
lockIconWrapParams.bottomMargin = utils.dp(118);
lockIconWrapParams.rightMargin = utils.dp(22);
overlayRoot.addView(lockIconWrap, lockIconWrapParams);

setContentView(overlayRoot);

// ---------- Voice note: recording engine + hold/slide-to-cancel/slide-to-lock ----------
final android.media.MediaRecorder[] activeRecorder = new android.media.MediaRecorder[1];
final String[] activeRecordingPath = new String[1];
final boolean[] isRecordingActive = { false };
final boolean[] isRecordingLocked = { false };
final boolean[] isCancelArmed = { false };
final boolean[] isLockArmed = { false };
final float[] recordTouchStartX = { 0f };
final float[] recordTouchStartY = { 0f };
final long[] recordStartTimeMs = { 0L };

class WaveformDownsampler {
    int[] downsample(java.util.List<Float> samples, int targetCount) {
        int[] result = new int[targetCount];
        if (samples == null || samples.isEmpty()) return result;
        int total = samples.size();
        for (int i = 0; i < targetCount; i++) {
            int startIdx = total * i / targetCount;
            int endIdx = Math.max(startIdx + 1, total * (i + 1) / targetCount);
            float sum = 0f; int n = 0;
            for (int j = startIdx; j < endIdx && j < total; j++) { sum += samples.get(j); n++; }
            float avg = n > 0 ? sum / n : 0.05f;
            result[i] = Math.round(Math.max(6f, avg * 100f));
        }
        return result;
    }
}
final WaveformDownsampler waveformDownsampler = new WaveformDownsampler();

class TimeFmt {
    String mmss(int totalSeconds) {
        int m = totalSeconds / 60, s = totalSeconds % 60;
        return m + ":" + (s < 10 ? "0" + s : "" + s);
    }
}
final TimeFmt timeFmt = new TimeFmt();

// Reply labels must preserve the useful identity of audio messages even though
// audio messages do not use their text field.
class ReplyLabelHelper {
    String label(ChatMessageItem target) {
        if (target == null) return "";
        if ("audio".equals(target.type)) {
            return "Audio message (" + timeFmt.mmss(target.audioDurationSeconds) + ")";
        }
        String value = target.text == null ? "" : target.text.trim();
        if (value.length() > 80) value = value.substring(0, 80).trim() + "…";
        return value;
    }
}
final ReplyLabelHelper replyLabelHelper = new ReplyLabelHelper();

class VoiceRecorderHelper {
    boolean hasMicPermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    void requestMicPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{ android.Manifest.permission.RECORD_AUDIO }, 4021);
        }
    }
    String start() {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "voice_notes");
            if (!dir.exists()) dir.mkdirs();
            java.io.File outFile = new java.io.File(dir, "vn_" + System.currentTimeMillis() + ".m4a");
            android.media.MediaRecorder recorder = new android.media.MediaRecorder();
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(48000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            activeRecorder[0] = recorder;
            activeRecordingPath[0] = outFile.getAbsolutePath();
            return outFile.getAbsolutePath();
        } catch (Throwable t) {
            activeRecorder[0] = null;
            activeRecordingPath[0] = null;
            return null;
        }
    }
    void stopQuiet() {
        if (activeRecorder[0] != null) {
            try { activeRecorder[0].stop(); } catch (Throwable ignored) {}
            try { activeRecorder[0].release(); } catch (Throwable ignored) {}
            activeRecorder[0] = null;
        }
    }
    void deleteFile(String path) {
        if (path == null) return;
        try { new java.io.File(path).delete(); } catch (Throwable ignored) {}
    }
}
final VoiceRecorderHelper voiceRecorder = new VoiceRecorderHelper();

final Runnable[] recordTickRunnable = new Runnable[1];
final Runnable[] resetRecordingUiHolder = new Runnable[1];
final Runnable[] finishRecordingHolder = new Runnable[1];
final Runnable[] cancelRecordingHolder = new Runnable[1];
final Runnable[] lockRecordingHolder = new Runnable[1];

resetRecordingUiHolder[0] = new Runnable() {
    @Override public void run() {
        isRecordingActive[0] = false;
        isRecordingLocked[0] = false;
        isCancelArmed[0] = false;
        isLockArmed[0] = false;
        if (recordTickRunnable[0] != null) mainHandler.removeCallbacks(recordTickRunnable[0]);
        chatInput.setVisibility(android.view.View.VISIBLE);
        recordingInfoRow.setVisibility(android.view.View.GONE);
        recordingInfoRow.setTranslationX(0f);
        micBtn.setTranslationX(0f);
        micBtn.setTranslationY(0f);
        waveformAndTimerRow.setVisibility(android.view.View.VISIBLE);
        cancelHintText.setVisibility(android.view.View.GONE);
        lockIconWrap.setVisibility(android.view.View.GONE);
        lockIconView.setImageDrawable(new LockIconDrawable(COLOR_MUTED));
        lockIconWrap.setBackground(ui.rounded(android.graphics.Color.WHITE, 18));
        liveWaveform.reset();
        recordTimerText.setText("0:00");
        micBtn.setVisibility(chatInput.getText().length() == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        sendBtn.setVisibility(chatInput.getText().length() > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
    }
};

recordTickRunnable[0] = new Runnable() {
    @Override public void run() {
        if (!isRecordingActive[0] || activeRecorder[0] == null) return;
        int amp = 0;
        try { amp = activeRecorder[0].getMaxAmplitude(); } catch (Throwable ignored) {}
        float normalized = Math.min(1f, amp / 12000f);
        liveWaveform.addAmplitude(normalized);
        long elapsedMs = System.currentTimeMillis() - recordStartTimeMs[0];
        int elapsedSec = (int) (elapsedMs / 1000);
        recordTimerText.setText(timeFmt.mmss(elapsedSec));
        if (elapsedSec >= 60) {
            // Voice-note hard limit — auto-stop and send exactly like a release would.
            finishRecordingHolder[0].run();
            return;
        }
        mainHandler.postDelayed(recordTickRunnable[0], 150);
    }
};

finishRecordingHolder[0] = new Runnable() {
    @Override public void run() {
        if (!isRecordingActive[0]) return;
        long elapsedMs = System.currentTimeMillis() - recordStartTimeMs[0];
        final int durationSec = Math.max(1, (int) Math.round(elapsedMs / 1000.0));
        final String recordedPath = activeRecordingPath[0];
        voiceRecorder.stopQuiet();
        if (elapsedMs < 700) {
            // Too short to be a real voice note (an accidental tap) — discard quietly.
            voiceRecorder.deleteFile(recordedPath);
            resetRecordingUiHolder[0].run();
            return;
        }

        // STAGE 2: real send. Message text/metadata stays tiny in /messages —
        // the heavy base64 payload lives only in /audio_payloads.
        final int[] waveformSummary = waveformDownsampler.downsample(liveWaveform.getSamples(), 40);

        if (sessionUnreadActive[0]) { sessionUnreadActive[0] = false; sessionFirstUnreadId[0] = null; }

        final com.google.firebase.database.DatabaseReference newMsgRef = rootRef.child("messages").child(pairId).push();
        final String newMsgId = newMsgRef.getKey();

        // The sender's own recording doubles as its local cache — move it into
        // the permanent audio_cache dir under the message's own id, so it never
        // has to be re-downloaded by the device that recorded it.
        java.io.File cacheDir = new java.io.File(getFilesDir(), "audio_cache");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        final java.io.File cachedFile = new java.io.File(cacheDir, newMsgId + ".m4a");
        boolean moved = false;
        try {
            java.io.File src = new java.io.File(recordedPath);
            moved = src.renameTo(cachedFile);
        } catch (Throwable ignored) {}
        final String finalLocalPath = moved ? cachedFile.getAbsolutePath() : recordedPath;

        pendingSendIds.add(newMsgId);
        pendingPersist.save();

        final long sentTimestamp = System.currentTimeMillis();
        ChatMessageItem audioMsg = new ChatMessageItem();
        audioMsg.id = newMsgId;
        audioMsg.senderId = myUid;
        audioMsg.receiverId = otherUid;
        audioMsg.type = "audio";
        audioMsg.localAudioPath = finalLocalPath;
        audioMsg.audioDurationSeconds = durationSec;
        audioMsg.waveformSamples = waveformSummary;
        audioMsg.timestamp = sentTimestamp;
        audioMsg.status = "sent";
        messageList.add(audioMsg);
        rebuildMessagesHolder[0].run();
        resetRecordingUiHolder[0].run();

        // Encode + upload off the main thread.
        persistenceExecutor.execute(new Runnable() {
            @Override public void run() {
                String base64Audio = null;
                try {
                    java.io.File f = new java.io.File(finalLocalPath);
                    byte[] bytes = new byte[(int) f.length()];
                    java.io.FileInputStream fis = new java.io.FileInputStream(f);
                    int off = 0, n;
                    while (off < bytes.length && (n = fis.read(bytes, off, bytes.length - off)) != -1) off += n;
                    fis.close();
                    base64Audio = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                } catch (Throwable ignored) {}
                final String encoded = base64Audio;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (encoded == null) {
                            android.widget.Toast.makeText(ChatActivity.this, "Couldn't prepare voice note for sending", android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        java.util.Map<String, Object> payloadData = new java.util.HashMap<>();
                        payloadData.put("base64Data", encoded);
                        rootRef.child("audio_payloads").child(newMsgId).setValue(payloadData);

                        java.util.Map<String, Object> msgData = new java.util.HashMap<>();
                        msgData.put("senderId", myUid);
                        msgData.put("receiverId", otherUid);
                        msgData.put("type", "audio");
                        msgData.put("timestamp", sentTimestamp);
                        msgData.put("serverTimestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
                        msgData.put("updatedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
                        msgData.put("audioDurationSeconds", durationSec);
                        java.util.List<Integer> waveformList = new java.util.ArrayList<Integer>();
                        for (int lvl : waveformSummary) waveformList.add(lvl);
                        msgData.put("waveformSamples", waveformList);
                        msgData.put("isDeletedForEveryone", false);
                        msgData.put("status", "sent");
                        pendingPayloads.put(newMsgId, msgData);

                        newMsgRef.setValue(msgData, new com.google.firebase.database.DatabaseReference.CompletionListener() {
                            @Override public void onComplete(com.google.firebase.database.DatabaseError error, com.google.firebase.database.DatabaseReference ref) {
                                if (error == null) {
                                    pendingSendIds.remove(newMsgId);
                                    pendingPayloads.remove(newMsgId);
                                    pendingPersist.save();
                                    notificationWebhook.send(myUid, otherUid, pairId, newMsgId);
                                } else {
                                    pendingSendIds.add(newMsgId);
                                    pendingPersist.save();
                                }
                                rebuildMessagesHolder[0].run();
                            }
                        });

                        java.util.Map<String, Object> lastMsgData = new java.util.HashMap<>();
                        lastMsgData.put("text", "\uD83C\uDFA4 Voice message");
                        lastMsgData.put("senderId", myUid);
                        lastMsgData.put("timestamp", sentTimestamp);
                        lastMsgData.put("status", "sent");
                        lastMsgData.put("isDeletedForEveryone", false);
                        rootRef.child("conversations").child(pairId).child("lastMessage").setValue(lastMsgData);

                        rootRef.child("conversations").child(pairId).child("unreadCount").child(otherUid)
                            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                                @Override public com.google.firebase.database.Transaction.Result doTransaction(com.google.firebase.database.MutableData currentData) {
                                    Long curr = currentData.getValue(Long.class);
                                    currentData.setValue((curr == null ? 0L : curr) + 1L);
                                    return com.google.firebase.database.Transaction.success(currentData);
                                }
                                @Override public void onComplete(com.google.firebase.database.DatabaseError error, boolean committed, com.google.firebase.database.DataSnapshot snapshot) {}
                            });
                    }
                });
            }
        });
    }
};

cancelRecordingHolder[0] = new Runnable() {
    @Override public void run() {
        if (!isRecordingActive[0]) return;
        String path = activeRecordingPath[0];
        voiceRecorder.stopQuiet();
        voiceRecorder.deleteFile(path);
        recordingInfoRow.animate()
            .translationX(-utils.dp(140))
            .scaleX(0.2f).scaleY(0.2f)
            .alpha(0f)
            .setDuration(280)
            .withEndAction(new Runnable() {
                @Override public void run() {
                    recordingInfoRow.setAlpha(1f);
                    recordingInfoRow.setScaleX(1f);
                    recordingInfoRow.setScaleY(1f);
                    resetRecordingUiHolder[0].run();
                }
            }).start();
    }
};

lockRecordingHolder[0] = new Runnable() {
    @Override public void run() {
        isRecordingLocked[0] = true;
        isCancelArmed[0] = false;
        isLockArmed[0] = false;
        recordingInfoRow.setTranslationX(0f);
        micBtn.setTranslationX(0f);
        micBtn.setTranslationY(0f);
        waveformAndTimerRow.setVisibility(android.view.View.VISIBLE);
        cancelHintText.setVisibility(android.view.View.GONE);
        lockIconWrap.setVisibility(android.view.View.GONE);
        micBtn.setVisibility(android.view.View.GONE);
        sendBtn.setVisibility(android.view.View.VISIBLE);
    }
};

binIconView.setOnClickListener(new android.view.View.OnClickListener() {
    @Override public void onClick(android.view.View v) {
        if (isRecordingActive[0]) cancelRecordingHolder[0].run();
    }
});

micBtn.setOnTouchListener(new android.view.View.OnTouchListener() {
    @Override public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
        switch (event.getAction()) {
            case android.view.MotionEvent.ACTION_DOWN: {
                if (!voiceRecorder.hasMicPermission()) {
                    voiceRecorder.requestMicPermission();
                    android.widget.Toast.makeText(ChatActivity.this, "Allow microphone access, then hold the mic again", android.widget.Toast.LENGTH_LONG).show();
                    return true;
                }
                String path = voiceRecorder.start();
                if (path == null) {
                    android.widget.Toast.makeText(ChatActivity.this, "Couldn't start recording", android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
                recordTouchStartX[0] = event.getRawX();
                recordTouchStartY[0] = event.getRawY();
                recordStartTimeMs[0] = System.currentTimeMillis();
                isRecordingActive[0] = true;
                isRecordingLocked[0] = false;
                isCancelArmed[0] = false;
                isLockArmed[0] = false;
                liveWaveform.reset();
                recordTimerText.setText("0:00");
                chatInput.setVisibility(android.view.View.GONE);
                recordingInfoRow.setVisibility(android.view.View.VISIBLE);
                recordingInfoRow.setAlpha(1f);
                recordingInfoRow.setTranslationX(0f);
                micBtn.setTranslationX(0f);
                micBtn.setTranslationY(0f);
                waveformAndTimerRow.setVisibility(android.view.View.VISIBLE);
                cancelHintText.setVisibility(android.view.View.GONE);
                lockIconWrap.setVisibility(android.view.View.VISIBLE);
                lockIconView.setImageDrawable(new LockIconDrawable(COLOR_MUTED));
                lockIconWrap.setBackground(ui.rounded(android.graphics.Color.WHITE, 18));
                if (recordTickRunnable[0] != null) mainHandler.removeCallbacks(recordTickRunnable[0]);
                mainHandler.post(recordTickRunnable[0]);
                return true;
            }
            case android.view.MotionEvent.ACTION_MOVE: {
                if (!isRecordingActive[0] || isRecordingLocked[0]) return true;
                float dx = event.getRawX() - recordTouchStartX[0];
                float dy = event.getRawY() - recordTouchStartY[0];
                boolean nowCancelArmed = dx <= -utils.dp(110);
                boolean nowLockArmed = dy <= -utils.dp(50);
                if (nowCancelArmed != isCancelArmed[0]) {
                    isCancelArmed[0] = nowCancelArmed;
                    waveformAndTimerRow.setVisibility(nowCancelArmed ? android.view.View.GONE : android.view.View.VISIBLE);
                    cancelHintText.setVisibility(nowCancelArmed ? android.view.View.VISIBLE : android.view.View.GONE);
                }
                if (nowLockArmed != isLockArmed[0]) {
                    isLockArmed[0] = nowLockArmed;
                    lockIconView.setImageDrawable(new LockIconDrawable(nowLockArmed ? COLOR_BLUE : COLOR_MUTED));
                    lockIconWrap.setBackground(ui.rounded(nowLockArmed ? COLOR_BG_LIGHT_BLUE : android.graphics.Color.WHITE, 18));
                }
                // The mic button itself visually follows the drag — clamped to
                // left/up only, capped at the same distance that arms cancel/lock.
                float clampedDx = Math.min(0f, Math.max(dx, -utils.dp(110)));
                float clampedDy = Math.min(0f, Math.max(dy, -utils.dp(50)));
                micBtn.setTranslationX(clampedDx);
                micBtn.setTranslationY(clampedDy);
                // Keep the waveform/timer row fixed. Only the mic overlay
                // follows the left/up gesture.
                recordingInfoRow.setTranslationX(0f);
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
                if (!isRecordingActive[0] || isRecordingLocked[0]) return true;
                if (isLockArmed[0]) {
                    lockRecordingHolder[0].run();
                } else if (isCancelArmed[0] || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                    cancelRecordingHolder[0].run();
                } else if (System.currentTimeMillis() - recordStartTimeMs[0] < 350L) {
                    // Tap-to-record: keep recording and switch to the locked UI.
                    // The send button is then used to finish and send the note.
                    lockRecordingHolder[0].run();
                } else {
                    finishRecordingHolder[0].run();
                }
                return true;
            }
        }
        return false;
    }
});

// ---------- Voice note: playback (one voice note plays at a time) ----------
class AudioPlaybackController {
    android.media.MediaPlayer currentPlayer;
    String currentMsgId;
    android.widget.ImageView currentIconView;
    int currentIconColor;
    StaticWaveformView currentWaveformView;
    Runnable progressRunnable;

    void stopCurrent() {
        if (progressRunnable != null) mainHandler.removeCallbacks(progressRunnable);
        if (currentPlayer != null) {
            try { currentPlayer.stop(); } catch (Throwable ignored) {}
            try { currentPlayer.release(); } catch (Throwable ignored) {}
            currentPlayer = null;
        }
        if (currentIconView != null) currentIconView.setImageDrawable(new PlayIconDrawable(currentIconColor));
        if (currentWaveformView != null) currentWaveformView.setPlayedFraction(0f);
        currentMsgId = null;
        currentIconView = null;
        currentWaveformView = null;
    }

    void togglePlay(final ChatMessageItem msg, final android.widget.ImageView iconView, final int iconColor, final StaticWaveformView waveformView) {
        if (msg.localAudioPath == null) return;
        if (msg.id.equals(currentMsgId) && currentPlayer != null) {
            stopCurrent();
            return;
        }
        stopCurrent();
        try {
            final android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setDataSource(msg.localAudioPath);
            player.prepare();
            player.start();
            currentPlayer = player;
            currentMsgId = msg.id;
            currentIconView = iconView;
            currentIconColor = iconColor;
            currentWaveformView = waveformView;
            iconView.setImageDrawable(new PauseIconDrawable(iconColor));
            final int totalDurationMs = player.getDuration();
            progressRunnable = new Runnable() {
                @Override public void run() {
                    if (currentPlayer != player) return;
                    try {
                        if (!player.isPlaying()) return;
                        float frac = totalDurationMs > 0 ? (float) player.getCurrentPosition() / totalDurationMs : 0f;
                        waveformView.setPlayedFraction(frac);
                        mainHandler.postDelayed(this, 120);
                    } catch (Throwable ignored) {}
                }
            };
            mainHandler.post(progressRunnable);
            player.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(android.media.MediaPlayer mp) { stopCurrent(); }
            });
        } catch (Throwable t) {
            android.widget.Toast.makeText(ChatActivity.this, "Couldn't play voice note", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
final AudioPlaybackController audioPlaybackController = new AudioPlaybackController();

// ---------- Voice note: download-on-demand for received audio (Stage 2) ----------
class AudioDownloadHelper {
    void download(final ChatMessageItem msg) {
        if (downloadingAudioIds.contains(msg.id)) return;
        downloadingAudioIds.add(msg.id);
        rebuildMessagesHolder[0].run();
        rootRef.child("audio_payloads").child(msg.id).child("base64Data").addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                final String base64 = snapshot.getValue(String.class);
                if (base64 == null) {
                    downloadingAudioIds.remove(msg.id);
                    rebuildMessagesHolder[0].run();
                    android.widget.Toast.makeText(ChatActivity.this, "Voice note unavailable", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                persistenceExecutor.execute(new Runnable() {
                    @Override public void run() {
                        boolean ok = false;
                        String savedPath = null;
                        try {
                            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
                            java.io.File dir = new java.io.File(getFilesDir(), "audio_cache");
                            if (!dir.exists()) dir.mkdirs();
                            java.io.File out = new java.io.File(dir, msg.id + ".m4a");
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                            fos.write(bytes);
                            fos.close();
                            savedPath = out.getAbsolutePath();
                            ok = true;
                        } catch (Throwable ignored) {}
                        final boolean success = ok;
                        final String finalPath = savedPath;
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                downloadingAudioIds.remove(msg.id);
                                if (success) msg.localAudioPath = finalPath;
                                else android.widget.Toast.makeText(ChatActivity.this, "Couldn't download voice note", android.widget.Toast.LENGTH_SHORT).show();
                                rebuildMessagesHolder[0].run();
                            }
                        });
                    }
                });
            }
            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {
                downloadingAudioIds.remove(msg.id);
                rebuildMessagesHolder[0].run();
            }
        });
    }
}
final AudioDownloadHelper audioDownloadHelper = new AudioDownloadHelper();

// ---------- SMOOTH SCROLL HELPER ----------
class ScrollHelper {
    void smoothScrollToBottom() {
        if (renderItems.isEmpty()) return;
        final int target = renderItems.size() - 1;
        messagesListView.post(new Runnable() {
            @Override
            public void run() {
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    messagesListView.smoothScrollToPositionFromTop(target, 0, 300);
                } else {
                    messagesListView.smoothScrollToPosition(target);
                }
            }
        });
    }
    
    void instantScrollToBottom() {
        if (renderItems.isEmpty()) return;
        final int target = renderItems.size() - 1;
        messagesListView.post(new Runnable() {
            @Override
            public void run() {
                messagesListView.setSelection(target);
            }
        });
    }
}
final ScrollHelper scrollHelper = new ScrollHelper();

class ChatDefaultAvatarDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final String letters;
    ChatDefaultAvatarDrawable(String name) {
        paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        String n = name == null ? "" : name.trim(); if (n.length() == 0) n = "User";
        String[] p = n.split("\\s+"); String a = p[0].length() == 0 ? "U" : p[0].substring(0,1);
        String b = p.length > 1 && p[p.length - 1].length() > 0 ? p[p.length - 1].substring(0,1) : "";
        letters = (a + b).toUpperCase(java.util.Locale.getDefault());
    }
    @Override public void draw(android.graphics.Canvas c) {
        android.graphics.Rect b=getBounds(); float x=b.exactCenterX(), y=b.exactCenterY(), r=Math.min(b.width(),b.height())*.5f;
        paint.setStyle(android.graphics.Paint.Style.FILL); paint.setColor(android.graphics.Color.parseColor("#0076FF")); c.drawCircle(x,y,r,paint);
        paint.setColor(android.graphics.Color.WHITE); paint.setTextAlign(android.graphics.Paint.Align.CENTER); paint.setTextSize(r*(letters.length() > 1 ? 0.72f : 0.9f));
        android.graphics.Paint.FontMetrics f=paint.getFontMetrics(); c.drawText(letters,x,y-(f.ascent+f.descent)/2f,paint);
    }
    @Override public void setAlpha(int a){paint.setAlpha(a);} @Override public void setColorFilter(android.graphics.ColorFilter f){paint.setColorFilter(f);} @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
}
// The quoted-reply prefix is a custom-drawn reply-arrow icon, so it never
// depends on font glyph coverage.
class ReplyIconDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    ReplyIconDrawable(int colorInt) {
        paint.setColor(colorInt);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
    }
    @Override public void draw(android.graphics.Canvas c) {
        android.graphics.Rect b = getBounds();
        float w = b.width(), h = b.height();
        float cy = b.top + h * 0.55f;
        // Horizontal stem
        c.drawLine(b.left + w * 0.18f, cy, b.right - w * 0.15f, cy, paint);
        // Left-pointing arrowhead (the classic "reply" shape)
        android.graphics.Path arrow = new android.graphics.Path();
        arrow.moveTo(b.left + w * 0.45f, b.top + h * 0.20f);
        arrow.lineTo(b.left + w * 0.16f, cy);
        arrow.lineTo(b.left + w * 0.45f, b.top + h * 0.85f);
        c.drawPath(arrow, paint);
    }
    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
// The clock/pending icon is custom-drawn so it never depends on system emoji rendering.
class ClockIconDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    ClockIconDrawable(int colorInt) {
        paint.setColor(colorInt);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * density);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
    }
    @Override public void draw(android.graphics.Canvas c) {
        android.graphics.Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float r = Math.min(b.width(), b.height()) / 2f - paint.getStrokeWidth();
        if (r < 1f) r = 1f;
        c.drawCircle(cx, cy, r, paint);
        c.drawLine(cx, cy, cx, cy - r * 0.55f, paint);
        c.drawLine(cx, cy, cx + r * 0.45f, cy, paint);
    }
    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}

class ChatAvatarHelper {
    void load(String url, String name, android.widget.ImageView target) {
        // Show the default lettered avatar immediately so it is never blank,
        // then swap it for the real photo if one loads successfully.
        target.setImageDrawable(new ChatDefaultAvatarDrawable(name));
        if (url == null || url.trim().length() == 0 || url.contains("ui-avatars.com")) {
            return;
        }
        try {
            com.bumptech.glide.Glide.with(ChatActivity.this)
                .load(url)
                .placeholder(new ChatDefaultAvatarDrawable(name))
                .error(new ChatDefaultAvatarDrawable(name))
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .circleCrop()
                .into(target);
        } catch (Throwable ignored) {
            target.setImageDrawable(new ChatDefaultAvatarDrawable(name));
        }
    }
}
final ChatAvatarHelper chatAvatarHelper = new ChatAvatarHelper();

// Show the default avatar immediately while the cached/Firebase profile loads.
chatAvatarHelper.load("", "User", headerAvatar);

// ---------- STEP 2: Profile preview + call launcher ----------
class ProfilePreviewHelper {
    void show(String photoURL, String displayName) {
        final android.app.Dialog dialog = new android.app.Dialog(ChatActivity.this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout dialogRoot = new android.widget.FrameLayout(ChatActivity.this);
        dialogRoot.setBackgroundColor(android.graphics.Color.parseColor("#DD000000"));

        android.widget.ImageView bigAvatar = new android.widget.ImageView(ChatActivity.this);
        android.widget.FrameLayout.LayoutParams p = new android.widget.FrameLayout.LayoutParams(utils.dp(250), utils.dp(250));
        p.gravity = android.view.Gravity.CENTER;
        bigAvatar.setLayoutParams(p);
        bigAvatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        chatAvatarHelper.load(photoURL, displayName, bigAvatar);

        dialogRoot.addView(bigAvatar);
        dialogRoot.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) { dialog.dismiss(); }
        });
        dialog.setContentView(dialogRoot);
        dialog.show();
    }
}
final ProfilePreviewHelper profilePreview = new ProfilePreviewHelper();

headerAvatarWrap.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) { profilePreview.show(otherUserPhotoHolder[0], otherUserNameHolder[0]); }
});

callBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        try {
            android.content.Intent callIntent = new android.content.Intent(ChatActivity.this, Class.forName(getPackageName() + ".CallActivity"));
            callIntent.putExtra("otherUid", otherUid);
            callIntent.putExtra("otherName", otherUserNameHolder[0]);
            callIntent.putExtra("otherPhoto", otherUserPhotoHolder[0]);
            startActivity(callIntent);
        } catch (ClassNotFoundException e) {
            android.widget.Toast.makeText(ChatActivity.this, "CallActivity not created yet", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
});

// ---------- STEP 3: Header sub-text composer (Symmetric Privacy) ----------
class HeaderSubUpdater {
    void refresh() {
        if (!isDeviceConnected[0]) {
            // We're offline — whatever online/typing state we last saw for
            // the other person could be stale, so don't show it as current.
            headerSub.setText("@" + otherUserUsernameHolder[0]);
            headerSub.setTextColor(COLOR_LIGHT_MUTED);
            headerSub.setTypeface(null, android.graphics.Typeface.NORMAL);
            headerOnlineDot.setVisibility(android.view.View.GONE);
            return;
        }

        boolean canSeeTyping = otherUserShowTypingHolder[0] && myShowTypingHolder[0];
        boolean canSeeOnline = otherUserShowOnlineHolder[0] && myShowOnlineHolder[0];

        if (otherUserIsTypingHolder[0] && canSeeTyping) {
            headerSub.setText("Typing...");
            headerSub.setTextColor(COLOR_BLUE);
            headerSub.setTypeface(null, android.graphics.Typeface.BOLD);
            headerOnlineDot.setVisibility(android.view.View.GONE);
        } else if (otherUserOnlineHolder[0] && canSeeOnline) {
            headerSub.setText("Online");
            headerSub.setTextColor(COLOR_GREEN);
            headerSub.setTypeface(null, android.graphics.Typeface.BOLD);
            headerOnlineDot.setVisibility(android.view.View.VISIBLE);
        } else {
            headerSub.setText("@" + otherUserUsernameHolder[0]);
            headerSub.setTextColor(COLOR_LIGHT_MUTED);
            headerSub.setTypeface(null, android.graphics.Typeface.NORMAL);
            headerOnlineDot.setVisibility(android.view.View.GONE);
        }
    }
}
final HeaderSubUpdater headerSubUpdater = new HeaderSubUpdater();

// ---------- STEP 4: Persistent Profile & Header Cache Loader ----------
class ProfileCacheHelper {
    void load() {
        try {
            String cachedProfileJson = cachePrefs.getString(profileCacheKey, null);
            if (cachedProfileJson != null) {
                org.json.JSONObject pObj = new org.json.JSONObject(cachedProfileJson);
                otherUserNameHolder[0] = pObj.optString("name", "");
                otherUserUsernameHolder[0] = pObj.optString("username", "");
                otherUserPhotoHolder[0] = pObj.optString("photoURL", "");

                headerName.setText(otherUserNameHolder[0]);
                headerSub.setText("@" + otherUserUsernameHolder[0]);
                chatAvatarHelper.load(otherUserPhotoHolder[0], otherUserNameHolder[0], headerAvatar);
            }
        } catch (Exception e) {}
    }

    void save(String name, String username, String photoURL) {
        try {
            org.json.JSONObject pObj = new org.json.JSONObject();
            pObj.put("name", name != null ? name : "");
            pObj.put("username", username != null ? username : "");
            pObj.put("photoURL", photoURL != null ? photoURL : "");
            cachePrefs.edit().putString(profileCacheKey, pObj.toString()).apply();
        } catch (Exception e) {}
    }
}
final ProfileCacheHelper profileCache = new ProfileCacheHelper();
profileCache.load();

// ---------- STEP 5: Delete / undo logic ----------
class DeleteActionsHelper {
    void deleteForMe(final ChatMessageItem msg) {
        undoDeleteMsgIdHolder[0] = msg.id;
        msg.deletedForMe = true;
        messageList.remove(msg);
        if (rebuildMessagesHolder[0] != null) rebuildMessagesHolder[0].run();
        // DELTA SYNC: bump updatedAt together with the change (one atomic write).
        java.util.Map<String, Object> hideUpdates = new java.util.HashMap<>();
        hideUpdates.put("deletedFor/" + myUid, true);
        hideUpdates.put("updatedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
        rootRef.child("messages").child(pairId).child(msg.id).updateChildren(hideUpdates);
        showUndoToast();
    }

    void deleteForEveryone(final ChatMessageItem msg) {
        msg.isDeletedForEveryone = true;
        // Show the clock next to the timestamp until the deletion is actually
        // confirmed against Firebase (sender may be offline or on a slow connection).
        pendingDeleteIds.add(msg.id);
        if (rebuildMessagesHolder[0] != null) rebuildMessagesHolder[0].run();
        // STAGE 2: an audio message's heavy payload has no reason to survive a
        // delete-for-everyone — remove it and its local cached copy too.
        if ("audio".equals(msg.type)) {
            rootRef.child("audio_payloads").child(msg.id).removeValue();
            try { new java.io.File(getFilesDir(), "audio_cache/" + msg.id + ".m4a").delete(); } catch (Throwable ignored) {}
        }
        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("isDeletedForEveryone", true);
        updates.put("updatedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
        // Keep the original text, edit history, and edit state intact. The
        // renderer shows the same message content and only adds the delete mark.
        rootRef.child("messages").child(pairId).child(msg.id).updateChildren(updates, new com.google.firebase.database.DatabaseReference.CompletionListener() {
            @Override public void onComplete(com.google.firebase.database.DatabaseError error, com.google.firebase.database.DatabaseReference ref) {
                pendingDeleteIds.remove(msg.id);
                if (rebuildMessagesHolder[0] != null) rebuildMessagesHolder[0].run();
            }
        });

        rootRef.child("conversations").child(pairId).child("lastMessage").child("timestamp")
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                    Long lastTs = snapshot.getValue(Long.class);
                    if (lastTs != null && lastTs == msg.timestamp) {
                        java.util.Map<String, Object> convUpdates = new java.util.HashMap<>();
                        convUpdates.put("text", msg.text == null ? "" : msg.text);
                        convUpdates.put("isDeletedForEveryone", true);
                        rootRef.child("conversations").child(pairId).child("lastMessage").updateChildren(convUpdates);
                    }
                }
                @Override
                public void onCancelled(com.google.firebase.database.DatabaseError error) {}
            });
    }

    void showUndoToast() {
        undoToastText.setText("Message deleted for you");
        undoToastBar.setVisibility(android.view.View.VISIBLE);

        if (undoHideRunnableHolder[0] != null) mainHandler.removeCallbacks(undoHideRunnableHolder[0]);
        undoHideRunnableHolder[0] = new Runnable() {
            @Override
            public void run() {
                undoToastBar.setVisibility(android.view.View.GONE);
                undoDeleteMsgIdHolder[0] = null;
            }
        };
        mainHandler.postDelayed(undoHideRunnableHolder[0], 5000);
    }
}
final DeleteActionsHelper deleteActions = new DeleteActionsHelper();

undoToastAction.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        if (undoDeleteMsgIdHolder[0] != null) {
            java.util.Map<String, Object> undoUpdates = new java.util.HashMap<>();
            undoUpdates.put("deletedFor/" + myUid, null);
            undoUpdates.put("updatedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
            rootRef.child("messages").child(pairId).child(undoDeleteMsgIdHolder[0]).updateChildren(undoUpdates);
        }
        if (undoHideRunnableHolder[0] != null) mainHandler.removeCallbacks(undoHideRunnableHolder[0]);
        undoToastBar.setVisibility(android.view.View.GONE);
        undoDeleteMsgIdHolder[0] = null;
    }
});

// ---------- STEP 6: Context menu (long-press) ----------
class KeyboardHelper {
    void hideAndClearFocus() {
        chatInput.clearFocus();
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(chatInput.getWindowToken(), 0);
    }
}
final KeyboardHelper keyboardHelper = new KeyboardHelper();

class ContextMenuHelper {
    void show(final ChatMessageItem msg) {
        final android.app.Dialog dialog = new android.app.Dialog(ChatActivity.this);
        android.widget.LinearLayout sheet = new android.widget.LinearLayout(ChatActivity.this);
        sheet.setOrientation(android.widget.LinearLayout.VERTICAL);
        sheet.setBackgroundColor(android.graphics.Color.WHITE);
        sheet.setPadding(utils.dp(16), utils.dp(16), utils.dp(16), utils.dp(16));

        boolean isMine = msg.senderId.equals(myUid);
        final boolean isDeleted = msg.isDeletedForEveryone;
        final boolean isAudioMsg = "audio".equals(msg.type);
        // Voice notes only support delete + reactions — no copy/edit, per spec.
        boolean canEdit = isMine && !isDeleted && !isAudioMsg && (System.currentTimeMillis() - msg.timestamp) <= (15L * 60L * 1000L);

        // A message that hasn't finished sending yet only gets one option —
        // Delete for Everyone. No copy, no edit, no reactions, no "delete for me".
        final boolean isPending = pendingSendIds.contains(msg.id);
        if (isPending) {
            sheet.addView(makeSheetItem("Delete for Everyone", COLOR_RED, android.graphics.Color.parseColor("#FEF2F2"), new Runnable() {
                @Override
                public void run() {
                    // Show it as deleted right away, keep a clock next to the
                    // timestamp until the cancellation is confirmed, then drop it.
                    pendingSendIds.remove(msg.id);
                    pendingPayloads.remove(msg.id);
                    pendingPersist.save();
                    msg.isDeletedForEveryone = true;
                    pendingDeleteIds.add(msg.id);
                    rebuildMessagesHolder[0].run();
                    // STAGE 2: clean up the audio payload/local cache too, in case
                    // the upload had already landed just before this cancellation.
                    if (isAudioMsg) {
                        rootRef.child("audio_payloads").child(msg.id).removeValue();
                        try { new java.io.File(getFilesDir(), "audio_cache/" + msg.id + ".m4a").delete(); } catch (Throwable ignored) {}
                    }
                    rootRef.child("messages").child(pairId).child(msg.id).removeValue(new com.google.firebase.database.DatabaseReference.CompletionListener() {
                        @Override public void onComplete(com.google.firebase.database.DatabaseError error, com.google.firebase.database.DatabaseReference ref) {
                            pendingDeleteIds.remove(msg.id);
                            rebuildMessagesHolder[0].run();
                        }
                    });
                    dialog.dismiss();
                    keyboardHelper.hideAndClearFocus();
                }
            }));

            android.widget.TextView cancelItemPending = new android.widget.TextView(ChatActivity.this);
            cancelItemPending.setText("Cancel");
            cancelItemPending.setGravity(android.view.Gravity.CENTER);
            cancelItemPending.setTextColor(COLOR_MUTED);
            cancelItemPending.setBackground(ui.outline(COLOR_BORDER, 12));
            cancelItemPending.setPadding(utils.dp(16), utils.dp(12), utils.dp(16), utils.dp(12));
            android.widget.LinearLayout.LayoutParams cancelParamsPending = new android.widget.LinearLayout.LayoutParams(-1, -2);
            cancelParamsPending.topMargin = utils.dp(4);
            cancelItemPending.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    dialog.dismiss();
                    keyboardHelper.hideAndClearFocus();
                }
            });
            sheet.addView(cancelItemPending, cancelParamsPending);

            dialog.setContentView(sheet);
            activeContextDialog[0] = dialog;
            sheet.setTranslationY(utils.dp(80));
            android.view.Window pendingWindow = dialog.getWindow();
            if (pendingWindow != null) {
                pendingWindow.setGravity(android.view.Gravity.BOTTOM);
                pendingWindow.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            dialog.show();
            sheet.animate().translationY(0f).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            return;
        }

        if (isDeleted) {
            // A message deleted for everyone cannot be copied.
            // Show Delete for Me instead.
            sheet.addView(makeSheetItem("Delete for Me", COLOR_TEXT, android.graphics.Color.parseColor("#F8FAFC"), new Runnable() {
                @Override
                public void run() {
                    deleteActions.deleteForMe(msg);
                    dialog.dismiss();
                    keyboardHelper.hideAndClearFocus();
                }
            }));
        } else if (!isAudioMsg) {
            sheet.addView(makeSheetItem("Copy Text", COLOR_TEXT, android.graphics.Color.parseColor("#F8FAFC"), new Runnable() {
                @Override
                public void run() {
                    try {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        String fullOriginalText = msg.text == null ? "" : msg.text;
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", fullOriginalText));
                        android.widget.Toast.makeText(ChatActivity.this, "Text copied to clipboard", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        android.widget.Toast.makeText(ChatActivity.this, "Couldn't copy text", android.widget.Toast.LENGTH_SHORT).show();
                    }
                    dialog.dismiss();
                    keyboardHelper.hideAndClearFocus();
                }
            }));
        }

        if (canEdit) {
            sheet.addView(makeSheetItem("Edit (within 15 mins)", COLOR_TEXT, android.graphics.Color.parseColor("#F8FAFC"), new Runnable() {
                @Override
                public void run() {
                    messageToEditHolder[0] = msg;
                    chatInput.setText(msg.text);
                    chatInput.setSelection(chatInput.getText().length());
                    editWatermarkChoice[0] = true;
                    refreshWatermarkOptions.run();
                    editIndicatorBar.setVisibility(android.view.View.VISIBLE);
                    watermarkOptions.setVisibility(android.view.View.GONE);
                    dialog.dismiss();
                    chatInput.requestFocus();
                }
            }));
        }

        if (!isDeleted) {
            sheet.addView(makeSheetItem("Delete for Me", COLOR_TEXT, android.graphics.Color.parseColor("#F8FAFC"), new Runnable() {
                @Override
                public void run() {
                    deleteActions.deleteForMe(msg);
                    dialog.dismiss();
                    keyboardHelper.hideAndClearFocus();
                }
            }));
        }

        if (isMine && !isDeleted) {
            sheet.addView(makeSheetItem("Delete for Everyone", COLOR_RED, android.graphics.Color.parseColor("#FEF2F2"), new Runnable() {
                @Override
                public void run() {
                    deleteActions.deleteForEveryone(msg);
                    dialog.dismiss();
                    keyboardHelper.hideAndClearFocus();
                }
            }));
        }

        android.widget.TextView cancelItem = new android.widget.TextView(ChatActivity.this);
        cancelItem.setText("Cancel");
        cancelItem.setGravity(android.view.Gravity.CENTER);
        cancelItem.setTextColor(COLOR_MUTED);
        cancelItem.setBackground(ui.outline(COLOR_BORDER, 12));
        cancelItem.setPadding(utils.dp(16), utils.dp(12), utils.dp(16), utils.dp(12));
        android.widget.LinearLayout.LayoutParams cancelParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
        cancelParams.topMargin = utils.dp(4);
        cancelItem.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                dialog.dismiss();
                keyboardHelper.hideAndClearFocus();
            }
        });
        sheet.addView(cancelItem, cancelParams);

        dialog.setContentView(sheet);
        activeContextDialog[0] = dialog;
        sheet.setTranslationY(utils.dp(80));
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(android.view.Gravity.BOTTOM);
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        sheet.animate().translationY(0f).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    android.widget.TextView makeSheetItem(String text, int textColor, int bgColor, final Runnable action) {
        android.widget.TextView item = new android.widget.TextView(ChatActivity.this);
        item.setText(text);
        item.setTextColor(textColor);
        item.setBackground(ui.rounded(bgColor, 12));
        item.setPadding(utils.dp(16), utils.dp(13), utils.dp(16), utils.dp(13));
        item.setTextSize(14.5f);
        android.widget.LinearLayout.LayoutParams p = new android.widget.LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = utils.dp(8);
        item.setLayoutParams(p);
        item.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (activeReactionDialog[0] != null && activeReactionDialog[0].isShowing()) activeReactionDialog[0].dismiss();
                action.run();
            }
        });
        return item;
    }
}
final ContextMenuHelper contextMenuHelper = new ContextMenuHelper();

// ---------- STEP 6B: Long-press reactions ----------
class ReactionHelper {
    final String[] reactionChoices = {"👍", "❤️", "😂", "😮", "😢", "🙏"};
    final String[] allEmojiChoices = {
        "😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃","😉","😊","😇","🥰","😍","🤩","😘","😗","☺️","😚","😙","🥲","😋","😛","😜","🤪","🤨","🧐","🤓","😎","🥳","😏","😒","😞","😔","😟","😕","🙁","☹️","😣","😖","😫","😩","🥺","😢","😭","😤","😠","😡","🤬","🤯","😳","🥵","🥶","😱","😨","😰","😥","😓","🤗","🤔","🫡","🤭","🤫","🤥","😶","😐","😑","😬","🙄","😯","😦","😧","😮","😲","🥱","😴","🤤","😪","😵","🤐","🥴","🤢","🤮","🤧","😷","🤒","🤕","👍","👎","👏","🙌","👐","🤝","🙏","💪","❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖","💘","💝","💟","🔥","✨","⭐","🌟","💫","💥","💯","🎉","🎊","✅","❌","⚡","☀️","🌙","🌈","🍎","🍕","🍔","🍟","🌭","🍿","🍩","🍪","⚽","🏀","🏆","🎮","🚗","✈️","🎵","🎶"
    };

    void toggle(final ChatMessageItem msg, final String emoji) {
        String current = msg.reactions.get(myUid);
        boolean removing = emoji.equals(current);
        // DELTA SYNC: the reaction and the message's updatedAt are written in
        // ONE atomic update, so the other user's delta query always sees it.
        java.util.Map<String, Object> reactionUpdates = new java.util.HashMap<>();
        if (removing) {
            msg.reactions.remove(myUid);
            reactionUpdates.put("reactions/" + myUid, null);
        } else {
            msg.reactions.put(myUid, emoji);
            reactionUpdates.put("reactions/" + myUid, emoji);
        }
        reactionUpdates.put("updatedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
        rootRef.child("messages").child(pairId).child(msg.id).updateChildren(reactionUpdates);

        // Publish a lightweight activity event for Home/notifications. This
        // does not alter the original message timestamp or status; it only
        // makes the latest conversation card say who reacted and to what.
        java.util.Map<String, Object> reactionEvent = new java.util.HashMap<>();
        reactionEvent.put("eventType", removing ? "reaction_removed" : "reaction");
        reactionEvent.put("eventActorId", myUid);
        reactionEvent.put("eventEmoji", removing ? "" : emoji);
        reactionEvent.put("eventMessageText", msg.text == null ? "" : msg.text);
        reactionEvent.put("eventMessageType", msg.type == null ? "text" : msg.type);
        reactionEvent.put("eventMessageDurationSeconds", msg.audioDurationSeconds);
        reactionEvent.put("messageId", msg.id);
        reactionEvent.put("senderId", myUid);
        reactionEvent.put("timestamp", System.currentTimeMillis());
        reactionEvent.put("status", "sent");
        rootRef.child("conversations").child(pairId).child("lastMessage").updateChildren(reactionEvent);
        messagesListView.invalidateViews();
    }

    void placeAbove(android.app.Dialog dialog, android.view.View anchor) {
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        window.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        window.setAttributes(window.getAttributes());
        android.view.WindowManager.LayoutParams params = window.getAttributes();
        params.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        params.x = Math.max(utils.dp(8), location[0]);
        params.y = Math.max(utils.dp(8), location[1] - utils.dp(68));
        window.setAttributes(params);
    }

    void showAllEmojiPicker(final ChatMessageItem msg) {
        final android.app.Dialog dialog = new android.app.Dialog(ChatActivity.this);
        android.widget.LinearLayout outer = new android.widget.LinearLayout(ChatActivity.this);
        outer.setOrientation(android.widget.LinearLayout.VERTICAL);
        outer.setPadding(utils.dp(12), utils.dp(10), utils.dp(12), utils.dp(10));
        outer.setBackground(ui.rounded(android.graphics.Color.WHITE, 22));
        android.widget.TextView title = new android.widget.TextView(ChatActivity.this);
        title.setText("Choose an emoji");
        title.setTextSize(13);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(utils.dp(4), 0, 0, utils.dp(8));
        outer.addView(title);
        android.widget.GridLayout grid = new android.widget.GridLayout(ChatActivity.this);
        grid.setColumnCount(6);
        for (final String emoji : allEmojiChoices) {
            android.widget.TextView button = new android.widget.TextView(ChatActivity.this);
            button.setText(emoji);
            button.setTextSize(27);
            button.setAlpha(1.0f);
            button.setGravity(android.view.Gravity.CENTER);
            button.setTypeface(emojiTypeface);
            button.setBackground(ui.rounded(android.graphics.Color.parseColor((grid.getChildCount() % 2 == 0) ? "#FFF1F2" : "#EFF6FF"), 14));
            button.setPadding(utils.dp(2), utils.dp(2), utils.dp(2), utils.dp(2));
            button.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { toggle(msg, emoji); activeReactionDialog[0] = null; if (activeContextDialog[0] != null) activeContextDialog[0].dismiss(); dialog.dismiss(); }
            });
            android.widget.GridLayout.LayoutParams gp = new android.widget.GridLayout.LayoutParams();
            gp.width = utils.dp(48); gp.height = utils.dp(44);
            grid.addView(button, gp);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(ChatActivity.this);
        scroll.addView(grid, new android.widget.ScrollView.LayoutParams(-1, -2));
        outer.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(300)));
        dialog.setContentView(outer);
        activeReactionDialog[0] = dialog;
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            dialog.getWindow().setLayout(utils.dp(330), utils.dp(370));
        }
    }

    void show(final ChatMessageItem msg, final android.view.View anchor) {
        final android.app.Dialog dialog = new android.app.Dialog(ChatActivity.this);
        android.widget.LinearLayout panel = new android.widget.LinearLayout(ChatActivity.this);
        panel.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        panel.setGravity(android.view.Gravity.CENTER_VERTICAL);
        panel.setPadding(utils.dp(10), utils.dp(8), utils.dp(10), utils.dp(8));
        panel.setBackground(ui.rounded(android.graphics.Color.WHITE, 28));
        panel.setElevation(utils.dp(10));
        java.util.ArrayList<String> quickChoices = new java.util.ArrayList<String>();
        String selectedReaction = msg.reactions.get(myUid);
        if (selectedReaction != null && selectedReaction.length() > 0) quickChoices.add(selectedReaction);
        for (String defaultReaction : reactionChoices) {
            if (!quickChoices.contains(defaultReaction) && quickChoices.size() < 6) quickChoices.add(defaultReaction);
        }
        for (final String emoji : quickChoices) {
            final android.widget.TextView button = new android.widget.TextView(ChatActivity.this);
            button.setText(emoji); button.setTextSize(26); button.setAlpha(1.0f); button.setGravity(android.view.Gravity.CENTER); button.setTypeface(emojiTypeface);
            button.setBackground(ui.rounded(android.graphics.Color.parseColor("#FFF7ED"), 18));
            button.setPadding(utils.dp(5), utils.dp(3), utils.dp(5), utils.dp(3));
            if (emoji.equals(msg.reactions.get(myUid))) button.setBackground(ui.rounded(COLOR_BG_LIGHT_BLUE, 18));
            button.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { toggle(msg, emoji); activeReactionDialog[0] = null; if (activeContextDialog[0] != null) activeContextDialog[0].dismiss(); dialog.dismiss(); }
            });
            panel.addView(button, new android.widget.LinearLayout.LayoutParams(utils.dp(42), utils.dp(42)));
        }
        if (selectedReaction != null && selectedReaction.length() > 0) {
            // The selected reaction occupies the add slot; tapping it toggles it off.
            // Once removed, the next long press shows the plus button again.
        }
        android.widget.TextView moreButton = new android.widget.TextView(ChatActivity.this);
        moreButton.setText("+"); moreButton.setTextSize(22); moreButton.setTextColor(COLOR_MUTED); moreButton.setGravity(android.view.Gravity.CENTER); moreButton.setBackground(ui.rounded(COLOR_BG, 18));
        moreButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { activeReactionDialog[0] = null; if (activeContextDialog[0] != null) activeContextDialog[0].dismiss(); dialog.dismiss(); showAllEmojiPicker(msg); }
        });
        android.widget.LinearLayout.LayoutParams moreParams = new android.widget.LinearLayout.LayoutParams(utils.dp(42), utils.dp(42));
        moreParams.leftMargin = utils.dp(2);
        if (selectedReaction == null || selectedReaction.length() == 0) panel.addView(moreButton, moreParams);

        final android.widget.TextView actionsButton = new android.widget.TextView(ChatActivity.this);
        actionsButton.setText("⋮");
        actionsButton.setTextSize(22);
        actionsButton.setTextColor(COLOR_MUTED);
        actionsButton.setGravity(android.view.Gravity.CENTER);
        actionsButton.setBackground(ui.rounded(android.graphics.Color.parseColor("#F1F5F9"), 18));
        actionsButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                if (activeReactionDialog[0] != null && activeReactionDialog[0].isShowing()) activeReactionDialog[0].dismiss();
                contextMenuHelper.show(msg);
            }
        });
        android.widget.LinearLayout.LayoutParams actionParams = new android.widget.LinearLayout.LayoutParams(utils.dp(42), utils.dp(42));
        actionParams.leftMargin = utils.dp(3);
        panel.addView(actionsButton, actionParams);
        dialog.setContentView(panel);
        activeReactionDialog[0] = dialog;
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            placeAbove(dialog, anchor);
        }
    }

    android.widget.LinearLayout buildReactionSummary(ChatMessageItem msg, boolean isMine) {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<String, Integer>();
        for (String emoji : msg.reactions.values()) counts.put(emoji, counts.containsKey(emoji) ? counts.get(emoji) + 1 : 1);
        android.widget.LinearLayout row = new android.widget.LinearLayout(ChatActivity.this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL); row.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL); row.setPadding(utils.dp(8), utils.dp(3), utils.dp(8), utils.dp(3));
        row.setBackground(ui.rounded(android.graphics.Color.parseColor("#E5E7EB"), 16));
        boolean hasAny = false;
        for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
            hasAny = true;
            android.widget.TextView chip = new android.widget.TextView(ChatActivity.this);
            chip.setText(entry.getKey() + (entry.getValue() > 1 ? " " + entry.getValue() : "")); chip.setTextSize(11.5f); chip.setGravity(android.view.Gravity.CENTER); chip.setTypeface(emojiTypeface);
            chip.setTextColor(COLOR_TEXT); chip.setPadding(utils.dp(5), utils.dp(1), utils.dp(5), utils.dp(1));
            chip.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            android.widget.LinearLayout.LayoutParams chipParams = new android.widget.LinearLayout.LayoutParams(-2, -2); chipParams.rightMargin = utils.dp(4); row.addView(chip, chipParams);
        }
        row.setVisibility(hasAny ? android.view.View.VISIBLE : android.view.View.GONE);
        return row;
    }
}
final ReactionHelper reactionHelper = new ReactionHelper();

// ---------- STEP 7: Messages adapter ----------
final android.widget.BaseAdapter[] adapterHolder = new android.widget.BaseAdapter[1];

adapterHolder[0] = new android.widget.BaseAdapter() {
    @Override
    public int getCount() { return renderItems.size(); }
    @Override
    public Object getItem(int position) { return renderItems.get(position); }
    @Override
    public long getItemId(int position) { return position; }

    @Override
    public android.view.View getView(final int position, android.view.View convertView, android.view.ViewGroup parent) {
        Object obj = renderItems.get(position);

        if (obj instanceof UnreadDividerMarker) {
            UnreadDividerMarker marker = (UnreadDividerMarker) obj;
            android.widget.LinearLayout dividerRow = new android.widget.LinearLayout(ChatActivity.this);
            dividerRow.setGravity(android.view.Gravity.CENTER);
            dividerRow.setPadding(0, utils.dp(14), 0, utils.dp(14));

            android.widget.TextView dividerLabel = new android.widget.TextView(ChatActivity.this);
            dividerLabel.setText(marker.count + (marker.count == 1 ? " Unread Message" : " Unread Messages"));
            dividerLabel.setTextColor(android.graphics.Color.parseColor("#475569"));
            dividerLabel.setTextSize(12);
            dividerLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            dividerLabel.setBackground(ui.rounded(COLOR_BORDER, 14));
            dividerLabel.setPadding(utils.dp(14), utils.dp(5), utils.dp(14), utils.dp(5));
            dividerRow.addView(dividerLabel);
            return dividerRow;
        }

        if (obj instanceof DateDividerMarker) {
            DateDividerMarker dateMarker = (DateDividerMarker) obj;
            android.widget.LinearLayout dateRow = new android.widget.LinearLayout(ChatActivity.this);
            dateRow.setGravity(android.view.Gravity.CENTER);
            dateRow.setPadding(0, utils.dp(12), 0, utils.dp(8));

            android.widget.TextView dateLabel = new android.widget.TextView(ChatActivity.this);
            dateLabel.setText(dateMarker.label);
            dateLabel.setTextColor(android.graphics.Color.parseColor("#64748B"));
            dateLabel.setTextSize(11.5f);
            dateLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            dateLabel.setBackground(ui.rounded(android.graphics.Color.parseColor("#E2E8F0"), 12));
            dateLabel.setPadding(utils.dp(12), utils.dp(4), utils.dp(12), utils.dp(4));
            dateRow.addView(dateLabel);
            return dateRow;
        }

        final ChatMessageItem msg = (ChatMessageItem) obj;
        final boolean isMine = msg.senderId != null && msg.senderId.equals(myUid);
        final boolean isDeleted = msg.isDeletedForEveryone;

        android.widget.LinearLayout rowOuter = new android.widget.LinearLayout(ChatActivity.this);
        rowOuter.setOrientation(android.widget.LinearLayout.VERTICAL);
        rowOuter.setGravity(isMine ? android.view.Gravity.END : android.view.Gravity.START);
        rowOuter.setPadding(utils.dp(14), utils.dp(4), utils.dp(14), utils.dp(4));
        rowOuter.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, -2));

        final android.widget.LinearLayout bubble = new android.widget.LinearLayout(ChatActivity.this);
        android.widget.LinearLayout.LayoutParams bubbleParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bubble.setLayoutParams(bubbleParams);
        bubble.setOrientation(android.widget.LinearLayout.VERTICAL);
        bubble.setPadding(utils.dp(13), utils.dp(9), utils.dp(13), utils.dp(9));

        if (isDeleted) {
            bubble.setBackground(ui.dashedOutline(COLOR_BORDER, COLOR_DIVIDER, 16));
        } else if (isMine) {
            bubble.setBackground(ui.rounded(COLOR_BLUE, 16));
        } else {
            bubble.setBackground(ui.outline(COLOR_BORDER, 16));
        }

        String fullText = isDeleted ? "This message was deleted." : (msg.text == null ? "" : msg.text);
        final boolean isAudioMsg = "audio".equals(msg.type) && !isDeleted;

        if (!isDeleted && msg.replyToMessageId != null && msg.replyToMessageId.length() > 0) {
            int replyAccentColor = isMine ? android.graphics.Color.parseColor("#DCEBFF") : COLOR_MUTED;

            android.widget.LinearLayout quotedReplyRow = new android.widget.LinearLayout(ChatActivity.this);
            quotedReplyRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            quotedReplyRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            quotedReplyRow.setPadding(utils.dp(8), utils.dp(5), utils.dp(8), utils.dp(5));
            quotedReplyRow.setBackground(ui.rounded(isMine ? android.graphics.Color.parseColor("#2D8BFF") : COLOR_BG_LIGHT_BLUE, 8));

            android.widget.ImageView replyIconView = new android.widget.ImageView(ChatActivity.this);
            replyIconView.setImageDrawable(new ReplyIconDrawable(replyAccentColor));
            android.widget.LinearLayout.LayoutParams replyIconParams = new android.widget.LinearLayout.LayoutParams(utils.dp(13), utils.dp(13));
            replyIconParams.rightMargin = utils.dp(6);
            quotedReplyRow.addView(replyIconView, replyIconParams);

            android.widget.TextView quotedReply = new android.widget.TextView(ChatActivity.this);
            String quotedReplyText = msg.replyToText == null ? "" : msg.replyToText;
            // Backward-compatible fallback for replies created before audio
            // labels were stored in replyToText.
            if (quotedReplyText.length() == 0 && msg.replyToMessageId != null) {
                for (ChatMessageItem original : messageList) {
                    if (msg.replyToMessageId.equals(original.id)) {
                        quotedReplyText = replyLabelHelper.label(original);
                        break;
                    }
                }
            }
            quotedReply.setText(quotedReplyText);
            quotedReply.setTextSize(11.5f);
            quotedReply.setTextColor(replyAccentColor);
            quotedReply.setTypeface(emojiTypeface);
            quotedReply.setMaxLines(2); quotedReply.setEllipsize(android.text.TextUtils.TruncateAt.END);
            quotedReply.setHorizontallyScrolling(false);
            quotedReply.setMaxWidth(maxBubbleWidth - utils.dp(30));
            quotedReplyRow.addView(quotedReply, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

            android.widget.LinearLayout.LayoutParams quotedReplyRowParams = new android.widget.LinearLayout.LayoutParams(-2, -2);
            quotedReplyRowParams.bottomMargin = utils.dp(5);
            bubble.addView(quotedReplyRow, quotedReplyRowParams);
        }

        if (isAudioMsg) {
            // Voice note bubble — fixed width regardless of clip duration.
            // STAGE 2: check whether this clip is already cached locally (the
            // sender's own copy always is; a receiver's only after downloading).
            java.io.File audioCacheFile = new java.io.File(getFilesDir(), "audio_cache/" + msg.id + ".m4a");
            if (msg.localAudioPath == null && audioCacheFile.exists()) msg.localAudioPath = audioCacheFile.getAbsolutePath();
            final boolean isDownloaded = msg.localAudioPath != null;
            final boolean isDownloadingThis = downloadingAudioIds.contains(msg.id);

            android.widget.LinearLayout audioRow = new android.widget.LinearLayout(ChatActivity.this);
            audioRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            audioRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

            final android.widget.FrameLayout playBtn = new android.widget.FrameLayout(ChatActivity.this);
            playBtn.setBackground(ui.rounded(isMine ? android.graphics.Color.parseColor("#2D8BFF") : COLOR_BG_LIGHT_BLUE, 18));
            final int playIconColor = isMine ? android.graphics.Color.WHITE : COLOR_BLUE;
            final android.widget.ImageView playIconView = new android.widget.ImageView(ChatActivity.this);
            android.widget.FrameLayout.LayoutParams playIconParams = new android.widget.FrameLayout.LayoutParams(utils.dp(16), utils.dp(16));
            playIconParams.gravity = android.view.Gravity.CENTER;

            if (isDownloadingThis) {
                android.widget.ProgressBar spinner = new android.widget.ProgressBar(ChatActivity.this, null, android.R.attr.progressBarStyleSmall);
                spinner.getIndeterminateDrawable().setColorFilter(playIconColor, android.graphics.PorterDuff.Mode.SRC_IN);
                android.widget.FrameLayout.LayoutParams spinnerParams = new android.widget.FrameLayout.LayoutParams(utils.dp(18), utils.dp(18));
                spinnerParams.gravity = android.view.Gravity.CENTER;
                playBtn.addView(spinner, spinnerParams);
            } else if (isDownloaded) {
                playIconView.setImageDrawable(new PlayIconDrawable(playIconColor));
                playBtn.addView(playIconView, playIconParams);
            } else {
                playIconView.setImageDrawable(new DownloadIconDrawable(playIconColor));
                playBtn.addView(playIconView, playIconParams);
            }
            android.widget.LinearLayout.LayoutParams playBtnParams = new android.widget.LinearLayout.LayoutParams(utils.dp(36), utils.dp(36));
            playBtnParams.rightMargin = utils.dp(8);
            audioRow.addView(playBtn, playBtnParams);

            int waveformColor = isMine ? android.graphics.Color.WHITE : COLOR_BLUE;
            int waveformDimColor = isMine ? android.graphics.Color.parseColor("#4DA3FF") : COLOR_BORDER;
            final StaticWaveformView staticWaveform = new StaticWaveformView(ChatActivity.this, waveformColor, waveformDimColor);
            staticWaveform.setLevels(msg.waveformSamples);
            audioRow.addView(staticWaveform, new android.widget.LinearLayout.LayoutParams(utils.dp(140), utils.dp(28)));

            android.widget.TextView durationLabel = new android.widget.TextView(ChatActivity.this);
            durationLabel.setText(timeFmt.mmss(msg.audioDurationSeconds));
            durationLabel.setTextSize(11);
            durationLabel.setTextColor(isMine ? android.graphics.Color.parseColor("#DCEBFF") : COLOR_LIGHT_MUTED);
            android.widget.LinearLayout.LayoutParams durationParams = new android.widget.LinearLayout.LayoutParams(-2, -2);
            durationParams.leftMargin = utils.dp(8);
            audioRow.addView(durationLabel, durationParams);

            playBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    if (downloadingAudioIds.contains(msg.id)) return;
                    if (msg.localAudioPath != null) {
                        // Downloading is not playing. Only playback changes a
                        // received audio from blue/read to yellow/played.
                        if (!isMine && myReadReceiptsHolder[0]
                                && !viewedWithoutReadReceiptIds.contains(msg.id)
                                && !"played".equals(msg.status)) {
                            msg.status = "played";
                            // DELTA SYNC: status + updatedAt in one atomic write.
                            java.util.Map<String, Object> playedUpdates = new java.util.HashMap<>();
                            playedUpdates.put("status", "played");
                            playedUpdates.put("updatedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
                            rootRef.child("messages").child(pairId).child(msg.id).updateChildren(playedUpdates);
                            rebuildMessagesHolder[0].run();
                        }
                        audioPlaybackController.togglePlay(msg, playIconView, playIconColor, staticWaveform);
                    } else {
                        audioDownloadHelper.download(msg);
                    }
                }
            });

            bubble.addView(audioRow, new android.widget.LinearLayout.LayoutParams(-2, -2));
        } else {
            // An edited message renders as a single normal bubble with its
            // current text; the "edited" state is shown only as a small label
            // next to the timestamp below (see metaRow).
            final int currentLimit = expandedMessageLengths.containsKey(msg.id) ? expandedMessageLengths.get(msg.id) : 400;
            boolean isLong = fullText.length() > (currentLimit + 50);
            String shownText = isLong ? fullText.substring(0, currentLimit).trim() + "\u2026" : fullText;

            final android.widget.TextView textView = new android.widget.TextView(ChatActivity.this);
            android.widget.LinearLayout.LayoutParams tvParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            );
            textView.setLayoutParams(tvParams);
            if (isDeleted) {
                textView.setText(shownText);
                textView.setTextColor(COLOR_MUTED);
                textView.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC));
                textView.setTextSize(14.5f);
            } else {
                emojiRenderHelper.styleMessageText(textView, shownText, isMine ? android.graphics.Color.WHITE : COLOR_TEXT, emojiTypeface);
            }
            textView.setMaxWidth(maxBubbleWidth);
            textView.setMinWidth(utils.dp(48));
            bubble.addView(textView);

            if (isLong) {
                android.widget.TextView readMoreBtn = new android.widget.TextView(ChatActivity.this);
                readMoreBtn.setText("Read more...");
                readMoreBtn.setTextSize(12.5f);
                readMoreBtn.setTypeface(null, android.graphics.Typeface.BOLD);
                readMoreBtn.setTextColor(isMine ? android.graphics.Color.WHITE : COLOR_BLUE);
                readMoreBtn.setPadding(0, utils.dp(4), 0, 0);
                readMoreBtn.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        expandedMessageLengths.put(msg.id, currentLimit + 1000);
                        adapterHolder[0].notifyDataSetChanged();
                    }
                });
                bubble.addView(readMoreBtn);
            }
        }

        android.widget.LinearLayout metaRow = new android.widget.LinearLayout(ChatActivity.this);
        metaRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        metaRow.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);

        // "edited" shows as a small label right next to the send time, same row.
        // Deleted-for-everyone messages keep showing their timestamp.
        int metaTextColor = isDeleted ? COLOR_LIGHT_MUTED : (isMine ? android.graphics.Color.parseColor("#DCEBFF") : COLOR_LIGHT_MUTED);

        if (!isDeleted && msg.isEdited) {
            android.widget.TextView editedLabel = new android.widget.TextView(ChatActivity.this);
            editedLabel.setText("edited");
            editedLabel.setTextSize(10.5f);
            editedLabel.setTextColor(metaTextColor);
            android.widget.LinearLayout.LayoutParams editedLabelParams = new android.widget.LinearLayout.LayoutParams(-2, -2);
            editedLabelParams.rightMargin = utils.dp(5);
            metaRow.addView(editedLabel, editedLabelParams);
        }

        android.widget.TextView timeLabel = new android.widget.TextView(ChatActivity.this);
        timeLabel.setText(new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(new java.util.Date(msg.timestamp)));
        timeLabel.setTextSize(10.5f);
        timeLabel.setTextColor(metaTextColor);
        metaRow.addView(timeLabel, new android.widget.LinearLayout.LayoutParams(-2, -2));

        if (isMine && !isDeleted) {
            boolean isPendingTick = pendingSendIds.contains(msg.id);
            android.widget.TextView tickLabel = new android.widget.TextView(ChatActivity.this);
            tickLabel.setTextSize(11f);
            android.widget.LinearLayout.LayoutParams tickParams = new android.widget.LinearLayout.LayoutParams(-2, -2);
            tickParams.leftMargin = utils.dp(4);
            if (isPendingTick) {
                // Still sending — custom clock icon instead of a checkmark.
                android.widget.ImageView clockView = new android.widget.ImageView(ChatActivity.this);
                clockView.setImageDrawable(new ClockIconDrawable(metaTextColor));
                android.widget.LinearLayout.LayoutParams clockParams = new android.widget.LinearLayout.LayoutParams(utils.dp(11), utils.dp(11));
                clockParams.leftMargin = utils.dp(4);
                metaRow.addView(clockView, clockParams);
            } else if ("played".equals(msg.status)) {
                // Yellow double ticks mean this audio message was played.
                tickLabel.setText("\u2713\u2713");
                tickLabel.setTextColor(COLOR_TICK_PLAYED);
                metaRow.addView(tickLabel, tickParams);
            } else if ("read".equals(msg.status)) {
                tickLabel.setText("\u2713\u2713");
                tickLabel.setTextColor(COLOR_TICK_READ);
                metaRow.addView(tickLabel, tickParams);
            } else if ("delivered".equals(msg.status) || "read_hidden".equals(msg.status)) {
                // read_hidden means the other side viewed it with read receipts off —
                // never reveal that as "read" to the sender, show delivered instead.
                tickLabel.setText("\u2713\u2713");
                tickLabel.setTextColor(metaTextColor);
                metaRow.addView(tickLabel, tickParams);
            } else {
                tickLabel.setText("\u2713");
                tickLabel.setTextColor(metaTextColor);
                metaRow.addView(tickLabel, tickParams);
            }
        } else if (isMine && isDeleted && pendingDeleteIds.contains(msg.id)) {
            // A message deleted for everyone while still mid-send: keep the clock
            // showing next to the timestamp until the cancellation write is
            // actually confirmed against Firebase — then it drops on its own.
            android.widget.ImageView clockView = new android.widget.ImageView(ChatActivity.this);
            clockView.setImageDrawable(new ClockIconDrawable(metaTextColor));
            android.widget.LinearLayout.LayoutParams clockParams = new android.widget.LinearLayout.LayoutParams(utils.dp(11), utils.dp(11));
            clockParams.leftMargin = utils.dp(4);
            metaRow.addView(clockView, clockParams);
        }

        android.widget.LinearLayout.LayoutParams metaParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        metaParams.topMargin = utils.dp(4);
        metaParams.gravity = android.view.Gravity.END; 
        
        bubble.addView(metaRow, metaParams);

        final float[] swipeStartX = { 0f };
        final boolean[] longPressTriggered = { false };
        final boolean[] replyTriggered = { false };
        final Runnable[] reactionRunnable = new Runnable[1];
        final Runnable[] menuRunnable = new Runnable[1];
        bubble.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override public boolean onTouch(final android.view.View v, android.view.MotionEvent event) {
                if (isDeleted) return false;
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    swipeStartX[0] = event.getRawX(); longPressTriggered[0] = false; replyTriggered[0] = false;
                    reactionRunnable[0] = new Runnable() { @Override public void run() { longPressTriggered[0] = true; reactionHelper.show(msg, bubble); } };
                    menuRunnable[0] = new Runnable() { @Override public void run() { longPressTriggered[0] = true; contextMenuHelper.show(msg); } };
                    // A message still sending only gets the restricted context
                    // menu (Delete for Everyone) — skip the reaction popup entirely.
                    if (pendingSendIds.contains(msg.id)) {
                        mainHandler.postDelayed(menuRunnable[0], 500);
                    } else {
                        mainHandler.postDelayed(reactionRunnable[0], 500);
                        mainHandler.postDelayed(menuRunnable[0], 1400);
                    }
                    return true;
                }
                if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                    float distance = event.getRawX() - swipeStartX[0];
                    if (Math.abs(distance) > utils.dp(18)) {
                        if (reactionRunnable[0] != null) mainHandler.removeCallbacks(reactionRunnable[0]);
                        if (menuRunnable[0] != null) mainHandler.removeCallbacks(menuRunnable[0]);
                    }
                    if (distance > 0 && !longPressTriggered[0]) {
                        v.setTranslationX(Math.min(distance, utils.dp(72)));
                        if (distance >= utils.dp(56) && !replyTriggered[0]) {
                            replyTriggered[0] = true;
                            messageToReplyHolder[0] = msg;
                            String replySnippet = replyLabelHelper.label(msg);
                            replyPreviewText.setText("Replying to: " + replySnippet);
                            replyPreviewBar.setVisibility(android.view.View.VISIBLE);
                            chatInput.requestFocus();
                        }
                    }
                    return true;
                }
                if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    if (reactionRunnable[0] != null) mainHandler.removeCallbacks(reactionRunnable[0]);
                    if (menuRunnable[0] != null) mainHandler.removeCallbacks(menuRunnable[0]);
                    float distance = event.getRawX() - swipeStartX[0];
                    // The custom touch listener consumes the gesture, so explicitly
                    // dispatch a normal tap to the reply bubble when no swipe occurred.
                    if (!longPressTriggered[0] && Math.abs(distance) < utils.dp(18)) {
                        v.performClick();
                    }
                    if (!longPressTriggered[0] && distance >= utils.dp(56) && !replyTriggered[0]) {
                        replyTriggered[0] = true;
                        messageToReplyHolder[0] = msg;
                        String replySnippet = replyLabelHelper.label(msg);
                        replyPreviewText.setText("Replying to: " + replySnippet);
                        replyPreviewBar.setVisibility(android.view.View.VISIBLE); chatInput.requestFocus();
                    }
                    v.animate().translationX(0f).setDuration(180).setInterpolator(new android.view.animation.OvershootInterpolator()).start();
                    return true;
                }
                if (event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                    if (reactionRunnable[0] != null) mainHandler.removeCallbacks(reactionRunnable[0]);
                    if (menuRunnable[0] != null) mainHandler.removeCallbacks(menuRunnable[0]);
                    v.animate().translationX(0f).setDuration(180).start();
                }
                return true;
            }
        });

        bubble.setOnLongClickListener(new android.view.View.OnLongClickListener() {
            @Override public boolean onLongClick(android.view.View v) {
                if (!isDeleted) return false;
                contextMenuHelper.show(msg);
                return true;
            }
        });

        rowOuter.addView(bubble);
        if (msg.replyToMessageId != null && msg.replyToMessageId.length() > 0) {
            bubble.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    int targetPosition = -1;
                    for (int i = 0; i < renderItems.size(); i++) {
                        Object candidate = renderItems.get(i);
                        if (candidate instanceof ChatMessageItem && msg.replyToMessageId.equals(((ChatMessageItem) candidate).id)) {
                            if (((ChatMessageItem) candidate).isDeletedForEveryone) return;
                            targetPosition = i; break;
                        }
                    }
                    if (targetPosition < 0) return;
                    final int destination = targetPosition;
                    // Always move the referenced message toward the middle, even
                    // when it is already visible.
                    int centerOffset = Math.max(0, messagesListView.getHeight() / 2 - utils.dp(90));
                    messagesListView.smoothScrollToPositionFromTop(destination, centerOffset, 320);
                    messagesListView.postDelayed(new Runnable() {
                        @Override public void run() {
                            int nowFirst = messagesListView.getFirstVisiblePosition();
                            int childIndex = destination - nowFirst;
                            if (childIndex < 0 || childIndex >= messagesListView.getChildCount()) return;
                            final android.view.View targetRow = messagesListView.getChildAt(childIndex);
                            if (targetRow == null) return;

                            // Correct the remaining offset using the actual row
                            // height, then highlight only after that correction.
                            int rowCenter = (targetRow.getTop() + targetRow.getBottom()) / 2;
                            int viewportCenter = messagesListView.getHeight() / 2;
                            int correction = rowCenter - viewportCenter;
                            if (Math.abs(correction) > utils.dp(2)) {
                                messagesListView.smoothScrollBy(correction, 220);
                            }

                            messagesListView.postDelayed(new Runnable() {
                                @Override public void run() {
                                    int settledFirst = messagesListView.getFirstVisiblePosition();
                                    int settledChildIndex = destination - settledFirst;
                                    if (settledChildIndex < 0 || settledChildIndex >= messagesListView.getChildCount()) return;
                                    final android.view.View settledRow = messagesListView.getChildAt(settledChildIndex);
                                    if (settledRow == null) return;

                                    // Fade in, hold, then fade out over 1.8 seconds.
                                    final android.graphics.drawable.ColorDrawable highlight = new android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#9CC8FF"));
                                    highlight.setAlpha(0);
                                    settledRow.setBackground(highlight);
                                    android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofInt(0, 235, 235, 0);
                                    fade.setDuration(1800);
                                    fade.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                                        @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                                            highlight.setAlpha((Integer) a.getAnimatedValue());
                                        }
                                    });
                                    fade.start();
                                }
                            }, 260);
                        }
                    }, 380);
                }
            });
        }
        if (!isDeleted) {
            android.widget.LinearLayout reactionSummary = reactionHelper.buildReactionSummary(msg, isMine);
            android.widget.LinearLayout.LayoutParams reactionParams = new android.widget.LinearLayout.LayoutParams(-2, -2);
            reactionParams.gravity = isMine ? android.view.Gravity.END : android.view.Gravity.START;
            reactionParams.topMargin = -utils.dp(2);
            rowOuter.addView(reactionSummary, reactionParams);
        }
        return rowOuter;
    }
};

final android.widget.BaseAdapter messagesAdapter = adapterHolder[0];
messagesListView.setAdapter(messagesAdapter);

messagesListView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
    @Override
    public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
        if (scrollState == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
            userHasScrolled[0] = true;
            if (sessionUnreadActive[0]) {
                sessionUnreadActive[0] = false;
                sessionFirstUnreadId[0] = null;
                messagesListView.post(new Runnable() {
                    @Override
                    public void run() { messagesAdapter.notifyDataSetChanged(); }
                });
            }
        }
    }

    @Override
    public void onScroll(android.widget.AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        boolean atBottom = totalItemCount == 0 || (firstVisibleItem + visibleItemCount) >= totalItemCount;
        scrollToBottomBtn.setVisibility(atBottom ? android.view.View.GONE : android.view.View.VISIBLE);
        // Only trigger pagination once the user has genuinely scrolled —
        // otherwise this could fire during the initial jump-to-bottom sequence.
        if (firstVisibleItem <= 2 && totalItemCount > 0 && userHasScrolled[0] && loadOlderMessagesHolder[0] != null) {
            loadOlderMessagesHolder[0].run();
        }
    }
});

scrollToBottomBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        scrollHelper.smoothScrollToBottom();
    }
});

// ---------- STEP 8: Delivered / read sync helper ----------
// DELTA SYNC: every status change also writes updatedAt in the same multi-path
// update, so the sender's delta query picks the new tick up on their next open.
class MessageSyncHelper {
    void markDelivered() {
        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        for (ChatMessageItem m : messageList) {
            if (m.receiverId != null && m.receiverId.equals(myUid) && "sent".equals(m.status)) {
                updates.put(m.id + "/status", "delivered");
                updates.put(m.id + "/updatedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
                m.status = "delivered";
            }
        }
        if (!updates.isEmpty()) {
            rootRef.child("messages").child(pairId).updateChildren(updates);
        }
    }

    void markRead() {
        // Opening the chat while receipts are off is still a local viewing event,
        // but it must never become a blue read tick later if receipts are enabled.
        if (!myReadReceiptsHolder[0]) {
            java.util.Map<String, Object> hiddenReadUpdates = new java.util.HashMap<>();
            for (ChatMessageItem m : messageList) {
                if (m.senderId != null && m.senderId.equals(otherUid)
                        && !"read".equals(m.status)
                        && !"played".equals(m.status)) {
                    viewedWithoutReadReceiptIds.add(m.id);
                    if (!"read_hidden".equals(m.status)) {
                        m.status = "read_hidden";
                        hiddenReadUpdates.put(m.id + "/status", "read_hidden");
                        hiddenReadUpdates.put(m.id + "/updatedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
                    }
                }
            }
            persistViewedWithoutReadReceipts.run();
            if (!hiddenReadUpdates.isEmpty()) rootRef.child("messages").child(pairId).updateChildren(hiddenReadUpdates);
            return;
        }

        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        boolean any = false;
        for (ChatMessageItem m : messageList) {
            if (m.senderId != null && m.senderId.equals(otherUid)
                    && !"read".equals(m.status)
                    && !"played".equals(m.status)
                    && !viewedWithoutReadReceiptIds.contains(m.id)) {
                updates.put(m.id + "/status", "read");
                updates.put(m.id + "/updatedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
                m.status = "read";
                any = true;
            }
        }
        if (any) {
            rootRef.child("messages").child(pairId).updateChildren(updates);
        }
    }
}
final MessageSyncHelper messageSync = new MessageSyncHelper();

// ---------- STEP 9: Date Formatter Helper ----------
class DateHelper {
    String getFormattedDate(long timestamp) {
        java.util.Calendar msgDate = java.util.Calendar.getInstance();
        msgDate.setTimeInMillis(timestamp);

        java.util.Calendar today = java.util.Calendar.getInstance();
        
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);

        if (msgDate.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
            msgDate.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "Today";
        } else if (msgDate.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
                   msgDate.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "Yesterday";
        } else {
            long diffMillis = today.getTimeInMillis() - msgDate.getTimeInMillis();
            long diffDays = diffMillis / (24 * 60 * 60 * 1000);
            if (diffDays < 7 && msgDate.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)) {
                return new java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(msgDate.getTime());
            } else {
                return new java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(msgDate.getTime());
            }
        }
    }
}
final DateHelper dateHelper = new DateHelper();

// ---------- STEP 10: Rebuild render list ----------
final Runnable rebuildMessages = new Runnable() {
    @Override
    public void run() {
        renderItems.clear();

        java.util.List<ChatMessageItem> visible = new java.util.ArrayList<>();
        for (ChatMessageItem m : messageList) {
            if (!m.deletedForMe) visible.add(m);
        }
        java.util.Collections.sort(visible, new java.util.Comparator<ChatMessageItem>() {
            @Override
            public int compare(ChatMessageItem a, ChatMessageItem b) {
                int byTime = Long.compare(a.timestamp, b.timestamp);
                if (byTime != 0) return byTime;
                if (a.id == null || b.id == null) return 0;
                return a.id.compareTo(b.id);
            }
        });

        int dividerRenderIndex = -1;
        String lastDateLabel = "";

        for (ChatMessageItem m : visible) {
            String currentDateLabel = dateHelper.getFormattedDate(m.timestamp);
            if (!currentDateLabel.equals(lastDateLabel)) {
                renderItems.add(new DateDividerMarker(currentDateLabel));
                lastDateLabel = currentDateLabel;
            }

            if (sessionUnreadActive[0] && sessionFirstUnreadId[0] != null && m.id.equals(sessionFirstUnreadId[0])) {
                renderItems.add(new UnreadDividerMarker(sessionUnreadCount[0]));
                dividerRenderIndex = renderItems.size() - 1;
            }
            renderItems.add(m);
        }

        messagesAdapter.notifyDataSetChanged();

        int lastVisible = messagesListView.getLastVisiblePosition();
        boolean shouldScrollToBottom = lastVisible >= renderItems.size() - 3 || renderItems.isEmpty();

        if (!userHasScrolled[0] || isInitialScroll[0] || (!hasInitializedBarrier[0] && dividerRenderIndex >= 0)) {
            if (!renderItems.isEmpty()) {
                final int lastMessagePosition = renderItems.size() - 1;

                messagesListView.post(new Runnable() {
                    @Override
                    public void run() {
                        messagesListView.setSelection(lastMessagePosition);

                        messagesListView.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (messagesAdapter.getCount() > 0) {
                                    messagesListView.setSelection(messagesAdapter.getCount() - 1);
                                }
                            }
                        }, 80);
                    }
                });
            }

            isInitialScroll[0] = false;
        } else if (shouldScrollToBottom && !renderItems.isEmpty()) {
            scrollHelper.smoothScrollToBottom();
        }

        // DELTA SYNC: never write the cache until the old cache has been merged
        // in — otherwise a half-loaded list could overwrite the saved history.
        if (!cacheMerged[0]) return;

        final java.util.ArrayList<ChatMessageItem> cacheSnapshot = new java.util.ArrayList<ChatMessageItem>(messageList);
        if (persistChatCache[0] != null) mainHandler.removeCallbacks(persistChatCache[0]);
        persistChatCache[0] = new Runnable() {
            @Override public void run() {
                persistenceExecutor.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            org.json.JSONArray arr = new org.json.JSONArray();
                            long maxUpdatedAt = 0L;
                            for (ChatMessageItem m : cacheSnapshot) {
                                org.json.JSONObject obj = new org.json.JSONObject();
                                obj.put("id", m.id); obj.put("senderId", m.senderId); obj.put("receiverId", m.receiverId);
                                obj.put("text", m.text); obj.put("timestamp", m.timestamp); obj.put("isEdited", m.isEdited);
                                obj.put("editedAt", m.editedAt); obj.put("editedWatermark", m.editedWatermark);
                                org.json.JSONObject reactionsObj = new org.json.JSONObject();
                                for (java.util.Map.Entry<String, String> reaction : m.reactions.entrySet()) reactionsObj.put(reaction.getKey(), reaction.getValue());
                                obj.put("reactions", reactionsObj); obj.put("replyToMessageId", m.replyToMessageId);
                                obj.put("replyToSenderId", m.replyToSenderId); obj.put("replyToText", m.replyToText);
                                org.json.JSONArray historyArr = new org.json.JSONArray();
                                for (String historyText : m.editHistory) historyArr.put(historyText);
                                obj.put("editHistory", historyArr); obj.put("isDeletedForEveryone", m.isDeletedForEveryone);
                                obj.put("status", m.status); obj.put("deletedForMe", m.deletedForMe);
                                // STAGE 2: persist audio metadata too, so a cold app
                                // restart shows the bubble immediately.
                                obj.put("type", m.type); obj.put("audioDurationSeconds", m.audioDurationSeconds);
                                obj.put("updatedAt", m.updatedAt);
                                if (m.updatedAt > maxUpdatedAt) maxUpdatedAt = m.updatedAt;
                                if (m.waveformSamples != null) {
                                    org.json.JSONArray wfArr = new org.json.JSONArray();
                                    for (int lvl : m.waveformSamples) wfArr.put(lvl);
                                    obj.put("waveformSamples", wfArr);
                                }
                                arr.put(obj);
                            }
                            // Cache + lastSync are written in ONE commit so they can
                            // never disagree. A 5s safety margin is subtracted so a
                            // slightly-off local estimate of the server clock can
                            // never make the next open skip a change.
                            android.content.SharedPreferences.Editor editor = cachePrefs.edit();
                            editor.putString(cacheKey, arr.toString());
                            if (maxUpdatedAt > 0L) {
                                long newSync = Math.max(cachePrefs.getLong(lastSyncKey, 0L), maxUpdatedAt - 5000L);
                                editor.putLong(lastSyncKey, newSync);
                            }
                            editor.commit();
                        } catch (Throwable ignored) {}
                    }
                });
            }
        };
        mainHandler.postDelayed(persistChatCache[0], 120);
    }
 };
rebuildMessagesHolder[0] = rebuildMessages;

// ---------- STEP 10A: Batched rebuild scheduler ----------
// Collapses any burst of rapid-fire updates into a single rebuild a moment
// after the burst ends, instead of one heavy rebuild per event.
final Runnable[] pendingRebuildRunnable = new Runnable[1];
class RebuildScheduler {
    void schedule() {
        if (pendingRebuildRunnable[0] != null) mainHandler.removeCallbacks(pendingRebuildRunnable[0]);
        pendingRebuildRunnable[0] = new Runnable() {
            @Override public void run() {
                pendingRebuildRunnable[0] = null;
                rebuildMessages.run();
            }
        };
        mainHandler.postDelayed(pendingRebuildRunnable[0], 30);
    }
}
final RebuildScheduler rebuildScheduler = new RebuildScheduler();

// ---------- STEP 10B: Unread Barrier Helper ----------
class BarrierHelper {
    void checkAndInit() {
        if (hasInitializedBarrier[0]) return;
        if (initialUnreadCount[0] != -1 && messagesLoaded[0]) {
            if (initialUnreadCount[0] > 0 && !messageList.isEmpty()) {
                // messageList is not guaranteed to be in time order (cache items
                // and live items arrive separately), so walk a sorted copy.
                java.util.List<ChatMessageItem> ordered = new java.util.ArrayList<ChatMessageItem>(messageList);
                java.util.Collections.sort(ordered, new java.util.Comparator<ChatMessageItem>() {
                    @Override public int compare(ChatMessageItem a, ChatMessageItem b) {
                        return Long.compare(a.timestamp, b.timestamp);
                    }
                });
                int c = 0;
                String firstId = null;
                for (int i = ordered.size() - 1; i >= 0; i--) {
                    ChatMessageItem m = ordered.get(i);
                    if (m.senderId != null && m.senderId.equals(otherUid)) {
                        firstId = m.id;
                        c++;
                        if (c == initialUnreadCount[0]) break;
                    }
                }
                if (firstId != null) {
                    sessionFirstUnreadId[0] = firstId;
                    sessionUnreadCount[0] = initialUnreadCount[0];
                }
            }
            hasInitializedBarrier[0] = true;
            rebuildMessages.run();
        }
    }
}
final BarrierHelper barrierHelper = new BarrierHelper();

// ---------- STEP 11: Load cached messages ----------
// Parsed on a background thread so a large local read can never freeze the
// screen; the cached list pops in the moment parsing finishes.
pendingPersist.load();
persistenceExecutor.execute(new Runnable() {
    @Override public void run() {
        final java.util.List<ChatMessageItem> loadedItems = new java.util.ArrayList<ChatMessageItem>();
        final java.util.Map<String, java.util.Map<String, Object>> loadedPending = new java.util.HashMap<String, java.util.Map<String, Object>>();
        try {
            String cachedJson = cachePrefs.getString(cacheKey, null);
            if (cachedJson != null) {
                org.json.JSONArray arr = new org.json.JSONArray(cachedJson);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject obj = arr.getJSONObject(i);
                    ChatMessageItem item = new ChatMessageItem();
                    item.id = obj.optString("id", null);
                    item.senderId = obj.optString("senderId", null);
                    item.receiverId = obj.optString("receiverId", null);
                    item.text = obj.isNull("text") ? null : obj.optString("text");
                    item.timestamp = obj.optLong("timestamp");
                    item.updatedAt = obj.optLong("updatedAt", 0L);
                    item.isEdited = obj.optBoolean("isEdited");
                    item.editedAt = obj.optLong("editedAt");
                    item.editedWatermark = obj.optBoolean("editedWatermark", false);
                    org.json.JSONObject reactionsObj = obj.optJSONObject("reactions");
                    if (reactionsObj != null) {
                        java.util.Iterator<String> reactionKeys = reactionsObj.keys();
                        while (reactionKeys.hasNext()) {
                            String reactionUid = reactionKeys.next();
                            item.reactions.put(reactionUid, reactionsObj.optString(reactionUid, ""));
                        }
                    }
                    item.replyToMessageId = obj.optString("replyToMessageId", "");
                    item.replyToSenderId = obj.optString("replyToSenderId", "");
                    item.replyToText = obj.optString("replyToText", "");
                    org.json.JSONArray historyArr = obj.optJSONArray("editHistory");
                    if (historyArr != null) {
                        for (int h = 0; h < historyArr.length(); h++) item.editHistory.add(historyArr.optString(h, ""));
                    }
                    item.isDeletedForEveryone = obj.optBoolean("isDeletedForEveryone");
                    item.status = obj.isNull("status") ? null : obj.optString("status");
                    item.deletedForMe = obj.optBoolean("deletedForMe");
                    item.type = obj.optString("type", "text");
                    item.audioDurationSeconds = obj.optInt("audioDurationSeconds", 0);
                    org.json.JSONArray waveformArr = obj.optJSONArray("waveformSamples");
                    if (waveformArr != null) {
                        int[] wf = new int[waveformArr.length()];
                        for (int wi = 0; wi < waveformArr.length(); wi++) wf[wi] = waveformArr.optInt(wi, 0);
                        item.waveformSamples = wf;
                    }
                    loadedItems.add(item);
                    if (item.id != null && pendingSendIds.contains(item.id)) {
                        java.util.Map<String, Object> pending = new java.util.HashMap<String, Object>();
                        pending.put("senderId", item.senderId); pending.put("receiverId", item.receiverId); pending.put("text", item.text);
                        pending.put("timestamp", item.timestamp); pending.put("serverTimestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
                        pending.put("updatedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
                        pending.put("isEdited", item.isEdited); pending.put("editedWatermark", item.editedWatermark);
                        pending.put("editHistory", new java.util.ArrayList<String>(item.editHistory));
                        pending.put("replyToMessageId", item.replyToMessageId); pending.put("replyToSenderId", item.replyToSenderId);
                        pending.put("replyToText", item.replyToText); pending.put("isDeletedForEveryone", item.isDeletedForEveryone);
                        pending.put("status", "sent"); loadedPending.put(item.id, pending);
                    }
                }
            }
        } catch (Exception e) {
            cacheLoadFailed[0] = true;
        }

        mainHandler.post(new Runnable() {
            @Override public void run() {
                // Every cached item is checked against what's already in
                // messageList (added by the live listener) before being added,
                // so the live/authoritative copy always wins and nothing is
                // ever inserted twice.
                for (ChatMessageItem loaded : loadedItems) {
                    boolean alreadyPresent = false;
                    for (int i = 0; i < messageList.size(); i++) {
                        if (loaded.id != null && loaded.id.equals(messageList.get(i).id)) {
                            alreadyPresent = true;
                            break;
                        }
                    }
                    if (!alreadyPresent) messageList.add(loaded);
                }
                for (java.util.Map.Entry<String, java.util.Map<String, Object>> entry : loadedPending.entrySet()) {
                    if (!pendingPayloads.containsKey(entry.getKey())) pendingPayloads.put(entry.getKey(), entry.getValue());
                }
                // From now on it is safe to write the cache + lastSync.
                cacheMerged[0] = true;
                // If the cache was unreadable but we were in delta mode, the
                // history would be incomplete — drop lastSync and re-fetch the
                // recent window once.
                if (cacheLoadFailed[0] && useDeltaSync) {
                    cachePrefs.edit().remove(lastSyncKey).apply();
                    if (fullRecoveryHolder[0] != null) fullRecoveryHolder[0].run();
                }
                if (!loadedItems.isEmpty() || !messageList.isEmpty()) rebuildMessagesHolder[0].run();
            }
        });
    }
});

// ---------- STEP 12: Edit-cancel + typing + millisecond send handler ----------
editCancelBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        messageToEditHolder[0] = null;
        editIndicatorBar.setVisibility(android.view.View.GONE);
        watermarkOptions.setVisibility(android.view.View.GONE);
        editWatermarkChoice[0] = false;
        refreshWatermarkOptions.run();
        chatInput.setText("");
        chatInput.clearFocus();
    }
});

chatInput.addTextChangedListener(new android.text.TextWatcher() {
    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {}
    @Override
    public void afterTextChanged(android.text.Editable s) {
        // Voice note: mic <-> send swap, only while not actively recording.
        // Whitespace-only text keeps the mic, matching what send actually does.
        if (!isRecordingActive[0]) {
            boolean hasRealText = s.toString().trim().length() > 0;
            micBtn.setVisibility(hasRealText ? android.view.View.GONE : android.view.View.VISIBLE);
            sendBtn.setVisibility(hasRealText ? android.view.View.VISIBLE : android.view.View.GONE);
        }

        if (!myShowTypingHolder[0]) {
            if (isTypingLocally[0]) {
                isTypingLocally[0] = false;
                rootRef.child("conversations").child(pairId).child("typing").child(myUid).setValue(false);
            }
            return;
        }

        if (s.length() > 0) {
            if (!isTypingLocally[0]) {
                isTypingLocally[0] = true;
                rootRef.child("conversations").child(pairId).child("typing").child(myUid).setValue(true);
            }
            if (typingStopRunnableHolder[0] != null) mainHandler.removeCallbacks(typingStopRunnableHolder[0]);
            typingStopRunnableHolder[0] = new Runnable() {
                @Override
                public void run() {
                    isTypingLocally[0] = false;
                    rootRef.child("conversations").child(pairId).child("typing").child(myUid).setValue(false);
                }
            };
            mainHandler.postDelayed(typingStopRunnableHolder[0], 2000);
        } else {
            isTypingLocally[0] = false;
            if (typingStopRunnableHolder[0] != null) mainHandler.removeCallbacks(typingStopRunnableHolder[0]);
            rootRef.child("conversations").child(pairId).child("typing").child(myUid).setValue(false);
        }
    }
});

sendBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        // Voice note: sendBtn doubles as the "send" control while a locked
        // recording is in progress (chatInput is hidden in that state).
        if (isRecordingLocked[0]) {
            finishRecordingHolder[0].run();
            return;
        }

        final String text = chatInput.getText().toString().trim();
        if (text.length() == 0) return;

        if (sessionUnreadActive[0]) {
            sessionUnreadActive[0] = false;
            sessionFirstUnreadId[0] = null;
        }

        if (messageToEditHolder[0] != null) {
            final ChatMessageItem editing = messageToEditHolder[0];
            messageToEditHolder[0] = null;
            editIndicatorBar.setVisibility(android.view.View.GONE);
            watermarkOptions.setVisibility(android.view.View.GONE);
            final boolean useEditedWatermark = true;
            editWatermarkChoice[0] = false;
            refreshWatermarkOptions.run();
            chatInput.setText("");
            chatInput.clearFocus();

            if (text.equals(editing.text)) return;

            java.util.Map<String, Object> updates = new java.util.HashMap<>();
            java.util.List<String> nextEditHistory = new java.util.ArrayList<>();
            if (editing.editHistory != null) nextEditHistory.addAll(editing.editHistory);
            // Store the initial text on the first edit, then append each
            // previous version before writing the new current text.
            if (editing.editHistory == null || editing.editHistory.isEmpty()) {
                nextEditHistory.add(editing.text == null ? "" : editing.text);
            } else if (editing.text != null && (nextEditHistory.isEmpty() || !editing.text.equals(nextEditHistory.get(nextEditHistory.size() - 1)))) {
                nextEditHistory.add(editing.text);
            }
            updates.put("text", text);
            updates.put("isEdited", true);
            updates.put("editedAt", System.currentTimeMillis());
            updates.put("editedWatermark", useEditedWatermark);
            updates.put("editHistory", nextEditHistory);
            updates.put("updatedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
            editing.editedWatermark = useEditedWatermark;
            editing.editHistory = nextEditHistory;
            editing.isEdited = true;
            editing.text = text;
            rootRef.child("messages").child(pairId).child(editing.id).updateChildren(updates);
            messagesAdapter.notifyDataSetChanged();

            rootRef.child("conversations").child(pairId).child("lastMessage").child("timestamp")
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                        Long lastTs = snapshot.getValue(Long.class);
                        if (lastTs != null && lastTs == editing.timestamp) {
                            rootRef.child("conversations").child(pairId).child("lastMessage").child("text").setValue(text);
                        }
                    }
                    @Override
                    public void onCancelled(com.google.firebase.database.DatabaseError error) {}
                });
            return;
        }

        final ChatMessageItem replyTarget = messageToReplyHolder[0];
        final String replyId = replyTarget == null ? "" : replyTarget.id;
        final String replySender = replyTarget == null ? "" : (replyTarget.senderId == null ? "" : replyTarget.senderId);
        final String replyText = replyTarget == null ? "" : replyLabelHelper.label(replyTarget);
        messageToReplyHolder[0] = null;
        replyPreviewBar.setVisibility(android.view.View.GONE);
        chatInput.setText("");
        
        // Stop typing immediately when sending
        isTypingLocally[0] = false;
        if (typingStopRunnableHolder[0] != null) mainHandler.removeCallbacks(typingStopRunnableHolder[0]);
        rootRef.child("conversations").child(pairId).child("typing").child(myUid).setValue(false);

        final long now = System.currentTimeMillis();
        final String initialStatus = "sent";
        final com.google.firebase.database.DatabaseReference newMsgRef = rootRef.child("messages").child(pairId).push();
        final String newMsgId = newMsgRef.getKey();
        
        pendingSendIds.add(newMsgId);
        pendingPayloads.put(newMsgId, new java.util.HashMap<String, Object>());
        pendingPersist.save();

        ChatMessageItem optimisticMsg = new ChatMessageItem();
        optimisticMsg.id = newMsgId;
        optimisticMsg.senderId = myUid;
        optimisticMsg.receiverId = otherUid;
        optimisticMsg.text = text;
        optimisticMsg.timestamp = now;
        optimisticMsg.isEdited = false;
        optimisticMsg.isDeletedForEveryone = false;
        optimisticMsg.status = initialStatus;
        optimisticMsg.deletedForMe = false;
        optimisticMsg.replyToMessageId = replyId;
        optimisticMsg.replyToSenderId = replySender;
        optimisticMsg.replyToText = replyText;

        messageList.add(optimisticMsg);
        rebuildMessages.run();
        
        // Jump to the newest message immediately after send, even if the
        // user was reading an older part of the conversation.
        if (messagesAdapter.getCount() > 0) {
            messagesListView.setSelection(messagesAdapter.getCount() - 1);
            messagesListView.post(new Runnable() {
                @Override public void run() {
                    if (messagesAdapter.getCount() > 0) messagesListView.setSelection(messagesAdapter.getCount() - 1);
                }
            });
        }
        chatInput.requestFocus();

        java.util.Map<String, Object> msgData = new java.util.HashMap<>();
        msgData.put("senderId", myUid);
        msgData.put("receiverId", otherUid);
        msgData.put("text", text);
        msgData.put("timestamp", now);
        msgData.put("serverTimestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
        msgData.put("updatedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
        msgData.put("isEdited", false);
        msgData.put("editedWatermark", false);
        msgData.put("editHistory", new java.util.ArrayList<String>());
        msgData.put("replyToMessageId", replyId);
        msgData.put("replyToSenderId", replySender);
        msgData.put("replyToText", replyText);
        msgData.put("isDeletedForEveryone", false);
        msgData.put("status", initialStatus);
        pendingPayloads.get(newMsgId).putAll(msgData);

        java.util.Map<String, Object> lastMsgData = new java.util.HashMap<>();
        lastMsgData.put("text", text);
        lastMsgData.put("senderId", myUid);
        lastMsgData.put("timestamp", now);
        lastMsgData.put("status", initialStatus);
        lastMsgData.put("isDeletedForEveryone", false);

        // Write the actual message independently. Conversation preview and unread
        // bookkeeping must never delay or block delivery of the message itself.
        newMsgRef.setValue(msgData, new com.google.firebase.database.DatabaseReference.CompletionListener() {
            @Override
            public void onComplete(com.google.firebase.database.DatabaseError error, com.google.firebase.database.DatabaseReference ref) {
                if (error == null) {
                    pendingSendIds.remove(newMsgId);
                    pendingPayloads.remove(newMsgId);
                    pendingPersist.save();
                    notificationWebhook.send(myUid, otherUid, pairId, newMsgId);
                } else {
                    pendingSendIds.add(newMsgId);
                    pendingPersist.save();
                }
                messagesAdapter.notifyDataSetChanged();
            }
        });

        // These are secondary best-effort updates and run asynchronously after
        // the primary message write has already been queued.
        rootRef.child("conversations").child(pairId).child("lastMessage").setValue(lastMsgData);

        rootRef.child("conversations").child(pairId).child("unreadCount").child(otherUid)
            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                @Override public com.google.firebase.database.Transaction.Result doTransaction(com.google.firebase.database.MutableData currentData) {
                    Long curr = currentData.getValue(Long.class);
                    currentData.setValue((curr == null ? 0L : curr) + 1L);
                    return com.google.firebase.database.Transaction.success(currentData);
                }
                @Override public void onComplete(com.google.firebase.database.DatabaseError error, boolean committed, com.google.firebase.database.DataSnapshot snapshot) {}
            });
    }
});

// ---------- STEP 13: Firebase listeners & Fast Reconnect Flush ----------

// Fast child-level message updates: only the changed message is refreshed.
class FastMessageUpdateHelper {
    ChatMessageItem parse(com.google.firebase.database.DataSnapshot msgSnap) {
        ChatMessageItem item = new ChatMessageItem();
        item.id = msgSnap.getKey();
        item.senderId = msgSnap.child("senderId").getValue(String.class);
        item.receiverId = msgSnap.child("receiverId").getValue(String.class);
        item.text = msgSnap.child("text").getValue(String.class);
        Long ts = msgSnap.child("timestamp").getValue(Long.class);
        item.timestamp = ts == null ? 0 : ts;
        Long updatedAtVal = msgSnap.child("updatedAt").getValue(Long.class);
        item.updatedAt = updatedAtVal == null ? 0 : updatedAtVal;
        Boolean edited = msgSnap.child("isEdited").getValue(Boolean.class);
        item.isEdited = edited != null && edited;
        Long editedAt = msgSnap.child("editedAt").getValue(Long.class);
        item.editedAt = editedAt == null ? 0 : editedAt;
        Boolean watermark = msgSnap.child("editedWatermark").getValue(Boolean.class);
        item.editedWatermark = watermark != null && watermark;
        item.replyToMessageId = msgSnap.child("replyToMessageId").getValue(String.class);
        item.replyToSenderId = msgSnap.child("replyToSenderId").getValue(String.class);
        item.replyToText = msgSnap.child("replyToText").getValue(String.class);
        for (com.google.firebase.database.DataSnapshot reactionSnap : msgSnap.child("reactions").getChildren()) {
            String reactionEmoji = reactionSnap.getValue(String.class);
            if (reactionEmoji != null) item.reactions.put(reactionSnap.getKey(), reactionEmoji);
        }
        for (com.google.firebase.database.DataSnapshot historySnap : msgSnap.child("editHistory").getChildren()) {
            String historyText = historySnap.getValue(String.class);
            if (historyText != null) item.editHistory.add(historyText);
        }
        Boolean deletedEveryone = msgSnap.child("isDeletedForEveryone").getValue(Boolean.class);
        item.isDeletedForEveryone = deletedEveryone != null && deletedEveryone;
        item.status = msgSnap.child("status").getValue(String.class);
        Boolean deletedForMe = msgSnap.child("deletedFor").child(myUid).getValue(Boolean.class);
        item.deletedForMe = deletedForMe != null && deletedForMe;
        // STAGE 2: audio message fields — the lightweight metadata only; the
        // actual clip lives in /audio_payloads and is fetched on demand.
        String typeVal = msgSnap.child("type").getValue(String.class);
        item.type = typeVal == null ? "text" : typeVal;
        Long durationVal = msgSnap.child("audioDurationSeconds").getValue(Long.class);
        item.audioDurationSeconds = durationVal == null ? 0 : durationVal.intValue();
        com.google.firebase.database.DataSnapshot waveformSnap = msgSnap.child("waveformSamples");
        if (waveformSnap.exists()) {
            java.util.List<Integer> wf = new java.util.ArrayList<Integer>();
            for (com.google.firebase.database.DataSnapshot levelSnap : waveformSnap.getChildren()) {
                Long lvl = levelSnap.getValue(Long.class);
                wf.add(lvl == null ? 0 : lvl.intValue());
            }
            int[] arr = new int[wf.size()];
            for (int wi = 0; wi < arr.length; wi++) arr[wi] = wf.get(wi);
            item.waveformSamples = arr;
        }
        return item;
    }

    void upsert(com.google.firebase.database.DataSnapshot snap) {
        if (snap.getKey() == null) return;
        ChatMessageItem next = parse(snap);
        for (int i = 0; i < messageList.size(); i++) {
            if (snap.getKey().equals(messageList.get(i).id)) {
                messageList.set(i, next);
                rebuildScheduler.schedule();
                return;
            }
        }
        messageList.add(next);
        rebuildScheduler.schedule();
    }

    void remove(com.google.firebase.database.DataSnapshot snap) {
        final String removedKey = snap.getKey();
        if (removedKey == null) return;
        // FIX: on a limitToLast query Firebase also fires "removed" when an old
        // message is merely pushed out of the window. Verify against the real
        // data first — only a node that truly no longer exists (the pending-send
        // cancel flow) is shown as deleted.
        chatMessagesRef.child(removedKey).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(com.google.firebase.database.DataSnapshot check) {
                if (check.exists()) return;
                for (int i = 0; i < messageList.size(); i++) {
                    ChatMessageItem existing = messageList.get(i);
                    if (removedKey.equals(existing.id)) {
                        // Stays visible, marked deleted, exactly as intended.
                        existing.isDeletedForEveryone = true;
                        rebuildScheduler.schedule();
                        return;
                    }
                }
            }
            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
        });
    }
}
final FastMessageUpdateHelper fastMessageUpdates = new FastMessageUpdateHelper();

// Recovery path (unreadable cache while in delta mode): fetch the recent window once.
fullRecoveryHolder[0] = new Runnable() {
    @Override public void run() {
        chatMessagesRecentQuery.addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) fastMessageUpdates.upsert(child);
            }
            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
        });
    }
};

// Load-older-messages-on-scroll-up. Fetches a batch of messages older than the
// oldest one currently known; it re-reads them fresh from Firebase rather than
// trusting the local cache, which also corrects any stale cached state.
loadOlderMessagesHolder[0] = new Runnable() {
    @Override public void run() {
        if (isLoadingOlderMessages[0] || reachedConversationStart[0]) return;
        String oldestId = null;
        long oldestTs = Long.MAX_VALUE;
        for (ChatMessageItem m : messageList) {
            if (m.id != null && m.timestamp < oldestTs) { oldestTs = m.timestamp; oldestId = m.id; }
        }
        if (oldestId == null) return;
        isLoadingOlderMessages[0] = true;
        final String boundaryId = oldestId;
        // endAt() is inclusive of the boundary item, so we fetch one extra and
        // skip the boundary id itself below — it's already in messageList.
        chatMessagesRef.orderByKey().endAt(boundaryId).limitToLast(41)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                    int fetchedCount = 0;
                    for (com.google.firebase.database.DataSnapshot msgSnap : snapshot.getChildren()) {
                        if (boundaryId.equals(msgSnap.getKey())) continue;
                        fetchedCount++;
                        ChatMessageItem fetched = fastMessageUpdates.parse(msgSnap);
                        boolean found = false;
                        for (int i = 0; i < messageList.size(); i++) {
                            if (fetched.id != null && fetched.id.equals(messageList.get(i).id)) {
                                messageList.set(i, fetched);
                                found = true;
                                break;
                            }
                        }
                        if (!found) messageList.add(fetched);
                    }
                    isLoadingOlderMessages[0] = false;
                    if (fetchedCount == 0) { reachedConversationStart[0] = true; return; }

                    // Preserve the user's scroll position: older items are being
                    // prepended above what's currently on screen.
                    final int firstPos = messagesListView.getFirstVisiblePosition();
                    android.view.View firstView = messagesListView.getChildAt(0);
                    final int topOffset = firstView != null ? firstView.getTop() : 0;
                    final int oldRenderCount = renderItems.size();

                    rebuildMessagesHolder[0].run();

                    final int delta = renderItems.size() - oldRenderCount;
                    messagesListView.post(new Runnable() {
                        @Override public void run() {
                            messagesListView.setSelectionFromTop(firstPos + delta, topOffset);
                        }
                    });
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {
                    isLoadingOlderMessages[0] = false;
                }
            });
    }
};
retryPendingSends[0] = new Runnable() {
    @Override public void run() {
        if (pendingPayloads.isEmpty()) return;
        for (final java.util.Map.Entry<String, java.util.Map<String, Object>> entry : new java.util.HashMap<String, java.util.Map<String, Object>>(pendingPayloads).entrySet()) {
            final String id = entry.getKey();
            rootRef.child("messages").child(pairId).child(id).setValue(entry.getValue(), new com.google.firebase.database.DatabaseReference.CompletionListener() {
                @Override public void onComplete(com.google.firebase.database.DatabaseError error, com.google.firebase.database.DatabaseReference ref) {
                    if (error == null) { pendingSendIds.remove(id); pendingPayloads.remove(id); pendingPersist.save(); messagesAdapter.notifyDataSetChanged(); }
                }
            });
        }
    }
};

// ===== PRIORITY 1: the message listeners (delta query, or limitToLast(60) on a first open) =====
activeMessagesQuery.addChildEventListener(new com.google.firebase.database.ChildEventListener() {
    @Override public void onChildAdded(com.google.firebase.database.DataSnapshot snap, String previousChildKey) {
        fastMessageUpdates.upsert(snap);
        messagesLoaded[0] = true;
        if (!hasInitializedBarrier[0]) barrierHelper.checkAndInit();
    }
    @Override public void onChildChanged(com.google.firebase.database.DataSnapshot snap, String previousChildKey) {
        fastMessageUpdates.upsert(snap);
    }
    @Override public void onChildRemoved(com.google.firebase.database.DataSnapshot snap) {
        fastMessageUpdates.remove(snap);
    }
    @Override public void onChildMoved(com.google.firebase.database.DataSnapshot snap, String previousChildKey) {}
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

// Listen to own unreadCount independently to enforce zeroing regardless of read receipts setting
rootRef.child("conversations").child(pairId).child("unreadCount").child(myUid).addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        Long val = snapshot.getValue(Long.class);
        if (val != null) {
            if (initialUnreadCount[0] == -1) {
                initialUnreadCount[0] = val.intValue();
                barrierHelper.checkAndInit();
            }
            // Only zero this out while the chat is actually visible, so the
            // badge/notification logic on Home isn't told it was handled.
            if (val > 0 && isChatVisible[0]) {
                rootRef.child("conversations").child(pairId).child("unreadCount").child(myUid).setValue(0);
            }
        }
    }
    @Override
    public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

// My own privacy settings stay immediate: they decide whether read receipts
// are allowed, so they must be known before anything is marked read.
rootRef.child("users").child(myUid).child("privacy").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        Boolean showOnline = snapshot.child("showOnline").getValue(Boolean.class);
        Boolean showTyping = snapshot.child("showTyping").getValue(Boolean.class);
        Boolean readReceipts = snapshot.child("readReceipts").getValue(Boolean.class);
        
        myShowOnlineHolder[0] = (showOnline == null || showOnline);
        myShowTypingHolder[0] = (showTyping == null || showTyping);
        myReadReceiptsHolder[0] = (readReceipts == null || readReceipts);
        myPrivacyLoaded[0] = true;
        
        if (isChatVisible[0]) messageSync.markRead();
        headerSubUpdater.refresh();
        messagesListView.post(new Runnable() {
            @Override
            public void run() { messagesAdapter.notifyDataSetChanged(); }
        });
    }
    @Override
    public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

// Side-effects of the message query (delivered/read marking, unread barrier).
// The ChildEventListener above already keeps messageList in sync, so this does
// not re-upsert anything.
activeMessagesQuery.addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        messagesLoaded[0] = true;
        messageSync.markDelivered();
        // Wait for my privacy settings so a receipts-off user is never marked "read".
        if (isChatVisible[0] && myPrivacyLoaded[0]) messageSync.markRead();
        if (!hasInitializedBarrier[0]) barrierHelper.checkAndInit();
        else rebuildScheduler.schedule();
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

rootRef.child(".info/connected").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        Boolean connected = snapshot.getValue(Boolean.class);
        isDeviceConnected[0] = connected != null && connected;
        headerSubUpdater.refresh();
        if (connected != null && connected) {
            if (retryPendingSends[0] != null) retryPendingSends[0].run();
            // Never clear pending IDs merely because the socket connected.
            // Connection state does not prove that a write was accepted.
        }
    }
    @Override
    public void onCancelled(com.google.firebase.database.DatabaseError error) {}
});

// ===== PRIORITY 2: secondary listeners, attached just AFTER the message
// listeners so nothing competes with sending / loading messages. They also now
// read only small fields instead of whole nodes. =====
final String[] lastAvatarKeyHolder = { "" };
final Runnable applyOtherProfileUi = new Runnable() {
    @Override public void run() {
        profileCache.save(otherUserNameHolder[0], otherUserUsernameHolder[0], otherUserPhotoHolder[0]);
        headerName.setText(otherUserNameHolder[0]);
        // Reload the avatar only when the photo or name really changed.
        String avatarKey = otherUserPhotoHolder[0] + "|" + otherUserNameHolder[0];
        if (!avatarKey.equals(lastAvatarKeyHolder[0])) {
            lastAvatarKeyHolder[0] = avatarKey;
            chatAvatarHelper.load(otherUserPhotoHolder[0], otherUserNameHolder[0], headerAvatar);
        }
        headerSubUpdater.refresh();
        messagesListView.post(new Runnable() {
            @Override public void run() { messagesAdapter.notifyDataSetChanged(); }
        });
    }
};

final Runnable attachSecondaryListeners = new Runnable() {
    @Override public void run() {
        com.google.firebase.database.DatabaseReference otherUserRef = rootRef.child("users").child(otherUid);

        otherUserRef.child("name").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                String v = snapshot.getValue(String.class);
                otherUserNameHolder[0] = v == null ? "" : v;
                applyOtherProfileUi.run();
            }
            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
        });
        otherUserRef.child("username").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                String v = snapshot.getValue(String.class);
                otherUserUsernameHolder[0] = v == null ? "" : v;
                applyOtherProfileUi.run();
            }
            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
        });
        otherUserRef.child("photoURL").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                String v = snapshot.getValue(String.class);
                otherUserPhotoHolder[0] = v == null ? "" : v;
                applyOtherProfileUi.run();
            }
            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
        });
        otherUserRef.child("privacy").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                Boolean showOnline = snapshot.child("showOnline").getValue(Boolean.class);
                Boolean showTyping = snapshot.child("showTyping").getValue(Boolean.class);
                Boolean readReceipts = snapshot.child("readReceipts").getValue(Boolean.class);
                otherUserShowOnlineHolder[0] = (showOnline == null || showOnline);
                otherUserShowTypingHolder[0] = (showTyping == null || showTyping);
                otherUserReadReceiptsHolder[0] = (readReceipts == null || readReceipts);
                headerSubUpdater.refresh();
            }
            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
        });
        otherUserRef.child("status").child("state").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                String state = snapshot.getValue(String.class);
                otherUserOnlineHolder[0] = "online".equals(state);
                headerSubUpdater.refresh();
            }
            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {}
        });

        rootRef.child("conversations").child(pairId).child("typing").child(otherUid).addValueEventListener(new com.google.firebase.database.ValueEventListener() {
            @Override
            public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                Boolean typing = snapshot.getValue(Boolean.class);
                otherUserIsTypingHolder[0] = typing != null && typing;
                headerSubUpdater.refresh();
            }
            @Override
            public void onCancelled(com.google.firebase.database.DatabaseError error) {}
        });

        // Only THIS pair's relationship, not the whole /relationships node.
        rootRef.child("relationships").child(pairId).addValueEventListener(new com.google.firebase.database.ValueEventListener() {
            @Override
            public void onDataChange(com.google.firebase.database.DataSnapshot relSnap) {
                String status = relSnap.child("status").getValue(String.class);
                String requester = relSnap.child("requester").getValue(String.class);

                if (otherUid.equals(myUid) || "friends".equals(status)) {
                    blockedBanner.setVisibility(android.view.View.GONE);
                    inputWrap.setVisibility(android.view.View.VISIBLE);
                } else {
                    inputWrap.setVisibility(android.view.View.GONE);
                    blockedBanner.setVisibility(android.view.View.VISIBLE);
                    if ("pending".equals(status) && myUid.equals(requester)) {
                        blockedBanner.setText("You can't send messages until the friend request is accepted.");
                    } else if ("pending".equals(status)) {
                        blockedBanner.setText("Accept the friend request to enable chat.");
                    } else {
                        blockedBanner.setText("You can't send messages until you become friends.");
                    }
                }
            }
            @Override
            public void onCancelled(com.google.firebase.database.DatabaseError error) {}
        });
    }
};
mainHandler.postDelayed(attachSecondaryListeners, 150);








// ===== onPause code =====





try {
    java.util.Map<String, Object> lifecycleBag = (java.util.Map<String, Object>) getWindow().getDecorView().getTag();
    if (lifecycleBag != null) {
        boolean[] isChatVisible = (boolean[]) lifecycleBag.get("isChatVisible");
        android.content.SharedPreferences cachePrefs = (android.content.SharedPreferences) lifecycleBag.get("cachePrefs");
        String otherUid = (String) lifecycleBag.get("otherUid");
        if (isChatVisible != null) isChatVisible[0] = false;
        if (cachePrefs != null && otherUid != null) {
            String current = cachePrefs.getString("active_chat_uid", null);
            // Only clear if it still points at us — avoids a race if the
            // user jumps straight from this chat into another one.
            if (otherUid.equals(current)) {
                cachePrefs.edit().remove("active_chat_uid").apply();
            }
        }
    }
} catch (Throwable ignored) {}



// ===== onResume code =====





try {
    java.util.Map<String, Object> lifecycleBag = (java.util.Map<String, Object>) getWindow().getDecorView().getTag();
    if (lifecycleBag != null) {
        boolean[] isChatVisible = (boolean[]) lifecycleBag.get("isChatVisible");
        android.content.SharedPreferences cachePrefs = (android.content.SharedPreferences) lifecycleBag.get("cachePrefs");
        String otherUid = (String) lifecycleBag.get("otherUid");
        if (isChatVisible != null) isChatVisible[0] = true;
        if (cachePrefs != null && otherUid != null) {
            cachePrefs.edit().putString("active_chat_uid", otherUid).apply();
        }
    }
} catch (Throwable ignored) {}