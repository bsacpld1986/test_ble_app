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
// Description: test_ble_app MainActivity                                  //
//                                                                         //
// Author:      Sergey Bezzubov (bsa-one@yandex.ru)                        //
// 09.11.2026                                                              //
//-------------------------------------------------------------------------//

package ru.narod.cpld.mcu.test_ble_app;

import java.util.*;
import java.io.*;
import java.nio.*;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.content.*;
import android.util.*;
import android.text.format.*;
import android.media.*;

import android.Manifest;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.annotation.NonNull;

public class MainActivity extends Activity
{
	private String BLE_NAME     = "CH9141BLE2U";
	private String BLE_PASSWORD = "123456";
	
	private static final int RD_BUF_SIZE = 256;
	private static final int CMD_DELAY_MS = 10;
	
	private static final int READ_DATA_MAX_LINES = 15;
	
	private static final int UI_UPDATE_PERIOD_MS = 100;
	
	private EditText FindDeviceName;
	private EditText FindDevicePassword;
	private TextView FindDeviceStatus;
	private Button FindDeviceButton;
	
	private EditText WriteData;
	private TextView WriteStatus;
	private Button WriteButton;
	
	private TextView ReadData;
	
	private TextView DebugText;
	
	private Context AppContext;
	private BLEActivity ble = null;
	
	private static final int REQUEST_PERMISSION = 1;
	private static final String[] PERMISSION_LIST_12_16 = {
		"android.permission.BLUETOOTH_SCAN", 
		"android.permission.BLUETOOTH_CONNECT"
	};
	private static final String[] PERMISSION_LIST_5_11 = {
		"android.permission.BLUETOOTH", 
		"android.permission.BLUETOOTH_ADMIN", 
		"android.permission.ACCESS_FINE_LOCATION"
	};
	
	@Override
    public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		
		FindDeviceName = (EditText)findViewById(R.id.FindDeviceName);
		FindDeviceName.setText(BLE_NAME);
		FindDevicePassword = (EditText)findViewById(R.id.FindDevicePassword);
		FindDevicePassword.setText(BLE_PASSWORD);
		FindDeviceStatus = (TextView)findViewById(R.id.FindDeviceStatus);
		FindDeviceButton = (Button)findViewById(R.id.FindDeviceButton);
		
		WriteData = (EditText)findViewById(R.id.WriteData);
		WriteStatus = (TextView)findViewById(R.id.WriteStatus);
		WriteButton = (Button)findViewById(R.id.WriteButton);
		
		ReadData = (TextView)findViewById(R.id.ReadData);
		
		DebugText = (TextView)findViewById(R.id.DebugText);
		
