// ===== onCreate code =====



// ============================================================
// MIXI CHAT — SettingsActivity (Profile, Live Username Check,
// DP-by-Link with Live Preview, Symmetric Privacy Toggles, & Logout)
// Paste into: Settings screen -> onCreate -> Add Source Directly
//
// FIX SUMMARY (this revision):
// - REMOVED the entire "Upload DP" gallery-picker + ImgBB upload
//   system: imagePickerLauncher, choosePhotoBtn, the background
//   Thread that read/compressed/base64'd the bitmap, and the
//   multipart/urlencoded POST to the image host. None of that
//   remains.
// - ADDED a "DP Link" field placed above the Name field. The user
//   pastes an image URL directly into it. This is checked live
//   (350ms debounce, same pattern as the username checker):
//     * empty -> fine, no-op, no status shown
//     * doesn't look like http(s) -> red "enter a valid link"
//     * looks like a link -> attempt to actually load it with
//       Glide; on success show a green "valid image" status AND
//       preview it in the avatar circle; on failure show a red
//       "couldn't load an image from this link" status and revert
//       the avatar preview back to whatever was last known-good.
// - The field is left BLANK whenever the saved photoURL is the
//   generated default (ui-avatars.com or empty) — it never shows
//   our internal default link. When the user already has a real
//   uploaded/linked DP, the field is pre-filled with that link so
//   they can see/edit it.
// - A small grey instruction line sits under the field: search
//   Google for an "image to URL" tool, upload the picture there,
//   and paste the resulting link into this field.
// - "Remove DP" button and its behavior (reset to the generated
//   ui-avatars default) is UNCHANGED and still present.
// - Saving: the DP link is only written to photoURL on "Save
//   Profile Changes", mirroring how name/username are handled,
//   and only if it's non-empty, changed, and passed the live
//   check. Leaving the field blank never touches the existing
//   photoURL (use "Remove DP" for that).
// - COMPILE FIX (kept from prior revision): OnFailureListener.
//   onFailure() takes a plain java.lang.Exception —
//   com.google.android.gms.tasks.Exception is not a real class.
// ============================================================

final com.google.firebase.auth.FirebaseAuth firebaseAuth = com.google.firebase.auth.FirebaseAuth.getInstance();
final com.google.firebase.auth.FirebaseUser currentUser = firebaseAuth.getCurrentUser();

if (currentUser == null) {
    finish();
    return;
}

final String myUid = currentUser.getUid();
final com.google.firebase.database.FirebaseDatabase firebaseDb = com.google.firebase.database.FirebaseDatabase.getInstance();
try { firebaseDb.setPersistenceEnabled(true); } catch (Exception ignored) { /* already enabled elsewhere */ }
final com.google.firebase.database.DatabaseReference rootRef = firebaseDb.getReference();
rootRef.child("users").child(myUid).keepSynced(true);

// Offline-first snapshot. Firebase persistence helps, but this explicit snapshot also
// lets the screen render the complete last-known profile immediately on every open.
final android.content.SharedPreferences profileCache = getSharedPreferences("settings_profile_cache", android.content.Context.MODE_PRIVATE);
final String profileCacheKey = "profile_" + myUid;
final String privacyCacheKey = "privacy_" + myUid;
final String cachedProfileJson = profileCache.getString(profileCacheKey, "");
final String cachedPrivacyJson = profileCache.getString(privacyCacheKey, "");

final float density = getResources().getDisplayMetrics().density;
class Utils {
    int dp(float val) { return (int)(val * density); }
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

final int COLOR_BLUE = android.graphics.Color.parseColor("#0076FF");
final int COLOR_TEXT = android.graphics.Color.parseColor("#1E293B");
final int COLOR_MUTED = android.graphics.Color.parseColor("#64748B");
final int COLOR_LIGHT_MUTED = android.graphics.Color.parseColor("#94A3B8");
final int COLOR_BG = android.graphics.Color.parseColor("#F8FAFC");
final int COLOR_BORDER = android.graphics.Color.parseColor("#E2E8F0");
final int COLOR_RED = android.graphics.Color.parseColor("#EF4444");
final int COLOR_GREEN = android.graphics.Color.parseColor("#10B981");
final int COLOR_AMBER = android.graphics.Color.parseColor("#F59E0B");

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
    android.widget.EditText createInput(android.content.Context ctx, String hint) {
        android.widget.EditText et = new android.widget.EditText(ctx);
        et.setTypeface(emojiTypeface);
        et.setHint(hint);
        et.setTextSize(14.5f);
        et.setTextColor(COLOR_TEXT);
        et.setHintTextColor(COLOR_LIGHT_MUTED);
        et.setBackground(outline(COLOR_BORDER, 12));
        et.setPadding(utils.dp(16), utils.dp(12), utils.dp(16), utils.dp(12));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = utils.dp(4);
        et.setLayoutParams(lp);
        return et;
    }
}
final UiKit ui = new UiKit();

class SettingsDefaultAvatarDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final String letters;
    SettingsDefaultAvatarDrawable(String name) {
        paint.setTypeface(android.graphics.Typeface.create(emojiTypeface, android.graphics.Typeface.BOLD));
        String n = name == null ? "" : name.trim();
        if (n.length() == 0) n = "User";
        String[] parts = n.split("\\s+");
        String a = parts[0].length() == 0 ? "U" : parts[0].substring(0, 1);
        String b = parts.length > 1 && parts[parts.length - 1].length() > 0 ? parts[parts.length - 1].substring(0, 1) : "";
        letters = (a + b).toUpperCase(java.util.Locale.getDefault());
    }
    @Override public void draw(android.graphics.Canvas canvas) {
        android.graphics.Rect b=getBounds(); float cx=b.exactCenterX(), cy=b.exactCenterY(); float r=Math.min(b.width(),b.height())*.5f;
        paint.setStyle(android.graphics.Paint.Style.FILL); paint.setColor(COLOR_BLUE); canvas.drawCircle(cx,cy,r,paint);
        paint.setColor(android.graphics.Color.WHITE); paint.setTextAlign(android.graphics.Paint.Align.CENTER); paint.setTextSize(r*(letters.length()>1?.72f:.9f));
        android.graphics.Paint.FontMetrics fm=paint.getFontMetrics(); canvas.drawText(letters,cx,cy-(fm.ascent+fm.descent)/2f,paint);
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
    @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
}

