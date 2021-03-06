package com.yeolabgt.mahmoodms.ssvepinterfacetf

import android.app.Activity
import android.app.ProgressDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.support.v4.app.NavUtils
import android.support.v4.content.FileProvider
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.*

import com.androidplot.util.Redrawer
import com.google.common.primitives.Doubles
import com.google.common.primitives.Floats
import com.parrot.arsdk.arcommands.ARCOMMANDS_JUMPINGSUMO_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_ENUM
import com.parrot.arsdk.arcontroller.ARCONTROLLER_DEVICE_STATE_ENUM
import com.parrot.arsdk.arcontroller.ARControllerCodec
import com.parrot.arsdk.arcontroller.ARFrame
import com.parrot.arsdk.ardiscovery.ARDiscoveryDeviceService
import com.yeolabgt.mahmoodms.actblelibrary.ActBle
import com.yeolabgt.mahmoodms.ssvepinterfacetf.ParrotDrone.JSDrone
import kotlinx.android.synthetic.main.activity_device_control.*

import org.tensorflow.contrib.android.TensorFlowInferenceInterface

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.experimental.and
import kotlin.experimental.or

/**
 * Created by mahmoodms on 5/31/2016.
 * Android Activity for Controlling Bluetooth LE Device Connectivity
 */

class DeviceControlActivity : Activity(), ActBle.ActBleListener, TensorflowOptionsMenu.NoticeDialogListener {
    // Graphing Variables:
    private var mGraphInitializedBoolean = false
    private var mGraphAdapterCh1: GraphAdapter? = null
    private var mGraphAdapterCh2: GraphAdapter? = null
    private var mTimeDomainPlotAdapter: XYPlotAdapter? = null
    private var mCh1: DataChannel? = null
    private var mCh2: DataChannel? = null
    //Device Information
    private var mBleInitializedBoolean = false
    private lateinit var mBluetoothGattArray: Array<BluetoothGatt?>
    private var mActBle: ActBle? = null
    private var mDeviceName: String? = null
    private var mDeviceAddress: String? = null
    private var mConnected: Boolean = false
    private var mMSBFirst = false
    //Connecting to Multiple Devices
    private var deviceMacAddresses: Array<String>? = null
    private var mLedWheelchairControlService: BluetoothGattService? = null
    private var mEEGConfigGattService: BluetoothGattService? = null
    private var mWheelchairGattIndex: Int = 0
    private var mEEGConfigGattIndex: Int = 0
    private var mEEGConnectedAllChannels = false
    // Classification
    private var mNumber2ChPackets = -1
    private var mNumberOfClassifierCalls = 0
    private var mRunTrainingBool: Boolean = false
    //UI Elements - TextViews, Buttons, etc
    private var mTrainingInstructions: TextView? = null
    private var mBatteryLevel: TextView? = null
    private var mDataRate: TextView? = null
    private var mSSVEPClassTextView: TextView? = null
    private var mYfitTextView: TextView? = null
    private var mSButton: Button? = null
    private var mFButton: Button? = null
    private var mLButton: Button? = null
    private var mRButton: Button? = null
    private var mReverseButton: Button? = null
    private var mChannelSelect: ToggleButton? = null
    private var menu: Menu? = null
    //Data throughput counter
    private var mLastTime: Long = 0
    private var points = 0
    private val mTimerHandler = Handler()
    private var mTimerEnabled = false
    //Data Variables:
    private val batteryWarning = 20//
    private var dataRate: Double = 0.toDouble()
    private var mStimulusDelaySeconds = 0.0
    //Classification
    private var mWheelchairControl = false //Default classifier.
    //Play Sound:
    private lateinit var mMediaBeep: MediaPlayer
    //Tensorflow:
    private var mTFRunModel = false
    private var mTFInferenceInterface: TensorFlowInferenceInterface? = null
    private var mOutputScoresNames: Array<String>? = null
    private var mTensorflowSolutionIndex = 0
    private var mTensorflowWindowSize = 256
    private var mTensorflowXDim = 2 // x-dimension
    private var mTensorflowYDim = 128 // y-dimension

    private val timeStamp: String
        get() = SimpleDateFormat("yyyy.MM.dd_HH.mm.ss", Locale.US).format(Date())

    // Native Interface Function Handler:
    private val mNativeInterface = NativeInterfaceClass()

    // Drone Stuff:
    private var mARService: ARDiscoveryDeviceService? = null
    private var mJSDrone: JSDrone? = null

    private var mConnectionProgressDialog: ProgressDialog? = null

    private val mJSDroneSpeedLR: Int = 10
    private val mJSDroneSpeedFWREV: Int = 20

    private enum class AudioState {
        MUTE,
        INPUT,
        BIDIRECTIONAL
    }

    private var mAudioState = AudioState.MUTE

