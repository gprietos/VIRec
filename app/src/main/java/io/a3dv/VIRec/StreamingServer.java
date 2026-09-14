package io.a3dv.VIRec;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;
import timber.log.Timber;

/**
 * Minimal HTTP server exposing a live MJPEG video feed at "/video" and a live
 * Server-Sent-Events telemetry feed (IMU/orientation/GPS) at "/telemetry", so a PC on the
 * same network can open the phone's URL and monitor a recording session in progress.
 *
 * Other classes (Camera2Proxy's JPEG tap, IMUManager, GPSManager) push data in via the
 * publish*() methods; this class never reaches out to them.
 */
public class StreamingServer extends NanoHTTPD {

    private static final String MJPEG_BOUNDARY = "frame";
    private static final int MAX_QUEUED_TELEMETRY_SAMPLES = 200;

    // ---- Video (/video) state -------------------------------------------------------------

    private final Object mFrameLock = new Object();
    private volatile byte[] mLatestFrame = null;
    private volatile long mFrameVersion = 0;

    private final List<MjpegInputStream> mActiveVideoStreams = new ArrayList<>();

    // ---- Telemetry (/telemetry) state ------------------------------------------------------

    private final Object mSubscribersLock = new Object();
    private final List<SseInputStream> mTelemetrySubscribers = new ArrayList<>();

    public StreamingServer(int port) {
        super(port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if ("/".equals(uri)) {
            String html = "<html><body><img src=\"/video\"><p><a href=\"/telemetry\">/telemetry</a> " +
                    "(Server-Sent Events)</p></body></html>";
            return newFixedLengthResponse(html);
        } else if ("/video".equals(uri)) {
            MjpegInputStream stream = new MjpegInputStream();
            registerVideoStream(stream);
            return newChunkedResponse(Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=" + MJPEG_BOUNDARY, stream);
        } else if ("/telemetry".equals(uri)) {
            SseInputStream stream = new SseInputStream();
            registerTelemetrySubscriber(stream);
            return newChunkedResponse(Response.Status.OK, "text/event-stream", stream);
        } else {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
    }

    @Override
    public void stop() {
        super.stop();
        // Unblock any InputStreams currently parked in wait() so their reader threads exit
        // cleanly instead of hanging once the underlying sockets are gone.
        synchronized (mFrameLock) {
            for (MjpegInputStream stream : new ArrayList<>(mActiveVideoStreams)) {
                stream.close();
            }
            mActiveVideoStreams.clear();
        }
        synchronized (mSubscribersLock) {
            for (SseInputStream stream : new ArrayList<>(mTelemetrySubscribers)) {
                stream.close();
            }
            mTelemetrySubscribers.clear();
        }
    }

    // ---- Public publish API -----------------------------------------------------------------

    /** Called from the camera tap with the latest JPEG-encoded frame. Latest frame wins. */
    public void publishFrame(byte[] jpegBytes) {
        synchronized (mFrameLock) {
            mLatestFrame = jpegBytes;
            mFrameVersion++;
            mFrameLock.notifyAll();
        }
    }

    public void publishImuSample(long timestampNs, float gx, float gy, float gz,
                                  float ax, float ay, float az, long unixTimeMillis) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "imu");
            json.put("t", timestampNs);
            json.put("gx", gx);
            json.put("gy", gy);
            json.put("gz", gz);
            json.put("ax", ax);
            json.put("ay", ay);
            json.put("az", az);
            json.put("unixTime", unixTimeMillis);
            broadcastTelemetry(json.toString());
        } catch (JSONException e) {
            Timber.e(e, "Failed to build IMU telemetry JSON");
        }
    }

