// ===== onCreate code =====






// ============================================================
// MIXI CHAT — CallActivity (Real-Time Low-Latency Edition)
// Paste into: Call screen -> onCreate -> Add Source Directly
// ============================================================

final float density = getResources().getDisplayMetrics().density;
class Utils {
    int dp(float v) { return (int)(v * density + 0.5f); }
}
final Utils utils = new Utils();

// Use the app's custom emoji font everywhere on this screen.
final android.graphics.Typeface emojiTypeface;
android.graphics.Typeface loadedEmojiTypeface;
try {
    loadedEmojiTypeface = android.graphics.Typeface.createFromAsset(getAssets(), "twemoji/iOS_Emojis.ttf");
} catch (Throwable ignored) {
    loadedEmojiTypeface = android.graphics.Typeface.DEFAULT;
}
emojiTypeface = loadedEmojiTypeface;

final int BLUE = android.graphics.Color.parseColor("#0076FF");
final int DARK = android.graphics.Color.parseColor("#17345D");
final int MUTED = android.graphics.Color.parseColor("#8295AE");
final int BG = android.graphics.Color.parseColor("#F7FBFF");
final int RED = android.graphics.Color.parseColor("#FF3038");
final int GREEN = android.graphics.Color.parseColor("#11B96A");
final int PALE_BLUE = android.graphics.Color.parseColor("#EAF4FF");
final int PALE_GREY = android.graphics.Color.parseColor("#E9EFF5");

final com.google.firebase.auth.FirebaseUser meUser =
    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
if (meUser == null) {
    finish();
    return;
}
final String myUid = meUser.getUid();
final com.google.firebase.database.DatabaseReference db =
    com.google.firebase.database.FirebaseDatabase.getInstance().getReference();
final android.content.SharedPreferences cache =
    getSharedPreferences("MixiCache", android.content.Context.MODE_PRIVATE);

final android.content.Intent launchIntent = getIntent();
final boolean incoming = launchIntent.getBooleanExtra("incoming", false);
final String[] otherUidHolder = { launchIntent.getStringExtra("otherUid") };
final String[] otherNameHolder = { launchIntent.getStringExtra("otherName") };
final String[] otherPhotoHolder = { launchIntent.getStringExtra("otherPhoto") };
final String[] callIdHolder = { launchIntent.getStringExtra("callId") };

if (otherUidHolder[0] == null || otherUidHolder[0].trim().length() == 0) {
    finish();
    return;
}

final String otherUid = otherUidHolder[0];
if (myUid.equals(otherUid)) {
    android.widget.Toast.makeText(CallActivity.this, "You cannot call yourself.", android.widget.Toast.LENGTH_SHORT).show();
    finish();
    return;
}
final String cacheKey = "call_profile_" + otherUid;
if (otherNameHolder[0] == null) otherNameHolder[0] = cache.getString(cacheKey + "_name", "User");
if (otherPhotoHolder[0] == null) otherPhotoHolder[0] = cache.getString(cacheKey + "_photo", "");

// ---------- Small drawable helpers ----------
class Drawables {
    android.graphics.drawable.GradientDrawable circle(int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }
    android.graphics.drawable.GradientDrawable rounded(int color, float radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(utils.dp(radius));
        return d;
    }
}
final Drawables drawables = new Drawables();

// ---------- Custom vector-style call icons ----------
class CallIconDrawable extends android.graphics.drawable.Drawable {
    static final int PHONE = 1;
    static final int HANGUP = 2;
    static final int SPEAKER = 3;
    static final int MIC = 4;
    private final int type;
    private final android.graphics.Paint stroke = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private boolean slashed = false;

    CallIconDrawable(int iconType) {
        type = iconType;
        stroke.setStyle(android.graphics.Paint.Style.STROKE);
        stroke.setStrokeWidth(utils.dp(4));
        stroke.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        stroke.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        stroke.setColor(android.graphics.Color.WHITE);
        fill.setStyle(android.graphics.Paint.Style.FILL);
        fill.setColor(android.graphics.Color.WHITE);
    }

    void setSlashed(boolean value) { slashed = value; invalidateSelf(); }

