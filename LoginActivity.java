// ===== onCreate code =====




// ============================================================
// MIXI CHAT — LoginActivity (Remember Me removed — session persists
// until explicit Logout, matching WhatsApp/Telegram-style behavior.
// Friendly error messages replace raw Firebase exception text.)
// Paste into: Login screen -> onCreate -> Add Source Directly
// ============================================================

final com.google.firebase.auth.FirebaseAuth firebaseAuth = com.google.firebase.auth.FirebaseAuth.getInstance();
com.google.firebase.auth.FirebaseUser existingUser = firebaseAuth.getCurrentUser();

if (existingUser != null) {
    android.content.Intent skipIntent = new android.content.Intent(LoginActivity.this, MainActivity.class);
    startActivity(skipIntent);
    finish();
    return;
}

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

// ---------- Color Palette Matching the Design ----------
final int COLOR_BLUE = android.graphics.Color.parseColor("#0076FF");
final int COLOR_BG = android.graphics.Color.parseColor("#F8FAFC");
final int COLOR_INPUT_BG = android.graphics.Color.parseColor("#FAFCFF");
final int COLOR_INPUT_BORDER = android.graphics.Color.parseColor("#E2E8F0");
final int COLOR_TEXT = android.graphics.Color.parseColor("#0F172A");
final int COLOR_LABEL = android.graphics.Color.parseColor("#475569");
final int COLOR_MUTED = android.graphics.Color.parseColor("#64748B");

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
}
final UiKit ui = new UiKit();

// ---------- Root Frame & Scroll Container ----------
android.widget.FrameLayout root = new android.widget.FrameLayout(LoginActivity.this);
root.setBackgroundColor(COLOR_BG);

android.widget.ScrollView scroll = new android.widget.ScrollView(LoginActivity.this);
scroll.setFillViewport(true);
android.widget.FrameLayout.LayoutParams scrollParams = new android.widget.FrameLayout.LayoutParams(-1, -1);
scroll.setLayoutParams(scrollParams);

android.widget.FrameLayout centerFrame = new android.widget.FrameLayout(LoginActivity.this);
centerFrame.setLayoutParams(new android.widget.ScrollView.LayoutParams(-1, -2));
centerFrame.setPadding(utils.dp(24), utils.dp(40), utils.dp(24), utils.dp(40));

// ---------- Centered Card View (Clean Flat Style) ----------
android.widget.LinearLayout card = new android.widget.LinearLayout(LoginActivity.this);
card.setOrientation(android.widget.LinearLayout.VERTICAL);
card.setPadding(utils.dp(24), utils.dp(32), utils.dp(24), utils.dp(32));
card.setBackground(ui.cardBackground(android.graphics.Color.WHITE, COLOR_INPUT_BORDER, 24));
card.setElevation(0f); // Shadows removed for clean border-only design

android.widget.FrameLayout.LayoutParams cardParams = new android.widget.FrameLayout.LayoutParams(-1, -2);
cardParams.gravity = android.view.Gravity.CENTER;
card.setLayoutParams(cardParams);

// ---------- Local App Logo ----------
android.widget.ImageView logoView = new android.widget.ImageView(LoginActivity.this);
android.widget.LinearLayout.LayoutParams logoParams = new android.widget.LinearLayout.LayoutParams(utils.dp(72), utils.dp(72));
logoParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
logoParams.bottomMargin = utils.dp(16);
logoView.setLayoutParams(logoParams);
logoView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);

try {
    int resId = getResources().getIdentifier("favicon", "drawable", getPackageName());
    if (resId != 0) {
        logoView.setImageResource(resId);
    } else {
        logoView.setImageResource(android.R.drawable.ic_menu_gallery);
    }
} catch (Throwable t) {
    logoView.setImageResource(android.R.drawable.ic_menu_gallery);
}

// ---------- Title & Subtitle ----------
android.widget.TextView title = new android.widget.TextView(LoginActivity.this);
title.setTypeface(emojiTypeface);
title.setText("Mixi Chat");
title.setTextSize(22);
title.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
title.setTextColor(COLOR_TEXT);
title.setGravity(android.view.Gravity.CENTER);

