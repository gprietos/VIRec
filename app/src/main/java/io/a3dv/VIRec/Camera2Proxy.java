package io.a3dv.VIRec;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;
import androidx.annotation.NonNull;

import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import java.util.List;

import timber.log.Timber;

public class Camera2Proxy {
    private final Activity mActivity;
    private static SharedPreferences mSharedPreferences;
    private String mCameraIdStr = "";
    private final boolean mSecondCamera;
    private Size mPreviewSize;
    private Size mVideoSize;
    private final CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private Rect sensorArraySize;
    private Integer mTimeSourceValue;

    private CaptureRequest mPreviewRequest;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private ImageReader mImageReader;
    private Surface mPreviewSurface;
    private SurfaceTexture mPreviewSurfaceTexture = null;

    /** Live JPEG tap for StreamingServer, off by default. Wired up as a second simultaneous
     * ImageReader target of the repeating capture request only when a listener is set (before
     * configureCamera()/openCamera() runs), so the extra YUV->JPEG conversion work never
     * happens when nobody's watching. */
    public interface FrameStreamListener {
        void onJpegFrame(byte[] jpegBytes, long timestampNs);
    }

    private FrameStreamListener mFrameStreamListener;
    private Handler mImageReaderHandler;
    private HandlerThread mImageReaderThread;
    private long mLastStreamedFrameElapsedMs = 0;

    public void setFrameStreamListener(FrameStreamListener listener) {
        mFrameStreamListener = listener;
    }

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Wait until the CONTROL_AF_MODE is in auto.
     */
    private static final int STATE_WAITING_AUTO = 1;

    /**
     * Trigger auto focus algorithm.
     */
    private static final int STATE_TRIGGER_AUTO = 2;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 3;

    /**
     * Camera state: Focus distance is locked.
     */
    private static final int STATE_FOCUS_LOCKED = 4;
    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mFocusCaptureCallback
     */
    private int mState = STATE_PREVIEW;

    private BufferedWriter mFrameMetadataWriter = null;

    private volatile boolean mRecordingMetadata = false;

    private final FocalLengthHelper mFocalLengthHelper = new FocalLengthHelper();

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Timber.d("onOpened");
            mCameraDevice = camera;
            initPreviewRequest();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Timber.d("onDisconnected");
            releaseCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Timber.w("Camera Open failed with error %d", error);
            releaseCamera();
        }
    };

    public Integer getmTimeSourceValue() {
        return mTimeSourceValue;
    }

    public Size getmVideoSize() {
        return mVideoSize;
    }

    public void startRecordingCaptureResult(String captureResultFile) {
        try {
            if (mFrameMetadataWriter != null) {
                try {
                    mFrameMetadataWriter.flush();
                    mFrameMetadataWriter.close();
                    Timber.d("Flushing results!");
                } catch (IOException err) {
                    Timber.e(err, "IOException in closing an earlier frameMetadataWriter.");
                }
            }
            mFrameMetadataWriter = new BufferedWriter(
                    new FileWriter(captureResultFile, true));
            String header = "Timestamp[nanosec],fx[px],fy[px],Frame No.," +
                    "Exposure time[nanosec],Sensor frame duration[nanosec]," +
                    "Frame readout time[nanosec]," +
                    "ISO,Focal length,Focus distance,AF mode,Unix time[nanosec]";

            mFrameMetadataWriter.write(header + "\n");
            mRecordingMetadata = true;
        } catch (IOException err) {
            Timber.e(err, "IOException in opening frameMetadataWriter at %s",
                    captureResultFile);
        }
    }