class FirebaseMessageHelper {
    String friendly(com.google.firebase.database.DatabaseError error) {
        if (error == null) return "Something went wrong. Please try again.";
        switch (error.getCode()) {
            case com.google.firebase.database.DatabaseError.PERMISSION_DENIED: return "You don't have permission to do that.";
            case com.google.firebase.database.DatabaseError.NETWORK_ERROR: return "Network problem. Please check your connection.";
            case com.google.firebase.database.DatabaseError.DISCONNECTED: return "Connection lost. Please try again.";
            default: return "We couldn't sync your changes. Please try again.";
        }
    }
    String friendly(Exception error) {
        if (error == null) return "Something went wrong. Please try again.";
        String m = error.getMessage();
        if (m != null && m.toLowerCase(java.util.Locale.US).contains("permission")) return "You don't have permission to do that.";
        if (m != null && (m.toLowerCase(java.util.Locale.US).contains("network") || m.toLowerCase(java.util.Locale.US).contains("unavailable"))) return "Network problem. Please check your connection.";
        return "We couldn't save your changes. Please try again.";
    }
    void show(String message) { android.widget.Toast.makeText(SettingsActivity.this, message, android.widget.Toast.LENGTH_SHORT).show(); }
}
final FirebaseMessageHelper firebaseMessages = new FirebaseMessageHelper();

// --- Network & Offline Handler ---
class NetworkUtils {
    boolean isOnline(android.content.Context ctx) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            android.net.NetworkInfo netInfo = cm.getActiveNetworkInfo();
            return netInfo != null && netInfo.isConnectedOrConnecting();
        }
        return false;
    }

    void executeIfOnline(final android.content.Context ctx, final Runnable onOnline, final Runnable onOffline) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(ctx);
        pd.setMessage("Connecting...");
        pd.setCancelable(false);
        pd.show();

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                pd.dismiss();
                if (isOnline(ctx)) {
                    if (onOnline != null) onOnline.run();
                } else {
                    if (onOffline != null) onOffline.run();
                    new android.app.AlertDialog.Builder(ctx)
                        .setTitle("Connection Error")
                        .setMessage("You are offline. Please check your internet connection and try again.")
                        .setPositiveButton("OK", null)
                        .show();
                }
            }
        }, 600);
    }
}
final NetworkUtils netUtils = new NetworkUtils();
final Runnable showOffline = new Runnable() {
    @Override public void run() {
        android.widget.Toast.makeText(SettingsActivity.this, "You are offline.", android.widget.Toast.LENGTH_SHORT).show();
    }
};

// --- UI Elements Initialization ---
final android.widget.LinearLayout rootLayout = new android.widget.LinearLayout(SettingsActivity.this);
rootLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
rootLayout.setBackgroundColor(COLOR_BG);

android.widget.LinearLayout header = new android.widget.LinearLayout(SettingsActivity.this);
header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
header.setGravity(android.view.Gravity.CENTER_VERTICAL);
header.setPadding(utils.dp(16), utils.dp(12), utils.dp(16), utils.dp(12));
header.setBackgroundColor(android.graphics.Color.WHITE);

final android.widget.TextView backBtn = new android.widget.TextView(SettingsActivity.this);
backBtn.setTypeface(emojiTypeface);
backBtn.setText("\u2039");
backBtn.setTextSize(30);
backBtn.setTextColor(COLOR_TEXT);
backBtn.setPadding(0, 0, utils.dp(16), utils.dp(4));
backBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) { finish(); }
});
header.addView(backBtn, new android.widget.LinearLayout.LayoutParams(-2, -2));

final android.widget.TextView headerTitle = new android.widget.TextView(SettingsActivity.this);
headerTitle.setTypeface(emojiTypeface);
headerTitle.setText("Settings");
headerTitle.setTextSize(18);
headerTitle.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
headerTitle.setTextColor(COLOR_TEXT);
header.addView(headerTitle, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

rootLayout.addView(header, new android.widget.LinearLayout.LayoutParams(-1, -2));

final android.widget.ScrollView scrollView = new android.widget.ScrollView(SettingsActivity.this);
final android.widget.LinearLayout contentLayout = new android.widget.LinearLayout(SettingsActivity.this);
contentLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
contentLayout.setPadding(utils.dp(16), utils.dp(16), utils.dp(16), utils.dp(24));

// --- Profile Section ---
final android.widget.TextView profileHeader = new android.widget.TextView(SettingsActivity.this);
profileHeader.setTypeface(emojiTypeface);
profileHeader.setText("MY PROFILE");
profileHeader.setTextSize(12);
profileHeader.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
profileHeader.setTextColor(COLOR_MUTED);
android.widget.LinearLayout.LayoutParams profHeadParams = new android.widget.LinearLayout.LayoutParams(-2, -2);
profHeadParams.bottomMargin = utils.dp(8);
profileHeader.setLayoutParams(profHeadParams);
contentLayout.addView(profileHeader);

final android.widget.LinearLayout profileCard = new android.widget.LinearLayout(SettingsActivity.this);
profileCard.setOrientation(android.widget.LinearLayout.VERTICAL);
profileCard.setBackground(ui.outline(COLOR_BORDER, 16));
profileCard.setPadding(utils.dp(16), utils.dp(20), utils.dp(16), utils.dp(20));

final android.widget.LinearLayout avatarContainer = new android.widget.LinearLayout(SettingsActivity.this);
avatarContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
avatarContainer.setGravity(android.view.Gravity.CENTER);
android.widget.LinearLayout.LayoutParams acParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
acParams.bottomMargin = utils.dp(16);
avatarContainer.setLayoutParams(acParams);

final android.widget.FrameLayout avatarFrame = new android.widget.FrameLayout(SettingsActivity.this);
avatarFrame.setLayoutParams(new android.widget.LinearLayout.LayoutParams(utils.dp(84), utils.dp(84)));

final android.widget.ImageView avatarView = new android.widget.ImageView(SettingsActivity.this);
avatarView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
avatarView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
avatarFrame.addView(avatarView);

final android.widget.ProgressBar dpProgressBar = new android.widget.ProgressBar(SettingsActivity.this);
android.widget.FrameLayout.LayoutParams progressParams = new android.widget.FrameLayout.LayoutParams(utils.dp(30), utils.dp(30));
progressParams.gravity = android.view.Gravity.CENTER;
dpProgressBar.setLayoutParams(progressParams);
dpProgressBar.setVisibility(android.view.View.GONE);
avatarFrame.addView(dpProgressBar);

avatarContainer.addView(avatarFrame);

// Only "Remove DP" remains here now — there is no gallery upload button anymore.
android.widget.LinearLayout avatarBtnRow = new android.widget.LinearLayout(SettingsActivity.this);
avatarBtnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
avatarBtnRow.setGravity(android.view.Gravity.CENTER);
android.widget.LinearLayout.LayoutParams rParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
rParams.topMargin = utils.dp(8);
avatarBtnRow.setLayoutParams(rParams);

final android.widget.TextView removeDpBtn = new android.widget.TextView(SettingsActivity.this);
removeDpBtn.setTypeface(emojiTypeface);
removeDpBtn.setText("Remove DP");
removeDpBtn.setTextSize(13);
removeDpBtn.setTextColor(COLOR_RED);
removeDpBtn.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
removeDpBtn.setPadding(utils.dp(12), utils.dp(4), utils.dp(12), utils.dp(4));
removeDpBtn.setVisibility(android.view.View.GONE);
avatarBtnRow.addView(removeDpBtn);

avatarContainer.addView(avatarBtnRow);
profileCard.addView(avatarContainer);

// --- DP Link field (replaces the old gallery-upload flow) ---
final android.widget.EditText dpLinkInput = ui.createInput(SettingsActivity.this, "Paste image URL for your DP");
dpLinkInput.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_CLASS_TEXT);
dpLinkInput.setSingleLine(true);
profileCard.addView(dpLinkInput);