    @Override public void draw(android.graphics.Canvas canvas) {
        android.graphics.Rect b = getBounds();
        float w = b.width(), h = b.height();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        android.graphics.Path path = new android.graphics.Path();
        if (type == PHONE) {
            path.moveTo(cx - w * .27f, cy - h * .27f);
            path.cubicTo(cx - w * .38f, cy - h * .16f, cx - w * .16f, cy + h * .20f, cx + w * .14f, cy + h * .27f);
            path.cubicTo(cx + w * .24f, cy + h * .29f, cx + w * .34f, cy + h * .18f, cx + w * .28f, cy + h * .10f);
            canvas.drawPath(path, stroke);
            canvas.drawLine(cx - w * .27f, cy - h * .27f, cx - w * .14f, cy - h * .34f, stroke);
            canvas.drawLine(cx + w * .14f, cy + h * .27f, cx + w * .28f, cy + h * .19f, stroke);
        } else if (type == HANGUP) {
            path.moveTo(cx - w * .31f, cy + h * .05f);
            path.cubicTo(cx - w * .16f, cy - h * .13f, cx + w * .16f, cy - h * .13f, cx + w * .31f, cy + h * .05f);
            canvas.drawPath(path, stroke);
            canvas.drawLine(cx - w * .31f, cy + h * .05f, cx - w * .36f, cy + h * .18f, stroke);
            canvas.drawLine(cx + w * .31f, cy + h * .05f, cx + w * .36f, cy + h * .18f, stroke);
        } else if (type == SPEAKER) {
            android.graphics.Path speaker = new android.graphics.Path();
            speaker.moveTo(cx - w * .31f, cy - h * .10f);
            speaker.lineTo(cx - w * .12f, cy - h * .10f);
            speaker.lineTo(cx + w * .10f, cy - h * .28f);
            speaker.lineTo(cx + w * .10f, cy + h * .28f);
            speaker.lineTo(cx - w * .12f, cy + h * .10f);
            speaker.lineTo(cx - w * .31f, cy + h * .10f);
            speaker.close();
            canvas.drawPath(speaker, fill);
            stroke.setStrokeWidth(utils.dp(3));
            canvas.drawArc(cx - w * .03f, cy - h * .22f, cx + w * .38f, cy + h * .22f, -55, 110, false, stroke);
            canvas.drawArc(cx - w * .08f, cy - h * .35f, cx + w * .53f, cy + h * .35f, -48, 96, false, stroke);
        } else if (type == MIC) {
            canvas.drawRoundRect(cx - w * .11f, cy - h * .31f, cx + w * .11f, cy + h * .12f, w * .11f, w * .11f, fill);
            canvas.drawArc(cx - w * .27f, cy - h * .10f, cx + w * .27f, cy + h * .31f, 0, 180, false, stroke);
            canvas.drawLine(cx, cy + h * .30f, cx, cy + h * .40f, stroke);
            canvas.drawLine(cx - w * .15f, cy + h * .40f, cx + w * .15f, cy + h * .40f, stroke);
            if (slashed) {
                stroke.setStrokeWidth(utils.dp(4));
                canvas.drawLine(cx - w * .38f, cy - h * .38f, cx + w * .38f, cy + h * .38f, stroke);
            }
        }
    }
    @Override public void setAlpha(int a) { stroke.setAlpha(a); fill.setAlpha(a); }
    @Override public void setColorFilter(android.graphics.ColorFilter f) { stroke.setColorFilter(f); fill.setColorFilter(f); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}

class CallAvatarDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final String letters;
    CallAvatarDrawable(String name) {
        String n = name == null ? "User" : name.trim();
        if (n.length() == 0) n = "User";
        String[] a = n.split("\\s+");
        String first = a[0].substring(0, 1);
        String last = a.length > 1 ? a[a.length - 1].substring(0, 1) : "";
        letters = (first + last).toUpperCase(java.util.Locale.getDefault());
        p.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
    }
    @Override public void draw(android.graphics.Canvas c) {
        android.graphics.Rect b = getBounds();
        float x = b.exactCenterX(), y = b.exactCenterY();
        float r = Math.min(b.width(), b.height()) * 0.5f;
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(BLUE); c.drawCircle(x, y, r, p);
        p.setColor(android.graphics.Color.WHITE);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setTextSize(r * (letters.length() > 1 ? 0.72f : 0.9f));
        android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(letters, x, y - (fm.ascent + fm.descent) / 2f, p);
    }
    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}

final android.widget.FrameLayout root = new android.widget.FrameLayout(CallActivity.this);
root.setBackgroundColor(BG);

final android.widget.LinearLayout page = new android.widget.LinearLayout(CallActivity.this);
page.setOrientation(android.widget.LinearLayout.VERTICAL);
page.setBackgroundColor(BG);

// ---------- Screenshot-matching header ----------
final android.widget.LinearLayout header = new android.widget.LinearLayout(CallActivity.this);
header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
header.setGravity(android.view.Gravity.CENTER_VERTICAL);
header.setPadding(utils.dp(22), utils.dp(13), utils.dp(20), utils.dp(10));
header.setElevation(utils.dp(2));
header.setBackgroundColor(android.graphics.Color.WHITE);

