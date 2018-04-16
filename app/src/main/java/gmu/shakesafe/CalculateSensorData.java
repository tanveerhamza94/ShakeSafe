package gmu.shakesafe;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.SensorEvent;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.util.Log;


import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;

import static gmu.shakesafe.MainActivity.LOG_TAG;
import static gmu.shakesafe.MainActivity.accSensor;
import static gmu.shakesafe.MainActivity.canUpload;
import static gmu.shakesafe.MainActivity.screenDelayDone;
import static gmu.shakesafe.MainActivity.screenDelayStarted;
import static gmu.shakesafe.MainActivity.sdObject;
import static gmu.shakesafe.MainActivity.GlobalContext;
import static gmu.shakesafe.MainActivity.userFileExists;


/**
 * Created by Mauro on 3/12/2018.
 *
 *      This class calculates all the values needed to display the sensor data on the Sensor tab.
 *      It also handles the uploading of the data.
 *
 */



public class CalculateSensorData extends AsyncTask<SensorEvent, Void, Void> {

    @Override
    public Void doInBackground(SensorEvent... ev) {
        PowerManager pm = (PowerManager) GlobalContext.getSystemService(Context.POWER_SERVICE);
        SensorEvent event = ev[0];

        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = GlobalContext.registerReceiver(null, ifilter);

        // Is the phone plugged in?
        int status;
        boolean isCharging;


        AmazonS3 s3Client = new AmazonS3Client(AWSMobileClient.getInstance().getCredentialsProvider());

        boolean ScreenOn;

        try {
            ScreenOn = pm.isInteractive();
            status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (NullPointerException e) {
            ScreenOn = false;
            isCharging = false;
        }


        double newAcc, tilt, denom, deltaX, deltaY, deltaZ;

        double realX, realY, realZ;

        // Extract the data we have stored in the sensor object
        double gravityX = accSensor.getGravityX();
        double gravityY = accSensor.getGravityY();
        double gravityZ = accSensor.getGravityZ();


        final double alpha = 0.8;

        //these values will accommodate for gravitational acceleration

        gravityX = alpha * gravityX + (1 - alpha) * event.values[0];
        gravityY = alpha * gravityY + (1 - alpha) * event.values[1];
        gravityZ = alpha * gravityZ + (1 - alpha) * event.values[2];

        realX = Math.abs((float) (event.values[0] - gravityX));
        realY = Math.abs((float) (event.values[1] - gravityY));
        realZ = Math.abs((float) (event.values[2] - gravityZ));
        //end gravity code

        // get the change of the x,y,z values of the accelerometer
        deltaX = (Math.abs(0 - event.values[0]));
        deltaY = (Math.abs(0 - event.values[1]));
        deltaZ = (Math.abs(0 - event.values[2]));

        // if the change is below 1, it is considered noise
        if (deltaX < 1)
            deltaX = 0;
        if (deltaY < 1)
            deltaY = 0;
        if (deltaZ < 1)
            deltaZ = 0;

        // deriving tilt
        denom = (float) Math.sqrt((deltaX * deltaX) + (deltaY * deltaY) + (deltaZ * deltaZ));
        tilt = (float) Math.acos(deltaZ / denom);
        tilt = (float) ((tilt * 180) / 3.14);

        newAcc = Math.sqrt(realX * realX + realY * realY + realZ * realZ);


        // Calculates the standard deviation on a background thread
        new CalculateSD().execute(newAcc);



        // This portion of the code uploads data if the phone screen is off,
        // the tilt is less than 1, and the phone is plugged in.
        if (tilt < 1 && !ScreenOn && isCharging) {

            if (MainActivity.UPLOADS_ON) {

                // This creates a delay so the threshold isn't triggered when the phone's screen
                // is turned off manually
                if (!screenDelayStarted) {

                    screenDelayStarted = true;
                    MainActivity.screenOffTimer();
                    Log.d(LOG_TAG, "SCREEN OFF TIMER: STARTED");

                } else if (canUpload && (newAcc >= sdObject.getThreshold()) && screenDelayDone) {

                    canUpload = false;

                    String[] data = MainActivity.getLocation().split("/");

                    String userFilesData = data[0] + "/" + data[1] + "/" + data[2] + "/" + data[3];
                    String activeUsersData = data[0] + "/" + data[1];


                    s3Client.putObject("shakesafe-userfiles-mobilehub-889569083",
                            "ActiveUsers/" + MainActivity.uniqueID + ".txt", activeUsersData);

                    userFileExists = true;


                    s3Client.putObject("shakesafe-userfiles-mobilehub-889569083",
                            "s3Folder/" + MainActivity.uniqueID + ".txt", userFilesData);


                    Log.d(LOG_TAG, "UPLOAD COMPLETE... TIMER STARTED");
                    MainActivity.uploadTimer();
                }

            } else
                Log.d(LOG_TAG, "******** UPLOADS ARE DISABLED ********");
        }
        else {

            // This deletes the ActiveUsers file in S3 if the phone is no longer considered active.
            try {
                if(userFileExists) {
                    userFileExists = false;
                    s3Client.deleteObject("shakesafe-userfiles-mobilehub-889569083", "ActiveUsers/" + MainActivity.uniqueID + ".txt");
                }
            }
            catch (Exception e){
                e.printStackTrace();
            }
        }



        // Local calculations are done. Store values back into the sensor object for future
        // calls to this method, as well as for printing out to the screen.
        accSensor.setDeltaX(deltaX);
        accSensor.setDeltaY(deltaY);
        accSensor.setDeltaZ(deltaZ);

        accSensor.setGravityX(gravityX);
        accSensor.setGravityY(gravityY);
        accSensor.setGravityZ(gravityZ);

        accSensor.setTilt(tilt);

        return null;
    }
}