//    public void resumeRecordingCaptureResult() {
//        mRecordingMetadata = true;
//    }
//
//    public void pauseRecordingCaptureResult() {
//        mRecordingMetadata = false;
//    }

    public void stopRecordingCaptureResult() {
        if (mRecordingMetadata) {
            mRecordingMetadata = false;
        }
        if (mFrameMetadataWriter != null) {
            try {
                mFrameMetadataWriter.flush();
                mFrameMetadataWriter.close();
            } catch (IOException err) {
                Timber.e(err, "IOException in closing frameMetadataWriter.");
            }
            mFrameMetadataWriter = null;
        }
    }

    public Camera2Proxy(Activity activity, boolean secondCamera) {
        mActivity = activity;
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        mCameraManager = (CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE);
        mSecondCamera = secondCamera; // If it's the second camera
    }

    public Size configureCamera() {
        try {
            if (mSecondCamera) {
                mCameraIdStr = mSharedPreferences.getString("prefCamera2", "1");
            } else {
                mCameraIdStr = mSharedPreferences.getString("prefCamera", "0");
            }

            CameraCharacteristics mCameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraIdStr);

            String imageSize = mSharedPreferences.getString("prefSizeRaw",
                    DesiredCameraSetting.mDesiredFrameSize);
            int width = Integer.parseInt(imageSize.substring(0, imageSize.lastIndexOf("x")));
            int height = Integer.parseInt(imageSize.substring(imageSize.lastIndexOf("x") + 1));

            sensorArraySize = mCameraCharacteristics.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            mTimeSourceValue = mCameraCharacteristics.get(
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);

            StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics
                    .SCALER_STREAM_CONFIGURATION_MAP);

            Size[] videoSizeChoices = map.getOutputSizes(MediaRecorder.class);
            mVideoSize = CameraUtils.chooseVideoSize(videoSizeChoices, width, height, width);

            mFocalLengthHelper.setLensParams(mCameraCharacteristics);
            mFocalLengthHelper.setmImageSize(mVideoSize);

            mPreviewSize = CameraUtils.chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    width, height, mVideoSize);
            Timber.d("Video size %s preview size %s.",
                    mVideoSize.toString(), mPreviewSize.toString());

        } catch (CameraAccessException e) {
            Timber.e(e);
        }
        return mPreviewSize;
    }

    @SuppressLint("MissingPermission")
    public void openCamera() {
        Timber.v("openCamera");
        startBackgroundThread();
        if (mCameraIdStr.isEmpty()) {
            configureCamera();
        }
        try {
            mCameraManager.openCamera(mCameraIdStr, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Timber.e(e);
        }
    }

    public void releaseCamera() {
        Timber.v("releaseCamera");
        if (null != mCaptureSession) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        mPreviewSurfaceTexture = null;
        mCameraIdStr = "";
        stopRecordingCaptureResult();
        stopBackgroundThread();
        stopImageReaderThread();
    }

    public void setPreviewSurfaceTexture(SurfaceTexture surfaceTexture) {
        mPreviewSurfaceTexture = surfaceTexture;
    }

    private static class NumExpoIso {
        public Long mNumber;
        public Long mExposureNanos;
        public Integer mIso;

        public NumExpoIso(Long number, Long expoNanos, Integer iso) {
            mNumber = number;
            mExposureNanos = expoNanos;
            mIso = iso;
        }
    }

    private final int kMaxExpoSamples = 10;
    private final ArrayList<NumExpoIso> expoStats = new ArrayList<>(kMaxExpoSamples);

    private void setExposureAndIso() {
        long exposureNanos = DesiredCameraSetting.mDesiredExposureTime;
        long desiredIsoL = 30L * 30000000L / exposureNanos;
        Integer desiredIso = (int) desiredIsoL;
        if (!expoStats.isEmpty()) {
            int index = expoStats.size() / 2;
            Long actualExpo = expoStats.get(index).mExposureNanos;
            Integer actualIso = expoStats.get(index).mIso;
            if (actualExpo != null && actualIso != null) {
                if (actualExpo <= exposureNanos) {
                    exposureNanos = actualExpo;
                    desiredIso = actualIso;
                } else {
                    desiredIsoL = actualIso * actualExpo / exposureNanos;
                    desiredIso = (int) desiredIsoL;
                }
            } // else may occur on an emulated device.
        }

        boolean manualControl = mSharedPreferences.getBoolean("switchManualControl", false);
        if (manualControl) {
            float exposureTimeMs = (float) exposureNanos / 1e6f;
            String exposureTimeMsStr = mSharedPreferences.getString(
                    "prefExposureTime", String.valueOf(exposureTimeMs));
            exposureNanos = (long) (Float.parseFloat(exposureTimeMsStr) * 1e6f);
            String desiredIsoStr = mSharedPreferences.getString("prefISO", String.valueOf(desiredIso));
            desiredIso = Integer.parseInt(desiredIsoStr);
        }

        // fix exposure
        mPreviewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);

        mPreviewRequestBuilder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNanos);
        Timber.d("Exposure time set to %d", exposureNanos);

        // fix ISO
        mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, desiredIso);
        Timber.d("ISO set to %d", desiredIso);
    }

    private void initPreviewRequest() {
        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            // Set control elements, we want auto white balance
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);

            // We disable customizing focus distance by user input because
            // it is less flexible than tap to focus.
