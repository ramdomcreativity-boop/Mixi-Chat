// ===== onCreate code =====



// ============================================================
// MIXI CHAT — DISCOVER ACTIVITY
// Sketchware Pro: paste this entire file into:
// DiscoverActivity -> onCreate -> Add Source Directly
// Do not paste it into the Java source/class body.
// This is the only file needed for the Discover page.
// ============================================================

final com.google.firebase.auth.FirebaseUser currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
if (currentUser == null) {
    finish();
    return;
}
final String myUid = currentUser.getUid();
final com.google.firebase.database.DatabaseReference rootRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference();
final android.content.SharedPreferences cachePrefs = getSharedPreferences("MixiCache", android.content.Context.MODE_PRIVATE);
final float discoverDensity = getResources().getDisplayMetrics().density;

class DiscoverUtils {
    int dp(float value) { return (int)(value * discoverDensity + 0.5f); }
}
final DiscoverUtils utils = new DiscoverUtils();

// Use the app's custom emoji font everywhere on this screen.
final android.graphics.Typeface emojiTypeface;
android.graphics.Typeface loadedEmojiTypeface;
try {
    loadedEmojiTypeface = android.graphics.Typeface.createFromAsset(getAssets(), "twemoji/iOS_Emojis.ttf");
} catch (Throwable ignored) {
    loadedEmojiTypeface = android.graphics.Typeface.DEFAULT;
}
emojiTypeface = loadedEmojiTypeface;

class DiscoverUi {
    android.graphics.drawable.GradientDrawable rounded(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * discoverDensity);
        return d;
    }
}
final DiscoverUi ui = new DiscoverUi();

class DiscoverDefaultAvatarDrawable extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final String letters;
    DiscoverDefaultAvatarDrawable(String name) {
        paint.setTypeface(android.graphics.Typeface.create(emojiTypeface, android.graphics.Typeface.BOLD));
        String n=name==null?"":name.trim(); if(n.length()==0)n="User"; String[] p=n.split("\\s+");
        String a=p[0].length()==0?"U":p[0].substring(0,1); String b=p.length>1?p[p.length-1].substring(0,1):"";
        letters=(a+b).toUpperCase(java.util.Locale.getDefault());
    }
    @Override public void draw(android.graphics.Canvas c){ android.graphics.Rect b=getBounds(); float x=b.exactCenterX(),y=b.exactCenterY(),r=Math.min(b.width(),b.height())*.5f; paint.setStyle(android.graphics.Paint.Style.FILL); paint.setColor(android.graphics.Color.parseColor("#0076FF")); c.drawCircle(x,y,r,paint); paint.setColor(android.graphics.Color.WHITE); paint.setTextAlign(android.graphics.Paint.Align.CENTER); paint.setTextSize(r*(letters.length()>1?.72f:.9f)); android.graphics.Paint.FontMetrics f=paint.getFontMetrics(); c.drawText(letters,x,y-(f.ascent+f.descent)/2f,paint); }
    @Override public void setAlpha(int a){paint.setAlpha(a);} @Override public void setColorFilter(android.graphics.ColorFilter f){paint.setColorFilter(f);} @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
}
class DiscoverMessageHelper {
    boolean online(){ android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getSystemService(android.content.Context.CONNECTIVITY_SERVICE); android.net.NetworkInfo n=cm==null?null:cm.getActiveNetworkInfo(); return n!=null&&n.isConnectedOrConnecting(); }
    void offline(){ android.widget.Toast.makeText(DiscoverActivity.this,"Please connect to the internet and try again.",android.widget.Toast.LENGTH_SHORT).show(); }
    String friendly(com.google.firebase.database.DatabaseError e){ if(e==null)return "Something went wrong. Please try again."; if(e.getCode()==com.google.firebase.database.DatabaseError.PERMISSION_DENIED)return "You don't have permission to do that."; if(e.getCode()==com.google.firebase.database.DatabaseError.NETWORK_ERROR||e.getCode()==com.google.firebase.database.DatabaseError.DISCONNECTED)return "Network problem. Please check your connection."; return "We couldn't sync this page. Please try again."; }
    void show(String text){ android.widget.Toast.makeText(DiscoverActivity.this,text,android.widget.Toast.LENGTH_SHORT).show(); }
}
final DiscoverMessageHelper discoverMessages = new DiscoverMessageHelper();

final android.widget.FrameLayout discoverPageOverlay = new android.widget.FrameLayout(DiscoverActivity.this);
discoverPageOverlay.setBackgroundColor(android.graphics.Color.WHITE);
discoverPageOverlay.setVisibility(android.view.View.VISIBLE);
discoverPageOverlay.setElevation(utils.dp(20));