final android.widget.ImageView logo = new android.widget.ImageView(CallActivity.this);
logo.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
try {
    int logoId = getResources().getIdentifier("favicon", "drawable", getPackageName());
    logo.setImageResource(logoId == 0 ? android.R.drawable.ic_menu_gallery : logoId);
} catch (Throwable ignored) { logo.setImageResource(android.R.drawable.ic_menu_gallery); }
header.addView(logo, new android.widget.LinearLayout.LayoutParams(utils.dp(44), utils.dp(44)));

final android.widget.TextView appName = new android.widget.TextView(CallActivity.this);
appName.setTypeface(emojiTypeface);
appName.setText("Mixi Chat");
appName.setTextSize(20);
appName.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
appName.setTextColor(BLUE);
appName.setGravity(android.view.Gravity.CENTER_VERTICAL);
android.widget.LinearLayout.LayoutParams appNameParams = new android.widget.LinearLayout.LayoutParams(-2, -1);
appNameParams.leftMargin = utils.dp(12);
header.addView(appName, appNameParams);
page.addView(header, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(70)));

final android.view.View divider = new android.view.View(CallActivity.this);
divider.setBackgroundColor(android.graphics.Color.parseColor("#EDF2F7"));
page.addView(divider, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(1)));

// ---------- Main call body ----------
final android.widget.LinearLayout body = new android.widget.LinearLayout(CallActivity.this);
body.setOrientation(android.widget.LinearLayout.VERTICAL);
body.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
body.setPadding(utils.dp(18), utils.dp(30), utils.dp(18), utils.dp(18));
body.setBackground(drawables.rounded(android.graphics.Color.WHITE, 28));
body.setElevation(utils.dp(3));

final android.widget.ImageView avatar = new android.widget.ImageView(CallActivity.this);
avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
avatar.setImageDrawable(new CallAvatarDrawable(otherNameHolder[0]));
avatar.setBackground(drawables.circle(android.graphics.Color.WHITE));
avatar.setPadding(utils.dp(4), utils.dp(4), utils.dp(4), utils.dp(4));
android.widget.LinearLayout.LayoutParams avatarParams = new android.widget.LinearLayout.LayoutParams(utils.dp(144), utils.dp(144));
avatarParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
body.addView(avatar, avatarParams);

final android.widget.TextView personName = new android.widget.TextView(CallActivity.this);
personName.setTypeface(emojiTypeface);
personName.setText(otherNameHolder[0] == null ? "User" : otherNameHolder[0]);
personName.setTextSize(26);
personName.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
personName.setTextColor(DARK);
personName.setGravity(android.view.Gravity.CENTER);
android.widget.LinearLayout.LayoutParams personNameParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
personNameParams.topMargin = utils.dp(20);
body.addView(personName, personNameParams);

final android.widget.TextView statusText = new android.widget.TextView(CallActivity.this);
statusText.setTypeface(emojiTypeface);
statusText.setText(incoming ? "Incoming audio call" : "Calling...");
statusText.setTextSize(16);
statusText.setTextColor(MUTED);
statusText.setTypeface(emojiTypeface, android.graphics.Typeface.NORMAL);
statusText.setGravity(android.view.Gravity.CENTER);
android.widget.LinearLayout.LayoutParams statusParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
statusParams.topMargin = utils.dp(8);
body.addView(statusText, statusParams);

final android.widget.TextView timerText = new android.widget.TextView(CallActivity.this);
timerText.setTypeface(emojiTypeface);
timerText.setText("");
timerText.setTextSize(23);
timerText.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
timerText.setTextColor(DARK);
timerText.setGravity(android.view.Gravity.CENTER);
body.addView(timerText, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(35)));

final android.widget.LinearLayout.LayoutParams bodyParams = new android.widget.LinearLayout.LayoutParams(-1, 0, 1f);
page.addView(body, bodyParams);

// ---------- Bottom controls ----------
final android.widget.LinearLayout controls = new android.widget.LinearLayout(CallActivity.this);
controls.setOrientation(android.widget.LinearLayout.HORIZONTAL);
controls.setGravity(android.view.Gravity.CENTER);
controls.setPadding(utils.dp(20), utils.dp(14), utils.dp(20), utils.dp(22));
controls.setElevation(utils.dp(2));
controls.setBackgroundColor(BG);
final CallIconDrawable[] muteIconHolder = new CallIconDrawable[1];
final android.widget.LinearLayout[] incomingAcceptColumnHolder = new android.widget.LinearLayout[1];
final android.view.View[] speakerCircleHolder = new android.view.View[1];

