package org.chromium.latency.walt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;



public class TouchCatcherView extends View {

    private Paint linePaint = new Paint();
    private ClockManager clockManager;

    public TouchCatcherView(Context context, AttributeSet attrs) {
        super(context, attrs);
        clockManager = ClockManager.getInstance(context);
        initialisePaint();
    }

    private void initialisePaint() {
        float density = getResources().getDisplayMetrics().density;
        float lineWidth = 10f * density;
        linePaint.setColor(Color.RED);
        linePaint.setStrokeWidth(lineWidth);
    }

    public static double markerPosition(long t_us) {
        int period = 1000000;  // microseconds
        // Normalized time within period, goes from 0 to 1
        double t = (t_us % period) / (double) period;
        // Triangular wave with amplitude 1
        double x_tri = -1 + 4 * Math.abs(t - 0.5);

        // Apply some smoothing to get rid of the sharp edges of triangular wave.
        // beta is a dimensionless smoothing parameter used below, experimentally derived.
        // Higher value gives less smoothing.
        double beta = 4;
        // Multiply by the smoothing function 1/{1 + exp(b(|x|-1))/(b-1)}
        // This is a Fermi function, modified to have a zero derivative at x = 1.
        double x_smooth = x_tri / (1 + Math.exp(beta*(Math.abs(x_tri)-1))/(beta - 1));
        return x_smooth;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int h = getHeight();
        double normPos = markerPosition(clockManager.micros());
        double amplitude = 0.4; // Of View height
        int pos = (int) (h * (0.5 + amplitude * normPos));
        // Log.i("AnimatedView", "Pos is " + pos);
        int w = getWidth();
        canvas.drawLine(w/3, pos, w*2/3, pos, linePaint);

        // Run every frame
        invalidate();
    }
}