final int DISCOVER_BLUE = android.graphics.Color.parseColor("#0076FF");
final int DISCOVER_BLUE_LIGHT = android.graphics.Color.parseColor("#EBF4FF");
final int DISCOVER_TEXT = android.graphics.Color.parseColor("#0F172A");
final int DISCOVER_MUTED = android.graphics.Color.parseColor("#64748B");
final int DISCOVER_BORDER = android.graphics.Color.parseColor("#E2E8F0");
final int DISCOVER_BG = android.graphics.Color.parseColor("#F8FAFC");
final int DISCOVER_GREEN = android.graphics.Color.parseColor("#10B981");
final int DISCOVER_AMBER = android.graphics.Color.parseColor("#F59E0B");
final int DISCOVER_RED = android.graphics.Color.parseColor("#EF4444");
final int DISCOVER_PAGE_SIZE = 10;

final android.content.SharedPreferences discoverPrefs = cachePrefs;
final java.util.concurrent.ExecutorService discoverDiskExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
final java.util.List<java.util.HashMap<String, String>> discoverUsers = new java.util.ArrayList<>();
final java.util.HashMap<String, java.util.HashMap<String, String>> discoverRelationships = new java.util.HashMap<>();
final String[] discoverMyName = { currentUser.getDisplayName() == null || currentUser.getDisplayName().length() == 0 ? "My Name" : currentUser.getDisplayName() };
final String[] discoverMyUsername = { "You" };
final String[] discoverMyPhoto = { currentUser.getPhotoUrl() == null ? "" : currentUser.getPhotoUrl().toString() };
final boolean[] discoverLoading = { false };
final boolean[] discoverFinished = { false };
final int[] discoverPageNumber = { 0 };
final String[] discoverLastKey = { null };

final android.widget.LinearLayout discoverRoot = new android.widget.LinearLayout(DiscoverActivity.this);
discoverRoot.setOrientation(android.widget.LinearLayout.VERTICAL);
discoverRoot.setBackgroundColor(android.graphics.Color.WHITE);
discoverPageOverlay.addView(discoverRoot, new android.widget.FrameLayout.LayoutParams(-1, -1));

final android.widget.LinearLayout discoverHeader = new android.widget.LinearLayout(DiscoverActivity.this);
discoverHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
discoverHeader.setPadding(utils.dp(18), utils.dp(12), utils.dp(18), utils.dp(12));
discoverHeader.setBackgroundColor(android.graphics.Color.WHITE);

final android.widget.TextView discoverBack = new android.widget.TextView(DiscoverActivity.this);
discoverBack.setTypeface(emojiTypeface);
discoverBack.setText("‹");
discoverBack.setTextSize(38);
discoverBack.setTextColor(DISCOVER_TEXT);
discoverBack.setGravity(android.view.Gravity.CENTER);
discoverBack.setPadding(0, 0, utils.dp(8), utils.dp(4));
discoverHeader.addView(discoverBack, new android.widget.LinearLayout.LayoutParams(utils.dp(38), utils.dp(44)));

final android.widget.TextView discoverTitle = new android.widget.TextView(DiscoverActivity.this);
discoverTitle.setTypeface(emojiTypeface);
discoverTitle.setText("Find People");
discoverTitle.setTextSize(18);
discoverTitle.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
discoverTitle.setTextColor(DISCOVER_TEXT);
discoverHeader.addView(discoverTitle, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

discoverRoot.addView(discoverHeader, new android.widget.LinearLayout.LayoutParams(-1, -2));
android.view.View discoverHeaderLine = new android.view.View(DiscoverActivity.this);
discoverHeaderLine.setBackgroundColor(DISCOVER_BORDER);
discoverRoot.addView(discoverHeaderLine, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(1)));

final android.widget.LinearLayout discoverSearchBox = new android.widget.LinearLayout(DiscoverActivity.this);
discoverSearchBox.setPadding(utils.dp(18), utils.dp(14), utils.dp(18), utils.dp(14));
discoverSearchBox.setBackgroundColor(android.graphics.Color.WHITE);
final android.widget.EditText discoverSearch = new android.widget.EditText(DiscoverActivity.this);
discoverSearch.setTypeface(emojiTypeface);
discoverSearch.setSingleLine(true);
discoverSearch.setTextSize(14);
discoverSearch.setTextColor(DISCOVER_TEXT);
discoverSearch.setHintTextColor(DISCOVER_MUTED);
discoverSearch.setHint("Search by name, @username, or name:John...");
discoverSearch.setPadding(utils.dp(16), 0, utils.dp(16), 0);
discoverSearch.setBackground(ui.rounded(DISCOVER_BG, 16));
discoverSearchBox.addView(discoverSearch, new android.widget.LinearLayout.LayoutParams(-1, utils.dp(48)));
discoverRoot.addView(discoverSearchBox, new android.widget.LinearLayout.LayoutParams(-1, -2));

