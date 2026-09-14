package io.a3dv.VIRec;

import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import timber.log.Timber;

/**
 * Renders a single recording session's video files and IMU/orientation/GPS CSVs, one section
 * per sensor plus one per camera. If a sensor was unavailable at recording time (see
 * SensorCsvParser.Unavailable) or its file is missing, that section falls back to a plain-text
 * explanation instead of blanking the whole screen; same idea for a missing movie file.
 *
 * All 4 charts share a single time origin (the earliest first-sample timestamp across whichever
 * of the 3 sensor sources are actually available), so touching any chart can look up the nearest
 * sample in every source at that same instant and cross-highlight all charts at once.
 */
public class RecordingViewerActivity extends AppCompatActivity {

    // Retained so the touch-to-inspect feature (see setupCrossHighlighting) can look up the
    // nearest sample in any of the three sources after the charts that display them have been
    // built. Null means that source was unavailable/empty for this session.
    private List<SensorCsvParser.ImuSample> imuSamples;
    private List<SensorCsvParser.OrientationSample> orientationSamples;
    private List<SensorCsvParser.GpsSample> gpsSamples;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recording_viewer_activity);

        String sessionDirPath = getIntent().getStringExtra(
                RecordingsListActivity.EXTRA_SESSION_DIR);
        File sessionDir = sessionDirPath != null ? new File(sessionDirPath) : null;

        VideoView mainVideoView = findViewById(R.id.video_main);
        TextView mainVideoFallback = findViewById(R.id.video_main_fallback);
        VideoView frontVideoView = findViewById(R.id.video_front);
        TextView frontVideoFallback = findViewById(R.id.video_front_fallback);

        final LineChart gyroChart = findViewById(R.id.chart_gyro);
        final LineChart accelChart = findViewById(R.id.chart_accel);
        final LineChart orientationChart = findViewById(R.id.chart_orientation);
        final LineChart gpsChart = findViewById(R.id.chart_gps);

        TextView gyroFallback = findViewById(R.id.chart_gyro_fallback);
        TextView accelFallback = findViewById(R.id.chart_accel_fallback);
        TextView orientationFallback = findViewById(R.id.chart_orientation_fallback);
        TextView gpsFallback = findViewById(R.id.chart_gps_fallback);

        final TextView valuesAtTimestampText = findViewById(R.id.values_at_timestamp_text);

        if (sessionDir == null) {
            showVideoFallback(mainVideoView, mainVideoFallback);
            showVideoFallback(frontVideoView, frontVideoFallback);
            showFallback(gyroChart, gyroFallback, "No session directory provided.");
            showFallback(accelChart, accelFallback, "No session directory provided.");
            showFallback(orientationChart, orientationFallback, "No session directory provided.");
            showFallback(gpsChart, gpsFallback, "No session directory provided.");
            return;
        }

        loadVideo(sessionDir, "movie.mp4", mainVideoView, mainVideoFallback);
        loadVideo(sessionDir, "movie2.mp4", frontVideoView, frontVideoFallback);

        // Parse every sensor source first (each falling back independently, same messages as
        // before) so a single shared t0 can be computed across all of them before any chart is
        // built.
        imuSamples = parseImuOrFallback(sessionDir, gyroChart, gyroFallback, accelChart,
                accelFallback);
        orientationSamples = parseOrientationOrFallback(sessionDir, orientationChart,
                orientationFallback);
        gpsSamples = parseGpsOrFallback(sessionDir, gpsChart, gpsFallback);

        final long t0 = computeSharedT0(imuSamples, orientationSamples, gpsSamples);

        if (imuSamples != null) {
            buildImuCharts(imuSamples, t0, gyroChart, accelChart);
        }
        if (orientationSamples != null) {
            buildOrientationChart(orientationSamples, t0, orientationChart);
        }
        if (gpsSamples != null) {
            buildGpsChart(gpsSamples, t0, gpsChart);
        }

        setupCrossHighlighting(t0, valuesAtTimestampText, gyroChart, accelChart,
                orientationChart, gpsChart);
    }

    private void loadVideo(File sessionDir, String filename, VideoView videoView,
                            TextView fallback) {
        File video = new File(sessionDir, filename);
        if (!video.exists()) {
            showVideoFallback(videoView, fallback);
            return;
        }

        videoView.setVisibility(View.VISIBLE);
        fallback.setVisibility(View.GONE);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setVideoURI(Uri.fromFile(video));
    }

    private static void showVideoFallback(VideoView videoView, TextView fallback) {
        videoView.setVisibility(View.GONE);
        fallback.setVisibility(View.VISIBLE);
    }

    private List<SensorCsvParser.ImuSample> parseImuOrFallback(
            File sessionDir, LineChart gyroChart, TextView gyroFallback,
            LineChart accelChart, TextView accelFallback) {
        File csv = new File(sessionDir, "gyro_accel.csv");
        List<SensorCsvParser.ImuSample> samples;
        try {
            samples = SensorCsvParser.parseImu(csv);
        } catch (SensorCsvParser.Unavailable e) {
            showFallback(gyroChart, gyroFallback, e.getMessage());
            showFallback(accelChart, accelFallback, e.getMessage());
            return null;
        } catch (IOException e) {
            Timber.e(e, "Failed to read %s", csv);
            showFallback(gyroChart, gyroFallback, "Failed to read gyro_accel.csv");
            showFallback(accelChart, accelFallback, "Failed to read gyro_accel.csv");
            return null;
        }

        if (samples.isEmpty()) {
            showFallback(gyroChart, gyroFallback, "No gyro/accel samples recorded.");
            showFallback(accelChart, accelFallback, "No gyro/accel samples recorded.");
            return null;
        }
        return samples;
    }

    private List<SensorCsvParser.OrientationSample> parseOrientationOrFallback(
            File sessionDir, LineChart chart, TextView fallback) {
        File csv = new File(sessionDir, "orientation.csv");
        List<SensorCsvParser.OrientationSample> samples;
        try {
            samples = SensorCsvParser.parseOrientation(csv);
        } catch (SensorCsvParser.Unavailable e) {
            showFallback(chart, fallback, e.getMessage());
            return null;
        } catch (IOException e) {
            Timber.e(e, "Failed to read %s", csv);
            showFallback(chart, fallback, "Failed to read orientation.csv");
            return null;
        }

        if (samples.isEmpty()) {
            showFallback(chart, fallback, "No orientation samples recorded.");
            return null;
        }
        return samples;
    }

    private List<SensorCsvParser.GpsSample> parseGpsOrFallback(
            File sessionDir, LineChart chart, TextView fallback) {
        File csv = new File(sessionDir, "location.csv");
        List<SensorCsvParser.GpsSample> samples;
        try {
            samples = SensorCsvParser.parseGps(csv);
        } catch (SensorCsvParser.Unavailable e) {
            showFallback(chart, fallback, e.getMessage());
            return null;
        } catch (IOException e) {
            Timber.e(e, "Failed to read %s", csv);
            showFallback(chart, fallback, "Failed to read location.csv");
            return null;
        }

        if (samples.isEmpty()) {
            showFallback(chart, fallback, "No GPS fixes recorded.");
            return null;
        }
        return samples;
    }

    /**
     * The shared time origin used as the X-axis zero across all 4 charts: the earliest
     * first-sample timestamp among whichever of the 3 sources are actually available. Falls
     * back to 0 if none are (every chart will then be showing its fallback text anyway).
     */
    private static long computeSharedT0(List<SensorCsvParser.ImuSample> imu,
                                         List<SensorCsvParser.OrientationSample> orientation,
                                         List<SensorCsvParser.GpsSample> gps) {
        Long t0 = null;
        if (imu != null && !imu.isEmpty()) {
            t0 = imu.get(0).t;
        }
        if (orientation != null && !orientation.isEmpty()) {
            long t = orientation.get(0).t;
            if (t0 == null || t < t0) {
                t0 = t;
            }
        }
        if (gps != null && !gps.isEmpty()) {
            long t = gps.get(0).t;
            if (t0 == null || t < t0) {
                t0 = t;
            }
        }
        return t0 != null ? t0 : 0L;
    }

    private void buildImuCharts(List<SensorCsvParser.ImuSample> samples, long t0,
                                 LineChart gyroChart, LineChart accelChart) {
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

    private void buildOrientationChart(List<SensorCsvParser.OrientationSample> samples, long t0,
                                        LineChart chart) {
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

    private void buildGpsChart(List<SensorCsvParser.GpsSample> samples, long t0,
                                LineChart chart) {
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

    /**
     * Wires an {@link OnChartValueSelectedListener} onto all 4 charts so touching any one of
     * them shows a consolidated "values at this timestamp" readout and cross-highlights the
     * same X position on the other charts. Since every chart now shares one t0, the touched
     * chart's selected X (already "seconds since shared t0") is directly valid on all of them
     * with no conversion.
     *
     * highlightValue(x, dataSetIndex, false) is used (not the 2-arg overload, which defaults
     * callListener to true) so that programmatically highlighting the other charts does not
     * itself re-invoke this listener -- verified against MPAndroidChart v3.1.0's
     * Chart#highlightValue(Highlight, boolean), which only calls the selection listener when
     * that boolean is true.
     */
    private void setupCrossHighlighting(final long t0, final TextView valuesAtTimestampText,
                                         final LineChart gyroChart, final LineChart accelChart,
                                         final LineChart orientationChart,
                                         final LineChart gpsChart) {
        final LineChart[] allCharts = {gyroChart, accelChart, orientationChart, gpsChart};

        OnChartValueSelectedListener listener = new OnChartValueSelectedListener() {
            @Override
            public void onValueSelected(Entry e, Highlight h) {
                float x = h.getX();
                valuesAtTimestampText.setText(buildValuesAtTimestampText(t0, x));
                for (LineChart chart : allCharts) {
                    if (chart.getData() != null) {
                        chart.highlightValue(x, 0, false);
                    }
                }
            }

            @Override
            public void onNothingSelected() {
            }
        };

        for (LineChart chart : allCharts) {
            chart.setOnChartValueSelectedListener(listener);
        }
    }

    private String buildValuesAtTimestampText(long t0, float x) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Selected t = %.3f s", x));

        SensorCsvParser.ImuSample imu = findNearestImu(t0, x);
        if (imu != null) {
            sb.append(String.format(Locale.US,
                    "\nGyro (rad/s): x=%.3f  y=%.3f  z=%.3f", imu.gx, imu.gy, imu.gz));
            sb.append(String.format(Locale.US,
                    "\nAccel (m/s^2): x=%.3f  y=%.3f  z=%.3f", imu.ax, imu.ay, imu.az));
        }

        SensorCsvParser.OrientationSample orientation = findNearestOrientation(t0, x);
        if (orientation != null) {
            sb.append(String.format(Locale.US,
                    "\nOrientation (deg): yaw=%.2f  pitch=%.2f  roll=%.2f",
                    orientation.yaw, orientation.pitch, orientation.roll));
        }

        SensorCsvParser.GpsSample gps = findNearestGps(t0, x);
        if (gps != null) {
            sb.append(String.format(Locale.US,
                    "\nGPS: altitude=%.2f m  speed=%.2f m/s", gps.alt, gps.speed));
        }

        return sb.toString();
    }

    private SensorCsvParser.ImuSample findNearestImu(long t0, float x) {
        if (imuSamples == null || imuSamples.isEmpty()) {
            return null;
        }
        SensorCsvParser.ImuSample best = imuSamples.get(0);
        float bestDiff = Math.abs(secondsSince(t0, best.t) - x);
        for (SensorCsvParser.ImuSample s : imuSamples) {
            float diff = Math.abs(secondsSince(t0, s.t) - x);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    private SensorCsvParser.OrientationSample findNearestOrientation(long t0, float x) {
        if (orientationSamples == null || orientationSamples.isEmpty()) {
            return null;
        }
        SensorCsvParser.OrientationSample best = orientationSamples.get(0);
        float bestDiff = Math.abs(secondsSince(t0, best.t) - x);
        for (SensorCsvParser.OrientationSample s : orientationSamples) {
            float diff = Math.abs(secondsSince(t0, s.t) - x);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    private SensorCsvParser.GpsSample findNearestGps(long t0, float x) {
        if (gpsSamples == null || gpsSamples.isEmpty()) {
            return null;
        }
        SensorCsvParser.GpsSample best = gpsSamples.get(0);
        float bestDiff = Math.abs(secondsSince(t0, best.t) - x);
        for (SensorCsvParser.GpsSample s : gpsSamples) {
            float diff = Math.abs(secondsSince(t0, s.t) - x);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
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