final android.widget.TextView dpLinkStatusHint = new android.widget.TextView(SettingsActivity.this);
dpLinkStatusHint.setTypeface(emojiTypeface);
dpLinkStatusHint.setTextSize(12);
dpLinkStatusHint.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
dpLinkStatusHint.setText("");
android.widget.LinearLayout.LayoutParams dpStatusParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
dpStatusParams.bottomMargin = utils.dp(4);
dpLinkStatusHint.setLayoutParams(dpStatusParams);
profileCard.addView(dpLinkStatusHint);

final android.widget.TextView dpLinkTip = new android.widget.TextView(SettingsActivity.this);
dpLinkTip.setTypeface(emojiTypeface);
dpLinkTip.setText("Tip: search Google for \"image to URL\", upload your photo there, then paste the link you get here.");
dpLinkTip.setTextSize(11.5f);
dpLinkTip.setTextColor(COLOR_LIGHT_MUTED);
android.widget.LinearLayout.LayoutParams dpTipParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
dpTipParams.bottomMargin = utils.dp(16);
dpLinkTip.setLayoutParams(dpTipParams);
profileCard.addView(dpLinkTip);

final android.widget.EditText nameInput = ui.createInput(SettingsActivity.this, "Full Name");
android.widget.LinearLayout.LayoutParams nameSpacing = (android.widget.LinearLayout.LayoutParams) nameInput.getLayoutParams();
nameSpacing.bottomMargin = utils.dp(12);
profileCard.addView(nameInput);

final android.widget.EditText usernameInput = ui.createInput(SettingsActivity.this, "Username");
profileCard.addView(usernameInput);

// --- live status hint under username (mirrors index.html's #settings-username-status) ---
final android.widget.TextView usernameStatusHint = new android.widget.TextView(SettingsActivity.this);
usernameStatusHint.setTypeface(emojiTypeface);
usernameStatusHint.setTextSize(12);
usernameStatusHint.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
usernameStatusHint.setText("");
android.widget.LinearLayout.LayoutParams statusParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
statusParams.bottomMargin = utils.dp(4);
usernameStatusHint.setLayoutParams(statusParams);
profileCard.addView(usernameStatusHint);

final android.widget.TextView usernameLimitNote = new android.widget.TextView(SettingsActivity.this);
usernameLimitNote.setTypeface(emojiTypeface);
usernameLimitNote.setText("Limit: 2 changes within 7 days. Your old username stays reserved for you for 7 days.");
usernameLimitNote.setTextSize(11.5f);
usernameLimitNote.setTextColor(COLOR_LIGHT_MUTED);
android.widget.LinearLayout.LayoutParams limitNoteParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
limitNoteParams.bottomMargin = utils.dp(12);
usernameLimitNote.setLayoutParams(limitNoteParams);
profileCard.addView(usernameLimitNote);

final android.widget.TextView saveProfileBtn = new android.widget.TextView(SettingsActivity.this);
saveProfileBtn.setTypeface(emojiTypeface);
saveProfileBtn.setText("Save Profile Changes");
saveProfileBtn.setTextSize(14.5f);
saveProfileBtn.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
saveProfileBtn.setTextColor(android.graphics.Color.WHITE);
saveProfileBtn.setBackground(ui.rounded(COLOR_BLUE, 12));
saveProfileBtn.setGravity(android.view.Gravity.CENTER);
saveProfileBtn.setPadding(utils.dp(16), utils.dp(14), utils.dp(16), utils.dp(14));
android.widget.LinearLayout.LayoutParams saveBtnParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
saveBtnParams.topMargin = utils.dp(4);
saveProfileBtn.setLayoutParams(saveBtnParams);
profileCard.addView(saveProfileBtn);

contentLayout.addView(profileCard, new android.widget.LinearLayout.LayoutParams(-1, -2));

// --- Privacy Section ---
final android.widget.TextView privacyHeader = new android.widget.TextView(SettingsActivity.this);
privacyHeader.setTypeface(emojiTypeface);
privacyHeader.setText("PRIVACY CONTROLS");
privacyHeader.setTextSize(12);
privacyHeader.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
privacyHeader.setTextColor(COLOR_MUTED);
android.widget.LinearLayout.LayoutParams privHeadParams = new android.widget.LinearLayout.LayoutParams(-2, -2);
privHeadParams.topMargin = utils.dp(24);
privHeadParams.bottomMargin = utils.dp(8);
privacyHeader.setLayoutParams(privHeadParams);
contentLayout.addView(privacyHeader);

final android.widget.LinearLayout privacyCard = new android.widget.LinearLayout(SettingsActivity.this);
privacyCard.setOrientation(android.widget.LinearLayout.VERTICAL);
privacyCard.setBackground(ui.outline(COLOR_BORDER, 16));
privacyCard.setPadding(utils.dp(16), utils.dp(8), utils.dp(16), utils.dp(8));

class SettingRowFactory {
    android.widget.LinearLayout createRow(String title, String subtitle, final android.widget.Switch switchWidget) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(SettingsActivity.this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, utils.dp(12), 0, utils.dp(12));

