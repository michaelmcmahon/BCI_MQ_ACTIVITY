package com.michael.bci;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;

import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import org.json.simple.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.simple.JSONValue;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeoutException;

/*
Michael McMahon
This class is focused on using the Google Play Services Activity Recognition API to determine if the user
is running, walking, in a vehicle, biking, or remaining still. When Google Play Services returns the user's
activity it is sent to the IntentService to perform the application logic in the background.
REFERENCE
https://code.tutsplus.com/tutorials/how-to-recognize-user-activity-with-activity-recognition--cms-25851
 */
public class ActivityRecognizedService extends IntentService {
    private Connection connection;
    private Channel channel_2;
    private static final String EXCHANGE_NAME = "exchange_1"; //RabbitMQ Exchange Name
    private static final String QUEUE_NAME = "activity"; //RabbitMQ Queue Name
    public final static String TAG = "ActivityRec";


    public ActivityRecognizedService() {
        super("ActivityRecognizedService");
    }

    public ActivityRecognizedService(String name) {
        super(name);
    }
    /*
    In the onHandleIntent() method of ActivityRecognizedService we validate that the received Intent contains
    activity recognition data and, if so, then extract the ActivityRecognitionResult from the Intent to see what
    activities the user might be performing by calling getProbableActivities() on the ActivityRecognitionResult
    object.
     */
    @Override
    protected void onHandleIntent(Intent intent) {
        if(ActivityRecognitionResult.hasResult(intent)) {
            ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
            DetectedActivity mostProbableActivity = result.getMostProbableActivity();
            handleDetectedActivities( result.getProbableActivities() );

            // Get the type of activity
            int activityType = mostProbableActivity.getType();
            // Get the confidence percentage for the most probable activity
            int confidence = mostProbableActivity.getConfidence();
        }
    }

    /*
    Suppress due to an unchecked 'put(K,V)' warning because org.json.simple.JSONObject uses raw type
    collections internally - need to change to a library which supports generics to be more type safe.
    */
    @SuppressWarnings(value = "unchecked")

    /*
    In the handleDetectedActivities method we connect to the RabbitMQ 'activity' Queue and send timestamped
    data of each activity that has been detected and how confident Google Play Services is that the user is
    performing that activity by calling getConfidence() on a DetectedActivity instance.
     */
    private void handleDetectedActivities(List<DetectedActivity> probableActivities) {

        try {

            Calendar calendar = Calendar.getInstance(); //Get calendar using current time zone and locale of the system.
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS"); //format and parse date-time
            long unixTime = System.currentTimeMillis() / 1000L; //Unix Timestamp

            for (DetectedActivity activity : probableActivities) {
                Channel channel = getChannel();
                Log.d(TAG, "RMQ: Connection/Channel 2 for EEG" +channel);

                /*Encode a JSON object using Map so order of the object entries is preserved*/
                Map obj=new HashMap();
                //LinkedHashMap<String, java.io.Serializable> obj = new LinkedHashMap<>();
                /* Create a new String using the date-time format */
                String dateString = simpleDateFormat.format(calendar.getTime());
                obj.put("TS", dateString); // Create a Timestamp

                switch (activity.getType())
                {
                    case DetectedActivity.IN_VEHICLE: {
                        obj.put("In Vehicle ", activity.getConfidence());
                        Log.e("ActivityRecognition", "In Vehicle " + activity.getConfidence());
                        break;
                    }
                    case DetectedActivity.ON_BICYCLE: {
                        obj.put("On Bicycle ", activity.getConfidence());
                        Log.e("ActivityRecognition", "On Bicycle " + activity.getConfidence());
                        break;
                    }
                    case DetectedActivity.STILL: {
                        obj.put("Still ", activity.getConfidence());
                        Log.e("ActivityRecognition", "Still " + activity.getConfidence());
                        break;
                    }
                    case DetectedActivity.TILTING: {
                        obj.put("Tilting ", activity.getConfidence());
                        Log.e("ActivityRecognition", "Tilting " + activity.getConfidence());
                        break;
                    }
                    case DetectedActivity.WALKING: {
                        obj.put("Walking ", activity.getConfidence());
                        Log.e("ActivityRecognition", "Walking " + activity.getConfidence());
                        break;
                    }
                    case DetectedActivity.RUNNING: {
                        obj.put("Running ", activity.getConfidence());
                        Log.e("ActivityRecognition", "Running " + activity.getConfidence());
                        break;
                    }
                    case DetectedActivity.ON_FOOT: {
                        obj.put("On Foot ", activity.getConfidence());
                        Log.e("ActivityRecognition", "On Foot" + activity.getConfidence());
                        break;
                    }
                    case DetectedActivity.UNKNOWN: {
                        obj.put("Unknown ", activity.getConfidence());
                        Log.e("ActivityRecognition", "Unknown " + activity.getConfidence());
                        break;
                    }
                }
                obj.put("UnixTS", unixTime); // Create a Unix Timestamp
                obj.put("TS", dateString); // Create a Formatted Timestamp

                channel.basicPublish(EXCHANGE_NAME, "white",null, JSONValue.toJSONString(obj).getBytes(StandardCharsets.UTF_8));

                /* For now lets just close connection every time */
                //RabbitmqConnection.CloseConnection();
                //CloseChannel();
            }
        } catch (Exception e) {
            e.printStackTrace();
            // If we hit any issues, close the RabbitMQ channel, so that it is re-created on the next read.
            try {
                CloseChannel();
                Log.d(TAG, "RMQ: Close Loop Channel for Activity");
            } catch (IOException | TimeoutException ioException) {
                ioException.printStackTrace();
            }
        }
    }

    /* Create RabbitMQ Connection to the Broker */
    private Connection getConnection() throws IOException, TimeoutException {
        // Only create the connection if it doesn't already exist
        if (connection == null)
        {
            try
            {
                ConnectionFactory factory = RabbitmqConnection.getConnectionFactory();
                connection =  factory.newConnection();
            }
            catch(Exception e)
            {
                // Clean up
                if (connection != null)
                {
                    connection.close();
                    connection = null;
                }
                throw e;
            }
        }
        return connection;
    }


    /* Create RabbitMQ Channel on Broker */
    private Channel getChannel() throws IOException, TimeoutException {
        // Only create the channel if it doesn't already exist
        if (channel_2 == null)
        {
            try
            {
                //channel_2 = RabbitmqConnection.getConnection().createChannel();
                channel_2 = getConnection().createChannel();
                channel_2.exchangeDeclare(EXCHANGE_NAME, "direct", true);
                channel_2.queueDeclare(QUEUE_NAME, false, false, false, null);
                channel_2.queueBind(QUEUE_NAME, EXCHANGE_NAME, "white");
            }
            catch(Exception e)
            {
                // Clean up
                if (channel_2 != null)
                {
                    channel_2.close();
                    channel_2 = null;
                }

                throw e;
            }
        }
        Log.d(TAG, "RMQ: Open Channel 2 for Activity" + channel_2);
        return channel_2;
    }

    // Cleanup the channel and leave it in an uninitialized state
    private void CloseChannel() throws IOException, TimeoutException {
        if (channel_2 != null)
        {
            channel_2.close();
            channel_2 = null;
            Log.d(TAG, "RMQ: Close Channel 2 for Activity");
        }

        if (connection != null)
        {
            connection.close();
            connection = null;
            Log.d(TAG, "RMQ: Close Connection for Activity");
        }
    }
}
