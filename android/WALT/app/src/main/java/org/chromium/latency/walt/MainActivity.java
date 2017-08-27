/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.chromium.latency.walt;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.Loader;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import org.chromium.latency.walt.programmer.Programmer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.Locale;

import static org.chromium.latency.walt.Utils.getBooleanPreference;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "WALT";
    private static final int PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE_SHARE_LOG = 2;
    private static final int PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE_SYSTRACE = 3;

    private Toolbar toolbar;
    LocalBroadcastManager broadcastManager;
    private SimpleLogger logger;
    private WaltDevice waltDevice;
    public Menu menu;

    public Handler handler = new Handler();


    /**
     * A method to display exceptions on screen. This is very useful because our USB port is taken
     * and we often need to debug without adb.
     * Based on this article:
     * https://trivedihardik.wordpress.com/2011/08/20/how-to-avoid-force-close-error-in-android/
     */
    public class LoggingExceptionHandler implements java.lang.Thread.UncaughtExceptionHandler {

        @Override
        public void uncaughtException(Thread thread, Throwable ex) {
            StringWriter stackTrace = new StringWriter();
            ex.printStackTrace(new PrintWriter(stackTrace));
            String msg = "WALT crashed with the following exception:\n" + stackTrace;

            // Fire a new activity showing the stack trace
            Intent intent = new Intent(MainActivity.this, CrashLogActivity.class);
            intent.putExtra("crash_log", msg);
            MainActivity.this.startActivity(intent);

            // Terminate this process
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        final UsbDevice usbDevice;
        Intent intent = getIntent();
        if (intent != null && intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            setIntent(null); // done with the intent
            usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        } else {
            usbDevice = null;
        }

        // Connect and sync clocks, but a bit later as it takes time
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (usbDevice == null) {
                    waltDevice.connect();
                } else {
                    waltDevice.connect(usbDevice);
                }
            }
        }, 1000);

        if (intent != null && AutoRunFragment.TEST_ACTION.equals(intent.getAction())) {
            getSupportFragmentManager().popBackStack("Automated Test",
                    FragmentManager.POP_BACK_STACK_INCLUSIVE);
            Fragment autoRunFragment = new AutoRunFragment();
            autoRunFragment.setArguments(intent.getExtras());
            switchScreen(autoRunFragment, "Automated Test");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler(new LoggingExceptionHandler());
        setContentView(R.layout.activity_main);

        // App bar
        toolbar = (Toolbar) findViewById(R.id.toolbar_main);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(new FragmentManager.OnBackStackChangedListener() {
            @Override
            public void onBackStackChanged() {
                int stackTopIndex = getSupportFragmentManager().getBackStackEntryCount() - 1;
                if (stackTopIndex >= 0) {
                    toolbar.setTitle(getSupportFragmentManager().getBackStackEntryAt(stackTopIndex).getName());
                } else {
                    toolbar.setTitle(R.string.app_name);
                    getSupportActionBar().setDisplayHomeAsUpEnabled(false);
                    // Disable fullscreen mode
                    getSupportActionBar().show();
                    getWindow().getDecorView().setSystemUiVisibility(0);
                }
            }
        });

        waltDevice = WaltDevice.getInstance(this);

        // Create front page fragment
        FrontPageFragment frontPageFragment = new FrontPageFragment();
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.add(R.id.fragment_container, frontPageFragment);
        transaction.commit();

        logger = SimpleLogger.getInstance(this);
        broadcastManager = LocalBroadcastManager.getInstance(this);

        // Add basic version and device info to the log
        logger.log(String.format("WALT v%s  (versionCode=%d)",
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        logger.log("WALT protocol version " + WaltDevice.PROTOCOL_VERSION);
        logger.log("DEVICE INFO:");
        logger.log("  " + Build.FINGERPRINT);
        logger.log("  Build.SDK_INT=" + Build.VERSION.SDK_INT);
        logger.log("  os.version=" + System.getProperty("os.version"));

        // Set volume buttons to control media volume
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        requestSystraceWritePermission();
        // Allow network operations on the main thread
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        this.menu = menu;
        return true;
    }

    public void toast(String msg) {
        logger.log(msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        // Go back when the back or up button on toolbar is clicked
        getSupportFragmentManager().popBackStack();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        Log.i(TAG, "Toolbar button: " + item.getTitle());

        switch (item.getItemId()) {
            case R.id.action_help:
                return true;
            case R.id.action_share:
                attemptSaveAndShareLog();
                return true;
            case R.id.action_upload:
                showUploadLogDialog();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Handlers for main menu clicks
    ////////////////////////////////////////////////////////////////////////////////////////////////

    private void switchScreen(Fragment newFragment, String title) {
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setTitle(title);
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, newFragment);
        transaction.addToBackStack(title);
        transaction.commit();
    }

    public void onClickClockSync(View view) {
        DiagnosticsFragment diagnosticsFragment = new DiagnosticsFragment();
        switchScreen(diagnosticsFragment, "Diagnostics");
    }

    public void onClickTapLatency(View view) {
        TapLatencyFragment newFragment = new TapLatencyFragment();
        requestSystraceWritePermission();
        switchScreen(newFragment, "Tap Latency");
    }

    public void onClickScreenResponse(View view) {
        ScreenResponseFragment newFragment = new ScreenResponseFragment();
        requestSystraceWritePermission();
        switchScreen(newFragment, "Screen Response");
    }

    public void onClickAudio(View view) {
        AudioFragment newFragment = new AudioFragment();
        switchScreen(newFragment, "Audio Latency");
    }

    public void onClickMIDI(View view) {
        if (MidiFragment.hasMidi(this)) {
            MidiFragment newFragment = new MidiFragment();
            switchScreen(newFragment, "MIDI Latency");
        } else {
            toast("This device does not support MIDI");
        }
    }

    public void onClickDragLatency(View view) {
        DragLatencyFragment newFragment = new DragLatencyFragment();
        switchScreen(newFragment, "Drag Latency");
    }

    public void onClickAccelerometer(View view) {
        AccelerometerFragment newFragment = new AccelerometerFragment();
        switchScreen(newFragment, "Accelerometer Latency");
    }

    public void onClickOpenLog(View view) {
        LogFragment logFragment = new LogFragment();
        // menu.findItem(R.id.action_help).setVisible(false);
        switchScreen(logFragment, "Log");
    }

    public void onClickOpenAbout(View view) {
        AboutFragment aboutFragment = new AboutFragment();
        switchScreen(aboutFragment, "About");
    }

    public void onClickOpenSettings(View view) {
        SettingsFragment settingsFragment = new SettingsFragment();
        switchScreen(settingsFragment, "Settings");
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Handlers for diagnostics menu clicks
    ////////////////////////////////////////////////////////////////////////////////////////////////
    public void onClickReconnect(View view) {
        waltDevice.connect();
    }

    public void onClickPing(View view) {
        long t1 = waltDevice.clock.micros();
        try {
            waltDevice.command(WaltDevice.CMD_PING);
            long dt = waltDevice.clock.micros() - t1;
            logger.log(String.format(Locale.US,
                    "Ping reply in %.1fms", dt / 1000.
            ));
        } catch (IOException e) {
            logger.log("Error sending ping: " + e.getMessage());
        }
    }

    public void onClickStartListener(View view) {
        if (waltDevice.isListenerStopped()) {
            try {
                waltDevice.startListener();
            } catch (IOException e) {
                logger.log("Error starting USB listener: " + e.getMessage());
            }
        } else {
            waltDevice.stopListener();
        }
    }

    public void onClickSync(View view) {
        try {
            waltDevice.syncClock();
        } catch (IOException e) {
            logger.log("Error syncing clocks: " + e.getMessage());
        }
    }

    public void onClickCheckDrift(View view) {
        waltDevice.checkDrift();
    }

    public void onClickProgram(View view) {
        if (waltDevice.isConnected()) {
            // show dialog telling user to first press white button
            final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Press white button")
                .setMessage("Please press the white button on the WALT device.")
                .setCancelable(false)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {}
                }).show();

            waltDevice.setConnectionStateListener(new WaltConnection.ConnectionStateListener() {
                @Override
                public void onConnect() {}

                @Override
                public void onDisconnect() {
                    dialog.cancel();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            new Programmer(MainActivity.this).program();
                        }
                    }, 1000);
                }
            });
        } else {
            new Programmer(this).program();
        }
    }

    private void attemptSaveAndShareLog() {
        int currentPermission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (currentPermission == PackageManager.PERMISSION_GRANTED) {
            String filePath = saveLogToFile();
            shareLogFile(filePath);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE_SHARE_LOG);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        final boolean isPermissionGranted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!isPermissionGranted) {
            logger.log("Could not get permission to write file to storage");
            return;
        }
        switch (requestCode) {
            case PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE_SHARE_LOG:
                attemptSaveAndShareLog();
                break;
        }
    }

    public String saveLogToFile() {

        // Save to file to later fire an Intent.ACTION_SEND
        // This allows to either send the file as email attachment
        // or upload it to Drive.

        // The permissions for attachments are a mess, writing world readable files
        // is frowned upon, but deliberately giving permissions as part of the intent is
        // way too cumbersome.

        String fname = "qstep_log.txt";
        // A reasonable world readable location,on many phones it's /storage/emulated/Documents
        // TODO: make this location configurable?
        File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File file = null;
        FileOutputStream outStream = null;

        Date now = new Date();
        logger.log("Saving log to:\n" + path.getPath() + "/" + fname);
        logger.log("On: " + now.toString());

        try {
            if (!path.exists()) {
                path.mkdirs();
            }
            file = new File(path, fname);
            outStream = new FileOutputStream(file);
            outStream.write(logger.getLogText().getBytes());

            outStream.close();
            logger.log("Log saved");
        } catch (Exception e) {
            e.printStackTrace();
            logger.log("Exception:\n" + e.getMessage());
        }
        return file.getPath();
    }

    public void shareLogFile(String filepath) {
        File file = new File(filepath);
        logger.log("Firing Intent.ACTION_SEND for file:");
        logger.log(file.getPath());

        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");

        i.putExtra(Intent.EXTRA_SUBJECT, "WALT log");
        i.putExtra(Intent.EXTRA_TEXT, "Attaching log file " + file.getPath());
        i.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));

        try {
            startActivity(Intent.createChooser(i, "Send mail..."));
        } catch (android.content.ActivityNotFoundException ex) {
            toast("There are no email clients installed.");
        }
    }

    private static boolean startsWithHttp(String url) {
        return url.toLowerCase().startsWith("http://") || url.toLowerCase().startsWith("https://");
    }

    private void showUploadLogDialog() {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Upload log to URL")
                .setView(R.layout.dialog_upload)
                .setPositiveButton("Upload", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {}
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {}
                })
                .show();
        final EditText editText = (EditText) dialog.findViewById(R.id.edit_text);
        editText.setText(Utils.getStringPreference(
                MainActivity.this, R.string.preference_log_url, ""));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).
                setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                View progress = dialog.findViewById(R.id.progress_bar);
                String urlString = editText.getText().toString();
                if (!startsWithHttp(urlString)) {
                    urlString = "http://" + urlString;
                }
                editText.setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
                LogUploader uploader = new LogUploader(MainActivity.this, urlString);
                final String finalUrlString = urlString;
                uploader.registerListener(1, new Loader.OnLoadCompleteListener<Integer>() {
                    @Override
                    public void onLoadComplete(Loader<Integer> loader, Integer data) {
                        dialog.cancel();
                        if (data == -1) {
                            Toast.makeText(MainActivity.this,
                                    "Failed to upload log", Toast.LENGTH_SHORT).show();
                            return;
                        } else if (data / 100 == 2) {
                            Toast.makeText(MainActivity.this,
                                    "Log successfully uploaded", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "Failed to upload log. Server returned status code " + data,
                                    Toast.LENGTH_SHORT).show();
                        }
                        SharedPreferences preferences = PreferenceManager
                                .getDefaultSharedPreferences(MainActivity.this);
                        preferences.edit().putString(
                                getString(R.string.preference_log_url), finalUrlString).apply();
                    }
                });
                uploader.startUpload();
            }
        });
    }

    private void requestSystraceWritePermission() {
        if (getBooleanPreference(this, R.string.preference_systrace, true)) {
            int currentPermission = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (currentPermission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE_SYSTRACE);
            }
        }
    }

}
