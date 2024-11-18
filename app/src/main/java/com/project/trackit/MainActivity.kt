package com.project.trackit

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.project.trackit.databinding.ActivityMainBinding
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.Socket
import java.net.DatagramSocket
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var longitudeText: TextView
    private lateinit var latitudeText: TextView
    private lateinit var dateText: TextView
    private lateinit var timeText: TextView
    //private lateinit var sendButton_UDP: Button

    private var currentData: String = ""
    private var isSendingUDP = false
    private val handler = Handler(Looper.getMainLooper())
    private val sendInterval: Long = 10000 // 10 seconds
    private var lastGpsUpdateTime: Long = 0
    private var lastNetworkUpdateTime: Long = 0

    private lateinit var rpmTextView: TextView
    private lateinit var velTextView: TextView
    private lateinit var fuelTextView: TextView
    private lateinit var connectionStatusTextView: TextView

    private lateinit var autoCompleteTextView: AutoCompleteTextView


    private lateinit var bluetoothManager: BluetoothOBDIIManager

    private lateinit var binding: ActivityMainBinding // dropdown menu


    companion object {
        private const val LOCATION_PERMISSION_CODE = 100
        private const val BLUETOOTH_CONNECT_REQUEST_CODE = 101
        private const val REQUEST_ENABLE_BT = 102
        const val UDP_PORT = 60001
        const val IP_ADDRESS_1 = "trackit01.ddns.net" // Server Jesús
        const val IP_ADDRESS_2 = "trackit02.ddns.net" // Server María Victoria
        const val IP_ADDRESS_3 = "trackit03.ddns.net" // Server Orlando
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //Configure cars dropdown menu
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cars = resources.getStringArray(R.array.cars)
        val arrayAdapter = ArrayAdapter(this, R.layout.dropdown_item, cars)
        binding.autoCompleteTextView.setAdapter(arrayAdapter)
        //

        latitudeText = findViewById(R.id.latitudeValue)
        longitudeText = findViewById(R.id.longitudeValue)
        dateText = findViewById(R.id.dateValue)
        timeText = findViewById(R.id.timeValue)
        //sendButton_UDP = findViewById(R.id.sendButton_UDP)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        rpmTextView = findViewById(R.id.RPMValue)
        velTextView = findViewById(R.id.speedValue)
        fuelTextView = findViewById(R.id.fuelValue)
        connectionStatusTextView = findViewById(R.id.connectionStatusValue)

        autoCompleteTextView = findViewById<AutoCompleteTextView>(R.id.autoCompleteTextView)


        // Initialize BluetoothOBDIIManager
        bluetoothManager = BluetoothOBDIIManager(this,
            rpmTextView = rpmTextView,
            velTextView = velTextView,
            fuelTextView = fuelTextView,
            connectionStatusTextView = connectionStatusTextView)

        // Iniciar la conexión Bluetooth en un hilo separado
        CoroutineScope(Dispatchers.IO).launch {
            bluetoothManager.manageMyConnectedSocket()
        }
        startSendingUDP()

        // Configure GetDevices button
        val buttonGetDevices: CardView = findViewById(R.id.buttonGetDevices)
        buttonGetDevices.setOnClickListener {
            bluetoothManager.checkConnectionAndShowDialog()
        }


        // Check permissions
        if (!checkLocationPermissions()) {
            requestLocationPermissions()
        } else {
            checkBluetoothPermissions()
            startLocationUpdates()
        }


        // Configure Start sending button
        // sendButton_UDP.setOnClickListener {
           // if (!isSendingUDP) {
          //      startSendingUDP()
         //   } else {
         //       stopSendingUDP()
         //   }
        //}
    }



    override fun onLocationChanged(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val provider = location.provider
        val locationTime = location.time

        when (provider) {
            LocationManager.GPS_PROVIDER -> lastGpsUpdateTime = locationTime
            LocationManager.NETWORK_PROVIDER -> lastNetworkUpdateTime = locationTime
        }

        if (lastGpsUpdateTime > lastNetworkUpdateTime) {
            if (provider == LocationManager.GPS_PROVIDER) {
                updateUIWithLocation(lat, lon, locationTime, provider)
            }
        } else {
            if (provider == LocationManager.NETWORK_PROVIDER) {
                updateUIWithLocation(lat, lon, locationTime, provider)
            }
        }



        Log.d("LocationUpdate", "Provider: $provider, Lat: $lat, Lon: $lon, Time: $locationTime")
    }



    private fun updateUIWithLocation(lat: Double, lon: Double, locationTime: Long, provider: String) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        dateFormat.timeZone = TimeZone.getDefault()
        timeFormat.timeZone = TimeZone.getDefault()
        val localDate = dateFormat.format(Date(locationTime))
        val localTime = timeFormat.format(Date(locationTime))

        val formattedLat = String.format("%.5f", lat)
        val formattedLon = String.format("%.5f", lon)

        val selectedText = autoCompleteTextView.text.toString()



        if (selectedText == "Car 1"){
            currentData = "Auto: 1, Lat: $formattedLat, Lon: $formattedLon, Date: $localDate, Time: $localTime, Vel: ${velTextView.text}, RPM: ${rpmTextView.text}, Fuel: ${fuelTextView.text}"
        }else{
            currentData = "Auto: 2, Lat: $formattedLat, Lon: $formattedLon, Date: $localDate, Time: $localTime, Vel: ${velTextView.text}, RPM: ${rpmTextView.text}, Fuel: ${fuelTextView.text}"
        }


        latitudeText.text = " $formattedLat"
        longitudeText.text = " $formattedLon"
        dateText.text = " $localDate"
        timeText.text = " $localTime"

        Log.d("LocationUpdate", "Using data from provider: $provider, Data: $currentData")
    }


    private fun startSendingUDP() {
        isSendingUDP = true
     //   sendButton_UDP.text = "Stop sending"
     //   sendButton_UDP.setCompoundDrawablesWithIntrinsicBounds(R.drawable.stop_icon, 0, 0, 0)

        CoroutineScope(Dispatchers.IO).launch {
            handler.post(object : Runnable {
                override fun run() {
                    if (isSendingUDP && currentData.isNotEmpty()) {

                        sendLocationData_UDP(IP_ADDRESS_1)
                        sendLocationData_UDP(IP_ADDRESS_2)
                        sendLocationData_UDP(IP_ADDRESS_3)
                        Log.d("UDP", "Datos enviados: $currentData")


                    }

                    // Programar el siguiente envío
                    if (isSendingUDP) {
                        handler.postDelayed(this, sendInterval)
                    }
                }
            })
        }
    }


    private fun stopSendingUDP() {
        isSendingUDP = false
     //   sendButton_UDP.text = "Start sending"
     //   sendButton_UDP.setCompoundDrawablesWithIntrinsicBounds(R.drawable.send_icon, 0, 0, 0)
        handler.removeCallbacksAndMessages(null)
    }

    private fun sendLocationData_UDP(ipAddress: String) {
        Thread {
            try {
                val socket_udp = DatagramSocket()
                val address = InetAddress.getByName(ipAddress)
                val message_udp = currentData.toByteArray()
                val packet = DatagramPacket(message_udp, message_udp.size, address, UDP_PORT)
                socket_udp.send(packet)
                socket_udp.close()
                Log.d("UDP", "Data sent to $ipAddress")
            } catch (e: Exception) {
                Log.e("UDP", "Error sending data to $ipAddress: ${e.message}")
                runOnUiThread {
                    //Toast.makeText(this, "Error sending data: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Location permission granted.", Toast.LENGTH_SHORT).show()
                    checkBluetoothPermissions()
                    startLocationUpdates()
                } else {
                    Toast.makeText(this, "Location permission denied.", Toast.LENGTH_SHORT).show()
                }
            }
            BLUETOOTH_CONNECT_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Bluetooth permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Bluetooth disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }



    private fun checkLocationPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fineLocation == PackageManager.PERMISSION_GRANTED && coarseLocation == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION_CODE)
    }

    private fun checkBluetoothPermissions() {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "This device does not support Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        // If Bluetooth is disabled, ask to enable
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        }

        // Ask for Bluetooth permissions if needed
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), BLUETOOTH_CONNECT_REQUEST_CODE)
        }
    }


    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 0f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 0f, this)
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