        android.widget.LinearLayout textCol = new android.widget.LinearLayout(SettingsActivity.this);
        textCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
        tp.rightMargin = utils.dp(12);
        textCol.setLayoutParams(tp);

        android.widget.TextView tLabel = new android.widget.TextView(SettingsActivity.this);
        tLabel.setTypeface(emojiTypeface);
        tLabel.setText(title);
        tLabel.setTextSize(14.5f);
        tLabel.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        tLabel.setTextColor(COLOR_TEXT);
        textCol.addView(tLabel);

        android.widget.TextView sLabel = new android.widget.TextView(SettingsActivity.this);
        sLabel.setTypeface(emojiTypeface);
        sLabel.setText(subtitle);
        sLabel.setTextSize(12);
        sLabel.setTextColor(COLOR_LIGHT_MUTED);
        android.widget.LinearLayout.LayoutParams sp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = utils.dp(2);
        sLabel.setLayoutParams(sp);
        textCol.addView(sLabel);

        row.addView(textCol);
        row.addView(switchWidget, new android.widget.LinearLayout.LayoutParams(-2, -2));
        return row;
    }
}
final SettingRowFactory rowFactory = new SettingRowFactory();

final android.widget.Switch onlineSwitch = new android.widget.Switch(SettingsActivity.this);
onlineSwitch.setTypeface(emojiTypeface);
final android.widget.Switch typingSwitch = new android.widget.Switch(SettingsActivity.this);
typingSwitch.setTypeface(emojiTypeface);
final android.widget.Switch readReceiptsSwitch = new android.widget.Switch(SettingsActivity.this);
readReceiptsSwitch.setTypeface(emojiTypeface);
final android.widget.Switch blockCallsSwitch = new android.widget.Switch(SettingsActivity.this);
blockCallsSwitch.setTypeface(emojiTypeface);

privacyCard.addView(rowFactory.createRow("Show Online Status", "Others can see when you are active.", onlineSwitch));
privacyCard.addView(rowFactory.createRow("Show Typing Indicator", "Others can see when you're typing.", typingSwitch));
privacyCard.addView(rowFactory.createRow("Read Receipts", "Let others know when you've read messages.", readReceiptsSwitch));
privacyCard.addView(rowFactory.createRow("Block All Calls", "Automatically decline incoming audio calls.", blockCallsSwitch));

contentLayout.addView(privacyCard, new android.widget.LinearLayout.LayoutParams(-1, -2));

final android.widget.TextView logoutBtn = new android.widget.TextView(SettingsActivity.this);
logoutBtn.setTypeface(emojiTypeface);
logoutBtn.setText("Log Out");
logoutBtn.setTextSize(14.5f);
logoutBtn.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
logoutBtn.setTextColor(COLOR_RED);
logoutBtn.setBackground(ui.outline(COLOR_BORDER, 16));
logoutBtn.setGravity(android.view.Gravity.CENTER);
logoutBtn.setPadding(utils.dp(16), utils.dp(14), utils.dp(16), utils.dp(14));
android.widget.LinearLayout.LayoutParams lgParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
lgParams.topMargin = utils.dp(24);
logoutBtn.setLayoutParams(lgParams);
contentLayout.addView(logoutBtn);

scrollView.addView(contentLayout, new android.widget.FrameLayout.LayoutParams(-1, -2));
rootLayout.addView(scrollView, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));

setContentView(rootLayout);

// --- Mutable state used by the live username checker & save flow ---
final String[] originalUsername = { "" };
final boolean[] usernameSaveAllowed = { true }; // true = current text is safe to save as-is
final android.os.Handler usernameCheckHandler = new android.os.Handler(android.os.Looper.getMainLooper());
final Runnable[] pendingUsernameCheck = { null };
final java.util.regex.Pattern usernamePattern = java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{3,25}$");

// --- Mutable state used by the live DP-link checker & save flow ---
final String[] originalDpLink = { "" };          // "" whenever the saved photo is our generated default
final String[] latestKnownPhotoURL = { null };   // last photoURL Firebase told us about (may be default url)
final String[] latestKnownName = { "" };         // last name Firebase told us about, for the fallback initials avatar
final boolean[] dpLinkValid = { true };          // true = current text is empty or a confirmed-loadable image
final android.os.Handler dpLinkCheckHandler = new android.os.Handler(android.os.Looper.getMainLooper());
final Runnable[] pendingDpLinkCheck = { null };
final java.util.regex.Pattern httpUrlPattern = java.util.regex.Pattern.compile("^https?://\\S+\\.\\S+$", java.util.regex.Pattern.CASE_INSENSITIVE);

class DpPreviewHelper {
    void revertToLastKnownGood() {
        String url = latestKnownPhotoURL[0];
        boolean isDefault = url == null || url.trim().length() == 0 || url.contains("ui-avatars.com");
        if (isDefault) {
            avatarView.setImageDrawable(new SettingsDefaultAvatarDrawable(latestKnownName[0]));
        } else {
            try {
                com.bumptech.glide.Glide.with(SettingsActivity.this)
                    .load(url).thumbnail(0.1f)
                    .placeholder(new SettingsDefaultAvatarDrawable(latestKnownName[0]))
                    .error(new SettingsDefaultAvatarDrawable(latestKnownName[0]))
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL).circleCrop().into(avatarView);
            } catch (Throwable t) {
                avatarView.setImageDrawable(new SettingsDefaultAvatarDrawable(latestKnownName[0]));
            }
        }
    }
}
final DpPreviewHelper dpPreview = new DpPreviewHelper();

final boolean[] isInitializing = { true };