class ControlFactory {
    android.widget.LinearLayout make(int iconType, String label, int color) {
        android.widget.LinearLayout col = new android.widget.LinearLayout(CallActivity.this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.widget.FrameLayout circle = new android.widget.FrameLayout(CallActivity.this);
        circle.setBackground(drawables.circle(color));
        android.widget.ImageView icon = new android.widget.ImageView(CallActivity.this);
        final CallIconDrawable iconDrawable = new CallIconDrawable(iconType);
        if (iconType == CallIconDrawable.MIC) muteIconHolder[0] = iconDrawable;
        icon.setImageDrawable(iconDrawable);
        icon.setPadding(utils.dp(16), utils.dp(16), utils.dp(16), utils.dp(16));
        circle.addView(icon, new android.widget.FrameLayout.LayoutParams(-1, -1));
        if (iconType == CallIconDrawable.SPEAKER) speakerCircleHolder[0] = circle;
        col.addView(circle, new android.widget.LinearLayout.LayoutParams(utils.dp(64), utils.dp(64)));
        android.widget.TextView text = new android.widget.TextView(CallActivity.this);
        text.setTypeface(emojiTypeface);
        text.setText(label);
        text.setTextColor(color == PALE_GREY ? android.graphics.Color.parseColor("#9AAABD") : color);
        text.setTextSize(13.5f);
        text.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        text.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(utils.dp(105), -2);
        lp.topMargin = utils.dp(8);
        col.addView(text, lp);
        return col;
    }
}
final ControlFactory controlsFactory = new ControlFactory();

final android.widget.LinearLayout endColumn = controlsFactory.make(CallIconDrawable.HANGUP, "End Call", RED);
final android.widget.LinearLayout speakerColumn = controlsFactory.make(CallIconDrawable.SPEAKER, "Speaker", BLUE);
final android.widget.LinearLayout muteColumn = controlsFactory.make(CallIconDrawable.MIC, "Mute", PALE_GREY);
controls.addView(endColumn, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
controls.addView(speakerColumn, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
controls.addView(muteColumn, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
page.addView(controls, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(132)));
speakerColumn.setEnabled(false); speakerColumn.setAlpha(0.45f);
if (speakerCircleHolder[0] != null) speakerCircleHolder[0].setBackground(drawables.circle(PALE_GREY));
muteColumn.setEnabled(false); muteColumn.setAlpha(0.45f);
root.addView(page, new android.widget.FrameLayout.LayoutParams(-1, -1));
setContentView(root);

// ---------- DP cache + Firebase profile refresh ----------
try {
    String cachedPhoto = cache.getString(cacheKey + "_photo", "");
    if (cachedPhoto.length() > 0 && !cachedPhoto.contains("ui-avatars.com")) {
        com.bumptech.glide.Glide.with(CallActivity.this).load(cachedPhoto)
            .placeholder(new CallAvatarDrawable(otherNameHolder[0]))
            .error(new CallAvatarDrawable(otherNameHolder[0]))
            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
            .circleCrop().into(avatar);
    }
} catch (Throwable ignored) {}

db.child("users").child(otherUid).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
    @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
        String n = snap.child("name").getValue(String.class);
        String p = snap.child("photoURL").getValue(String.class);
        if (n != null && n.length() > 0) {
            otherNameHolder[0] = n; personName.setText(n);
            cache.edit().putString(cacheKey + "_name", n).apply();
        }
        if (p != null) {
            otherPhotoHolder[0] = p; cache.edit().putString(cacheKey + "_photo", p).apply();
            try {
                if (!p.contains("ui-avatars.com")) com.bumptech.glide.Glide.with(CallActivity.this).load(p)
                    .placeholder(new CallAvatarDrawable(otherNameHolder[0]))
                    .error(new CallAvatarDrawable(otherNameHolder[0]))
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .circleCrop().into(avatar);
            } catch (Throwable ignored) {}
        }
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
});

// ---------- HIGH SPEED AUDIO ENGINE (Microphone input only) ----------
class AudioEngine {
    final int sampleRate = 16000;
    final int bufferSize = 4096;

    android.media.AudioRecord recorder;
    android.media.AudioTrack player;
    android.media.audiofx.AcousticEchoCanceler echoCanceler;
    android.media.audiofx.NoiseSuppressor noiseSuppressor;
    volatile boolean running;
    volatile boolean muted;
    final java.util.concurrent.ExecutorService playbackQueue =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    void start(final String id) {
        if (running) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 9101);
                android.widget.Toast.makeText(
                    CallActivity.this,
                    "Allow microphone access, then answer the call again.",
                    android.widget.Toast.LENGTH_LONG
                ).show();
                return;
            }

            android.media.AudioManager am =
                (android.media.AudioManager)getSystemService(android.content.Context.AUDIO_SERVICE);
            am.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(false);