final android.widget.FrameLayout discoverContent = new android.widget.FrameLayout(DiscoverActivity.this);
discoverContent.setBackgroundColor(DISCOVER_BG);
final android.widget.ListView discoverList = new android.widget.ListView(DiscoverActivity.this);
discoverList.setDivider(null);
discoverList.setDividerHeight(utils.dp(10));
discoverList.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
discoverList.setPadding(0, utils.dp(10), 0, utils.dp(20));
discoverList.setClipToPadding(false);
discoverContent.addView(discoverList, new android.widget.FrameLayout.LayoutParams(-1, -1));

final android.widget.TextView discoverEmpty = new android.widget.TextView(DiscoverActivity.this);
discoverEmpty.setTypeface(emojiTypeface);
discoverEmpty.setText("No users found.");
discoverEmpty.setTextSize(14);
discoverEmpty.setTextColor(DISCOVER_MUTED);
discoverEmpty.setGravity(android.view.Gravity.CENTER);
discoverEmpty.setVisibility(android.view.View.GONE);
discoverContent.addView(discoverEmpty, new android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER));

final android.widget.ProgressBar discoverProgress = new android.widget.ProgressBar(DiscoverActivity.this);
discoverProgress.setVisibility(android.view.View.GONE);
android.widget.FrameLayout.LayoutParams discoverProgressParams = new android.widget.FrameLayout.LayoutParams(utils.dp(32), utils.dp(32), android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
discoverProgressParams.bottomMargin = utils.dp(12);
discoverContent.addView(discoverProgress, discoverProgressParams);
discoverRoot.addView(discoverContent, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));

final android.view.View.OnClickListener closeDiscover = new android.view.View.OnClickListener() {
    @Override public void onClick(android.view.View v) {
        finish();
    }
};
discoverBack.setOnClickListener(closeDiscover);

