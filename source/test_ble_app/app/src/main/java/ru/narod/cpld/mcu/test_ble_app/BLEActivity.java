/*

Copyright (c) 2007-2026 Sergey Bezzubov (bsa-one@yandex.ru)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

*/

//-------------------------------------------------------------------------//
// File:        "BLEActivity.java"                                         //
// Project:     "test_ble_app"                                             //
// Description: BLE Activity                                               //
//                                                                         //
// Author:      Sergey Bezzubov (bsa-one@yandex.ru)                        //
// 09.11.2026                                                              //
//-------------------------------------------------------------------------//

package ru.narod.cpld.mcu.test_ble_app;

import java.util.*;

import android.content.*;
import android.util.Log;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

public class BLEActivity
{
	public static final int BLE_OPEN_OK      =   0;
	public static final int BLE_SCAN         =   1;
	public static final int BLE_SCAN_OK      =   2;
	public static final int BLE_WAIT         =   3;
	public static final int BLE_BONDING      =   4;
	public static final int BLE_SERVICE      =   5;
	public static final int BLE_DISCOVERED   =   6;
	public static final int BLE_DEBUG        =   7;
	public static final int BLE_NO_SCAN      =  -1;
	public static final int BLE_NO_DEVICE    =  -2;
	
	public static final int BLE_OK           =   0;
	public static final int BLE_ERR          =  -1;
	
	private static final int CHIP_CH9141     =   0;
	private static final int CHIP_WH_BLE106  =   1;
	private static final int CHIP_UNKNOWN    =  -1;
	
	private static final int ATT_SIZE        =   3;
	
	private static final int PAYLOAD_DEFAULT =  20;
	private static final int PAYLOAD_REQUEST = 197;
	
	private static final int MAX_TRIES       =   3;
	
	private int ble_state = BLE_NO_DEVICE;
	
	private int ble_chip = CHIP_UNKNOWN;
	private int ble_payload = PAYLOAD_DEFAULT;
	private boolean ble_password_ok = false;
	
	private static final String TAG = "BLEActivity";
	
	private Context AppContext = null;
	
	private BluetoothAdapter   adapter = null;
	private BluetoothLeScanner scanner = null;
	private BluetoothDevice    device  = null;
	private BluetoothGatt      gatt    = null;
	
	private boolean discover_running   = false;
	
	private static final String CH9141_notify_uuid    = "0000fff1-";
	private static final String CH9141_write_uuid     = "0000fff2-";
	
	private static final String WH_BLE106_notify_uuid = "0003cdd1-";
	private static final String WH_BLE106_write_uuid  = "0003cdd2-";
	
	private BluetoothGattCharacteristic notify_characteristic = null;
	private BluetoothGattCharacteristic write_characteristic  = null;
	
	public Queue<BleCommand> commandQueue = null;
	public boolean commandQueueBusy = false;
	public boolean isRetrying = false;
	public int nrTries = 0;
	
	private long queuePauseMillis = 0;
	
	private SimpleRingBuffer<byte[]> rd_buf = null;
	
	private static final String DEFAULT_NAME = "CH9141";
	private static final String DEFAULT_PASSWORD = "000000";
	
	private String dev_name = DEFAULT_NAME;
	private String dev_password = DEFAULT_PASSWORD;
	
	private static class BleCommand {
        final Runnable runnable;
        final long delayMillis;

        BleCommand(Runnable runnable, long delayMillis) {
            this.runnable = runnable;
            this.delayMillis = delayMillis;
        }
    }
	
