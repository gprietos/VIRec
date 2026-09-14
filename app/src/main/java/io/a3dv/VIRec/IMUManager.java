package io.a3dv.VIRec;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import timber.log.Timber;

public class IMUManager implements SensorEventListener {
    public static String ImuHeader = "Timestamp[nanosec],gx[rad/s],gy[rad/s],gz[rad/s]," +
            "ax[m/s^2],ay[m/s^2],az[m/s^2],Unix time[nanosec]\n";

    // TYPE_ROTATION_VECTOR is Android's own accelerometer+gyroscope+magnetometer fusion,
    // referenced to magnetic/true north (an East-North-Up world frame) -- unlike raw
    // gyro/accel above, it needs no manual integration and does not drift, so it's the
    // actual yaw/pitch/roll source for Frame rather than something derived from gyro_accel.csv.
    public static String OrientationHeader =
            "Timestamp[nanosec],yaw[deg],pitch[deg],roll[deg],Unix time[nanosec]\n";

    private static class SensorPacket {
        long timestamp; // nanoseconds
        long unixTime; // milliseconds
        float[] values;

        SensorPacket(long time, long unixTimeMillis, float[] vals) {
            timestamp = time;
            unixTime = unixTimeMillis;
            values = vals;
        }

        @NonNull
        @Override
        public String toString() {
            String delimiter = ",";
            StringBuilder sb = new StringBuilder();
            sb.append(timestamp);
            for (float value : values) {
                sb.append(delimiter).append(value);
            }
            sb.append(delimiter).append(unixTime).append("000000");
            return sb.toString();
        }
    }

    // Sensor listeners
    private final SensorManager mSensorManager;
    private final Sensor mAccel;
    private final Sensor mGyro;
    private final Sensor mRotationVector;
    private static SharedPreferences mSharedPreferences;

    private volatile boolean mRecordingInertialData = false;
    private BufferedWriter mDataWriter = null;
    private BufferedWriter mOrientationWriter = null;
    private HandlerThread mSensorThread;

    private final Deque<SensorPacket> mGyroData = new ArrayDeque<>();
    private final Deque<SensorPacket> mAccelData = new ArrayDeque<>();
    private final float[] mRotationMatrix = new float[9];
    private final float[] mOrientationRad = new float[3];

