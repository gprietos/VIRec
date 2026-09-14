package io.a3dv.VIRec;

import androidx.annotation.NonNull;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A small POJO wrapping a recording session directory, named
 * {@code yyyy_MM_dd_HH_mm_ss} by {@link CameraActivity#renewOutputDir()}.
 */
public class RecordingSession {
    // Must match CameraActivity.renewOutputDir()'s folder-naming pattern exactly.
    private static final String FOLDER_NAME_PATTERN = "yyyy_MM_dd_HH_mm_ss";

    private final File mDir;
    private final Date mTimestamp;

    public RecordingSession(File dir) {
        mDir = dir;

        Date parsed;
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat(FOLDER_NAME_PATTERN, Locale.US);
            parsed = dateFormat.parse(dir.getName());
        } catch (ParseException e) {
            parsed = null;
        }
        mTimestamp = parsed;
    }

    public File getDir() {
        return mDir;
    }

    public Date getTimestamp() {
        return mTimestamp;
    }

    @NonNull
    public String getDisplayName() {
        if (mTimestamp == null) {
            return mDir.getName();
        }
        SimpleDateFormat displayFormat =
                new SimpleDateFormat("EEE, MMM d yyyy HH:mm:ss", Locale.US);
        return displayFormat.format(mTimestamp);
    }
}
