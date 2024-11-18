package com.project.trackit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import android.os.HandlerThread



class BluetoothOBDIIManager(private val context: Context,
                            private val rpmTextView: TextView,
                            private val velTextView: TextView,
                            private val fuelTextView: TextView,
                            private val connectionStatusTextView: TextView
) {

    val pairedDevicesList = mutableListOf<String>()
    val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var isConnected = false
    private var selectedDevice: BluetoothDevice? = null

    private var rpm: Int = 0
    private var vel: Int = 0
    private var fuel: Int = 0

    private var connectedSocket: BluetoothSocket? = null


    fun checkConnectionAndShowDialog() {
        if (isConnected) {
            Toast.makeText(context, "Already connected to a device", Toast.LENGTH_SHORT).show()
        } else {
            showDeviceSelectionDialog()
        }
    }

    @SuppressLint("MissingPermission")
    private fun getPairedDevices() {
        pairedDevicesList.clear()
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter != null) {
            val devices = bluetoothAdapter.bondedDevices
            if (devices.isEmpty()) {
                Toast.makeText(context, "No paired devices", Toast.LENGTH_SHORT).show()
                Log.i("Bluetooth", "No paired devices")
            } else {
                devices.forEach { device: BluetoothDevice ->
                    val deviceName = device.name ?: "Unknown"
                    pairedDevicesList.add("$deviceName (${device.address})")
                }
            }
        } else {
            Toast.makeText(context, "Device does not support Bluetooth", Toast.LENGTH_SHORT).show()
            Log.e("Bluetooth", "Device does not support Bluetooth")
        }
    }

    private fun showDeviceSelectionDialog() {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle("Select Device")
            .setCancelable(false)

        // Get paired devices
        getPairedDevices()

        // Create a simple dialog view for device selection
        val listView = android.widget.ListView(context).apply {
            adapter = android.widget.ArrayAdapter(
                context,
                android.R.layout.simple_list_item_single_choice,
                pairedDevicesList
            )
            choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
        }

        builder.setView(listView)

        builder.setNegativeButton("Cancel") { dialog, _ ->
            Toast.makeText(context, "No device connected", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        builder.setPositiveButton("Connect") { dialog, _ ->
            val selectedPosition = listView.checkedItemPosition
            if (selectedPosition != android.widget.ListView.INVALID_POSITION) {
                val deviceInfo = pairedDevicesList[selectedPosition]
                val macAddress = deviceInfo.substring(
                    deviceInfo.indexOf('(') + 1,
                    deviceInfo.indexOf(')')
                )
                selectedDevice = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(macAddress)
                Toast.makeText(context, "Trying to connect to $macAddress", Toast.LENGTH_SHORT).show()

                // Create thread for communication with OBDII device
                val connectThread = ConnectThread()
                connectThread.connectToDevice(selectedDevice!!)
            } else {
                Toast.makeText(context, "Please select a device", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        builder.show()
    }



    //NUEVO ORLY
    // Clase interna para el hilo de conexión
    private inner class ConnectThread : Thread() {

        @SuppressLint("MissingPermission")
        private var mmSocket: BluetoothSocket? = null
        private val handler = Handler(Looper.getMainLooper())
        private var device: BluetoothDevice? = null // Variable para almacenar el dispositivo Bluetooth

        // Función para establecer el dispositivo y comenzar la conexión
        @SuppressLint("MissingPermission")
        fun connectToDevice(device: BluetoothDevice) {
            this.device = device
            mmSocket = device.createRfcommSocketToServiceRecord(MY_UUID)
            start() // Iniciar el hilo de conexión
        }

        @SuppressLint("MissingPermission")
        override fun run() {
            try {
                bluetoothAdapter?.cancelDiscovery() // Cancela la búsqueda de dispositivos

                // Intentar conectar
                mmSocket?.let { socket ->
                    socket.connect()
                    isConnected = true // Conexión exitosa

                    // Inicializar los streams de entrada y salida
                    initializeStreams(socket)

                    Handler(Looper.getMainLooper()).post {
                        connectionStatusTextView.text = "OBDII: connected"
                    }

                    // Llamada a la función para gestionar la conexión sin parámetros adicionales
                    manageMyConnectedSocket()
                    startConnectionCheck() // Comienza a verificar el estado de la conexión
                }
            } catch (e: IOException) {
                Log.e("Bluetooth", "Error al conectar: ${e.message}")
                handleDisconnection()
            }
        }

        // Función para inicializar los streams de entrada y salida
        fun initializeStreams(socket: BluetoothSocket) {
            inputStream = socket.inputStream
            outputStream = socket.outputStream
        }



        fun startConnectionCheck() {
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (isConnected) {
                        try {
                            // Intenta leer del socket para verificar la conexión
                            val buffer = ByteArray(1) // Buffer de un byte
                            mmSocket?.inputStream?.read(buffer) // Si se lanza IOException, significa que está desconectado
                        } catch (e: IOException) {
                            Log.e("Bluetooth", "Socket está desconectado: ${e.message}")
                            handleDisconnection()
                        } finally {
                            handler.postDelayed(this, 5000) // Verificar cada 5 segundos
                        }
                    }
                }
            }, 5000) // Comienza la verificación después de 5 segundos
        }

        fun handleDisconnection() {
            isConnected = false
            rpm = 0
            vel = 0
            fuel = 0
            Handler(Looper.getMainLooper()).post {
                connectionStatusTextView.text = "OBDII: disconnected"
                rpmTextView.text = "0"
                fuelTextView.text = "0"
                velTextView.text = "0"

                Toast.makeText(context, "Device has disconnected", Toast.LENGTH_SHORT).show()
            }
            try {
                mmSocket?.close() // Asegúrate de cerrar el socket
            } catch (closeException: IOException) {
                Log.e("Bluetooth", "Error al cerrar el socket: ${closeException.message}")
            }
        }
    }


    var inputStream: InputStream? = null
    var outputStream: OutputStream? = null




    fun manageMyConnectedSocket() {
        if (inputStream == null || outputStream == null) return

        val delay = 10000L // Intervalo de 10 segundos
        val shortDelay = 200L // Retraso corto entre comandos
        var initialized = false // Bandera para saber si se han enviado los comandos de inicialización

        if (isConnected) {
            val handlerThread = HandlerThread("BluetoothHandlerThread")
            handlerThread.start()
            val handler = Handler(handlerThread.looper)

            // Runnable para enviar y recibir datos
            val sendAndReceiveRunnable = object : Runnable {
                override fun run() {
                    try {
                        if (!initialized) {
                            // Enviar comandos de inicialización solo una vez
                            val initCommands = listOf("AT Z\r\n", "AT E0\r\n", "AT SP 0\r\n")
                            for (command in initCommands) {
                                sendData(command)
                                Thread.sleep(shortDelay)
                                val response = readData()
                                Log.d("Bluetooth", "Datos recibidos para $command: $response")
                            }
                            initialized = true // Marcar como inicializado después del primer envío
                        }

                        // Enviar comandos de datos cada 10 segundos
                        val dataCommands = listOf( "01 0C\r\n", "01 0D\r\n", "01 2F\r\n")
                        for (command in dataCommands) {
                            sendData(command)
                            Thread.sleep(shortDelay)
                            val response = readData()
                            Log.d("Bluetooth", "Datos recibidos para $command: $response")

                            // Procesar respuesta de acuerdo con el comando
                            when {
                                response.contains("1 0C") -> processRPMResponse(response)
                                response.contains("1 0D") -> processVELResponse(response)
                                response.contains("1 2F") -> processFUELResponse(response)
                                else -> Log.d("Bluetooth", "No concuerda para conversión. Se mantienen los valores anteriores.")
                                    // Aquí no cambiamos rpm, vel, y fuel, manteniendo sus últimos valores

                            }
                        }

                        // Repetir los comandos de datos cada 10 segundos si sigue conectado
                        if (isConnected) {
                            handler.postDelayed(this, delay)
                        }
                    } catch (e: IOException) {
                        Log.e("Bluetooth", "Error al enviar o recibir datos: ${e.message}")
                        isConnected = false
                    }
                }
            }
            handler.post(sendAndReceiveRunnable)
        }
    }



    // Función para enviar datos
    fun sendData(data: String) {
        try {
            outputStream?.write(data.toByteArray())
            outputStream?.flush()
            Log.d("Bluetooth", "Datos enviados: $data")
        } catch (e: IOException) {
            Log.e("Bluetooth", "Error al enviar datos: ${e.message}")
        }
    }

    // Función para leer datos
    fun readData(): String {
        return try {
            val buffer = ByteArray(1024)
            val bytes = inputStream!!.read(buffer)
            val receivedData = String(buffer, 0, bytes)

            receivedData
        } catch (e: IOException) {
            Log.e("Bluetooth", "Error al leer datos: ${e.message}")
            ""
        }
    }

    
    // Function for processing rpm
    fun processRPMResponse(receivedData: String) {
        val data = receivedData.split(" ")
        if (data.size >= 4) {
            val a = Integer.parseInt(data[2], 16)
            val b = Integer.parseInt(data[3], 16)
            rpm = ((a * 256) + b) / 4
            rpmTextView.post { rpmTextView.text = "$rpm" } // Actualiza el TextView de RPM
        }
    }

    // Function for processing speed
    fun processVELResponse(receivedData: String) {
        val data = receivedData.split(" ")
        if (data.size >= 3) {
            val a = Integer.parseInt(data[2], 16)
            vel = a
            velTextView.post { velTextView.text = "$vel" } // Actualiza el TextView de velocidad
        }
    }

    // Function for processing fuel
    fun processFUELResponse(receivedData: String) {
        val data = receivedData.split(" ")
        if (data.size >= 3) {
            val a = Integer.parseInt(data[2], 16)
            fuel = (100 * a) / 255
            fuelTextView.post { fuelTextView.text = "$fuel" } // Actualiza el TextView de combustible
        }
    }

}