            int minIn = android.media.AudioRecord.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            );
            int minOut = android.media.AudioTrack.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            );

            // Microphone input only. Do not use AudioPlaybackCaptureConfiguration
            // and do not upload anything read from AudioTrack.
            recorder = new android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minIn, bufferSize * 2)
            );

            // Remote audio is played locally only; it is never uploaded.
            player = new android.media.AudioTrack(
                android.media.AudioManager.STREAM_VOICE_CALL,
                sampleRate,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minOut, bufferSize * 2),
                android.media.AudioTrack.MODE_STREAM
            );

            if (recorder.getState() != android.media.AudioRecord.STATE_INITIALIZED ||
                player.getState() != android.media.AudioTrack.STATE_INITIALIZED) {
                stop();
                android.widget.Toast.makeText(
                    CallActivity.this,
                    "Audio device could not be initialized.",
                    android.widget.Toast.LENGTH_SHORT
                ).show();
                return;
            }

            if (android.os.Build.VERSION.SDK_INT >= 16 &&
                android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                echoCanceler = android.media.audiofx.AcousticEchoCanceler.create(
                    recorder.getAudioSessionId()
                );
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
            if (android.os.Build.VERSION.SDK_INT >= 16 &&
                android.media.audiofx.NoiseSuppressor.isAvailable()) {
                noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(
                    recorder.getAudioSessionId()
                );
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }

            recorder.startRecording();
            player.play();
            running = true;

            new Thread(new Runnable() {
                @Override public void run() {
                    int seq = 0;
                    byte[] pcm = new byte[bufferSize];
                    while (running) {
                        // This is the only read that is sent to the other user.
                        int read = recorder.read(pcm, 0, pcm.length);
                        if (read <= 0 || !running) continue;

                        if (muted) java.util.Arrays.fill(pcm, 0, read, (byte)0);

                        byte[] actualData = pcm;
                        if (read < pcm.length) {
                            actualData = new byte[read];
                            System.arraycopy(pcm, 0, actualData, 0, read);
                        }

                        final String encoded = android.util.Base64.encodeToString(
                            actualData, android.util.Base64.NO_WRAP
                        );
                        db.child("calls").child(id).child("audio").child(myUid)
                            .child(String.valueOf(seq++)).setValue(encoded);

                        if (seq > 6) {
                            db.child("calls").child(id).child("audio").child(myUid)
                                .child(String.valueOf(seq - 7)).removeValue();
                        }
                    }
                }
            }).start();
        } catch (Throwable t) {
            stop();
            android.widget.Toast.makeText(
                CallActivity.this,
                "Microphone could not be started",
                android.widget.Toast.LENGTH_SHORT
            ).show();
        }
    }

    void play(final String encoded) {
        if (!running || encoded == null) return;
        playbackQueue.execute(new Runnable() {
            @Override public void run() {
                try {
                    byte[] pcm = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
                    if (running && player != null && pcm.length > 0) {
                        player.write(pcm, 0, pcm.length);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    void stop() {
        running = false;
        try { if (recorder != null) { recorder.stop(); recorder.release(); } }
            catch (Throwable ignored) {}
        try { if (player != null) { player.stop(); player.release(); } }
            catch (Throwable ignored) {}
        try { if (echoCanceler != null) echoCanceler.release(); }
            catch (Throwable ignored) {}
        try { if (noiseSuppressor != null) noiseSuppressor.release(); }
            catch (Throwable ignored) {}
        try { playbackQueue.shutdownNow(); } catch (Throwable ignored) {}
        try {
            ((android.media.AudioManager)getSystemService(android.content.Context.AUDIO_SERVICE))
                .setMode(android.media.AudioManager.MODE_NORMAL);
        } catch (Throwable ignored) {}
    }
}
final AudioEngine audio = new AudioEngine();

// ---------- Real-Time Audio Listener (Reads only incoming chunks instantly) ----------
final com.google.firebase.database.ChildEventListener audioWatcher = new com.google.firebase.database.ChildEventListener() {
    @Override public void onChildAdded(com.google.firebase.database.DataSnapshot snap, String prev) {
        String encoded = snap.getValue(String.class);
        if (encoded != null) audio.play(encoded);
    }
    @Override public void onChildChanged(com.google.firebase.database.DataSnapshot snap, String prev) {}
    @Override public void onChildRemoved(com.google.firebase.database.DataSnapshot snap) {}
    @Override public void onChildMoved(com.google.firebase.database.DataSnapshot snap, String prev) {}
    @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
};

// ---------- Call state ----------
final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
final android.media.Ringtone[] incomingRingtone = { null };
if (incoming) {
    try {
        android.net.Uri ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE);
        incomingRingtone[0] = android.media.RingtoneManager.getRingtone(CallActivity.this, ringtoneUri);
        if (incomingRingtone[0] != null) incomingRingtone[0].play();
    } catch (Throwable ignored) {}
}
final long[] connectedAt = { 0L };
final boolean[] ended = { false };
final boolean[] accepted = { !incoming };
final boolean[] speaker = { false };
final boolean[] muted = { false };
final java.util.concurrent.atomic.AtomicBoolean creating = new java.util.concurrent.atomic.AtomicBoolean(false);
final long UNANSWERED_TIMEOUT_MS = 80L * 1000L;
final android.os.Handler unansweredHandler = new android.os.Handler(android.os.Looper.getMainLooper());
final boolean[] unansweredTimeoutScheduled = { false };

class Network {
    boolean online() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            return n != null && cm.getNetworkCapabilities(n) != null && cm.getNetworkCapabilities(n).hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) { return false; }
    }
}
final Network network = new Network();

final Runnable timer = new Runnable() { @Override public void run() {
    if (connectedAt[0] > 0 && !ended[0]) {
        long sec = Math.max(0, (System.currentTimeMillis() - connectedAt[0]) / 1000);
        timerText.setText(String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60));
        handler.postDelayed(this, 1000);
    }
}};

final Runnable closePage = new Runnable() { @Override public void run() {
    if (ended[0]) return; ended[0] = true; audio.stop(); handler.removeCallbacks(timer);
    try { if (incomingRingtone[0] != null && incomingRingtone[0].isPlaying()) incomingRingtone[0].stop(); } catch (Throwable ignored) {}
    finish();
}};

class CallActions {
    void writeHistory(final String status, final long durationSeconds) {
        try {
            long now = System.currentTimeMillis();
            java.util.Map<String,Object> mine = new java.util.HashMap<>();
            mine.put("otherUserId", otherUid); mine.put("otherUserName", otherNameHolder[0] == null ? "User" : otherNameHolder[0]);
            mine.put("otherUserPhoto", otherPhotoHolder[0] == null ? "" : otherPhotoHolder[0]); mine.put("type", "audio");
            mine.put("direction", incoming ? "incoming" : "outgoing"); mine.put("status", status);
            mine.put("duration", durationSeconds); mine.put("timestamp", now);

            java.util.Map<String,Object> other = new java.util.HashMap<>();
            other.put("otherUserId", myUid); other.put("otherUserName", meUser.getDisplayName() == null ? "User" : meUser.getDisplayName());
            other.put("otherUserPhoto", cache.getString("my_photo_url", "")); other.put("type", "audio");
            other.put("direction", incoming ? "outgoing" : "incoming"); other.put("status", status);
            other.put("duration", durationSeconds); other.put("timestamp", now);

            String myHistoryId = (callIdHolder[0] == null ? String.valueOf(now) : callIdHolder[0]) + "_" + myUid;
            String otherHistoryId = (callIdHolder[0] == null ? String.valueOf(now) : callIdHolder[0]) + "_" + otherUid;
            java.util.Map<String,Object> updates = new java.util.HashMap<>();
            updates.put("callHistory/" + myUid + "/" + myHistoryId, mine);
            updates.put("callHistory/" + otherUid + "/" + otherHistoryId, other);
            db.updateChildren(updates);
        } catch (Throwable ignored) {}
    }
    void end(String finalState) {
        if (ended[0]) return; ended[0] = true; audio.stop(); handler.removeCallbacks(timer);
        unansweredHandler.removeCallbacksAndMessages(null);
        try { if (incomingRingtone[0] != null && incomingRingtone[0].isPlaying()) incomingRingtone[0].stop(); } catch (Throwable ignored) {}
        
        final String id = callIdHolder[0];
        if (id != null && otherUid != null) {
            try { db.child("calls").child(id).child("audio").child(otherUid).removeEventListener(audioWatcher); } catch (Throwable ignored) {}
        }
        
        long durationSeconds = connectedAt[0] > 0 ? Math.max(0L, (System.currentTimeMillis() - connectedAt[0]) / 1000L) : 0L;
        writeHistory(finalState, durationSeconds);
        if (id != null) {
            java.util.Map<String,Object> u = new java.util.HashMap<>();
            u.put("status", finalState); u.put("endedAt", System.currentTimeMillis());
            db.child("calls").child(id).updateChildren(u);
            handler.postDelayed(new Runnable() { @Override public void run() {
                try { db.child("calls").child(id).removeValue(); } catch (Throwable ignored) {}
            }}, 1200);
        }
        finish();
    }
    void scheduleUnansweredTimeout() {
        if (unansweredTimeoutScheduled[0]) return;
        unansweredTimeoutScheduled[0] = true;
        unansweredHandler.postDelayed(new Runnable() { @Override public void run() {
            if (!ended[0] && connectedAt[0] == 0L) { statusText.setText("Missed call"); end("missed"); }
        }}, UNANSWERED_TIMEOUT_MS);
    }
    void connected() {
        if (ended[0] || connectedAt[0] > 0) return;
        connectedAt[0] = System.currentTimeMillis();
        unansweredHandler.removeCallbacksAndMessages(null);
        statusText.setText("Connected");
        if (incoming && incomingAcceptColumnHolder[0] != null) {
            controls.removeView(incomingAcceptColumnHolder[0]);
            controls.addView(speakerColumn, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            controls.addView(muteColumn, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            incomingAcceptColumnHolder[0] = null;
        }
        try { if (incomingRingtone[0] != null && incomingRingtone[0].isPlaying()) incomingRingtone[0].stop(); } catch (Throwable ignored) {}
        speakerColumn.setEnabled(true); speakerColumn.setAlpha(1f);
        muteColumn.setEnabled(true); muteColumn.setAlpha(1f);
        audio.start(callIdHolder[0]); handler.post(timer);
        db.child("calls").child(callIdHolder[0]).updateChildren(java.util.Collections.<String,Object>singletonMap("status", "connected"));
    }
}
final CallActions actions = new CallActions();

class CallStateWatcher implements com.google.firebase.database.ValueEventListener {
    @Override public void onDataChange(com.google.firebase.database.DataSnapshot s) {
        if (!s.exists()) { closePage.run(); return; }
        String state = s.child("status").getValue(String.class);
        if (state == null) return;
        if ("ringing".equals(state) || "calling".equals(state)) statusText.setText(incoming ? "Incoming audio call" : ("ringing".equals(state) ? "Ringing..." : "Calling..."));
        else if ("connected".equals(state)) actions.connected();
        else if ("declined".equals(state) || "ended".equals(state) || "missed".equals(state) || "blocked".equals(state)) {
            if ("blocked".equals(state)) statusText.setText(otherNameHolder[0] + " has blocked calls");
            closePage.run();
        }
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError e) { closePage.run(); }
}

// ---------- Start or accept the call ----------
class ActiveCallGuard {
    boolean live(String state) {
        return "calling".equals(state) || "ringing".equals(state) || "connected".equals(state);
    }
    void checkUserBusy(final String userUid, final String excludedCallId, final Runnable ifFree) {
        db.child("calls").addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(com.google.firebase.database.DataSnapshot allCalls) {
                boolean busy = false;
                for (com.google.firebase.database.DataSnapshot callSnap : allCalls.getChildren()) {
                    if (excludedCallId != null && excludedCallId.equals(callSnap.getKey())) continue;
                    String state = callSnap.child("status").getValue(String.class);
                    if (!live(state)) continue;
                    String caller = callSnap.child("callerId").getValue(String.class);
                    String receiver = callSnap.child("receiverId").getValue(String.class);
                    if (userUid.equals(caller) || userUid.equals(receiver)) { busy = true; break; }
                }
                if (busy) {
                    statusText.setText((otherNameHolder[0] == null ? "User" : otherNameHolder[0]) + " is on another call");
                    if (callIdHolder[0] != null) {
                        db.child("calls").child(callIdHolder[0]).updateChildren(java.util.Collections.<String,Object>singletonMap("status", "busy"));
                    }
                    handler.postDelayed(closePage, 1200);
                } else if (ifFree != null) {
                    ifFree.run();
                }
            }
            @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {
                if (ifFree != null) ifFree.run();
            }
        });
    }
}
final ActiveCallGuard activeCallGuard = new ActiveCallGuard();

class CallStarter {
    void startOutgoing() {
        if (myUid.equals(otherUid)) {
            statusText.setText("You cannot call yourself."); android.widget.Toast.makeText(CallActivity.this, "You cannot call yourself.", android.widget.Toast.LENGTH_SHORT).show();
            handler.postDelayed(closePage, 700); return;
        }
        if (!network.online()) { statusText.setText("You are offline"); handler.postDelayed(closePage, 900); return; }
        if (!creating.compareAndSet(false, true)) return;
        // A user cannot receive or start a second call while any call involving
        // that user is still calling, ringing, or connected.
        activeCallGuard.checkUserBusy(otherUid, null, new Runnable() {
            @Override public void run() {
                if (ended[0]) return;
                final String id = myUid.compareTo(otherUid) < 0 ? myUid + "_" + otherUid + "_" + System.currentTimeMillis() : otherUid + "_" + myUid + "_" + System.currentTimeMillis();
                callIdHolder[0] = id;
                db.child("users").child(otherUid).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(com.google.firebase.database.DataSnapshot user) {
                Boolean blocked = user.child("privacy").child("blockCalls").getValue(Boolean.class);
                if (Boolean.TRUE.equals(blocked)) {
                    statusText.setText(otherNameHolder[0] + " has blocked calls");
                    db.child("calls").child(id).setValue(new java.util.HashMap<String,Object>() {{ put("callerId", myUid); put("receiverId", otherUid); put("status", "blocked"); put("createdAt", System.currentTimeMillis()); }});
                    handler.postDelayed(closePage, 1500); return;
                }
                String presence = user.child("status").child("state").getValue(String.class);
                boolean recipientOnline = "online".equalsIgnoreCase(presence);
                String initial = recipientOnline ? "ringing" : "calling";
                statusText.setText(recipientOnline ? "Ringing..." : "Calling...");
                java.util.Map<String,Object> call = new java.util.HashMap<>();
                call.put("callerId", myUid); call.put("receiverId", otherUid); call.put("callerName", meUser.getDisplayName());
                call.put("callerPhoto", cache.getString("my_photo_url", "")); call.put("status", initial); call.put("createdAt", System.currentTimeMillis());
                
                db.child("calls").child(id).setValue(call);
                db.child("calls").child(id).addValueEventListener(new CallStateWatcher());
                // Attach real-time audio watcher ONLY to the other user's node
                db.child("calls").child(id).child("audio").child(otherUid).addChildEventListener(audioWatcher);
                actions.scheduleUnansweredTimeout();

                db.child("users").child(otherUid).child("status").child("state")
                    .addValueEventListener(new com.google.firebase.database.ValueEventListener() {
                        @Override public void onDataChange(com.google.firebase.database.DataSnapshot stateSnap) {
                            if (ended[0] || connectedAt[0] > 0L) return;
                            String presenceNow = stateSnap.getValue(String.class);
                            if (!"online".equalsIgnoreCase(presenceNow)) return;
                            db.child("calls").child(id).child("status")
                                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                                    @Override public void onDataChange(com.google.firebase.database.DataSnapshot callSnap) {
                                        String current = callSnap.getValue(String.class);
                                        if ("calling".equals(current) && !ended[0] && connectedAt[0] == 0L) {
                                            statusText.setText("Ringing..."); db.child("calls").child(id).updateChildren(java.util.Collections.<String,Object>singletonMap("status", "ringing"));
                                        }
                                    }
                                    @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
                                });
                        }
                        @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
                    });
            }
            @Override public void onCancelled(com.google.firebase.database.DatabaseError e) { statusText.setText("You are offline"); handler.postDelayed(closePage, 900); }
                });
            }
        });
    }
    void acceptIncoming() {
        if (!network.online()) { statusText.setText("You are offline"); handler.postDelayed(closePage, 900); return; }
        activeCallGuard.checkUserBusy(myUid, callIdHolder[0], new Runnable() {
            @Override public void run() { accepted[0] = true; actions.connected(); }
        });
    }
}
final CallStarter starter = new CallStarter();

