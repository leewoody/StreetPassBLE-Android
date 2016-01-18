package gupuru.streetpassble.service;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.text.TextUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import gupuru.streetpassble.constants.Constants;
import gupuru.streetpassble.parcelable.AdvertiseSuccessParcelable;
import gupuru.streetpassble.parcelable.ErrorParcelable;
import gupuru.streetpassble.parcelable.ScanDataParcelable;

public class StreetPassService extends Service {

    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothLeAdvertiser bluetoothLeAdvertiser;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private ConnectDeviceReceiver connectDeviceReceiver = null;
    private IntentFilter connectDeviceFilter = null;

    public StreetPassService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();

        connectDeviceReceiver = new ConnectDeviceReceiver();
        connectDeviceFilter = new IntentFilter(
                Constants.ACTION_CONNECT_DEVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null) {
            //uuid取得
            String uuid = "";
            if (!TextUtils.isEmpty(intent.getStringExtra(Constants.UUID))) {
                uuid = intent.getStringExtra(Constants.UUID);
            }
            //serviceData取得
            String data = "";
            if (!TextUtils.isEmpty(intent.getStringExtra(Constants.DATA))) {
                data = intent.getStringExtra(Constants.DATA);
            }
            //scanMode取得
            int scanMode = intent.getIntExtra(Constants.SCAN_MODE, ScanSettings.SCAN_MODE_LOW_POWER);
            //txPowerLevel取得
            int txPowerLevel = intent.getIntExtra(Constants.TX_POWER_LEVEL, AdvertiseSettings.ADVERTISE_TX_POWER_LOW);
            //advertiseMode取得
            int advertiseMode = intent.getIntExtra(Constants.ADVERTISE_MODE, AdvertiseSettings.ADVERTISE_MODE_BALANCED);

            registerReceiver(connectDeviceReceiver, connectDeviceFilter);

            //BLEの送信に対応しているか
            if (BluetoothAdapter.getDefaultAdapter().isMultipleAdvertisementSupported()) {
                //対応->送受信
                scan(uuid, scanMode);
                advertising(uuid, data, advertiseMode, txPowerLevel);
            } else {
                //非対応->受信のみ
                scan(uuid, scanMode);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (scanCallback != null && bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(scanCallback);
            bluetoothLeScanner = null;
        }
        if (bluetoothLeAdvertiser != null && advertiseCallback != null) {
            bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
            bluetoothLeAdvertiser = null;
        }
        unregisterReceiver(connectDeviceReceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scan(String uuid, int scanMode) {
        if (uuid != null && !uuid.equals("")) {
            List<ScanFilter> filters = new ArrayList<>();
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(UUID.fromString(uuid)))
                    .build();
            filters.add(filter);

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(scanMode)
                    .build();

            bluetoothLeScanner.startScan(filters, settings, scanCallback);
        }
    }

    private void advertising(String uuid, String data, int advertiseMode, int txPowerLevel) {
        if (uuid != null && !uuid.equals("")) {
            // 設定
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(advertiseMode)
                    .setTxPowerLevel(txPowerLevel)
                    .setTimeout(0)
                    .setConnectable(true)
                    .build();

            if (data == null) {
                data = "";
            }
            // アドバタイジングデータ
            ParcelUuid pUuid = new ParcelUuid(UUID.fromString(uuid));
            AdvertiseData advertiseData = new AdvertiseData.Builder()
                    .addServiceUuid(pUuid)
                    .addServiceData(pUuid, data.getBytes(Charset.forName("UTF-8")))
                    .setIncludeDeviceName(true)
                    .setIncludeTxPowerLevel(true)
                    .build();

            bluetoothLeAdvertiser.startAdvertising(settings, advertiseData, advertiseCallback);
        }
    }

    private AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);

            AdvertiseSuccessParcelable advertiseSuccessParcelable = new AdvertiseSuccessParcelable(
                    settingsInEffect.getTxPowerLevel(),
                    settingsInEffect.getMode(),
                    settingsInEffect.getTimeout()
            );

            Intent intent = new Intent();
            intent.setAction(Constants.ACTION_ADV);
            intent.putExtra(Constants.ADV_DATA, advertiseSuccessParcelable);
            sendBroadcast(intent);
        }

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            String errorMessage = "";
            switch (errorCode) {
                case ADVERTISE_FAILED_ALREADY_STARTED:
                    errorMessage = "既にAdvertiseを実行中です";
                    break;
                case ADVERTISE_FAILED_DATA_TOO_LARGE:
                    errorMessage = "Advertiseのメッセージが大きすぎます";
                    break;
                case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    errorMessage = "Advertiseをサポートしていません";
                    break;
                case ADVERTISE_FAILED_INTERNAL_ERROR:
                    errorMessage = "内部エラーが発生しました";
                    break;
                case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    errorMessage = "利用可能なAdvertiseのインスタンスが余っていません";
                    break;
            }