	private final BroadcastReceiver bondStateReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(Context context, Intent intent)
		{
			final String action = intent.getAction();
			final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

			// Ignore updates for other devices
			if (gatt == null || !device.getAddress().equals(gatt.getDevice().getAddress()))
				return;

			// Check if action is valid
			if(action == null) 
				return;

			// Take action depending on new bond state
			if (action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
			{
				final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
				//final int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);

				switch (bondState)
				{
					case BluetoothDevice.BOND_BONDING:
						{
							Log.d(TAG, "BroadcastReceiver: bonding started");
							SetState(BLE_BONDING);
							
							break;
						}

					case BluetoothDevice.BOND_BONDED:
						{
							Log.d(TAG, "BroadcastReceiver: bonding finished");
							SetState(BLE_WAIT);
							
							// Подключились к устройству, вызываем discoverServices с задержкой
							Handler callBackHandler = new Handler(Looper.getMainLooper());
							callBackHandler.postDelayed(new Runnable() {
									@Override
									public void run() {
										DiscoverServices();
									}
								}, 1000);
							
							break;
						}

					case BluetoothDevice.BOND_NONE:
						{
							Log.d(TAG, "BroadcastReceiver: bonding none");
							/*SetState(BLE_WAIT);

							// Подключились к устройству, вызываем discoverServices с задержкой
							Handler callBackHandler = new Handler(Looper.getMainLooper());
							callBackHandler.postDelayed(new Runnable() {
									@Override
									public void run() {
										DiscoverServices();
									}
								}, 1000);*/
							
							break;
						}
				}
			}
		}
	};
	
