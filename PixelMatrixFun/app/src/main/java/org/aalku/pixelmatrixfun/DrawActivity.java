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
    private ToggleButton frontColorButton;
    private ToggleButton backgroundColorButton;

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
        frontColorButton = this.findViewById(R.id.frontColorButton);
        backgroundColorButton = this.findViewById(R.id.backgroundColorButton);
        onColorClick(null);
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
        ToggleButton selected;
        ToggleButton other;
        if (view != backgroundColorButton) {
            selected = frontColorButton;
            other = backgroundColorButton;
        } else {
            selected = backgroundColorButton;
            other = frontColorButton;
        }
        selected.setChecked(true);
        other.setChecked(false);
        selected.setTypeface(null, Typeface.BOLD);
        other.setTypeface(null, Typeface.NORMAL);
    }
}