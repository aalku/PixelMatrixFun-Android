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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@RequiresApi(api = Build.VERSION_CODES.N)
public class DeviceService {

    public static DeviceService instance;

    public static final int TX_SIZE = 256; // Max 512

    private static final UUID SERVICE_UUID = UUID.fromString("5e92c674-3f45-420c-bb53-af2bcaff68b2");
    private static final UUID SEND_BITMAP_UUID = UUID.fromString("2de8d042-9a37-45f0-8233-5160faea472d");
    private static final UUID HELO_UUID = UUID.fromString("3ca58a89-0754-44d3-83e5-371aac29ca36");
    private static final UUID SEND_PIXEL_UUID = UUID.fromString("5038a8b8-5e8e-4f1f-97dd-8cf76c8e90e1");
    private static final UUID CLEAR_UUID = UUID.fromString("2ae4ad40-f1e2-4f4d-893c-f0109c024381");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String REFERENCE_DEVICE_NAME = "PixelMatrixFun";

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private final AtomicLong bleConnectingSince = new AtomicLong(0L);
    private final AtomicBoolean bleConnected = new AtomicBoolean(false);
    private final AtomicReference<BluetoothGatt> gattRef = new AtomicReference<>(null);
    private final AtomicReference<BluetoothGattService> serviceRef = new AtomicReference<>(null);

    private final ConcurrentLinkedDeque<WriteBytesMessage> sendQueue = new ConcurrentLinkedDeque<>();
    private final AtomicReference<WriteBytesMessage> currentlySending = new AtomicReference<>(null);
    private final AtomicBoolean sendLock = new AtomicBoolean(true);

    private final ArrayList<Path> pendingPaths = new ArrayList<>();

    private final AtomicLong lastHelo =  new AtomicLong(0L);
    private final AtomicReference<BluetoothGattCharacteristic> heloChar = new AtomicReference<>(null);

    private AtomicReference<String> statusText = new AtomicReference<>("");
    private Collection<Consumer<String>> statusListeners = new ArrayList<>();
    private Context context;

    private Collection<Consumer<Boolean>> connectionListeners = new ArrayList<>();

    private ConcurrentHashMap<UUID, AtomicInteger> seq = new ConcurrentHashMap<>();

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

    public CompletionStage<Boolean> sendBitmap(Bitmap bitmap) {
        BluetoothGattService s = serviceRef.get();
        BluetoothGatt gatt = gattRef.get();
        if (checkConnected(s, gatt)) {
            BluetoothGattCharacteristic c = s.getCharacteristic(SEND_BITMAP_UUID);
            Log.i("BLE", "Sending image...");
            if (bitmap != null) {
                return internalSendBitmap(gatt, c, bitmap);
            } else {
                bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
                // Black?
                return internalSendBitmap(gatt, c, bitmap);
            }
        }
        CompletableFuture<Boolean> errorCf = new CompletableFuture<>();
        errorCf.completeExceptionally(new IOException("Not connected"));
        return errorCf;
    }

    public void sendPixel(int x, int y, int color) {

        synchronized (pendingPaths) {
            Path p = null;
            for (Path pp: pendingPaths) {
                if (pp.merge(x, y, color)) {
                    p = pp;
                    break;
                }
            }
            if (p == null) {
                pendingPaths.add(p = new Path(x, y, color));
            }
            if (p.isFull() || this.sendQueue.isEmpty()) {
                pendingPaths.remove(p);
                sendPath(p);
            }
        }
    }

    private void sendPath(Path p) {
        if (p == null) {
            synchronized (pendingPaths) {
                if (pendingPaths.isEmpty()) {
                    return;
                } else {
                    sendPath(pendingPaths.remove(0));
                }
            }
        } else {
            setStatusText("Sending pixels...");
            BluetoothGattService s = serviceRef.get();
            BluetoothGatt gatt = gattRef.get();
            if (checkConnected(s, gatt)) {
                BluetoothGattCharacteristic c = s.getCharacteristic(SEND_PIXEL_UUID);
                Log.i("BLE", "Sending pixels...");
                writeBytesCharacteristic(gatt, p.getBytes(), c);
            }
        }
    }