if (callIdHolder[0] == null && !incoming) starter.startOutgoing();
else if (callIdHolder[0] != null) {
    db.child("calls").child(callIdHolder[0]).addValueEventListener(new CallStateWatcher());
    // Attach real-time audio watcher ONLY to the other user's node
    db.child("calls").child(callIdHolder[0]).child("audio").child(otherUid).addChildEventListener(audioWatcher);
    actions.scheduleUnansweredTimeout();
    if (!incoming && statusText.getText().toString().length() == 0) statusText.setText("Calling...");
}

// ---------- Buttons ----------
endColumn.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { actions.end(incoming && connectedAt[0] == 0 ? "declined" : "ended"); } });
speakerColumn.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) {
    if (connectedAt[0] <= 0 || ended[0]) return;
    speaker[0] = !speaker[0];
    try { ((android.media.AudioManager)getSystemService(android.content.Context.AUDIO_SERVICE)).setSpeakerphoneOn(speaker[0]); } catch (Throwable ignored) {}
    if (speakerCircleHolder[0] != null) speakerCircleHolder[0].setBackground(drawables.circle(speaker[0] ? BLUE : PALE_GREY));
    android.widget.TextView speakerLabel = (android.widget.TextView)((android.view.ViewGroup)speakerColumn).getChildAt(1);
    speakerLabel.setText("Speaker"); speakerLabel.setTextColor(speaker[0] ? BLUE : android.graphics.Color.parseColor("#9AAABD"));
}});
muteColumn.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) {
    if (connectedAt[0] <= 0 || ended[0] || !muteColumn.isEnabled()) return;
    muted[0] = !muted[0]; audio.muted = muted[0];
    if (muteIconHolder[0] != null) muteIconHolder[0].setSlashed(muted[0]);
    android.widget.TextView t = (android.widget.TextView)((android.view.ViewGroup)muteColumn).getChildAt(1);
    t.setText(muted[0] ? "Unmute" : "Mute"); t.setTextColor(muted[0] ? BLUE : android.graphics.Color.parseColor("#9AAABD"));
}});

if (incoming) {
    final android.widget.LinearLayout acceptColumn = controlsFactory.make(CallIconDrawable.PHONE, "Answer", GREEN);
    incomingAcceptColumnHolder[0] = acceptColumn;
    controls.removeAllViews();
    controls.addView(endColumn, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
    controls.addView(acceptColumn, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
    acceptColumn.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { starter.acceptIncoming(); }});
}
