package com.michael.bci;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.tasks.Task;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.google.android.gms.common.api.GoogleApiClient.*;

/*
Michael McMahon
MainActivity tells Android how the app should interact with the user by initializing the activity,
creating a window for the UI, and invokes callback methods corresponding to specific stages of its
lifecycle such as onCreate(), onStart(), onResume(), onPause(), onStop(), onRestart() and onDestroy().
We need to implement the ConnectionCallbacks and OnConnectionFailedListener interfaces to connect to
use Google Play Services for Activity Detection.
*/
public class MainActivity extends AppCompatActivity implements ConnectionCallbacks, OnConnectionFailedListener {

    final static private String TAG = "BCI_MAIN";

    private Button toggleStreamingButton;

    /*
    Member variable of type GoogleApiClient to keep a reference to the API client.
     */
    public GoogleApiClient mApiClient;
    private boolean streaming = false;

    @Override
    /*
    onCreate() is called when the activity is first created to do all static set up: create views,
    bind data to lists, etc. This method also provides a Bundle containing the activity's previously
    frozen state, if there was one.
    For OpenBCI commands reference https://docs.openbci.com/docs/02Cyton/CytonSDK
     */
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button button = findViewById(R.id.button);
        toggleStreamingButton = findViewById(R.id.button2);
        CheckBox testSignalCheckBox = findViewById(R.id.checkBox);

        /*
        Turn all available channels on, and connect them to internal test signal.
        These are useful for self test and calibration.
        0 Connect to internal GND (VDD - VSS)
        - Connect to test signal 1xAmplitude, slow pulse
        = Connect to test signal 1xAmplitude, fast pulse
        p Connect to DC signal
        [ Connect to test signal 2xAmplitude, slow pulse
        ] Connect to test signal 2xAmplitude, fast pulse
        */
        testSignalCheckBox.setChecked(false);
        testSignalCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String cmd;
            if (isChecked) {
                /* x (CHANNEL, POWER_DOWN, GAIN_SET, INPUT_TYPE_SET, BIAS_SET, SRB2_SET, SRB1_SET) X
                x1065110Xx2065110Xx3065110Xx4065110Xx5065110Xx6065110Xx7065110Xx8065110X
                Set A for write to SD Card*/
                cmd = "-";
            } else {
                /* Send 'd' to set all channels to default */
                cmd = "d";
            }
            Log.e(TAG, "Send Command - " + cmd);
            Intent intent = new Intent(BciService.SEND_COMMAND);
            intent.putExtra(BciService.COMMAND_EXTRA, cmd);
            sendBroadcast(intent);
        });

        /* Send 'v' to soft reset for the Board peripherals */
        button.setOnClickListener(v -> {
            Intent intent = new Intent(BciService.SEND_COMMAND);
            intent.putExtra(BciService.COMMAND_EXTRA, "v");
            Log.e(TAG, "Send Command - v");
            sendBroadcast(intent);
        });

        /* Toggle Streaming Button to start/stop streaming EEG data */
        toggleStreamingButton.setOnClickListener((View v) -> {
            if (!streaming) {
                /* To begin data streaming, transmit a single ASCII b */
                toggleStreamingButton.setText("stop streaming");
                streaming = true;
                // Intent intent = new Intent(String.valueOf(new BciService.mReceiver()));
                Intent intent = new Intent(BciService.SEND_COMMAND);
                intent.putExtra(BciService.COMMAND_EXTRA, "b");
                Log.e(TAG, "Send Command - b");
                sendBroadcast(intent);

                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACTIVITY_RECOGNITION) ==
                        PackageManager.PERMISSION_GRANTED) {
                    // You can use the API that requires the permission.
                    mApiClient.connect();
                } else {
                    // You can directly ask for the permission.
                    // The registered ActivityResultCallback gets the result of this request.
                    requestPermissionLauncher.launch(
                            Manifest.permission.ACTIVITY_RECOGNITION);
                }
            } else {
                /* To end data streaming, transmit a single ASCII s */
                toggleStreamingButton.setText("start streaming");
                streaming = false;
                Intent intent = new Intent(BciService.SEND_COMMAND);
                intent.putExtra(BciService.COMMAND_EXTRA, "s");
                Log.e(TAG, "Send Command - s");
                sendBroadcast(intent);
            }
        });

        IntentFilter filter = new IntentFilter(); //set an IntentFilter to register our BroadcastReceiver.
        filter.addAction(BciService.DATA_RECEIVED_INTENT); //custom action intent targeting mBroadCastReceiver
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED); //custom action intent targeting mBroadCastReceiver
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED); //custom action intent targeting mBroadCastReceiver
        launchOpenBciService(); //Launch the Bci Service

        /*
        Activity Recognition Setup - After implementing the required interfaces for the GoogleApiClient above,
        we initialize the client and connect to Google Play Services by requesting the ActivityRecognition.API
        and associating our listeners with the GoogleApiClient instance.
         */

        mApiClient = new Builder(this)
                .addApi(ActivityRecognition.API)
                .addConnectionCallbacks(this) //this is refer to connectionCallbacks interface implementation.
                .addOnConnectionFailedListener(this) //this is refer to onConnectionFailedListener interface implementation.
                .build();

        //mApiClient.connect();

    }

    /* Launch the OpenBCI Service */
    private void launchOpenBciService() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice usbDevice = null;

        HashMap<String, UsbDevice> deviceList = null;
        if (usbManager != null) {
            deviceList = usbManager.getDeviceList();
        }

        if (deviceList != null) {
            for (Map.Entry<String, UsbDevice> entry : deviceList.entrySet()) {
                UsbDevice device = entry.getValue();
                if (device.getVendorId() == BciService.VENDOR_ID) {
                    usbDevice = device;
                }
            }
        }

        if (null == usbDevice) {
            Log.d(TAG, "no device found");
        } else {
            Intent intent = new Intent(getApplicationContext(), BciService.class);
            intent.putExtra(UsbManager.EXTRA_DEVICE, usbDevice);
            startService(intent); //Start the BciService
        }
    }

    // Register the permissions callback, which handles the user's response to the
