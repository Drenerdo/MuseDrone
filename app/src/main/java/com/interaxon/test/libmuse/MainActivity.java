/**
 * Example of using libmuse library on android.
 * Interaxon, Inc. 2015
 */

package com.interaxon.test.libmuse;

import java.io.File;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View.OnClickListener;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.interaxon.libmuse.Accelerometer;
import com.interaxon.libmuse.AnnotationData;
import com.interaxon.libmuse.ConnectionState;
import com.interaxon.libmuse.Eeg;
import com.interaxon.libmuse.LibMuseVersion;
import com.interaxon.libmuse.MessageType;
import com.interaxon.libmuse.Muse;
import com.interaxon.libmuse.MuseArtifactPacket;
import com.interaxon.libmuse.MuseConfiguration;
import com.interaxon.libmuse.MuseConnectionListener;
import com.interaxon.libmuse.MuseConnectionPacket;
import com.interaxon.libmuse.MuseDataListener;
import com.interaxon.libmuse.MuseDataPacket;
import com.interaxon.libmuse.MuseDataPacketType;
import com.interaxon.libmuse.MuseFileFactory;
import com.interaxon.libmuse.MuseFileReader;
import com.interaxon.libmuse.MuseFileWriter;
import com.interaxon.libmuse.MuseManager;
import com.interaxon.libmuse.MusePreset;
import com.interaxon.libmuse.MuseVersion;
import com.parrot.arsdk.arcommands.ARCOMMANDS_MINIDRONE_ANIMATIONS_FLIP_DIRECTION_ENUM;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDeviceService;


/**
 * In this simple example MainActivity implements 2 MuseHeadband listeners
 * and updates UI when data from Muse is received. Similarly you can implement
 * listers for other data or register same listener to listen for different type
 * of data.
 * For simplicity we create Listeners as inner classes of MainActivity. We pass
 * reference to MainActivity as we want listeners to update UI thread in this
 * example app.
 * You can also connect multiple muses to the same phone and register same
 * listener to listen for data from different muses. In this case you will
 * have to provide synchronization for data members you are using inside
 * your listener.
 *
 * Usage instructions:
 * 1. Enable bluetooth on your device
 * 2. Pair your device with muse
 * 3. Run this project
 * 4. Press Refresh. It should display all paired Muses in Spinner
 * 5. Make sure Muse headband is waiting for connection and press connect.
 * It may take up to 10 sec in some cases.
 * 6. You should see EEG and accelerometer data as well as connection status,
 * Version information and MuseElements (alpha, beta, theta, delta, gamma waves)
 * on the screen.
 */
public class MainActivity extends Activity implements DeviceControllerListener {
    public DeviceController deviceController;
    public ARDiscoveryDeviceService service;

    private AlertDialog alertDialog;

    public static String EXTRA_DEVICE_SERVICE = "pilotingActivity.extra.device.service";

    @Override
    public void onDisconnect()
    {
        stopDeviceController();
    }

