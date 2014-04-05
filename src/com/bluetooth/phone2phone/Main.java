package com.bluetooth.phone2phone;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Set;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class Main extends Activity{
	public static final String DEVICE_ADDRESS = "device_address";
	public static final String DEVICE_NAME = "device_name";
	public static final String TOAST = "toast";
	public static final String PREFS_LAST_DEVICE = "LastDevice";

	//Max File size = 1mb
	public static final int BUFFER_SIZE = 1024^2;
	
	private BluetoothAdapter mBtAdapter;
	private ArrayAdapter<String> mPairedDevicesArrayAdapter;
	private ArrayAdapter<String> mNewDevicesArrayAdapter;

	private ConnectionService mConnectionService = null;
	private static final int REQUEST_ENABLE_BT = 100;
	private static final int PICKFILE_RESULT_CODE = 200;

	//Message types sent from the BluetoothChatService Handler
	public static final int MESSAGE_STATE_CHANGE = 1;
	public static final int MESSAGE_READ_FILE = 2;
	public static final int MESSAGE_READ_FILENAME = 3;
	public static final int MESSAGE_WRITE_FILE = 4;
	public static final int MESSAGE_WRITE_FILENAME = 5;
	public static final int MESSAGE_DEVICE_NAME = 6;
	public static final int MESSAGE_TOAST = 7;
	public static final int MESSAGE_SAVE_DEVICE = 8;
	public static final int MESSAGE_RESET_DEVICE = 9;

	private SharedPreferences prefs;
	private Editor prefsEditor;

	private String mConnectedDeviceName = null;
	public static String linkFilePath;
	public static String linkFileName;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = PreferenceManager.getDefaultSharedPreferences(this);
		prefsEditor = prefs.edit();

		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
		setContentView(R.layout.main);

		//Bluetooth adapter
		mBtAdapter = BluetoothAdapter.getDefaultAdapter();

		if(mBtAdapter==null){
			Toast.makeText(this, "Bluetooth Not Supported", Toast.LENGTH_SHORT).show();
			finish();
			return;
		}		
	}

	@Override
	protected void onStart(){
		super.onStart();

		if (!mBtAdapter.isEnabled()) {
			Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
		} 
		else {
			if (mConnectionService == null){ 
				setupLayout();
			}
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		if (mBtAdapter != null) {
			mBtAdapter.cancelDiscovery();
		}

		if(mConnectionService!=null){
			mConnectionService.stop();
		}

		unregisterReceiver(mReceiver);
	}

	@Override
	protected void onResume() {
		super.onResume();

		if (mConnectionService != null) {
			//Only if the state is STATE_NONE, do we know that we haven't started already
			if (mConnectionService.getState() == ConnectionService.STATE_NONE) {
				// Start the Bluetooth chat services
				mConnectionService.start();

				String address = prefs.getString(PREFS_LAST_DEVICE, null);
				if (mConnectionService.getState() != ConnectionService.STATE_CONNECTED && address!=null) {

					if (mBtAdapter.isDiscovering()) {
						mBtAdapter.cancelDiscovery();
					}

					//Get the BluetoothDevice object
					BluetoothDevice device = mBtAdapter.getRemoteDevice(address);
					mConnectionService.connect(device);
				}
			}
		}

	}

	private void setupLayout() {
		Button scanButton = (Button) findViewById(R.id.button_scan);
		scanButton.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				//mPairedDevicesArrayAdapter.clear();
				mNewDevicesArrayAdapter.clear();
				scan();
			}
		});

		mPairedDevicesArrayAdapter = new ArrayAdapter<String>(this, R.layout.device);
		ListView pairedListView = (ListView) findViewById(R.id.paired_devices);
		pairedListView.setAdapter(mPairedDevicesArrayAdapter);
		pairedListView.setOnItemClickListener(mDeviceClickListener);

		mNewDevicesArrayAdapter = new ArrayAdapter<String>(this, R.layout.device);
		ListView newDevicesListView = (ListView) findViewById(R.id.new_devices);
		newDevicesListView.setAdapter(mNewDevicesArrayAdapter);
		newDevicesListView.setOnItemClickListener(mDeviceClickListener);

		//For when a device is discovered
		IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
		this.registerReceiver(mReceiver, filter);

		//For when discovery has finished
		filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
		this.registerReceiver(mReceiver, filter);

		//Your device info
		TextView info = (TextView)findViewById(R.id.text_info); 
		info.setText("My Device: " +mBtAdapter.getName() +":"+ mBtAdapter.getAddress());

		updatePairedDevices();
		
		//Initialize the BluetoothChatService to perform bluetooth connections
		mConnectionService = new ConnectionService(this,mHandler);
	}

	
	public void updatePairedDevices(){
		mPairedDevicesArrayAdapter.clear();
		//Get currently paired devices
		Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();
		if (pairedDevices.size() > 0) {
			findViewById(R.id.title_paired_devices).setVisibility(View.VISIBLE);
			for (BluetoothDevice device : pairedDevices) {
				mPairedDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
			}
		} else {
			mPairedDevicesArrayAdapter.add("None Paired");
		}		
	}
	
	//Set up device so it can be discovered by other phones
	public void makeDiscoverable(View v){
		Intent discoverableIntent = new	Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
		discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
		startActivity(discoverableIntent);		
	}
	
	//Scan for devices
	private void scan() {
		setProgressBarIndeterminateVisibility(true);
		setTitle("Scanning...");

		findViewById(R.id.title_new_devices).setVisibility(View.VISIBLE);

		//Cancel if we're already discovering
		if (mBtAdapter.isDiscovering()) {
			mBtAdapter.cancelDiscovery();
		}

		mBtAdapter.startDiscovery();
	}
	
	// The Handler that gets information back from the BluetoothChatService
	private final Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MESSAGE_STATE_CHANGE:
				switch (msg.arg1) {
				case ConnectionService.STATE_CONNECTED:
					setTitle("Connected to " + mConnectedDeviceName);
					break;
				case ConnectionService.STATE_CONNECTING:
					setTitle("Connecting...");
					break;
				case ConnectionService.STATE_LISTEN:
				case ConnectionService.STATE_NONE:
					setTitle("Not Connected");
					break;
				}
				break;

			case MESSAGE_WRITE_FILENAME:
				Toast.makeText(getApplicationContext(), "Sent filename "+ linkFileName, Toast.LENGTH_LONG).show();
				break;

			case MESSAGE_WRITE_FILE:
				Toast.makeText(getApplicationContext(), "Sent file "+ linkFilePath, Toast.LENGTH_LONG).show();
				break;

			case MESSAGE_READ_FILE:
				byte[] bufferFile = (byte[]) msg.obj;				
				createFile(bufferFile);				
				break;

			case MESSAGE_READ_FILENAME:
				byte[] bufferFileName = (byte[]) msg.obj;

				try {
					String tmp = new String(bufferFileName,"UTF-8").trim();
					linkFileName = tmp.trim();
					Toast.makeText(getApplicationContext(), "linkFileName updated to "+linkFileName,Toast.LENGTH_SHORT).show();
				} 
				catch (UnsupportedEncodingException e) {
					Log.e("Main-mHandler","Cannot convert bytes to string! \n e="+e);
					Toast.makeText(getApplicationContext(), "ERROR Updating linkFileName",Toast.LENGTH_LONG).show();
					e.printStackTrace();
				}
				break;

			case MESSAGE_DEVICE_NAME:
				mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
				Toast.makeText(getApplicationContext(), "Connected to " + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
				updatePairedDevices();
				break;

			case MESSAGE_TOAST:
				Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST),Toast.LENGTH_SHORT).show();
				break;

			case MESSAGE_SAVE_DEVICE:
				prefsEditor.putString(PREFS_LAST_DEVICE, msg.obj.toString());
				prefsEditor.commit();
				break;

			case MESSAGE_RESET_DEVICE:
				prefsEditor.putString(PREFS_LAST_DEVICE, null);
				prefsEditor.commit();
				break;
			}
		}
	};

	//Set up device so it can be discovered by other phones
	public void send(View v){
		if(mConnectionService.getState()==ConnectionService.STATE_CONNECTED){
			pickFile();			
		}
		else{
			Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
		}
	}

	public void pickFile(){
		Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
		intent.setType("*/*");
		startActivityForResult(intent,PICKFILE_RESULT_CODE);
	}
	
	//Method finds path name, both from gallery or file manager
	public String getPath(Uri uri) {
		String[] projection = { MediaStore.Images.Media.DATA };
		Cursor cursor = managedQuery(uri, projection, null, null, null);

		if(cursor != null){
			int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
			cursor.moveToFirst();
			linkFilePath = cursor.getString(column_index);
		}
		else{
			linkFilePath = uri.getPath();
		}

		return linkFilePath;
	}

	public static byte[] convertFileToByteArray(File f){
		byte[] byteArray = null;

		try {
			InputStream inputStream = new FileInputStream(f);
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] b = new byte[BUFFER_SIZE];
			int bytesRead =0;

			while ((bytesRead = inputStream.read(b)) != -1)
			{
				bos.write(b, 0, bytesRead);
			}

			byteArray = bos.toByteArray();
			//inputStream.close();
			Log.e("Main-convertFileToByteArray","Converted file!");
		}
		catch (IOException e){
			Log.e("Main-convertFileToByteArray","Error converting file\n e="+e);
			e.printStackTrace();
		}

		return byteArray;
	}

	//ListView click listener
	private OnItemClickListener mDeviceClickListener = new OnItemClickListener() {
		public void onItemClick(AdapterView<?> av, View v, int arg2, long arg3) {
			//Stop scanning
			mBtAdapter.cancelDiscovery();

			//Get the device MAC address, which is the last 17 chars in the View
			String info = ((TextView) v).getText().toString();
			String address = info.substring(info.length() - 17);

			Toast.makeText(Main.this, "Info="+info+" Address="+address, Toast.LENGTH_SHORT).show();

			BluetoothDevice device = mBtAdapter.getRemoteDevice(address);

			//Attempt to connect to the device
			if(mConnectionService.getState() != ConnectionService.STATE_CONNECTED || mConnectionService.getState() != ConnectionService.STATE_CONNECTING){
				mConnectionService.connect(device);
			}
			else{
				//Restart
				mConnectionService.stop();
				mConnectionService.start();
			}
		}
	};

	private void createFile(byte[] buffer){
		try {
			File sdCard = Environment.getExternalStorageDirectory();
			File dir = new File (sdCard.getAbsolutePath() + "/Downloads");
			dir.mkdirs();

			//convert array of bytes into file
			FileOutputStream fileOuputStream = new FileOutputStream(dir.getAbsolutePath()+"/" + linkFileName);
			fileOuputStream.write(buffer);
			fileOuputStream.close();

			Message msg = mHandler.obtainMessage(Main.MESSAGE_TOAST);
			Bundle bundle = new Bundle();
			bundle.putString(Main.TOAST, "Received " + linkFileName);
			msg.setData(bundle);
			mHandler.sendMessage(msg);
		}
		catch(Exception e){
			Log.e("ConnectionService-createFile", "Error creating file\n e="+e);
			e.printStackTrace();

			Message msg = mHandler.obtainMessage(Main.MESSAGE_TOAST);
			Bundle bundle = new Bundle();
			bundle.putString(Main.TOAST, "Did not receive File!");
			msg.setData(bundle);
			mHandler.sendMessage(msg);			
		}
	}

	//Listens for discovered devices & changes the title when discovery is finished
	private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			//When discovery finds a device
			if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				//Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

				//If it's already paired, skip it, because it's been listed already
				if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
					mNewDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
				}
			} 

			else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				setProgressBarIndeterminateVisibility(false);
				setTitle("Select a Device");

				if (mNewDevicesArrayAdapter.getCount() == 0) {
					mNewDevicesArrayAdapter.add("No Device Found");
				}
			}
		}
	};

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case REQUEST_ENABLE_BT:
			if (resultCode == Activity.RESULT_OK) {
				setupLayout();
			} 
			else {
				Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_SHORT).show();
				finish();
			}
			break;

		case PICKFILE_RESULT_CODE:
			if(resultCode==RESULT_OK){
				linkFilePath = null;
				linkFileName = null;
				linkFilePath = getPath(data.getData());
				File file = new File (linkFilePath);
				linkFileName = file.getName().trim();
				
				if(linkFilePath!=null && linkFilePath.length()>0){
					mConnectionService.write(linkFileName.getBytes());
					mConnectionService.write(convertFileToByteArray(file));
				}

			}
			break;
		}
	}

}// end Main