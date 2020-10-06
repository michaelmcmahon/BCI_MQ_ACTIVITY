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

/*
In the handleDetectedActivities method we connect to the RabbitMQ 'activity' Queue and send timestamped
data of each activity that has been detected and how confident Google Play Services is that the user is
performing that activity by calling getConfidence() on a DetectedActivity instance.
 */
    private void handleDetectedActivities(List<DetectedActivity> probableActivities) {

        try {
            String QUEUE_NAME = "activity"; //RabbitMQ Queue Name
            ConnectionFactory factory;
            factory = new ConnectionFactory();
            factory.setHost("34.244.11.71"); //IP of the RabbitMQ Message Broker
            factory.setUsername("user"); //RabbitMQ Username
            factory.setPassword("VIIu8eoVRYrH"); //RabbitMQ Password
            factory.setVirtualHost("/"); //RabbitMQ Virtual Host
            factory.setPort(5672); //RabbitMQ Message Broker Port
            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();
            channel.queueDeclare(QUEUE_NAME, false, false, false, null);

            Calendar calendar = Calendar.getInstance(); //Get calendar using current time zone and locale of the system.
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS"); //format and parse date-time
            for (DetectedActivity activity : probableActivities) {
                JSONObject obj = new JSONObject();
                /* Create a new String using the date-time format */
                String dateString = simpleDateFormat.format(calendar.getTime());
                obj.put("TS", dateString); // Create a Timestamp

                if (activity.getType() == DetectedActivity.IN_VEHICLE && activity.getConfidence() >= 0) {
                   obj.put("In Vehicle: ", +activity.getConfidence());
                   Log.e("ActivityRecogition", "In Vehicle: " + activity.getConfidence());
               } else {
                   obj.put("In Vehicle:", 0);
               }
                if (activity.getType() == DetectedActivity.ON_BICYCLE && activity.getConfidence() >= 0) {
                    obj.put("On Bicycle: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Bicycle: " + activity.getConfidence());
                } else {
                    obj.put("On Bicycle:", 0);
                }
                if (activity.getType() == DetectedActivity.ON_FOOT && activity.getConfidence() >= 0) {
                    obj.put("On Foot: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("On Foot:", 0);
                }
                if (activity.getType() == DetectedActivity.RUNNING && activity.getConfidence() >= 0) {
                    obj.put("Running: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("Running:", 0);
                }
                if (activity.getType() == DetectedActivity.STILL && activity.getConfidence() >= 0) {
                    obj.put("Still: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("Still:", 0);
                }
                if (activity.getType() == DetectedActivity.TILTING && activity.getConfidence() >= 0) {
                    obj.put("Tilting: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("Tilting:", 0);
                }
                if (activity.getType() == DetectedActivity.WALKING && activity.getConfidence() >= 0) {
                    obj.put("Walking: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("Walking:", 0);
                }
                if (activity.getType() == DetectedActivity.UNKNOWN && activity.getConfidence() >= 0) {
                    obj.put("Unknown: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("Unknown:", 0);
                }
                channel.basicPublish("", QUEUE_NAME, null, obj.toJSONString().getBytes());
            }
        } catch (TimeoutException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