    @Override
    public void onUpdateBattery(final byte percent)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Think I might add some battery information stuff here
            }
        });
    }

    @Override
    protected void onStop()
    {
        stopDeviceController();

        super.onStop();
    }

    private void stopDeviceController()
    {
        if(deviceController != null)
        {
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(MainActivity.this);

            //Setting description
            alertDialogBuilder.setTitle("Disconnecting...");

            alertDialog = alertDialogBuilder.create();

            alertDialog = alertDialogBuilder.create();

            alertDialog.show();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    deviceController.stop();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            alertDialog.dismiss();
                            finish();
                        }
                    });

                }
            }).start();
        }
    }

    @Override
    public void onStart()
    {
        super.onStart();

        if(deviceController != null)
        {
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(MainActivity.this);

            alertDialogBuilder.setTitle("Connecting..");

            alertDialog = alertDialogBuilder.create();

            alertDialog.show();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean failed = false;
                    failed = deviceController.start();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            alertDialog.dismiss();
                        }
                    });

                    if(failed)
                    {
                        finish();
                    }
                    else
                    {
                        Date currentDate = new Date(System.currentTimeMillis());
                        deviceController.sendDate(currentDate);
                        deviceController.sendTime(currentDate);
                    }
                }
            }).start();
        }
    }
    /**
     * Connection listener updates UI with new connection status and logs it.
     */
    class ConnectionListener extends MuseConnectionListener {

        final WeakReference<Activity> activityRef;

        ConnectionListener(final WeakReference<Activity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseConnectionPacket(MuseConnectionPacket p) {
            final ConnectionState current = p.getCurrentConnectionState();
            final String status = p.getPreviousConnectionState().toString() +
                         " -> " + current;
            final String full = "Muse " + p.getSource().getMacAddress() +
                                " " + status;
            Log.i("Muse Headband", full);
            Activity activity = activityRef.get();
            // UI thread is used here only because we need to update
            // TextView values. You don't have to use another thread, unless
            // you want to run disconnect() or connect() from connection packet
            // handler. In this case creating another thread is required.
        }
    }

    /**
     * Data listener will be registered to listen for: Accelerometer,
     * Eeg and Relative Alpha bandpower packets. In all cases we will
     * update UI with new values.
     * We also will log message if Artifact packets contains "blink" flag.
     * DataListener methods will be called from execution thread. If you are
     * implementing "serious" processing algorithms inside those listeners,
     * consider to create another thread.
     */
    class DataListener extends MuseDataListener {

        final WeakReference<Activity> activityRef;

        DataListener(final WeakReference<Activity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseDataPacket(MuseDataPacket p) {
            switch(p.getPacketType()){
                case EEG:
                    updateEeg(p.getValues());
                    break;
                case ACCELEROMETER:
                    updateAccelerometer(p.getValues());
                    break;
                default:
                    break;
            }
        }

        @Override
        public void receiveMuseArtifactPacket(MuseArtifactPacket p) {
            if (p.getHeadbandOn() && p.getBlink()) {
                Log.i("Artifacts", "blink");
            }

            if(p.getBlink()) {
                if(running) {
                    textViewStatus.setText("blink:down");
                }
            } else if(p.getJawClench()){
                if(running) {
                    textViewStatus.setText("clench:up");
                }
            }
        }

        private void updateAccelerometer(final ArrayList<Double> data) {
            Activity activity = activityRef.get();
            if(activity != null) {
                activity.runOnUiThread(new Runnable(){
                    @Override
                    public void run() {
                        TextView acc_x = (TextView) findViewById(R.id.acc_x);
                        TextView acc_y = (TextView) findViewById(R.id.acc_y);
                        TextView acc_z = (TextView) findViewById(R.id.acc_z);

                        Double x = data.get(Accelerometer.FORWARD_BACKWARD.ordinal());
                        Double y = data.get(Accelerometer.UP_DOWN.ordinal());
                        Double z = data.get(Accelerometer.LEFT_RIGHT.ordinal());

                        if (x < -500) {
                            back();
                        } else if (x > 500) {
                            forward();
                        }

                        if(z > 600) {
                            right();
                        } else if(z < -250) {
                            left();
                        }

                        acc_x.setText(String.format(
                            "%6.2f", x));
                        acc_y.setText(String.format(
                            "%6.2f", y));
                        acc_z.setText(String.format(
                            "6.2f", z));
                    }
                });
            }
        }

        private int avg(double[] x) {
            double sum = 0;
            for(double xx : x){
                sum+=xx;
            }
            return(int)sum/x.length;
        }
        private double[][] _eeg = new double[4][4];
        private boolean hitLow = false;

        private void updateEeg(final ArrayList<Double> data) {
            Activity activity = activityRef.get();
            if (activity != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                         TextView tp9 = (TextView) findViewById(R.id.eeg_tp9);
                         TextView fp1 = (TextView) findViewById(R.id.eeg_fp1);
                         TextView fp2 = (TextView) findViewById(R.id.eeg_fp2);
                         TextView tp10 = (TextView) findViewById(R.id.eeg_tp10);

                         _eeg[0] = _eeg[1];
                         _eeg[1] = _eeg[2];
                         _eeg[2] = _eeg[3];
                         _eeg[3] = new double[] {
                            data.get(Eeg.TP9.ordinal()),
                            data.get(Eeg.FP1.ordinal()),
                            data.get(Eeg.FP2.ordinal()),
                            data.get(Eeg.TP10.ordinal())
                        };

                        int avg = avg(new double[] { avg(_eeg[0]), avg(_eeg[1]), avg(_eeg[2]), avg(_eeg[3]) });

                        if(avg < 400) {
                            hitLow = false;
                        }

                        if(avg > 1000 && hitLow){
                            hitLow = false;
                        }

                        tp9.setText(String.format(
                                "%6.2f", _eeg[3][0]));
                        fp1.setText(String.format(
                                "%6.2f", _eeg[3][1]));
                        fp2.setText(String.format(
                                "%6.2f", _eeg[3][2]));
                        tp10.setText(String.format(
                                "%6.2f", _eeg[3][3]));
                    }
                });
            }
        }
    }

    private Muse muse = null;
    private ConnectionListener connectionListener = null;
    private DataListener dataListener = null;
    private boolean dataTransmission = true;
    private MuseFileWriter fileWriter = null;

    private Button emergencyBt;
    private Button takeoffBt;
    private Button landingBt;
    private Button upButton;
    private Button forwardBt;
    private Button backBt;
    private Button rollLeftBt;
    private Button rollRightBt;
    private Button toggleHeadband;
    private TextView textViewStatus;
    private boolean running = false;

    public MainActivity() {
        // Create listeners and pass reference to activity to them
        WeakReference<Activity> weakActivity =
                                new WeakReference<Activity>(this);

        connectionListener = new ConnectionListener(weakActivity);
        dataListener = new DataListener(weakActivity);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        final Spinner musesSpinner = (Spinner) findViewById(R.id.muses_spinner);
        Button refreshButton = (Button) findViewById(R.id.refresh);
        refreshButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                MuseManager.refreshPairedMuses();
                List<Muse> pairedMuses = MuseManager.getPairedMuses();
                List<String> spinnerItems = new ArrayList<String>();
                for (Muse m : pairedMuses) {
                    String dev_id = m.getName() + "-" + m.getMacAddress();
                    Log.i("Muse Headband", dev_id);
                    spinnerItems.add(dev_id);
                }
                ArrayAdapter<String> adapterArray = new ArrayAdapter<String>(
                        MainActivity.this, android.R.layout.simple_spinner_item, spinnerItems);
                musesSpinner.setAdapter(adapterArray);
            }
        });
        Button connectButton = (Button) findViewById(R.id.connect);
        connectButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                List<Muse> pairedMuses = MuseManager.getPairedMuses();
                if (pairedMuses.size() < 1 ||
                        musesSpinner.getAdapter().getCount() < 1) {
                    Log.w("Muse Headband", "There is nothing to connect to");
                }
                else {
                    muse = pairedMuses.get(musesSpinner.getSelectedItemPosition());
                    ConnectionState state = muse.getConnectionState();
                    if (state == ConnectionState.CONNECTED ||
                            state == ConnectionState.CONNECTING) {
                        Log.w("Muse Headband", "doesn't make sense to connect second time to the same muse");
                        return;
                    }
                    configure_library();
                    /**
                     * In most cases libmuse native library takes care about
                     * exceptions and recovery mechanism, but native code still
                     * may throw in some unexpected situations (like bad bluetooth
                     * connection). Print all exceptions here.
                     */
                    try {
                        muse.runAsynchronously();
                    } catch (Exception e) {
                        Log.e("Muse Headband", e.toString());
                    }
                }
            }
        });
        Button disconnectButton = (Button) findViewById(R.id.disconnect);
        disconnectButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (muse != null) {
                    muse.disconnect(true);
                }
            }
        });

        Button pauseButton = (Button) findViewById(R.id.pause);
        pauseButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dataTransmission = !dataTransmission;
                if(muse != null) {
                    muse.enableDataTransmission(dataTransmission);
                }
            }
        });
        Log.i("Muse Headband", "libmuse version=" + LibMuseVersion.SDK_VERSION);

        Intent intent = getIntent();
        service = intent.getParcelableExtra(EXTRA_DEVICE_SERVICE);

        deviceController = new DeviceController(this, service);
        deviceController.setListener(this);

        emergencyBt = (Button) findViewById(R.id.emergencyBt);
        emergencyBt.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v)
            {
                if(deviceController != null)
                {
                    deviceController.sendEmergency();
                }
            }
        });

        takeoffBt = (Button) findViewById(R.id.takeoffBt);
        takeoffBt.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v)
            {
                if(deviceController != null)
                {
                    deviceController.sendTakeoff();
                }
            }
        });
        landingBt = (Button) findViewById(R.id.landingBt);
        landingBt.setOnClickListener(new View.OnClickListener()
        {
            public void onClick(View v)
            {
                if(deviceController != null)
                {
                    deviceController.sendLanding();
                }
            }
        });

        forwardBt = (Button) findViewById(R.id.forwardBt);
        forwardBt.setOnTouchListener(new View.OnTouchListener(){
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                switch (event.getAction())
                {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        if(deviceController != null)
                        {
                            deviceController.setPitch((byte) 50);
                            deviceController.setFlag((byte) 1);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if(deviceController != null)
                        {
                            deviceController.setPitch((byte) 0);
                            deviceController.setFlag((byte) 0);
                        }
                        break;
                    default:
                        break;
                }
                return true;
            }
        });

        backBt = (Button) findViewById(R.id.backBt);
        backBt.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                switch (event.getAction())
                {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        if(deviceController != null)
                        {
                            deviceController.setPitch((byte)-50);
                            deviceController.setFlag((byte)1);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if(deviceController != null)
                        {
                            deviceController.setPitch((byte)0);
                            deviceController.setFlag((byte)0);
                        }
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        rollLeftBt = (Button)findViewById(R.id.rollLeftBt);
        rollLeftBt.setOnTouchListener(new View.OnTouchListener(){
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                switch (event.getAction())
                {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        if(deviceController != null)
                        {
                            deviceController.setRoll((byte) -50);
                            deviceController.setFlag((byte) 1);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if(deviceController != null)
                        {
                            deviceController.setRoll((byte)0);
                            deviceController.setFlag((byte)0);
                        }
                        break;
                    default:
                        break;
                }

                return true;
            }
        });

        rollRightBt = (Button) findViewById(R.id.rollRightBt);
        rollRightBt.setOnTouchListener(new View.OnTouchListener(){
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                switch (event.getAction())
                {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        if(deviceController != null)
                        {
                            deviceController.setRoll((byte)50);
                            deviceController.setFlag((byte)1);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if(deviceController != null)
                        {
                            deviceController.setRoll((byte)0);
                            deviceController.setFlag((byte)0);
                        }
                        break;

                    default:
                        break;
                }
                return true;
            }
        });

        toggleHeadband = (Button)findViewById(R.id.useHeadband);
        textViewStatus = (TextView)findViewById(R.id.textViewStatus);

        textViewStatus.setText("Not running");
        toggleHeadband.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                flip();
            }
        });

        upButton = (Button)findViewById(R.id.upButton);
        upButton.setOnTouchListener(new View.OnTouchListener(){
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                switch(event.getAction())
                {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        if(deviceController != null)
                        {
                            deviceController.setGaz((byte)50);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if(deviceController != null)
                        {
                            deviceController.setGaz((byte)0);
                        }
                        break;

                    default:
                        break;

                }
                return true;
            }
        });
    }

    private void flip() {
        if(deviceController != null){
            deviceController.flip(ARCOMMANDS_MINIDRONE_ANIMATIONS_FLIP_DIRECTION_ENUM.ARCOMMANDS_MINIDRONE_ANIMATIONS_FLIP_DIRECTION_FRONT);
        }
    }

    private void forward() {
        if(deviceController != null)
        {
            deviceController.setPitch((byte)50);
            deviceController.setFlag((byte) 1);

            new android.os.Handler().postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            deviceController.setPitch((byte) 0);
                            deviceController.setFlag((byte) 0);
                        }
                    },
                    100);
        }
    }

    private void back() {
        if(deviceController != null)
        {
            deviceController.setPitch((byte)-50);
            deviceController.setFlag((byte) 1);

            new android.os.Handler().postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            deviceController.setPitch((byte)0);
                            deviceController.setFlag((byte)0);

                        }
                    },
                    100);
        }
    }

    private void right() {
        if(deviceController != null)
        {
            deviceController.setRoll((byte) 50);
            deviceController.setFlag((byte) 1);

            new android.os.Handler().postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            deviceController.setRoll((byte) 0);
                            deviceController.setFlag((byte)0);
                        }
                    },
                    100);
        }
    }

    private void left() {
        if(deviceController != null)
        {
            deviceController.setRoll((byte) -50);
            deviceController.setFlag((byte) 1);

            new android.os.Handler().postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            deviceController.setRoll((byte)0);
                            deviceController.setFlag((byte)0);
                        }
                    },
                    100);
        }
    }

    private void up() {
        if(deviceController != null)
        {
            deviceController.setGaz((byte) 50);
            new android.os.Handler().postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            deviceController.setGaz((byte) 0);
                        }
                    },
                    100);
        }
    }

    private void down() {
        if(deviceController != null)
        {
            deviceController.setGaz((byte)-50);
            new android.os.Handler().postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            deviceController.setGaz((byte)0);
                        }
                    },
                    100);
        }
    }



    private void configure_library() {
        muse.registerConnectionListener(connectionListener);
        muse.registerDataListener(dataListener,
                                  MuseDataPacketType.ACCELEROMETER);
        muse.registerDataListener(dataListener,
                                  MuseDataPacketType.EEG);
        muse.registerDataListener(dataListener,
                                  MuseDataPacketType.ALPHA_RELATIVE);
        muse.registerDataListener(dataListener,
                                  MuseDataPacketType.ARTIFACTS);
        muse.registerDataListener(dataListener,
                                  MuseDataPacketType.BATTERY);
        muse.setPreset(MusePreset.PRESET_14);
        muse.enableDataTransmission(dataTransmission);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
