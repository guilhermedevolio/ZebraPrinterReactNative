package com.guilherme5889.ZebraReactNativeZebra;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Trace;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.zebra.sdk.comm.Connection;
import com.zebra.sdk.comm.ConnectionException;
import com.zebra.sdk.comm.UsbConnection;
import com.zebra.sdk.printer.PrinterStatus;
import com.zebra.sdk.printer.ZebraPrinter;
import com.zebra.sdk.printer.ZebraPrinterLanguageUnknownException;
import com.zebra.sdk.printer.discovery.DiscoveredPrinter;
import com.zebra.sdk.printer.discovery.DiscoveredPrinterUsb;
import com.zebra.sdk.printer.discovery.DiscoveryHandler;
import com.zebra.sdk.printer.discovery.UsbDiscoverer;
import com.zebra.sdk.printer.ZebraPrinterFactory;

import java.util.LinkedList;
import java.util.List;

public class ZebraModule extends ReactContextBaseJavaModule {
    private UsbManager mUsbManager;
    private DiscoveredPrinterUsb discoveredPrinterUsb;
    public boolean hasPermissionToCommunicate = false;
    private PendingIntent mPermissionIntent;
    private UsbDevice usbDevice;

    public static final String DISCONNECT_INTENT = "com.zebra.kdu.usbDisconnected";
    public static final String USB_PERMISSION_GRANTED_ACTION = "com.zebra.kdu.usbPermissionGranted";

    private IntentFilter filter = new IntentFilter(USB_PERMISSION_GRANTED_ACTION);


    ZebraModule(ReactApplicationContext context) {
        super(context);

        mUsbManager = (UsbManager) getReactApplicationContext().getSystemService(Context.USB_SERVICE);
        mPermissionIntent = PendingIntent.getBroadcast(getReactApplicationContext(), 0, new Intent(USB_PERMISSION_GRANTED_ACTION), 0);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        context.registerReceiver(usbChangingReceiver, filter);

        IntentFilter permission = new IntentFilter(USB_PERMISSION_GRANTED_ACTION);
        context.registerReceiver(mUsbReceiver, permission);
    }

    @ReactMethod
    public Boolean CheckPermissionUSB() {
        Log.d("CheckPermissionUSB", this.hasPermissionToCommunicate ? "Permitido" : "Não Permitido");
        return this.hasPermissionToCommunicate ? true :false;
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (USB_PERMISSION_GRANTED_ACTION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            usbDevice = device;
                            hasPermissionToCommunicate = true;
                            sendEvent(getReactApplicationContext(), "ZEBRA_USB_CONNECT", null);
                        }
                    }
                    else {
                        hasPermissionToCommunicate = false;
                        sendEvent(getReactApplicationContext(), "ZEBRA_USB_DISCONNECT", null);
                        Toast.makeText(getReactApplicationContext(), "permission denied for device" + device, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    };

    private final BroadcastReceiver usbChangingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                if (device != null && UsbDiscoverer.isZebraUsbDevice(device)) {
                    sendEvent(getReactApplicationContext(), "ZEBRA_USB_DISCONNECT", null);
                    Toast.makeText(getReactApplicationContext(), "Zebra Desconectada", Toast.LENGTH_SHORT).show();
                }
            }

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                if (device != null && UsbDiscoverer.isZebraUsbDevice(device)) {
                    if (!mUsbManager.hasPermission(device)) {
                        mUsbManager.requestPermission(device, mPermissionIntent);
                    }

                    Toast.makeText(getReactApplicationContext(), "Zebra Conectada", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    @ReactMethod
    public void RequestPermission() {
        new Thread(new Runnable() {
            public void run() {
                // Find connected printers
                UsbDiscoveryHandler handler = new UsbDiscoveryHandler();
                UsbDiscoverer.findPrinters(getReactApplicationContext(), handler);

                try {
                    while (!handler.discoveryComplete) {
                        Thread.sleep(100);
                    }

                    if (handler.printers != null && handler.printers.size() > 0) {
                        discoveredPrinterUsb = handler.printers.get(0);

                        if (!mUsbManager.hasPermission(discoveredPrinterUsb.device)) {
                            mUsbManager.requestPermission(discoveredPrinterUsb.device, mPermissionIntent);
                        } else {
                            sendEvent(getReactApplicationContext(), "ZEBRA_USB_CONNECT", null);
                            hasPermissionToCommunicate = true;
                        }
                    }
                } catch (Exception e) {
                    Toast.makeText(getReactApplicationContext(), "Error in Request Permission", Toast.LENGTH_SHORT).show();
                }
            }
        }).start();
    }

    private String mapStatus(PrinterStatus printerStatus) {
        if (printerStatus.isReadyToPrint) {
            return "Ready To Print";
        } else if (printerStatus.isPaused) {
            return "Cannot Print because the printer is paused.";
        } else if (printerStatus.isHeadOpen) {
            return "Cannot Print because the printer head is open.";
        } else if (printerStatus.isPaperOut) {
            return "Cannot Print because the paper is out.";
        } else {
            return "Cannot Print";
        }
    }

    @SuppressLint("StaticFieldLeak")
    @ReactMethod
    public void printZPL(String zpl, Promise promise) {

        if(!hasPermissionToCommunicate) {
            Log.d("printteste", "SEM PERMISSAO NA PORTA USB");
            promise.reject("SEM PERMISSAO NA PORTA USB");
            return;
        }

        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
            }

            @Override
            protected Boolean doInBackground(Void... voids) {
                Connection conn = new UsbConnection(mUsbManager, usbDevice);

                try {
                    if(conn == null) {
                        promise.reject("NENHUMA ZEBRA CONECTADA");
                        return false;
                    }

                    conn.open();

                    ZebraPrinter printer = ZebraPrinterFactory.getInstance(conn);
                    PrinterStatus printerStatus = printer.getCurrentStatus();
                    String friendlyStatus = mapStatus(printerStatus);

                    if(!printerStatus.isReadyToPrint) {
                        promise.reject("A ZEBRA ESTA DOENTE:" + friendlyStatus);
                    } else {
                        printer.sendCommand(zpl);
                        promise.resolve("OK");
                    }

                    return true;
                } catch (ConnectionException e) {
                    Log.d("printteste", "ERRO DE CONEXAO");
                    promise.reject("ERRO DE CONEXAO");
                    return false;
                } catch (ZebraPrinterLanguageUnknownException e) {
                    Log.d("printteste", "LINGUAGEM DO ARQUIVO DE IMPRESSAO NAO IDENTIFICADO");
                    promise.reject("LINGUAGEM DO ARQUIVO DE IMPRESSAO NAO IDENTIFICADO");
                    return false;
                } finally {
                    if (conn != null) {
                        try {
                            conn.close();
                        } catch (ConnectionException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }

            @Override
            protected void onPostExecute(Boolean result) {
                super.onPostExecute(result);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        return;
    }

    private class UsbDiscoveryHandler implements DiscoveryHandler {
        public List<DiscoveredPrinterUsb> printers;
        public boolean discoveryComplete = false;

        public UsbDiscoveryHandler() {
            printers = new LinkedList<DiscoveredPrinterUsb>();
        }

        public void foundPrinter(final DiscoveredPrinter printer) {
            printers.add((DiscoveredPrinterUsb) printer);
        }

        public void discoveryFinished() {
            discoveryComplete = true;
        }

        public void discoveryError(String message) {
            discoveryComplete = true;
        }
    }

    private void sendEvent(ReactContext reactContext,
                           String eventName,
                           @Nullable WritableMap params) {
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    @NonNull
    @Override
    public String getName() {
        return "ZEBRA";
    }
}
