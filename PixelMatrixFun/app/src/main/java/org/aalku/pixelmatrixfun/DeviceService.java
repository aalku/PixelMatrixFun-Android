package org.aalku.pixelmatrixfun;

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
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@RequiresApi(api = Build.VERSION_CODES.N)
public class DeviceService {

    public static DeviceService instance;

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
    private final AtomicReference<BluetoothGatt> gattRef = new AtomicReference<>(null);
    private final AtomicReference<BluetoothGattService> serviceRef = new AtomicReference<>(null);

    private final ConcurrentLinkedDeque<WriteBytesMessage> currentlySending = new ConcurrentLinkedDeque<>();

    private final AtomicLong lastHelo =  new AtomicLong(0L);
    private final AtomicReference<BluetoothGattCharacteristic> heloChar = new AtomicReference<>(null);

    private AtomicReference<String> statusText = new AtomicReference<>("");
    private Collection<Consumer<String>> statusListeners = new ArrayList<>();
    private Context context;

    @RequiresApi(api = Build.VERSION_CODES.N)
    public DeviceService(Context baseContext) {
        synchronized (this.getClass()) {
            if (instance != null) {
                throw new IllegalStateException("DeviceService was created twice");
            }
            instance = this;
        }
        context = baseContext;

        executor.scheduleWithFixedDelay(() -> {
            long lastHelo = this.lastHelo.get();
            double lastHeloAgoSeconds = lastHelo <= 0L ? 0L : (System.currentTimeMillis() - lastHelo) / 1000.0;
            Log.d("BLE", this + " - Last HELO was " + lastHeloAgoSeconds + "s ago");
            if (bleConnected.get()) {
                if (lastHelo > 0 && lastHeloAgoSeconds > 5d) {
                    setStatusText("Connection might be lost!");
                    /* Disconnecting seems not useful. You can't connect fast after that */
                    // Optional.ofNullable(gattRef.get()).ifPresent(g->g.disconnect());
                } else if (lastHelo > 0) {
                    setStatusText("PixelMatrixFun is connected and ready.");
                } else {
                    setStatusText("Connected?");
                }
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
            } else {
                bleTryConnect();
            }
        }, 0, 1, TimeUnit.SECONDS);

    }

    @RequiresApi(api = Build.VERSION_CODES.N)
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

    @RequiresApi(api = Build.VERSION_CODES.N)
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