	private final BroadcastReceiver pairingReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(Context context, Intent intent)
		{
			final String action = intent.getAction();
			final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

			// Ignore updates for other devices
			if (gatt == null || !device.getAddress().equals(gatt.getDevice().getAddress()))
				return;

			// Check if action is valid
			if(action == null) 
				return;

			// Take action depending on pairing request
			if (action.equals(BluetoothDevice.ACTION_PAIRING_REQUEST))
			{
				// Example: Automating PIN entry (e.g., "1234")
				try {
					byte[] pin = dev_password.getBytes();
					device.setPin(pin);
					
					/*
					Handler callBackHandler = new Handler(Looper.getMainLooper());
					callBackHandler.postDelayed(new Runnable() {
							@Override
							public void run() {
								try
								{
									device.setPairingConfirmation(true);
								} catch (Exception e) {
									e.printStackTrace();
								}
							}
						}, 200);
					*/
					
					// Abort the broadcast to prevent the system pairing dialog from appearing
					//abortBroadcast();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	};
	
	public void Init(Context C, int rd_buf_size, long queue_pause_ms)
	{
		AppContext = C;
		
		IntentFilter bondStateFilter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
		//bondStateFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
		AppContext.registerReceiver(bondStateReceiver, bondStateFilter);
		
		IntentFilter pairingFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
		//pairingFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
		AppContext.registerReceiver(pairingReceiver, pairingFilter);
		
		rd_buf = new SimpleRingBuffer<>(rd_buf_size);
		
		commandQueue = new LinkedList<>();
		queuePauseMillis = queue_pause_ms;
	}
	
	public void DeInit()
	{
		if(AppContext != null)
		{
			try
			{
				AppContext.unregisterReceiver(bondStateReceiver);
				AppContext.unregisterReceiver(pairingReceiver);
			}
			catch(Exception ex)
			{
				Log.e(TAG, "Ошибка unregisterReceiver", ex);
			}
			
			AppContext = null;
		}
	}
	
	public void Exit()
	{
		if(scanner != null)
		{
			scanner.stopScan(scanCallback);
		}
		
		if (device != null && device.getBondState() != BluetoothDevice.BOND_NONE)
		{
			try {
				java.lang.reflect.Method method = device.getClass().getMethod("removeBond", (Class<?>[]) null);
				if (method != null) {
					boolean res = (Boolean) method.invoke(device, (Object[]) null);
					Log.d(TAG, "Принудительное удаление старого сопряжения (removeBond): " + res);
				}
			} catch (Exception e) {
				Log.e(TAG, "Не удалось вызвать removeBond через рефлексию", e);
			}
		}
		
		if(gatt != null)
		{
			gatt.disconnect();
		}
		
		new Timer().schedule(new TimerTask() {          
				@Override
				public void run() {
					if(gatt    != null)
					{
						gatt.close();
						gatt    = null;
						Log.d(TAG, "gatt    = null");
					}
					
					if(device  != null)
					{
						device  = null;
						Log.d(TAG, "device  = null");
					}
					
					if(scanner != null)
					{
						scanner = null;
						Log.d(TAG, "scanner = null");
					}
					
					if(adapter != null)
					{
						adapter = null;
						Log.d(TAG, "adapter = null");
					}
					
					rd_buf.flush();
					Log.d(TAG, "rd_buf.flush()");
					
					SetState(BLE_NO_DEVICE);
				}
			}, 300);
	}
	
	public void FindDevice(String name, String password)
	{
		Log.w(TAG, "FindDevice() - " + name + ", " + password);
		
		Exit();
		
		if(name == null)
		{
			dev_name = DEFAULT_NAME;
		}
		else
		{
			dev_name = name;
		}
		
		if(password == null)
		{
			dev_password = DEFAULT_PASSWORD;
		}
		else
		{
			dev_password = password;
		}
		
		new Timer().schedule(new TimerTask() {          
				@Override
				public void run() {
					RemoveBond();
					ScanStart();
				}
			}, 500);
	}
	
	private void RemoveBond()
	{
		try {
			BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
			if (btAdapter != null) {
				Set<BluetoothDevice> bondedDevices = btAdapter.getBondedDevices();
				if (bondedDevices != null) {
					for (BluetoothDevice bondedDevice : bondedDevices) {
						// Ищем наше устройство по имени (или по адресу, если бы он передавался)
						if (dev_name.equals(bondedDevice.getName())) {
							Log.w(TAG, "FindDevice: Найдено старое сопряжение. Очищаем кэш...");

							// Принудительно вызываем removeBond() ДО сканирования
							java.lang.reflect.Method method = bondedDevice.getClass().getMethod("removeBond", (Class<?>[]) null);
							if (method != null) {
								boolean res = (Boolean) method.invoke(bondedDevice, (Object[]) null);
								Log.d(TAG, "Старый кэш успешно аннулирован: " + res);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "Ошибка превентивной очистки кэша сопряжения", e);
		}
	}
	
	public boolean WriteBuffer(byte[] data)
	{
		if(data == null)
		{
			Log.e(TAG, "ERROR: Data is 'null', ignoring write request");
			return false;
		}
		
		// Локальная копия на случай изменения глобальной переменной во время выполнения
		int payloadSize = ble_payload;
		
		int bytesSent = 0;
		int totalBytes = data.length;
		
		while(bytesSent < totalBytes)
		{
			// Вычисляем размер текущего куска (остаток или полный ble_payload)
			int currentChunkSize = Math.min(payloadSize, totalBytes - bytesSent);
			byte[] chunk = new byte[currentChunkSize];
			
			// Копируем часть данных в отдельный массив
			System.arraycopy(data, bytesSent, chunk, 0, currentChunkSize);
			
			long delay_ms = (bytesSent == 0)? queuePauseMillis : 0;
			
			// Отправляем пакет в очередь
			if(!WritePacket(chunk, delay_ms))
			{
				Log.e(TAG, "ERROR: WritePacket failed at offset " + bytesSent);
				return false;
			}
			
			bytesSent += currentChunkSize;
		}

		return true;
	}
	
	public boolean WritePacket(byte[] data, long delay_ms)
	{
		if(gatt == null)
		{
			Log.e(TAG, "ERROR: Gatt is 'null', ignoring write request");
			return false;
		}

		// Check if characteristic is valid
		if(write_characteristic == null)
		{
			Log.e(TAG, "ERROR: Characteristic is 'null', ignoring write request");
			return false;
		}

		// Check if this characteristic actually has READ property
		if((write_characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 )
		{
			Log.e(TAG, "ERROR: Characteristic cannot be written");
			return false;
		}

		// Enqueue the read command now that all checks have been passed
		Log.d(TAG, "WriteBuffer(), commandQueue.add(new Runnable())");
		final byte[] data_ = data;
		
		Runnable bluetoothCommand = new Runnable() {
			@Override
			public void run() {
				write_characteristic.setValue(data_);
				write_characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

				//startTimer(1000);

				if(!gatt.writeCharacteristic(write_characteristic))
				{
					String msg = "writeCharacteristic " + write_characteristic.getUuid() + " write ERROR, nrTries = " + nrTries;
					Log.e(TAG, msg);
					//retryCommand();
					completedCommand();
				}
				else
				{
					//completedCommand();
				}
			}
		};
		
		boolean result = commandQueue.add(new BleCommand(bluetoothCommand, delay_ms));

		if(result)
		{
			nextCommand();
		}
		else
		{
			Log.e(TAG, "ERROR: Could not enqueue write characteristic command");
		}
		
		return result;
	}
	
	public byte[] ReadBuffer()
	{
		return rd_buf.get();
	}
	
	public boolean ReadBufferIsEmpty()
	{
		return rd_buf.isEmpty();
	}
	
	public int GetPayloadSize()
	{
		return ble_payload;
	}
	
	public void SetQueuePause(long pauseMillis)
	{
		if(pauseMillis < 0)
		{
			queuePauseMillis = 0;
		}
		else
		{
			queuePauseMillis = pauseMillis;
		}
		
		Log.d(TAG, "Queue pause set to: " + this.queuePauseMillis + " ms");
	}
	
	public long GetQueuePause()
	{
		return queuePauseMillis;
	}
	
	private void SetState(int state)
	{
		ble_state = state;

		if(ble_state != BLE_OPEN_OK)
		{
			notify_characteristic = null;
			write_characteristic  = null;
		}

		if(ble_state == BLE_SERVICE)
		{
			discover_running = true;
		}
		if(ble_state == BLE_DISCOVERED || ble_state == BLE_NO_DEVICE)
		{
			discover_running = false;
		}
		
		if(ble_state == BLE_NO_DEVICE)
		{
			purgeCommand();
			ble_password_ok = false;
			
			ble_chip = CHIP_UNKNOWN;
		}
		
		if(ble_state == BLE_OPEN_OK && !ble_password_ok)
		{
			if(ble_chip == CHIP_WH_BLE106) 
				WriteBuffer(dev_password.getBytes());
			
			ble_password_ok = true;
		}
	}

	public int GetState()
	{
		return ble_state;
	}
	
	private void ScanStart()
	{
		Log.d(TAG, "ScanStart()");
		
		adapter = BluetoothAdapter.getDefaultAdapter();
		
		scanner = adapter.getBluetoothLeScanner();
		
		if(scanner != null)
		{
			List<ScanFilter> filters = new ArrayList<>();
			
			ScanFilter filter = new ScanFilter.Builder()
				.setDeviceName(dev_name)
				.build();
			filters.add(filter);
			
			ScanSettings scanSettings = new ScanSettings.Builder()
				.setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
				.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
				.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
				.setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
				.setReportDelay(0L)
				.build();
			
			scanner.startScan(filters, scanSettings, scanCallback);
			
			Log.d(TAG, "Scan started");
			SetState(BLE_SCAN);
		}
		else
		{
			Log.e(TAG, "Could not get scanner object");
			SetState(BLE_NO_SCAN);
		}
	}
	
	private final ScanCallback scanCallback = new ScanCallback()
	{
		@Override
		public void onScanResult(int callbackType, ScanResult result)
		{
			// Остановка сканера ПЕРЕД коннектом - залог стабильности стека
            if(scanner != null)
			{
                scanner.stopScan(this);
            }
			
			Log.d(TAG, "Scan finished");
			SetState(BLE_SCAN_OK);
			
			device = result.getDevice();
			
			gatt = device.connectGatt(AppContext, false, 
									  bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE);
		}

		@Override
		public void onBatchScanResults(List<ScanResult> results)
		{
		}

		@Override
		public void onScanFailed(int errorCode)
		{
			Log.e(TAG, "Scan failed");
			SetState(BLE_NO_DEVICE);
		}
	};
	
	private final BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback()
	{
		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
		{
			if(status == BluetoothGatt.GATT_SUCCESS)
			{
				if(newState == BluetoothProfile.STATE_CONNECTED)
				{
					int bondState = device.getBondState();
					// Обрабатываем bondState
					switch (bondState)
					{
						case BluetoothDevice.BOND_BONDING:
							{
								Log.d(TAG, "onConnectionStateChange: waiting for bonding to complete");
								SetState(BLE_BONDING);

								break;
							}

						case BluetoothDevice.BOND_BONDED:
							{
								Log.d(TAG, "onConnectionStateChange: bonding finished");
								SetState(BLE_WAIT);

								// Подключились к устройству, вызываем discoverServices с задержкой
								Handler callBackHandler = new Handler(Looper.getMainLooper());
								callBackHandler.postDelayed(new Runnable() {
										@Override
										public void run() {
											DiscoverServices();
										}
									}, 1000);

								break;
							}

						case BluetoothDevice.BOND_NONE:
							{
								Log.d(TAG, "onConnectionStateChange: bonding none");
								SetState(BLE_WAIT);

								// Подключились к устройству, вызываем discoverServices с задержкой
								Handler callBackHandler = new Handler(Looper.getMainLooper());
								callBackHandler.postDelayed(new Runnable() {
										@Override
										public void run() {
											DiscoverServices();
										}
									}, 1000);

								break;
							}
					}
				}
				else if(newState == BluetoothProfile.STATE_DISCONNECTED)
				{
					Log.d(TAG, "Disconnected");
					SetState(BLE_NO_DEVICE);
					
					// Мы успешно отключились (контролируемое отключение)
					gatt.close();
				}
				else
				{
					// мы или подключаемся или отключаемся, просто игнорируем эти статусы
				}
			}
			else
			{
				Log.e(TAG, "Disconnected on error");
				switch(status)
				{
					case   8: Log.e(TAG, "GATT_CONN_TIMEOUT"); break;
					case  19: Log.e(TAG, "GATT_CONN_TERMINATE_PEER_USER"); break;
					case 133: Log.e(TAG, "GATT_ERROR"); break;
				}
				SetState(BLE_NO_DEVICE);
				
				// Произошла ошибка... разбираемся, что случилось!
				//...
				gatt.close();
			}
		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status)
		{
			if (status == BluetoothGatt.GATT_SUCCESS)
			{
				Log.d(TAG, "Services discovered");
				SetState(BLE_DISCOVERED);
				
				List<BluetoothGattService> services = gatt.getServices();
				for (BluetoothGattService service : services)
				{
					List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
					for (BluetoothGattCharacteristic characteristic : characteristics)
					{ 
						// Поиск сервиса с UUID для записи и чтения
						String uuid = characteristic.getUuid().toString();
						
						if(uuid.contains(CH9141_notify_uuid   ) || 
						   uuid.contains(WH_BLE106_notify_uuid) )
						{
							Log.d(TAG, "notify_uuid  = " + uuid);
						
							notify_characteristic = characteristic;
						}
						
						if(uuid.contains(CH9141_write_uuid   ) || 
						   uuid.contains(WH_BLE106_write_uuid) )
						{
							Log.d(TAG, "write_uuid   = " + uuid);
							
							write_characteristic = characteristic;
						}
						
						if(uuid.contains(CH9141_notify_uuid))
						{
							ble_chip = CHIP_CH9141;
						}
						else if(uuid.contains(WH_BLE106_notify_uuid))
						{
							ble_chip = CHIP_WH_BLE106;
						}
                    }
				}
				
				if(notify_characteristic == null || write_characteristic == null)
				{
					Log.e(TAG, "Characteristics not found");
					SetState(BLE_NO_DEVICE);
					
					gatt.disconnect();
					return;
				}
				else
				{
					Log.d(TAG, "Characteristics sucessfully founded");
				}
				
				if(!requestPayload(PAYLOAD_REQUEST))
				{
					Log.e(TAG, "requestPayload failed");
					SetState(BLE_NO_DEVICE);

					gatt.disconnect();
					return;
				}
				
				if(!setNotify(notify_characteristic, true))
				{
					Log.e(TAG, "setNotify failed");
					SetState(BLE_NO_DEVICE);

					gatt.disconnect();
					return;
				}
			}
			else
			{
				Log.e(TAG, "Service discovery failed");
				SetState(BLE_NO_DEVICE);
				
				gatt.disconnect();
			}
		}
		
		@Override
		public void onMtuChanged(BluetoothGatt gatt, int mtu, int status)
		{
			if(status == BluetoothGatt.GATT_SUCCESS)
			{
				ble_payload = mtu - ATT_SIZE;
				
				Log.d(TAG, "onMtuChanged() successful, ble_payload = " + mtu);
				completedCommand();
			}
			else
			{
				ble_payload = PAYLOAD_DEFAULT;
				
				Log.e(TAG, "onMtuChanged() failed, status = " + status);
				retryCommand();
			}
		}
		
		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
		{
			// Handle the received notification data here
			byte[] data = characteristic.getValue();
			// Process 'data' as needed
			
			rd_buf.put(data);
			
			String str = "";
			try
			{
				str = new String(data, "UTF-8");
			}
			catch(Exception ex)
			{
			}
			
			Log.d(TAG, "Characteristic " + characteristic.getUuid() + " notify, " + str);
		}

		@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status)
		{
			if(status == BluetoothGatt.GATT_SUCCESS)
			{
				// Notification subscription successful
				// You can initiate further actions here if needed
				Log.d(TAG, "onDescriptorWrite() successful");
				completedCommand();
				
				if(descriptor != null && CCC_DESCRIPTOR_UUID.equalsIgnoreCase(descriptor.getUuid().toString()))
				{
					Log.d(TAG, "Device sucessfully connected");
					SetState(BLE_OPEN_OK);
				}
			}
			else
			{
				// Handle descriptor write failure
				Log.e(TAG, "onDescriptorWrite() failed");
				retryCommand();
			}
		}
		
		@Override
		public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
		{
			if(status == BluetoothGatt.GATT_SUCCESS)
			{
				byte[] data = characteristic.getValue();
				
				String str = "";
				try
				{
					str = new String(data, "UTF-8");
				}
				catch(Exception ex)
				{
				}
				
				Log.d(TAG, "Characteristic " + characteristic.getUuid() + " read OK, " + str);
				completedCommand();
			}
			else
			{
				Log.e(TAG, "Characteristic " + characteristic.getUuid() + " read ERROR, nrTries = " + nrTries);
				retryCommand();
			}
		}

		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
		{
			//cancelTimer();
			
			if(status == BluetoothGatt.GATT_SUCCESS)
			{
				byte[] data = characteristic.getValue();
				
				String str = "";
				try
				{
					str = new String(data, "UTF-8");
				}
				catch(Exception ex)
				{
				}
				
				Log.d(TAG, "onCharacteristicWrite " + characteristic.getUuid() + " write OK, " + str);
				completedCommand();
			}
			else
			{
				Log.e(TAG, "onCharacteristicWrite " + characteristic.getUuid() + " write ERROR, nrTries = " + nrTries);
				retryCommand();
			}
		}
	};
	
	private void nextCommand()
	{
		Log.d(TAG, "nextCommand(), commandQueueBusy = " + commandQueueBusy);
		
		// If there is still a command being executed then bail out
		if(commandQueueBusy)
		{
			return;
		}
		
		// Check if we still have a valid gatt object
		if(gatt == null)
		{
			Log.e(TAG, String.format("ERROR: GATT is 'null' for peripheral '%s', clearing command queue", device.getAddress()));
			commandQueue.clear();
			commandQueueBusy = false;
			return;
		}
		
		// Execute the next command in the queue
		if(commandQueue.size() > 0)
		{
			final BleCommand bluetoothCommand = commandQueue.peek();
			commandQueueBusy = true;
			if (!isRetrying) {
				nrTries = 0;
			}
			
			long delay_ms = isRetrying? 0 : bluetoothCommand.delayMillis;
			
			Handler bleHandler = new Handler(Looper.getMainLooper());
			bleHandler.postDelayed(new Runnable() {
					@Override
					public void run() {
						try
						{
							Log.d(TAG, "bluetoothCommand.run()");
							bluetoothCommand.runnable.run();
						}
						catch(Exception ex)
						{
							Log.e(TAG, String.format("ERROR: Command exception for device '%s'", device.getName()), ex);
						}
					}
				}, delay_ms);
		}
	}
	
	private void completedCommand()
	{
		Log.d(TAG, "completedCommand()");
		
		isRetrying = false;
		commandQueue.poll();
		commandQueueBusy = false;
		nextCommand();
	}
	
	private void retryCommand()
	{
		Log.d(TAG, "retryCommand()");
		
		commandQueueBusy = false;
		BleCommand currentCommand = commandQueue.peek();
		if(currentCommand != null)
		{
			nrTries++;
			if(nrTries >= MAX_TRIES)
			{
				// Max retries reached, give up on this one and proceed
				String msg = "Max number of tries reached";
				Log.v(TAG, msg);
				
				completedCommand();
			}
			else
			{
				String msg = "retryCommand(), nrTries = " + nrTries;
				Log.v(TAG, msg);
				
				isRetrying = true;
			}
		}
		
		nextCommand();
	}
	
	private void purgeCommand()
	{
		if(!commandQueue.isEmpty())
		{
			commandQueue.clear();
		}
		
		commandQueueBusy = false;
		isRetrying = false;
		nrTries = 0;
	}
	
	public boolean requestPayload(int payload)
	{
		if(gatt == null)
		{
			Log.e(TAG, "ERROR: Gatt is 'null', ignoring requestMtu");
			return false;
		}
		
		if(android.os.Build.VERSION.SDK_INT == 24 || android.os.Build.VERSION.SDK_INT == 25)
		{
			return true;
		}
		
		final int mtuSize = payload + ATT_SIZE;

		Log.d(TAG, "requestPayload(), commandQueue.add(new Runnable())");
		
		Runnable bluetoothCommand = new Runnable() {
			@Override
			public void run() {
				if(!gatt.requestMtu(mtuSize))
				{
					String msg = String.format("ERROR: requestMtu failed");
					Log.e(TAG, msg);
					//retryCommand();
					completedCommand();
				}
				else
				{
					//completedCommand();
				}
			}
		};

		boolean result = commandQueue.add(new BleCommand(bluetoothCommand, 0));

		if(result)
		{
        	nextCommand();
    	}
		else
		{
        	Log.e(TAG, "ERROR: Could not enqueue requestPayload command");
    	}
		
		return result;
	}
	
	private final String CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb";
	public boolean setNotify(BluetoothGattCharacteristic characteristic, final boolean enable)
	{
		// Check if characteristic is valid
		if(characteristic == null)
		{
			Log.e(TAG, "ERROR: Characteristic is 'null', ignoring setNotify request");
			return false;
		}
		
		// Get the CCC Descriptor for the characteristic
		final BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(CCC_DESCRIPTOR_UUID));
		if(descriptor == null)
		{
			Log.e(TAG, String.format("ERROR: Could not get CCC descriptor for characteristic %s", characteristic.getUuid()));
			return false;
		}
		
		// Check if characteristic has NOTIFY or INDICATE properties and set the correct byte value to be written
		byte[] value;
		int properties = characteristic.getProperties();
		if((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0)
		{
			value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
		}
		else if((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0)
		{
			value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
		}
		else
		{
			Log.e(TAG, String.format("ERROR: Characteristic %s does not have notify or indicate property", characteristic.getUuid()));
			return false;
		}
		final byte[] finalValue = enable? value : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
		
		// Queue Runnable to turn on/off the notification now that all checks have been passed
		Log.d(TAG, "setNotify(), commandQueue.add(new Runnable())");
		
		Runnable bluetoothCommand = new Runnable() {
			@Override
			public void run() {
				// First set notification for Gatt object
				if(!gatt.setCharacteristicNotification(descriptor.getCharacteristic(), enable))
				{
					Log.e(TAG, String.format("ERROR: setCharacteristicNotification failed for descriptor: %s", descriptor.getUuid()));
				}

				// Then write to descriptor
				descriptor.setValue(finalValue);
				boolean result = gatt.writeDescriptor(descriptor);
				if(!result)
				{
					String msg = String.format("ERROR: writeDescriptor failed for descriptor: %s", descriptor.getUuid());
					Log.e(TAG, msg);
					//retryCommand();
					completedCommand();
				}
				else
				{
					//completedCommand();
				}
        	}
		};

		boolean result = commandQueue.add(new BleCommand(bluetoothCommand, 0));

    	if(result)
		{
        	nextCommand();
    	}
		else
		{
        	Log.e(TAG, "ERROR: Could not enqueue setNotify command");
    	}

    	return result;
	}
	
	private void DiscoverServices()
	{
		if(!discover_running && ble_state != BLE_BONDING)
		{
			if(gatt.discoverServices())
			{
				Log.d(TAG, "Discover services started");
				SetState(BLE_SERVICE);
			}
			else
			{
				Log.d(TAG, "Discover services failed");
				SetState(BLE_NO_DEVICE);
			}
		}
	}
}
