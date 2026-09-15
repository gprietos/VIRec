package io.a3dv.VIRec;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import timber.log.Timber;

/**
 * Renders a single recording session's video files and IMU/orientation/GPS CSVs, one section
 * per sensor plus one per camera. A section that was genuinely never recorded for this session
 * (video file missing, SensorCsvParser.Unavailable, or an empty sample list) hides itself
 * entirely -- label, content, and fallback text all gone -- rather than showing a placeholder.
 * A section whose file exists but failed to read (a real IOException) keeps its label visible
 * and shows an error message in its fallback text instead, since that's a real problem worth
 * surfacing rather than hiding.
 *
 * All 4 charts share a single time origin (the earliest first-sample timestamp across whichever
 * of the 3 sensor sources are actually available), so touching any chart can look up the nearest
 * sample in every source at that same instant and cross-highlight all charts (all datasets, not
 * just one) at once. Touching a chart also seeks the MAIN camera video to the matching instant,
 * and playing the main video sweeps the same crosshair across all 4 charts in step -- see
 * "Chart <-> main video sync" below.
 */
public class RecordingViewerActivity extends AppCompatActivity {

    // Retained so the touch-to-inspect feature (see setupCrossHighlighting) can look up the
    // nearest sample in any of the three sources after the charts that display them have been
    // built. Null means that source was unavailable/empty for this session.
    private List<SensorCsvParser.ImuSample> imuSamples;
    private List<SensorCsvParser.OrientationSample> orientationSamples;
    private List<SensorCsvParser.GpsSample> gpsSamples;

    // Current rotation (degrees, one of 0/90/180/270) applied to each TextureView via
    // View#setRotation() -- see the rotate buttons wired in onCreate. TextureView's content is
    // a normal hardware-accelerated View (a GL texture drawn through the regular view
    // hierarchy), so setRotation() genuinely rotates the decoded pixels -- unlike the old
    // VideoView/SurfaceView setup, whose separate compositor surface bypassed the View
    // transform pipeline and only resized the bounding box.
    private float mainVideoRotationDeg = 0f;
    private float frontVideoRotationDeg = 0f;

    // Each video's intrinsic (unrotated) pixel dimensions, captured once in its
    // setOnPreparedListener callback -- stored so the rotate button can recompute the
    // letterboxed fit (see resizeVideoToFit) without needing the MediaPlayer again.
    private int mainVideoIntrinsicWidth;
    private int mainVideoIntrinsicHeight;
    private int frontVideoIntrinsicWidth;
    private int frontVideoIntrinsicHeight;

    // Progress-polling Handler/Runnable pair per video (see setupVideoControls), stopped in
    // onPause()/onDestroy() via removeCallbacks to avoid leaking a repeating post-to-self after
    // the Activity goes away.
    private final Handler mainProgressHandler = new Handler(Looper.getMainLooper());
    private final Handler frontProgressHandler = new Handler(Looper.getMainLooper());
    private Runnable mainProgressRunnable;
    private Runnable frontProgressRunnable;

    // The MediaPlayer driving each TextureView, created in loadVideo() once its video file is
    // known to exist. TextureView (unlike the old VideoView) has no built-in playback controls
    // or MediaPlayer lifecycle management, so the app now owns these directly -- all playback
    // calls (isPlaying/pause/start/seekTo/getDuration/getCurrentPosition) go through them, and
    // they must be explicitly released (see onDestroy) to avoid leaking native resources. Null
    // until loadVideo() creates one (session dir missing, or that camera's video file absent).
    private MediaPlayer mainMediaPlayer;
    private MediaPlayer frontMediaPlayer;