    public CompletionStage<Boolean> clearBitmap(int color) {
        setStatusText("Clearing bitmap...");
        BluetoothGattService s = serviceRef.get();
        BluetoothGatt gatt = gattRef.get();
        if (checkConnected(s, gatt)) {
            BluetoothGattCharacteristic c = s.getCharacteristic(CLEAR_UUID);
            Log.i("BLE", "Clearing bitmap...");
            byte[] pixelsBytes = new byte[4];
            pixelsBytes[0] = (byte) ((color >> 16) & 0xff);
            pixelsBytes[1] = (byte) ((color >> 8 ) & 0xff);
            pixelsBytes[2] = (byte) ((color      ) & 0xff);
            pixelsBytes[3 ] = (byte) (getSeq(CLEAR_UUID) & 0xff);
            return writeBytesCharacteristic(gatt, pixelsBytes, c);
        }
        CompletableFuture<Boolean> errorCf = new CompletableFuture<>();
        errorCf.completeExceptionally(new IOException("Not connected"));
        return errorCf;
    }

    private int getSeq(UUID key) {
        return seq.computeIfAbsent(key, k->new AtomicInteger(0)).incrementAndGet();
    }

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
                                    DeviceService.this.onCharacteristicWrite(gatt, c, status);
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
            sendLock.set(false);
            notifyConnectionListeners(true);
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
            sendLock.set(true);
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
            notifyConnectionListeners(false);
        }
    }

    private CompletionStage<Boolean> internalSendBitmap(BluetoothGatt gatt, BluetoothGattCharacteristic c, Bitmap bitmap) {
        byte[] pixelsBytes = getBitmapBytes(bitmap);
        setStatusText("Sending bitmap...");
        return writeBytesCharacteristic(gatt, pixelsBytes, c);
    }

    public String getStatusText() {
        return statusText.get();
    }

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

    public void removeConnectionListener(Consumer<Boolean> x) {
        connectionListeners.remove(x);
        Log.d("ConnectionListener", "removeConnectionListener(" + x + "); total=" + connectionListeners.size());
    }

    public void addConnectionListener(Consumer<Boolean> x) {
        connectionListeners.add(x);
        Log.d("ConnectionListener", "addConnectionListener(" + x + "); total=" + connectionListeners.size());
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public void disconnect() {
        Optional.ofNullable(gattRef.get()).ifPresent(g->{
            g.disconnect();
        });
    }

    public void close() {
        executor.shutdown();
        disconnect();
    }

    private synchronized CompletionStage<Boolean> writeBytesCharacteristic(BluetoothGatt gatt, byte[] bytes, BluetoothGattCharacteristic c) {
        WriteBytesMessage msg = new WriteBytesMessage(c, bytes);
        sendQueue.add(msg);
        internalWrite(gatt);
        return msg.getFuture();
    }

    private synchronized void internalWrite(BluetoothGatt gatt) {
        if (!sendLock.getAndSet(true)) {
            WriteBytesMessage msg = currentlySending.get();
            boolean isContinuation = (msg != null);
            if (msg == null) {
                /* Let's see if there is something else to send */
                if (this.sendQueue.isEmpty()) {
                    sendPath(null);
                }
                msg = this.sendQueue.poll();
                if (msg == null) {
                    Log.i("BLE", "Nothing to send.");
                    sendLock.set(false);
                    return;
                }
            }
            currentlySending.set(msg);
            int offset = msg.getOffset();
            int totalLen = msg.getLength();
            byte[] bytes = msg.next();
            UUID cUUID = msg.getCharacteristic().getUuid();
            if (bytes.length > 0 && cUUID.equals(SEND_BITMAP_UUID)) {
                bytes = makePage(getSeq(cUUID), totalLen, offset, bytes);
            }
            BluetoothGattCharacteristic c = gatt.getService(SERVICE_UUID).getCharacteristic(cUUID);
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            c.setValue(bytes);
            boolean ok = gatt.writeCharacteristic(c);
            if (ok) {
                setStatusText(String.format("Sent %d/%d to %s", msg.getOffset(), msg.getLength(), c.getUuid().toString()));
            } else {
                sendLock.set(false);
                setStatusText("Error sending");
                executor.schedule(() -> internalWrite(gatt), 100, TimeUnit.MILLISECONDS);
            }
        }
    }

    private byte[] makePage(int seq, int totalLen, int offset, byte[] bytes) {
        byte[] res = new byte[bytes.length+5];
        System.arraycopy(bytes, 0, res, res.length - bytes.length, bytes.length);
        res[0]=(byte)(seq & 0xFF);
        res[1]=(byte)((totalLen >> 8) & 0xFF);
        res[2]=(byte)((totalLen     ) & 0xFF);
        res[3]=(byte)((offset >> 8) & 0xFF);
        res[4]=(byte)((offset     ) & 0xFF);
        return res;
    }

    private synchronized void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic c, int status) {
        sendLock.set(false);
        WriteBytesMessage sending = currentlySending.get();
        if (sending == null) {
            Log.e("BLE", "onCharacteristicWrite without currentlySending", new IllegalStateException());
            return;
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d("BLE", "onCharacteristicWrite: OK");
            if (sending.isDone()) {
                currentlySending.set(null);
                sending.complete(true);
                internalWrite(gatt);
            } else {
                internalWrite(gatt);
            }
        } else {
            Log.e("BLE", "Disconnecting due to write error: " + status);
            currentlySending.set(null);
            sending.completeExceptionally(new IOException("Write error: " + status));
            gatt.disconnect();
        }
    }

    private static class WriteBytesMessage {
        private final BluetoothGattCharacteristic c;
        private final byte[] bytes;
        private int offset = 0;
        private final CompletableFuture<Boolean> cf = new CompletableFuture<>();

        public int getOffset() {
            return offset;
        }

        public CompletionStage<Boolean> getFuture() {
            return cf;
        }

        private WriteBytesMessage(BluetoothGattCharacteristic c, byte[] bytes) {
            this.c = c;
            this.bytes = bytes;
        }

        public byte[] next() {
            int len = Math.min(bytes.length-offset, TX_SIZE);
            byte[] res = new byte[len];
            System.arraycopy(bytes, offset, res, 0, len);
            if (len == 0) {
                /* Special case to send an empty msg at the end. This and isDone() work together to achieve that. */
                offset++;
            } else {
                offset += len;
            }
            return res;
        }

        public boolean isDone() {
            /* We use > so there is an empty msg in the end.
             This is important since the las characteristicWrite may be automatically repeated later */
            return offset > bytes.length;
        }

        public BluetoothGattCharacteristic getCharacteristic() {
            return c;
        }

        public void complete(boolean value) {
            cf.complete(value);
        }

        public void completeExceptionally(Throwable ex) {
            cf.completeExceptionally(ex);
        }

        public int getLength() {
            return bytes.length;
        }
    }

    private static class Path {
        final static int PW = 5;
        final static int MAX = 16;
        int color;
        int lx;
        int ly;
        int count = 0;
        byte[] bytes = new byte[MAX*PW];
        Path(int x, int y, int color) {
            this.lx = x;
            this.ly = y;
            this.color = color;
            add(x,y);
        }
        boolean merge(int x, int y, int color) {
            if (color != this.color) {
                return false;
            }
            if (Math.abs(x-lx)>1) {
                return false;
            }
            if (Math.abs(y-ly)>1) {
                return false;
            }
            return add(x,y);
        }
        private boolean add(int x, int y) {
            if (count < MAX) {
                int pos = count * PW;
                bytes[pos+0] = (byte)(x & 0xFF);
                bytes[pos+1] = (byte)(y & 0xFF);
                bytes[pos+2] = (byte) ((color >> 16) & 0xff);
                bytes[pos+3] = (byte) ((color >> 8 ) & 0xff);
                bytes[pos+4] = (byte) ((color      ) & 0xff);
                lx = x;
                ly = y;
                count++;
                return true;
            } else {
                return false;
            }
        }
        public byte[] getBytes() {
            byte[] res;
            if (count == MAX) {
                res = bytes;
            } else {
                res = new byte[count*PW];
                System.arraycopy(bytes, 0, res, 0, count*PW);
            }
            return res;
        }

        public boolean isFull() {
            return count == MAX;
        }
    }

    private void notifyConnectionListeners(boolean connected) {
        for (Consumer<Boolean> l: connectionListeners) {
            l.accept(connected);
        }
    }

    public boolean isConnected() {
        return bleConnected.get();
    }

}