class DiscoverPageFunctions {
    android.graphics.drawable.GradientDrawable outline(float radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(android.graphics.Color.WHITE);
        d.setStroke(utils.dp(1.3f), DISCOVER_BORDER);
        d.setCornerRadius(utils.dp(radius));
        return d;
    }
    android.graphics.drawable.GradientDrawable blueBg() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(DISCOVER_BLUE);
        d.setCornerRadius(utils.dp(999));
        return d;
    }
    android.graphics.drawable.GradientDrawable redOutline() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(android.graphics.Color.WHITE);
        d.setStroke(utils.dp(1.3f), android.graphics.Color.rgb(252,165,165));
        d.setCornerRadius(utils.dp(999));
        return d;
    }
    android.widget.TextView label(String value, float size, int color, boolean bold) {
        android.widget.TextView tv = new android.widget.TextView(DiscoverActivity.this);
        tv.setTypeface(emojiTypeface);
        tv.setText(value);
        tv.setTextSize(size);
        tv.setTextColor(color);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (bold) tv.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        return tv;
    }
    java.util.HashMap<String, String> user(String uid, String name, String username, String photo) {
        java.util.HashMap<String, String> u = new java.util.HashMap<>();
        u.put("uid", uid == null ? "" : uid);
        u.put("name", name == null ? "" : name);
        u.put("username", username == null ? "" : username);
        u.put("photoURL", photo == null ? "" : photo);
        return u;
    }
    java.util.HashMap<String, String> find(String uid) {
        for (java.util.HashMap<String, String> u : discoverUsers) if (uid.equals(u.get("uid"))) return u;
        return null;
    }
    String pairId(String a, String b) { return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a; }
    String relationState(String uid) {
        java.util.HashMap<String, String> r = discoverRelationships.get(pairId(myUid, uid));
        if (r == null) return "not_friends";
        if ("friends".equals(r.get("status"))) return "friends";
        if ("pending".equals(r.get("status"))) return myUid.equals(r.get("requester")) ? "outgoing_pending" : "incoming_pending";
        return "not_friends";
    }
    void showDp(String url, String name) {
        final android.app.Dialog dialog = new android.app.Dialog(DiscoverActivity.this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(DiscoverActivity.this);
        frame.setBackgroundColor(android.graphics.Color.argb(225,0,0,0));
        android.widget.ImageView image = new android.widget.ImageView(DiscoverActivity.this);
        image.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        frame.addView(image, new android.widget.FrameLayout.LayoutParams(utils.dp(290), utils.dp(290), android.view.Gravity.CENTER));
        if (url == null || url.trim().length() == 0 || url.contains("ui-avatars.com")) image.setImageDrawable(new DiscoverDefaultAvatarDrawable(name));
        else try { com.bumptech.glide.Glide.with(DiscoverActivity.this).load(url).placeholder(new DiscoverDefaultAvatarDrawable(name)).error(new DiscoverDefaultAvatarDrawable(name)).diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL).circleCrop().into(image); } catch (Throwable ignored) { image.setImageDrawable(new DiscoverDefaultAvatarDrawable(name)); }
        frame.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { dialog.dismiss(); } });
        dialog.setContentView(frame);
        dialog.show();
    }
    void openChat(final java.util.HashMap<String, String> user, boolean selfChat) {
        try {
            android.content.Intent chatIntent = new android.content.Intent(DiscoverActivity.this, Class.forName(getPackageName() + ".ChatActivity"));
            chatIntent.putExtra("otherUid", user.get("uid"));
            chatIntent.putExtra("otherName", user.get("name"));
            chatIntent.putExtra("otherPhoto", user.get("photoURL"));
            chatIntent.putExtra("isSelfChat", selfChat);
            DiscoverActivity.this.startActivity(chatIntent);
        } catch (ClassNotFoundException e) {
            android.widget.Toast.makeText(DiscoverActivity.this, "ChatActivity not created yet", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    void sendRequest(String uid) {
        if (!discoverMessages.online()) { discoverMessages.offline(); return; }
        String pair = pairId(myUid, uid);
        java.util.HashMap<String, Object> values = new java.util.HashMap<>();
        values.put("userA", myUid.compareTo(uid) < 0 ? myUid : uid);
        values.put("userB", myUid.compareTo(uid) < 0 ? uid : myUid);
        values.put("requester", myUid);
        values.put("recipient", uid);
        values.put("status", "pending");
        values.put("updatedAt", System.currentTimeMillis());
        rootRef.child("relationships").child(pair).setValue(values).addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() { @Override public void onFailure(Exception e) { discoverMessages.show("Friend request could not be sent. Please try again."); } });
        java.util.HashMap<String, String> local = new java.util.HashMap<>();
        local.put("status", "pending"); local.put("requester", myUid); local.put("recipient", uid);
        discoverRelationships.put(pair, local); saveRelationships(); ((android.widget.BaseAdapter) discoverList.getAdapter()).notifyDataSetChanged();
    }
    void accept(String uid) {
        if (!discoverMessages.online()) { discoverMessages.offline(); return; }
        rootRef.child("relationships").child(pairId(myUid, uid)).child("status").setValue("friends").addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() { @Override public void onFailure(Exception e) { discoverMessages.show("Request could not be accepted. Please try again."); } });
        rootRef.child("relationships").child(pairId(myUid, uid)).child("updatedAt").setValue(System.currentTimeMillis()).addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() { @Override public void onFailure(Exception e) { discoverMessages.show("Request could not be updated. Please try again."); } });
        String pair = pairId(myUid, uid); java.util.HashMap<String, String> local = discoverRelationships.get(pair);
        if (local == null) local = new java.util.HashMap<String, String>();
        local.put("status", "friends"); local.put("requester", local.get("requester") == null ? "" : local.get("requester")); local.put("recipient", local.get("recipient") == null ? "" : local.get("recipient"));
        discoverRelationships.put(pair, local); saveRelationships(); ((android.widget.BaseAdapter) discoverList.getAdapter()).notifyDataSetChanged();
    }
    void confirmCancel(final String uid) {
        new android.app.AlertDialog.Builder(DiscoverActivity.this)
            .setTitle("Cancel Friend Request?")
            .setMessage("Are you sure? Do you want to cancel friend request?")
            .setNegativeButton("Keep Request", null)
            .setPositiveButton("Cancel Request", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface dialog, int which) {
                    cancel(uid);
                }
            })
            .show();
    }
    void cancel(String uid) { if (!discoverMessages.online()) { discoverMessages.offline(); return; } rootRef.child("relationships").child(pairId(myUid, uid)).removeValue().addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() { @Override public void onFailure(Exception e) { discoverMessages.show("Request could not be cancelled. Please try again."); } }); discoverRelationships.remove(pairId(myUid, uid)); saveRelationships(); ((android.widget.BaseAdapter) discoverList.getAdapter()).notifyDataSetChanged(); }
    void saveRelationships() {
        discoverDiskExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    org.json.JSONObject all = new org.json.JSONObject();
                    for (java.util.Map.Entry<String, java.util.HashMap<String, String>> entry : discoverRelationships.entrySet()) {
                        org.json.JSONObject one = new org.json.JSONObject();
                        one.put("status", entry.getValue().get("status")); one.put("requester", entry.getValue().get("requester")); one.put("recipient", entry.getValue().get("recipient"));
                        all.put(entry.getKey(), one);
                    }
                    discoverPrefs.edit().putString("discover_relationships_cache", all.toString()).apply();
                } catch (Exception ignored) {}
            }
        });
    }
    void saveMyProfile() {
        discoverPrefs.edit().putString("discover_my_name", discoverMyName[0]).putString("discover_my_username", discoverMyUsername[0]).putString("discover_my_photo", discoverMyPhoto[0]).apply();
    }
    void saveUser(final java.util.HashMap<String, String> user) {
        discoverDiskExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    String uid = user.get("uid");
                    if (uid == null || uid.length() == 0) return;
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("uid", user.get("uid")); o.put("name", user.get("name"));
                    o.put("username", user.get("username")); o.put("photoURL", user.get("photoURL"));
                    discoverPrefs.edit().putString("discover_user_" + uid, o.toString()).apply();
                } catch (Exception ignored) {}
            }
        });
    }
    void saveUsers(final java.util.List<java.util.HashMap<String, String>> users) {
        for (java.util.HashMap<String, String> user : users) saveUser(user);
    }

    void savePage(final int page, final java.util.List<java.util.HashMap<String, String>> users) {
        discoverDiskExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    org.json.JSONArray array = new org.json.JSONArray();
                    for (java.util.HashMap<String, String> u : users) {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("uid", u.get("uid")); o.put("name", u.get("name"));
                        o.put("username", u.get("username")); o.put("photoURL", u.get("photoURL"));
                        array.put(o);
                    }
                    int oldCount = discoverPrefs.getInt("discover_page_count", 0);
                    discoverPrefs.edit().putString("discover_page_" + page, array.toString()).putInt("discover_page_count", Math.max(oldCount, page + 1)).apply();
                } catch (Exception ignored) {}
            }
        });
    }
    void saveAllUserPages() {
        discoverDiskExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    android.content.SharedPreferences.Editor editor = discoverPrefs.edit();
                    java.util.Map<String, ?> all = discoverPrefs.getAll();
                    for (String key : all.keySet()) {
                        if (key.startsWith("discover_page_")) editor.remove(key);
                    }
                    int pageCount = (discoverUsers.size() + DISCOVER_PAGE_SIZE - 1) / DISCOVER_PAGE_SIZE;
                    editor.putInt("discover_page_count", pageCount);
                    editor.apply();
                    for (int page = 0; page < pageCount; page++) {
                        int from = page * DISCOVER_PAGE_SIZE;
                        int to = Math.min(from + DISCOVER_PAGE_SIZE, discoverUsers.size());
                        java.util.List<java.util.HashMap<String, String>> chunk =
                            new java.util.ArrayList<java.util.HashMap<String, String>>(
                                discoverUsers.subList(from, to));
                        savePage(page, chunk);
                    }
                } catch (Exception ignored) {}
            }
        });
    }
}
final DiscoverPageFunctions discoverFns = new DiscoverPageFunctions();