    // --- Chart <-> main video sync (see highlightAllChartsAt/seekMainVideoTo/onMainVideoProgress) ---
    // The shared time origin (same clock as the sensor CSVs) that chart X values are relative
    // to, and the main TextureView itself, both set once in onCreate so the sync methods (called
    // from the chart touch listener and from the main video's progress-polling loop) can reach
    // them without threading extra parameters through.
    private long sharedT0;
    private TextureView mainVideoView;
    private LineChart[] allCharts;
    private TextView valuesAtTimestampTextView;
    // Captured in setupVideoControls (isMain == true only) so pauseMainVideoIfPlaying() and
    // seekMainVideoTo() can reflect a chart-driven pause/seek in the main video's own seek bar
    // and play/pause button -- see "Chart <-> main video sync".
    private SeekBar mainSeekBar;
    private ImageButton mainPlayPauseButton;
    // Set once in onCreate from prefOrientationUnits (display-only -- orientation.csv itself is
    // always written/parsed in degrees, see buildOrientationChart/buildValuesAtTimestampText).
    private boolean useRadians;
    // elapsedRealtimeNanos() at the start of recording, parsed from edge_epochs.txt -- the
    // reference point that converts between a chart's "seconds since sharedT0" X value and the
    // main video's playback position in milliseconds. Null if the file is missing, empty, or
    // fails to parse, in which case sync is silently disabled (see parseRecordingStartElapsedNs).
    private Long recordingStartElapsedNs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recording_viewer_activity);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        useRadians = "rad".equals(prefs.getString("prefOrientationUnits", "deg"));

        String sessionDirPath = getIntent().getStringExtra(
                RecordingsListActivity.EXTRA_SESSION_DIR);
        File sessionDir = sessionDirPath != null ? new File(sessionDirPath) : null;

        TextView mainVideoLabel = findViewById(R.id.label_video_main);
        FrameLayout mainVideoContainer = findViewById(R.id.video_main_container);
        TextureView mainVideoView = findViewById(R.id.video_main);
        TextView mainVideoFallback = findViewById(R.id.video_main_fallback);
        ImageButton mainVideoRotateButton = findViewById(R.id.button_rotate_main);
        View mainVideoControls = findViewById(R.id.video_main_controls);
        ImageButton mainPlayPauseButton = findViewById(R.id.button_playpause_main);
        SeekBar mainSeekBar = findViewById(R.id.seekbar_main);
        this.mainVideoView = mainVideoView;

        TextView frontVideoLabel = findViewById(R.id.label_video_front);
        FrameLayout frontVideoContainer = findViewById(R.id.video_front_container);
        TextureView frontVideoView = findViewById(R.id.video_front);
        TextView frontVideoFallback = findViewById(R.id.video_front_fallback);
        ImageButton frontVideoRotateButton = findViewById(R.id.button_rotate_front);
        View frontVideoControls = findViewById(R.id.video_front_controls);
        ImageButton frontPlayPauseButton = findViewById(R.id.button_playpause_front);
        SeekBar frontSeekBar = findViewById(R.id.seekbar_front);

        TextView gyroLabel = findViewById(R.id.label_chart_gyro);
        TextView accelLabel = findViewById(R.id.label_chart_accel);
        TextView orientationLabel = findViewById(R.id.label_chart_orientation);
        TextView gpsLabel = findViewById(R.id.label_chart_gps);

        final LineChart gyroChart = findViewById(R.id.chart_gyro);
        final LineChart accelChart = findViewById(R.id.chart_accel);
        final LineChart orientationChart = findViewById(R.id.chart_orientation);
        final LineChart gpsChart = findViewById(R.id.chart_gps);
        this.allCharts = new LineChart[]{gyroChart, accelChart, orientationChart, gpsChart};

        TextView gyroFallback = findViewById(R.id.chart_gyro_fallback);
        TextView accelFallback = findViewById(R.id.chart_accel_fallback);
        TextView orientationFallback = findViewById(R.id.chart_orientation_fallback);
        TextView gpsFallback = findViewById(R.id.chart_gps_fallback);

        // Chart-wrapping FrameLayouts (chart + overlay unit labels + zoom-reset button) --
        // hidden/shown as a whole via hideSection/showFallback so overlay children don't linger
        // visible when there's no data for a section (see those methods' doc).
        View gyroChartFrame = findViewById(R.id.chart_frame_gyro);
        View accelChartFrame = findViewById(R.id.chart_frame_accel);
        View orientationChartFrame = findViewById(R.id.chart_frame_orientation);
        View gpsChartFrame = findViewById(R.id.chart_frame_gps);

        final TextView valuesAtTimestampText = findViewById(R.id.values_at_timestamp_text);
        this.valuesAtTimestampTextView = valuesAtTimestampText;

        TextView orientationUnitLabel = findViewById(R.id.label_orientation_unit);
        orientationUnitLabel.setText(useRadians ? "rad" : "deg");
        TextView gyroUnitLabel = findViewById(R.id.label_gyro_yaxis_unit);
        gyroUnitLabel.setText(useRadians ? "rad/s" : "deg/s");

        ImageButton zoomResetGyroButton = findViewById(R.id.button_zoom_reset_gyro);
        ImageButton zoomResetAccelButton = findViewById(R.id.button_zoom_reset_accel);
        ImageButton zoomResetOrientationButton = findViewById(R.id.button_zoom_reset_orientation);
        ImageButton zoomResetGpsButton = findViewById(R.id.button_zoom_reset_gps);
        zoomResetGyroButton.setOnClickListener(v -> {
            gyroChart.fitScreen();
            gyroChart.invalidate();
        });
        zoomResetAccelButton.setOnClickListener(v -> {
            accelChart.fitScreen();
            accelChart.invalidate();
        });
        zoomResetOrientationButton.setOnClickListener(v -> {
            orientationChart.fitScreen();
            orientationChart.invalidate();
        });
        zoomResetGpsButton.setOnClickListener(v -> {
            gpsChart.fitScreen();
            gpsChart.invalidate();
        });

        setupRotateButton(mainVideoRotateButton, mainVideoView, mainVideoContainer, true);
        setupRotateButton(frontVideoRotateButton, frontVideoView, frontVideoContainer, false);
        setupVideoControls(mainPlayPauseButton, mainSeekBar, mainProgressHandler, true);
        setupVideoControls(frontPlayPauseButton, frontSeekBar, frontProgressHandler, false);

        if (sessionDir == null) {
            showVideoUnavailable(mainVideoContainer, mainVideoControls, mainVideoFallback);
            showVideoUnavailable(frontVideoContainer, frontVideoControls, frontVideoFallback);
            showFallback(gyroChartFrame, gyroFallback, "No session directory provided.");
            showFallback(accelChartFrame, accelFallback, "No session directory provided.");
            showFallback(orientationChartFrame, orientationFallback, "No session directory provided.");
            showFallback(gpsChartFrame, gpsFallback, "No session directory provided.");
            return;
        }

        recordingStartElapsedNs = parseRecordingStartElapsedNs(sessionDir);

        loadVideo(sessionDir, "movie.mp4", mainVideoLabel, mainVideoContainer, mainVideoView,
                mainVideoFallback, mainVideoControls, mainPlayPauseButton, mainSeekBar, true);
        loadVideo(sessionDir, "movie2.mp4", frontVideoLabel, frontVideoContainer, frontVideoView,
                frontVideoFallback, frontVideoControls, frontPlayPauseButton, frontSeekBar, false);

        // Parse every sensor source first (each falling back independently, same messages as
        // before) so a single shared t0 can be computed across all of them before any chart is
        // built.
        imuSamples = parseImuOrFallback(sessionDir, gyroLabel, gyroChartFrame, gyroFallback,
                accelLabel, accelChartFrame, accelFallback);
        orientationSamples = parseOrientationOrFallback(sessionDir, orientationLabel,
                orientationChartFrame, orientationFallback);
        gpsSamples = parseGpsOrFallback(sessionDir, gpsLabel, gpsChartFrame, gpsFallback);

        final long t0 = computeSharedT0(imuSamples, orientationSamples, gpsSamples);
        sharedT0 = t0;

        if (imuSamples != null) {
            buildImuCharts(imuSamples, t0, gyroChart, accelChart);
        }
        if (orientationSamples != null) {
            buildOrientationChart(orientationSamples, t0, orientationChart);
        }
        if (gpsSamples != null) {
            buildGpsChart(gpsSamples, t0, gpsChart);
        }

        setupCrossHighlighting(gyroChart, accelChart, orientationChart, gpsChart);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopProgressPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopProgressPolling();
        // TextureView, unlike the old VideoView, does not own/manage a MediaPlayer internally --
        // the app created these directly in loadVideo() and must release them here to avoid
        // leaking native decoder resources.
        if (mainMediaPlayer != null) {
            mainMediaPlayer.release();
            mainMediaPlayer = null;
        }
        if (frontMediaPlayer != null) {
            frontMediaPlayer.release();
            frontMediaPlayer = null;
        }
    }

    private void stopProgressPolling() {
        if (mainProgressRunnable != null) {
            mainProgressHandler.removeCallbacks(mainProgressRunnable);
        }
        if (frontProgressRunnable != null) {
            frontProgressHandler.removeCallbacks(frontProgressRunnable);
        }
    }

    /**
     * Cycles the given TextureView's rotation through 0 -> 90 -> 180 -> 270 -> 0 degrees on
     * every tap, applied via View#setRotation(), and recomputes the letterboxed fit for the new
     * angle (see resizeVideoToFit) using the video's already-known intrinsic dimensions -- no
     * MediaPlayer access needed here. isMain selects which of the two per-video
     * rotation/intrinsic-size fields this button tracks, so the main and front camera rotate
     * buttons operate fully independently of one another.
     */
    private void setupRotateButton(ImageButton button, final TextureView videoView,
                                    final FrameLayout container, final boolean isMain) {
        button.setOnClickListener(v -> {
            float current = isMain ? mainVideoRotationDeg : frontVideoRotationDeg;
            float next = (current + 90f) % 360f;
            if (isMain) {
                mainVideoRotationDeg = next;
            } else {
                frontVideoRotationDeg = next;
            }
            int intrinsicWidth = isMain ? mainVideoIntrinsicWidth : frontVideoIntrinsicWidth;
            int intrinsicHeight = isMain ? mainVideoIntrinsicHeight : frontVideoIntrinsicHeight;
            resizeVideoToFit(videoView, container, intrinsicWidth, intrinsicHeight, next);
        });
    }

    /**
     * Computes the largest size (preserving the video's own aspect ratio) that fits within the
     * container's current bounds, applies it as the TextureView's LayoutParams centered via
     * Gravity.CENTER, and applies rotationDeg via View#setRotation(). Any space in the container
     * not covered by that box reads as letterbox/pillarbox bars against the container's black
     * background (see video_main_container/video_front_container in the layout).
     *
     * setRotation() spins the View around its own center without changing its measured layout
     * size, so at 90/270 degrees the box computed here still uses the video's UNROTATED
     * width/height -- only the container bounds it is scaled against are swapped, since it's the
     * ROTATED visual bounding box (height x width once turned on its side) that actually needs
     * to fit inside the container. E.g. a 1280x720 video rotated 90 degrees visually occupies a
     * 720x1280-shaped footprint, so the fit is computed against (containerHeight, containerWidth)
     * instead of (containerWidth, containerHeight) at that angle.
     *
     * The container's width/height may not be known yet if a layout pass hasn't happened (e.g.
     * called from onPrepared before the first frame) -- in that case this defers itself via
     * container.post(...) until it is.
     */
    private void resizeVideoToFit(final TextureView videoView, final FrameLayout container,
                                   final int videoWidth, final int videoHeight,
                                   final float rotationDeg) {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        final int containerWidth = container.getWidth();
        final int containerHeight = container.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) {
            container.post(() -> resizeVideoToFit(videoView, container, videoWidth, videoHeight,
                    rotationDeg));
            return;
        }

        int normalizedDeg = ((Math.round(rotationDeg) % 360) + 360) % 360;
        boolean transposed = (normalizedDeg == 90 || normalizedDeg == 270);
        float fitWidth = transposed ? containerHeight : containerWidth;
        float fitHeight = transposed ? containerWidth : containerHeight;

        float scale = Math.min(fitWidth / videoWidth, fitHeight / videoHeight);
        int displayWidth = Math.max(1, Math.round(videoWidth * scale));
        int displayHeight = Math.max(1, Math.round(videoHeight * scale));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(displayWidth, displayHeight);
        params.gravity = Gravity.CENTER;
        videoView.setLayoutParams(params);
        videoView.setRotation(rotationDeg);
    }

    /**
     * Returns whichever of the two per-video MediaPlayer fields isMain selects -- mirrors the
     * mainProgressRunnable/frontProgressRunnable field-pair pattern used elsewhere in this file.
     * Null until loadVideo() creates the corresponding MediaPlayer (which hasn't necessarily
     * happened yet when setupVideoControls() below wires its listeners, since that runs before
     * the sessionDir-null check and before loadVideo() is called -- so every caller here must
     * null-check the result).
     */
    private MediaPlayer currentMediaPlayer(boolean isMain) {
        return isMain ? mainMediaPlayer : frontMediaPlayer;
    }

    /**
     * Wires one video's play/pause button and SeekBar. Playback itself is controlled through
     * whichever MediaPlayer currentMediaPlayer(isMain) resolves to at the time of each
     * interaction (not a parameter captured up front), since this method is called before that
     * MediaPlayer exists -- see currentMediaPlayer's doc. The SeekBar's progress is kept in sync
     * with playback via a Handler+Runnable polling loop that reposts itself every ~200ms only
     * while the video isPlaying() (so it naturally stops reposting the moment playback
     * pauses/stops, no separate "stop polling" call needed there) -- suppressed while the user is
     * actively dragging the thumb (onStartTrackingTouch/onStopTrackingTouch bracket a
     * userSeeking flag) so the drag and the poll loop don't fight over the SeekBar's progress
     * value, the standard Android SeekBar pattern.
     *
     * When isMain is true, each polling tick also calls onMainVideoProgress(...) to sweep the
     * chart crosshair in step with main-camera playback (see "Chart <-> main video sync").
     */
    private void setupVideoControls(final ImageButton playPauseButton, final SeekBar seekBar,
                                     final Handler progressHandler, final boolean isMain) {
        if (isMain) {
            mainSeekBar = seekBar;
            mainPlayPauseButton = playPauseButton;
        }
        final boolean[] userSeeking = {false};
        final Runnable[] progressRunnable = new Runnable[1];
        progressRunnable[0] = () -> {
            MediaPlayer mp = currentMediaPlayer(isMain);
            if (mp == null) {
                return;
            }
            if (!userSeeking[0]) {
                int pos = mp.getCurrentPosition();
                seekBar.setProgress(pos);
                if (isMain) {
                    onMainVideoProgress(pos);
                }
            }
            if (mp.isPlaying()) {
                progressHandler.postDelayed(progressRunnable[0], 200);
            }
        };
        if (isMain) {
            mainProgressRunnable = progressRunnable[0];
        } else {
            frontProgressRunnable = progressRunnable[0];
        }

        playPauseButton.setOnClickListener(v -> {
            MediaPlayer mp = currentMediaPlayer(isMain);
            if (mp == null) {
                return;
            }
            if (mp.isPlaying()) {
                mp.pause();
                playPauseButton.setImageResource(R.drawable.ic_baseline_play_arrow_24);
            } else {
                mp.start();
                playPauseButton.setImageResource(R.drawable.ic_baseline_pause_24);
                progressHandler.post(progressRunnable[0]);
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking[0] = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking[0] = false;
                MediaPlayer mp = currentMediaPlayer(isMain);
                if (mp != null) {
                    mp.seekTo(seekBar.getProgress());
                }
                if (isMain) {
                    onMainVideoProgress(seekBar.getProgress());
                }
            }
        });
    }

    private void loadVideo(File sessionDir, String filename, TextView label,
                            final FrameLayout container, final TextureView videoView,
                            TextView fallback, final View controls,
                            final ImageButton playPauseButton, final SeekBar seekBar,
                            final boolean isMain) {
        File video = new File(sessionDir, filename);
        if (!video.exists()) {
            // Genuinely absent: this camera was never recorded for this session -- hide the
            // whole section instead of showing a "no video" placeholder.
            label.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            controls.setVisibility(View.GONE);
            fallback.setVisibility(View.GONE);
            return;
        }

        label.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);
        controls.setVisibility(View.VISIBLE);
        fallback.setVisibility(View.GONE);

        final MediaPlayer mediaPlayer = new MediaPlayer();
        if (isMain) {
            mainMediaPlayer = mediaPlayer;
        } else {
            frontMediaPlayer = mediaPlayer;
        }

        try {
            mediaPlayer.setDataSource(video.getAbsolutePath());
        } catch (IOException e) {
            Timber.e(e, "Failed to set data source for %s", video);
            showVideoUnavailable(container, controls, fallback);
            return;
        }

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Timber.e("MediaPlayer error for %s: what=%d extra=%d", video, what, extra);
            showVideoUnavailable(container, controls, fallback);
            return true;
        });

        mediaPlayer.setOnPreparedListener(mp -> {
            int videoWidth = mp.getVideoWidth();
            int videoHeight = mp.getVideoHeight();
            if (isMain) {
                mainVideoIntrinsicWidth = videoWidth;
                mainVideoIntrinsicHeight = videoHeight;
            } else {
                frontVideoIntrinsicWidth = videoWidth;
                frontVideoIntrinsicHeight = videoHeight;
            }
            float rotationDeg = isMain ? mainVideoRotationDeg : frontVideoRotationDeg;
            // onPrepared fires once per loadVideo() call (this app never reloads a video into
            // the same TextureView), so this naturally applies only on first load -- it can't
            // re-trigger on later frames or clobber a rotation the user later sets via the
            // rotate button. Portrait-shot footage otherwise starts letterboxed to a sliver; default
            // it to landscape display. 270 (not 90) -- confirmed on-device that a plain 90-degree
            // default displayed upside down, so the correct landscape orientation is 180 degrees
            // further around. The rotate button's next tap then cycles onward from 270 instead of 0.
            if (videoHeight > videoWidth) {
                rotationDeg = 270f;
            }
            if (isMain) {
                mainVideoRotationDeg = rotationDeg;
            } else {
                frontVideoRotationDeg = rotationDeg;
            }
            resizeVideoToFit(videoView, container, videoWidth, videoHeight, rotationDeg);

            seekBar.setMax(mp.getDuration());
            playPauseButton.setImageResource(R.drawable.ic_baseline_play_arrow_24);
        });

        // TextureView's SurfaceTexture isn't available until the view is attached/laid out.
        if (videoView.isAvailable()) {
            mediaPlayer.setSurface(new Surface(videoView.getSurfaceTexture()));
            mediaPlayer.prepareAsync();
        } else {
            videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width,
                                                        int height) {
                    mediaPlayer.setSurface(new Surface(surface));
                    mediaPlayer.prepareAsync();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width,
                                                          int height) {
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            });
        }
    }

    private static void showVideoUnavailable(ViewGroup container, View controls,
                                               TextView fallback) {
        container.setVisibility(View.GONE);
        controls.setVisibility(View.GONE);
        fallback.setVisibility(View.VISIBLE);
    }

    /**
     * Parses edge_epochs.txt (written by TimeBaseManager) for the elapsedRealtimeNanos() value
     * recorded at the start of the session -- the same clock already used as the origin for the
     * sensor CSVs/charts (see computeSharedT0), so this is the reference point that converts a
     * chart's "seconds since sharedT0" X value into the main video's playback position and back.
     * File format: some number of '#'-prefixed comment lines, then tab-separated data rows whose
     * first column is elapsedRealtimeNanos() -- this reads the first non-comment, non-blank line
     * and parses its first field. Returns null (silently disabling sync, per the class doc) if
     * the file is missing, empty, or fails to parse -- video and charts still work independently
     * in that case.
     */
    private static Long parseRecordingStartElapsedNs(File sessionDir) {
        File file = new File(sessionDir, "edge_epochs.txt");
        if (!file.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] fields = trimmed.split("\t");
                if (fields.length == 0) {
                    continue;
                }
                return Long.parseLong(fields[0]);
            }
        } catch (IOException | NumberFormatException e) {
            Timber.e(e, "Failed to parse edge_epochs.txt for chart/video sync");
            return null;
        }
        return null;
    }

    private List<SensorCsvParser.ImuSample> parseImuOrFallback(
            File sessionDir, TextView gyroLabel, View gyroChartFrame, TextView gyroFallback,
            TextView accelLabel, View accelChartFrame, TextView accelFallback) {
        File csv = new File(sessionDir, "gyro_accel.csv");
        List<SensorCsvParser.ImuSample> samples;
        try {
            samples = SensorCsvParser.parseImu(csv);
        } catch (SensorCsvParser.Unavailable e) {
            hideSection(gyroLabel, gyroChartFrame, gyroFallback);
            hideSection(accelLabel, accelChartFrame, accelFallback);
            return null;
        } catch (IOException e) {
            Timber.e(e, "Failed to read %s", csv);
            showFallback(gyroChartFrame, gyroFallback, "Failed to read gyro_accel.csv");
            showFallback(accelChartFrame, accelFallback, "Failed to read gyro_accel.csv");
            return null;
        }

        if (samples.isEmpty()) {
            hideSection(gyroLabel, gyroChartFrame, gyroFallback);
            hideSection(accelLabel, accelChartFrame, accelFallback);
            return null;
        }
        return samples;
    }

    private List<SensorCsvParser.OrientationSample> parseOrientationOrFallback(
            File sessionDir, TextView label, View chartFrame, TextView fallback) {
        File csv = new File(sessionDir, "orientation.csv");
        List<SensorCsvParser.OrientationSample> samples;
        try {
            samples = SensorCsvParser.parseOrientation(csv);
        } catch (SensorCsvParser.Unavailable e) {
            hideSection(label, chartFrame, fallback);
            return null;
        } catch (IOException e) {
            Timber.e(e, "Failed to read %s", csv);
            showFallback(chartFrame, fallback, "Failed to read orientation.csv");
            return null;
        }

        if (samples.isEmpty()) {
            hideSection(label, chartFrame, fallback);
            return null;
        }
        return samples;
    }

    private List<SensorCsvParser.GpsSample> parseGpsOrFallback(
            File sessionDir, TextView label, View chartFrame, TextView fallback) {
        File csv = new File(sessionDir, "location.csv");
        List<SensorCsvParser.GpsSample> samples;
        try {
            samples = SensorCsvParser.parseGps(csv);
        } catch (SensorCsvParser.Unavailable e) {
            hideSection(label, chartFrame, fallback);
            return null;
        } catch (IOException e) {
            Timber.e(e, "Failed to read %s", csv);
            showFallback(chartFrame, fallback, "Failed to read location.csv");
            return null;
        }

        if (samples.isEmpty()) {
            hideSection(label, chartFrame, fallback);
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
            float gxVal = useRadians ? s.gx : (float) Math.toDegrees(s.gx);
            float gyVal = useRadians ? s.gy : (float) Math.toDegrees(s.gy);
            float gzVal = useRadians ? s.gz : (float) Math.toDegrees(s.gz);
            gx.add(new Entry(x, gxVal));
            gy.add(new Entry(x, gyVal));
            gz.add(new Entry(x, gzVal));
            ax.add(new Entry(x, s.ax));
            ay.add(new Entry(x, s.ay));
            az.add(new Entry(x, s.az));
        }

        setLineData(gyroChart,
                buildDataSet(gx, "gx", Color.RED),
                buildDataSet(gy, "gy", Color.GREEN),
                buildDataSet(gz, "gz", Color.BLUE));
        configureSingleAxisChart(gyroChart);

        setLineData(accelChart,
                buildDataSet(ax, "ax", Color.RED),
                buildDataSet(ay, "ay", Color.GREEN),
                buildDataSet(az, "az", Color.BLUE));
        configureSingleAxisChart(accelChart);
    }

    private void buildOrientationChart(List<SensorCsvParser.OrientationSample> samples, long t0,
                                        LineChart chart) {
        List<Entry> yaw = new ArrayList<>();
        List<Entry> pitch = new ArrayList<>();
        List<Entry> roll = new ArrayList<>();
        for (SensorCsvParser.OrientationSample s : samples) {
            float x = secondsSince(t0, s.t);
            float yawVal = useRadians ? (float) Math.toRadians(s.yaw) : (float) s.yaw;
            float pitchVal = useRadians ? (float) Math.toRadians(s.pitch) : (float) s.pitch;
            float rollVal = useRadians ? (float) Math.toRadians(s.roll) : (float) s.roll;
            yaw.add(new Entry(x, yawVal));
            pitch.add(new Entry(x, pitchVal));
            roll.add(new Entry(x, rollVal));
        }

        setLineData(chart,
                buildDataSet(yaw, "yaw", Color.RED),
                buildDataSet(pitch, "pitch", Color.GREEN),
                buildDataSet(roll, "roll", Color.BLUE));
        configureSingleAxisChart(chart);
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
        // GPS keeps its dual axis (altitude left, speed right) -- only the X-axis position is
        // normalized to match the other 3 charts.
        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
    }

    /**
     * Left+bottom-only axis styling shared by the gyro, accel, and orientation charts. GPS is
     * deliberately excluded -- it keeps its own dual left/right axis setup (see buildGpsChart).
     */
    private static void configureSingleAxisChart(LineChart chart) {
        chart.getAxisRight().setEnabled(false);
        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
    }

    /**
     * Wires an {@link OnChartValueSelectedListener} onto all 4 charts so touching any one of
     * them shows a consolidated "values at this timestamp" readout and cross-highlights the
     * same X position -- across every dataset, not just the first -- on all 4 charts (including
     * itself), then also seeks the main camera video to the matching instant (see
     * seekMainVideoTo). Since every chart shares one sharedT0, the touched chart's selected X
     * (already "seconds since sharedT0") is directly valid on all of them with no conversion.
     *
     * highlightValues(Highlight[]) is used (not the 2-/3-arg highlightValue(...) convenience
     * overloads, several of which default callListener to true or route through the 4-arg
     * highlightValue(x, y, dataSetIndex, callListener)) so that programmatically highlighting
     * the other charts does not itself re-invoke this listener. Verified by disassembling
     * MPAndroidChart v3.1.0's Chart#highlightValues(Highlight[]) from the Gradle cache: it only
     * assigns mIndicesToHighlight, calls setLastHighlighted(...), and invalidate() -- it never
     * touches the selection listener.
     */
    private void setupCrossHighlighting(final LineChart gyroChart, final LineChart accelChart,
                                         final LineChart orientationChart,
                                         final LineChart gpsChart) {
        OnChartValueSelectedListener listener = new OnChartValueSelectedListener() {
            @Override
            public void onValueSelected(Entry e, Highlight h) {
                float x = h.getX();
                highlightAllChartsAt(x);
                pauseMainVideoIfPlaying();
                seekMainVideoTo(x);
            }

            @Override
            public void onNothingSelected() {
            }
        };

        for (LineChart chart : allCharts) {
            chart.setOnChartValueSelectedListener(listener);
        }
    }

    /**
     * Shared by both directions of the chart <-> main video sync: a manual chart touch (via the
     * OnChartValueSelectedListener wired in setupCrossHighlighting) and main-video playback (via
     * onMainVideoProgress, called from the main video's progress-polling loop in
     * setupVideoControls) both funnel through here to update the "values at this timestamp"
     * readout and cross-highlight all 4 charts at the given X ("seconds since sharedT0").
     */
    private void highlightAllChartsAt(float x) {
        valuesAtTimestampTextView.setText(buildValuesAtTimestampText(sharedT0, x));
        for (LineChart chart : allCharts) {
            LineData data = chart.getData();
            if (data == null) {
                continue;
            }
            int count = data.getDataSetCount();
            Highlight[] highlights = new Highlight[count];
            for (int i = 0; i < count; i++) {
                ILineDataSet dataSet = data.getDataSetByIndex(i);
                Entry entry = dataSet.getEntryForXValue(x, Float.NaN);
                float y = entry != null ? entry.getY() : 0f;
                highlights[i] = new Highlight(x, y, i);
            }
            chart.highlightValues(highlights);
        }
    }

    /**
     * Seeks the main camera video to the instant corresponding to chart X (seconds since
     * sharedT0), converting through recordingStartElapsedNs (see parseRecordingStartElapsedNs).
     * A no-op if sync is disabled (recordingStartElapsedNs is null) or the video isn't prepared
     * yet (getDuration() <= 0, before onPrepared has fired).
     *
     * This only seeks -- it never calls start()/pause() -- so it does not itself set the video
     * "playing", and therefore does not itself trigger the progress-polling loop that would call
     * back into onMainVideoProgress/highlightAllChartsAt. If the video happens to already be
     * playing when a chart is touched, the next poll tick (within ~200ms) will report the new
     * position and re-highlight at essentially the same X -- a harmless refresh, not a feedback
     * loop, since that path never seeks the video again.
     */
    private void seekMainVideoTo(float x) {
        if (recordingStartElapsedNs == null || mainMediaPlayer == null) {
            return;
        }
        int duration = mainMediaPlayer.getDuration();
        if (duration <= 0) {
            return;
        }
        long selectedTimestampNs = sharedT0 + (long) (x * 1e9);
        long videoPositionMs = (selectedTimestampNs - recordingStartElapsedNs) / 1_000_000L;
        long clamped = Math.max(0L, Math.min(videoPositionMs, (long) duration));
        mainMediaPlayer.seekTo((int) clamped);
        if (mainSeekBar != null) {
            mainSeekBar.setProgress((int) clamped);
        }
    }

    /**
     * Pauses the main video (and reflects that in its own play/pause button and progress-polling
     * loop) if it's currently playing -- called just before a chart touch seeks it, so scrubbing
     * a chart while the video is playing doesn't leave it running past the newly-selected instant
     * (see onValueSelected/seekMainVideoTo, "Chart <-> main video sync").
     */
    private void pauseMainVideoIfPlaying() {
        if (mainMediaPlayer != null && mainMediaPlayer.isPlaying()) {
            mainMediaPlayer.pause();
            if (mainPlayPauseButton != null) {
                mainPlayPauseButton.setImageResource(R.drawable.ic_baseline_play_arrow_24);
            }
            mainProgressHandler.removeCallbacks(mainProgressRunnable);
        }
    }

    /**
     * Converts the main video's current playback position back to chart X (seconds since
     * sharedT0) via recordingStartElapsedNs, and cross-highlights all 4 charts there -- called
     * from the main video's progress-polling loop (see setupVideoControls) so the crosshair
     * visibly sweeps across the charts as the video plays. A no-op if sync is disabled
     * (recordingStartElapsedNs is null).
     */
    private void onMainVideoProgress(int positionMs) {
        if (recordingStartElapsedNs == null) {
            return;
        }
        long sensorTimestampNs = recordingStartElapsedNs + positionMs * 1_000_000L;
        float x = (sensorTimestampNs - sharedT0) / 1e9f;
        highlightAllChartsAt(x);
    }

    private String buildValuesAtTimestampText(long t0, float x) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Selected t = %.3f s", x));

        SensorCsvParser.ImuSample imu = findNearestImu(t0, x);
        if (imu != null) {
            double gxVal = useRadians ? imu.gx : Math.toDegrees(imu.gx);
            double gyVal = useRadians ? imu.gy : Math.toDegrees(imu.gy);
            double gzVal = useRadians ? imu.gz : Math.toDegrees(imu.gz);
            String gyroUnit = useRadians ? "rad/s" : "deg/s";
            sb.append(String.format(Locale.US,
                    "\nGyro (%s): x=%.3f  y=%.3f  z=%.3f", gyroUnit, gxVal, gyVal, gzVal));
            sb.append(String.format(Locale.US,
                    "\nAccel (m/s^2): x=%.3f  y=%.3f  z=%.3f", imu.ax, imu.ay, imu.az));
        }

        SensorCsvParser.OrientationSample orientation = findNearestOrientation(t0, x);
        if (orientation != null) {
            double yawVal = useRadians ? Math.toRadians(orientation.yaw) : orientation.yaw;
            double pitchVal = useRadians ? Math.toRadians(orientation.pitch) : orientation.pitch;
            double rollVal = useRadians ? Math.toRadians(orientation.roll) : orientation.roll;
            String unit = useRadians ? "rad" : "deg";
            sb.append(String.format(Locale.US,
                    "\nOrientation (%s): yaw=%.2f  pitch=%.2f  roll=%.2f",
                    unit, yawVal, pitchVal, rollVal));
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

    private static void showFallback(View chartContainer, TextView fallback, String message) {
        chartContainer.setVisibility(View.GONE);
        fallback.setVisibility(View.VISIBLE);
        fallback.setText(message);
    }

    /**
     * Hides an entire section -- label, chart container (and any overlay siblings inside it,
     * e.g. unit labels/zoom-reset button), and fallback text -- for data that was genuinely
     * never recorded (SensorCsvParser.Unavailable, or an empty sample list), as opposed to a
     * real IOException reading an existing file (see showFallback, which keeps the label
     * visible and surfaces the error instead). chartContainer is the whole chart-wrapping
     * FrameLayout, not just the LineChart, so overlay children hide/show along with it.
     */
    private static void hideSection(TextView label, View chartContainer, TextView fallback) {
        label.setVisibility(View.GONE);
        chartContainer.setVisibility(View.GONE);
        fallback.setVisibility(View.GONE);
    }
}
