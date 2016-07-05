package org.chromium.latency.walt;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

/**
 * Created by kamrik on 7/5/16.
 */
public class TouchCatcherView extends View {
    public TouchCatcherView(Context context, AttributeSet attrs) {
        super(context, attrs);

        initialisePaint();
    }

    private void initialisePaint() {
        float density = getResources().getDisplayMetrics().density;
    }
}
