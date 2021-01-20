package com.michael.bci;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.ActivityRecognition;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/*
Michael McMahon
MainActivity tells Android how the app should interact with the user by initializing the activity,
creating a window for the UI, and invokes callback methods corresponding to specific stages of its
lifecycle such as onCreate(), onStart(), onResume(), onPause(), onStop(), onRestart() and onDestroy().
We need to implement the ConnectionCallbacks and OnConnectionFailedListener interfaces to connect to
use Google Play Services for Activity Detection.
*/
public class MainActivity extends AppCompatActivity implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

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
                /* '-' Connect to test signal 1xAmplitude, slow pulse */
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
        mApiClient = new GoogleApiClient.Builder(this)
                .addApi(ActivityRecognition.API)
                .addConnectionCallbacks(this) //this is refer to connectionCallbacks interface implementation.
                .addOnConnectionFailedListener(this) //this is refer to onConnectionFailedListener interface implementation.
                .build();

        mApiClient.connect();
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
        ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(mApiClient,1000,pendingIntent);
    }

    /* Activity Recognition onConnectionSuspended */
    @Override
    public void onConnectionSuspended(int i) {

    }

    /* Activity Recognition onConnectionFailed */
    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }
}
