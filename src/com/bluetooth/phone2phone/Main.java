package com.bluetooth.phone2phone;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
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
	public static String EXTRA_DEVICE_ADDRESS = "device_address";
	private static final String NAME = "Phone2Phone";
	private static final UUID MY_UUID = UUID.fromString("dfcf8571-7c02-4ac6-8ddd-b31731c5fa18");

	private BluetoothAdapter mBtAdapter;
	private ArrayAdapter<String> mPairedDevicesArrayAdapter;
	private ArrayAdapter<String> mNewDevicesArrayAdapter;

	private AcceptThread mAcceptThread;
	private ConnectThread mConnectThread;
	private ConnectedThread mConnectedThread;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
		setContentView(R.layout.main);

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

		//Bluetooth adapter
		mBtAdapter = BluetoothAdapter.getDefaultAdapter();

		if(mBtAdapter==null){
			Toast.makeText(this, "Device Not Supported", Toast.LENGTH_SHORT).show();
			finish();
			return;
		}

		//Your device info
		TextView info = (TextView)findViewById(R.id.text_info); 
		info.setText("My Device: " +mBtAdapter.getName() +":"+ mBtAdapter.getAddress());

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

	@Override
	protected void onDestroy() {
		super.onDestroy();

		if (mBtAdapter != null) {
			mBtAdapter.cancelDiscovery();
		}
		unregisterReceiver(mReceiver);
		stopThreads();
	}

	@Override
	protected void onResume() {
		super.onResume();
		startThreads();
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

	//Set up device so it can be discovered by other phones
	public void makeDiscoverable(View v){
		Intent discoverableIntent = new	Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
		discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
		startActivity(discoverableIntent);		
	}

	//ListView click listener
	private OnItemClickListener mDeviceClickListener = new OnItemClickListener() {
		public void onItemClick(AdapterView<?> av, View v, int arg2, long arg3) {
			//Stop scanning
			mBtAdapter.cancelDiscovery();

			// Get the device MAC address, which is the last 17 chars in the View
			String info = ((TextView) v).getText().toString();
			String address = info.substring(info.length() - 17);

			// Create the result Intent and include the MAC address
			Intent intent = new Intent();
			intent.putExtra(EXTRA_DEVICE_ADDRESS, address);

			Toast.makeText(Main.this, "Info="+info+" Address="+address, Toast.LENGTH_SHORT).show();

			BluetoothDevice device = mBtAdapter.getRemoteDevice(address);

			mConnectThread = new ConnectThread(device, new File ("/storage/sdcard1/Books/Ruby/JavaRuby.pdf"));
			mConnectThread.start();
		}
	};

	//Listens for discovered devices & changes the title when discovery is finished
	private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			//When discovery finds a device
			if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				//Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

				// If it's already paired, skip it, because it's been listed already
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

	public synchronized void stopThreads() {
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		if (mAcceptThread != null) {
			mAcceptThread.cancel();
			mAcceptThread = null;
		}
	}

	public synchronized void startThreads() {
		// Cancel any thread attempting to make a connection
		if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

		// Start the thread to listen on a BluetoothServerSocket
		if (mAcceptThread == null) {
			mAcceptThread = new AcceptThread();
			mAcceptThread.start();
		}
	}

	private class AcceptThread extends Thread {
		private final BluetoothServerSocket mmServerSocket;

		public AcceptThread() {
			BluetoothServerSocket tmp = null;
			try {
				// MY_UUID is the app's UUID string, also used by the client code
				tmp = mBtAdapter.listenUsingRfcommWithServiceRecord(NAME, MY_UUID);
			} catch (IOException e) { }
			mmServerSocket = tmp;
		}

		public void run() {
			BluetoothSocket socket = null;
			// Keep listening until exception occurs or a socket is returned
			while (true) {
				try {
					socket = mmServerSocket.accept();
				} catch (IOException e) {
					break;
				}
				// If a connection was accepted
				if (socket != null) {
					//Do work to manage the connection (in a separate thread)
					//manageConnectedSocket(socket);

					mConnectedThread = new ConnectedThread(socket);
					mConnectedThread.start();

					try {
						mmServerSocket.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					break;
				}
			}
		}

		public void cancel() {
			try {
				mmServerSocket.close();
			} catch (IOException e) { }
		}
	}


	private class ConnectThread extends Thread{
		private final BluetoothSocket mmSocket;
		private File file;

		public ConnectThread(BluetoothDevice device, File f)
		{
			BluetoothSocket tmp = null;

			//Get a BluetoothSocket to connect with the given BluetoothDevice
			try
			{
				file = f;
				tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
				Method m = device.getClass().getMethod("createRfcommSocket", new Class[] { int.class });
				tmp = (BluetoothSocket) m.invoke(device, 1);
			} catch (Exception e)
			{
				Log.e("Main-ConnectThread","Couldn't create a Bluetooth Socket!");
				e.printStackTrace();
			}

			mmSocket = tmp;
		}

		public void run()
		{
			mBtAdapter.cancelDiscovery();

			try{
				// Connect the device through the socket. This will block
				// until it succeeds or throws an exception
				mmSocket.connect();
				Log.d("Main-ConnectThread","Connection established");
			} 
			catch (IOException connectException){

				try{
					mmSocket.close();
				} 
				catch (IOException closeException){
					Log.e("Main-ConnectThread","Failed to close connection");
					closeException.printStackTrace();
				}

				Log.e("Main-ConnectThread","Failed to connect!");
				return;
			}

			//manageConnectedSocket(socket);

			mConnectedThread = new ConnectedThread(mmSocket,file);
			mConnectedThread.start();

			byte[] b = new byte[(int) file.length()];
			try {
				FileInputStream fileInputStream = new FileInputStream(file);
				fileInputStream.read(b);
				for (int i = 0; i < b.length; i++) {
					System.out.print((char)b[i]);
				}
			} catch (FileNotFoundException e) {
				System.out.println("File Not Found.");
				e.printStackTrace();
			}
			catch (IOException e1) {
				System.out.println("Error Reading The File.");
				e1.printStackTrace();
			}

			mConnectedThread.write(b);
		}

		public void cancel()
		{
			try
			{
				mmSocket.close();
			} catch (IOException e)
			{
				e.printStackTrace();
			}
		}
	}

	private class ConnectedThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;
		private File file;

		public ConnectedThread(BluetoothSocket socket, File f) {
			file = f;
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			// Get the input and output streams, using temp objects because
			// member streams are final
			try {
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) { }

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}

		public ConnectedThread(BluetoothSocket socket) {
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			// Get the input and output streams, using temp objects because
			// member streams are final
			try {
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) { }

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}

		public void run() {
			byte[] buffer = new byte[1024];  // buffer store for the stream
			int bytes = 0; // bytes returned from read()

			// Keep listening to the InputStream until an exception occurs
			while (true) {
				try {
					//Read from the InputStream
					bytes = mmInStream.read(buffer);
					//Send the obtained bytes to the UI activity
					//mHandler.obtainMessage(MESSAGE_READ, bytes, -1, buffer).sendToTarget();
					
				} 
				catch (IOException e) {
					break;
				}
			}
			
			FileOutputStream fos;
			try {
				fos = new FileOutputStream("");

	            fos.write(bytes);
	            fos.close();
			} 
			catch (FileNotFoundException e) {
				e.printStackTrace();
			} 
			catch (IOException e) {
				e.printStackTrace();
			}			
		}

		/* Call this from the main activity to send data to the remote device */
		public void write(byte[] bytes) {
			try {
				mmOutStream.write(bytes);
			} catch (IOException e) { }
		}

		/* Call this from the main activity to shutdown the connection */
		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) { }
		}
	}

}// end Main