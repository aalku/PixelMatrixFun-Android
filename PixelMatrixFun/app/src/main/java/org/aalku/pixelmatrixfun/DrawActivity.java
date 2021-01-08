package org.aalku.pixelmatrixfun;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.google.android.material.resources.TextAppearance;

import org.aalku.pixelmatrixfun.DrawView.DrawListener;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@RequiresApi(api = Build.VERSION_CODES.N)
public class DrawActivity extends AppCompatActivity implements DrawListener {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private Consumer<String> statusListener = s->setStatusText(s);
    private DrawView drawView;
    private TextView statusText;
    private DeviceService deviceService;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private ScheduledFuture<?> initTask;
    private ColorSet frontColor;
    private ColorSet backgroundColor;

    private class ColorSet {
        private final ToggleButton button;
        int color;
        int textColor;
        boolean checked;

        public ColorSet(ToggleButton button, int color, boolean checked) {
            this.button = button;
            this.color = color;
            this.checked = checked;
            update();
        }

        private void update() {
            textColor = getContrastVersionForColor(color);

            button.setTypeface(null, checked ? Typeface.BOLD : Typeface.NORMAL);
            button.setBackgroundColor(color);
            button.setTextColor(textColor);
            if (checked) {
                drawView.setColor(color);
            }
        }

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        deviceService = DeviceService.instance;
        setContentView(R.layout.activity_draw);
        statusText = this.findViewById(R.id.statusText);
        drawView = this.findViewById(R.id.drawView);
        deviceService.addStatusListener(statusListener);
        setStatusText(deviceService.getStatusText());
        drawView.setOnDrawListener(this);
        frontColor = new ColorSet(this.findViewById(R.id.frontColorButton), Color.WHITE, true);
        backgroundColor = new ColorSet(this.findViewById(R.id.backgroundColorButton), Color.BLACK, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // CompletionStage<Boolean> cf = deviceService.sendBitmap(drawView.getBitmap());
        this.initTask = executor.scheduleWithFixedDelay(()->{
            if (!ready.get()) {
                drawView.getBitmap().eraseColor(Color.BLACK);
                CompletionStage<Boolean> cf = deviceService.clearBitmap(Color.BLACK);
                cf.whenComplete((r, e) -> {
                    ready.set(true);
                });
            } else {
                if (initTask != null) {
                    initTask.cancel(false);
                    initTask = null;
                }
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    @Override
    protected void onDestroy() {
        DeviceService.instance.removeStatusListener(statusListener);
        executor.shutdown();
        super.onDestroy();
    }

    protected void setStatusText(String msg) {
        Log.i("STATUS", msg);
        this.runOnUiThread(()->statusText.setText(msg));
    }

    @Override
    public boolean notifyPixel(int x, int y, int color) {
        if (isDrawAllowed()) {
            deviceService.sendPixel(x, y, color);
            return true;
        }
        return false;
    }

    @Override
    public boolean isDrawAllowed() {
        return ready.get();
    }

    public void onColorClick(View view) {
        frontColor.checked = (view != backgroundColor.button);
        backgroundColor.checked = !frontColor.checked;
        frontColor.update();
        backgroundColor.update();
    }

    public static int getContrastVersionForColor(int color) {
        float[] hsv = new float[3];
        Color.RGBToHSV(Color.red(color), Color.green(color), Color.blue(color),
                hsv);
        if (hsv[2] < 0.5) {
            hsv[2] = 0.7f;
        } else {
            hsv[2] = 0.3f;
        }
        hsv[1] = hsv[1] * 0.2f;
        return Color.HSVToColor(hsv);
    }

}