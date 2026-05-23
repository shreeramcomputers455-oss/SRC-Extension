package com.shreeramcomputers45.srcsmstracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.media.RingtoneManager;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import android.widget.Button;
import android.widget.TextView;
import android.content.ClipboardManager;
import android.content.ClipData;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.common.PropertyTypeConstants;
import com.google.appinventor.components.runtime.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@DesignerComponent(
    version = 7,
    description = "Next-Gen Secure SMS Tracker & Offline Message Manager v7.0 - 100% Offline AES-256 GCM Encryption, Mandatory Google Account Binding, Advanced Background Service, Full-Screen Notifications, Automatic Incremental Sync, Zero Internet, Zero Cloud Leakage.",
    category = ComponentCategory.EXTENSION,
    nonVisible = true,
    iconName = "https://shreeramcomputers.com/image/logo.webp",
    helpUrl = "https://www.shreeramcomputers.com/help/extensions/srcsmstracker.html"
)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.RECEIVE_SMS,android.permission.READ_SMS,android.permission.SEND_SMS,android.permission.GET_ACCOUNTS,android.permission.POST_NOTIFICATIONS,android.permission.READ_EXTERNAL_STORAGE,android.permission.WRITE_EXTERNAL_STORAGE,android.permission.VIBRATE,android.permission.ACCESS_NETWORK_STATE")
public class SRCSMSTracker extends AndroidNonvisibleComponent implements OnDestroyListener, TextToSpeech.OnInitListener {

    private final Context context;
    private final Activity activity;
    private final Form form;
    private DatabaseHelper dbHelper;
    private TextToSpeech textToSpeech;
    private Handler mainHandler;
    
    private boolean isTracking = false;
    private boolean autoVoiceAlert = true;
    private boolean receiverRegistered = false;
    private boolean ttsInitialized = false;
    private boolean googleAccountVerified = false;
    private boolean initialSetupComplete = false;

    private String boundGoogleAccount = "";
    private final List<String> otpKeywords = new ArrayList<>();
    private final List<String> bankingKeywords = new ArrayList<>();

    // Security preference keys
    private static final String PREF_KEY_PIN = "src_security_pin";
    private static final String PREF_KEY_Q1 = "src_security_q1";
    private static final String PREF_KEY_Q2 = "src_security_q2";
    private static final String PREF_KEY_A1 = "src_security_a1";
    private static final String PREF_KEY_A2 = "src_security_a2";
    private static final String PREF_KEY_ACCOUNT = "src_google_account";
    private static final String PREF_KEY_LAST_SYNC = "src_last_sync_timestamp";
    private static final String PREF_KEY_INITIAL_FETCH_DONE = "src_initial_fetch_completed";
    private static final String PREF_KEY_SETUP_COMPLETE = "src_setup_complete";

    private static final String[] SECURITY_QUESTIONS = {
        "What is your primary school name?",
        "What is your mother's name?",
        "In which city were you born?",
        "What is your first pet's name?",
        "What is your favorite book?"
    };

    public SRCSMSTracker(ComponentContainer container) {
        super(container.$form());
        this.form = container.$form();
        this.activity = container.$context();
        this.context = container.$context();
        this.mainHandler = new Handler(Looper.getMainLooper());

        form.registerForOnDestroy(this);
        applyScreenSecurity();
        
        this.dbHelper = new DatabaseHelper(context);
        this.textToSpeech = new TextToSpeech(context, this);
        
        initializeKeywordFilters();
        performSystemIntegrityCheck();
        restorePreviousGoogleAccount();
    }

