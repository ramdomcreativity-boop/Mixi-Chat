// ===== onCreate code =====





// ============================================================
// MIXI CHAT — RegisterActivity
// FIXES APPLIED (this revision):
// - Username claiming is now atomic via Firebase runTransaction(),
//   closing the race where two users both see "available" and one
//   silently overwrites the other's claim.
// - Retains: orphaned-account cleanup, friendly error messages,
//   email validation, ui-avatars.com default photo.
// Paste into: Register screen -> onCreate -> Add Source Directly
// ============================================================

final com.google.firebase.auth.FirebaseAuth firebaseAuth = com.google.firebase.auth.FirebaseAuth.getInstance();
final com.google.firebase.database.DatabaseReference rootRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference();

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
final int COLOR_BG = android.graphics.Color.parseColor("#F8FAFC");
final int COLOR_INPUT_BG = android.graphics.Color.parseColor("#FAFCFF");
final int COLOR_INPUT_BORDER = android.graphics.Color.parseColor("#E2E8F0");
final int COLOR_TEXT = android.graphics.Color.parseColor("#0F172A");
final int COLOR_LABEL = android.graphics.Color.parseColor("#475569");
final int COLOR_MUTED = android.graphics.Color.parseColor("#64748B");
final int COLOR_GREEN = android.graphics.Color.parseColor("#10B981");
final int COLOR_RED = android.graphics.Color.parseColor("#EF4444");
final int COLOR_AMBER = android.graphics.Color.parseColor("#F59E0B");

class UiKit {
    android.graphics.drawable.GradientDrawable rounded(int colorInt, float radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(colorInt);
        d.setCornerRadius(radiusDp * density);
        return d;
    }
    android.graphics.drawable.GradientDrawable cardBackground(int bgColor, int borderColor, float radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(radiusDp * density);
        d.setStroke(utils.dp(1), borderColor);
        return d;
    }
    android.graphics.drawable.GradientDrawable inputField(int bgColor, int borderColor, float radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(radiusDp * density);
        d.setStroke(utils.dp(1), borderColor);
        return d;
    }
    android.widget.TextView label(String text, int color) {
        android.widget.TextView tv = new android.widget.TextView(RegisterActivity.this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
        tv.setTextColor(color);
        return tv;
    }
}
final UiKit ui = new UiKit();

android.widget.FrameLayout root = new android.widget.FrameLayout(RegisterActivity.this);
root.setBackgroundColor(COLOR_BG);

android.widget.ScrollView scroll = new android.widget.ScrollView(RegisterActivity.this);
scroll.setFillViewport(true);
scroll.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));

android.widget.FrameLayout centerFrame = new android.widget.FrameLayout(RegisterActivity.this);
centerFrame.setLayoutParams(new android.widget.ScrollView.LayoutParams(-1, -2));
centerFrame.setPadding(utils.dp(24), utils.dp(40), utils.dp(24), utils.dp(40));

android.widget.LinearLayout card = new android.widget.LinearLayout(RegisterActivity.this);
card.setOrientation(android.widget.LinearLayout.VERTICAL);
card.setPadding(utils.dp(24), utils.dp(32), utils.dp(24), utils.dp(32));
card.setBackground(ui.cardBackground(android.graphics.Color.WHITE, COLOR_INPUT_BORDER, 24));
card.setElevation(0f);

android.widget.FrameLayout.LayoutParams cardParams = new android.widget.FrameLayout.LayoutParams(-1, -2);
cardParams.gravity = android.view.Gravity.CENTER;
card.setLayoutParams(cardParams);

android.widget.ImageView logoView = new android.widget.ImageView(RegisterActivity.this);
android.widget.LinearLayout.LayoutParams logoParams = new android.widget.LinearLayout.LayoutParams(utils.dp(72), utils.dp(72));
logoParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
logoParams.bottomMargin = utils.dp(16);
logoView.setLayoutParams(logoParams);
logoView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
try {
    int resId = getResources().getIdentifier("favicon", "drawable", getPackageName());
    if (resId != 0) logoView.setImageResource(resId);
    else logoView.setImageResource(android.R.drawable.ic_menu_gallery);
} catch (Throwable t) {
    logoView.setImageResource(android.R.drawable.ic_menu_gallery);
}

