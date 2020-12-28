package org.aalku.pixelmatrixfun;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class DeviceService {

    public static final int TX_SIZE = 256; // Max 512

    private static final UUID SERVICE_UUID = UUID.fromString("6ff4913c-ea8a-4e5b-afdc-9f0f0e488ab1");
    private static final UUID SEND_BITMAP_UUID = UUID.fromString("6ff4913c-ea8a-4e5b-afdc-9f0f0e488ab2");
    private static final UUID HELO_UUID = UUID.fromString("6ff4913c-ea8a-4e5b-afdc-9f0f0e488ab3");
    private static final UUID SEND_PIXEL_UUID = UUID.fromString("6ff4913c-ea8a-4e5b-afdc-9f0f0e488ab4");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String REFERENCE_DEVICE_NAME = "PixelMatrixFun";

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private final AtomicLong bleConnectingSince = new AtomicLong(0L);
    private final AtomicBoolean bleConnected = new AtomicBoolean(false);
    private final AtomicReference<BluetoothGatt> gattRef = new AtomicReference(null);
    private final AtomicReference<BluetoothGattService> serviceRef = new AtomicReference(null);

    private final AtomicReference<ByteArrayInputStream> currentlySending = new AtomicReference<>(null);

    private final AtomicLong lastHelo =  new AtomicLong(0L);

    private MainActivity mainActivity;

    @RequiresApi(api = Build.VERSION_CODES.M)
    DeviceService(MainActivity mainActivity) {
        this.mainActivity = mainActivity;
        executor.scheduleWithFixedDelay(() -> {
            long lastHelo = this.lastHelo.get();
            double lastHeloAgoSeconds = lastHelo <= 0L ? 0L : (System.currentTimeMillis() - lastHelo) / 1000.0;
            Log.d("BLE", "Last HELO was " + lastHeloAgoSeconds + "s ago");
            if (bleConnected.get() && lastHelo > 0 && lastHeloAgoSeconds > 3d) {
                mainActivity.setStatusText("Connection might be lost!");
                /* Disconnecting seems not useful. You can't connect fast after that */
                // Optional.ofNullable(gattRef.get()).ifPresent(g->g.disconnect());
            } else if (!bleConnected.get() && bleConnectingSince.get() == 0) {
                bleTryConnect();
            } else if (bleConnectingSince.get() > 0) {
                long since = bleConnectingSince.get();
                if (since > 0 && (System.currentTimeMillis() - since) > 5000) {
                    BluetoothGatt gatt = gattRef.get();
                    if (gatt != null) {
                        gatt.disconnect();
                    } else {
                        synchronized (bleConnected) {
                            bleConnectingSince.set(0L);
                        }
                    }
                }
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    public void sendBitmap(Bitmap bitmap) {
        BluetoothGattService s = serviceRef.get();
        BluetoothGatt gatt = gattRef.get();
        if (checkConnected(s, gatt)) {
            BluetoothGattCharacteristic c = s.getCharacteristic(SEND_BITMAP_UUID);
            Log.i("BLE", "Sending image...");
            if (bitmap != null) {
                sendBitmap(gatt, c, bitmap);
            } else {
                bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
                // Black?
                sendBitmap(gatt, c, bitmap);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    private boolean checkConnected(BluetoothGattService s, BluetoothGatt gatt) {
        return bleConnected.get() && s != null && gatt != null;
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
                out[p] = red;
                out[p + 1] = green;
                out[p + 2] = blue;
            }
        }
        return out;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    void bleTryConnect() {
        Log.d("BLE", "bleConnect()");
        synchronized (bleConnected) {
            if (bleConnected.get()) {
                Log.d("BLE", "Already connected.");
                return;
            } else if (bleConnectingSince.get() > 0) {
                Log.d("BLE", "Already connecting.");
                return;
            } else {
                Log.d("BLE", "Need to scan.");
                bleConnectingSince.set(System.currentTimeMillis());
            }
        }
        mainActivity.setStatusText("...");
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        adapter.cancelDiscovery();
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();

        if (scanner != null) {
            mainActivity.setStatusText("Scanning...");
            ScanSettings scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(1000).build();
            List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setDeviceName(REFERENCE_DEVICE_NAME).build());
            ScanCallback scanCallback = new ScanCallback() {
                @RequiresApi(api = Build.VERSION_CODES.M)
                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    Log.d("BLE", "  onBatchScanResults(results.size=" + results.size() + ")");
                    scanner.stopScan(this); // Enough
                    if (results.size() == 0) {
                        synchronized (bleConnected) {
                            bleConnectingSince.set(0L);
                            String msg = "MatrixPixelFun not found";
                            mainActivity.setStatusText(msg);
                            return;
                        }
                    }
                    for (ScanResult r : results) {
                        Log.w("BLE", "  onBatchScanResults[*]: " + r);
                        String deviceName = r.getScanRecord().getDeviceName();
                        if (deviceName != null && deviceName.equals(REFERENCE_DEVICE_NAME)) {
                            mainActivity.setStatusText("Found MatrixPixelFun ...");
                            Log.w("BLE", "  connecting to " + deviceName + "...");
                            BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

                                @Override
                                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                                        executor.schedule(()->{
                                            mainActivity.setStatusText("Discovering device services ...");
                                            gatt.discoverServices();
                                        }, 100, TimeUnit.MILLISECONDS);
                                    } else {
                                        synchronized (bleConnected) {
                                            gatt.disconnect();
                                            gatt.close();
                                            mainActivity.setStatusText("MatrixPixelFun was disconnected");
                                            bleConnected.set(false);
                                            serviceRef.set(null);
                                            gattRef.set(null);
                                            bleConnectingSince.set(0L);
                                        }
                                    }
                                }

                                @Override
                                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                                    if (status != BluetoothGatt.GATT_SUCCESS) {
                                        Log.e("BLE", "Can't discover services!!");
                                        gatt.disconnect();
                                        return;
                                    }
                                    BluetoothGattService s = gatt.getService(SERVICE_UUID);
                                    if (s != null) {
                                        Log.i("BLE", "Found service!!");
                                        BluetoothGattCharacteristic heloChar = s.getCharacteristic(HELO_UUID);
                                        if (heloChar == null) {
                                            lastHelo.set(-1);
                                        } else {
                                            lastHelo.set(0L);
                                        }
                                        if (gatt.setCharacteristicNotification(heloChar, true)) {
                                            BluetoothGattDescriptor descriptor = heloChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                            gatt.writeDescriptor(descriptor);
                                        }

                                        mainActivity.setStatusText("MatrixPixelFun is ready.");
                                        // gatt.requestMtu(256);
                                        synchronized (bleConnected) {
                                            gattRef.set(gatt);
                                            serviceRef.set(s);
                                            bleConnected.set(true);
                                            bleConnectingSince.set(0L);
                                        }
                                    } else {
                                        Log.i("BLE", "Service not found!!");
                                        gatt.disconnect();
                                    }
                                }

                                @Override
                                public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                                    Log.d("BLE", "CharacteristicsChanged: " + characteristic);
                                    if (characteristic.getUuid().equals(HELO_UUID)) {
                                        lastHelo.set(System.currentTimeMillis());
                                    }
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
                            r.getDevice().connectGatt(mainActivity.getBaseContext(), false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                        }
                    }
                    super.onBatchScanResults(results);
                }

                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    Log.d("BLE", "onScanResult(" + result +")");
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

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void internalWrite(BluetoothGatt gatt, byte[] bytes, BluetoothGattCharacteristic c) {
        if (bytes != null) {
            // New
            this.currentlySending.set(new ByteArrayInputStream(bytes));
        }
        ByteArrayInputStream bais = this.currentlySending.get();
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
            Log.i("BLE", "Sent!!");
            mainActivity.setStatusText("Sent!! PixelMatrixFun is ready.");
            this.currentlySending.set(null);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void sendBitmap(BluetoothGatt gatt, BluetoothGattCharacteristic c, Bitmap bitmap) {
        byte[] pixelsBytes = getBitmapBytes(bitmap);
        mainActivity.setStatusText("Sending bitmap...");
        internalWrite(gatt, pixelsBytes, c);
    }

}