// system permissions dialog. Save the return value, an instance of
// ActivityResultLauncher, as an instance variable.
    private ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    // Permission is granted. Continue the action or workflow in your
                    // app.
                    mApiClient.connect();
                } else {
                    // Explain to the user that the feature is unavailable because the
                    // features requires a permission that the user has denied. At the
                    // same time, respect the user's decision. Don't link to system
                    // settings in an effort to convince the user to change their
                    // decision.
                }
            });

    /*
    Once the GoogleApiClient instance has connected on onCreate above, this onConnected() is called and
    we create a PendingIntent that goes to the IntentService created in the ActivityRecognizedService class,
    and passes it to the ActivityRecognitionApi. We can set an interval for how often the API should check
    the user's activity e.g. value of 1000, or one second.
     */
    @Override
    public void onConnected(@Nullable Bundle bundle) {
        //Before request API request, GoogleApiClient must be in connected mode.
        Intent intent = new Intent(this,ActivityRecognizedService.class);
        PendingIntent pendingIntent = PendingIntent.getService(this,1,intent,PendingIntent.FLAG_UPDATE_CURRENT);
        ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(mApiClient,10000, pendingIntent);
        //ActivityRecognitionClient activityRecognitionClient = ActivityRecognition.getClient(this);
        //activityRecognitionClient.requestActivityUpdates(1000, pendingIntent);
    }

    /* Activity Recognition onConnectionSuspended */
    @Override
    public void onConnectionSuspended(int i) {
        Log.i(TAG, "Connection suspended");
        mApiClient.connect();
    }

    /* Activity Recognition onConnectionFailed */
    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.i(TAG, "Connection failed: ConnectionResult.getErrorCode() = " + connectionResult.getErrorCode());
    }

}