		AppContext = getApplicationContext();
		
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
		{
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || 
				ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE ) != PackageManager.PERMISSION_GRANTED || 
				ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN        ) != PackageManager.PERMISSION_GRANTED || 
				ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT     ) != PackageManager.PERMISSION_GRANTED)
			{
				ActivityCompat.requestPermissions(this, PERMISSION_LIST_12_16, REQUEST_PERMISSION);
			}
		}
		else
		{
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || 
				ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE ) != PackageManager.PERMISSION_GRANTED || 
				ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH             ) != PackageManager.PERMISSION_GRANTED || 
				ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN       ) != PackageManager.PERMISSION_GRANTED || 
				ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION  ) != PackageManager.PERMISSION_GRANTED)
			{
				ActivityCompat.requestPermissions(this, PERMISSION_LIST_5_11, REQUEST_PERMISSION);
			}
		}

		ble = new BLEActivity();
		ble.Init(AppContext, RD_BUF_SIZE, CMD_DELAY_MS);

		Timer timer = new Timer();
		TimerTask task = new BLETimer();

		timer.schedule(task, UI_UPDATE_PERIOD_MS, UI_UPDATE_PERIOD_MS);
    }

	@Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
	{
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSION)
		{
			boolean bluetoothPermissionGranted = true;

			for (int i=0 ; i<permissions.length ; i++)
			{
				String permission = permissions[i];
				int result = grantResults[i];

				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
				{
					if (Manifest.permission.BLUETOOTH_SCAN.equals(permission)    || 
						Manifest.permission.BLUETOOTH_CONNECT.equals(permission) )
					{
						if (result != PackageManager.PERMISSION_GRANTED)
						{
							bluetoothPermissionGranted = false;
						}
					}
				}
				else
				{
					if (Manifest.permission.BLUETOOTH.equals(permission)            || 
						Manifest.permission.BLUETOOTH_ADMIN.equals(permission)      || 
						Manifest.permission.ACCESS_FINE_LOCATION.equals(permission) )
					{
						if (result != PackageManager.PERMISSION_GRANTED)
						{
							bluetoothPermissionGranted = false;
						}
					}
				}
			}

			if (!bluetoothPermissionGranted)
			{
				Toast.makeText(this, "Нет разрешения для работы с Bluetooth!", Toast.LENGTH_SHORT).show();
			}
        }
    }
	
	public void FindDevice()
	{
		String name = FindDeviceName.getText().toString();
		String password = FindDevicePassword.getText().toString();
		
		ble.FindDevice(name, password);
	}
	
	public void WriteCmd(String cmd)
	{
		WriteStatus.setText("");

		if(ble.WriteBuffer(cmd.getBytes()))
		{
			WriteStatus.setText("write OK");
			WriteStatus.setTextColor(0xFF0FFF00);
		}
		else
		{
			WriteStatus.setText("write ERROR");
			WriteStatus.setTextColor(0xFFFF0F00);
		}
	}
	
	public void FindDeviceButton(View v)
	{
		FindDevice();
	}
	
	public void WriteButton(View v)
	{
		String cmd = WriteData.getText().toString() + "\n";
		WriteCmd(cmd);
	}
	
	class BLETimer extends TimerTask
	{
		@Override
		public void run()
		{
			runOnUiThread(new Runnable() {
				@Override
				public void run()
				{
					UpdateUI();
				}
			});
		}
	}
	
	public void UpdateUI()
	{
		switch(ble.GetState())
		{
			case ble.BLE_OPEN_OK:
				{
					FindDeviceStatus.setText("BLE: устройство подключено.");
					FindDeviceStatus.setTextColor(0xFF0FFF00);
					break;
				}

			case ble.BLE_SCAN:
				{
					FindDeviceStatus.setText("BLE: идёт поиск устройства.");
					FindDeviceStatus.setTextColor(0xFFF0FF00);
					break;
				}
				
			case ble.BLE_SCAN_OK:
				{
					FindDeviceStatus.setText("BLE: поиск устройства завершён.");
					FindDeviceStatus.setTextColor(0xFFF0FF00);
					break;
				}
				
			case ble.BLE_WAIT:
				{
					FindDeviceStatus.setText("BLE: ожидание сканирования сервисов.");
					FindDeviceStatus.setTextColor(0xFFF0FF00);
					break;
				}
				
			case ble.BLE_BONDING:
				{
					FindDeviceStatus.setText("BLE: идёт сопряжение устройства.");
					FindDeviceStatus.setTextColor(0xFFF0FF00);
					break;
				}
				
			case ble.BLE_SERVICE:
				{
					FindDeviceStatus.setText("BLE: идёт сканирование сервисов.");
					FindDeviceStatus.setTextColor(0xFFF0FF00);
					break;
				}
				
			case ble.BLE_DISCOVERED:
				{
					FindDeviceStatus.setText("BLE: сканирование сервисов завершёно.");
					FindDeviceStatus.setTextColor(0xFFF0FF00);
					break;
				}
				
			case ble.BLE_DEBUG:
				{
					FindDeviceStatus.setText("BLE: debug");
					FindDeviceStatus.setTextColor(0xFFF0FF00);
					break;
				}
				
			case ble.BLE_NO_SCAN:
				{
					FindDeviceStatus.setText("BLE: ошибка при поиске устройства!");
					FindDeviceStatus.setTextColor(0xFF0F0FFF);
					break;
				}

			case ble.BLE_NO_DEVICE:
				{
					FindDeviceStatus.setText("BLE: устройство не подключено!");
					FindDeviceStatus.setTextColor(0xFFFF0F00);
					break;
				}
				
			default:
				{
					FindDeviceStatus.setText("BLE: неизвестная ошибка!");
					FindDeviceStatus.setTextColor(0xFFFF0F00);
					break;
				}
		}
		
		if(!ble.ReadBufferIsEmpty())
		{
			byte[] data = ble.ReadBuffer();
			
			String str = "";
			try
			{
				str = new String(data, "UTF-8");
			}
			catch(Exception ex)
			{
			}
			
			ReadData.append(str);
			
			String currentText = ReadData.getText().toString();
			int totalLines = 1;
			int idx = 0;
			while((idx = currentText.indexOf("\n", idx)) != -1)
			{
				totalLines++;
				idx++;
			}
			if(totalLines > READ_DATA_MAX_LINES)
			{
				int linesToRemove = totalLines - READ_DATA_MAX_LINES;
				int cutoffIndex = 0;
				
				for(int i=0 ; i<linesToRemove ; i++)
				{
					cutoffIndex = currentText.indexOf("\n", cutoffIndex) + 1;
				}
				
				ReadData.setText(currentText.substring(cutoffIndex));
			}
		}
		
		DebugText.setText("nrTries = " + ble.nrTries + 
						  "\nisRetrying = " + ble.isRetrying + 
						  "\ncommandQueueBusy = " + ble.commandQueueBusy + 
						  "\ncommandQueue.size() = " + ble.commandQueue.size() + 
						  "\nble_payload = " + ble.GetPayloadSize());
	}
	
	@Override
	public void onBackPressed()
	{
		ble.Exit();
		
		new Timer().schedule(new TimerTask() {          
				@Override
				public void run() {
					moveTaskToBack(true);
					finish();
					System.runFinalizersOnExit(true);
					System.exit(0);
				}
			}, 500);
	}
}
