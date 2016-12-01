/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.chromium.latency.walt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;



public class TouchCatcherView extends View {

    private Paint linePaint = new Paint();
    private WaltDevice waltDevice;
    private boolean isAnimated = false;

    private double animationAmplitude = 0.4;  // Fraction of view height
    private double lineLength = 0.6;  // Fraction of view width
    public final int animationPeriod_us = 1000000;

    public void startAnimation() {
        isAnimated = true;
        invalidate();
    }

    public void stopAnimation() {
        isAnimated = false;
        invalidate();
    }

    public TouchCatcherView(Context context, AttributeSet attrs) {
        super(context, attrs);
        waltDevice = WaltDevice.getInstance(context);
        initialisePaint();
    }

    private void initialisePaint() {
        float density = getResources().getDisplayMetrics().density;
        float lineWidth = 10f * density;
        linePaint.setColor(Color.GREEN);
        linePaint.setStrokeWidth(lineWidth);
    }

    public static double markerPosition(long t_us, int period_us) {
        // Normalized time within a period, goes from 0 to 1
        double t = (t_us % period_us) / (double) period_us;

        // Triangular wave with unit amplitude
        //  1| *               *
        //   |   *           *   *
        //   0-----*-------*---|---*-----> t
        //   |       *   *     1     *
        // -1|         *               *
        double y_tri = -1 + 4 * Math.abs(t - 0.5);

        // Apply some smoothing to get a feeling of deceleration and acceleration at the edges.
        // f(y) = y / {1 + exp(b(|y|-1))/(b-1)}
        // This is inspired by Fermi function and adjusted to have continuous derivative at extrema.
        // b = beta is a dimensionless smoothing parameter, value selected by experimentation.
        // Higher value gives less smoothing = closer to original triangular wave.
        double beta = 4;
        double y_smooth = y_tri / (1 + Math.exp(beta*(Math.abs(y_tri)-1))/(beta - 1));
        return y_smooth;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isAnimated) return;

        int h = getHeight();
        double normPos = markerPosition(waltDevice.clock.micros(), animationPeriod_us);
        int pos = (int) (h * (0.5 + animationAmplitude * normPos));
        // Log.i("AnimatedView", "Pos is " + pos);
        int w = getWidth();

        int lineStart = (int) (w * (1 - lineLength) / 2);
        int lineEnd   = (int) (w * (1 + lineLength) / 2);
        canvas.drawLine(lineStart, pos, lineEnd, pos, linePaint);

        // Run every frame
        invalidate();
    }
}
