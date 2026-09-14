package io.a3dv.VIRec;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.ViewHolder> {
    public interface OnSessionClickListener {
        void onSessionClick(RecordingSession session);
    }

    private final List<RecordingSession> mSessions;
    private final OnSessionClickListener mListener;

    public RecordingsAdapter(List<RecordingSession> sessions, OnSessionClickListener listener) {
        mSessions = sessions;
        mListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext()).inflate(
                R.layout.recording_session_item, parent, false);
        return new ViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, int position) {
        holder.mItem = mSessions.get(position);
        holder.mNameView.setText(holder.mItem.getDisplayName());
        holder.mView.setOnClickListener(v -> {
            if (null != mListener) {
                mListener.onSessionClick(holder.mItem);
            }
        });
    }

    @Override
    public int getItemCount() {
        return mSessions.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final View mView;
        public final TextView mNameView;
        public RecordingSession mItem;

        public ViewHolder(View view) {
            super(view);
            mView = view;
            mNameView = view.findViewById(R.id.session_name);
        }

        @NonNull
        @Override
        public String toString() {
            return super.toString() + " '" + mNameView.getText() + "'";
        }
    }
}