    @RequiresApi(api = Build.VERSION_CODES.N)
    void bleTryConnect() {
        Log.d("BLE", this + " - bleConnect()");
        synchronized (bleConnected) {
            if (bleConnected.get()) {
                Log.d("BLE", this + " - Already connected.");
                return;
            } else if (bleConnectingSince.get() > 0) {
                Log.d("BLE", this + " - Already connecting.");
                return;
            } else {
                Log.d("BLE", this + " - Need to scan.");
                bleConnectingSince.set(System.currentTimeMillis());
            }
        }
        setStatusText("...");
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        adapter.cancelDiscovery();
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();

        if (scanner != null) {
            setStatusText("Scanning...");
            ScanSettings scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(1000).build();
            List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setDeviceName(REFERENCE_DEVICE_NAME).build());
            ScanCallback scanCallback = new ScanCallback() {
                @RequiresApi(api = Build.VERSION_CODES.N)
                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    if (bleConnected.get()) {
                        // Ignored because already connected
                        return;
                    }
                    Log.d("BLE", this + " -   onBatchScanResults(results.size=" + results.size() + ")");
                    scanner.stopScan(this); // Enough
                    if (results.size() == 0) {
                        synchronized (bleConnected) {
                            bleConnectingSince.set(0L);
                            setStatusText("MatrixPixelFun not found");
                            return;
                        }
                    }
                    for (ScanResult r : results) {
                        Log.w("BLE", "  onBatchScanResults[*]: " + r);
                        String deviceName = r.getScanRecord().getDeviceName();
                        if (deviceName != null && deviceName.equals(REFERENCE_DEVICE_NAME)) {
                            setStatusText("Found MatrixPixelFun ...");
                            Log.w("BLE", "  connecting to " + deviceName + "...");
                            BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

                                @Override
                                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                                        onBleConnected(gatt);
                                    } else {
                                        onDisconnected(gatt);
                                    }
                                }

                                @Override
                                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                                    if (status != BluetoothGatt.GATT_SUCCESS) {
                                        Log.e("BLE", this + " - Can't discover services!!");
                                        gatt.disconnect();
                                        return;
                                    }
                                    BluetoothGattService s = gatt.getService(SERVICE_UUID);
                                    if (s != null) {
                                        Log.i("BLE", this + " - Found service!!");
                                        onFullyConnected(gatt, s);
                                    } else {
                                        Log.i("BLE", this + " - Service not found!!");
                                        gatt.discoverServices();
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
                                        internalWrite(gatt); // Continue
                                    } else {
                                        Log.e("BLE", "Disconnecting due to write error: " + status);
                                        gatt.disconnect();
                                    }
                                }
                            };
                            r.getDevice().connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                        }
                    }
                    super.onBatchScanResults(results);
                }

                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    Log.d("BLE", this + " - onScanResult(" + result +")");
                }

                @Override
                public void onScanFailed(int errorCode) {
                    Log.e("BLE", this + " - onScanFailed: " + errorCode);
                    super.onScanFailed(errorCode);
                }
            };
            scanner.startScan(filters, scanSettings, scanCallback);
            Log.d("BLE", this + " - scan started");
            // scanner.flushPendingScanResults(scanCallback);
        }  else {
            Log.e("BLE", this + " - could not get scanner object");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void onFullyConnected(BluetoothGatt gatt, BluetoothGattService s) {
        BluetoothGattCharacteristic heloChar = s.getCharacteristic(HELO_UUID);
        DeviceService.this.heloChar.set(heloChar);
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

        setStatusText("MatrixPixelFun is ready.");
        // gatt.requestMtu(256);
        synchronized (bleConnected) {
            gattRef.set(gatt);
            serviceRef.set(s);
            bleConnected.set(true);
            bleConnectingSince.set(0L);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void onBleConnected(BluetoothGatt gatt) {
        executor.schedule(()->{
            setStatusText("Discovering device services ...");
            gatt.discoverServices();
        }, 100, TimeUnit.MILLISECONDS);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void onDisconnected(BluetoothGatt gatt) {
        synchronized (bleConnected) {

            BluetoothGattCharacteristic heloChar = DeviceService.this.heloChar.get();
            if (heloChar != null && gatt.setCharacteristicNotification(heloChar, true)) {
                BluetoothGattDescriptor descriptor = heloChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
                DeviceService.this.heloChar.set(null);
            }
            gatt.disconnect();
            gatt.close();
            setStatusText("MatrixPixelFun was disconnected");
            bleConnected.set(false);
            serviceRef.set(null);
            gattRef.set(null);
            bleConnectingSince.set(0L);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void sendBitmap(BluetoothGatt gatt, BluetoothGattCharacteristic c, Bitmap bitmap) {
        byte[] pixelsBytes = getBitmapBytes(bitmap);
        setStatusText("Sending bitmap...");
        writeBytesCharacteristic(gatt, pixelsBytes, c);
    }

    public String getStatusText() {
        return statusText.get();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void setStatusText(String s) {
        this.statusText.set(s);
        for (Consumer<String> c: statusListeners) {
            c.accept(s);
        }
    }

    public void addStatusListener(Consumer<String> x) {
        statusListeners.add(x);
        Log.d("StatusListener", "addStatusListener(" + x + "); total=" + statusListeners.size());
    }

    public void removeStatusListener(Consumer<String> x) {
        statusListeners.remove(x);
        Log.d("StatusListener", "removeStatusListener(" + x + "); total=" + statusListeners.size());
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public void disconnect() {
        Optional.ofNullable(gattRef.get()).ifPresent(g->{
            g.disconnect();
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public void close() {
        executor.shutdown();
        disconnect();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void writeBytesCharacteristic(BluetoothGatt gatt, byte[] bytes, BluetoothGattCharacteristic c) {
        this.currentlySending.addAll(WriteBytesMessage.asMessages(c, bytes));
        internalWrite(gatt);
    }

    private void internalWrite(BluetoothGatt gatt) {
        WriteBytesMessage msg = this.currentlySending.poll();
        if (msg != null) {
            msg.c.setValue(msg.bytes);
            msg.c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            boolean ok = gatt.writeCharacteristic(msg.c);
            Log.i("BLE", "Sent " + msg.bytes.length + " " + (ok?"ok":"fail!"));
            setStatusText("Sending ..." + this.currentlySending.stream().map(z->".").collect(Collectors.joining()));
        }
    }

    private static class WriteBytesMessage {
        private final BluetoothGattCharacteristic c;
        private final byte[] bytes;

        private WriteBytesMessage(BluetoothGattCharacteristic c, byte[] bytes) {
            this.c = c;
            this.bytes = bytes;
        }

        public static List<WriteBytesMessage> asMessages(BluetoothGattCharacteristic c, byte[] bytes) {
            int offset = 0;
            List<WriteBytesMessage> res = new ArrayList<>();
            while (offset < bytes.length) {
                int len = Math.min(bytes.length - offset, TX_SIZE);
                byte[] buff = new byte[len];
                System.arraycopy(bytes, offset, buff, 0, len);
                res.add(new WriteBytesMessage(c, buff));
                offset += len;
            }
            return res;
        }

    }
}