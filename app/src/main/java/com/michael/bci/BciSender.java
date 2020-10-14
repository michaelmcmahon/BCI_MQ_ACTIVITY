package com.michael.bci;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.util.Arrays;

public class BciSender {

    /* Start the sender thread to send messages to the OpenBCI Cyton Board */
    void startSenderThread(BciService bciService) {
        bciService.mSenderThread = new SenderThread(bciService, "OpenBCI_sender");
        bciService.mSenderThread.start();
    }

    /* Send the actual OpenBCI command via FTDI to USB Dongle */
    void SendMessage(String writeData, BciService bciService) {
        /* The default FTDI latency is too large for EEG apps, making the incoming signal "choppy", Change from 16ms to 1ms */
        bciService.ftDevice.setLatencyTimer((byte) 1);
//        ftDevice.purge((byte) (D2xxManager.FT_PURGE_TX | D2xxManager.FT_PURGE_RX)); //MAY NEED THIS
//        String writeData = "v";//writeText.getText().toString(); //MAY NEED THIS
        byte[] OutData = writeData.getBytes();
        Log.w("PROCESS_DATA:", "OutData 0:" + OutData);
        Log.w("PROCESS_DATA:", "OutData 1:" + Arrays.toString(OutData));
        bciService.ftDevice.write(OutData, writeData.length());

        if (bciService.ftDevice.isOpen() == false) {
            Log.e("j2xx", "SendMessage: device not open");
            return;
        }
    }

    public static class SenderThread extends Thread {
        private final BciService bciService;
        Handler mHandler;
        SenderThread(BciService bciService, String string) {
            super(string);
            this.bciService = bciService;
        }
        @SuppressLint("HandlerLeak")
        public void run() {
            Looper.prepare();
            mHandler = new Handler() {
                public void handleMessage(Message msg) {
                    Log.i(BciService.TAG, "USB handleMessage() 10 or not?" + msg.what);
                    if (msg.what == 10) {
                        final String writeData = (String) msg.obj;
                        new BciSender().SendMessage(writeData, bciService);
                        Log.d(BciService.TAG, "USB calling bulkTransfer() out: "+writeData);
                    } else if (msg.what != 10) {
                        Looper.myLooper().quit();
                    }
                }
            };
            Looper.loop();
            Log.i(BciService.TAG, "sender thread stopped");
        }
    }
}
