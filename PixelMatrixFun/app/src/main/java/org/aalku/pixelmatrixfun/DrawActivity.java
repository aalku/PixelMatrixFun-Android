package org.aalku.pixelmatrixfun;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;

import java.util.function.Consumer;

public class DrawActivity extends AppCompatActivity {

    private Consumer<String> statusListener = s->setStatusText(s);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_draw);
        DeviceService.instance.addStatusListener(statusListener);
    }

    @Override
    protected void onDestroy() {
        DeviceService.instance.removeStatusListener(statusListener);
        super.onDestroy();
    }

    protected void setStatusText(String msg) {
        Log.i("STATUS", msg);
        // this.runOnUiThread(()->statusText.setText(msg));
    }

}