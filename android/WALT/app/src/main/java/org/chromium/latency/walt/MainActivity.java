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

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "WALT";

    private Toolbar toolbar;
    LocalBroadcastManager broadcastManager;
    public SimpleLogger logger = new SimpleLogger();
    public ClockManager clockManager;
    public Menu mMenu;

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler(new LoggingExceptionHandler());
        setContentView(R.layout.activity_main);

        // App bar
        toolbar = (Toolbar) findViewById(R.id.toolbar_main);
        setSupportActionBar(toolbar);

        clockManager = new ClockManager(this, logger);

        // Connect and sync clocks, but a bit later as it takes time
        handler.post(new Runnable() {
            @Override
            public void run() {
                clockManager.connect();
                // TODO: Do we need to sync here? Sync is done before each measurement.
                // Ideally we need to sync here and check whether the process works ok
                // on Nexus 9 it sometimes hangs for ~5 seconds.
                clockManager.syncClock();
            }
        });

        // Create front page fragment
        FrontPageFragment frontPageFragment = new FrontPageFragment();
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.add(R.id.fragment_container, frontPageFragment);
        transaction.commit();

        broadcastManager = LocalBroadcastManager.getInstance(this);
        logger.setBroadcastManager(broadcastManager);

        // Add basic device info to the log
        logger.log("DEVICE INFO");
        logger.log("  os.version=" + System.getProperty("os.version"));
        logger.log("  Build.SDK_INT=" + Build.VERSION.SDK_INT);
        logger.log("  Build.DEVICE=" + Build.DEVICE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        mMenu = menu;
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
        // Remove the back arrow from the toolbar because now we are at the top
        getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        toolbar.setTitle(R.string.app_name);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        int id = item.getItemId();
        Log.i(TAG, "Toolbar button: " + item.getTitle());

        switch (item.getItemId()) {
            case R.id.action_help:
                return true;
            case R.id.action_share:
                String filepath = saveLogToFile();
                shareLogFile(filepath);
                return true;
            case R.id.action_upload:
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
        transaction.addToBackStack(null);
        transaction.commit();
    }

    public void onClickClockSync(View view) {
        DiagnosticsFragment diagnosticsFragment = new DiagnosticsFragment();
        switchScreen(diagnosticsFragment, "Diagnostics");
    }

    public void onClickTapLatency(View view) {
        TapLatencyFragment newFragment = new TapLatencyFragment();
        switchScreen(newFragment, "Tap Latency");
    }

    public void onClickScreenResponse(View view) {
        ScreenResponseFragment newFragment = new ScreenResponseFragment();
        switchScreen(newFragment, "Screen Response");
    }

    public void onClickAudio(View view) {
        AudioFragment newFragment = new AudioFragment();
        switchScreen(newFragment, "Audio Output");
    }

    public void onClickDragLatency(View view) {
        DragLatencyFragment newFragment = new DragLatencyFragment();
        switchScreen(newFragment, "Drag Latency");
    }

    public void onClickOpenLog(View view) {
        LogFragment logFragment = new LogFragment();
        // mMenu.findItem(R.id.action_help).setVisible(false);
        switchScreen(logFragment, "Log");
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Handlers for diagnostics menu clicks
    ////////////////////////////////////////////////////////////////////////////////////////////////
    public void onClickReconnect(View view) {
        clockManager.connect();
    }

    public void onClickPing(View view) {
        // TODO: this prints "ping reply" even if there is no connection, do something about it
        long t1 = clockManager.micros();
        String s = clockManager.sendReceive('P');
        long dt = clockManager.micros() - t1;
        logger.log(String.format(
                "Ping reply in %.1fms: \"%s\"", dt / 1000.,
                s.trim()
        ));
    }

    public void onClickSendT(View view) {
        clockManager.sendByte('T');
    }

    public void onClickStartListener(View view) {
        if (clockManager.isListenerStopped()) {
            clockManager.startUsbListener();
        } else {
            clockManager.stopUsbListener();
        }
    }

    public void onClickSync(View view) {
        clockManager.syncClock();
    }

    public void onClickCheckDrift(View view) {
        clockManager.updateBounds();
        int minE = clockManager.getMinE();
        int maxE = clockManager.getMaxE();
        logger.log(String.format("Remote clock delayed between %d and %d us", minE, maxE));
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
            Toast.makeText(MainActivity.this, "There are no email clients installed.", Toast.LENGTH_SHORT).show();
        }
    }

}
