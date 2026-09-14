package io.a3dv.VIRec;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * Shared parsing utility for the sensor CSV files written by {@link IMUManager} and
 * {@link GPSManager}. Reuses their header constants directly so the writer and reader can
 * never drift apart.
 */
public final class SensorCsvParser {
    private SensorCsvParser() {
    }

    /**
     * Thrown when a CSV file's first line doesn't match the expected header -- meaning the
     * whole file is instead a plain-text warning that the corresponding sensor was
     * unavailable at recording time (see IMUManager.startRecording()).
     */
    public static class Unavailable extends Exception {
        public Unavailable(String reason) {
            super(reason);
        }
    }

    public static class ImuSample {
        public long t;
        public float gx, gy, gz, ax, ay, az;
    }

    public static class OrientationSample {
        public long t;
        public double yaw, pitch, roll;
    }

    public static class GpsSample {
        public long t;
        public double lat, lon, alt;
        public float speed;
    }

    public static List<ImuSample> parseImu(File csv) throws IOException, Unavailable {
        List<String> lines = readLinesAfterHeader(csv, IMUManager.ImuHeader);
        List<ImuSample> samples = new ArrayList<>();
        for (String line : lines) {
            try {
                String[] parts = line.split(",");
                ImuSample s = new ImuSample();
                s.t = Long.parseLong(parts[0]);
                s.gx = Float.parseFloat(parts[1]);
                s.gy = Float.parseFloat(parts[2]);
                s.gz = Float.parseFloat(parts[3]);
                s.ax = Float.parseFloat(parts[4]);
                s.ay = Float.parseFloat(parts[5]);
                s.az = Float.parseFloat(parts[6]);
                samples.add(s);
            } catch (RuntimeException e) {
                Timber.w(e, "Skipping malformed IMU row: %s", line);
            }
        }
        return samples;
    }

    public static List<OrientationSample> parseOrientation(File csv)
            throws IOException, Unavailable {
        List<String> lines = readLinesAfterHeader(csv, IMUManager.OrientationHeader);
        List<OrientationSample> samples = new ArrayList<>();
        for (String line : lines) {
            try {
                String[] parts = line.split(",");
                OrientationSample s = new OrientationSample();
                s.t = Long.parseLong(parts[0]);
                s.yaw = Double.parseDouble(parts[1]);
                s.pitch = Double.parseDouble(parts[2]);
                s.roll = Double.parseDouble(parts[3]);
                samples.add(s);
            } catch (RuntimeException e) {
                Timber.w(e, "Skipping malformed orientation row: %s", line);
            }
        }
        return samples;
    }

    public static List<GpsSample> parseGps(File csv) throws IOException, Unavailable {
        List<String> lines = readLinesAfterHeader(csv, GPSManager.GpsHeader);
        List<GpsSample> samples = new ArrayList<>();
        for (String line : lines) {
            try {
                String[] parts = line.split(",");
                GpsSample s = new GpsSample();
                s.t = Long.parseLong(parts[0]);
                s.lat = Double.parseDouble(parts[1]);
                s.lon = Double.parseDouble(parts[2]);
                s.alt = Double.parseDouble(parts[3]);
                s.speed = Float.parseFloat(parts[4]);
                samples.add(s);
            } catch (RuntimeException e) {
                Timber.w(e, "Skipping malformed GPS row: %s", line);
            }
        }
        return samples;
    }

    /**
     * Reads {@code csv}, checks its first line against {@code expectedHeader} (compared
     * without the trailing newline the header constants carry), and returns the remaining
     * non-empty lines. If the first line doesn't match, the whole file is treated as a
     * plain-text warning and {@link Unavailable} is thrown carrying that text.
     */
    private static List<String> readLinesAfterHeader(File csv, String expectedHeader)
            throws IOException, Unavailable {
        if (csv == null || !csv.exists() || csv.length() == 0) {
            throw new Unavailable("No data file found.");
        }

        String expected = expectedHeader.trim();
        List<String> allLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csv))) {
            String line;
            while ((line = reader.readLine()) != null) {
                allLines.add(line);
            }
        }

        if (allLines.isEmpty()) {
            throw new Unavailable("Data file is empty.");
        }

        String firstLine = allLines.get(0).trim();
        if (!firstLine.equals(expected)) {
            StringBuilder warning = new StringBuilder();
            for (String line : allLines) {
                warning.append(line).append("\n");
            }
            throw new Unavailable(warning.toString());
        }

        List<String> dataLines = new ArrayList<>();
        for (int i = 1; i < allLines.size(); i++) {
            String line = allLines.get(i);
            if (!line.trim().isEmpty()) {
                dataLines.add(line);
            }
        }
        return dataLines;
    }
}
