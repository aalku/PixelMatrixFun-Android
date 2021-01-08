package org.aalku.pixelmatrixfun;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.resources.TextAppearance;

import org.aalku.pixelmatrixfun.DrawView.DrawListener;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import petrov.kristiyan.colorpicker.ColorPicker;

@RequiresApi(api = Build.VERSION_CODES.N)
public class DrawActivity extends AppCompatActivity implements DrawListener {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private Consumer<String> statusListener = s->setStatusText(s);
    private Consumer<Boolean> connectionListener = c->onConnectionChanged(c);

    private DrawView drawView;
    private TextView statusText;
    private Switch syncSwitch;
    private TextView syncSwitchText;

    private DeviceService deviceService;

    private ScheduledFuture<?> initTask;
    private ColorSet frontColor;
    private ColorSet backgroundColor;

    private class ColorSet {
        private final ToggleButton button;
        private final FloatingActionButton changeColorButton;
        int color;
        int textColor;
        boolean checked;

        public ColorSet(ToggleButton button, FloatingActionButton changeColorButton, int color, boolean checked) {
            this.button = button;
            this.changeColorButton = changeColorButton;
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
        syncSwitch = this.findViewById(R.id.syncSwitch);
        syncSwitchText = this.findViewById(R.id.syncText);

        deviceService.addConnectionListener(connectionListener);
        deviceService.addStatusListener(statusListener);
        setStatusText(deviceService.getStatusText());
        drawView.setOnDrawListener(this);
        frontColor = new ColorSet(
                this.findViewById(R.id.frontColorButton),
                this.findViewById(R.id.frontChangeColor),
                Color.WHITE,
                true);
        backgroundColor = new ColorSet(
                this.findViewById(R.id.backgroundColorButton),
                this.findViewById(R.id.backgroundChangeColor),
                Color.BLACK,
                false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncSwitch.setChecked(false);
        syncSwitch.setEnabled(deviceService.isConnected());
        syncSwitchText.setEnabled(syncSwitch.isEnabled());
    }

    @Override
    protected void onDestroy() {
        deviceService.removeStatusListener(statusListener);
        deviceService.removeConnectionListener(connectionListener);
        executor.shutdown();
        super.onDestroy();
    }

    protected void setStatusText(String msg) {
        Log.i("STATUS", msg);
        this.runOnUiThread(()->statusText.setText(msg));
    }

    @Override
    public void notifyPixel(int x, int y, int color) {
        if (syncSwitch.isChecked()) {
            deviceService.sendPixel(x, y, color);
        }
    }

    public void onColorClick(View view) {
        frontColor.checked = (view != backgroundColor.button && view != backgroundColor.changeColorButton);
        backgroundColor.checked = !frontColor.checked;
        frontColor.update();
        backgroundColor.update();
        if (view == frontColor.changeColorButton || view == backgroundColor.changeColorButton) {
            ColorSet c = view == backgroundColor.changeColorButton ? backgroundColor : frontColor;
            ColorPicker colorPicker = new ColorPicker(this);
            colorPicker.setOnFastChooseColorListener(new ColorPicker.OnFastChooseColorListener() {
                @Override
                public void setOnFastChooseColorListener(int position, int color) {
                    c.color = color;
                    c.update();
                }

                @Override
                public void onCancel() {
                }
            })
                    .setColumns(4)
                    .setColors(
                            0xFF000000,
                            0xFF555555,
                            0xFF0000AA,
                            0xFF5555FF,
                            0xFF00AA00,
                            0xFF55FF55,
                            0xFF00AAAA,
                            0xFF55FFFF,
                            0xFFAA0000,
                            0xFFFF5555,
                            0xFFAA00AA,
                            0xFFFF55FF,
                            0xFFAA5500,
                            0xFFFFFF55,
                            0xFFAAAAAA,
                            0xFFFFFFFF)
                    .setDefaultColorButton(c.color)
                    .show();
        }
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

    private void onConnectionChanged(Boolean c) {
        if (c) {
            syncSwitch.setEnabled(true);
        } else {
            syncSwitch.setEnabled(false);
        }
        syncSwitch.setChecked(false);
        syncSwitchText.setEnabled(syncSwitch.isEnabled());
    }

    public void onSyncClick(View view) {
        if (view == syncSwitchText) {
            if (syncSwitch.isEnabled()) {
                syncSwitch.setChecked(!syncSwitch.isChecked());
                onSyncClick(syncSwitch);
            }
        } else {
            if (syncSwitch.isEnabled() && syncSwitch.isChecked()) {
                syncSwitch.setEnabled(false);
                syncSwitchText.setEnabled(syncSwitch.isEnabled());
                // CompletionStage<Boolean> cf = deviceService.clearBitmap(Color.BLACK);
                CompletionStage<Boolean> cf = deviceService.sendBitmap(drawView.getBitmap());
                cf.whenComplete((r, e) -> {
                    this.runOnUiThread(()->{
                        syncSwitch.setEnabled(true);
                        syncSwitchText.setEnabled(syncSwitch.isEnabled());
                    });
                });
            } else {
                syncSwitch.setEnabled(deviceService.isConnected());
            }
            syncSwitchText.setEnabled(syncSwitch.isEnabled());
        }
    }

}