    private val mClassifyThread = Runnable {
        val y: DoubleArray
        if (mTFRunModel) {
            // Log time before preprocessing
            Log.i(TAG, "onCharacteristicChanged: TF_PRECALL_TIME, N#" + mNumberOfClassifierCalls.toString())
            //Run TF Model: SEE ORIGINAL .py SCRIPT TO VERIFY CORRECT INPUTS!
            val outputScores = FloatArray(5)//5 is number of classes/labels
            // Extract features from last wlen=256 datapoints:
            val ch1Doubles = DoubleArray(mTensorflowWindowSize)
            System.arraycopy(mCh1!!.classificationBuffer,
                    mCh1!!.classificationBufferSize - mTensorflowWindowSize - 1,
                    ch1Doubles, 0, mTensorflowWindowSize)
            val ch2Doubles = DoubleArray(mTensorflowWindowSize)
            System.arraycopy(mCh2!!.classificationBuffer,
                    mCh2!!.classificationBufferSize - mTensorflowWindowSize - 1,
                    ch2Doubles, 0, mTensorflowWindowSize)
            // TODO: it is easier to copy from each array into the larger array instead of doing this:
            var mSSVEPDataFeedTF = FloatArray(mTensorflowXDim * mTensorflowYDim)
            val ch1 = mNativeInterface.jtimeDomainPreprocessing(ch1Doubles, mTensorflowWindowSize)
            val ch2 = mNativeInterface.jtimeDomainPreprocessing(ch2Doubles, mTensorflowWindowSize)
            val mFilteredDataFloats = Floats.concat(ch1, ch2)
            if (mTensorflowSolutionIndex in 1..5) {
                val filteredDataDoubles: DoubleArray = DataChannel.convertFloatsToDoubles(mFilteredDataFloats)
                mSSVEPDataFeedTF = mNativeInterface.jTFCSMExtraction(filteredDataDoubles, mTensorflowWindowSize)
            } else if (mTensorflowSolutionIndex in 6..10) {
                mSSVEPDataFeedTF = mFilteredDataFloats
            }
            // 1 - feed probabilities:
            mTFInferenceInterface!!.feed("keep_prob", floatArrayOf(1f))
            mTFInferenceInterface!!.feed(INPUT_DATA_FEED, mSSVEPDataFeedTF, mTensorflowXDim.toLong(), mTensorflowYDim.toLong())
            mTFInferenceInterface!!.run(mOutputScoresNames)
            mTFInferenceInterface!!.fetch(OUTPUT_DATA_FEED, outputScores)
            val yTF = DataChannel.getIndexOfLargest(outputScores)
            Log.i(TAG, "CALL#" + mNumberOfClassifierCalls.toString() + ":\n" +
                    "TF outputScores: " + Arrays.toString(outputScores))
            val s = "SSVEP cPSDA\n [" + yTF.toString() + "]"
            runOnUiThread { mYfitTextView!!.text = s }
            mNumberOfClassifierCalls++
            executeWheelchairCommand(yTF)
        } else {
            Log.e(TAG, "[" + (mNumberOfClassifierCalls + 1).toString() + "] CALLING CLASSIFIER FUNCTION!")
            y = when (mSampleRate) {
                250 -> {
                    val getInstance1 = DoubleArray(mSampleRate * 2)
                    val getInstance2 = DoubleArray(mSampleRate * 2)
                    System.arraycopy(mCh1!!.classificationBuffer, mSampleRate * 2, getInstance1, 0, mSampleRate * 2) //8000→end
                    System.arraycopy(mCh2!!.classificationBuffer, mSampleRate * 2, getInstance2, 0, mSampleRate * 2)
                    mNativeInterface.jClassifySSVEP(getInstance1, getInstance2, 1.5)
                }
                else -> doubleArrayOf(-1.0, -1.0)
            }
            mNumberOfClassifierCalls++
            Log.e(TAG, "Classifier Output: [#" + mNumberOfClassifierCalls.toString() + "::" + y[0].toString() + "," + y[1].toString() + "]")
            val s = "SSVEP cPSDA\n: [" + y[1].toString() + "]"
            runOnUiThread { mYfitTextView!!.text = s }
            executeWheelchairCommand(y[1].toInt())
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_control)
        //Set orientation of device based on screen type/size:
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        //Receive Intents:
        val intent = intent
        deviceMacAddresses = intent.getStringArrayExtra(MainActivity.INTENT_DEVICES_KEY)
        val deviceDisplayNames = intent.getStringArrayExtra(MainActivity.INTENT_DEVICES_NAMES)
        val intentStimulusClass = intent.getStringArrayExtra(MainActivity.INTENT_DELAY_VALUE_SECONDS)
        if (intent.extras != null)
            mRunTrainingBool = intent.extras!!.getBoolean(MainActivity.INTENT_TRAIN_BOOLEAN)
        else
            Log.e(TAG, "ERROR: intent.getExtras = null")

        mStimulusDelaySeconds = Integer.valueOf(intentStimulusClass[0])!!.toDouble()
        mDeviceName = deviceDisplayNames[0]
        mDeviceAddress = deviceMacAddresses!![0]
        Log.d(TAG, "Device Names: " + Arrays.toString(deviceDisplayNames))
        Log.d(TAG, "Device MAC Addresses: " + Arrays.toString(deviceMacAddresses))
        Log.d(TAG, Arrays.toString(deviceMacAddresses))
        //Set up action bar:
        if (actionBar != null) {
            actionBar!!.setDisplayHomeAsUpEnabled(true)
        }
        val actionBar = actionBar
        actionBar!!.setBackgroundDrawable(ColorDrawable(Color.parseColor("#6078ef")))
        //Flag to keep screen on (stay-awake):
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        //Set up TextViews
        val mExportButton = findViewById<Button>(R.id.button_export)
        mBatteryLevel = findViewById(R.id.batteryText)
        mTrainingInstructions = findViewById(R.id.trainingInstructions)
        updateTrainingView(mRunTrainingBool)
        mDataRate = findViewById(R.id.dataRate)
        mDataRate!!.text = "..."
        mYfitTextView = findViewById(R.id.textViewYfit)
        val ab = getActionBar()
        ab!!.title = mDeviceName
        ab.subtitle = mDeviceAddress
        //Initialize Bluetooth
        if (!mBleInitializedBoolean) initializeBluetoothArray()
        mMediaBeep = MediaPlayer.create(this, R.raw.beep_01a)
        mSButton = findViewById(R.id.buttonS)
        mFButton = findViewById(R.id.buttonF)
        mLButton = findViewById(R.id.buttonL)
        mRButton = findViewById(R.id.buttonR)
        mReverseButton = findViewById(R.id.buttonReverse)
        val mTensorflowSwitch = findViewById<Switch>(R.id.tensorflowClassificationSwitch)
        changeUIElementVisibility(false)
        mLastTime = System.currentTimeMillis()
        mSSVEPClassTextView = findViewById(R.id.eegClassTextView)
        mOutputScoresNames = arrayOf(OUTPUT_DATA_FEED)

        //UI Listeners
        val toggleButton1 = findViewById<ToggleButton>(R.id.toggleButtonWheelchairControl)
        toggleButton1.setOnCheckedChangeListener { _, b ->
            mWheelchairControl = b
            executeWheelchairCommand(0)
            changeUIElementVisibility(b)
            mFilterData = b
            mPacketBuffer = if (b) {
                9
            } else {
                1
            }
            mCh1?.resetBuffers()
            mCh2?.resetBuffers()
        }
        mChannelSelect = findViewById(R.id.toggleButtonCh1)
        mChannelSelect!!.setOnCheckedChangeListener { _, b ->
            if (b) {
                mGraphAdapterCh2!!.clearPlot()
            } else {
                mGraphAdapterCh1!!.clearPlot()
            }
            mGraphAdapterCh1!!.plotData = b
            mGraphAdapterCh2!!.plotData = !b
        }
        mTensorflowSwitch.setOnCheckedChangeListener { _, b ->
            if (b) {
                showNoticeDialog()
            } else {
                //Reset counter:
                mTFRunModel = false
                mNumberOfClassifierCalls = 1
                Toast.makeText(applicationContext, "Using PSD Analysis", Toast.LENGTH_SHORT).show()
            }
        }
        mSButton!!.setOnClickListener {
            executeWheelchairCommand(0)
        }
        mFButton!!.setOnClickListener {
            //            executeWheelchairCommand(1)
            executeWheelchairCommand(4)
        }
        mLButton!!.setOnClickListener {
            //            executeWheelchairCommand(2)
            executeWheelchairCommand(3)
        }
        mRButton!!.setOnClickListener {
            executeWheelchairCommand(2)
//            executeWheelchairCommand(3)
        }
        mReverseButton!!.setOnClickListener {
            //            executeWheelchairCommand(4)
            executeWheelchairCommand(0)
        }
        mExportButton.setOnClickListener { exportData() }
        writeNewSettings.setOnClickListener {
            //only read 2-ch datas
            val bytes = ADS1299_DEFAULT_BYTE_CONFIG
            writeNewADS1299Settings(bytes)
        }
        mARService = intent.getParcelableExtra(MainActivity.EXTRA_DRONE_SERVICE)
        if (mARService != null) {
            mJSDrone = JSDrone(this, mARService!!)
            mJSDrone?.addListener(mJSListener)
            connectDrone()
            setAudioState(AudioState.MUTE)
        }
    }

    private fun showNoticeDialog() {
        val dialog = TensorflowOptionsMenu()
        dialog.show(fragmentManager, "TFOM")
    }

    @Override
    override fun onTensorflowOptionsClick(integerValue: Int) {
        enableTensorFlowModel(File(MODEL_FILENAME), integerValue)
    }

