/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.graphics.Rect;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

class FastPathSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    private boolean isActive = false;

    public FastPathSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
        setZOrderOnTop(true);
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Surface surface = holder.getSurface();
        if (surface == null)
            return;

        try {
            Method setSharedBufferMode = Surface.class.getMethod("setSharedBufferMode", boolean.class);
            setSharedBufferMode.invoke(surface, true);
            displayMessage("Using shared buffer mode.");
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            displayMessage("Shared buffer mode is not supported.");
        }
        Canvas canvas = surface.lockCanvas(null);
        canvas.drawColor(Color.GRAY);
        surface.unlockCanvasAndPost(canvas);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        isActive = true;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        isActive = false;
    }

    private void displayMessage(String message) {
        Toast toast = Toast.makeText(getContext(), message, Toast.LENGTH_SHORT);
        toast.show();
    }

    public void setRectColor(int color) {
        Surface surface = getHolder().getSurface();
        if (surface == null || !isActive)
            return;
        Rect rect = new Rect(10, 10, 310, 310);
        Canvas canvas = surface.lockCanvas(rect);
        Paint paint = new Paint();
        paint.setColor(color);
        canvas.drawRect(rect, paint);
        surface.unlockCanvasAndPost(canvas);
    }
}