class SettingsCache {
    void saveProfile(String name, String username, String photoURL) {
        try {
            profileCache.edit().putString(profileCacheKey, new org.json.JSONObject()
                .put("name", name == null ? "" : name)
                .put("username", username == null ? "" : username)
                .put("photoURL", photoURL == null ? "" : photoURL).toString()).apply();
        } catch (Exception ignored) {}
    }
    void savePrivacy(boolean showOnline, boolean showTyping, boolean readReceipts, boolean blockCalls) {
        try {
            profileCache.edit().putString(privacyCacheKey, new org.json.JSONObject()
                .put("showOnline", showOnline).put("showTyping", showTyping)
                .put("readReceipts", readReceipts).put("blockCalls", blockCalls).toString()).apply();
        } catch (Exception ignored) {}
    }
    void hydrateProfile(String json) {
        if (json == null || json.trim().isEmpty()) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            String name = o.optString("name", "");
            String username = o.optString("username", "");
            String photoURL = o.optString("photoURL", "");
            latestKnownName[0] = name;
            latestKnownPhotoURL[0] = photoURL;
            if (!name.isEmpty()) nameInput.setText(name);
            if (!username.isEmpty()) { usernameInput.setText(username); originalUsername[0] = username; }
            boolean isDefault = photoURL.isEmpty() || photoURL.contains("ui-avatars.com");
            removeDpBtn.setVisibility(isDefault ? android.view.View.GONE : android.view.View.VISIBLE);
            dpLinkInput.setText(isDefault ? "" : photoURL);
            originalDpLink[0] = isDefault ? "" : photoURL;
            dpPreview.revertToLastKnownGood();
        } catch (Exception ignored) {}
    }
    void hydratePrivacy(String json) {
        if (json == null || json.trim().isEmpty()) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            isInitializing[0] = true;
            onlineSwitch.setChecked(o.optBoolean("showOnline", true));
            typingSwitch.setChecked(o.optBoolean("showTyping", true));
            readReceiptsSwitch.setChecked(o.optBoolean("readReceipts", true));
            blockCallsSwitch.setChecked(o.optBoolean("blockCalls", false));
            isInitializing[0] = false;
        } catch (Exception ignored) { isInitializing[0] = false; }
    }
}
final SettingsCache settingsCache = new SettingsCache();
settingsCache.hydrateProfile(cachedProfileJson);
settingsCache.hydratePrivacy(cachedPrivacyJson);

// --- Firebase Data Binding ---
rootRef.child("users").child(myUid).addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        String name = snapshot.child("name").getValue(String.class);
        String username = snapshot.child("username").getValue(String.class);
        String photoURL = snapshot.child("photoURL").getValue(String.class);

        latestKnownName[0] = name == null ? "" : name;
        latestKnownPhotoURL[0] = photoURL;
        settingsCache.saveProfile(name, username, photoURL);

        if (nameInput.getText().toString().isEmpty() && name != null) nameInput.setText(name);
        if (usernameInput.getText().toString().isEmpty() && username != null) {
            usernameInput.setText(username);
            originalUsername[0] = username;
        }

        boolean isDefaultPhoto = photoURL == null || photoURL.trim().length() == 0 || photoURL.contains("ui-avatars.com");
        removeDpBtn.setVisibility(isDefaultPhoto ? android.view.View.GONE : android.view.View.VISIBLE);

        // Only pre-fill the DP link box for a real, non-default photo — and only
        // the first time (don't clobber whatever the user is currently typing).
        if (dpLinkInput.getText().toString().isEmpty()) {
            if (!isDefaultPhoto && photoURL != null) {
                dpLinkInput.setText(photoURL);
                originalDpLink[0] = photoURL;
            } else {
                originalDpLink[0] = "";
            }
        }

        if (isDefaultPhoto) {
            avatarView.setImageDrawable(new SettingsDefaultAvatarDrawable(name));
        } else {
            try {
                com.bumptech.glide.Glide.with(SettingsActivity.this)
                    .load(photoURL).thumbnail(0.1f)
                    .placeholder(new SettingsDefaultAvatarDrawable(name))
                    .error(new SettingsDefaultAvatarDrawable(name))
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL).circleCrop().into(avatarView);
            } catch (Throwable t) { avatarView.setImageDrawable(new SettingsDefaultAvatarDrawable(name)); }
        }
    }
    @Override
    public void onCancelled(com.google.firebase.database.DatabaseError error) { firebaseMessages.show(firebaseMessages.friendly(error)); }
});

rootRef.child("users").child(myUid).child("privacy").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override
    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
        isInitializing[0] = true;
        Boolean showOnline = snapshot.child("showOnline").getValue(Boolean.class);
        Boolean showTyping = snapshot.child("showTyping").getValue(Boolean.class);
        Boolean readReceipts = snapshot.child("readReceipts").getValue(Boolean.class);
        Boolean blockCalls = snapshot.child("blockCalls").getValue(Boolean.class);

        onlineSwitch.setChecked(showOnline == null || showOnline);
        typingSwitch.setChecked(showTyping == null || showTyping);
        readReceiptsSwitch.setChecked(readReceipts == null || readReceipts);
        blockCallsSwitch.setChecked(blockCalls != null && blockCalls);
        settingsCache.savePrivacy(showOnline == null || showOnline, showTyping == null || showTyping,
            readReceipts == null || readReceipts, blockCalls != null && blockCalls);
        isInitializing[0] = false;
    }
    @Override
    public void onCancelled(com.google.firebase.database.DatabaseError error) { firebaseMessages.show(firebaseMessages.friendly(error)); }
});