    public IMUManager(Activity activity) {
        mSensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        mAccel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mGyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        mRotationVector = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    public void startRecording(String captureResultFile) {
        try {
            mDataWriter = new BufferedWriter(
                    new FileWriter(captureResultFile, false));
            if (mGyro == null || mAccel == null) {
                String warning = "The device may not have a gyroscope or an accelerometer!\n" +
                        "No IMU data will be logged.\n" +
                        "Has Gyroscope? " + (mGyro == null ? "No" : "Yes") + "\n"
                        + "Has Accelerometer? " + (mAccel == null ? "No" : "Yes") + "\n";
                mDataWriter.write(warning);
            } else {
                mDataWriter.write(ImuHeader);
            }
        } catch (IOException err) {
            Timber.e(err, "IOException in opening inertial data writer at %s",
                    captureResultFile);
        }

        String orientationFile = new File(captureResultFile).getParent()
                + File.separator + "orientation.csv";
        try {
            mOrientationWriter = new BufferedWriter(new FileWriter(orientationFile, false));
            if (mRotationVector == null) {
                mOrientationWriter.write("The device has no fused rotation-vector sensor!\n" +
                        "No orientation data will be logged.\n");
            } else {
                mOrientationWriter.write(OrientationHeader);
            }
        } catch (IOException err) {
            Timber.e(err, "IOException in opening orientation data writer at %s",
                    orientationFile);
        }

        mRecordingInertialData = true;
    }

    public void stopRecording() {
        if (mRecordingInertialData) {
            mRecordingInertialData = false;
            try {
                mDataWriter.flush();
                mDataWriter.close();
            } catch (IOException err) {
                Timber.e(err, "IOException in closing inertial data writer");
            }
            mDataWriter = null;

            try {
                mOrientationWriter.flush();
                mOrientationWriter.close();
            } catch (IOException err) {
                Timber.e(err, "IOException in closing orientation data writer");
            }
            mOrientationWriter = null;
        }
    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // sync inertial data by interpolating linear acceleration for each gyro data
    // Because the sensor events are delivered to the handler thread in order,
    // no need for synchronization here
    private SensorPacket syncInertialData() {
        if (mGyroData.size() >= 1 && mAccelData.size() >= 2) {
            SensorPacket oldestGyro = mGyroData.peekFirst();
            SensorPacket oldestAccel = mAccelData.peekFirst();
            SensorPacket latestAccel = mAccelData.peekLast();

            assert oldestGyro != null;
            assert oldestAccel != null;
            if (oldestGyro.timestamp < oldestAccel.timestamp) {
                Timber.w("throwing one gyro data");
                mGyroData.removeFirst();
            } else {
                assert latestAccel != null;
                if (oldestGyro.timestamp > latestAccel.timestamp) {
                    Timber.w("throwing #accel data %d", mAccelData.size() - 1);
                    mAccelData.clear();
                    mAccelData.add(latestAccel);
                } else { // linearly interpolate the accel data at the gyro timestamp
                    float[] gyro_accel = new float[6];
                    SensorPacket sp = new SensorPacket(oldestGyro.timestamp, oldestGyro.unixTime, gyro_accel);
                    gyro_accel[0] = oldestGyro.values[0];
                    gyro_accel[1] = oldestGyro.values[1];
                    gyro_accel[2] = oldestGyro.values[2];

                    SensorPacket leftAccel = null;
                    SensorPacket rightAccel = null;
                    for (SensorPacket packet : mAccelData) {
                        if (packet.timestamp <= oldestGyro.timestamp) {
                            leftAccel = packet;
                        } else {
                            rightAccel = packet;
                            break;
                        }
                    }

                    // if the accelerometer data has a timestamp within the
                    // [t-x, t+x] of the gyro data at t, then the original acceleration data
                    // is used instead of linear interpolation
                    // nanoseconds
                    long mInterpolationTimeResolution = 500;
                    assert leftAccel != null;
                    if (oldestGyro.timestamp - leftAccel.timestamp <=
                            mInterpolationTimeResolution) {
                        gyro_accel[3] = leftAccel.values[0];
                        gyro_accel[4] = leftAccel.values[1];
                        gyro_accel[5] = leftAccel.values[2];
                    } else {
                        assert rightAccel != null;
                        if (rightAccel.timestamp - oldestGyro.timestamp <=
                                mInterpolationTimeResolution) {
                            gyro_accel[3] = rightAccel.values[0];
                            gyro_accel[4] = rightAccel.values[1];
                            gyro_accel[5] = rightAccel.values[2];
                        } else {
                            float tmp1 = oldestGyro.timestamp - leftAccel.timestamp;
                            float tmp2 = rightAccel.timestamp - leftAccel.timestamp;
                            float ratio = tmp1 / tmp2;
                            gyro_accel[3] = leftAccel.values[0] +
                                    (rightAccel.values[0] - leftAccel.values[0]) * ratio;
                            gyro_accel[4] = leftAccel.values[1] +
                                    (rightAccel.values[1] - leftAccel.values[1]) * ratio;
                            gyro_accel[5] = leftAccel.values[2] +
                                    (rightAccel.values[2] - leftAccel.values[2]) * ratio;
                        }
                    }

                    mGyroData.removeFirst();
                    for (Iterator<SensorPacket> iterator = mAccelData.iterator();
                         iterator.hasNext(); ) {
                        SensorPacket packet = iterator.next();
                        if (packet.timestamp < leftAccel.timestamp) {
                            // Remove the current element from the iterator and the list.
                            iterator.remove();
                        } else {
                            break;
                        }
                    }
                    return sp;
                }
            }
        }
        return null;
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        long unixTime = System.currentTimeMillis();
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            SensorPacket sp = new SensorPacket(event.timestamp, unixTime, event.values);
            mAccelData.add(sp);
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            SensorPacket sp = new SensorPacket(event.timestamp, unixTime, event.values);
            mGyroData.add(sp);
            SensorPacket syncedData = syncInertialData();
            if (syncedData != null && mRecordingInertialData) {
                try {
                    mDataWriter.write(syncedData.toString() + "\n");
                } catch (IOException ioe) {
                    Timber.e(ioe);
                }
            }
        } else if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            if (mRecordingInertialData) {
                SensorManager.getRotationMatrixFromVector(mRotationMatrix, event.values);
                SensorManager.getOrientation(mRotationMatrix, mOrientationRad);
                double yawDeg = Math.toDegrees(mOrientationRad[0]);
                double pitchDeg = Math.toDegrees(mOrientationRad[1]);
                double rollDeg = Math.toDegrees(mOrientationRad[2]);
                try {
                    mOrientationWriter.write(event.timestamp + "," + yawDeg + "," + pitchDeg
                            + "," + rollDeg + "," + unixTime + "000000\n");
                } catch (IOException ioe) {
                    Timber.e(ioe);
                }
            }
        }
    }

    /**
     * This will register all IMU listeners
     */
    public void register() {
        mSensorThread = new HandlerThread("Sensor thread",
                Process.THREAD_PRIORITY_MORE_FAVORABLE);
        mSensorThread.start();
        String imuFreq = mSharedPreferences.getString("prefImuFreq", "1");
        int mSensorRate = Integer.parseInt(imuFreq);
        // Blocks until looper is prepared, which is fairly quick
        Handler sensorHandler = new Handler(mSensorThread.getLooper());
        mSensorManager.registerListener(this, mAccel, mSensorRate, sensorHandler);
        mSensorManager.registerListener(this, mGyro, mSensorRate, sensorHandler);
        if (mRotationVector != null) {
            mSensorManager.registerListener(this, mRotationVector, mSensorRate, sensorHandler);
        }
    }

    /**
     * This will unregister all IMU listeners
     */
    public void unregister() {
        mSensorManager.unregisterListener(this, mAccel);
        mSensorManager.unregisterListener(this, mGyro);
        if (mRotationVector != null) {
            mSensorManager.unregisterListener(this, mRotationVector);
        }
        mSensorManager.unregisterListener(this);
        mSensorThread.quitSafely();
        stopRecording();
    }
}
