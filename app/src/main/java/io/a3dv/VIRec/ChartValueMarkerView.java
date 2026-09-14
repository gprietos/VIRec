package io.a3dv.VIRec;

import android.content.Context;
import android.widget.TextView;

import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.utils.MPPointF;

import java.util.Locale;

/**
 * Small floating box drawn next to the crosshair on a chart, listing every dataset's value at
 * the highlighted X (e.g. gx/gy/gz all at once on the gyro chart), not just the single entry
 * that was actually touched.
 *
 * Wired onto all 4 charts in RecordingViewerActivity. RecordingViewerActivity's cross-chart
 * highlighting (see setupCrossHighlighting) calls highlightValues(...) on every chart whenever
 * any one of them is touched, not just the touched one. MPAndroidChart v3.1.0's
 * Chart#highlightValues(Highlight[]) sets mIndicesToHighlight and calls invalidate() -- and
 * BarLineChartBase#onDraw unconditionally calls Chart#drawMarkers(canvas), which draws this
 * marker whenever Chart#valuesToHighlight() is true (verified by disassembling the library
 * classes in the Gradle cache). So this marker naturally pops up on all 4 charts at once, each
 * showing its own variables, with no extra invalidate()/refresh call needed here.
 */
public class ChartValueMarkerView extends MarkerView {

    private final TextView markerText;

    public ChartValueMarkerView(Context context, int layoutResource) {
        super(context, layoutResource);
        markerText = findViewById(R.id.marker_text);
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        StringBuilder sb = new StringBuilder();
        if (getChartView() != null && getChartView().getData() instanceof LineData) {
            LineData lineData = (LineData) getChartView().getData();
            for (ILineDataSet dataSet : lineData.getDataSets()) {
                Entry entry = dataSet.getEntryForXValue(highlight.getX(), Float.NaN);
                if (entry == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(String.format(Locale.US, "%s: %.3f", dataSet.getLabel(), entry.getY()));
            }
        }
        markerText.setText(sb.toString());
        super.refreshContent(e, highlight);
    }

    @Override
    public MPPointF getOffset() {
        // Offset right-and-up of the highlighted point, beside it rather than centered on top
        // of it. MarkerView#getOffsetForDrawingAtPoint (base class) already clamps this back
        // on-screen near chart edges using the chart's own width/height, so no manual edge
        // handling is needed here.
        return new MPPointF(12f, -getHeight() - 10f);
    }
}