    private void applyScreenSecurity() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    activity.getWindow().setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE, 
                        WindowManager.LayoutParams.FLAG_SECURE
                    );
                } catch (Exception e) {
                    // Silent bypass
                }
            }
        });
    }

    private void restorePreviousGoogleAccount() {
        String savedAccount = dbHelper.getSetting(PREF_KEY_ACCOUNT);
        if (savedAccount != null && !savedAccount.isEmpty()) {
            this.boundGoogleAccount = savedAccount;
            this.googleAccountVerified = true;
        }
    }

    private void initializeKeywordFilters() {
        otpKeywords.clear();
        otpKeywords.add("otp");
        otpKeywords.add("verification");
        otpKeywords.add("verify");
        otpKeywords.add("code");
        otpKeywords.add("pin");
        otpKeywords.add("one-time");
        otpKeywords.add("one time");
        otpKeywords.add("passcode");
        otpKeywords.add("mfa");
        otpKeywords.add("2fa");
        otpKeywords.add("activation");
        otpKeywords.add("validate");
        otpKeywords.add("authenticate");

        bankingKeywords.clear();
        bankingKeywords.add("debited");
        bankingKeywords.add("credited");
        bankingKeywords.add("bank");
        bankingKeywords.add("txn");
        bankingKeywords.add("transaction");
        bankingKeywords.add("balance");
        bankingKeywords.add("withdrawn");
        bankingKeywords.add("deposited");
        bankingKeywords.add("upi");
        bankingKeywords.add("a/c");
        bankingKeywords.add("card");
        bankingKeywords.add("amount");
        bankingKeywords.add("rupee");
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = textToSpeech.setLanguage(Locale.US);
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                this.ttsInitialized = true;
            }
        }
    }

    // ==========================================
    // DESIGNER PROPERTIES
    // ==========================================

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN, defaultValue = "True")
    @SimpleProperty(description = "Enable automatic audio alerts for secure verification messages.")
    public void AutoVoiceAlert(boolean enabled) {
        this.autoVoiceAlert = enabled;
    }

    @SimpleProperty(category = PropertyCategory.BEHAVIOR)
    public boolean AutoVoiceAlert() {
        return this.autoVoiceAlert;
    }

    @SimpleProperty(category = PropertyCategory.BEHAVIOR, description = "Status of background SMS receiver.")
    public boolean IsTrackingActive() {
        return this.isTracking;
    }

    @SimpleProperty(category = PropertyCategory.BEHAVIOR, description = "Currently bound Google Account email.")
    public String BoundGoogleAccount() {
        return this.boundGoogleAccount;
    }

    @SimpleProperty(category = PropertyCategory.BEHAVIOR, description = "Is Google Account verified and set.")
    public boolean IsGoogleAccountVerified() {
        return this.googleAccountVerified;
    }

    @SimpleProperty(category = PropertyCategory.BEHAVIOR, description = "Is initial setup complete.")
    public boolean IsInitialSetupComplete() {
        return this.initialSetupComplete;
    }

    // ==========================================
    // SYSTEM INTEGRITY CHECKS
    // ==========================================

    private void performSystemIntegrityCheck() {
        if (isEmulator() || isRooted() || isUsbDebuggingActive()) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    showCriticalShutdownDialog("SECURITY VIOLATION: Emulator, Root Access, or USB Debugging detected. Access Denied.");
                }
            });
        }
    }

    private boolean isEmulator() {
        String fingerprint = Build.FINGERPRINT;
        String model = Build.MODEL;
        String manufacturer = Build.MANUFACTURER;
        String hardware = Build.HARDWARE;
        return fingerprint.startsWith("generic") 
            || fingerprint.startsWith("unknown") 
            || model.contains("google_sdk") 
            || model.contains("Emulator") 
            || model.contains("Android SDK built for x86") 
            || manufacturer.contains("Genymotion") 
            || hardware.contains("goldfish") 
            || hardware.contains("ranchu");
    }

    private boolean isRooted() {
        String[] paths = {
            "/system/app/Superuser.apk", 
            "/sbin/su", 
            "/system/bin/su", 
            "/system/xbin/su", 
            "/data/local/xbin/su", 
            "/data/local/bin/su", 
            "/system/sd/xbin/su", 
            "/system/bin/failsafe/su", 
            "/data/local/su"
        };
        for (String path : paths) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return false;
    }

    private boolean isUsbDebuggingActive() {
        try {
            int adbEnabled = Settings.Global.getInt(
                context.getContentResolver(), 
                Settings.Global.ADB_ENABLED, 0
            );
            return adbEnabled != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void showCriticalShutdownDialog(String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("System Integrity Threat")
               .setMessage(message)
               .setCancelable(false)
               .setPositiveButton("Close App", new DialogInterface.OnClickListener() {
                   @Override
                   public void onClick(DialogInterface dialog, int which) {
                       activity.finishAffinity();
                       System.exit(0);
                   }
               })
               .show();
    }

    // ==========================================
    // PERMISSION MANAGEMENT
    // ==========================================

    @SimpleFunction(description = "Check if all required permissions are granted.")
    public boolean HasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean receiveSms = context.checkSelfPermission("android.permission.RECEIVE_SMS") == PackageManager.PERMISSION_GRANTED;
            boolean readSms = context.checkSelfPermission("android.permission.READ_SMS") == PackageManager.PERMISSION_GRANTED;
            boolean sendSms = context.checkSelfPermission("android.permission.SEND_SMS") == PackageManager.PERMISSION_GRANTED;
            boolean getAccounts = context.checkSelfPermission("android.permission.GET_ACCOUNTS") == PackageManager.PERMISSION_GRANTED;
            boolean postNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU 
                ? context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
                : true;
            return receiveSms && readSms && sendSms && getAccounts && postNotif;
        }
        return true;
    }

    @SimpleFunction(description = "Request all required permissions sequentially.")
    public void RequestAllPermissions() {
        if (HasRequiredPermissions()) {
            OnAllPermissionsGranted();
            checkUserSecurityFlow();
            return;
        }
        requestSmsReceivePermission();
    }

    private void requestSmsReceivePermission() {
        form.askPermission("android.permission.RECEIVE_SMS", new PermissionResultHandler() {
            @Override
            public void HandlePermissionResponse(String permission, boolean granted) {
                if (granted) {
                    requestSmsReadPermission();
                } else {
                    showPermissionDeniedAlert();
                }
            }
        });
    }

    private void requestSmsReadPermission() {
        form.askPermission("android.permission.READ_SMS", new PermissionResultHandler() {
            @Override
            public void HandlePermissionResponse(String permission, boolean granted) {
                if (granted) {
                    requestSmsSendPermission();
                } else {
                    showPermissionDeniedAlert();
                }
            }
        });
    }

    private void requestSmsSendPermission() {
        form.askPermission("android.permission.SEND_SMS", new PermissionResultHandler() {
            @Override
            public void HandlePermissionResponse(String permission, boolean granted) {
                if (granted) {
                    requestGetAccountsPermission();
                } else {
                    showPermissionDeniedAlert();
                }
            }
        });
    }

    private void requestGetAccountsPermission() {
        form.askPermission("android.permission.GET_ACCOUNTS", new PermissionResultHandler() {
            @Override
            public void HandlePermissionResponse(String permission, boolean granted) {
                if (granted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestNotificationPermission();
                    } else {
                        OnAllPermissionsGranted();
                        checkUserSecurityFlow();
                    }
                } else {
                    showPermissionDeniedAlert();
                }
            }
        });
    }

    private void requestNotificationPermission() {
        form.askPermission("android.permission.POST_NOTIFICATIONS", new PermissionResultHandler() {
            @Override
            public void HandlePermissionResponse(String permission, boolean granted) {
                OnAllPermissionsGranted();
                checkUserSecurityFlow();
            }
        });
    }

    private void showPermissionDeniedAlert() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                builder.setTitle("Permissions Required")
                       .setMessage("All permissions are mandatory to use this app. Please grant all requested permissions.")
                       .setCancelable(false)
                       .setPositiveButton("Grant Permissions", new DialogInterface.OnClickListener() {
                           @Override
                           public void onClick(DialogInterface dialog, int which) {
                               RequestAllPermissions();
                           }
                       })
                       .setNegativeButton("Exit App", new DialogInterface.OnClickListener() {
                           @Override
                           public void onClick(DialogInterface dialog, int which) {
                               activity.finishAffinity();
                               System.exit(0);
                           }
                       })
                       .show();
            }
        });
    }

    // ==========================================
    // GOOGLE ACCOUNT BINDING (MANDATORY)
    // ==========================================

    private void checkUserSecurityFlow() {
        String setupComplete = dbHelper.getSetting(PREF_KEY_SETUP_COMPLETE);
        
        if (setupComplete != null && setupComplete.equals("true")) {
            this.initialSetupComplete = true;
            String savedPin = dbHelper.getSetting(PREF_KEY_PIN);
            if (savedPin == null || savedPin.isEmpty()) {
                promptPinRegistration();
            } else {
                promptLockerVerification();
            }
            return;
        }

        if (googleAccountVerified && !boundGoogleAccount.isEmpty()) {
            String savedPin = dbHelper.getSetting(PREF_KEY_PIN);
            if (savedPin == null || savedPin.isEmpty()) {
                promptPinRegistration();
            } else {
                promptLockerVerification();
            }
            return;
        }

        String savedAccount = dbHelper.getSetting(PREF_KEY_ACCOUNT);
        if (savedAccount == null || savedAccount.isEmpty()) {
            promptGoogleAccountSelection();
        } else {
            this.boundGoogleAccount = savedAccount;
            this.googleAccountVerified = true;
            String savedPin = dbHelper.getSetting(PREF_KEY_PIN);
            if (savedPin == null || savedPin.isEmpty()) {
                promptPinRegistration();
            } else {
                promptLockerVerification();
            }
        }
    }

    private void promptGoogleAccountSelection() {
        try {
            Account[] accounts = AccountManager.get(context).getAccountsByType("com.google");
            if (accounts.length == 0) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                        builder.setTitle("Google Account Required")
                               .setMessage("This app requires a Google Account to function. Please add a Google Account in device settings.")
                               .setCancelable(false)
                               .setPositiveButton("Exit App", new DialogInterface.OnClickListener() {
                                   @Override
                                   public void onClick(DialogInterface dialog, int which) {
                                       activity.finishAffinity();
                                       System.exit(0);
                                   }
                               })
                               .show();
                    }
                });
                return;
            }

            final String[] accountNames = new String[accounts.length];
            for (int i = 0; i < accounts.length; i++) {
                accountNames[i] = accounts[i].name;
            }

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                    builder.setTitle("Select Google Account")
                           .setMessage("Select your primary Google Account for this app. This is mandatory and cannot be skipped.")
                           .setCancelable(false)
                           .setItems(accountNames, new DialogInterface.OnClickListener() {
                               @Override
                               public void onClick(DialogInterface dialog, int which) {
                                   String chosen = accountNames[which];
                                   dbHelper.setSetting(PREF_KEY_ACCOUNT, chosen);
                                   boundGoogleAccount = chosen;
                                   googleAccountVerified = true;
                                   OnGoogleAccountSelected(chosen);
                                   
                                   String savedPin = dbHelper.getSetting(PREF_KEY_PIN);
                                   if (savedPin == null || savedPin.isEmpty()) {
                                       promptPinRegistration();
                                   } else {
                                       promptLockerVerification();
                                   }
                               }
                           })
                           .show();
                }
            });
        } catch (SecurityException e) {
            OnSmartTrackerError("Security error accessing Google accounts: " + e.getMessage());
        }
    }

    // ==========================================
    // SECURITY PIN & QUESTIONS SETUP
    // ==========================================

    private void promptPinRegistration() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(40, 20, 40, 20);

                TextView hintText = new TextView(activity);
                hintText.setText("Set Your 6-Digit Security PIN:");
                hintText.setTextSize(16);
                hintText.setPadding(0, 10, 0, 10);
                layout.addView(hintText);

                EditText pinInput = new EditText(activity);
                pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                pinInput.setHint("6-Digit PIN");
                layout.addView(pinInput);

                TextView q1Text = new TextView(activity);
                q1Text.setText("Security Question 1:");
                q1Text.setTextSize(14);
                q1Text.setPadding(0, 20, 0, 5);
                layout.addView(q1Text);

                Spinner spinnerQ1 = new Spinner(activity);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    activity, android.R.layout.simple_spinner_item, SECURITY_QUESTIONS);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerQ1.setAdapter(adapter);
                layout.addView(spinnerQ1);

                EditText a1Input = new EditText(activity);
                a1Input.setHint("Your Answer");
                layout.addView(a1Input);

                TextView q2Text = new TextView(activity);
                q2Text.setText("Security Question 2:");
                q2Text.setTextSize(14);
                q2Text.setPadding(0, 20, 0, 5);
                layout.addView(q2Text);

                Spinner spinnerQ2 = new Spinner(activity);
                spinnerQ2.setAdapter(adapter);
                layout.addView(spinnerQ2);

                EditText a2Input = new EditText(activity);
                a2Input.setHint("Your Answer");
                layout.addView(a2Input);

                AlertDialog registerDialog = new AlertDialog.Builder(activity)
                    .setTitle("Security Setup")
                    .setView(layout)
                    .setCancelable(false)
                    .setPositiveButton("Save PIN", null)
                    .setNegativeButton("Exit App", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            activity.finishAffinity();
                            System.exit(0);
                        }
                    })
                    .create();

                registerDialog.setOnShowListener(new DialogInterface.OnShowListener() {
                    @Override
                    public void onShow(DialogInterface dialog) {
                        registerDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                String pin = pinInput.getText().toString().trim();
                                String a1 = a1Input.getText().toString().trim().toLowerCase(Locale.ROOT);
                                String a2 = a2Input.getText().toString().trim().toLowerCase(Locale.ROOT);
                                int q1Index = spinnerQ1.getSelectedItemPosition();
                                int q2Index = spinnerQ2.getSelectedItemPosition();

                                if (pin.length() != 6 || !pin.matches("[0-9]+")) {
                                    Toast.makeText(context, "PIN must be exactly 6 digits.", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                if (q1Index == q2Index) {
                                    Toast.makeText(context, "Select two different security questions.", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                if (a1.isEmpty() || a2.isEmpty()) {
                                    Toast.makeText(context, "Answers cannot be empty.", Toast.LENGTH_SHORT).show();
                                    return;
                                }

                                try {
                                    String hashPin = hashStringSHA256(pin);
                                    String hashA1 = hashStringSHA256(a1);
                                    String hashA2 = hashStringSHA256(a2);

                                    dbHelper.setSetting(PREF_KEY_PIN, hashPin);
                                    dbHelper.setSetting(PREF_KEY_Q1, String.valueOf(q1Index));
                                    dbHelper.setSetting(PREF_KEY_Q2, String.valueOf(q2Index));
                                    dbHelper.setSetting(PREF_KEY_A1, hashA1);
                                    dbHelper.setSetting(PREF_KEY_A2, hashA2);
                                    dbHelper.setSetting(PREF_KEY_SETUP_COMPLETE, "true");

                                    Toast.makeText(context, "Security PIN saved successfully.", Toast.LENGTH_SHORT).show();
                                    registerDialog.dismiss();
                                    
                                    initialSetupComplete = true;
                                    OnSecurityPinSet();
                                    performInitialFetchIfNeeded();
                                } catch (Exception e) {
                                    Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                });

                registerDialog.show();
            }
        });
    }

    private void promptLockerVerification() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(40, 20, 40, 20);

                TextView hintText = new TextView(activity);
                hintText.setText("Enter Your 6-Digit PIN:");
                hintText.setTextSize(16);
                hintText.setPadding(0, 10, 0, 10);
                layout.addView(hintText);

                EditText pinInput = new EditText(activity);
                pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                pinInput.setHint("Enter PIN");
                layout.addView(pinInput);

                Button forgotBtn = new Button(activity);
                forgotBtn.setText("Forgot PIN?");
                forgotBtn.setBackgroundColor(0);
                forgotBtn.setTextColor(0xFFD32F2F);
                layout.addView(forgotBtn);

                AlertDialog lockDialog = new AlertDialog.Builder(activity)
                    .setTitle("App Locked")
                    .setView(layout)
                    .setCancelable(false)
                    .setPositiveButton("Unlock", null)
                    .setNegativeButton("Exit", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            activity.finishAffinity();
                            System.exit(0);
                        }
                    })
                    .create();

                forgotBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        lockDialog.dismiss();
                        promptSecurityPinRecovery();
                    }
                });

                lockDialog.setOnShowListener(new DialogInterface.OnShowListener() {
                    @Override
                    public void onShow(DialogInterface dialog) {
                        lockDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                String pin = pinInput.getText().toString();
                                try {
                                    String hashInput = hashStringSHA256(pin);
                                    String savedHash = dbHelper.getSetting(PREF_KEY_PIN);

                                    if (hashInput.equals(savedHash)) {
                                        Toast.makeText(context, "PIN verified successfully.", Toast.LENGTH_SHORT).show();
                                        lockDialog.dismiss();
                                        initialSetupComplete = true;
                                        OnSecurityPinVerified();
                                        performInitialFetchIfNeeded();
                                    } else {
                                        pinInput.setError("Incorrect PIN!");
                                        Toast.makeText(context, "Wrong PIN. Try again.", Toast.LENGTH_SHORT).show();
                                    }
                                } catch (Exception e) {
                                    Toast.makeText(context, "Verification error.", Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                });

                lockDialog.show();
            }
        });
    }

    private void promptSecurityPinRecovery() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(40, 20, 40, 20);

                try {
                    final int q1Idx = Integer.parseInt(dbHelper.getSetting(PREF_KEY_Q1));
                    final int q2Idx = Integer.parseInt(dbHelper.getSetting(PREF_KEY_Q2));

                    TextView q1Header = new TextView(activity);
                    q1Header.setText("Q1: " + SECURITY_QUESTIONS[q1Idx]);
                    q1Header.setTextSize(14);
                    q1Header.setPadding(0, 10, 0, 5);
                    layout.addView(q1Header);

                    EditText a1Input = new EditText(activity);
                    a1Input.setHint("Answer");
                    layout.addView(a1Input);

                    TextView q2Header = new TextView(activity);
                    q2Header.setText("Q2: " + SECURITY_QUESTIONS[q2Idx]);
                    q2Header.setTextSize(14);
                    q2Header.setPadding(0, 20, 0, 5);
                    layout.addView(q2Header);

                    EditText a2Input = new EditText(activity);
                    a2Input.setHint("Answer");
                    layout.addView(a2Input);

                    AlertDialog recoveryDialog = new AlertDialog.Builder(activity)
                        .setTitle("Recover PIN")
                        .setView(layout)
                        .setCancelable(false)
                        .setPositiveButton("Verify", null)
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss(); 
                                promptLockerVerification();
                            }
                        })
                        .create();

                    recoveryDialog.setOnShowListener(new DialogInterface.OnShowListener() {
                        @Override
                        public void onShow(DialogInterface dialog) {
                            recoveryDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    String a1 = a1Input.getText().toString().trim().toLowerCase(Locale.ROOT);
                                    String a2 = a2Input.getText().toString().trim().toLowerCase(Locale.ROOT);

                                    if (a1.isEmpty() || a2.isEmpty()) {
                                        Toast.makeText(context, "Answers cannot be empty.", Toast.LENGTH_SHORT).show();
                                        return;
                                    }

                                    try {
                                        String hashA1 = hashStringSHA256(a1);
                                        String hashA2 = hashStringSHA256(a2);

                                        String savedHashA1 = dbHelper.getSetting(PREF_KEY_A1);
                                        String savedHashA2 = dbHelper.getSetting(PREF_KEY_A2);

                                        if (hashA1.equals(savedHashA1) && hashA2.equals(savedHashA2)) {
                                            recoveryDialog.dismiss();
                                            Toast.makeText(context, "Identity verified. Set new PIN.", Toast.LENGTH_SHORT).show();
                                            promptPinRegistration();
                                        } else {
                                            Toast.makeText(context, "Answers do not match. Try again.", Toast.LENGTH_LONG).show();
                                        }
                                    } catch (Exception e) {
                                        Toast.makeText(context, "Verification error.", Toast.LENGTH_LONG).show();
                                    }
                                }
                            });
                        }
                    });

                    recoveryDialog.show();
                } catch (Exception e) {
                    Toast.makeText(context, "Recovery setup error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private String hashStringSHA256(String base) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(base.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    // ==========================================
    // BACKGROUND SMS INTERCEPTION
    // ==========================================

    @SimpleFunction(description = "Start background SMS tracking.")
    public void StartTracking() {
        if (!googleAccountVerified || boundGoogleAccount.isEmpty()) {
            OnSmartTrackerError("Access Denied: Google Account not verified.");
            return;
        }
        if (isTracking) {
            OnSmartTrackerError("Tracking already active.");
            return;
        }
        if (!HasRequiredPermissions()) {
            OnSmartTrackerError("Permissions insufficient.");
            return;
        }

        try {
            IntentFilter filter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
            filter.setPriority(999);
            context.getApplicationContext().registerReceiver(smsReceiver, filter);
            this.receiverRegistered = true;
            this.isTracking = true;
            OnTrackingStatusChanged(true);
        } catch (Exception e) {
            OnSmartTrackerError("Failed to start tracking: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Stop background SMS tracking.")
    public void StopTracking() {
        if (!isTracking) return;
        try {
            if (receiverRegistered) {
                context.getApplicationContext().unregisterReceiver(smsReceiver);
                this.receiverRegistered = false;
            }
            this.isTracking = false;
            OnTrackingStatusChanged(false);
        } catch (Exception e) {
            OnSmartTrackerError("Failed to stop tracking: " + e.getMessage());
        }
    }

    private final BroadcastReceiver smsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    try {
                        Object[] pdus = (Object[]) bundle.get("pdus");
                        if (pdus != null) {
                            for (Object pdu : pdus) {
                                SmsMessage smsMessage;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    String format = bundle.getString("format");
                                    smsMessage = SmsMessage.createFromPdu((byte[]) pdu, format);
                                } else {
                                    smsMessage = SmsMessage.createFromPdu((byte[]) pdu);
                                }

                                final String sender = smsMessage.getDisplayOriginatingAddress();
                                final String body = smsMessage.getDisplayMessageBody();
                                final long timestamp = smsMessage.getTimestampMillis();

                                processIncomingMessage(sender, body, timestamp, true);
                            }
                        }
                    } catch (Exception e) {
                        OnSmartTrackerError("SMS reception error: " + e.getMessage());
                    }
                }
            }
        }
    };

    private void performInitialFetchIfNeeded() {
        String initialFetchDone = dbHelper.getSetting(PREF_KEY_INITIAL_FETCH_DONE);
        if (initialFetchDone == null || initialFetchDone.isEmpty() || initialFetchDone.equals("false")) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(500);
                        startInitialIncrementalFetch();
                    } catch (Exception e) {
                        OnSmartTrackerError("Initial fetch error: " + e.getMessage());
                    }
                }
            }).start();
        }
    }

    @SimpleFunction(description = "Manually sync new messages.")
    public void SyncNewMessages() {
        if (!googleAccountVerified || boundGoogleAccount.isEmpty()) {
            OnSmartTrackerError("Access Denied: Google Account not set.");
            return;
        }
        if (!HasRequiredPermissions()) {
            OnSmartTrackerError("Permissions insufficient.");
            return;
        }
        startInitialIncrementalFetch();
    }

    private void startInitialIncrementalFetch() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long lastTimestamp = dbHelper.getLastMessageTimestamp();
                    fetchInboxMessagesSince(lastTimestamp);
                    dbHelper.setSetting(PREF_KEY_INITIAL_FETCH_DONE, "true");
                } catch (Exception e) {
                    OnSmartTrackerError("Fetch failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private void fetchInboxMessagesSince(long timestamp) {
        try {
            Uri uri = Uri.parse("content://sms/inbox");
            String[] projection = {"_id", "address", "body", "date"};
            String selection = "date > ?";
            String[] selectionArgs = {String.valueOf(timestamp)};
            String sortOrder = "date ASC";

            Cursor cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);
            if (cursor != null) {
                int count = 0;
                while (cursor.moveToNext()) {
                    String sender = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                    String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                    long date = cursor.getLong(cursor.getColumnIndexOrThrow("date"));

                    processIncomingMessage(sender, body, date, false);
                    count++;
                }
                cursor.close();
                if (count > 0) {
                    OnIncrementalSyncCompleted(count);
                }
            }
        } catch (Exception e) {
            OnSmartTrackerError("Sync failed: " + e.getMessage());
        }
    }

    // ==========================================
    // MESSAGE PROCESSING & CATEGORIZATION
    // ==========================================

    private void processIncomingMessage(String sender, String body, long timestamp, boolean isRealtime) {
        if (dbHelper.isMessageDuplicate(sender, body, timestamp)) {
            return;
        }

        boolean isOTP = evaluateKeywordMatch(body, otpKeywords);
        boolean isBanking = evaluateKeywordMatch(body, bankingKeywords);

        String category = "Promotional";
        if (isOTP) {
            category = "OTP/Verification";
        } else if (isBanking) {
            category = "Banking/Transaction";
        } else if (isTransactionalFormat(sender)) {
            category = "Transactional";
        } else {
            category = "Personal";
        }

        String secureTag = (isOTP || isBanking) ? "Secure" : "Standard";

        int messageId = dbHelper.insertMessage(sender, body, timestamp, secureTag, category, isOTP ? 1 : 0, boundGoogleAccount);

        if (isRealtime) {
            if (autoVoiceAlert && ttsInitialized && textToSpeech != null) {
                String announcement = "New " + category + " message from " + sender;
                textToSpeech.speak(announcement, TextToSpeech.QUEUE_FLUSH, null);
            }

            OnSMSInterceptionSuccess(sender, body, timestamp, category, secureTag);
            triggerFullScreenNotification(sender, body, category);
            playNotificationSound();
        }
    }

    private boolean isTransactionalFormat(String sender) {
        return sender != null && sender.matches("^[a-zA-Z0-9]{2,}-?[a-zA-Z0-9]*$");
    }

    private boolean evaluateKeywordMatch(String text, List<String> list) {
        if (text == null || text.trim().isEmpty()) return false;
        String clean = text.toLowerCase(Locale.ROOT);
        for (String key : list) {
            if (clean.contains(key)) return true;
        }
        return false;
    }

    private void triggerFullScreenNotification(String sender, String body, String category) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            String channelId = "src_sms_security_alerts";
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    channelId, 
                    "SRC SMS Shield", 
                    NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Secure SMS notifications");
                channel.enableVibration(true);
                channel.setShowBadge(true);
                nm.createNotificationChannel(channel);
            }

            Intent intent = new Intent(context, activity.getClass());
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                context, 
                (int) System.currentTimeMillis(), 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            android.app.Notification.Builder notification;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification = new android.app.Notification.Builder(context, channelId);
            } else {
                notification = new android.app.Notification.Builder(context);
            }

            notification.setContentTitle("SRC Shield: " + category)
                        .setContentText("From: " + sender)
                        .setSubText(body.length() > 50 ? body.substring(0, 50) + "..." : body)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .setVibrate(new long[]{0, 500, 250, 500})
                        .setPriority(android.app.Notification.PRIORITY_HIGH);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                notification.setFullScreenIntent(pi, true);
            }

            nm.notify((int) System.currentTimeMillis(), notification.build());
        } catch (Exception e) {
            // Silent bypass
        }
    }

    private void playNotificationSound() {
        try {
            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            android.media.Ringtone ringtone = RingtoneManager.getRingtone(context, soundUri);
            ringtone.play();
        } catch (Exception e) {
            // Silent bypass
        }
    }

    // ==========================================
    // SMS SENDING
    // ==========================================

    @SimpleFunction(description = "Send SMS securely offline.")
    public void SendSMS(String phoneNumber, String messageText) {
        if (!googleAccountVerified || boundGoogleAccount.isEmpty()) {
            OnSmartTrackerError("Access Denied: Google Account not verified.");
            return;
        }
        if (!HasRequiredPermissions()) {
            OnSmartTrackerError("Permissions insufficient.");
            return;
        }
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phoneNumber, null, messageText, null, null);
            OnSMSSentSuccess(phoneNumber, messageText);
            Toast.makeText(context, "SMS sent successfully.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            OnSmartTrackerError("SMS Send Error: " + e.getMessage());
        }
    }

    // ==========================================
    // MESSAGE MANAGEMENT
    // ==========================================

    @SimpleFunction(description = "Clear all stored messages.")
    public void ClearAllStoredMessages() {
        if (!googleAccountVerified || boundGoogleAccount.isEmpty()) {
            OnSmartTrackerError("Access Denied: Google Account not set.");
            return;
        }
        try {
            dbHelper.clearAllMessages(boundGoogleAccount);
            Toast.makeText(context, "All messages cleared.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            OnSmartTrackerError("Clear failed: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Modify security tag of a message.")
    public void ModifySecurityTag(int messageId, String customTag) {
        if (!googleAccountVerified || boundGoogleAccount.isEmpty()) {
            OnSmartTrackerError("Access Denied: Google Account not set.");
            return;
        }
        try {
            dbHelper.updateMessageTag(messageId, customTag);
            OnMessageTagUpdated(messageId, customTag);
        } catch (Exception e) {
            OnSmartTrackerError("Tag update failed: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Delete a specific message record.")
    public void DeleteMessageRecord(int messageId) {
        if (!googleAccountVerified || boundGoogleAccount.isEmpty()) {
            OnSmartTrackerError("Access Denied: Google Account not set.");
            return;
        }
        try {
            dbHelper.deleteMessage(messageId);
            OnMessageRecordDeleted(messageId);
        } catch (Exception e) {
            OnSmartTrackerError("Delete failed: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Get all stored messages as JSON.")
    public String GetAllStoredMessages() {
        if (!googleAccountVerified || boundGoogleAccount.isEmpty()) {
            OnSmartTrackerError("Access Denied: Google Account not set.");
            return "[]";
        }
        try {
            return dbHelper.getEncryptedMessageJson(boundGoogleAccount);
        } catch (Exception e) {
            OnSmartTrackerError("Fetch failed: " + e.getMessage());
            return "[]";
        }
    }

    @SimpleFunction(description = "Get message count by category.")
    public int GetMessageCountByCategory(String category) {
        if (!googleAccountVerified || boundGoogleAccount.isEmpty()) {
            OnSmartTrackerError("Access Denied: Google Account not set.");
            return 0;
        }
        try {
            return dbHelper.getMessageCountByCategory(boundGoogleAccount, category);
        } catch (Exception e) {
            OnSmartTrackerError("Count fetch failed: " + e.getMessage());
            return 0;
        }
    }

    @SimpleFunction(description = "Get total message count.")
    public int GetTotalMessageCount() {
        if (!googleAccountVerified || boundGoogleAccount.isEmpty()) {
            OnSmartTrackerError("Access Denied: Google Account not set.");
            return 0;
        }
        try {
            return dbHelper.getTotalMessageCount(boundGoogleAccount);
        } catch (Exception e) {
            OnSmartTrackerError("Count fetch failed: " + e.getMessage());
            return 0;
        }
    }

    // ==========================================
    // OTP & LINK UTILITIES
    // ==========================================

    @SimpleFunction(description = "Copy OTP code to clipboard.")
    public void CopyOTPToClipboard(String bodyText) {
        try {
            String numericCode = "";
            String[] words = bodyText.split("\\s+");
            for (String word : words) {
                String cleanWord = word.replaceAll("[^0-9]", "");
                if (cleanWord.length() >= 4 && cleanWord.length() <= 8) {
                    numericCode = cleanWord;
                    break;
                }
            }

            if (!numericCode.isEmpty()) {
                ClipboardManager clipboard = 
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("SRC_OTP", numericCode);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "OTP Copied: " + numericCode, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "No OTP code found.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            OnSmartTrackerError("Copy failed: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Extract and open link from message.")
    public void HandleSecureRedirection(String contentBody) {
        if (contentBody == null) return;
        try {
            String url = extractFirstLink(contentBody);
            if (url != null && !url.isEmpty()) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(browserIntent);
            } else {
                Toast.makeText(context, "No link found in message.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            OnSmartTrackerError("Redirection failed: " + e.getMessage());
        }
    }

    private String extractFirstLink(String body) {
        String[] parts = body.split("\\s+");
        for (String part : parts) {
            if (part.startsWith("http://") || part.startsWith("https://")) {
                return part;
            }
        }
        return null;
    }

    // ==========================================
    // ENCRYPTION & EXPORT/IMPORT (.SRC)
    // ==========================================

    @SimpleFunction(description = "Export database as encrypted .src file.")
    public void ExportDatabaseToSrc() {
        if (!googleAccountVerified || boundGoogleAccount.isEmpty()) {
            OnSmartTrackerError("Access Denied: Google Account not set.");
            return;
        }
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String rawJson = dbHelper.getEncryptedMessageJson(boundGoogleAccount);
                        byte[] keyBytes = generateEncryptionKeyFromAccount();
                        
                        byte[] iv = new byte[12];
                        new SecureRandom().nextBytes(iv);
                        
                        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
                        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
                        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
                        
                        byte[] cipherText = cipher.doFinal(rawJson.getBytes(StandardCharsets.UTF_8));
                        
                        byte[] outputBytes = new byte[12 + cipherText.length];
                        System.arraycopy(iv, 0, outputBytes, 0, 12);
                        System.arraycopy(cipherText, 0, outputBytes, 12, cipherText.length);
                        
                        File docFolder = new File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), 
                            "SRCSMSTracker"
                        );
                        if (!docFolder.exists()) {
                            docFolder.mkdirs();
                        }
                        
                        File backupFile = new File(docFolder, "backup.src");
                        if (backupFile.exists()) {
                            backupFile.delete();
                        }
                        
                        FileOutputStream fos = new FileOutputStream(backupFile);
                        fos.write(outputBytes);
                        fos.close();
                        
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(context, "Backup exported successfully.", Toast.LENGTH_SHORT).show();
                                OnExportCompleted(backupFile.getAbsolutePath());
                            }
                        });
                    } catch (Exception e) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                OnSmartTrackerError("Export failed: " + e.getMessage());
                            }
                        });
                    }
                }
            }).start();
        } catch (Exception e) {
            OnSmartTrackerError("Export error: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Import encrypted .src backup file.")
    public void ImportDatabaseFromSrc() {
        if (!googleAccountVerified || boundGoogleAccount.isEmpty()) {
            OnSmartTrackerError("Access Denied: Google Account not set.");
            return;
        }
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File backupFile = new File(
                            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SRCSMSTracker"), 
                            "backup.src"
                        );
                        
                        if (!backupFile.exists()) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    OnSmartTrackerError("Backup file not found.");
                                }
                            });
                            return;
                        }

                        byte[] fileBytes = new byte[(int) backupFile.length()];
                        FileInputStream fis = new FileInputStream(backupFile);
                        fis.read(fileBytes);
                        fis.close();

                        if (fileBytes.length < 13) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    OnSmartTrackerError("Backup file is corrupted.");
                                }
                            });
                            return;
                        }

                        byte[] iv = new byte[12];
                        System.arraycopy(fileBytes, 0, iv, 0, 12);
                        
                        byte[] cipherText = new byte[fileBytes.length - 12];
                        System.arraycopy(fileBytes, 12, cipherText, 0, cipherText.length);

                        byte[] keyBytes = generateEncryptionKeyFromAccount();
                        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
                        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

                        byte[] decryptedBytes = cipher.doFinal(cipherText);
                        String rawJson = new String(decryptedBytes, StandardCharsets.UTF_8);

                        List<Map<String, String>> parsedList = parseJsonArray(rawJson);
                        dbHelper.restoreDatabaseFromParsedList(parsedList, boundGoogleAccount);
                        
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(context, "Backup imported successfully.", Toast.LENGTH_SHORT).show();
                                OnImportCompleted();
                            }
                        });
                    } catch (Exception e) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                OnSmartTrackerError("Import failed: " + e.getMessage());
                            }
                        });
                    }
                }
            }).start();
        } catch (Exception e) {
            OnSmartTrackerError("Import error: " + e.getMessage());
        }
    }

    private byte[] generateEncryptionKeyFromAccount() throws Exception {
        String pinHash = dbHelper.getSetting(PREF_KEY_PIN);
        String baseSeed = boundGoogleAccount + (pinHash != null ? pinHash : "");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(baseSeed.getBytes(StandardCharsets.UTF_8));
    }

    // ==========================================
    // JSON PARSING
    // ==========================================

    private static List<Map<String, String>> parseJsonArray(String jsonStr) {
        List<Map<String, String>> list = new ArrayList<>();
        if (jsonStr == null || jsonStr.trim().isEmpty()) return list;
        
        String trimmed = jsonStr.trim();
        if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        trimmed = trimmed.trim();
        
        if (trimmed.isEmpty()) return list;
        
        int len = trimmed.length();
        boolean inString = false;
        boolean isEscaped = false;
        StringBuilder currentObjStr = new StringBuilder();
        
        for (int i = 0; i < len; i++) {
            char c = trimmed.charAt(i);
            if (isEscaped) {
                currentObjStr.append(c);
                isEscaped = false;
                continue;
            }
            if (c == '\\') {
                currentObjStr.append(c);
                isEscaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
            }
            
            if (!inString && c == '}') {
                currentObjStr.append(c);
                Map<String, String> map = parseSingleJsonObject(currentObjStr.toString());
                if (!map.isEmpty()) {
                    list.add(map);
                }
                currentObjStr.setLength(0);
                while (i + 1 < len && (trimmed.charAt(i + 1) == ',' || trimmed.charAt(i + 1) == ' ' || trimmed.charAt(i + 1) == '\n' || trimmed.charAt(i + 1) == '\r')) {
                    i++;
                }
                continue;
            }
            
            if (currentObjStr.length() > 0 || c == '{') {
                currentObjStr.append(c);
            }
        }
        return list;
    }

    private static Map<String, String> parseSingleJsonObject(String objStr) {
        Map<String, String> map = new HashMap<>();
        String trimmed = objStr.trim();
        if (trimmed.startsWith("{")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("}")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        trimmed = trimmed.trim();
        
        int len = trimmed.length();
        boolean inString = false;
        boolean isEscaped = false;
        StringBuilder keyBuilder = new StringBuilder();
        StringBuilder valueBuilder = new StringBuilder();
        boolean parsingValue = false;
        
        for (int i = 0; i < len; i++) {
            char c = trimmed.charAt(i);
            if (isEscaped) {
                if (parsingValue) valueBuilder.append(c);
                else keyBuilder.append(c);
                isEscaped = false;
                continue;
            }
            if (c == '\\') {
                isEscaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString && c == ':') {
                parsingValue = true;
                continue;
            }
            if (!inString && c == ',') {
                String key = keyBuilder.toString().trim();
                String val = valueBuilder.toString().trim();
                map.put(key, val);
                keyBuilder.setLength(0);
                valueBuilder.setLength(0);
                parsingValue = false;
                continue;
            }
            
            if (parsingValue) {
                valueBuilder.append(c);
            } else {
                keyBuilder.append(c);
            }
        }
        if (keyBuilder.length() > 0) {
            map.put(keyBuilder.toString().trim(), valueBuilder.toString().trim());
        }
        return map;
    }

    // ==========================================
    // EVENTS / DISPATCHERS
    // ==========================================

    @SimpleEvent(description = "All permissions granted.")
    public void OnAllPermissionsGranted() {
        EventDispatcher.dispatchEvent(this, "OnAllPermissionsGranted");
    }

    @SimpleEvent(description = "Google Account selected and set.")
    public void OnGoogleAccountSelected(String accountEmail) {
        EventDispatcher.dispatchEvent(this, "OnGoogleAccountSelected", accountEmail);
    }

    @SimpleEvent(description = "Security PIN set successfully.")
    public void OnSecurityPinSet() {
        EventDispatcher.dispatchEvent(this, "OnSecurityPinSet");
    }

    @SimpleEvent(description = "Security PIN verified.")
    public void OnSecurityPinVerified() {
        EventDispatcher.dispatchEvent(this, "OnSecurityPinVerified");
    }

    @SimpleEvent(description = "Incremental sync completed.")
    public void OnIncrementalSyncCompleted(int recordCount) {
        EventDispatcher.dispatchEvent(this, "OnIncrementalSyncCompleted", recordCount);
    }

    @SimpleEvent(description = "SMS interception successful.")
    public void OnSMSInterceptionSuccess(String sender, String body, long timestamp, String category, String secureTag) {
        EventDispatcher.dispatchEvent(this, "OnSMSInterceptionSuccess", sender, body, timestamp, category, secureTag);
    }

    @SimpleEvent(description = "Message tag updated.")
    public void OnMessageTagUpdated(int messageId, String updatedTag) {
        EventDispatcher.dispatchEvent(this, "OnMessageTagUpdated", messageId, updatedTag);
    }

    @SimpleEvent(description = "Message record deleted.")
    public void OnMessageRecordDeleted(int messageId) {
        EventDispatcher.dispatchEvent(this, "OnMessageRecordDeleted", messageId);
    }

    @SimpleEvent(description = "Export completed.")
    public void OnExportCompleted(String filePath) {
        EventDispatcher.dispatchEvent(this, "OnExportCompleted", filePath);
    }

    @SimpleEvent(description = "Import completed.")
    public void OnImportCompleted() {
        EventDispatcher.dispatchEvent(this, "OnImportCompleted");
    }

    @SimpleEvent(description = "Tracking status changed.")
    public void OnTrackingStatusChanged(boolean isActive) {
        EventDispatcher.dispatchEvent(this, "OnTrackingStatusChanged", isActive);
    }

    @SimpleEvent(description = "SMS sent successfully.")
    public void OnSMSSentSuccess(String phoneNumber, String messageText) {
        EventDispatcher.dispatchEvent(this, "OnSMSSentSuccess", phoneNumber, messageText);
    }

    @SimpleEvent(description = "Error occurred.")
    public void OnSmartTrackerError(String errorMessage) {
        EventDispatcher.dispatchEvent(this, "OnSmartTrackerError", errorMessage);
    }

    @Override
    public void onDestroy() {
        StopTracking();
        if (textToSpeech != null) {
            try {
                textToSpeech.stop();
                textToSpeech.shutdown();
            } catch (Exception e) {
                // Silent release
            }
        }
        if (dbHelper != null) {
            dbHelper.close();
        }
    }

    // ==========================================
    // DATABASE HELPER
    // ==========================================

    private static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String DATABASE_NAME = "src_sms_offline.db";
        private static final int DATABASE_VERSION = 2;

        private static final String TABLE_MSGS = "tbl_messages";
        private static final String TABLE_PREFS = "tbl_preferences";

        private static final String COL_ID = "msg_id";
        private static final String COL_SENDER = "msg_sender";
        private static final String COL_BODY = "msg_body";
        private static final String COL_TIME = "msg_timestamp";
        private static final String COL_SECURE = "msg_secure_rating";
        private static final String COL_CAT = "msg_category";
        private static final String COL_OTP = "msg_is_otp";
        private static final String COL_GOOG = "msg_google_binder";

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            String createMsgs = "CREATE TABLE " + TABLE_MSGS + "(" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_SENDER + " TEXT, " +
                COL_BODY + " TEXT, " +
                COL_TIME + " INTEGER, " +
                COL_SECURE + " TEXT, " +
                COL_CAT + " TEXT, " +
                COL_OTP + " INTEGER, " +
                COL_GOOG + " TEXT)";

            String createPrefs = "CREATE TABLE " + TABLE_PREFS + "(" +
                "pref_key TEXT PRIMARY KEY, pref_val TEXT)";

            db.execSQL(createMsgs);
            db.execSQL(createPrefs);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_MSGS);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_PREFS);
            onCreate(db);
        }

        public void setSetting(String key, String value) {
            SQLiteDatabase db = this.getWritableDatabase();
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put("pref_key", key);
            cv.put("pref_val", value);
            db.replace(TABLE_PREFS, null, cv);
        }

        public String getSetting(String key) {
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.query(TABLE_PREFS, new String[]{"pref_val"}, "pref_key=?", new String[]{key}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String val = cursor.getString(0);
                cursor.close();
                return val;
            }
            if (cursor != null) cursor.close();
            return "";
        }

        public int insertMessage(String sender, String body, long timestamp, String secure, String cat, int isOtp, String binder) {
            SQLiteDatabase db = this.getWritableDatabase();
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(COL_SENDER, sender);
            cv.put(COL_BODY, body);
            cv.put(COL_TIME, timestamp);
            cv.put(COL_SECURE, secure);
            cv.put(COL_CAT, cat);
            cv.put(COL_OTP, isOtp);
            cv.put(COL_GOOG, binder);
            return (int) db.insert(TABLE_MSGS, null, cv);
        }

        public boolean isMessageDuplicate(String sender, String body, long timestamp) {
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.query(TABLE_MSGS, new String[]{COL_ID}, 
                COL_SENDER + "=? AND " + COL_BODY + "=? AND " + COL_TIME + "=?",
                new String[]{sender, body, String.valueOf(timestamp)}, null, null, null);
            boolean exists = (cursor != null && cursor.getCount() > 0);
            if (cursor != null) cursor.close();
            return exists;
        }

        public long getLastMessageTimestamp() {
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.rawQuery("SELECT MAX(" + COL_TIME + ") FROM " + TABLE_MSGS, null);
            if (cursor != null && cursor.moveToFirst()) {
                long maxVal = cursor.getLong(0);
                cursor.close();
                return maxVal;
            }
            if (cursor != null) cursor.close();
            return 0;
        }

        public void updateMessageTag(int msgId, String customTag) {
            SQLiteDatabase db = this.getWritableDatabase();
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(COL_SECURE, customTag);
            db.update(TABLE_MSGS, cv, COL_ID + "=?", new String[]{String.valueOf(msgId)});
        }

        public void deleteMessage(int msgId) {
            SQLiteDatabase db = this.getWritableDatabase();
            db.delete(TABLE_MSGS, COL_ID + "=?", new String[]{String.valueOf(msgId)});
        }

        public void clearAllMessages(String binder) {
            SQLiteDatabase db = this.getWritableDatabase();
            db.delete(TABLE_MSGS, COL_GOOG + "=?", new String[]{binder});
        }

        public int getMessageCountByCategory(String binder, String category) {
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.query(TABLE_MSGS, new String[]{"COUNT(*)"}, 
                COL_GOOG + "=? AND " + COL_CAT + "=?", 
                new String[]{binder, category}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int count = cursor.getInt(0);
                cursor.close();
                return count;
            }
            if (cursor != null) cursor.close();
            return 0;
        }

        public int getTotalMessageCount(String binder) {
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.query(TABLE_MSGS, new String[]{"COUNT(*)"}, 
                COL_GOOG + "=?", new String[]{binder}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int count = cursor.getInt(0);
                cursor.close();
                return count;
            }
            if (cursor != null) cursor.close();
            return 0;
        }

        public String getEncryptedMessageJson(String binder) {
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.query(TABLE_MSGS, null, COL_GOOG + "=?", new String[]{binder}, null, null, COL_TIME + " DESC");
            StringBuilder json = new StringBuilder("[");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    int id = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ID));
                    String sender = cursor.getString(cursor.getColumnIndexOrThrow(COL_SENDER));
                    String body = cursor.getString(cursor.getColumnIndexOrThrow(COL_BODY));
                    long time = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIME));
                    String secure = cursor.getString(cursor.getColumnIndexOrThrow(COL_SECURE));
                    String cat = cursor.getString(cursor.getColumnIndexOrThrow(COL_CAT));
                    int isOtp = cursor.getInt(cursor.getColumnIndexOrThrow(COL_OTP));

                    if (json.length() > 1) json.append(",");
                    json.append("{")
                        .append("\"id\":").append(id).append(",")
                        .append("\"sender\":\"").append(escapeJson(sender)).append("\",")
                        .append("\"body\":\"").append(escapeJson(body)).append("\",")
                        .append("\"timestamp\":").append(time).append(",")
                        .append("\"secure_tag\":\"").append(escapeJson(secure)).append("\",")
                        .append("\"category\":\"").append(escapeJson(cat)).append("\",")
                        .append("\"is_otp\":").append(isOtp)
                        .append("}");
                }
                cursor.close();
            }
            json.append("]");
            return json.toString();
        }

        public void restoreDatabaseFromParsedList(List<Map<String, String>> parsedList, String binder) throws Exception {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete(TABLE_MSGS, COL_GOOG + "=?", new String[]{binder});

                for (Map<String, String> map : parsedList) {
                    String sender = map.containsKey("sender") ? map.get("sender") : "";
                    String body = map.containsKey("body") ? map.get("body") : "";
                    long timestamp = map.containsKey("timestamp") ? Long.parseLong(map.get("timestamp")) : 0;
                    String secure = map.containsKey("secure_tag") ? map.get("secure_tag") : "";
                    String cat = map.containsKey("category") ? map.get("category") : "";
                    int isOtp = map.containsKey("is_otp") ? Integer.parseInt(map.get("is_otp")) : 0;

                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(COL_SENDER, sender);
                    cv.put(COL_BODY, body);
                    cv.put(COL_TIME, timestamp);
                    cv.put(COL_SECURE, secure);
                    cv.put(COL_CAT, cat);
                    cv.put(COL_OTP, isOtp);
                    cv.put(COL_GOOG, binder);
                    db.insert(TABLE_MSGS, null, cv);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
        }
    }
}
