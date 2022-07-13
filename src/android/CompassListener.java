/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova.deviceorientation;

import java.util.List;

import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.content.Context;

import android.os.Handler;
import android.os.Looper;

/**
 * This class listens to the compass sensor and stores the latest heading value.
 */
public class CompassListener extends CordovaPlugin implements SensorEventListener {

    public static int STOPPED = 0;
    public static int STARTING = 1;
    public static int RUNNING = 2;
    public static int ERROR_FAILED_TO_START = 3;

    public long TIMEOUT = 30000;        // Timeout in msec to shut off listener

    int status;                         // status of listener
    double heading;                      // most recent heading value
    long timeStamp;                     // time of most recent value
    long lastAccessTime;                // time the value was last retrieved
    int accuracy;                       // accuracy of the sensor
    float[] gravityValues = new float[3];
    float gravity;
    float[] magneticFieldValues = new float[3];
    float magneticField;
    float[] normEastVector = new float[3];
    float[] normNorthVector = new float[3];

    private SensorManager sensorManager;// Sensor manager
    Sensor gravitySensor;         // Gravity sensor returned by sensor manager
    Sensor magneticFieldSensor;         // Magnetic Field sensor returned by sensor manager

    private CallbackContext callbackContext;

    /**
     * Constructor.
     */
    public CompassListener() {
        this.heading = 0;
        this.timeStamp = 0;
        this.setStatus(CompassListener.STOPPED);
    }