    private fun enableTensorFlowModel(embeddedModel: File, integerValue: Int) {
        mTensorflowSolutionIndex = integerValue
        val customModelPath = Environment.getExternalStorageDirectory().absolutePath + "/Download/tensorflow_assets/ssvep_final/"
        // Hard-coded Strings of Model Names
        // NOTE: Zero index is an empty string (no model)
        val customModel = arrayOf("",
                "CSM128_opt_CNN-1-a.parametricrelu.[0.1]-drop0.5-fc.1024.relu-lr.1e-3-k.[5]",
                "CSM192_opt_CNN-1-a.parametricrelu.[0.1]-drop0.5-fc.1024.relu-lr.1e-3-k.[5]",
                "CSM256_opt_CNN-1-a.parametricrelu.[0.1]-drop0.5-fc.1024.relu-lr.1e-3-k.[5]",
                "CSM384_opt_CNN-1-a.parametricrelu.[0.1]-drop0.5-fc.1024.relu-lr.1e-3-k.[5]",
                "CSM512_opt_CNN-1-a.parametricrelu.[0.1]-drop0.5-fc.1024.relu-lr.1e-3-k.[5]",
                "TD128_opt_CNN-4-a.parametricrelu.[0.75, 0.5, 0.25, 0.1]-drop0.5-fc.1024.relu-lr.1e-3-k.[50, 25, 12, 5]",
                "TD192_opt_CNN-4-a.parametricrelu.[0.75, 0.5, 0.25, 0.1]-drop0.5-fc.1024.relu-lr.1e-3-k.[50, 25, 12, 5]",
                "TD256_opt_CNN-4-a.parametricrelu.[0.75, 0.5, 0.25, 0.1]-drop0.5-fc.1024.relu-lr.1e-3-k.[50, 25, 12, 5]",
                "TD384_opt_CNN-4-a.parametricrelu.[0.75, 0.5, 0.25, 0.1]-drop0.5-fc.1024.relu-lr.1e-3-k.[50, 25, 12, 5]",
                "TD512_opt_CNN-4-a.parametricrelu.[0.75, 0.5, 0.25, 0.01]-drop0.5-fc.1024.relu-lr.1e-3-k.[50, 25, 12, 5]")

        val tensorflowModelLocation = customModelPath + customModel[integerValue] + ".pb"
        for (s in customModel) {
            val modelFolderPath = "$customModelPath$s.pb"
            Log.e(TAG, "Model " + modelFolderPath + " exists? " + File(modelFolderPath).exists().toString())
        }
        mTensorflowWindowSize = when (integerValue) {
            1, 6 -> 128
            2, 7 -> 192
            3, 8 -> 256
            4, 9 -> 384
            5, 10 -> 512
            else -> 256
        }
        mTensorflowXDim = when (integerValue) {
            in 1..5 -> 3 // CSM
            else -> 2 // Anything else (T-D, PSD)
        }
        mTensorflowYDim = when (integerValue) {
            in 1..3 -> 64
            4, 5 -> 128
            else -> mTensorflowWindowSize
        }
        Log.e(TAG, "Input Length: 2x" + mTensorflowWindowSize + " Output = " + mTensorflowXDim + "x" + mTensorflowYDim)
        when {
            File(tensorflowModelLocation).exists() -> {
                mTFInferenceInterface = TensorFlowInferenceInterface(assets, tensorflowModelLocation)
                //Reset counter:
                mNumberOfClassifierCalls = 1
                mTFRunModel = true
                Log.i(TAG, "Tensorflow: customModel loaded")
            }
            embeddedModel.exists() -> { //Check if there's a model included:
                tensorflowClassificationSwitch.isChecked = false
                mTFRunModel = false
                Toast.makeText(applicationContext, "No TF Model Found!", Toast.LENGTH_LONG).show()
            }
            else -> { // No model found, continuing with original (reset switch)
                tensorflowClassificationSwitch.isChecked = false
                mTFRunModel = false
                Toast.makeText(applicationContext, "No TF Model Found!", Toast.LENGTH_LONG).show()
            }
        }
        if (mTFRunModel) {
            Toast.makeText(applicationContext, "TF Model Loaded", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportData() {
        try {
            terminateDataFileWriter()
        } catch (e: IOException) {
            Log.e(TAG, "IOException in saveDataFile")
            e.printStackTrace()
        }

        val context = applicationContext
        val uii = FileProvider.getUriForFile(context, context.packageName + ".provider", mPrimarySaveDataFile!!.file)
        val exportData = Intent(Intent.ACTION_SEND)
        exportData.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        exportData.putExtra(Intent.EXTRA_SUBJECT, "Sensor Data Export Details")
        exportData.putExtra(Intent.EXTRA_STREAM, uii)
        exportData.type = "text/html"
        startActivity(exportData)
    }

    @Throws(IOException::class)
    private fun terminateDataFileWriter() {
        mPrimarySaveDataFile?.terminateDataFileWriter()
    }

    public override fun onResume() {
        mNativeInterface.jmainInitialization(false)
        if (mRedrawer != null) {
            mRedrawer!!.start()
        }
        super.onResume()
    }

    override fun onPause() {
        if (mRedrawer != null) mRedrawer!!.pause()
        super.onPause()
    }

    private fun initializeBluetoothArray() {
        val mBluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val mBluetoothDeviceArray = arrayOfNulls<BluetoothDevice>(deviceMacAddresses!!.size)
        Log.d(TAG, "Device Addresses: " + Arrays.toString(deviceMacAddresses))
        if (deviceMacAddresses != null) {
            for (i in deviceMacAddresses!!.indices) {
                mBluetoothDeviceArray[i] = mBluetoothManager.adapter.getRemoteDevice(deviceMacAddresses!![i])
            }
        } else {
            Log.e(TAG, "No Devices Queued, Restart!")
            Toast.makeText(this, "No Devices Queued, Restart!", Toast.LENGTH_SHORT).show()
        }
        mActBle = ActBle(this, mBluetoothManager, this)
        mBluetoothGattArray = Array(deviceMacAddresses!!.size, { i -> mActBle!!.connect(mBluetoothDeviceArray[i]) })
        for (i in mBluetoothDeviceArray.indices) {
            Log.e(TAG, "Connecting to Device: Name: " + (mBluetoothDeviceArray[i]!!.name + " \nMAC:" + mBluetoothDeviceArray[i]!!.address))
            if ("WheelchairControl" == mBluetoothDeviceArray[i]!!.name) {
                mWheelchairGattIndex = i
                Log.e(TAG, "mWheelchairGattIndex: " + mWheelchairGattIndex)
                continue //we are done initializing
            } else {
                mEEGConfigGattIndex = i
            }

            val btDeviceName = mBluetoothDeviceArray[i]?.name?.toLowerCase()
            mMSBFirst = when {
                btDeviceName == null -> false
                btDeviceName.contains("EMG 250Hz") -> false
                btDeviceName.contains("nrf52") -> true
                else -> false
            }
            mSampleRate = when {
                btDeviceName == null -> 250
                btDeviceName.contains("8k") -> 8000
                btDeviceName.contains("4k") -> 4000
                btDeviceName.contains("2k") -> 2000
                btDeviceName.contains("1k") -> 1000
                btDeviceName.contains("500") -> 500
                else -> 250
            }
            mPacketBuffer = mSampleRate / 250
            Log.e(TAG, "mSampleRate: " + mSampleRate + "Hz")
            fPSD = mNativeInterface.jLoadfPSD(mSampleRate, mWindowLength)
            Log.d(TAG, "initializeBluetoothArray: jLoadfPSD: " + fPSD!!.size.toString())
            if (!mGraphInitializedBoolean) setupGraph()

            mGraphAdapterCh1!!.setxAxisIncrementFromSampleRate(mSampleRate)
            mGraphAdapterCh2!!.setxAxisIncrementFromSampleRate(mSampleRate)

            mGraphAdapterCh1!!.setSeriesHistoryDataPoints(250 * 5)
            mGraphAdapterCh2!!.setSeriesHistoryDataPoints(250 * 5)
            val fileNameTimeStamped = "EEG_SSVEPData_" + timeStamp + "_" + mSampleRate.toString() + "Hz"
            Log.e(TAG, "fileTimeStamp: " + fileNameTimeStamped)
            try {
                mPrimarySaveDataFile = SaveDataFile("/EEGData", fileNameTimeStamped,
                        24, 1.toDouble() / mSampleRate)
            } catch (e: IOException) {
                Log.e(TAG, "initializeBluetoothArray: IOException", e)
            }

        }
        mBleInitializedBoolean = true
    }

    private fun setupGraph() {
        // Initialize our XYPlot reference:
        mGraphAdapterCh1 = GraphAdapter(mSampleRate * 4, "EEG Data Ch 1", false, Color.BLUE) //Color.parseColor("#19B52C") also, RED, BLUE, etc.
        mGraphAdapterCh2 = GraphAdapter(mSampleRate * 4, "EEG Data Ch 2", false, Color.RED) //Color.parseColor("#19B52C") also, RED, BLUE, etc.
        mGraphAdapterCh1PSDA = GraphAdapter(mPSDDataPointsToShow, "EEG Power Spectrum (Ch1)", false, Color.BLUE)
        mGraphAdapterCh2PSDA = GraphAdapter(mPSDDataPointsToShow, "EEG Power Spectrum (Ch2)", false, Color.RED)
        //PLOT CH1 By default
        mGraphAdapterCh1!!.plotData = true
        mGraphAdapterCh1PSDA!!.plotData = true
        mGraphAdapterCh2PSDA!!.plotData = true
        mGraphAdapterCh1!!.setPointWidth(2.toFloat())
        mGraphAdapterCh2!!.setPointWidth(2.toFloat())
        mGraphAdapterCh1PSDA!!.setPointWidth(2.toFloat())
        mGraphAdapterCh2PSDA!!.setPointWidth(2.toFloat())
        mTimeDomainPlotAdapter = XYPlotAdapter(findViewById(R.id.eegTimeDomainXYPlot), false, 1000)
        if (mTimeDomainPlotAdapter!!.xyPlot != null) {
            mTimeDomainPlotAdapter!!.xyPlot!!.addSeries(mGraphAdapterCh1!!.series, mGraphAdapterCh1!!.lineAndPointFormatter)
            mTimeDomainPlotAdapter!!.xyPlot!!.addSeries(mGraphAdapterCh2!!.series, mGraphAdapterCh2!!.lineAndPointFormatter)
        }
        mFreqDomainPlotAdapter = XYPlotAdapter(findViewById(R.id.frequencyAnalysisXYPlot), "Frequency (Hz)", "Power Density (W/Hz)", mSampleRate.toDouble() / 125.0)
        if (mFreqDomainPlotAdapter!!.xyPlot != null) {
            mFreqDomainPlotAdapter!!.xyPlot!!.addSeries(mGraphAdapterCh1PSDA!!.series, mGraphAdapterCh1PSDA!!.lineAndPointFormatter)
            mFreqDomainPlotAdapter!!.xyPlot!!.addSeries(mGraphAdapterCh2PSDA!!.series, mGraphAdapterCh2PSDA!!.lineAndPointFormatter)
        }
        val xyPlotList = listOf(mTimeDomainPlotAdapter!!.xyPlot, mFreqDomainPlotAdapter!!.xyPlot)
        mRedrawer = Redrawer(xyPlotList, 30f, false)
        mRedrawer!!.start()
        mGraphInitializedBoolean = true
    }

    private fun setNameAddress(name_action: String?, address_action: String?) {
        val name = menu!!.findItem(R.id.action_title)
        val address = menu!!.findItem(R.id.action_address)
        name.title = name_action
        address.title = address_action
        invalidateOptionsMenu()
    }

    private fun connectDrone(): Boolean {
        // show a loading view while the JumpingSumo drone is connecting
        if (mJSDrone != null && ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_RUNNING != mJSDrone!!.connectionState) {
            mConnectionProgressDialog = ProgressDialog(this, R.style.AppCompatAlertDialogStyle)
            mConnectionProgressDialog!!.isIndeterminate = true
            mConnectionProgressDialog!!.setMessage("Connecting ...")
            mConnectionProgressDialog!!.show()

            // if the connection to the Jumping fails, finish the activity
            if (!mJSDrone!!.connect()) {
                Toast.makeText(applicationContext, "Failed to Connect Drone", Toast.LENGTH_LONG).show()
                finish()
                return false
            }
            return true
        } else {
            return false
        }
    }

    override fun onDestroy() {
        mRedrawer?.finish()
        mJSDrone?.dispose()
        disconnectAllBLE()
        try {
            terminateDataFileWriter()
        } catch (e: IOException) {
            Log.e(TAG, "IOException in saveDataFile")
            e.printStackTrace()
        }

        stopMonitoringRssiValue()
        mNativeInterface.jmainInitialization(true) //Just a technicality, doesn't actually do anything
        super.onDestroy()
    }

    private fun disconnectAllBLE() {
        if (mActBle != null) {
            for (bluetoothGatt in mBluetoothGattArray) {
                mActBle!!.disconnect(bluetoothGatt!!)
                mConnected = false
                resetMenuBar()
            }
        }
    }

    private fun resetMenuBar() {
        runOnUiThread {
            if (menu != null) {
                menu!!.findItem(R.id.menu_connect).isVisible = true
                menu!!.findItem(R.id.menu_disconnect).isVisible = false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_device_control, menu)
        menuInflater.inflate(R.menu.actionbar_item, menu)
        if (mConnected) {
            menu.findItem(R.id.menu_connect).isVisible = false
            menu.findItem(R.id.menu_disconnect).isVisible = true
        } else {
            menu.findItem(R.id.menu_connect).isVisible = true
            menu.findItem(R.id.menu_disconnect).isVisible = false
        }
        this.menu = menu
        setNameAddress(mDeviceName, mDeviceAddress)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_connect -> {
                if (mActBle != null) {
                    initializeBluetoothArray()
                }
                connect()
                return true
            }
            R.id.menu_disconnect -> {
                if (mActBle != null) {
                    disconnectAllBLE()
                }
                return true
            }
            android.R.id.home -> {
                if (mActBle != null) {
                    disconnectAllBLE()
                }
                NavUtils.navigateUpFromSameTask(this)
                onBackPressed()
                return true
            }
            R.id.action_settings -> {
                launchSettingsMenu()
                return true
            }
            R.id.action_export -> {
                exportData()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1) {
            val context = applicationContext
            //UI Stuff:
            val chSel = PreferencesFragment.channelSelect(context)
            val longPSDA = PreferencesFragment.psdaWideRange(context)
            val showPSDA = PreferencesFragment.showPSDA(context)
            val showUIElements = PreferencesFragment.showUIElements(context)
            //File Save Stuff
            val saveTimestamps = PreferencesFragment.saveTimestamps(context)
            val precision = (if (PreferencesFragment.setBitPrecision(context)) 64 else 32).toShort()
            val saveClass = PreferencesFragment.saveClass(context)
            mPrimarySaveDataFile!!.setSaveTimestamps(saveTimestamps)
            mPrimarySaveDataFile!!.setFpPrecision(precision)
            mPrimarySaveDataFile!!.setIncludeClass(saveClass)
            val filterData = PreferencesFragment.setFilterData(context)
            //TODO: for now just ch1:
            if (mGraphAdapterCh1 != null) {
                mFilterData = filterData
            }
            /**
             * Settings for ADS1299:
             */
            val registerConfigBytes = Arrays.copyOf(ADS1299_DEFAULT_BYTE_CONFIG, ADS1299_DEFAULT_BYTE_CONFIG.size)
            when (PreferencesFragment.setSampleRate(context)) {
            // TODO: Change response to 6, 5, 4, 3, 2
            // TODO: we can do rCB[0] = (0x90 + srateInt).toByte()
                0 -> {
                    registerConfigBytes[0] = 0x96.toByte()
                }
                1 -> {
                    registerConfigBytes[0] = 0x95.toByte()
                }
                2 -> {
                    registerConfigBytes[0] = 0x94.toByte()
                }
                3 -> {
                    registerConfigBytes[0] = 0x93.toByte()
                }
                4 -> {
                    registerConfigBytes[0] = 0x92.toByte()
                }
            }
            val numChEnabled = PreferencesFragment.setNumberChannelsEnabled(context)
            Log.e(TAG, "numChEnabled: " + numChEnabled.toString())
            // Set BIAS Muxes:
            if (PreferencesFragment.setSensP(context)) { //Sets Four LSBs to 1 if chEn
                registerConfigBytes[12] = ((1 shl numChEnabled) - 1).toByte()
            } else {
                registerConfigBytes[12] = 0b0000_0000 // Turn off BIAS_SENSP
            }
            if (PreferencesFragment.setSensN(context)) {
                registerConfigBytes[13] = ((1 shl numChEnabled) - 1).toByte()
            } else {
                registerConfigBytes[13] = 0b0000_0000
            }
            // Set all to disable.
            for (i in 4..7) registerConfigBytes[i] = 0xE1.toByte()
            // Enable Selection
            for (i in 4..(3 + numChEnabled)) {
                registerConfigBytes[i] = 0x00.toByte()
            }
            val gain12 = PreferencesFragment.setGainCh12(context)
            // Set gain for chs if enabled
            for (i in 4..7) { //Checks first bit enabled on chs 1 & 2.
                registerConfigBytes[i] = when (registerConfigBytes[i] and 0x80.toByte()) {
                    0x80.toByte() -> registerConfigBytes[i] // do nothing if ch disabled
                    else -> registerConfigBytes[i] or (gain12 shl 4).toByte() // Shift gain into 0xxx_0000 position
                }
            }
            if (PreferencesFragment.setSRB1(context)) {
                registerConfigBytes[20] = 0x20.toByte()
            } else {
                registerConfigBytes[20] = 0x00.toByte()
            }

            Log.e(TAG, "SettingsNew: " + DataChannel.byteArrayToHexString(registerConfigBytes))
            writeNewADS1299Settings(registerConfigBytes)

            mGraphAdapterCh1PSDA!!.plotData = showPSDA
            mGraphAdapterCh2PSDA!!.plotData = showPSDA
            mFreqDomainPlotAdapter!!.setXyPlotVisibility(showPSDA)
            mFreqDomainPlotAdapter!!.xyPlot?.redraw()
            mTimeDomainPlotAdapter!!.xyPlot?.redraw()
            mChannelSelect!!.isChecked = chSel
            mGraphAdapterCh1!!.plotData = chSel
            mGraphAdapterCh2!!.plotData = !chSel
            if (longPSDA) {
                fPSDStartIndex = 0
                fPSDEndIndex = 255//120 == ~56Hz
            } else {
                fPSDStartIndex = 16
                fPSDEndIndex = 44
            }
            mWheelchairControl = showUIElements
            executeWheelchairCommand(0)
            changeUIElementVisibility(showUIElements)
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun writeNewADS1299Settings(bytes: ByteArray) {
        Log.e(TAG, "bytesOriginal: " + DataChannel.byteArrayToHexString(ADS1299_DEFAULT_BYTE_CONFIG))
        if (mEEGConfigGattService != null) {
            Log.e(TAG, "SendingCommand (byte): " + DataChannel.byteArrayToHexString(bytes))
            mActBle!!.writeCharacteristic(mBluetoothGattArray[mEEGConfigGattIndex]!!, mEEGConfigGattService!!.getCharacteristic(AppConstant.CHAR_EEG_CONFIG), bytes)
            //Should notify/update after writing
        }
    }

    private fun launchSettingsMenu() {
        val intent = Intent(applicationContext, SettingsActivity::class.java)
        startActivityForResult(intent, 1)
    }

    private fun connect() {
        runOnUiThread {
            val menuItem = menu!!.findItem(R.id.action_status)
            menuItem.title = "Connecting..."
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        Log.i(TAG, "onServicesDiscovered")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            for (service in gatt.services) {
                if (service == null || service.uuid == null) {
                    continue
                }
                if (AppConstant.SERVICE_DEVICE_INFO == service.uuid) {
                    //Read the device serial number (if available)
                    if (service.getCharacteristic(AppConstant.CHAR_SERIAL_NUMBER) != null) {
                        mActBle!!.readCharacteristic(gatt, service.getCharacteristic(AppConstant.CHAR_SERIAL_NUMBER))
                    }
                    //Read the device software version (if available)
                    if (service.getCharacteristic(AppConstant.CHAR_SOFTWARE_REV) != null) {
                        mActBle!!.readCharacteristic(gatt, service.getCharacteristic(AppConstant.CHAR_SOFTWARE_REV))
                    }
                }
                if (AppConstant.SERVICE_WHEELCHAIR_CONTROL == service.uuid) {
                    mLedWheelchairControlService = service
                    Log.i(TAG, "BLE Wheelchair Control Service found")
                }

                if (AppConstant.SERVICE_EEG_SIGNAL == service.uuid) {
                    if (service.getCharacteristic(AppConstant.CHAR_EEG_CONFIG) != null) {
                        mEEGConfigGattService = service
                        mActBle!!.readCharacteristic(gatt, service.getCharacteristic(AppConstant.CHAR_EEG_CONFIG))
                        mActBle!!.setCharacteristicNotifications(gatt, service.getCharacteristic(AppConstant.CHAR_EEG_CONFIG), true)
                    }

                    if (service.getCharacteristic(AppConstant.CHAR_EEG_CH1_SIGNAL) != null) {
                        mActBle!!.setCharacteristicNotifications(gatt, service.getCharacteristic(AppConstant.CHAR_EEG_CH1_SIGNAL), true)
                        if (mCh1 == null) mCh1 = DataChannel(false, mMSBFirst, 4 * mSampleRate)
                    }
                    if (service.getCharacteristic(AppConstant.CHAR_EEG_CH2_SIGNAL) != null) {
                        mActBle!!.setCharacteristicNotifications(gatt, service.getCharacteristic(AppConstant.CHAR_EEG_CH2_SIGNAL), true)
                        if (mCh2 == null) mCh2 = DataChannel(false, mMSBFirst, 4 * mSampleRate)
                    }
                    if (service.getCharacteristic(AppConstant.CHAR_EEG_CH3_SIGNAL) != null)
                        mActBle!!.setCharacteristicNotifications(gatt, service.getCharacteristic(AppConstant.CHAR_EEG_CH3_SIGNAL), true)
                    if (service.getCharacteristic(AppConstant.CHAR_EEG_CH4_SIGNAL) != null)
                        mActBle!!.setCharacteristicNotifications(gatt, service.getCharacteristic(AppConstant.CHAR_EEG_CH4_SIGNAL), true)
                    if (service.getCharacteristic(AppConstant.CHAR_EEG_CH5_SIGNAL) != null)
                        mActBle!!.setCharacteristicNotifications(gatt, service.getCharacteristic(AppConstant.CHAR_EEG_CH5_SIGNAL), true)
                    if (service.getCharacteristic(AppConstant.CHAR_EEG_CH6_SIGNAL) != null)
                        mActBle!!.setCharacteristicNotifications(gatt, service.getCharacteristic(AppConstant.CHAR_EEG_CH6_SIGNAL), true)
                    if (service.getCharacteristic(AppConstant.CHAR_EEG_CH7_SIGNAL) != null)
                        mActBle!!.setCharacteristicNotifications(gatt, service.getCharacteristic(AppConstant.CHAR_EEG_CH7_SIGNAL), true)
                    if (service.getCharacteristic(AppConstant.CHAR_EEG_CH8_SIGNAL) != null)
                        mActBle!!.setCharacteristicNotifications(gatt, service.getCharacteristic(AppConstant.CHAR_EEG_CH8_SIGNAL), true)
                }

                if (AppConstant.SERVICE_BATTERY_LEVEL == service.uuid) { //Read the device battery percentage
                    mActBle!!.readCharacteristic(gatt, service.getCharacteristic(AppConstant.CHAR_BATTERY_LEVEL))
                    mActBle!!.setCharacteristicNotifications(gatt, service.getCharacteristic(AppConstant.CHAR_BATTERY_LEVEL), true)
                }
            }
            //Run process only once:
            mActBle?.runProcess()
        }
    }

    private fun changeUIElementVisibility(visible: Boolean) {
        val viewVisibility = if (visible) View.VISIBLE else View.INVISIBLE
        runOnUiThread {
            mSButton!!.visibility = viewVisibility
            mFButton!!.visibility = viewVisibility
            mLButton!!.visibility = viewVisibility
            mRButton!!.visibility = viewVisibility
            mReverseButton!!.visibility = viewVisibility
        }
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        Log.i(TAG, "onCharacteristicRead")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (AppConstant.CHAR_BATTERY_LEVEL == characteristic.uuid) {
                if (characteristic.value != null) {
                    val batteryLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                    updateBatteryStatus(batteryLevel)
                    Log.i(TAG, "Battery Level :: " + batteryLevel)
                }
            }
            //TODO: NEED TO CHANGE mSampleRate, DataChannel[], and GraphAdapter[] here.
            if (AppConstant.CHAR_EEG_CONFIG == characteristic.uuid) {
                if (characteristic.value != null) {
                    val readValue = characteristic.value
                    Log.e(TAG, "onCharacteriticRead: \n" +
                            "CHAR_EEG_CONFIG: " + DataChannel.byteArrayToHexString(readValue))
                }
            }
        } else {
            Log.e(TAG, "onCharacteristic Read Error" + status)
        }
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (AppConstant.CHAR_EEG_CONFIG == characteristic.uuid) {
            if (characteristic.value != null) {
                val readValue = characteristic.value
                Log.e(TAG, "onCharacteriticChanged: \n" +
                        "CHAR_EEG_CONFIG: " + DataChannel.byteArrayToHexString(readValue))
                when (readValue[0] and 0x0F.toByte()) {
                    0x06.toByte() -> mSampleRate = 250
                    0x05.toByte() -> mSampleRate = 500
                    0x04.toByte() -> mSampleRate = 1000
                    0x03.toByte() -> mSampleRate = 2000
                    0x02.toByte() -> mSampleRate = 4000
                }
                //RESET mCH1 & mCH2:
                mCh1?.classificationBufferSize = 4 * mSampleRate
                mCh2?.classificationBufferSize = 4 * mSampleRate
                Log.e(TAG, "Updated Sample Rate: " + mSampleRate.toString())
            }
        }

        if (AppConstant.CHAR_BATTERY_LEVEL == characteristic.uuid) {
            val batteryLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)!!
            updateBatteryStatus(batteryLevel)
        }

        if (AppConstant.CHAR_EEG_CH1_SIGNAL == characteristic.uuid) {
            val mNewEEGdataBytes = characteristic.value
            if (!mCh1!!.chEnabled) {
                mCh1!!.chEnabled = true
            }
            getDataRateBytes(mNewEEGdataBytes.size)
            if (mEEGConnectedAllChannels) {
                mCh1!!.handleNewData(mNewEEGdataBytes)
                if (mCh1!!.packetCounter.toInt() == mPacketBuffer) {
                    addToGraphBuffer(mCh1!!, mGraphAdapterCh1, true)
                }
            }
        }

        if (AppConstant.CHAR_EEG_CH2_SIGNAL == characteristic.uuid) {
            if (!mCh2!!.chEnabled) {
                mCh2!!.chEnabled = true
            }
            val mNewEEGdataBytes = characteristic.value
            val byteLength = mNewEEGdataBytes.size
            getDataRateBytes(byteLength)
            if (mEEGConnectedAllChannels) {
                mCh2!!.handleNewData(mNewEEGdataBytes)
                if (mCh2!!.packetCounter.toInt() == mPacketBuffer) {
                    addToGraphBuffer(mCh2!!, mGraphAdapterCh2, false)
                }
            }
        }

        if (AppConstant.CHAR_EEG_CH3_SIGNAL == characteristic.uuid) {
            getDataRateBytes(characteristic.value.size)
        }

        if (AppConstant.CHAR_EEG_CH4_SIGNAL == characteristic.uuid) {
            getDataRateBytes(characteristic.value.size)
        }

        if (AppConstant.CHAR_EEG_CH5_SIGNAL == characteristic.uuid) {
            getDataRateBytes(characteristic.value.size)
        }

        if (AppConstant.CHAR_EEG_CH6_SIGNAL == characteristic.uuid) {
            getDataRateBytes(characteristic.value.size)
        }

        if (AppConstant.CHAR_EEG_CH7_SIGNAL == characteristic.uuid) {
            getDataRateBytes(characteristic.value.size)
        }

        if (AppConstant.CHAR_EEG_CH8_SIGNAL == characteristic.uuid) {
            getDataRateBytes(characteristic.value.size)
        }

        if (mCh1!!.chEnabled && mCh2!!.chEnabled) {
            mNumber2ChPackets++
            mEEGConnectedAllChannels = true
            mCh1!!.chEnabled = false
            mCh2!!.chEnabled = false
            if (mCh1!!.characteristicDataPacketBytes != null && mCh2!!.characteristicDataPacketBytes != null) {
                mPrimarySaveDataFile!!.writeToDisk(mCh1!!.characteristicDataPacketBytes, mCh2!!.characteristicDataPacketBytes)
            }
            if (mNumber2ChPackets % 20 == 0) { //Every x * 20 data points
                val classifyTaskThread = Thread(mClassifyThread)
                classifyTaskThread.start()
            }
            if (mNumber2ChPackets % 3 == 0) {
                val powerSpectrumThreadTask = Thread(mPowerSpectrumRunnableThread)
                powerSpectrumThreadTask.start()
            }
        }

        runOnUiThread {
            val concat = "C:[$mSSVEPClass]"
            mSSVEPClassTextView!!.text = concat
        }
    }

    private fun addToGraphBuffer(dataChannel: DataChannel, graphAdapter: GraphAdapter?, updateTrainingRoutine: Boolean) {
//        Log.e(TAG, "dataChannel.dataBuffer!!.size: " + dataChannel.dataBuffer?.size)
        if (mFilterData && dataChannel.totalDataPointsReceived > 250) {
            val filteredData = mNativeInterface.jSSVEPCfilter(dataChannel.classificationBuffer)
            graphAdapter!!.clearPlot()

            for (i in filteredData.indices) { // gA.addDataPointTimeDomain(y,x)
                graphAdapter.addDataPointTimeDomainAlt(filteredData[i].toDouble(),
                        dataChannel.totalDataPointsReceived - 999 + i)
            }
        } else {
            if (dataChannel.dataBuffer != null) {
                if (mPrimarySaveDataFile!!.resolutionBits == 24) {
                    var i = 0
                    while (i < dataChannel.dataBuffer!!.size / 3) {
                        graphAdapter!!.addDataPointTimeDomain(DataChannel.bytesToDouble(dataChannel.dataBuffer!![3 * i],
                                dataChannel.dataBuffer!![3 * i + 1], dataChannel.dataBuffer!![3 * i + 2]),
                                dataChannel.totalDataPointsReceived - dataChannel.dataBuffer!!.size / 3 + i)
                        if (updateTrainingRoutine) {
                            for (j in 0 until graphAdapter.sampleRate / 250) {
                                updateTrainingRoutine(dataChannel.totalDataPointsReceived - dataChannel.dataBuffer!!.size / 3 + i + j)
                            }
                        }
                        i += graphAdapter.sampleRate / 250
                    }
                } else if (mPrimarySaveDataFile!!.resolutionBits == 16) {
                    var i = 0
                    while (i < dataChannel.dataBuffer!!.size / 2) {
                        graphAdapter!!.addDataPointTimeDomain(DataChannel.bytesToDouble(dataChannel.dataBuffer!![2 * i],
                                dataChannel.dataBuffer!![2 * i + 1]),
                                dataChannel.totalDataPointsReceived - dataChannel.dataBuffer!!.size / 2 + i)
                        if (updateTrainingRoutine) {
                            for (j in 0 until graphAdapter.sampleRate / 250) {
                                updateTrainingRoutine(dataChannel.totalDataPointsReceived - dataChannel.dataBuffer!!.size / 2 + i + j)
                            }
                        }
                        i += graphAdapter.sampleRate / 250
                    }
                }
            }
        }

        dataChannel.resetBuffers()
    }

    private val mPowerSpectrumRunnableThread = Runnable {
        runPowerSpectrum()
    }

    private fun updateTrainingRoutine(dataPoints: Int) {
        if (dataPoints % mSampleRate == 0 && mRunTrainingBool) {
            val second = dataPoints / mSampleRate
            val mSDS = mStimulusDelaySeconds.toInt()
            Log.d(TAG, "mSDS:" + mSDS.toString() + " second: " + second.toString())
            if (second % mSDS == 0) mMediaBeep.start()
            when {
                (second < mSDS) -> {
                    updateTrainingPromptColor(Color.GREEN)
                    mSSVEPClass = 0.0
                    updateTrainingPrompt("EYES CLOSED")
                }
                (second == mSDS) -> {
                    mSSVEPClass = 1.0
                    updateTrainingPrompt("11.1Hz")
                }
                (second == 2 * mSDS) -> {
                    mSSVEPClass = 2.0
                    updateTrainingPrompt("12.5hz")
                }
                (second == 3 * mSDS) -> {
                    mSSVEPClass = 3.0
                    updateTrainingPrompt("15.2Hz")
                }
                (second == 4 * mSDS) -> {
                    mSSVEPClass = 4.0
                    updateTrainingPrompt("16.7Hz")
                }
                (second == 5 * mSDS) -> {
                    mSSVEPClass = 0.0
                    updateTrainingPrompt("Stop!")
                    updateTrainingPromptColor(Color.RED)
                    disconnectAllBLE()
//                    mSSVEPClass = 5.0
                }
                (second == 6 * mSDS) -> {
                }
            }
        }
    }

    private fun updateTrainingPrompt(prompt: String) {
        runOnUiThread {
            if (mRunTrainingBool) {
                mTrainingInstructions!!.text = prompt
            }
        }
    }

    private fun updateTrainingView(b: Boolean) {
        val visibility = if (b) View.VISIBLE else View.GONE
        runOnUiThread { mTrainingInstructions!!.visibility = visibility }
    }

    private fun updateTrainingPromptColor(color: Int) {
        runOnUiThread {
            if (mRunTrainingBool) {
                mTrainingInstructions!!.setTextColor(color)
            }
        }
    }

    private fun executeDroneCommand(command: Int) {
//        mJSDrone?.setTurn(0.toByte())
//        mJSDrone?.setSpeed(0.toByte())
//        mJSDrone?.setFlag(0.toByte())
        if (mJSDrone != null && mWheelchairControl) {
            Log.e(TAG, "SendingCommand: " + command.toString())
            //Original:
//            when (command) {
//                0 -> { //Do Nothing
//                    mJSDrone?.setTurn(0.toByte())
//                    mJSDrone?.setSpeed(0.toByte())
//                    mJSDrone?.setFlag(0.toByte())
//                }
//                1 -> {//FWD:
//                    mJSDrone?.setSpeed(mJSDroneSpeedFWREV.toByte())
//                    mJSDrone?.setFlag(1.toByte())
//                }
//                2 -> { // LEFT
//                    mJSDrone?.setTurn((-mJSDroneSpeedLR).toByte())
//                    mJSDrone?.setFlag(1.toByte())
//                }
//                3 -> { // RIGHT
//                    mJSDrone?.setTurn((mJSDroneSpeedLR).toByte())
//                    mJSDrone?.setFlag(1.toByte())
//                }
//                4 -> {
//                    // REVERSE:
//                    mJSDrone?.setSpeed((-20).toByte())
//                    mJSDrone?.setFlag(1.toByte())
//                }
//                else -> {
//                    mJSDrone?.setTurn(0.toByte())
//                    mJSDrone?.setSpeed(0.toByte())
//                    mJSDrone?.setFlag(0.toByte())
//                }
//            }
            when (command) {
                0 -> { //Do Nothing
                    mJSDrone?.setTurn(0.toByte())
                    mJSDrone?.setSpeed(0.toByte())
                    mJSDrone?.setFlag(0.toByte())
                }
                1 -> {//Do Nothing
                    mJSDrone?.setTurn(0.toByte())
                    mJSDrone?.setSpeed(0.toByte())
                    mJSDrone?.setFlag(0.toByte())
                }
                2 -> { // RIGHT
                    mJSDrone?.setSpeed(0.toByte())
                    mJSDrone?.setTurn((mJSDroneSpeedLR).toByte())
                    mJSDrone?.setFlag(1.toByte())
                }
                3 -> { // LEFT
                    mJSDrone?.setSpeed(0.toByte())
                    mJSDrone?.setTurn((-mJSDroneSpeedLR).toByte())
                    mJSDrone?.setFlag(1.toByte())
                }
                4 -> {//FWD:
                    mJSDrone?.setSpeed(mJSDroneSpeedFWREV.toByte())
                    mJSDrone?.setTurn(0.toByte())
                    mJSDrone?.setFlag(1.toByte())
                }
                else -> {
                    mJSDrone?.setTurn(0.toByte())
                    mJSDrone?.setSpeed(0.toByte())
                    mJSDrone?.setFlag(0.toByte())
                }
            }
        }
    }

    private fun executeWheelchairCommand(command: Int) {
        //Pass command onto drone:
        executeDroneCommand(command)
        val bytes = ByteArray(1)
        when (command) {
        /**
         * ORIGINAL:
         *
         *
        0 -> bytes[0] = 0x00.toByte()
        1 -> bytes[0] = 0x01.toByte() // Forward
        2 -> bytes[0] = 0xF0.toByte() // Rotate Left
        3 -> bytes[0] = 0x0F.toByte() // Rotate Right ??
        4 -> bytes[0] = 0xFF.toByte() // TODO: 6/27/2017 Disconnect instead of reverse?
         */
            0 -> bytes[0] = 0x00.toByte()
            1 -> bytes[0] = 0x00.toByte() // Forward
            2 -> bytes[0] = 0xF0.toByte() // Rotate Right
            3 -> bytes[0] = 0x0F.toByte() // Rotate Left
            4 -> bytes[0] = 0x01.toByte() // TODO: 6/27/2017 Disconnect instead of reverse?
            else -> {
            }
        }
        if (mLedWheelchairControlService != null && mWheelchairControl) {
            Log.e(TAG, "SendingCommand: " + command.toString())
            Log.e(TAG, "SendingCommand (byte): " + DataChannel.byteArrayToHexString(bytes))
            mActBle!!.writeCharacteristic(mBluetoothGattArray[mWheelchairGattIndex]!!, mLedWheelchairControlService!!.getCharacteristic(AppConstant.CHAR_WHEELCHAIR_CONTROL), bytes)
        }
    }

    private fun getDataRateBytes(bytes: Int) {
        val mCurrentTime = System.currentTimeMillis()
        points += bytes
        if (mCurrentTime > mLastTime + 5000) {
            dataRate = (points / 5).toDouble()
            points = 0
            mLastTime = mCurrentTime
            Log.e(" DataRate:", dataRate.toString() + " Bytes/s")
            runOnUiThread {
                val s = dataRate.toString() + " Bytes/s"
                mDataRate!!.text = s
            }
        }
    }

    override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
        uiRssiUpdate(rssi)
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                mConnected = true
                runOnUiThread {
                    if (menu != null) {
                        menu!!.findItem(R.id.menu_connect).isVisible = false
                        menu!!.findItem(R.id.menu_disconnect).isVisible = true
                    }
                }
                Log.i(TAG, "Connected")
                updateConnectionState(getString(R.string.connected))
                invalidateOptionsMenu()
                runOnUiThread {
                    mDataRate!!.setTextColor(Color.BLACK)
                    mDataRate!!.setTypeface(null, Typeface.NORMAL)
                }
                //Start the service discovery:
                gatt.discoverServices()
                startMonitoringRssiValue()
            }
            BluetoothProfile.STATE_CONNECTING -> {
            }
            BluetoothProfile.STATE_DISCONNECTING -> {
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                mConnected = false
                runOnUiThread {
                    if (menu != null) {
                        menu!!.findItem(R.id.menu_connect).isVisible = true
                        menu!!.findItem(R.id.menu_disconnect).isVisible = false
                    }
                }
                Log.i(TAG, "Disconnected")
                runOnUiThread {
                    mDataRate!!.setTextColor(Color.RED)
                    mDataRate!!.setTypeface(null, Typeface.BOLD)
                    mDataRate!!.text = HZ
                }
                updateConnectionState(getString(R.string.disconnected))
                stopMonitoringRssiValue()
                invalidateOptionsMenu()
            }
            else -> {
            }
        }
    }

    private fun startMonitoringRssiValue() {
        readPeriodicallyRssiValue(true)
    }

    private fun stopMonitoringRssiValue() {
        readPeriodicallyRssiValue(false)
    }

    private fun readPeriodicallyRssiValue(repeat: Boolean) {
        mTimerEnabled = repeat
        // check if we should stop checking RSSI value
        if (!mConnected || !mTimerEnabled) {
            mTimerEnabled = false
            return
        }

        mTimerHandler.postDelayed(Runnable {
            if (!mConnected) {
                mTimerEnabled = false
                return@Runnable
            }
            // request RSSI value
            mBluetoothGattArray[0]!!.readRemoteRssi()
            // add call it once more in the future
            readPeriodicallyRssiValue(mTimerEnabled)
        }, RSSI_UPDATE_TIME_INTERVAL.toLong())
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        Log.i(TAG, "onCharacteristicWrite :: Status:: " + status)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {}

    override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        Log.i(TAG, "onDescriptorRead :: Status:: " + status)
    }

    override fun onError(errorMessage: String) {
        Log.e(TAG, "Error:: " + errorMessage)
    }

    private fun updateConnectionState(status: String) {
        runOnUiThread {
            if (status == getString(R.string.connected)) {
                Toast.makeText(applicationContext, "Device Connected!", Toast.LENGTH_SHORT).show()
            } else if (status == getString(R.string.disconnected)) {
                Toast.makeText(applicationContext, "Device Disconnected!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBatteryStatus(integerValue: Int) {
        val status: String
        val convertedBatteryVoltage = integerValue.toDouble() / 4096.0 * 7.20
        //Because TPS63001 dies below 1.8V, we need to set up a linear fit between 1.8-4.2V
        //Anything over 4.2V = 100%
        val finalPercent: Double = when {
            125.0 / 3.0 * convertedBatteryVoltage - 75.0 > 100.0 -> 100.0
            125.0 / 3.0 * convertedBatteryVoltage - 75.0 < 0.0 -> 0.0
            else -> 125.0 / 3.0 * convertedBatteryVoltage - 75.0
        }
        Log.e(TAG, "Battery Integer Value: " + integerValue.toString())
        Log.e(TAG, "ConvertedBatteryVoltage: " + String.format(Locale.US, "%.5f", convertedBatteryVoltage) + "V : " + String.format(Locale.US, "%.3f", finalPercent) + "%")
        status = String.format(Locale.US, "%.1f", finalPercent) + "%"
        runOnUiThread {
            if (finalPercent <= batteryWarning) {
                mBatteryLevel!!.setTextColor(Color.RED)
                mBatteryLevel!!.setTypeface(null, Typeface.BOLD)
                Toast.makeText(applicationContext, "Charge Battery, Battery Low " + status, Toast.LENGTH_SHORT).show()
            } else {
                mBatteryLevel!!.setTextColor(Color.GREEN)
                mBatteryLevel!!.setTypeface(null, Typeface.BOLD)
            }
            mBatteryLevel!!.text = status
        }
    }

    private fun uiRssiUpdate(rssi: Int) {
        runOnUiThread {
            val menuItem = menu!!.findItem(R.id.action_rssi)
            val statusActionItem = menu!!.findItem(R.id.action_status)
            val valueOfRSSI = rssi.toString() + " dB"
            menuItem.title = valueOfRSSI
            if (mConnected) {
                val newStatus = "Status: " + getString(R.string.connected)
                statusActionItem.title = newStatus
            } else {
                val newStatus = "Status: " + getString(R.string.disconnected)
                statusActionItem.title = newStatus
            }
        }
    }

    private fun runPowerSpectrum() {
        val bufferLastIndex = (mCh1?.classificationBufferSize ?: 1000) - 1
        val ch1 = Arrays.copyOfRange(mCh1?.classificationBuffer, bufferLastIndex - mWindowLength - 1, bufferLastIndex - 1)
        val ch2 = Arrays.copyOfRange(mCh2?.classificationBuffer, bufferLastIndex - mWindowLength - 1, bufferLastIndex - 1)
        val concat = Doubles.concat(ch1, ch2)
        val extractedPSD = mNativeInterface.jTFPSDExtraction(concat, mWindowLength)
        val psdCh1 = Arrays.copyOfRange(extractedPSD, 0, mWindowLength / 2 - 1)
        val psdCh2 = Arrays.copyOfRange(extractedPSD, mWindowLength / 2, mWindowLength - 1)
        powerSpectrumUpdateUI(psdCh1, psdCh2)
    }

    private fun powerSpectrumUpdateUI(mPSDCh1: FloatArray, mPSDCh2: FloatArray) {
        mPSDDataPointsToShow = fPSDEndIndex - fPSDStartIndex
        mGraphAdapterCh1PSDA!!.setSeriesHistoryDataPoints(mPSDDataPointsToShow)
        mGraphAdapterCh2PSDA!!.setSeriesHistoryDataPoints(mPSDDataPointsToShow)
        if (mPSDDataPointsToShow > 64)
            mFreqDomainPlotAdapter!!.setXyPlotDomainIncrement(6.0)
        else
            mFreqDomainPlotAdapter!!.setXyPlotDomainIncrement(2.0)
        mGraphAdapterCh1PSDA?.series?.clear()
        mGraphAdapterCh2PSDA?.series?.clear()
        mGraphAdapterCh1PSDA!!.addDataPointsGeneric(fPSD!!, mPSDCh1, fPSDStartIndex, fPSDEndIndex)
        mGraphAdapterCh2PSDA!!.addDataPointsGeneric(fPSD!!, mPSDCh2, fPSDStartIndex, fPSDEndIndex)
    }

    private fun setAudioState(audioState: AudioState) {
        mAudioState = audioState
        mJSDrone?.setAudioStreamEnabled(false, false)
//        when (mAudioState) {
//            JSActivity.AudioState.MUTE -> {
//                mAudioBt.setText("MUTE")
//                mJSDrone.setAudioStreamEnabled(false, false)
//            }
//
//            JSActivity.AudioState.INPUT -> {
//                mAudioBt.setText("INPUT")
//                mJSDrone.setAudioStreamEnabled(true, false)
//            }
//
//            JSActivity.AudioState.BIDIRECTIONAL -> {
//                mAudioBt.setText("IN/OUTPUT")
//                mJSDrone.setAudioStreamEnabled(true, true)
//            }
//        }
    }

    private val mJSListener = object : JSDrone.Listener {
        override fun onDroneConnectionChanged(state: ARCONTROLLER_DEVICE_STATE_ENUM) {
            when (state) {
                ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_RUNNING -> mConnectionProgressDialog?.dismiss()

                ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_STOPPED -> {
                    // if the deviceController is stopped, go back to the previous activity
                    mConnectionProgressDialog?.dismiss()
                    finish()
                }

                else -> {
                }
            }
        }

        override fun onBatteryChargeChanged(batteryPercentage: Int) {
//            mBatteryLabel?.setText(String.format("%d%%", batteryPercentage))
        }

        override fun onPictureTaken(error: ARCOMMANDS_JUMPINGSUMO_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_ENUM) {
            Log.i(TAG, "Picture has been taken")
        }

        override fun configureDecoder(codec: ARControllerCodec) {}

        override fun onFrameReceived(frame: ARFrame) {
//            mVideoView.displayFrame(frame)
        }

        override fun onAudioStateReceived(inputEnabled: Boolean, outputEnabled: Boolean) {
//            if (inputEnabled) {
//                mAudioPlayer.start()
//            } else {
//                mAudioPlayer.stop()
//            }
//
//            if (outputEnabled) {
//                mAudioRecorder.start()
//            } else {
//                mAudioRecorder.stop()
//            }
        }

        override fun configureAudioDecoder(codec: ARControllerCodec) {
//            if (codec.type == ARCONTROLLER_STREAM_CODEC_TYPE_ENUM.ARCONTROLLER_STREAM_CODEC_TYPE_PCM16LE) {
//
//                val codecPCM16le = codec.asPCM16LE
//
//                mAudioPlayer.configureCodec(codecPCM16le.sampleRate)
//            }
        }

        override fun onAudioFrameReceived(frame: ARFrame) {
//            mAudioPlayer.onDataReceived(frame)
        }

        override fun onMatchingMediasFound(nbMedias: Int) {
//            mDownloadProgressDialog.dismiss()
//
//            mNbMaxDownload = nbMedias
//            mCurrentDownloadIndex = 1
//
//            if (nbMedias > 0) {
//                mDownloadProgressDialog = ProgressDialog(this@JSActivity, R.style.AppCompatAlertDialogStyle)
//                mDownloadProgressDialog.setIndeterminate(false)
//                mDownloadProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
//                mDownloadProgressDialog.setMessage("Downloading medias")
//                mDownloadProgressDialog.setMax(mNbMaxDownload * 100)
//                mDownloadProgressDialog.setSecondaryProgress(mCurrentDownloadIndex * 100)
//                mDownloadProgressDialog.setProgress(0)
//                mDownloadProgressDialog.setCancelable(false)
//                mDownloadProgressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", DialogInterface.OnClickListener { dialog, which -> mJSDrone.cancelGetLastFlightMedias() })
//                mDownloadProgressDialog.show()
//            }
        }

        override fun onDownloadProgressed(mediaName: String, progress: Int) {
//            mDownloadProgressDialog.setProgress((mCurrentDownloadIndex - 1) * 100 + progress)
        }

        override fun onDownloadComplete(mediaName: String) {
//            mCurrentDownloadIndex++
//            mDownloadProgressDialog.setSecondaryProgress(mCurrentDownloadIndex * 100)
//
//            if (mCurrentDownloadIndex > mNbMaxDownload) {
//                mDownloadProgressDialog.dismiss()
//                mDownloadProgressDialog = null
//            }
        }
    }

    companion object {
        internal const val HZ = "0 Hz"
        private val TAG = DeviceControlActivity::class.java.simpleName
        private var mGraphAdapterCh1PSDA: GraphAdapter? = null
        private var mGraphAdapterCh2PSDA: GraphAdapter? = null
        private var mFreqDomainPlotAdapter: XYPlotAdapter? = null
        var mRedrawer: Redrawer? = null
        var mSSVEPClass = 0.0
        // Power Spectrum Graph Data:
        private var fPSD: DoubleArray? = null
        private var mWindowLength: Int = 512
        private var mPSDDataPointsToShow = 0
        internal var fPSDStartIndex = 16
        internal var fPSDEndIndex = 44
        private var mSampleRate = 250
        //Data Channel Classes
        internal var mFilterData = false
        private var mPacketBuffer = 2
        //RSSI:
        private const val RSSI_UPDATE_TIME_INTERVAL = 2000
        //Save Data File
        private var mPrimarySaveDataFile: SaveDataFile? = null
        //Tensorflow CONSTANTS:
        const val INPUT_DATA_FEED = "input"
        const val OUTPUT_DATA_FEED = "output"
        val ADS1299_DEFAULT_BYTE_CONFIG = byteArrayOf(
                0x96.toByte(), 0xD0.toByte(), 0xEC.toByte(), 0x00.toByte(), //CONFIG1-3, LOFF
                0x40.toByte(), 0x40.toByte(), 0xE1.toByte(), 0xE1.toByte(), //CHSET 1-4
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), //CHSET 5-8
                0x0F.toByte(), 0x0F.toByte(), // BIAS_SENSP/N
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // LOFF_P/N (IGNORE)
                0x0F.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()) //GPIO, MISC1 (0x20 for SRB1), MISC2, CONFIG4

        //Directory:
        private const val MODEL_FILENAME = "file:///android_asset/opt_ssvep_net.pb"

        //Note for companion object: JNI call must include Companion in call: e.g. package_class_Companion_function(...).
        //TODO: Still does not work when I try to call from the companion object.
        init {
            System.loadLibrary("ssvep-lib")
        }
    }
}