//            boolean manualControl = mSharedPreferences.getBoolean("switchManualControl", false);
//            if (manualControl) {
//                String focus = mSharedPreferences.getString("prefFocusDistance", "5.0");
//                Float focusDistance = Float.parseFloat(focus);
//                mPreviewRequestBuilder.set(
//                        CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
//                mPreviewRequestBuilder.set(
//                        CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance);
//                Timber.d("Focus distance set to %f", focusDistance);
//            }

            List<Surface> surfaces = new ArrayList<>();

            if (mPreviewSurfaceTexture != null && mPreviewSurface == null) { // use texture view
                mPreviewSurfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),
                        mPreviewSize.getHeight());
                mPreviewSurface = new Surface(mPreviewSurfaceTexture);
            }
            surfaces.add(mPreviewSurface);
            mPreviewRequestBuilder.addTarget(mPreviewSurface);

            if (mFrameStreamListener != null) {
                setUpImageReader();
                surfaces.add(mImageReader.getSurface());
                mPreviewRequestBuilder.addTarget(mImageReader.getSurface());
            }

            mCameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mCaptureSession = session;
                            mPreviewRequest = mPreviewRequestBuilder.build();
                            startPreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Timber.w("ConfigureFailed. session: mCaptureSession");
                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Timber.e(e);
        }
    }

    /**
     * Sets up mImageReader as a second, simultaneous target of the repeating preview/record
     * capture request, feeding onImageAvailable below. Only called when mFrameStreamListener is
     * non-null (see initPreviewRequest), so streaming carries no cost when nobody is watching.
     */
    private void setUpImageReader() {
        startImageReaderThread();
        try {
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(mCameraIdStr);
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] yuvSizeChoices = map.getOutputSizes(ImageFormat.YUV_420_888);
            // Same size-selection helper used for mPreviewSize/mVideoSize elsewhere in this
            // class; target roughly 640x480 at the recording's aspect ratio.
            Size streamSize = CameraUtils.chooseOptimalSize(yuvSizeChoices, 640, 480, mVideoSize);
            mImageReader = ImageReader.newInstance(
                    streamSize.getWidth(), streamSize.getHeight(), ImageFormat.YUV_420_888, 2);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mImageReaderHandler);
        } catch (CameraAccessException e) {
            Timber.e(e, "Failed to set up streaming ImageReader");
        }
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage(); // drops any backlog; this IS the
            // throttling mechanism against slow JPEG encoding -- no manual frame-skip needed.
            if (image == null) {
                return;
            }

            int targetFps;
            try {
                targetFps = Integer.parseInt(
                        mSharedPreferences.getString("prefStreamFps", "5"));
            } catch (NumberFormatException e) {
                targetFps = 5;
            }
            if (targetFps <= 0) {
                targetFps = 5;
            }
            long minIntervalMs = 1000L / targetFps;
            long nowElapsedMs = SystemClock.elapsedRealtime();
            if (nowElapsedMs - mLastStreamedFrameElapsedMs < minIntervalMs) {
                image.close();
                return;
            }
            mLastStreamedFrameElapsedMs = nowElapsedMs;

            if (mFrameStreamListener == null) {
                image.close();
                return;
            }

            try {
                byte[] nv21 = yuv420ToNv21(image);
                int width = image.getWidth();
                int height = image.getHeight();
                long timestampNs = image.getTimestamp();
                image.close();

                YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, width, height), 50, out);
                mFrameStreamListener.onJpegFrame(out.toByteArray(), timestampNs);
            } catch (Exception e) {
                Timber.e(e, "Error converting camera frame to JPEG for streaming");
                image.close();
            }
        }
    };

    /** Standard YUV_420_888 (3-plane, possibly non-contiguous/interleaved chroma) -> NV21
     * conversion, respecting each plane's rowStride/pixelStride. */
    private static byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        Image.Plane yPlane = planes[0];
        Image.Plane uPlane = planes[1];
        Image.Plane vPlane = planes[2];

        int ySize = width * height;
        byte[] nv21 = new byte[ySize + (width * height) / 2];

        ByteBuffer yBuffer = yPlane.getBuffer();
        int yRowStride = yPlane.getRowStride();
        int yPixelStride = yPlane.getPixelStride();
        int pos = 0;
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, ySize);
            pos = ySize;
        } else {
            byte[] row = new byte[yRowStride];
            for (int r = 0; r < height; r++) {
                yBuffer.position(r * yRowStride);
                int rowLen = Math.min(yRowStride, yBuffer.remaining());
                yBuffer.get(row, 0, rowLen);
                for (int c = 0; c < width; c++) {
                    nv21[pos++] = row[c * yPixelStride];
                }
            }
        }

        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();
        int uRowStride = uPlane.getRowStride();
        int uPixelStride = uPlane.getPixelStride();
        int vRowStride = vPlane.getRowStride();
        int vPixelStride = vPlane.getPixelStride();

        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        byte[] uRow = new byte[uRowStride];
        byte[] vRow = new byte[vRowStride];
        int uvPos = ySize;
        // NV21 = Y plane followed by interleaved VU pairs, one pair per 2x2 luma block.
        for (int r = 0; r < chromaHeight; r++) {
            uBuffer.position(r * uRowStride);
            int uRowLen = Math.min(uRowStride, uBuffer.remaining());
            uBuffer.get(uRow, 0, uRowLen);

            vBuffer.position(r * vRowStride);
            int vRowLen = Math.min(vRowStride, vBuffer.remaining());
            vBuffer.get(vRow, 0, vRowLen);

            for (int c = 0; c < chromaWidth; c++) {
                nv21[uvPos++] = vRow[c * vPixelStride];
                nv21[uvPos++] = uRow[c * uPixelStride];
            }
        }

        return nv21;
    }

    public void startPreview() {
        Timber.v("startPreview");
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            Timber.w("startPreview: mCaptureSession or mPreviewRequestBuilder is null");
            return;
        }
        try {
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequest, mFocusCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Timber.e(e);
        }
    }

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to tap to focus.
     * https://stackoverflow.com/questions/42127464/how-to-lock-focus-in-camera2-api-android
     */
    private final CameraCaptureSession.CaptureCallback mFocusCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_AUTO: {
                    Integer afMode = result.get(CaptureResult.CONTROL_AF_MODE);
                    if (afMode != null && afMode == CaptureResult.CONTROL_AF_MODE_AUTO) {
                        mState = STATE_TRIGGER_AUTO;

                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_AUTO);
                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                CameraMetadata.CONTROL_AF_TRIGGER_START);
                        try {
                            mCaptureSession.capture(
                                    mPreviewRequestBuilder.build(),
                                    mFocusCaptureCallback, mBackgroundHandler);
                        } catch (CameraAccessException e) {
                            Timber.e(e);
                        }
                    }
                    break;
                }
                case STATE_TRIGGER_AUTO: {
                    mState = STATE_WAITING_LOCK;

                    setExposureAndIso();

                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_AUTO);
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                    try {
                        mCaptureSession.setRepeatingRequest(
                                mPreviewRequestBuilder.build(),
                                mFocusCaptureCallback, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        Timber.e(e);
                    }
                    Timber.d("Focus trigger auto");
                    break;
                }
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        mState = STATE_FOCUS_LOCKED;
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        mState = STATE_FOCUS_LOCKED;
                        Timber.d("Focus locked after waiting lock");
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            long unixTime = System.currentTimeMillis();
            process(result);

            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            Long number = result.getFrameNumber();
            Long exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);

            Long frmDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION);
            Long frmReadoutNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            if (expoStats.size() > kMaxExpoSamples) {
                expoStats.subList(0, kMaxExpoSamples / 2).clear();
            }
            expoStats.add(new NumExpoIso(number, exposureTimeNs, iso));

            Float fl = result.get(CaptureResult.LENS_FOCAL_LENGTH);

            Float fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE);

            Integer afMode = result.get(CaptureResult.CONTROL_AF_MODE);

            Rect rect = result.get(CaptureResult.SCALER_CROP_REGION);
            mFocalLengthHelper.setmFocalLength(fl);
            mFocalLengthHelper.setmFocusDistance(fd);
            mFocalLengthHelper.setmCropRegion(rect);
            SizeF sz_focal_length = mFocalLengthHelper.getFocalLengthPixel();
            String delimiter = ",";
            String frame_info = timestamp +
                    delimiter + sz_focal_length.getWidth() +
                    delimiter + sz_focal_length.getHeight() +
                    delimiter + number +
                    delimiter + exposureTimeNs +
                    delimiter + frmDurationNs +
                    delimiter + frmReadoutNs +
                    delimiter + iso +
                    delimiter + fl +
                    delimiter + fd +
                    delimiter + afMode +
                    delimiter + unixTime + "000000";
            if (mRecordingMetadata) {
                try {
                    mFrameMetadataWriter.write(frame_info + "\n");
                } catch (IOException err) {
                    Timber.e(err, "Error writing captureResult");
                }
            }
            ((CameraActivityBase) mActivity).updateCaptureResultPanel(
                    sz_focal_length.getWidth(), exposureTimeNs, afMode, mSecondCamera);
        }

    };


    void changeManualFocusPoint(ManualFocusConfig focusConfig) {
        float eventX = focusConfig.mEventX;
        float eventY = focusConfig.mEventY;
        int viewWidth = focusConfig.mViewWidth;
        int viewHeight = focusConfig.mViewHeight;

        final int y = (int) ((eventX / (float) viewWidth) * (float) sensorArraySize.height());
        final int x = (int) ((eventY / (float) viewHeight) * (float) sensorArraySize.width());
        final int halfTouchWidth = 400;
        final int halfTouchHeight = 400;
        MeteringRectangle focusAreaTouch = new MeteringRectangle(Math.max(x - halfTouchWidth, 0),
                Math.max(y - halfTouchHeight, 0),
                halfTouchWidth * 2,
                halfTouchHeight * 2,
                MeteringRectangle.METERING_WEIGHT_MAX - 1);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_AUTO);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS,
                new MeteringRectangle[]{focusAreaTouch});
        try {
            mState = STATE_WAITING_AUTO;
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), mFocusCaptureCallback, null);
        } catch (CameraAccessException e) {
            Timber.e(e);
        }
    }

    private void startBackgroundThread() {
        if (mBackgroundThread == null || mBackgroundHandler == null) {
            Timber.v("startBackgroundThread");
            mBackgroundThread = new HandlerThread("CameraBackground");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        Timber.v("stopBackgroundThread");
        try {
            if (mBackgroundThread != null) {
                mBackgroundThread.quitSafely();
                mBackgroundThread.join();
            }
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            Timber.e(e);
        }
    }

    // Dedicated thread for ImageReader's onImageAvailable callback -- kept separate from
    // mBackgroundThread/mBackgroundHandler, which already carries camera device/session/capture
    // -result callbacks (including the focus state machine), mirroring this codebase's existing
    // per-concern-thread pattern (e.g. IMUManager's "Sensor thread").
    private void startImageReaderThread() {
        if (mImageReaderThread == null || mImageReaderHandler == null) {
            Timber.v("startImageReaderThread");
            mImageReaderThread = new HandlerThread("ImageReaderThread");
            mImageReaderThread.start();
            mImageReaderHandler = new Handler(mImageReaderThread.getLooper());
        }
    }

    private void stopImageReaderThread() {
        Timber.v("stopImageReaderThread");
        try {
            if (mImageReaderThread != null) {
                mImageReaderThread.quitSafely();
                mImageReaderThread.join();
            }
            mImageReaderThread = null;
            mImageReaderHandler = null;
        } catch (InterruptedException e) {
            Timber.e(e);
        }
    }
}