android.widget.TextView subtitle = new android.widget.TextView(LoginActivity.this);
subtitle.setTypeface(emojiTypeface);
subtitle.setText("Real-time messaging & audio calling");
subtitle.setTextSize(13);
subtitle.setTextColor(COLOR_MUTED);
subtitle.setGravity(android.view.Gravity.CENTER);
subtitle.setPadding(0, utils.dp(4), 0, utils.dp(24));

// ---------- Field 1: Email Address ----------
android.widget.TextView emailLabel = new android.widget.TextView(LoginActivity.this);
emailLabel.setTypeface(emojiTypeface);
emailLabel.setText("Email Address");
emailLabel.setTextSize(12.5f);
emailLabel.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
emailLabel.setTextColor(COLOR_LABEL);
android.widget.LinearLayout.LayoutParams labelParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
labelParams.bottomMargin = utils.dp(6);

final android.widget.EditText email = new android.widget.EditText(LoginActivity.this);
email.setTypeface(emojiTypeface);
email.setHint("you@mixi.com");
email.setHintTextColor(COLOR_MUTED);
email.setBackground(ui.inputField(COLOR_INPUT_BG, COLOR_INPUT_BORDER, 14));
email.setPadding(utils.dp(16), utils.dp(14), utils.dp(16), utils.dp(14));
email.setTextSize(14f);
email.setTextColor(COLOR_TEXT);
email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
android.widget.LinearLayout.LayoutParams fieldParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
fieldParams.bottomMargin = utils.dp(16);

// ---------- Field 2: Password ----------
android.widget.TextView passwordLabel = new android.widget.TextView(LoginActivity.this);
passwordLabel.setTypeface(emojiTypeface);
passwordLabel.setText("Password");
passwordLabel.setTextSize(12.5f);
passwordLabel.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
passwordLabel.setTextColor(COLOR_LABEL);

final android.widget.EditText password = new android.widget.EditText(LoginActivity.this);
password.setTypeface(emojiTypeface);
password.setHint("••••••••");
password.setHintTextColor(COLOR_MUTED);
password.setBackground(ui.inputField(COLOR_INPUT_BG, COLOR_INPUT_BORDER, 14));
password.setPadding(utils.dp(16), utils.dp(14), utils.dp(16), utils.dp(14));
password.setTextSize(14f);
password.setTextColor(COLOR_TEXT);
password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
android.widget.LinearLayout.LayoutParams passwordParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
passwordParams.bottomMargin = utils.dp(24);

// ---------- Log In Button ----------
final android.widget.Button loginButton = new android.widget.Button(LoginActivity.this);
loginButton.setTypeface(emojiTypeface);
loginButton.setText("Log In");
loginButton.setAllCaps(false);
loginButton.setTextSize(15f);
loginButton.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
loginButton.setTextColor(android.graphics.Color.WHITE);
loginButton.setBackground(ui.rounded(COLOR_BLUE, 25));
loginButton.setPadding(0, utils.dp(14), 0, utils.dp(14));
loginButton.setElevation(0f);

// ---------- Sign Up Link Row ----------
android.widget.LinearLayout signupRow = new android.widget.LinearLayout(LoginActivity.this);
signupRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
signupRow.setGravity(android.view.Gravity.CENTER);
signupRow.setPadding(0, utils.dp(20), 0, 0);

android.widget.TextView signupPrompt = new android.widget.TextView(LoginActivity.this);
signupPrompt.setTypeface(emojiTypeface);
signupPrompt.setText("Don't have an account? ");
signupPrompt.setTextSize(13.5f);
signupPrompt.setTextColor(COLOR_MUTED);

final android.widget.TextView signupLink = new android.widget.TextView(LoginActivity.this);
signupLink.setTypeface(emojiTypeface);
signupLink.setText("Sign Up");
signupLink.setTextSize(13.5f);
signupLink.setTypeface(emojiTypeface, android.graphics.Typeface.BOLD);
signupLink.setTextColor(COLOR_BLUE);

signupRow.addView(signupPrompt);
signupRow.addView(signupLink);

// ---------- View Hierarchy Assembly ----------
card.addView(logoView);
card.addView(title, new android.widget.LinearLayout.LayoutParams(-1, -2));
card.addView(subtitle, new android.widget.LinearLayout.LayoutParams(-1, -2));

card.addView(emailLabel, labelParams);
card.addView(email, fieldParams);

