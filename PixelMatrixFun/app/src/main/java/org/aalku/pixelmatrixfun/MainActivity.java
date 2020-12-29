package org.aalku.pixelmatrixfun;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    AtomicReference<Bitmap> readyToSendBitmap = new AtomicReference<>(null);
    private DeviceService deviceService;

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        deviceService = new DeviceService(getBaseContext());
        deviceService.addStatusListener(s->setStatusText(s));
        setContentView(R.layout.activity_main);
        statusText = this.findViewById(R.id.statusText);

        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED){
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)){
                Toast.makeText(this, "The permission to get BLE location data is required", Toast.LENGTH_SHORT).show();
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 0);
            }
        } else {
            Toast.makeText(this, "Location permissions already granted", Toast.LENGTH_SHORT).show();
        }
    }

    public void selectImage(View view) {
        Intent intent = new Intent(Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, 0);
    }

    public void takePicture(View view) {
        Intent intent  = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        startActivityForResult(intent, 1);
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent imageReturnedIntent) {
        super.onActivityResult(requestCode, resultCode, imageReturnedIntent);
        if(resultCode != RESULT_OK) {
            return;
        }
        switch(requestCode) {
            case 0:
                Uri selectedImage = imageReturnedIntent.getData();
                try {
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), selectedImage);
                    bitmapSelected(bitmap);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                break;
            case 1:
                Bitmap bitmap = (Bitmap) imageReturnedIntent.getExtras().get("data");
                bitmapSelected(bitmap);
                break;
        }
    }

    private void bitmapSelected(Bitmap src) {
        ImageView imagePreview = findViewById(R.id.imagePreview);
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(crop(src), 16, 16, true);
        this.readyToSendBitmap.set(scaledBitmap);
        Bitmap previewBitmap = Bitmap.createScaledBitmap(scaledBitmap, 160, 160, false);
        imagePreview.setImageBitmap(previewBitmap);
    }

    private Bitmap crop(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int size = Math.min(w,h);
        int xo = (w - size) /2;
        int yo = (h - size)/2;
        return Bitmap.createBitmap(bitmap, xo, yo, size, size);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public void send(View view) {
        deviceService.sendBitmap(readyToSendBitmap.get());
    }

    protected void setStatusText(String msg) {
        Log.i("STATUS", msg);
        this.runOnUiThread(()->statusText.setText(msg));
    }

    public void startDrawActivity(View view) {
        Intent intent = new Intent(this, DrawActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        setStatusText(deviceService.getStatusText());
        super.onResume();
    }
}