android.widget.TextView title = new android.widget.TextView(RegisterActivity.this);
title.setTypeface(emojiTypeface);
title.setText("Mixi Chat");
title.setTextSize(22);
title.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
title.setTextColor(COLOR_TEXT);
title.setGravity(android.view.Gravity.CENTER);

android.widget.TextView subtitle = new android.widget.TextView(RegisterActivity.this);
subtitle.setTypeface(emojiTypeface);
subtitle.setText("Create your new Mixi account");
subtitle.setTextSize(13);
subtitle.setTextColor(COLOR_MUTED);
subtitle.setGravity(android.view.Gravity.CENTER);
subtitle.setPadding(0, utils.dp(4), 0, utils.dp(24));

android.widget.LinearLayout.LayoutParams labelParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
labelParams.bottomMargin = utils.dp(6);
android.widget.LinearLayout.LayoutParams fieldParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
fieldParams.bottomMargin = utils.dp(16);

android.widget.TextView nameLabel = ui.label("Full Name", COLOR_LABEL);
final android.widget.EditText nameField = new android.widget.EditText(RegisterActivity.this);
nameField.setTypeface(emojiTypeface);
nameField.setHint("Fahad Daud");
nameField.setHintTextColor(COLOR_MUTED);
nameField.setBackground(ui.inputField(COLOR_INPUT_BG, COLOR_INPUT_BORDER, 14));
nameField.setPadding(utils.dp(16), utils.dp(14), utils.dp(16), utils.dp(14));
nameField.setTextSize(14f);
nameField.setTextColor(COLOR_TEXT);

android.widget.TextView usernameLabel = ui.label("Username", COLOR_LABEL);
final android.widget.EditText usernameField = new android.widget.EditText(RegisterActivity.this);
usernameField.setTypeface(emojiTypeface);
usernameField.setHint("fahad_daud");
usernameField.setHintTextColor(COLOR_MUTED);
usernameField.setBackground(ui.inputField(COLOR_INPUT_BG, COLOR_INPUT_BORDER, 14));
usernameField.setPadding(utils.dp(16), utils.dp(14), utils.dp(16), utils.dp(14));
usernameField.setTextSize(14f);
usernameField.setTextColor(COLOR_TEXT);

final android.widget.TextView usernameStatus = new android.widget.TextView(RegisterActivity.this);
usernameStatus.setTypeface(emojiTypeface);
usernameStatus.setTextSize(12f);
usernameStatus.setPadding(utils.dp(4), utils.dp(4), 0, utils.dp(12));

android.widget.TextView emailLabel = ui.label("Email Address", COLOR_LABEL);
final android.widget.EditText emailField = new android.widget.EditText(RegisterActivity.this);
emailField.setTypeface(emojiTypeface);
emailField.setHint("you@mixi.com");
emailField.setHintTextColor(COLOR_MUTED);
emailField.setBackground(ui.inputField(COLOR_INPUT_BG, COLOR_INPUT_BORDER, 14));
emailField.setPadding(utils.dp(16), utils.dp(14), utils.dp(16), utils.dp(14));
emailField.setTextSize(14f);
emailField.setTextColor(COLOR_TEXT);
emailField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

android.widget.TextView passwordLabel = ui.label("Password", COLOR_LABEL);
final android.widget.EditText passwordField = new android.widget.EditText(RegisterActivity.this);
passwordField.setTypeface(emojiTypeface);
passwordField.setHint("At least 6 characters");
passwordField.setHintTextColor(COLOR_MUTED);
passwordField.setBackground(ui.inputField(COLOR_INPUT_BG, COLOR_INPUT_BORDER, 14));
passwordField.setPadding(utils.dp(16), utils.dp(14), utils.dp(16), utils.dp(14));
passwordField.setTextSize(14f);
passwordField.setTextColor(COLOR_TEXT);
passwordField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

android.widget.TextView confirmLabel = ui.label("Confirm Password", COLOR_LABEL);
final android.widget.EditText confirmPasswordField = new android.widget.EditText(RegisterActivity.this);
confirmPasswordField.setTypeface(emojiTypeface);
confirmPasswordField.setHint("Re-enter password");
confirmPasswordField.setHintTextColor(COLOR_MUTED);
confirmPasswordField.setBackground(ui.inputField(COLOR_INPUT_BG, COLOR_INPUT_BORDER, 14));
confirmPasswordField.setPadding(utils.dp(16), utils.dp(14), utils.dp(16), utils.dp(14));
confirmPasswordField.setTextSize(14f);
confirmPasswordField.setTextColor(COLOR_TEXT);
confirmPasswordField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