            ErrorParcelable errorParcelable = new ErrorParcelable(errorCode, errorMessage);

            Intent intent = new Intent();
            intent.setAction(Constants.ACTION_SCAN_ADV_ERROR);
            intent.putExtra(Constants.ERROR_SCAN_ADV, errorParcelable);
            sendBroadcast(intent);
        }
    };

    private ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            if (result == null
                    || result.getDevice() == null
                    || TextUtils.isEmpty(result.getDevice().getName()))
                return;

            BluetoothDevice bluetoothDevice = result.getDevice();
            ScanRecord scanRecord = result.getScanRecord();
            if (scanRecord != null) {
                double distance = scanRecord.getTxPowerLevel() - result.getRssi();
                String uuid = "";
                byte[] data = null;
                String serviceData = "";

                for (ParcelUuid parcelUuid : scanRecord.getServiceUuids()) {
                    data = scanRecord.getServiceData(parcelUuid);
                    uuid = parcelUuid.getUuid().toString();
                }
                if (data != null) {
                    try {
                        serviceData = new String(data, "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                }

                ScanDataParcelable scanDataParcelable
                        = new ScanDataParcelable(callbackType, bluetoothDevice.getAddress(), bluetoothDevice.getName(), uuid, distance, serviceData);
                Intent intent = new Intent();
                intent.setAction(Constants.ACTION_SCAN);
                intent.putExtra(Constants.SCAN_DATA, scanDataParcelable);
                sendBroadcast(intent);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            String errorMessage = "";
            switch (errorCode) {
                case SCAN_FAILED_ALREADY_STARTED:
                    errorMessage = "既にBLEスキャンを実行中です";
                    break;
                case SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    errorMessage = "BLEスキャンを開始できませんでした";
                    break;
                case SCAN_FAILED_FEATURE_UNSUPPORTED:
                    errorMessage = "BLEの検索をサポートしていません。";
                    break;
                case SCAN_FAILED_INTERNAL_ERROR:
                    errorMessage = "内部エラーが発生しました";
                    break;
            }

            ErrorParcelable errorParcelable = new ErrorParcelable(errorCode, errorMessage);

            Intent intent = new Intent();
            intent.setAction(Constants.ACTION_SCAN_ADV_ERROR);
            intent.putExtra(Constants.ERROR_SCAN_ADV, errorParcelable);
            sendBroadcast(intent);
        }
    };

    private class ConnectDeviceReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!TextUtils.isEmpty(intent.getStringExtra(Constants.DEVICE_ADDRESS))) {
                String deviceAddress = intent.getStringExtra(Constants.DEVICE_ADDRESS);
                if (deviceAddress != null && !deviceAddress.equals("")) {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
                    bluetoothGatt = device.connectGatt(this, false, mGattCallback);

                }
            }
        }
    }

    private final BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
        }
    };

}
