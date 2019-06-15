package com.imaginadesarrollo.universalhrm.main

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Handler
import android.os.ParcelUuid
import android.preference.PreferenceManager
import android.util.Log
import com.imaginadesarrollo.universalhrm.HrmCallbackMethods
import com.imaginadesarrollo.universalhrm.R
import com.imaginadesarrollo.universalhrm.manager.HRDeviceRef
import com.imaginadesarrollo.universalhrm.manager.HRManager
import com.imaginadesarrollo.universalhrm.manager.HRProvider
import com.imaginadesarrollo.universalhrm.ui.custom.DeviceDialogFragment
import com.imaginadesarrollo.universalhrm.utils.LibUtils.convertFromInteger
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanSettings
import io.reactivex.schedulers.Schedulers
import java.util.*

internal class UniversalHrmImplementation(private val activity: android.support.v7.app.AppCompatActivity, private val caller: HrmCallbackMethods? = null): HrmImplementation, HRProvider.HRClient, DeviceAdapter.OnDeviceSelected {

    companion object {
        const val TAG = "UniversalHrmImpl"
    }

    private val callback: HrmCallbackMethods by lazy {  caller ?: (activity as HrmCallbackMethods) }
    private val deviceAdapter: DeviceAdapter by lazy { DeviceAdapter(this) }
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(activity) }
    private val handler: Handler by lazy { Handler() }
    private val customBuilder by lazy { DeviceDialogFragment(deviceAdapter) }

    private var selectedHr: HRDeviceRef? = null
    private var hrProviderSelected: Boolean = false

    private val providers: List<HRProvider>
    private lateinit var hrProvider: HRProvider

    init {
        selectedHr = retrieveSavedDevice()

        providers = HRManager.getHRProviderList(activity)
        if(providers.isEmpty()){
            // Does not have Bluetooth or Ant+ capabilities
            callback.deviceNotSupported()
        }

        load()
        open()
    }

    override fun scan() {
        //scanAction()
        startRxScan()
    }

    override fun connect() {connect2()}

    override fun disconnect() {
        if(::hrProvider.isInitialized){
            Log.d(TAG, hrProvider.providerName + ".disconnect()")
            //stopTimer()
            hrProvider.disconnect()
            callback.onDeviceDisconnected()
        }
    }

    override fun isConnected(): Boolean {
        return (::hrProvider.isInitialized && (hrProvider.isConnecting || hrProvider.isConnected))
    }

    override fun isThereSavedDevice(): Boolean = selectedHr != null

    /** MAIN FRAGMENT IMPLEMENTATION **/

    private fun startScan(hrProvider: HRProvider) {
        Log.d(hrProvider.providerName, ".startScan()")
        deviceAdapter.clear()
        hrProvider.startScan()

        customBuilder.show(activity.supportFragmentManager, "dialog")
    }

    private fun selectProvider() {
        val items = arrayOfNulls<CharSequence>(providers.size)
        val itemNames = arrayOfNulls<CharSequence>(providers.size)
        for (i in items.indices) {
            items[i] = providers.get(i).getProviderName()
            itemNames[i] = providers.get(i).getName()
        }
        val builder = android.support.v7.app.AlertDialog.Builder(activity)
        builder.setTitle(activity.getString(R.string.select_type_of_Bluetooth_device))
        builder.setPositiveButton(activity.getString(R.string.OK)
        ) { dialog, which -> open() }
        builder.setNegativeButton(activity.getString(R.string.cancel)
        ) { dialog, which ->
            mIsScanning = false
            load()
            open()
            dialog.dismiss()
        }
        builder.setSingleChoiceItems(itemNames, -1
        ) { arg0, arg1 ->
            hrProvider = HRManager.getHRProvider(activity,
                    items[arg1].toString())
            hrProviderSelected = true
            Log.d(TAG, "hrProvider = " + hrProvider.providerName)
        }
        builder.show()
    }

    private fun retrieveSavedDevice(): HRDeviceRef?{
        with(prefs){
            val name = getString(activity.getString(R.string.pref_bt_name), "") ?: ""
            val address = getString(activity.getString(R.string.pref_bt_address), "") ?: ""
            val provider = getString(activity.getString(R.string.pref_bt_provider), "") ?: ""
            if(address.isNotEmpty() && provider.isNotEmpty()){
                return HRDeviceRef.create(provider, name, address)
            }else return null
        }
    }

    private fun saveDevice(deviceName: String, deviceAddress: String, providerName: String) {
        prefs.edit().apply {
            putString(activity.resources.getString(R.string.pref_bt_name), deviceName)
            putString(activity.resources.getString(R.string.pref_bt_address), deviceAddress)
            putString(activity.resources.getString(R.string.pref_bt_provider), providerName)
        }.apply()
    }

    /** END MAIN FRAGMENT IMPLEMENTATION **/

    /** PRESENTER IMPLEMENTATION **/

    private fun load() {
        if (!selectedHr?.name.isNullOrBlank()) {
            Log.d(TAG, "HRManager.get(${selectedHr!!.provider})")
            hrProvider = HRManager.getHRProvider(activity, selectedHr!!.provider)
        }
    }

    private fun open() {
        if (hrProviderSelected == true && ::hrProvider.isInitialized && !hrProvider.isEnabled) {
            if (hrProvider.startEnableIntent(activity, 0)) {
                return
            }
            //hrProvider = null
            hrProviderSelected = false
        }
        if (hrProviderSelected == true && ::hrProvider.isInitialized) {
            Log.d(hrProvider.providerName, ".open(this)")
            hrProvider.open(handler, this)
        }
    }

    private fun scanConnect() {
        Log.d(TAG, hrProvider.providerName + ".stopScan()")
        hrProvider.stopScan()
        connect2()
    }

    private fun scanCancel() {
        Log.d(TAG, hrProvider.providerName + ".stopScan()")
        callback.scanCanceled()
        hrProvider.stopScan()
        load()
        open()
    }

    private fun connect2() { // or reconnect
        // TODO add feedback. Procesing...
        //stopTimer()
        if (!::hrProvider.isInitialized || selectedHr?.address.isNullOrBlank()) {
            return
        }
        if (hrProvider.isConnecting || hrProvider.isConnected) {
            disconnect()
        }

        selectedHr?.name?.let { callback.setHeartRateMonitorName(it) }
        callback.setHeartRateValue(0)

        Log.d(TAG, hrProvider.providerName + ".connect(" + selectedHr?.name + ")")

        hrProvider.connect(selectedHr/*HRDeviceRef.create(hrProvider.providerName, btName, btAddress)*/)
    }

    private val reconnectingTime = 2000 // 2 sec
    private var numTry = 1
    private var ongoing = false
    private fun reconnect(){
        if(!ongoing){
            if(isConnected()){
                numTry = 1
                ongoing = false
            }else{
                //stopTimer()
                ongoing = true
                val wait = reconnectingTime.times(numTry).toLong()
                Log.d(TAG, "Reconnecting in ${wait.div(1000)} seconds")
                Handler().postDelayed({
                    connect2()
                    ongoing = false
                }, wait)
            }
        }

    }

  /*  private var hrReader: Timer? = null
    private fun startTimer() {
        hrReader = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    handler.post(object : Runnable {
                        override fun run() {
                            readHR()
                        }
                    })
                }
            }, 0, 500)
        }
    }

    private fun stopTimer() {
        hrReader?.apply {
            cancel()
            purge()
        }
        hrReader = null
    }*/

    private fun readHR() {
        if (hrProviderSelected == true && ::hrProvider.isInitialized) {
            hrProvider.hrData?.let {
                var hrValue: Long = 0
                if (it.hasHeartRate)
                    hrValue = it.hrValue

                callback.setHeartRateValue(hrValue.toInt())
            } ?: run { reconnect() }
        }
    }

    private fun scanAction() {
        clear()
        //stopTimer()

        close()
        mIsScanning = true
        Log.d(TAG, "select HR-provider")
        selectProvider()
    }

    private fun clear() {
        selectedHr = null
    }

    private fun close() {
        if(hrProviderSelected == true && ::hrProvider.isInitialized){
            Log.d(hrProvider.providerName, ".close()")
            hrProvider.close()
            //hrProvider = null
            hrProviderSelected = false
        }
    }

    /** END PRESENTER IMPLEMENTATION **/

    /** HR CLIENT IMPLEMENTATION **/

    private var mIsScanning = false

    override fun onOpenResult(ok: Boolean) {
        Log.d(TAG, hrProvider.providerName + "::onOpenResult(" + ok + ")")
        if (mIsScanning) {
            mIsScanning = false
            startScan(hrProvider)
            return
        }
    }

    override fun onScanResult(device: HRDeviceRef) {
        /*Log.d(TAG, (hrProvider.providerName + "::onScanResult(" + device.address + ", "
                + device.name + ")"))
        deviceAdapter.addDevice(device)*/
    }

    override fun onConnectResult(connectOK: Boolean) {
        Log.d(TAG, hrProvider.providerName + "::onConnectResult(" + connectOK + ")")
        if (connectOK) {
            callback.onDeviceConnected()
            callback.setHeartRateMonitorName(hrProvider.name)
            callback.setHeartRateMonitorProviderName(hrProvider.providerName)
            callback.setHeartRateMonitorAddress(selectedHr!!.address)
            saveDevice(hrProvider.name, selectedHr!!.address, hrProvider.providerName)
            if (hrProvider.batteryLevel > 0) {
                val level = hrProvider.batteryLevel
                callback.setBatteryLevel(level)
            }
            //startTimer()
        }
    }

    override fun onDisconnectResult(disconnectOK: Boolean) {
        if (disconnectOK){
            callback.onDeviceDisconnected()
        }
        Log.d(TAG, hrProvider.providerName + "::onDisconnectResult(" + disconnectOK + ")")
    }

    override fun onCloseResult(closeOK: Boolean) {
        Log.d(TAG, hrProvider.providerName + "::onCloseResult(" + closeOK + ")")
    }

    override fun log(src: HRProvider?, msg: String?) {
        Log.d(TAG, "$src?.name: $msg?")
    }

    /*verride fun onDeviceSelected(device: HRDeviceRef) {
        customBuilder.dismiss()
        selectedHr = device
        connect2()
    }*/


    /**
     *
     *  New code with Rx
     *
     */
    val rxBleClient by lazy { RxBleClient.create(activity) }

    val HRP_SERVICE = UUID
            //.fromString("0000180D-0000-0000-0000-000000000000")
            .fromString("0000180D-0000-1000-8000-00805f9b34fb")
    val BATTERY_SERVICE = UUID
            .fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISON_UUID = UUID
            .fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val DIS_UUID = UUID
            .fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_CHARAC = UUID
            .fromString("00002A19-0000-1000-8000-00805f9b34fb")
    val CCC = UUID
            .fromString("00002902-0000-1000-8000-00805f9b34fb")

    val HEART_RATE_MEASUREMENT_CHARAC = UUID
            .fromString("00002A37-0000-1000-8000-00805f9b34fb")

    val UNIVERSAL_HRP_SERVICE = convertFromInteger(0x180D)
    val HEART_RATE_MEASUREMENT_CHAR_UUID = convertFromInteger(0x2A37)

    @SuppressLint("CheckResult")
    @TargetApi(23)
    private fun startRxScan() {
        val scanSettings = ScanSettings.Builder()
               /* .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)*/
                .build()

        val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UNIVERSAL_HRP_SERVICE))
                .build()

        rxBleClient.scanBleDevices(scanSettings, scanFilter)
                .doOnSubscribe { customBuilder.show(activity.supportFragmentManager, "dialog") }
                .doOnError { Log.e(TAG, it.message) }
                .subscribe{ scanResult -> deviceAdapter.addDevice(scanResult.bleDevice) }
    }

    override fun onDeviceSelected(device: RxBleDevice) {
        rxBleClient.getBleDevice(device.macAddress)
                .establishConnection(true)
                .flatMap { it.setupNotification(HEART_RATE_MEASUREMENT_CHARAC) }
                .doOnSubscribe { customBuilder.dismiss() }
                .subscribe { emitter ->
                    emitter.subscribeOn(Schedulers.io())
                            .observeOn(Schedulers.io())
                            .doOnError { it.printStackTrace() }
                            .doOnComplete { Log.e(TAG, "Completed") }
                            .subscribe { characteristicValue ->

                                Log.d(TAG, "Charactaristic value: $characteristicValue")
                            }
                }
    }
}


/*.flatMap { rxBleConnection -> Observable
                  .interval(1, TimeUnit.SECONDS)
                  .flatMapSingle {  rxBleConnection.readCharacteristic(HEART_RATE_MEASUREMENT_CHARAC)}
          }*/

/*.flatMapSingle { it.discoverServices() }
        .flatMap { it.getCharacteristic(HRP_SERVICE, HEART_RATE_MEASUREMENT_CHARAC).toObservable() }
        .map { characteristic ->
            Observable
                    .interval(1, TimeUnit.SECONDS)
                    .flatMapSingle { Single.create<Byte> { characteristic.value } }
                    .subscribe {

                    }

        }*/