final android.widget.Button registerButton = new android.widget.Button(RegisterActivity.this);
registerButton.setTypeface(emojiTypeface);
registerButton.setText("Create Account");
registerButton.setAllCaps(false);
registerButton.setTextSize(15f);
registerButton.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
registerButton.setTextColor(android.graphics.Color.WHITE);
registerButton.setBackground(ui.rounded(COLOR_BLUE, 25));
registerButton.setPadding(0, utils.dp(14), 0, utils.dp(14));
registerButton.setElevation(0f);
registerButton.setEnabled(false);
registerButton.setAlpha(0.6f);

android.widget.LinearLayout loginRow = new android.widget.LinearLayout(RegisterActivity.this);
loginRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
loginRow.setGravity(android.view.Gravity.CENTER);
loginRow.setPadding(0, utils.dp(20), 0, 0);

android.widget.TextView loginPrompt = new android.widget.TextView(RegisterActivity.this);
loginPrompt.setTypeface(emojiTypeface);
loginPrompt.setText("Already have an account? ");
loginPrompt.setTextSize(13.5f);
loginPrompt.setTextColor(COLOR_MUTED);

final android.widget.TextView loginLink = new android.widget.TextView(RegisterActivity.this);
loginLink.setTypeface(emojiTypeface);
loginLink.setText("Log In");
loginLink.setTextSize(13.5f);
loginLink.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
loginLink.setTextColor(COLOR_BLUE);

loginRow.addView(loginPrompt);
loginRow.addView(loginLink);

card.addView(logoView);
card.addView(title, new android.widget.LinearLayout.LayoutParams(-1, -2));
card.addView(subtitle, new android.widget.LinearLayout.LayoutParams(-1, -2));
card.addView(nameLabel, labelParams);
card.addView(nameField, fieldParams);
card.addView(usernameLabel, labelParams);
card.addView(usernameField, fieldParams);
card.addView(usernameStatus, new android.widget.LinearLayout.LayoutParams(-1, -2));
card.addView(emailLabel, labelParams);
card.addView(emailField, fieldParams);
card.addView(passwordLabel, labelParams);
card.addView(passwordField, fieldParams);
card.addView(confirmLabel, labelParams);
card.addView(confirmPasswordField, fieldParams);

android.widget.LinearLayout.LayoutParams buttonParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
buttonParams.topMargin = utils.dp(8);
card.addView(registerButton, buttonParams);
card.addView(loginRow, new android.widget.LinearLayout.LayoutParams(-1, -2));

centerFrame.addView(card);
scroll.addView(centerFrame);
root.addView(scroll);
setContentView(root);

class RegisterErrorMapper {
    String friendlyMessage(Exception e) {
        if (e == null) return "Registration failed. Please try again.";
        if (e instanceof com.google.firebase.auth.FirebaseAuthUserCollisionException) {
            return "An account with this email already exists. Try logging in instead.";
        }
        if (e instanceof com.google.firebase.auth.FirebaseAuthWeakPasswordException) {
            return "Password is too weak. Please choose a stronger password.";
        }
        if (e instanceof com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
            return "That email address doesn't look valid. Please check and try again.";
        }
        if (e instanceof com.google.firebase.FirebaseNetworkException) {
            return "No internet connection. Please check your network and try again.";
        }
        String raw = e.getMessage() == null ? "" : e.getMessage().toUpperCase(java.util.Locale.US);
        if (raw.contains("EMAIL_ALREADY_IN_USE") || raw.contains("ALREADY_EXISTS")) {
            return "An account with this email already exists. Try logging in instead.";
        }
        if (raw.contains("INVALID_EMAIL") || raw.contains("BADLY_FORMATTED")) {
            return "That email address doesn't look valid. Please check and try again.";
        }
        if (raw.contains("WEAK_PASSWORD")) {
            return "Password is too weak. Please choose a stronger password.";
        }
        if (raw.contains("NETWORK")) {
            return "No internet connection. Please check your network and try again.";
        }
        return "Registration failed. Please try again.";
    }
}
final RegisterErrorMapper errorMapper = new RegisterErrorMapper();