// ============================================================
// REAL-TIME USERNAME AVAILABILITY CHECK
// Mirrors index.html's checkSettingsUsernameLive():
//  - unchanged from current username -> always fine, no network hit
//  - must match ^[a-zA-Z0-9_-]{3,25}$
//  - looked up in usernames/{name}: available if node doesn't exist,
//    OR node's uid == myUid, OR node's reservedUntil has passed
// ============================================================
usernameInput.addTextChangedListener(new android.text.TextWatcher() {
    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
    @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

    @Override
    public void afterTextChanged(android.text.Editable editable) {
        if (pendingUsernameCheck[0] != null) usernameCheckHandler.removeCallbacks(pendingUsernameCheck[0]);

        final String typed = editable.toString().trim();
        final String clean = typed.toLowerCase();

        if (!clean.equals(originalUsername[0]) && !netUtils.isOnline(SettingsActivity.this)) {
            usernameSaveAllowed[0] = false;
            usernameStatusHint.setTextColor(COLOR_AMBER);
            usernameStatusHint.setText("You are offline.");
            saveProfileBtn.setAlpha(0.55f);
            return;
        }

        if (clean.equals(originalUsername[0])) {
            usernameStatusHint.setText("");
            usernameSaveAllowed[0] = true;
            saveProfileBtn.setAlpha(1.0f);
            return;
        }

        if (clean.isEmpty()) {
            usernameStatusHint.setText("");
            usernameSaveAllowed[0] = false;
            saveProfileBtn.setAlpha(0.6f);
            return;
        }

        if (!usernamePattern.matcher(clean).matches()) {
            usernameStatusHint.setTextColor(COLOR_RED);
            usernameStatusHint.setText("\u274C Letters, numbers, \"_\" or \"-\" (3-25 chars)");
            usernameSaveAllowed[0] = false;
            saveProfileBtn.setAlpha(0.6f);
            return;
        }

        usernameStatusHint.setTextColor(COLOR_AMBER);
        usernameStatusHint.setText("\u23F3 Checking...");
        usernameSaveAllowed[0] = false;
        saveProfileBtn.setAlpha(0.6f);

        Runnable checkTask = new Runnable() {
            @Override
            public void run() {
                // Offline guard: don't leave the hint stuck on "Checking..." forever,
                // and don't fire a Firebase call that has nothing to talk to.
                if (!netUtils.isOnline(SettingsActivity.this)) {
                    String currentText = usernameInput.getText().toString().trim().toLowerCase();
                    if (!currentText.equals(clean)) return;
                    usernameStatusHint.setTextColor(COLOR_AMBER);
                    usernameStatusHint.setText("\u26A0 No internet connection — can't check availability");
                    usernameSaveAllowed[0] = false;
                    saveProfileBtn.setAlpha(0.6f);
                    return;
                }

                rootRef.child("usernames").child(clean).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                        // Guard against stale results if the user kept typing.
                        String currentText = usernameInput.getText().toString().trim().toLowerCase();
                        if (!currentText.equals(clean)) return;

                        boolean isAvailable;
                        if (!snap.exists()) {
                            isAvailable = true;
                        } else {
                            String ownerUid = snap.child("uid").getValue(String.class);
                            Long reservedUntil = snap.child("reservedUntil").getValue(Long.class);
                            boolean reservationExpired = reservedUntil != null && reservedUntil < System.currentTimeMillis();
                            isAvailable = myUid.equals(ownerUid) || reservationExpired;
                        }

                        if (isAvailable) {
                            usernameStatusHint.setTextColor(COLOR_GREEN);
                            usernameStatusHint.setText("\u2705 Available to you");
                            usernameSaveAllowed[0] = true;
                            saveProfileBtn.setAlpha(1.0f);
                        } else {
                            usernameStatusHint.setTextColor(COLOR_RED);
                            usernameStatusHint.setText("\u274C Username already taken or reserved");
                            usernameSaveAllowed[0] = false;
                            saveProfileBtn.setAlpha(0.6f);
                        }
                    }
                    @Override
                    public void onCancelled(com.google.firebase.database.DatabaseError error) {
                        usernameStatusHint.setTextColor(COLOR_RED);
                        usernameStatusHint.setText("Couldn't check username availability. Please try again.");
                        usernameSaveAllowed[0] = false;
                        firebaseMessages.show(firebaseMessages.friendly(error));
                    }
                });
            }
        };
        pendingUsernameCheck[0] = checkTask;
        usernameCheckHandler.postDelayed(checkTask, 350); // debounce, matches web app's 350ms
    }
});

// ============================================================
// REAL-TIME DP LINK CHECK
//  - unchanged from current saved link -> fine, no-op
//  - empty -> fine (means "don't touch photoURL" on save)
//  - must at least look like an http(s) URL
//  - then actually attempt to load it with Glide; only a real,
//    successful image load counts as valid, and on success the
//    avatar preview is updated live. A failed load reverts the
//    preview back to whatever Firebase last confirmed.
// ============================================================
dpLinkInput.addTextChangedListener(new android.text.TextWatcher() {
    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
    @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

    @Override
    public void afterTextChanged(android.text.Editable editable) {
        if (pendingDpLinkCheck[0] != null) dpLinkCheckHandler.removeCallbacks(pendingDpLinkCheck[0]);

        final String typed = editable.toString().trim();

        if (!typed.equals(originalDpLink[0]) && !netUtils.isOnline(SettingsActivity.this)) {
            dpLinkValid[0] = false;
            dpProgressBar.setVisibility(android.view.View.GONE);
            dpLinkStatusHint.setTextColor(COLOR_AMBER);
            dpLinkStatusHint.setText("You are offline.");
            dpPreview.revertToLastKnownGood();
            return;
        }

        if (typed.equals(originalDpLink[0])) {
            dpLinkStatusHint.setText("");
            dpProgressBar.setVisibility(android.view.View.GONE);
            dpLinkValid[0] = true;
            dpPreview.revertToLastKnownGood();
            return;
        }

        if (typed.isEmpty()) {
            dpLinkStatusHint.setText("");
            dpProgressBar.setVisibility(android.view.View.GONE);
            dpLinkValid[0] = true; // empty = leave photoURL untouched on save
            dpPreview.revertToLastKnownGood();
            return;
        }

        if (!httpUrlPattern.matcher(typed).matches()) {
            dpLinkStatusHint.setTextColor(COLOR_RED);
            dpLinkStatusHint.setText("\u274C Enter a valid http(s) image link");
            dpProgressBar.setVisibility(android.view.View.GONE);
            dpLinkValid[0] = false;
            return;
        }

        dpLinkStatusHint.setTextColor(COLOR_AMBER);
        dpLinkStatusHint.setText("\u23F3 Checking link...");
        dpProgressBar.setVisibility(android.view.View.VISIBLE);
        dpLinkValid[0] = false;

        Runnable checkTask = new Runnable() {
            @Override
            public void run() {
                if (!netUtils.isOnline(SettingsActivity.this)) {
                    String currentText = dpLinkInput.getText().toString().trim();
                    if (!currentText.equals(typed)) return;
                    dpProgressBar.setVisibility(android.view.View.GONE);
                    dpLinkStatusHint.setTextColor(COLOR_AMBER);
                    dpLinkStatusHint.setText("\u26A0 No internet connection — can't verify link");
                    dpLinkValid[0] = false;
                    return;
                }

                try {
                    com.bumptech.glide.Glide.with(SettingsActivity.this)
                        .load(typed)
                        .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                            @Override
                            public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                                String currentText = dpLinkInput.getText().toString().trim();
                                if (!currentText.equals(typed)) return true;
                                dpProgressBar.setVisibility(android.view.View.GONE);
                                dpLinkStatusHint.setTextColor(COLOR_RED);
                                dpLinkStatusHint.setText("\u274C Couldn't load an image from this link");
                                dpLinkValid[0] = false;
                                dpPreview.revertToLastKnownGood();
                                return true;
                            }
                            @Override
                            public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                String currentText = dpLinkInput.getText().toString().trim();
                                if (!currentText.equals(typed)) return false;
                                dpProgressBar.setVisibility(android.view.View.GONE);
                                dpLinkStatusHint.setTextColor(COLOR_GREEN);
                                dpLinkStatusHint.setText("\u2705 Valid image — preview updated");
                                dpLinkValid[0] = true;
                                return false;
                            }
                        })
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        .circleCrop()
                        .into(avatarView);
                } catch (Throwable t) {
                    dpProgressBar.setVisibility(android.view.View.GONE);
                    dpLinkStatusHint.setTextColor(COLOR_RED);
                    dpLinkStatusHint.setText("\u274C Couldn't load an image from this link");
                    dpLinkValid[0] = false;
                    dpPreview.revertToLastKnownGood();
                }
            }
        };
        pendingDpLinkCheck[0] = checkTask;
        dpLinkCheckHandler.postDelayed(checkTask, 350); // debounce, matches the username checker
    }
});