// Restore every previously loaded person individually before Firebase responds.
// This keeps all visited people visible while offline and avoids reloading them.
try {
    java.util.Map<String, ?> cachedEntries = discoverPrefs.getAll();
    for (java.util.Map.Entry<String, ?> entry : cachedEntries.entrySet()) {
        if (!entry.getKey().startsWith("discover_user_") || !(entry.getValue() instanceof String)) continue;
        org.json.JSONObject o = new org.json.JSONObject((String) entry.getValue());
        String uid = o.optString("uid", "");
        if (uid.length() == 0 || myUid.equals(uid) || discoverFns.find(uid) != null) continue;
        discoverUsers.add(discoverFns.user(uid, o.optString("name", ""), o.optString("username", ""), o.optString("photoURL", "")));
    }
} catch (Exception ignored) {}

// Restore the complete local state before Firebase responds. This includes
// your own profile, every loaded user's metadata/DP, and relationship status.
String cachedMyName = discoverPrefs.getString("discover_my_name", null);
String cachedMyUsername = discoverPrefs.getString("discover_my_username", null);
String cachedMyPhoto = discoverPrefs.getString("discover_my_photo", null);
if (cachedMyName != null && cachedMyName.length() > 0) discoverMyName[0] = cachedMyName;
if (cachedMyUsername != null && cachedMyUsername.length() > 0) discoverMyUsername[0] = cachedMyUsername;
if (cachedMyPhoto != null) discoverMyPhoto[0] = cachedMyPhoto;
try {
    String rawRelationships = discoverPrefs.getString("discover_relationships_cache", null);
    if (rawRelationships != null) {
        org.json.JSONObject all = new org.json.JSONObject(rawRelationships);
        java.util.Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            String pair = keys.next(); org.json.JSONObject one = all.getJSONObject(pair);
            java.util.HashMap<String, String> r = new java.util.HashMap<>();
            r.put("status", one.optString("status", "")); r.put("requester", one.optString("requester", "")); r.put("recipient", one.optString("recipient", ""));
            discoverRelationships.put(pair, r);
        }
    }
} catch (Exception ignored) {}