final android.os.Handler debounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
final Runnable[] pendingCheck = new Runnable[1];
final java.util.regex.Pattern usernamePattern = java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{3,25}$");
final java.util.regex.Pattern emailPattern = android.util.Patterns.EMAIL_ADDRESS;

usernameField.addTextChangedListener(new android.text.TextWatcher() {
    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

    @Override
    public void afterTextChanged(final android.text.Editable editable) {
        if (pendingCheck[0] != null) debounceHandler.removeCallbacks(pendingCheck[0]);
        final String raw = editable.toString().trim().toLowerCase();

        if (raw.isEmpty()) {
            usernameStatus.setText("");
            registerButton.setEnabled(false);
            registerButton.setAlpha(0.6f);
            return;
        }
        if (!usernamePattern.matcher(raw).matches()) {
            usernameStatus.setText("Letters, numbers, \"_\" or \"-\" (3-25 chars)");
            usernameStatus.setTextColor(COLOR_RED);
            registerButton.setEnabled(false);
            registerButton.setAlpha(0.6f);
            return;
        }

        usernameStatus.setText("Checking...");
        usernameStatus.setTextColor(COLOR_AMBER);
        registerButton.setEnabled(false);
        registerButton.setAlpha(0.6f);

        pendingCheck[0] = new Runnable() {
            @Override
            public void run() {
                rootRef.child("usernames").child(raw).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                        boolean isAvailable;
                        if (!snapshot.exists()) {
                            isAvailable = true;
                        } else {
                            Long reservedUntil = snapshot.child("reservedUntil").getValue(Long.class);
                            isAvailable = (reservedUntil != null && reservedUntil < System.currentTimeMillis());
                        }
                        if (isAvailable) {
                            usernameStatus.setText("Username available");
                            usernameStatus.setTextColor(COLOR_GREEN);
                            registerButton.setEnabled(true);
                            registerButton.setAlpha(1f);
                        } else {
                            usernameStatus.setText("Username already taken");
                            usernameStatus.setTextColor(COLOR_RED);
                            registerButton.setEnabled(false);
                            registerButton.setAlpha(0.6f);
                        }
                    }
                    @Override
                    public void onCancelled(com.google.firebase.database.DatabaseError error) {
                        usernameStatus.setText("");
                    }
                });
            }
        };
        debounceHandler.postDelayed(pendingCheck[0], 350);
    }
});

// ---------- Register action ----------