card.addView(passwordLabel, labelParams);
card.addView(password, passwordParams);

card.addView(loginButton, new android.widget.LinearLayout.LayoutParams(-1, -2));
card.addView(signupRow, new android.widget.LinearLayout.LayoutParams(-1, -2));

centerFrame.addView(card);
scroll.addView(centerFrame);
root.addView(scroll);

setContentView(root);

// ---------- Friendly Error Mapper ----------
// Converts raw Firebase exceptions (e.g. "[INVALID_LOGIN_CREDENTIALS]")
// into plain, user-facing text instead of leaking backend error codes.
class LoginErrorMapper {
    String friendlyMessage(Exception e) {
        if (e == null) return "Login failed. Please try again.";

        if (e instanceof com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
            return "Incorrect email or password. Please check your details and try again.";
        }
        if (e instanceof com.google.firebase.auth.FirebaseAuthInvalidUserException) {
            return "We couldn't find an account with that email.";
        }
        if (e instanceof com.google.firebase.FirebaseNetworkException) {
            return "No internet connection. Please check your network and try again.";
        }
        if (e instanceof com.google.firebase.FirebaseTooManyRequestsException) {
            return "Too many attempts. Please wait a moment and try again.";
        }

        // Fallback: inspect the raw message for known Firebase error codes,
        // since some auth failures surface as a generic FirebaseAuthException
        // wrapping an internal code like INVALID_LOGIN_CREDENTIALS.
        String raw = e.getMessage() == null ? "" : e.getMessage().toUpperCase(java.util.Locale.US);
        if (raw.contains("INVALID_LOGIN_CREDENTIALS") || raw.contains("WRONG_PASSWORD")
                || raw.contains("INVALID_EMAIL") || raw.contains("USER_NOT_FOUND")
                || raw.contains("INVALID_CREDENTIAL")) {
            return "Incorrect email or password. Please check your details and try again.";
        }
        if (raw.contains("USER_DISABLED")) {
            return "This account has been disabled. Please contact support.";
        }
        if (raw.contains("NETWORK")) {
            return "No internet connection. Please check your network and try again.";
        }

        return "Login failed. Please check your details and try again.";
    }
}
final LoginErrorMapper errorMapper = new LoginErrorMapper();

// ---------- Authentication Logic ----------

loginButton.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View view) {
        String userEmail = email.getText().toString().trim();
        String userPassword = password.getText().toString();

        if (userEmail.isEmpty()) {
            email.setError("Enter your email");
            return;
        }
        if (userPassword.isEmpty()) {
            password.setError("Enter your password");
            return;
        }

        loginButton.setEnabled(false);
        loginButton.setText("Signing in...");

        firebaseAuth.signInWithEmailAndPassword(userEmail, userPassword)
                .addOnCompleteListener(LoginActivity.this, new com.google.android.gms.tasks.OnCompleteListener() {
                    @Override
                    public void onComplete(com.google.android.gms.tasks.Task task) {
                        if (task.isSuccessful()) {
                            if (firebaseAuth.getCurrentUser() != null) {
                                String uid = firebaseAuth.getCurrentUser().getUid();
                                com.google.firebase.database.DatabaseReference statusRef =
                                        com.google.firebase.database.FirebaseDatabase.getInstance()
                                                .getReference("users").child(uid).child("status");

                                java.util.Map<String, Object> statusUpdate = new java.util.HashMap<>();
                                statusUpdate.put("state", "online");
                                statusUpdate.put("lastChanged", System.currentTimeMillis());
                                statusRef.setValue(statusUpdate);
                            }

                            android.content.Intent intent = new android.content.Intent(LoginActivity.this, MainActivity.class);
                            startActivity(intent);
                            finish();
                        } else {
                            loginButton.setEnabled(true);
                            loginButton.setText("Log In");

                            String friendlyMessage = errorMapper.friendlyMessage(task.getException());
                            android.widget.Toast.makeText(LoginActivity.this, friendlyMessage, android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }
});

signupLink.setOnClickListener(new android.view.View.OnClickListener() {
    @Override
    public void onClick(android.view.View view) {
        android.content.Intent intent = new android.content.Intent(LoginActivity.this, RegisterActivity.class);
        startActivity(intent);
    }
});