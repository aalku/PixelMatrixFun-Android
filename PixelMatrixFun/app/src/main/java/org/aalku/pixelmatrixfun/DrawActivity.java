package org.aalku.pixelmatrixfun;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import org.aalku.pixelmatrixfun.DrawView.DrawListener;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@RequiresApi(api = Build.VERSION_CODES.N)
public class DrawActivity extends AppCompatActivity implements DrawListener {

    private Consumer<String> statusListener = s->setStatusText(s);
    private DrawView drawView;
    private TextView statusText;
    private DeviceService deviceService;
    private final AtomicBoolean ready = new AtomicBoolean(false);

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        // CompletionStage<Boolean> cf = deviceService.sendBitmap(drawView.getBitmap());
        CompletionStage<Boolean> cf = deviceService.clearBitmap(Color.BLACK);
        cf.whenComplete((r,e)->{
            ready.set(true);
        });
    }

    @Override
    protected void onDestroy() {
        DeviceService.instance.removeStatusListener(statusListener);
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
}