registerButton.setOnClickListener(new android.view.View.OnClickListener() {

    void resetButton() {
        registerButton.setEnabled(true);
        registerButton.setAlpha(1f);
        registerButton.setText("Create Account");
    }

    // Atomically claims usernames/{username} for uid. Firebase guarantees only
    // one of two racing clients can win this — the loser's transaction function
    // re-runs against the winner's already-committed data and correctly aborts.
    void claimUsernameAtomically(final String uid, final String username, final String name, final String emailValue,
                                  final com.google.firebase.auth.FirebaseUser newUser) {
        rootRef.child("usernames").child(username).runTransaction(new com.google.firebase.database.Transaction.Handler() {
            @Override
            public com.google.firebase.database.Transaction.Result doTransaction(com.google.firebase.database.MutableData currentData) {
                Object existing = currentData.getValue();
                boolean available;
                if (existing == null) {
                    available = true;
                } else {
                    Object reservedObj = ((java.util.Map) existing).get("reservedUntil");
                    long reservedUntil = reservedObj == null ? 0L : ((Number) reservedObj).longValue();
                    available = reservedUntil > 0 && reservedUntil < System.currentTimeMillis();
                }
                if (!available) {
                    return com.google.firebase.database.Transaction.abort();
                }
                java.util.Map<String, Object> claim = new java.util.HashMap<>();
                claim.put("uid", uid);
                claim.put("active", true);
                currentData.setValue(claim);
                return com.google.firebase.database.Transaction.success(currentData);
            }

            @Override
            public void onComplete(com.google.firebase.database.DatabaseError error, boolean committed, com.google.firebase.database.DataSnapshot currentSnap) {
                if (error != null) {
                    newUser.delete();
                    resetButton();
                    android.widget.Toast.makeText(RegisterActivity.this, "Registration failed. Please try again.", android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                if (!committed) {
                    // Lost the race — someone else claimed it a moment ago.
                    newUser.delete();
                    resetButton();
                    usernameStatus.setText("Username already taken");
                    usernameStatus.setTextColor(COLOR_RED);
                    android.widget.Toast.makeText(RegisterActivity.this, "That username was just taken. Please choose another and try again.", android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                // Username claim won — now write the rest of the profile.
                writeProfile(uid, username, name, emailValue, newUser);
            }
        });
    }

    void writeProfile(final String uid, final String username, final String name, final String emailValue,
                       final com.google.firebase.auth.FirebaseUser newUser) {
        String photoUrl = "https://ui-avatars.com/api/?name=" + android.net.Uri.encode(name) + "&background=0076FF&color=fff&size=150";

        java.util.Map<String, Object> userMap = new java.util.HashMap<>();
        userMap.put("uid", uid);
        userMap.put("name", name);
        userMap.put("username", username);
        userMap.put("email", emailValue);
        userMap.put("photoURL", photoUrl);
        userMap.put("createdAt", System.currentTimeMillis());
        userMap.put("usernameChangeCount7d", 0);

        java.util.Map<String, Object> statusMap = new java.util.HashMap<>();
        statusMap.put("state", "online");
        statusMap.put("lastChanged", System.currentTimeMillis());
        userMap.put("status", statusMap);

        java.util.Map<String, Object> privacyMap = new java.util.HashMap<>();
        privacyMap.put("showOnline", true);
        privacyMap.put("showTyping", true);
        privacyMap.put("readReceipts", true);
        userMap.put("privacy", privacyMap);

        rootRef.child("users").child(uid).setValue(userMap).addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener() {
            @Override
            public void onComplete(com.google.android.gms.tasks.Task writeTask) {
                if (writeTask.isSuccessful()) {
                    android.content.Intent intent = new android.content.Intent(RegisterActivity.this, MainActivity.class);
                    startActivity(intent);
                    finish();
                } else {
                    // Profile write failed after we already won the username claim —
                    // release the claim and delete the orphaned Auth account.
                    rootRef.child("usernames").child(username).removeValue();
                    newUser.delete().addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener() {
                        @Override
                        public void onComplete(com.google.android.gms.tasks.Task deleteTask) {
                            resetButton();
                            android.widget.Toast.makeText(RegisterActivity.this, "Couldn't finish setting up your account. Please check your connection and try again.", android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onClick(android.view.View view) {
        final String name = nameField.getText().toString().trim();
        final String username = usernameField.getText().toString().trim().toLowerCase();
        final String emailValue = emailField.getText().toString().trim();
        final String password = passwordField.getText().toString();
        final String confirmPassword = confirmPasswordField.getText().toString();

        if (name.isEmpty()) { nameField.setError("Enter your full name"); return; }
        if (emailValue.isEmpty()) { emailField.setError("Enter your email"); return; }
        if (!emailPattern.matcher(emailValue).matches()) { emailField.setError("Enter a valid email address"); return; }
        if (password.length() < 6) { passwordField.setError("Password must be at least 6 characters"); return; }
        if (!password.equals(confirmPassword)) { confirmPasswordField.setError("Passwords do not match"); return; }

        registerButton.setEnabled(false);
        registerButton.setText("Creating Account...");

        firebaseAuth.createUserWithEmailAndPassword(emailValue, password)
                .addOnCompleteListener(RegisterActivity.this, new com.google.android.gms.tasks.OnCompleteListener() {
                    @Override
                    public void onComplete(com.google.android.gms.tasks.Task task) {
                        if (task.isSuccessful()) {
                            final com.google.firebase.auth.FirebaseUser newUser = firebaseAuth.getCurrentUser();
                            if (newUser == null) {
                                resetButton();
                                android.widget.Toast.makeText(RegisterActivity.this, "Registration failed. Please try again.", android.widget.Toast.LENGTH_LONG).show();
                                return;
                            }
                            claimUsernameAtomically(newUser.getUid(), username, name, emailValue, newUser);
                        } else {
                            resetButton();
                            String friendlyMessage = errorMapper.friendlyMessage(task.getException());
                            android.widget.Toast.makeText(RegisterActivity.this, friendlyMessage, android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }
});

loginLink.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View view) {
        finish();
    }
});