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

public class MainActivity extends AppCompatActivity implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    final static private String TAG = "BCI_SERVICE 1";

    private Button toggleStreamingButton;
    private Button recordButton;

    public GoogleApiClient mApiClient;
    private boolean streaming = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button button = findViewById(R.id.button);
        toggleStreamingButton = findViewById(R.id.button2);
        CheckBox testSignalCheckBox = findViewById(R.id.checkBox);

        testSignalCheckBox.setChecked(false);
        testSignalCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String cmd = "";
            if (isChecked) {
                cmd = "-";
            } else {
                cmd = "d";
            }
            Log.e(TAG, "Send Command - " + cmd);
            Intent intent = new Intent(BciService.SEND_COMMAND);
            intent.putExtra(BciService.COMMAND_EXTRA, cmd);
            sendBroadcast(intent);
        });

        button.setOnClickListener(v -> {
            Intent intent = new Intent(BciService.SEND_COMMAND);
            intent.putExtra(BciService.COMMAND_EXTRA, "v");
            Log.e(TAG, "Send Command - v");
            sendBroadcast(intent);
        });

        // Toggle Streaming Button to start/stop streaming EEG data
        toggleStreamingButton.setOnClickListener((View v) -> {
            if (!streaming) {
                //To begin data streaming, transmit a single ASCII b
                toggleStreamingButton.setText("stop streaming");
                streaming = true;
               // Intent intent = new Intent(String.valueOf(new BciService.mReceiver()));
                Intent intent = new Intent(BciService.SEND_COMMAND);
                intent.putExtra(BciService.COMMAND_EXTRA, "b");
                Log.e(TAG, "Send Command - b");
                sendBroadcast(intent);
            } else {
                //To end data streaming, transmit a single ASCII s
                toggleStreamingButton.setText("start streaming");
                streaming = false;
                Intent intent = new Intent(BciService.SEND_COMMAND);
                intent.putExtra(BciService.COMMAND_EXTRA, "s");
                Log.e(TAG, "Send Command - s");
                sendBroadcast(intent);
                stopOpenBciService(); //Stop BCI Service
            }
        });

        IntentFilter filter = new IntentFilter(); //set an IntentFilter to register our BroadcastReceiver.
        filter.addAction(BciService.DATA_RECEIVED_INTENT); //custom action intent targeting mBroadCastReceiver
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED); //custom action intent targeting mBroadCastReceiver
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED); //custom action intent targeting mBroadCastReceiver
        launchOpenBciService(); //Launch the Bci Service

        //Activity Recognition Setup
        mApiClient = new GoogleApiClient.Builder(this)
                .addApi(ActivityRecognition.API)
                .addConnectionCallbacks(this) //this is refer to connectionCallbacks interface implementation.
                .addOnConnectionFailedListener(this) //this is refer to onConnectionFailedListener interface implementation.
                .build();

        //Activity Recognition Connect
        mApiClient.connect();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Launch the BCI service if USB dongle is attached via OTG
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.contains(intent.getAction())) {
            launchOpenBciService();
        }
        // Stop the BCI service if USB dongle OTG device is removed
        if (UsbManager.ACTION_USB_DEVICE_DETACHED.contains(intent.getAction())) {
            stopOpenBciService();
        }
    }

    //Launch the OpenBCI Service
    private void launchOpenBciService() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice usbDevice = null;

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

        for (Map.Entry<String, UsbDevice> entry : deviceList.entrySet()) {
            UsbDevice device = entry.getValue();
            if (device.getVendorId() == BciService.VENDOR_ID) {
                usbDevice = device;
            }
        }

        if (null == usbDevice) {
            Log.d(TAG, "no device found");
        } else {
            Intent intent = new Intent(getApplicationContext(), BciService.class);
            intent.putExtra(UsbManager.EXTRA_DEVICE, usbDevice);
            startService(intent); //Start the OpenBCI Service
        }
    }

    //Stop the OpenBCI Service
    private void stopOpenBciService() {
        Intent intent = new Intent(getApplicationContext(), BciService.class);
        stopService(intent);
    }


    //WHAT TO DO HERE AS I WANT FOREGROUND SERVICE TO CONTINUE?
    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    //Instantiate the Handler Thread inside the onStart Method and start thread
    @Override
    protected void onStart() {
        Log.d(TAG, "onStart: called.");
        super.onStart();
    }

    //stop the Handler Thread inside the onStop Method (when app closes)
    @Override
    protected void onStop() {
        Log.d(TAG, "onStop: called.");
        super.onStop();
    }

    //Activity Recognition onConnected
    @Override
    public void onConnected(@Nullable Bundle bundle) {
        //Before request API request, GoogleApiClient must be in connected mode.
        Intent intent = new Intent(this,ActivityRecognizedService.class);
        PendingIntent pendingIntent = PendingIntent.getService(this,1,intent,PendingIntent.FLAG_UPDATE_CURRENT);
        ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(mApiClient,1,pendingIntent);
    }

    //Activity Recognition onConnectionSuspended
    @Override
    public void onConnectionSuspended(int i) {

    }

    //Activity Recognition onConnectionFailed
    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }
}
