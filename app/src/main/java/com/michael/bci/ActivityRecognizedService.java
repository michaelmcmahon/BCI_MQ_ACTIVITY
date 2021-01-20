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
            handleDetectedActivities( result.getProbableActivities() );
        }
    }

    /* Create RabbitMQ connection to Broker */
    private Connection getConnection() throws IOException, TimeoutException {
        ConnectionFactory factory;
        factory = RabbitmqConnection.getConnectionFactory();
        return factory.newConnection();
    }

    /* Create RabbitMQ Channel on Broker */
    private Channel getChannel(Connection connection) throws IOException {
        return connection.createChannel();
    }

    /* Declare the RabbitMQ Queue on Broker */
    private void declareQueue(Channel channel) throws IOException {
        channel.queueDeclare("activity", false, false, false, null);
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
            /* Placed RabbitMQ connection, channel, queue into their own Methods */
            String QUEUE_NAME = "activity"; //RabbitMQ Queue Name
            Connection connection = getConnection();
            Channel channel = getChannel(connection);
            declareQueue(channel);

            Calendar calendar = Calendar.getInstance(); //Get calendar using current time zone and locale of the system.
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS"); //format and parse date-time
            for (DetectedActivity activity : probableActivities) {
                /*Encode a JSON object using Map so order of the object entries is preserved*/
                Map obj=new LinkedHashMap();
                /* Create a new String using the date-time format */
                String dateString = simpleDateFormat.format(calendar.getTime());
                obj.put("TS", dateString); // Create a Timestamp

                if (activity.getType() == DetectedActivity.IN_VEHICLE && activity.getConfidence() >= 1) {
                    obj.put("In Vehicle: ", +activity.getConfidence());
                    Log.e("ActivityRecognition", "In Vehicle: " + activity.getConfidence());
                } else {
                    obj.put("In Vehicle:", 0);
                }
                if (activity.getType() == DetectedActivity.ON_BICYCLE && activity.getConfidence() >= 1) {
                    obj.put("On Bicycle: ", +activity.getConfidence());
                    Log.e("ActivityRecognition", "On Bicycle: " + activity.getConfidence());
                } else {
                    obj.put("On Bicycle:", 0);
                }
                if (activity.getType() == DetectedActivity.ON_FOOT && activity.getConfidence() >= 1) {
                    obj.put("On Foot: ", +activity.getConfidence());
                    Log.e("ActivityRecognition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("On Foot:", 0);
                }
                if (activity.getType() == DetectedActivity.RUNNING && activity.getConfidence() >= 1) {
                    obj.put("Running: ", +activity.getConfidence());
                    Log.e("ActivityRecognition", "Running: " + activity.getConfidence());
                } else {
                    obj.put("Running:", 0);
                }
                if (activity.getType() == DetectedActivity.STILL && activity.getConfidence() >= 1) {
                    obj.put("Still: ", +activity.getConfidence());
                    Log.e("ActivityRecognition", "Still: " + activity.getConfidence());
                } else {
                    obj.put("Still:", 0);
                }
                if (activity.getType() == DetectedActivity.TILTING && activity.getConfidence() >= 1) {
                    obj.put("Tilting: ", +activity.getConfidence());
                    Log.e("ActivityRecognition", "Tilting: " + activity.getConfidence());
                } else {
                    obj.put("Tilting:", 0);
                }
                if (activity.getType() == DetectedActivity.WALKING && activity.getConfidence() >= 1) {
                    obj.put("Walking: ", +activity.getConfidence());
                    Log.e("ActivityRecognition", "Walking: " + activity.getConfidence());
                } else {
                    obj.put("Walking:", 0);
                }
                if (activity.getType() == DetectedActivity.UNKNOWN && activity.getConfidence() >= 1) {
                    obj.put("Unknown: ", +activity.getConfidence());
                    Log.e("ActivityRecognition", "Unknown: " + activity.getConfidence());
                } else {
                    obj.put("Unknown:", 0);
                }
                channel.basicPublish("", QUEUE_NAME, null, JSONValue.toJSONString(obj).getBytes("UTF-8"));
            }
        } catch (TimeoutException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