final android.widget.BaseAdapter discoverAdapter = new android.widget.BaseAdapter() {
    java.util.List<java.util.HashMap<String, String>> filtered() {
        String q = discoverSearch.getText().toString().trim().toLowerCase();
        java.util.List<java.util.HashMap<String, String>> out = new java.util.ArrayList<>();
        for (java.util.HashMap<String, String> u : discoverUsers) {
            String name = u.get("name") == null ? "" : u.get("name").toLowerCase();
            String username = u.get("username") == null ? "" : u.get("username").toLowerCase();
            boolean ok = q.length() == 0;
            if (q.startsWith("name:")) ok = name.contains(q.substring(5).trim());
            else if (q.startsWith("username:")) ok = username.contains(q.substring(9).trim().replace("@", ""));
            else if (q.length() > 0) ok = name.contains(q) || username.contains(q.replace("@", ""));
            if (ok) out.add(u);
        }
        java.util.Collections.sort(out, new java.util.Comparator<java.util.HashMap<String, String>>() {
            @Override public int compare(java.util.HashMap<String, String> a, java.util.HashMap<String, String> b) { return a.get("name").compareToIgnoreCase(b.get("name")); }
        });
        return out;
    }
    @Override public int getCount() { return filtered().size() + 1; }
    @Override public Object getItem(int position) { return position == 0 ? null : filtered().get(position - 1); }
    @Override public long getItemId(int position) { return position; }
    android.widget.TextView action(String text, int color, android.graphics.drawable.Drawable bg) {
        android.widget.TextView v = discoverFns.label(text, 11.5f, color, true);
        v.setGravity(android.view.Gravity.CENTER); v.setPadding(utils.dp(10), utils.dp(6), utils.dp(10), utils.dp(6)); v.setBackground(bg); return v;
    }
    @Override public android.view.View getView(final int position, android.view.View convertView, android.view.ViewGroup parent) {
        final boolean self = position == 0;
        final java.util.HashMap<String, String> u;
        if (self) {
            u = discoverFns.user(myUid, discoverMyName[0], discoverMyUsername[0], discoverMyPhoto[0]);
        } else u = (java.util.HashMap<String, String>) getItem(position);

        android.widget.LinearLayout card = new android.widget.LinearLayout(DiscoverActivity.this);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL); card.setPadding(utils.dp(14), utils.dp(12), utils.dp(14), utils.dp(12)); card.setBackground(ui.rounded(android.graphics.Color.WHITE, 16));
        android.widget.ImageView avatar = new android.widget.ImageView(DiscoverActivity.this);
        avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        String rowPhoto = u.get("photoURL"); String rowName = u.get("name");
        if (rowPhoto == null || rowPhoto.trim().length() == 0 || rowPhoto.contains("ui-avatars.com")) avatar.setImageDrawable(new DiscoverDefaultAvatarDrawable(rowName));
        else try { com.bumptech.glide.Glide.with(DiscoverActivity.this).load(rowPhoto).placeholder(new DiscoverDefaultAvatarDrawable(rowName)).error(new DiscoverDefaultAvatarDrawable(rowName)).diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL).circleCrop().into(avatar); } catch (Throwable ignored) { avatar.setImageDrawable(new DiscoverDefaultAvatarDrawable(rowName)); }
        avatar.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { discoverFns.showDp(u.get("photoURL"), u.get("name")); } });
        android.widget.LinearLayout.LayoutParams ap = new android.widget.LinearLayout.LayoutParams(utils.dp(50), utils.dp(50)); ap.rightMargin = utils.dp(12); card.addView(avatar, ap);

        android.widget.LinearLayout info = new android.widget.LinearLayout(DiscoverActivity.this); info.setOrientation(android.widget.LinearLayout.VERTICAL); info.setGravity(android.view.Gravity.CENTER_VERTICAL);
        info.addView(discoverFns.label(self ? u.get("name") + " (You)" : u.get("name"), 14.5f, DISCOVER_TEXT, true));
        info.addView(discoverFns.label(self ? "@" + u.get("username") + " (You)" : "@" + u.get("username"), 12, self ? DISCOVER_BLUE : DISCOVER_MUTED, false));
        card.addView(info, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

        if (self) {
            android.widget.TextView open = action("Open", DISCOVER_BLUE, discoverFns.outline(999));
            open.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { discoverFns.openChat(u, true); } });
            card.addView(open);
            card.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { discoverFns.openChat(u, true); } });
        } else {
            String state = discoverFns.relationState(u.get("uid"));
            android.widget.LinearLayout actions = new android.widget.LinearLayout(DiscoverActivity.this); actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
            if ("not_friends".equals(state)) {
                android.widget.TextView add = action("Send Friend Request", android.graphics.Color.WHITE, discoverFns.blueBg());
                add.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { discoverFns.sendRequest(u.get("uid")); } }); actions.addView(add);
            } else if ("outgoing_pending".equals(state)) {
                actions.addView(action("Request Sent", DISCOVER_AMBER, discoverFns.outline(999)));
                android.widget.TextView cancel = action("Cancel", DISCOVER_RED, discoverFns.redOutline());
                cancel.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { discoverFns.confirmCancel(u.get("uid")); } }); actions.addView(cancel);
            } else if ("incoming_pending".equals(state)) {
                android.widget.TextView accept = action("Accept", android.graphics.Color.WHITE, discoverFns.blueBg());
                accept.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { discoverFns.accept(u.get("uid")); } }); actions.addView(accept);
                android.widget.TextView decline = action("Decline", DISCOVER_RED, discoverFns.redOutline());
                decline.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { discoverFns.cancel(u.get("uid")); } }); actions.addView(decline);
            } else {
                android.widget.TextView open = action("Message", DISCOVER_MUTED, discoverFns.outline(999));
                open.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { discoverFns.openChat(u, false); } }); actions.addView(open);
            }
            card.addView(actions);
        }
        card.setSoundEffectsEnabled(false);
        return card;
    }
};
discoverList.setAdapter(discoverAdapter);