    public void publishOrientationSample(long timestampNs, double yaw, double pitch, double roll,
                                          long unixTimeMillis) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "orientation");
            json.put("t", timestampNs);
            json.put("yaw", yaw);
            json.put("pitch", pitch);
            json.put("roll", roll);
            json.put("unixTime", unixTimeMillis);
            broadcastTelemetry(json.toString());
        } catch (JSONException e) {
            Timber.e(e, "Failed to build orientation telemetry JSON");
        }
    }

    public void publishLocationSample(long timestampNs, double lat, double lon, double alt,
                                       float speed, long unixTimeMillis) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "location");
            json.put("t", timestampNs);
            json.put("lat", lat);
            json.put("lon", lon);
            json.put("alt", alt);
            json.put("speed", speed);
            json.put("unixTime", unixTimeMillis);
            broadcastTelemetry(json.toString());
        } catch (JSONException e) {
            Timber.e(e, "Failed to build location telemetry JSON");
        }
    }

    // ---- Internal plumbing ------------------------------------------------------------------

    private void registerVideoStream(MjpegInputStream stream) {
        synchronized (mFrameLock) {
            mActiveVideoStreams.add(stream);
        }
    }

    private void unregisterVideoStream(MjpegInputStream stream) {
        synchronized (mFrameLock) {
            mActiveVideoStreams.remove(stream);
        }
    }

    private void registerTelemetrySubscriber(SseInputStream stream) {
        synchronized (mSubscribersLock) {
            mTelemetrySubscribers.add(stream);
        }
    }

    private void unregisterTelemetrySubscriber(SseInputStream stream) {
        synchronized (mSubscribersLock) {
            mTelemetrySubscribers.remove(stream);
        }
    }

    private void broadcastTelemetry(String jsonLine) {
        synchronized (mSubscribersLock) {
            for (SseInputStream stream : mTelemetrySubscribers) {
                stream.offer(jsonLine);
            }
        }
    }

    /**
     * Blocks in read() until a new JPEG frame is published, then emits it as one
     * multipart/x-mixed-replace chunk. Re-fills its internal buffer from the latest published
     * frame each time the previous chunk has been fully consumed by the HTTP writer.
     */
    private class MjpegInputStream extends InputStream {
        private byte[] mCurrentChunk;
        private int mCurrentPos;
        private long mLastServedVersion = -1;
        private volatile boolean mClosed = false;

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int n = read(single, 0, 1);
            return n <= 0 ? -1 : (single[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (mClosed) {
                return -1;
            }
            if (mCurrentChunk == null || mCurrentPos >= mCurrentChunk.length) {
                byte[] frame = waitForNextFrame();
                if (frame == null) {
                    return -1;
                }
                mCurrentChunk = buildMjpegChunk(frame);
                mCurrentPos = 0;
            }
            int toCopy = Math.min(mCurrentChunk.length - mCurrentPos, len);
            System.arraycopy(mCurrentChunk, mCurrentPos, b, off, toCopy);
            mCurrentPos += toCopy;
            return toCopy;
        }

        private byte[] waitForNextFrame() {
            synchronized (mFrameLock) {
                while (!mClosed && (mLatestFrame == null || mFrameVersion == mLastServedVersion)) {
                    try {
                        mFrameLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                if (mClosed) {
                    return null;
                }
                mLastServedVersion = mFrameVersion;
                return mLatestFrame;
            }
        }

        private byte[] buildMjpegChunk(byte[] jpeg) {
            String header = "--" + MJPEG_BOUNDARY + "\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: " + jpeg.length + "\r\n\r\n";
            byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
            byte[] footerBytes = "\r\n".getBytes(StandardCharsets.US_ASCII);
            byte[] chunk = new byte[headerBytes.length + jpeg.length + footerBytes.length];
            System.arraycopy(headerBytes, 0, chunk, 0, headerBytes.length);
            System.arraycopy(jpeg, 0, chunk, headerBytes.length, jpeg.length);
            System.arraycopy(footerBytes, 0, chunk, headerBytes.length + jpeg.length, footerBytes.length);
            return chunk;
        }

        @Override
        public void close() {
            mClosed = true;
            synchronized (mFrameLock) {
                mFrameLock.notifyAll();
            }
            unregisterVideoStream(this);
        }
    }

    /**
     * Blocks in read() until a new telemetry sample is published, then emits it as one
     * Server-Sent-Events "data: ...\n\n" chunk. Each subscriber has its own queue, so every
     * connected client sees every sample (unlike /video, where only the latest frame matters).
     */
    private class SseInputStream extends InputStream {
        private final Object mLock = new Object();
        private final ArrayDeque<String> mQueue = new ArrayDeque<>();
        private byte[] mCurrentChunk;
        private int mCurrentPos;
        private volatile boolean mClosed = false;

        void offer(String jsonLine) {
            synchronized (mLock) {
                if (mQueue.size() >= MAX_QUEUED_TELEMETRY_SAMPLES) {
                    mQueue.removeFirst(); // drop oldest rather than block the publishing thread
                }
                mQueue.addLast(jsonLine);
                mLock.notifyAll();
            }
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int n = read(single, 0, 1);
            return n <= 0 ? -1 : (single[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (mClosed) {
                return -1;
            }
            if (mCurrentChunk == null || mCurrentPos >= mCurrentChunk.length) {
                String jsonLine = takeNext();
                if (jsonLine == null) {
                    return -1;
                }
                mCurrentChunk = ("data: " + jsonLine + "\n\n").getBytes(StandardCharsets.UTF_8);
                mCurrentPos = 0;
            }
            int toCopy = Math.min(mCurrentChunk.length - mCurrentPos, len);
            System.arraycopy(mCurrentChunk, mCurrentPos, b, off, toCopy);
            mCurrentPos += toCopy;
            return toCopy;
        }

        private String takeNext() {
            synchronized (mLock) {
                while (!mClosed && mQueue.isEmpty()) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                if (mClosed) {
                    return null;
                }
                return mQueue.pollFirst();
            }
        }

        @Override
        public void close() {
            mClosed = true;
            synchronized (mLock) {
                mLock.notifyAll();
            }
            unregisterTelemetrySubscriber(this);
        }
    }
}