    /**
     * Sets the context of the Command. This can then be used to do things like
     * get file paths associated with the Activity.
     *
     * @param cordova The context of the main Activity.
     * @param webView The CordovaWebView Cordova is running in.
     */
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        this.sensorManager = (SensorManager) cordova.getActivity().getSystemService(Context.SENSOR_SERVICE);
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action                The action to execute.
     * @param args          	    JSONArry of arguments for the plugin.
     * @param callbackS=Context     The callback id used when calling back into JavaScript.
     * @return              	    True if the action was valid.
     * @throws JSONException 
     */
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("start")) {
            this.start();
        }
        else if (action.equals("stop")) {
            this.stop();
        }
        else if (action.equals("getStatus")) {
            int i = this.getStatus();
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, i));
        }
        else if (action.equals("getHeading")) {
            // If not running, then this is an async call, so don't worry about waiting
            if (this.status != CompassListener.RUNNING) {
                int r = this.start();
                if (r == CompassListener.ERROR_FAILED_TO_START) {
                    callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.IO_EXCEPTION, CompassListener.ERROR_FAILED_TO_START));
                    return true;
                }
                // Set a timeout callback on the main thread.
                Handler handler = new Handler(Looper.getMainLooper());
                handler.postDelayed(new Runnable() {
                    public void run() {
                        CompassListener.this.timeout();
                    }
                }, 2000);
            }
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, getCompassHeading()));
        }
        else if (action.equals("setTimeout")) {
            this.setTimeout(args.getLong(0));
        }
        else if (action.equals("getTimeout")) {
            long l = this.getTimeout();
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, l));
        } else {
            // Unsupported action
            return false;
        }
        return true;
    }

    /**
     * Called when listener is to be shut down and object is being destroyed.
     */
    public void onDestroy() {
        this.stop();
    }

    /**
     * Called when app has navigated and JS listeners have been destroyed.
     */
    public void onReset() {
        this.stop();
    }

    //--------------------------------------------------------------------------
    // LOCAL METHODS
    //--------------------------------------------------------------------------

    /**
     * Start listening for compass sensor.
     *
     * @return          status of listener
     */
    public int start() {

        // If already starting or running, then just return
        if ((this.status == CompassListener.RUNNING) || (this.status == CompassListener.STARTING)) {
            return this.status;
        }

        // Get accelerometer sensor from sensor manager
        List<Sensor> gravityList = this.sensorManager.getSensorList(Sensor.TYPE_GRAVITY);
        // Get magnetic field sensor from sensor manager
        List<Sensor> magneticFieldList = this.sensorManager.getSensorList(Sensor.TYPE_MAGNETIC_FIELD);

        // If found, then register as listener
        if (gravityList != null && gravityList.size() > 0 && magneticFieldList != null && magneticFieldList.size() > 0) {
            this.gravitySensor = gravityList.get(0);
            this.sensorManager.registerListener(this, this.gravitySensor, SensorManager.SENSOR_STATUS_ACCURACY_HIGH);

            this.magneticFieldSensor = magneticFieldList.get(0);
            this.sensorManager.registerListener(this, this.magneticFieldSensor, SensorManager.SENSOR_STATUS_ACCURACY_HIGH);

            this.lastAccessTime = System.currentTimeMillis();
            this.setStatus(CompassListener.STARTING);
        }
        // If error, then set status to error
        else {
            this.setStatus(CompassListener.ERROR_FAILED_TO_START);
        }

        return this.status;
    }

    /**
     * Stop listening to compass sensor.
     */
    public void stop() {
        if (this.status != CompassListener.STOPPED) {
            this.sensorManager.unregisterListener(this, gravitySensor);
            this.sensorManager.unregisterListener(this, magneticFieldSensor);
        }
        this.setStatus(CompassListener.STOPPED);
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // TODO Auto-generated method stub
    }

    /**
     * Called after a delay to time out if the listener has not attached fast enough.
     */
    private void timeout() {
        if (this.status == CompassListener.STARTING) {
            this.setStatus(CompassListener.ERROR_FAILED_TO_START);
            if (this.callbackContext != null) {
                this.callbackContext.error("Compass listener failed to start.");
            }
        }
    }

    /**
     * Sensor listener event.
     *
     * @param SensorEvent event
     */
    public void onSensorChanged(SensorEvent event) {
        this.timeStamp = System.currentTimeMillis();
        int sensorType = event.sensor.getType();
        switch (sensorType) {
            case Sensor.TYPE_GRAVITY:
                this.gravityValues = event.values.clone();
                this.gravity = (float) Math.sqrt(this.gravityValues[0] * this.gravityValues[0] + this.gravityValues[1] * this.gravityValues[1] + this.gravityValues[2] * this.gravityValues[2]);
                for (int i = 0; i < gravityValues.length; i++) gravityValues[i] /= gravity;
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                this.magneticFieldValues = event.values.clone();
                this.magneticField = (float) Math.sqrt(this.magneticFieldValues[0] * this.magneticFieldValues[0] + this.magneticFieldValues[1] * this.magneticFieldValues[1] + this.magneticFieldValues[2] * this.magneticFieldValues[2]);
                for (int i = 0; i < magneticFieldValues.length; i++) magneticFieldValues[i] /= magneticField;
                break;
        }

        if (this.gravityValues != null && this.magneticFieldValues != null) {
            this.heading = calculateHeading();
        }

        // If heading hasn't been read for TIMEOUT time, then turn off compass sensor to save power
        if ((this.timeStamp - this.lastAccessTime) > this.TIMEOUT) {
            this.stop();
        }
    }

    public double calculateHeading() {
        float eastX = this.magneticFieldValues[1] * this.gravityValues[2] - this.magneticFieldValues[2] * this.gravityValues[1];
        float eastY = this.magneticFieldValues[2] * this.gravityValues[0] - this.magneticFieldValues[0] * this.gravityValues[2];
        float eastZ = this.magneticFieldValues[0] * this.gravityValues[1] - this.magneticFieldValues[1] * this.gravityValues[0];
        float normEast = (float) Math.sqrt(eastX * eastX + eastY * eastY + eastZ * eastZ);
        if (gravity * magneticField * normEast >= 0.1f) {
            normEastVector[0] = eastX / normEast;
            normEastVector[1] = eastY / normEast;
            normEastVector[2] = eastZ / normEast;
        }
        float mDotG = (this.gravityValues[0] * this.magneticFieldValues[0] + this.gravityValues[1] * this.magneticFieldValues[1] + this.gravityValues[2] * this.magneticFieldValues[2]);
        float northX = this.magneticFieldValues[0] - this.gravityValues[0] * mDotG;
        float northY = this.magneticFieldValues[1] - this.gravityValues[1] * mDotG;
        float northZ = this.magneticFieldValues[2] - this.gravityValues[2] * mDotG;
        float normNorth = (float) Math.sqrt(northX * northX + northY * northY + northZ * northZ);
        normNorthVector[0] = northX / normNorth;
        normNorthVector[1] = northY / normNorth;
        normNorthVector[2] = northZ / normNorth;

        float sin = normEastVector[1] -  normNorthVector[0];
        float cos = normEastVector[0] +  normNorthVector[1];
        float azimuthRadians = (float) (sin != 0 && cos != 0 ? Math.atan2(sin, cos) : 0);
        double azimuth = azimuthRadians * (180 / Math.PI);
        if (azimuth < 0) {
            return azimuth + 360;
        }
        return azimuth;
    }

    /**
     * Get status of compass sensor.
     *
     * @return          status
     */
    public int getStatus() {
        return this.status;
    }

    /**
     * Get the most recent compass heading.
     *
     * @return          heading
     */
    public double getHeading() {
        this.lastAccessTime = System.currentTimeMillis();
        return this.heading;
    }

    /**
     * Set the timeout to turn off compass sensor if getHeading() hasn't been called.
     *
     * @param timeout       Timeout in msec.
     */
    public void setTimeout(long timeout) {
        this.TIMEOUT = timeout;
    }

    /**
     * Get the timeout to turn off compass sensor if getHeading() hasn't been called.
     *
     * @return timeout in msec
     */
    public long getTimeout() {
        return this.TIMEOUT;
    }

    /**
     * Set the status and send it to JavaScript.
     * @param status
     */
    private void setStatus(int status) {
        this.status = status;
    }

    /**
     * Create the CompassHeading JSON object to be returned to JavaScript
     *
     * @return a compass heading
     */
    private JSONObject getCompassHeading() throws JSONException {
        JSONObject obj = new JSONObject();

        obj.put("magneticHeading", this.getHeading());
        obj.put("trueHeading", this.getHeading());
        // Since the magnetic and true heading are always the same our and accuracy
        // is defined as the difference between true and magnetic always return zero
        obj.put("headingAccuracy", 0);
        obj.put("timestamp", this.timeStamp);

        return obj;
    }

}