final Runnable discoverLoadNextPage = new Runnable() {
    @Override public void run() {
        if (discoverLoading[0] || discoverFinished[0]) return;
        if (!discoverMessages.online()) {
            discoverProgress.setVisibility(android.view.View.GONE);
            if (discoverUsers.isEmpty()) { discoverEmpty.setText("Please connect to the internet to discover people."); discoverEmpty.setVisibility(android.view.View.VISIBLE); }
            return;
        }
        discoverLoading[0] = true; discoverProgress.setVisibility(android.view.View.VISIBLE);
        com.google.firebase.database.Query query = rootRef.child("users").orderByKey();
        if (discoverLastKey[0] == null) query = query.limitToFirst(DISCOVER_PAGE_SIZE + 2);
        else query = query.startAt(discoverLastKey[0]).limitToFirst(DISCOVER_PAGE_SIZE + 2);
        final int page = discoverPageNumber[0];
        query.addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                java.util.List<java.util.HashMap<String, String>> pageUsers = new java.util.ArrayList<>();
                String newest = discoverLastKey[0];
                for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                    String key = child.getKey(); if (key == null || key.equals(discoverLastKey[0])) continue;
                    newest = key;
                    if (key.equals(myUid)) continue;
                    java.util.HashMap<String, String> u = discoverFns.user(key, child.child("name").getValue(String.class), child.child("username").getValue(String.class), child.child("photoURL").getValue(String.class));
                    pageUsers.add(u); if (pageUsers.size() >= DISCOVER_PAGE_SIZE) break;
                }
                if (pageUsers.isEmpty()) discoverFinished[0] = true;
                else {
                    for (java.util.HashMap<String, String> u : pageUsers) {
                        java.util.HashMap<String, String> oldUser = discoverFns.find(u.get("uid"));
                        if (oldUser == null) discoverUsers.add(u); else { oldUser.clear(); oldUser.putAll(u); }
                    }
                    discoverLastKey[0] = newest; discoverPageNumber[0]++; discoverFns.savePage(page, pageUsers); discoverFns.saveUsers(pageUsers);
                    if (pageUsers.size() < DISCOVER_PAGE_SIZE) discoverFinished[0] = true;
                }
                discoverLoading[0] = false; discoverProgress.setVisibility(android.view.View.GONE); discoverAdapter.notifyDataSetChanged(); discoverEmpty.setVisibility(discoverAdapter.getCount() == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
            }
            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) { discoverLoading[0] = false; discoverProgress.setVisibility(android.view.View.GONE); if (discoverUsers.isEmpty()) { discoverEmpty.setText(discoverMessages.friendly(error)); discoverEmpty.setVisibility(android.view.View.VISIBLE); } else discoverMessages.show(discoverMessages.friendly(error)); }
        });
    }
};

discoverList.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
    @Override public void onScrollStateChanged(android.widget.AbsListView view, int state) {}
    @Override public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
        if (total > 0 && first + visible >= total - 3) discoverLoadNextPage.run();
    }
});
discoverSearch.addTextChangedListener(new android.text.TextWatcher() {
    @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
    @Override public void onTextChanged(CharSequence s, int st, int before, int count) { discoverAdapter.notifyDataSetChanged(); }
    @Override public void afterTextChanged(android.text.Editable e) {}
});

// Load only the cached pages that the user previously reached.
final int discoverCachedCount = discoverPrefs.getInt("discover_page_count", 0);
for (int page = 0; page < discoverCachedCount; page++) {
    try {
        String raw = discoverPrefs.getString("discover_page_" + page, null); if (raw == null) break;
        org.json.JSONArray arr = new org.json.JSONArray(raw);
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.getJSONObject(i);
            java.util.HashMap<String, String> u = discoverFns.user(o.optString("uid"), o.optString("name"), o.optString("username"), o.optString("photoURL"));
            if (!myUid.equals(u.get("uid")) && discoverFns.find(u.get("uid")) == null) discoverUsers.add(u);
        }
    } catch (Exception ignored) {}
}
if (discoverCachedCount > 0) {
    discoverPageNumber[0] = discoverCachedCount;
    if (!discoverUsers.isEmpty()) discoverLastKey[0] = discoverUsers.get(discoverUsers.size() - 1).get("uid");
}