// --- Action Listeners with Offline Protection ---

// ============================================================
// SAVE HANDLER — performs the same guarded username rename as
// index.html's handleSaveSettings(), and now also commits the
// DP link (if it's non-empty, changed, and passed the live check):
//  1. If the availability check hasn't cleared the current text,
//     block the save and re-explain why (no silent overwrite).
//  2. If the username is unchanged, only save name/photo as before.
//  3. If changed: enforce max 2 changes / 7 days using
//     users/{uid}/usernameHistory, then atomically:
//       - mark usernames/{new} active for this uid
//       - mark usernames/{old} inactive with a 7-day reservedUntil
//       - update users/{uid}/username
//       - record users/{uid}/usernameHistory/{now} = old username
// ============================================================
saveProfileBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        if (!netUtils.isOnline(SettingsActivity.this)) { showOffline.run(); return; }
        netUtils.executeIfOnline(SettingsActivity.this, new Runnable() {
            public void run() {
                final String newName = nameInput.getText().toString().trim();
                final String newUsername = usernameInput.getText().toString().trim().toLowerCase();
                final String oldUsername = originalUsername[0];
                final String newDpLink = dpLinkInput.getText().toString().trim();

                if (!newName.isEmpty()) {
                    rootRef.child("users").child(myUid).child("name").setValue(newName)
                        .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                            @Override public void onFailure(Exception e) { firebaseMessages.show(firebaseMessages.friendly(e)); }
                        });
                }

                // DP link: only touch photoURL if the user actually typed a new,
                // verified link. An empty box never overwrites the existing photo —
                // use "Remove DP" for that.
                if (!newDpLink.isEmpty() && !newDpLink.equals(originalDpLink[0])) {
                    if (!dpLinkValid[0]) {
                        android.widget.Toast.makeText(SettingsActivity.this, "Please enter a valid, loadable image link for your DP.", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    rootRef.child("users").child(myUid).child("photoURL").setValue(newDpLink)
                        .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Void>() {
                            @Override public void onSuccess(Void unused) { originalDpLink[0] = newDpLink; }
                        })
                        .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                            @Override public void onFailure(Exception e) { firebaseMessages.show(firebaseMessages.friendly(e)); }
                        });
                }

                if (newUsername.isEmpty()) {
                    android.widget.Toast.makeText(SettingsActivity.this, "Username cannot be empty", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }

                if (newUsername.equals(oldUsername)) {
                    android.widget.Toast.makeText(SettingsActivity.this, "Profile Saved", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }

                if (!usernamePattern.matcher(newUsername).matches()) {
                    android.widget.Toast.makeText(SettingsActivity.this, "Invalid username format", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }

                if (!usernameSaveAllowed[0]) {
                    android.widget.Toast.makeText(SettingsActivity.this, "That username isn't available yet — please wait for the check to finish.", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }

                // Re-verify against the server right before committing (closes any
                // race where the user typed, waited, then someone else grabbed it).
                rootRef.child("usernames").child(newUsername).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(com.google.firebase.database.DataSnapshot nameSnap) {
                        if (nameSnap.exists()) {
                            String ownerUid = nameSnap.child("uid").getValue(String.class);
                            Long reservedUntil = nameSnap.child("reservedUntil").getValue(Long.class);
                            boolean reservationExpired = reservedUntil != null && reservedUntil < System.currentTimeMillis();
                            if (!myUid.equals(ownerUid) && !reservationExpired) {
                                usernameStatusHint.setTextColor(COLOR_RED);
                                usernameStatusHint.setText("\u274C Username already taken or reserved");
                                android.widget.Toast.makeText(SettingsActivity.this, "That username was just taken. Please choose another.", android.widget.Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }

                        // Enforce 2 changes / 7 days, exactly like the web app.
                        rootRef.child("users").child(myUid).child("usernameHistory")
                            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                                @Override
                                public void onDataChange(com.google.firebase.database.DataSnapshot historySnap) {
                                    long now = System.currentTimeMillis();
                                    long sevenDaysMs = 7L * 24 * 60 * 60 * 1000;
                                    int recentChanges = 0;
                                    for (com.google.firebase.database.DataSnapshot child : historySnap.getChildren()) {
                                        try {
                                            long ts = Long.parseLong(child.getKey());
                                            if (now - ts < sevenDaysMs) recentChanges++;
                                        } catch (NumberFormatException ignored) {}
                                    }

                                    if (recentChanges >= 2) {
                                        new android.app.AlertDialog.Builder(SettingsActivity.this)
                                            .setTitle("Limit Reached")
                                            .setMessage("You have reached the maximum of 2 username changes within 7 days.")
                                            .setPositiveButton("OK", null)
                                            .show();
                                        return;
                                    }

                                    long sevenDaysLater = now + sevenDaysMs;
                                    java.util.Map<String, Object> updates = new java.util.HashMap<>();
                                    java.util.Map<String, Object> newUnameNode = new java.util.HashMap<>();
                                    newUnameNode.put("uid", myUid);
                                    newUnameNode.put("active", true);
                                    updates.put("usernames/" + newUsername, newUnameNode);

                                    if (oldUsername != null && !oldUsername.isEmpty()) {
                                        java.util.Map<String, Object> oldUnameNode = new java.util.HashMap<>();
                                        oldUnameNode.put("uid", myUid);
                                        oldUnameNode.put("active", false);
                                        oldUnameNode.put("reservedUntil", sevenDaysLater);
                                        updates.put("usernames/" + oldUsername, oldUnameNode);
                                    }

                                    updates.put("users/" + myUid + "/username", newUsername);
                                    updates.put("users/" + myUid + "/usernameHistory/" + now, oldUsername);

                                    rootRef.updateChildren(updates).addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Void>() {
                                        @Override
                                        public void onSuccess(Void unused) {
                                            originalUsername[0] = newUsername;
                                            usernameStatusHint.setText("");
                                            android.widget.Toast.makeText(SettingsActivity.this, "Profile updated successfully!", android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                    }).addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                                        @Override
                                        public void onFailure(Exception e) {
                                            firebaseMessages.show(firebaseMessages.friendly(e));
                                        }
                                    });
                                }
                                @Override
                                public void onCancelled(com.google.firebase.database.DatabaseError error) {
                                    firebaseMessages.show(firebaseMessages.friendly(error));
                                }
                            });
                    }
                    @Override
                    public void onCancelled(com.google.firebase.database.DatabaseError error) {
                        firebaseMessages.show(firebaseMessages.friendly(error));
                    }
                });
            }
        }, null);
    }
});

removeDpBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        if (!netUtils.isOnline(SettingsActivity.this)) { showOffline.run(); return; }
        netUtils.executeIfOnline(SettingsActivity.this, new Runnable() {
            public void run() {
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                    .setTitle("Confirmation")
                    .setMessage("Are you sure you want to remove your profile picture?")
                    .setPositiveButton("Yes", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            String currentName = nameInput.getText().toString().trim();
                            if (currentName.isEmpty()) currentName = "User";
                            String defaultUrl = "https://ui-avatars.com/api/?name=" + android.net.Uri.encode(currentName) + "&background=0076FF&color=fff&size=150";
                            avatarView.setImageDrawable(new SettingsDefaultAvatarDrawable(currentName));
                            dpLinkInput.setText("");
                            originalDpLink[0] = "";
                            rootRef.child("users").child(myUid).child("photoURL").setValue(defaultUrl)
                                .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Void>() {
                                    @Override public void onSuccess(Void unused) { android.widget.Toast.makeText(SettingsActivity.this, "Default profile picture applied.", android.widget.Toast.LENGTH_SHORT).show(); }
                                })
                                .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                                    @Override public void onFailure(Exception e) { firebaseMessages.show(firebaseMessages.friendly(e)); }
                                });
                        }
                    })
                    .setNegativeButton("No", null)
                    .show();
            }
        }, null);
    }
});

android.widget.CompoundButton.OnCheckedChangeListener switchListener = new android.widget.CompoundButton.OnCheckedChangeListener() {
    @Override
    public void onCheckedChanged(final android.widget.CompoundButton buttonView, final boolean isChecked) {
        if (isInitializing[0]) return;
        if (!netUtils.isOnline(SettingsActivity.this)) {
            isInitializing[0] = true;
            buttonView.setChecked(!isChecked);
            isInitializing[0] = false;
            showOffline.run();
            return;
        }
        netUtils.executeIfOnline(SettingsActivity.this, new Runnable() {
            public void run() {
                String key = "";
                if (buttonView == onlineSwitch) key = "showOnline";
                else if (buttonView == typingSwitch) key = "showTyping";
                else if (buttonView == readReceiptsSwitch) key = "readReceipts";
                else if (buttonView == blockCallsSwitch) key = "blockCalls";

                if (!key.isEmpty()) rootRef.child("users").child(myUid).child("privacy").child(key).setValue(isChecked)
                    .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                        @Override public void onFailure(Exception e) { firebaseMessages.show(firebaseMessages.friendly(e)); }
                    });
            }
        }, new Runnable() {
            public void run() {
                isInitializing[0] = true;
                buttonView.setChecked(!isChecked);
                isInitializing[0] = false;
            }
        });
    }
};

onlineSwitch.setOnCheckedChangeListener(switchListener);
typingSwitch.setOnCheckedChangeListener(switchListener);
readReceiptsSwitch.setOnCheckedChangeListener(switchListener);
blockCallsSwitch.setOnCheckedChangeListener(switchListener);

final com.google.firebase.auth.FirebaseUser settingsCallUser = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
if (settingsCallUser != null) {
    final String settingsCallUid = settingsCallUser.getUid();
    final java.util.Set<String> settingsHandledCallIds = new java.util.HashSet<>();
    rootRef.child("calls").orderByChild("receiverId").equalTo(settingsCallUid)
        .addChildEventListener(new com.google.firebase.database.ChildEventListener() {
            @Override public void onChildAdded(com.google.firebase.database.DataSnapshot snap, String prev) { handleIncoming(snap); }
            @Override public void onChildChanged(com.google.firebase.database.DataSnapshot snap, String prev) { handleIncoming(snap); }
            void handleIncoming(com.google.firebase.database.DataSnapshot snap) {
                String status=snap.child("status").getValue(String.class); String callId=snap.getKey();
                if ("ringing".equals(status) && callId != null && settingsHandledCallIds.add(callId)) {
                    try {
                        android.content.Intent i=new android.content.Intent(SettingsActivity.this, Class.forName(getPackageName()+".CallActivity"));
                        i.putExtra("incoming",true); i.putExtra("callId",callId);
                        i.putExtra("otherUid",snap.child("callerId").getValue(String.class));
                        i.putExtra("otherName",snap.child("callerName").getValue(String.class));
                        i.putExtra("otherPhoto",snap.child("callerPhoto").getValue(String.class));
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i);
                    } catch (Exception e) { firebaseMessages.show("Incoming call couldn't be opened."); }
                }
            }
            @Override public void onChildRemoved(com.google.firebase.database.DataSnapshot snap) {}
            @Override public void onChildMoved(com.google.firebase.database.DataSnapshot snap, String prev) {}
            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) { firebaseMessages.show(firebaseMessages.friendly(error)); }
        });
}

logoutBtn.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View v) {
        if (!netUtils.isOnline(SettingsActivity.this)) { showOffline.run(); return; }
        netUtils.executeIfOnline(SettingsActivity.this, new Runnable() {
            public void run() {
                try { rootRef.child("users").child(myUid).child("status").child("state").setValue("offline"); } catch (Exception e) {}
                firebaseAuth.signOut();
                try {
                    android.content.Intent intent = new android.content.Intent(SettingsActivity.this, Class.forName(getPackageName() + ".LoginActivity"));
                    intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                } catch (Exception e) { finish(); }
            }
        }, null);
    }
});
