package com.dsh.botctl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Minimal sparkline for bot latency history. No external deps.
 */
public class PingChart extends View {

    private final int[] values = new int[60];
    private int count = 0;
    private final Paint line = new Paint();
    private final Paint grid = new Paint();
    private final Paint label = new Paint();

    public PingChart(Context c) {
        super(c);
        line.setColor(Color.parseColor("#2E7D32"));
        line.setStrokeWidth(3f);
        line.setAntiAlias(true);
        line.setStyle(Paint.Style.STROKE);

        grid.setColor(Color.parseColor("#E0E0E0"));
        grid.setStrokeWidth(1f);

        label.setColor(Color.parseColor("#9E9E9E"));
        label.setTextSize(22f);
        label.setAntiAlias(true);
    }

    /** Push a new sample; negative values are treated as "no reading" and skipped. */
    public void push(int ms) {
        if (ms < 0) return;
        if (count < values.length) {
            values[count++] = ms;
        } else {
            System.arraycopy(values, 1, values, 0, values.length - 1);
            values[values.length - 1] = ms;
        }
        postInvalidate();
    }

    public void clear() {
        count = 0;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas g) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // horizontal guides
        for (int i = 0; i <= 4; i++) {
            float y = h * i / 4f;
            g.drawLine(0, y, w, y, grid);
        }

        if (count < 2) {
            g.drawText("等待采样…", 12f, h / 2f, label);
            return;
        }

        int max = 50;
        for (int i = 0; i < count; i++) {
            if (values[i] > max) max = values[i];
        }
        max = (int) (max * 1.2f);

        Path p = new Path();
        float stepX = w / (float) (count - 1);
        for (int i = 0; i < count; i++) {
            float x = i * stepX;
            float y = h - (values[i] / (float) max) * (h - 6f) - 3f;
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        g.drawPath(p, line);

        // current value marker
        float lx = (count - 1) * stepX;
        float ly = h - (values[count - 1] / (float) max) * (h - 6f) - 3f;
        g.drawCircle(lx, ly, 5f, line);

        g.drawText(max + "ms", 4f, 24f, label);
    }
}
