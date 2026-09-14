package io.a3dv.VIRec;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * Renders a single recording session's IMU/orientation/GPS CSVs as line charts, one section
 * per sensor. If a sensor was unavailable at recording time (see SensorCsvParser.Unavailable)
 * or its file is missing, that section falls back to a plain-text explanation instead of
 * blanking the whole screen.
 */
public class RecordingViewerActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recording_viewer_activity);

        String sessionDirPath = getIntent().getStringExtra(
                RecordingsListActivity.EXTRA_SESSION_DIR);
        File sessionDir = sessionDirPath != null ? new File(sessionDirPath) : null;

        LineChart gyroChart = findViewById(R.id.chart_gyro);
        LineChart accelChart = findViewById(R.id.chart_accel);
        LineChart orientationChart = findViewById(R.id.chart_orientation);
        LineChart gpsChart = findViewById(R.id.chart_gps);

        TextView gyroFallback = findViewById(R.id.chart_gyro_fallback);
        TextView accelFallback = findViewById(R.id.chart_accel_fallback);
        TextView orientationFallback = findViewById(R.id.chart_orientation_fallback);
        TextView gpsFallback = findViewById(R.id.chart_gps_fallback);

        if (sessionDir == null) {
            showFallback(gyroChart, gyroFallback, "No session directory provided.");
            showFallback(accelChart, accelFallback, "No session directory provided.");
            showFallback(orientationChart, orientationFallback, "No session directory provided.");
            showFallback(gpsChart, gpsFallback, "No session directory provided.");
            return;
        }

        loadImuCharts(sessionDir, gyroChart, gyroFallback, accelChart, accelFallback);
        loadOrientationChart(sessionDir, orientationChart, orientationFallback);
        loadGpsChart(sessionDir, gpsChart, gpsFallback);
    }

    private void loadImuCharts(File sessionDir, LineChart gyroChart, TextView gyroFallback,
                                LineChart accelChart, TextView accelFallback) {
        File csv = new File(sessionDir, "gyro_accel.csv");
        List<SensorCsvParser.ImuSample> samples;
        try {
            samples = SensorCsvParser.parseImu(csv);
        } catch (SensorCsvParser.Unavailable e) {
            showFallback(gyroChart, gyroFallback, e.getMessage());
            showFallback(accelChart, accelFallback, e.getMessage());
            return;
        } catch (IOException e) {
            Timber.e(e, "Failed to read %s", csv);
            showFallback(gyroChart, gyroFallback, "Failed to read gyro_accel.csv");
            showFallback(accelChart, accelFallback, "Failed to read gyro_accel.csv");
            return;
        }

        if (samples.isEmpty()) {
            showFallback(gyroChart, gyroFallback, "No gyro/accel samples recorded.");
            showFallback(accelChart, accelFallback, "No gyro/accel samples recorded.");
            return;
        }

        long t0 = samples.get(0).t;
        List<Entry> gx = new ArrayList<>();
        List<Entry> gy = new ArrayList<>();
        List<Entry> gz = new ArrayList<>();
        List<Entry> ax = new ArrayList<>();
        List<Entry> ay = new ArrayList<>();
        List<Entry> az = new ArrayList<>();
        for (SensorCsvParser.ImuSample s : samples) {
            float x = secondsSince(t0, s.t);
            gx.add(new Entry(x, s.gx));
            gy.add(new Entry(x, s.gy));
            gz.add(new Entry(x, s.gz));
            ax.add(new Entry(x, s.ax));
            ay.add(new Entry(x, s.ay));
            az.add(new Entry(x, s.az));
        }

        setLineData(gyroChart,
                buildDataSet(gx, "gx", Color.RED),
                buildDataSet(gy, "gy", Color.GREEN),
                buildDataSet(gz, "gz", Color.BLUE));

        setLineData(accelChart,
                buildDataSet(ax, "ax", Color.RED),
                buildDataSet(ay, "ay", Color.GREEN),
                buildDataSet(az, "az", Color.BLUE));
    }

    private void loadOrientationChart(File sessionDir, LineChart chart, TextView fallback) {
        File csv = new File(sessionDir, "orientation.csv");
        List<SensorCsvParser.OrientationSample> samples;
        try {
            samples = SensorCsvParser.parseOrientation(csv);
        } catch (SensorCsvParser.Unavailable e) {
            showFallback(chart, fallback, e.getMessage());
            return;
        } catch (IOException e) {
            Timber.e(e, "Failed to read %s", csv);
            showFallback(chart, fallback, "Failed to read orientation.csv");
            return;
        }

        if (samples.isEmpty()) {
            showFallback(chart, fallback, "No orientation samples recorded.");
            return;
        }

        long t0 = samples.get(0).t;
        List<Entry> yaw = new ArrayList<>();
        List<Entry> pitch = new ArrayList<>();
        List<Entry> roll = new ArrayList<>();
        for (SensorCsvParser.OrientationSample s : samples) {
            float x = secondsSince(t0, s.t);
            yaw.add(new Entry(x, (float) s.yaw));
            pitch.add(new Entry(x, (float) s.pitch));
            roll.add(new Entry(x, (float) s.roll));
        }

        setLineData(chart,
                buildDataSet(yaw, "yaw", Color.RED),
                buildDataSet(pitch, "pitch", Color.GREEN),
                buildDataSet(roll, "roll", Color.BLUE));
    }

    private void loadGpsChart(File sessionDir, LineChart chart, TextView fallback) {
        File csv = new File(sessionDir, "location.csv");
        List<SensorCsvParser.GpsSample> samples;
        try {
            samples = SensorCsvParser.parseGps(csv);
        } catch (SensorCsvParser.Unavailable e) {
            showFallback(chart, fallback, e.getMessage());
            return;
        } catch (IOException e) {
            Timber.e(e, "Failed to read %s", csv);
            showFallback(chart, fallback, "Failed to read location.csv");
            return;
        }

        if (samples.isEmpty()) {
            showFallback(chart, fallback, "No GPS fixes recorded.");
            return;
        }

        long t0 = samples.get(0).t;
        List<Entry> altitude = new ArrayList<>();
        List<Entry> speed = new ArrayList<>();
        for (SensorCsvParser.GpsSample s : samples) {
            float x = secondsSince(t0, s.t);
            altitude.add(new Entry(x, (float) s.alt));
            speed.add(new Entry(x, s.speed));
        }

        LineDataSet altitudeSet = buildDataSet(altitude, "altitude (m)", Color.RED);
        LineDataSet speedSet = buildDataSet(speed, "speed (m/s)", Color.BLUE);
        speedSet.setAxisDependency(YAxis.AxisDependency.RIGHT);

        chart.getAxisRight().setEnabled(true);
        setLineData(chart, altitudeSet, speedSet);
    }

    private static float secondsSince(long t0Ns, long tNs) {
        return (tNs - t0Ns) / 1e9f;
    }

    private static LineDataSet buildDataSet(List<Entry> entries, String label, int color) {
        LineDataSet dataSet = new LineDataSet(entries, label);
        dataSet.setColor(color);
        dataSet.setDrawCircles(false);
        dataSet.setDrawValues(false);
        return dataSet;
    }

    private static void setLineData(LineChart chart, LineDataSet... dataSets) {
        LineData data = new LineData(dataSets);
        chart.setData(data);
        chart.getDescription().setEnabled(false);
        chart.invalidate();
    }

    private static void showFallback(LineChart chart, TextView fallback, String message) {
        chart.setVisibility(View.GONE);
        fallback.setVisibility(View.VISIBLE);
        fallback.setText(message);
    }
}
