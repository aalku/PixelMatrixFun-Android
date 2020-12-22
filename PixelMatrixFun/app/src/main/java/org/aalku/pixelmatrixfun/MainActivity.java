package org.aalku.pixelmatrixfun;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import org.aalku.pixelmatrixfun.R;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity {

    public static final int TX_SIZE = 512; // Max 512

    AtomicReference<Bitmap> readyToSendBitmap = new AtomicReference<>(null);

    UUID serviceUUID = UUID.fromString("6ff4913c-ea8a-4e5b-afdc-9f0f0e488ab1");
    UUID writeCharacteristicUUID = UUID.fromString("6ff4913c-ea8a-4e5b-afdc-9f0f0e488ab2");
    String referenceDeviceName = "PixelMatrixFun";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void send(View view) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        adapter.cancelDiscovery();
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();

        if (scanner != null) {
            ScanSettings scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(1000).build();
            List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setDeviceName(referenceDeviceName).build());
            ScanCallback scanCallback = new ScanCallback() {
                @RequiresApi(api = Build.VERSION_CODES.M)
                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    scanner.stopScan(this);
                    for (ScanResult r : results) {
                        Log.w("BLE", "  onBatchScanResults[*]: " + r);
                        String deviceName = r.getScanRecord().getDeviceName();
                        if (deviceName != null && deviceName.equals(referenceDeviceName)) {
                            Log.w("BLE", "  connecting to " + deviceName + "...");
                            BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

                                final AtomicReference<ByteArrayInputStream> baisRef = new AtomicReference<>(null);

                                private void internalWrite(BluetoothGatt gatt, byte[] bytes, BluetoothGattCharacteristic c) {
                                    if (bytes != null) {
                                        // New
                                        this.baisRef.set(new ByteArrayInputStream(bytes));
                                    }
                                    ByteArrayInputStream bais = this.baisRef.get();
                                    if (bais == null) {
                                        Log.e("BLE", "bais is null!");
                                    }
                                    byte[] buffer = new byte[Math.min(bais.available(), TX_SIZE)];
                                    if (buffer.length > 0) {
                                        try {
                                            if (bais.read(buffer) != buffer.length) {
                                                Log.e("BLE", "bais.read error!");
                                                throw new IOException("bais.read error!");
                                            }
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                        c.setValue(buffer);
                                        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                                        boolean ok = gatt.writeCharacteristic(c);
                                        Log.i("BLE", "Sent " + buffer.length + " " + (ok?"ok":"fail!"));
                                    } else {
                                        Log.i("BLE", "Sent!! Disconnecting!!");
                                        this.baisRef.set(null);
                                        gatt.disconnect();
                                    }
                                }

                                @Override
                                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                                        gatt.discoverServices();
                                    } else {
                                        gatt.close();
                                    }
                                }

                                @Override
                                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                                    for (BluetoothGattService s: gatt.getServices()) {
                                        if (s.getUuid().equals(serviceUUID)) {
                                            Log.i("BLE", "Found service!!");
                                            BluetoothGattCharacteristic c = s.getCharacteristic(writeCharacteristicUUID);
                                            Bitmap bitmap = readyToSendBitmap.get();
                                            if (bitmap != null) {
                                                sendBitmap(gatt, c, bitmap);
                                            } else {
                                                bitmap = Bitmap.createBitmap(16,16, Bitmap.Config.ARGB_8888);
                                                // Black?
                                                sendBitmap(gatt, c, bitmap);
                                            }
                                        }
                                    }
                                }

                                private void sendBitmap(BluetoothGatt gatt, BluetoothGattCharacteristic c, Bitmap bitmap) {
                                    byte[] pixelsBytes = getBitmapBytes(bitmap);
                                    internalWrite(gatt, pixelsBytes, c);
                                }

                                @Override
                                public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic c, int status) {
                                    if (status == BluetoothGatt.GATT_SUCCESS) {
                                        Log.d("BLE", "onCharacteristicWrite: OK");
                                        internalWrite(gatt, null, c); // Continue
                                    } else {
                                        Log.e("BLE", "Disconnecting due to write error: " + status);
                                        gatt.disconnect();
                                    }
                                }
                            };
                            r.getDevice().connectGatt(getBaseContext(), false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                        }
                    }
                    super.onBatchScanResults(results);
                }

                @Override
                public void onScanFailed(int errorCode) {
                    Log.e("BLE", "onScanFailed: " + errorCode);
                    super.onScanFailed(errorCode);
                }
            };
            scanner.startScan(filters, scanSettings, scanCallback);
            Log.d("BLE", "scan started");
            // scanner.flushPendingScanResults(scanCallback);
        }  else {
            Log.e("BLE", "could not get scanner object");
        }
    }

    private byte[] getBitmapBytes(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixelsInt = new int[w * h];
        byte[] out = new byte[pixelsInt.length * 3];
        bitmap.getPixels(pixelsInt, 0, w, 0, 0, w, h);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int argb = pixelsInt[y*w+x];
                byte red = (byte) Color.red(argb);
                byte green = (byte) Color.green(argb);
                byte blue = (byte) Color.blue(argb);
                int p = (x * h + y) * 3;
                out[p + 0] = red;
                out[p + 1] = green;
                out[p + 2] = blue;
            }
        }
        return out;
    }
}