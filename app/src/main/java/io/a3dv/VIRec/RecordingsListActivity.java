package io.a3dv.VIRec;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class RecordingsListActivity extends AppCompatActivity
        implements RecordingsAdapter.OnSessionClickListener {

    public static final String EXTRA_SESSION_DIR = "io.a3dv.VIRec.EXTRA_SESSION_DIR";

    // Matches the folder names produced by CameraActivity.renewOutputDir(), e.g.
    // "2024_01_31_23_59_59". Guards against stray non-session files/dirs showing up here.
    private static final Pattern SESSION_DIR_PATTERN =
            Pattern.compile("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}");

    private RecyclerView mRecyclerView;
    private TextView mEmptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recordings_list_activity);

        mRecyclerView = findViewById(R.id.recordings_list);
        mEmptyView = findViewById(R.id.recordings_empty);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        List<RecordingSession> sessions = loadSessions();
        if (sessions.isEmpty()) {
            mRecyclerView.setVisibility(View.GONE);
            mEmptyView.setVisibility(View.VISIBLE);
        } else {
            mEmptyView.setVisibility(View.GONE);
            mRecyclerView.setVisibility(View.VISIBLE);
            mRecyclerView.setAdapter(new RecordingsAdapter(sessions, this));
        }
    }

    private List<RecordingSession> loadSessions() {
        List<RecordingSession> sessions = new ArrayList<>();
        File dataDir = getExternalFilesDir(Environment.getDataDirectory().getAbsolutePath());
        if (dataDir == null) {
            return sessions;
        }

        File[] files = dataDir.listFiles();
        if (files == null) {
            return sessions;
        }

        List<File> dirs = new ArrayList<>(Arrays.asList(files));
        // Descending order: the yyyy_MM_dd_HH_mm_ss format sorts lexicographically
        // chronological, so a reverse-name sort gives newest-first.
        Collections.sort(dirs, (a, b) -> b.getName().compareTo(a.getName()));

        for (File dir : dirs) {
            if (dir.isDirectory() && SESSION_DIR_PATTERN.matcher(dir.getName()).matches()) {
                sessions.add(new RecordingSession(dir));
            }
        }
        return sessions;
    }

    @Override
    public void onSessionClick(RecordingSession session) {
        Intent intent = new Intent(this, RecordingViewerActivity.class);
        intent.putExtra(EXTRA_SESSION_DIR, session.getDir().getAbsolutePath());
        startActivity(intent);
    }
}