final com.google.firebase.database.ValueEventListener discoverUsersSyncListener =
    new com.google.firebase.database.ValueEventListener() {
        @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
            if (!discoverMessages.online()) return;

            java.util.ArrayList<java.util.HashMap<String, String>> freshUsers =
                new java.util.ArrayList<java.util.HashMap<String, String>>();
            for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                String uid = child.getKey();
                if (uid == null || uid.length() == 0 || myUid.equals(uid)) continue;
                freshUsers.add(discoverFns.user(
                    uid,
                    child.child("name").getValue(String.class),
                    child.child("username").getValue(String.class),
                    child.child("photoURL").getValue(String.class)
                ));
            }

            discoverUsers.clear();
            discoverUsers.addAll(freshUsers);
            discoverLastKey[0] = null;
            discoverPageNumber[0] = 0;
            discoverFinished[0] = true;

            android.content.SharedPreferences.Editor cacheEditor = discoverPrefs.edit();
            java.util.Map<String, ?> cached = discoverPrefs.getAll();
            java.util.HashSet<String> currentUids = new java.util.HashSet<String>();
            for (java.util.HashMap<String, String> user : freshUsers) {
                String uid = user.get("uid");
                if (uid != null) currentUids.add(uid);
                discoverFns.saveUser(user);
            }
            for (String key : cached.keySet()) {
                if (!key.startsWith("discover_user_")) continue;
                String uid = key.substring("discover_user_".length());
                if (!currentUids.contains(uid)) cacheEditor.remove(key);
            }
            cacheEditor.apply();
            discoverFns.saveAllUserPages();

            discoverLoading[0] = false;
            discoverProgress.setVisibility(android.view.View.GONE);
            discoverAdapter.notifyDataSetChanged();
            discoverEmpty.setText("No users found.");
            discoverEmpty.setVisibility(discoverUsers.isEmpty()
                ? android.view.View.VISIBLE : android.view.View.GONE);
        }

        @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {
            discoverLoading[0] = false;
            discoverProgress.setVisibility(android.view.View.GONE);
            if (discoverUsers.isEmpty()) {
                discoverEmpty.setText(discoverMessages.friendly(error));
                discoverEmpty.setVisibility(android.view.View.VISIBLE);
            } else {
                discoverMessages.show(discoverMessages.friendly(error));
            }
        }
    };

rootRef.child("users").addValueEventListener(discoverUsersSyncListener);

rootRef.child("relationships").addValueEventListener(new com.google.firebase.database.ValueEventListener() {
    @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
        discoverRelationships.clear();
        for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
            String pair = child.getKey(); if (pair == null || !pair.contains(myUid)) continue;
            java.util.HashMap<String, String> r = new java.util.HashMap<>();
            r.put("status", child.child("status").getValue(String.class));
            r.put("requester", child.child("requester").getValue(String.class));
            r.put("recipient", child.child("recipient").getValue(String.class));
            discoverRelationships.put(pair, r);
        }
        discoverFns.saveRelationships();
        discoverAdapter.notifyDataSetChanged();
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) { discoverMessages.show(discoverMessages.friendly(error)); }
});

// Always use the profile stored in users/{myUid}, including the real name,
// username, and DP. Firebase local persistence also makes this available offline.
rootRef.child("users").child(myUid).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
    @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
        String name = snap.child("name").getValue(String.class);
        String username = snap.child("username").getValue(String.class);
        String photo = snap.child("photoURL").getValue(String.class);
        if (name != null && name.length() > 0) discoverMyName[0] = name;
        if (username != null && username.length() > 0) discoverMyUsername[0] = username;
        if (photo != null) discoverMyPhoto[0] = photo;
        discoverFns.saveMyProfile();
        discoverAdapter.notifyDataSetChanged();
    }
    @Override public void onCancelled(com.google.firebase.database.DatabaseError error) { discoverMessages.show(discoverMessages.friendly(error)); }
});

discoverPageOverlay.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) {} });
setContentView(discoverPageOverlay);
discoverAdapter.notifyDataSetChanged();
// Cache-first rendering has already happened above. When online, the users
// listener performs a full server reconciliation; offline data stays visible.
if (discoverUsers.isEmpty() && !discoverMessages.online()) {
    discoverEmpty.setText("Please connect to the internet to discover people.");
    discoverEmpty.setVisibility(android.view.View.VISIBLE);
} else if (discoverMessages.online()) {
    discoverProgress.setVisibility(android.view.View.VISIBLE);
}

// If your existing plus button is named plusButton, add this listener to it:
// plusButton.setOnClickListener(new android.view.View.OnClickListener() {
//     @Override public void onClick(android.view.View v) {
//         discoverPageOverlay.setVisibility(android.view.View.VISIBLE);
//     }
// });
