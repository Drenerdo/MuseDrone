package com.interaxon.test.libmuse;

import android.os.SystemClock;
import android.util.Log;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

// This is for the parrot drone SDK
import com.parrot.arsdk.arcommands.ARCOMMANDS_COMMON_MAVLINK_START_TYPE_ENUM;
import com.parrot.arsdk.arcommands.ARCOMMANDS_DECODER_ERROR_ENUM;
import com.parrot.arsdk.arcommands.ARCOMMANDS_GENERATOR_ERROR_ENUM;
import com.parrot.arsdk.arcommands.ARCOMMANDS_MINIDRONE_ANIMATIONS_FLIP_DIRECTION_ENUM;
import com.parrot.arsdk.arcommands.ARCommand;
import com.parrot.arsdk.arcommands.ARCommandCommonCommonStateBatteryStateChangedListener;
import com.parrot.arsdk.arcommands.ARCommandsVersion;
import com.parrot.arsdk.ardiscovery.ARDISCOVERY_ERROR_ENUM;
import com.parrot.arsdk.ardiscovery.ARDiscoveryConnection;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDeviceBLEService;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDeviceNetService;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDeviceService;
import com.parrot.arsdk.arnetwork.ARNETWORK_ERROR_ENUM;
import com.parrot.arsdk.arnetwork.ARNETWORK_MANAGER_CALLBACK_RETURN_ENUM;
import com.parrot.arsdk.arnetwork.ARNETWORK_MANAGER_CALLBACK_STATUS_ENUM;
import com.parrot.arsdk.arnetwork.ARNetworkIOBufferParam;
import com.parrot.arsdk.arnetwork.ARNetworkManager;
import com.parrot.arsdk.arnetworkal.ARNETWORKAL_ERROR_ENUM;
import com.parrot.arsdk.arnetworkal.ARNETWORKAL_FRAME_TYPE_ENUM;
import com.parrot.arsdk.arnetworkal.ARNetworkALManager;
import com.parrot.arsdk.arsal.ARNativeData;
import com.parrot.arsdk.arsal.ARSALPrint;
/**
 * Created by andresmith on 9/7/15.
 */
public class DeviceController implements ARCommandCommonCommonStateBatteryStateChangedListener
{
    private static String TAG = "DeviceController";
    private static int iobufferC2dNack = 10;
    private static int iobufferC2dAck = 11;
    private static int iobufferc2dEmergency = 12;
    private static int iobufferD2cNavdata = (ARNetworkALManager.ARNETWORK_MANAGER_BLE_ID_MAX / 2) - 1;
    private static int iobufferD2cEvents = (ARNetworkALManager.ARNERWORKAL_MANAGER_BLE_ID_MAX / 2) - 2;

    private static int ackOffset = (ARNetworkALManager.ARNETWORK_MANAGER_BLE_ID_MAX / 2);

    protected static int bleNotificationIDs[] = new int[]{iobufferD2cNavdata, iobufferD2cEvents, (iobufferC2dAck + ackOffset), (iobufferc2dEmergency + ackOffset)};

    private android.content.Context context;

    private ARNetworkALManager alManager;
    private ARNetworkManager netManager;
    private boolean mediaOpened;

    private int c2dPort;
    private int d2cPort;
    private Thread rxThread;
    private Thread txThread;

    private List<ReaderThread> readerThreads;
    private Semaphore discoverSemaphore;
    private ARDiscoveryConnection discoveryData;

    private LooperThread looperThread;
    private DataPCMD dataPCMD;
    private ARDiscoveryDeviceService deviceService;

    private DeviceControllerListener listener;

    static
    {
        c2dParams.clear();
        c2dParams.add (new ARNetworkIOBufferParam (iobufferC2dNack,
                ARNETWORKAL_FRAME_TYPE_ENUM.ARNETWORKAL_FRAME_TYPE_DATA,
                20,
                ARNetworkIOBufferParam.ARNETWORK_IOBUFFERPARAM_INFINITE_NUMBER,
                ARNetworkIOBufferParam.ARNETWORK_IOBUFFERPARAM_INFINITE_NUMBER,
                1,
                ARNetworkIOBufferParam.ARNETWORK_IOBUFFERPARAM_DATACOPYMAXSIZE_USE_MAX,
                true));
        c2dParams.add (new ARNetworkIOBufferParam (iobufferC2dAck,
                ARNETWORKAL_FRAME_TYPE_ENUM.ARNETWORKAL_FRAME_TYPE_DATA_WITH_ACK,
                20,
                500,
                3,
                20,
                ARNetworkIOBufferParam.ARNETWORK_IOBUFFERPARAM_DATACOPYMAXSIZE_USE_MAX,
                false));
        c2dParams.add (new ARNetworkIOBufferParam (iobufferC2dEmergency,
                ARNETWORKAL_FRAME_TYPE_ENUM.ARNETWORKAL_FRAME_TYPE_DATA_WITH_ACK,
                1,
                100,
                ARNetworkIOBufferParam.ARNETWORK_IOBUFFERPARAM_INFINITE_NUMBER,
                1,
                ARNetworkIOBufferParam.ARNETWORK_IOBUFFERPARAM_DATACOPYMAXSIZE_USE_MAX,
                false));

        d2cParams.clear();
        d2cParams.add (new ARNetworkIOBufferParam (iobufferD2cNavdata,
                ARNETWORKAL_FRAME_TYPE_ENUM.ARNETWORKAL_FRAME_TYPE_DATA,
                20,
                ARNetworkIOBufferParam.ARNETWORK_IOBUFFERPARAM_INFINITE_NUMBER,
                ARNetworkIOBufferParam.ARNETWORK_IOBUFFERPARAM_INFINITE_NUMBER,
                20,
                ARNetworkIOBufferParam.ARNETWORK_IOBUFFERPARAM_DATACOPYMAXSIZE_USE_MAX,
                false));
        d2cParams.add(new ARNetworkIOBufferParam(iobufferD2cEvents,
                ARNETWORKAL_FRAME_TYPE_ENUM.ARNETWORKAL_FRAME_TYPE_DATA_WITH_ACK,
                20,
                500,
                3,
                20,
                ARNetworkIOBufferParam.ARNETWORK_IOBUFFERPARAM_DATACOPYMAXSIZE_USE_MAX,
                false));

        commandsBuffers = new int[] {
                iobufferD2cNavdata,
                iobufferD2cEvents,
        };

    }

    public DeviceController (android.content.Context context, ARDiscoveryDeviceService service)
    {
        dataPCMD = new DataPCMD();
        deviceService = service;
        this.context = context;
        readerThreads = new ArrayList<ReaderThread>();
    }

    public boolean start()
    {
        Log.d(TAG, "Start...");

        boolean failed = false;

        registerARCommandListener();

        failed = startNetwork();

        if(!failed)
        {
            startReadThreads();
        }
        if(!failed)
        {
            startReadThreads();
        }
        if(!failed)
        {
            startLooperThread();
        }
        return failed;
    }

    public void stop()
    {
        Log.d(TAG, "stop...");
        unregisterARCommandsListener();

        stopLooperThread();
        stopReaderThreads();
        stopNetwork();
    }

    private boolean startNetwork()
    {
        ARNETWORKAL_ERROR_ENUM netALError = ARNETWORKAL_ERROR_ENUM.ARNETWORKAL_OK;
        boolean failed = false;
        int pingDelay = 0;
        alManager = new ARNetworkALManager();

        // Setting up ARNETWORK for BLE
        ARDiscoveryDeviceBLEService bleDevice = (ARDiscoveryDeviceBLEService) deviceService.getDevice();
        netALError = alManager.initBLENetwork(context, bleDevice.getBluetoothDevice(), 1, bleNotificationIDs);
        if(netALError == ALNETWORKAL_ERROR_ENUM.ARNETWORKAL_OK)
        {
            mediaOpened = true;
            pingDelay = -1;
        }
        else
        {
            ARSALPrint.e(TAG, "error occured:" + netALError.toString());
            failed = true;
        }
        if(failed == false)
        {
            netManager = new ARNetworkManagerExtend(alManager, c2dParams.toArray(new ARNetworkIOBufferParam[c2dParams.size()]), d2cParams.toArray(new ARNetworkIOBufferParam[d2cParams.size()]), pingDelay);

            if(netManager.isCorrectlyInitized() == false)
            {
                ARSALPrint.e(TAG, "new ARNetworkManager failed");
                failed = true;
            }
        }

        if(failed == false)
        {
            rxThread = new Thread(netManager.m_receivingRunnable);
            rxThread.start();
            txThread = new Thread(netManager.m_sendingRunnable);
            txThead.start();
        }

        return failed;
    }

    private void startReadThreads()
    {
        for(int bufferId : commandsBuffers)
        {
            ReaderThread readerThread = new ReaderThread(bufferId);
            readerThreads.add(readerThread);
        }

        for(ReaderThread readerThread : readerThreads)
        {
            readerThread.start();
        }
    }

    private void startLooperThread()
    {
        looperThread = new ControllerLooperThread();
        looperThread.start();
    }

    private void stopLooperThread()
    {
        if(null != looperThread)
        {
            looperThread.stopThread();
            try
            {
                looperThread.join();
            }
            catch(InterruptedException e)
            {
                e.printStackTrace();
            }
        }
    }

    private void stopReaderThreads()
    {
        if(readerThreads != null)
        {
            for(ReaderThread thread : readerThreads)
            {
                thread.stopThread();
            }
            for(ReaderThread thread : readerThreads)
            {
                try
                {
                    thread.join();
                }
                catch(InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
            readerThreads.clear();
        }
    }

    private void stopNetwork()
    {
        if(netManager != null)
        {
            netManager.stop();
            try
            {
                if(txThread != null)
                {
                    txThread.join();
                }
                if(rxThread != null)
                {
                    rxThread.join();
                }
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
            netManager.dispose();
        }

        if((alManager != null) && (mediaOpened))
        {
            if(deviceService.getDevice() instanceof ARDiscoveryDeviceNetService)
            {
                alManager.closeWifiNetwork();
            }
            else if(deviceService.getDevice() instanceof ARDiscoveryDeviceBLEService)
            {
                alManager.closeBLENetwork(context);
            }
            mediaOpened = false;
            alManager.dispose();
        }
    }

    protected void registerARCommandListener()
    {
        ARCommand.setCommonCommonStateBatteryStateChangedListener(this);
    }

    protected void unregisterARCommandsListener()
    {
        ARCommand.setCommonCommonStateBatteryStateChangedListener(null);
    }
    private boolean sendPCMD()
    {
        ARCOMMANDS_GENERATOR_ERROR_ENUM cmdError = ARCOMMANDS_GENERATOR_ERROR_ENUM.ARCOMMANDS_GENERATOR_OK;
        boolean sendStatus = true;
        ARCommand cmd = new ARCommand();

        cmdError = cmd.setMiniDronePilotingPCMD(dataPCMD.flag, dataPCMD.roll, dataPCMD.pitch, dataPCMD.yaw, dataPCMD.gaz, dataPCMD.psi);
        if(cmdError == ARCOMMANDS_GENERATOR_ERROR_ENUM.ARCOMMANDS_GENERATOR_OK)
        {
            ARNETWORK_ERROR_ENUM netError = netManager.sendData(iobufferC2dNack, cmd, null, true);

            if(netError != ARNETWORK_ERROR_ENUM.ARNETWORK_OK)
            {
                ARSALPrint.e(TAG, "netManager.sendData() failed." + netError.toString());
                sendStatus = false;
            }
            cmd.dispose();
        }

        if(sendStatus == false)
        {
            ARSALPrint.e(TAG, "Failed to send PCMD command.");
        }

        return sendStatus;
    }

    public boolean filp(ARCOMMAND_MINIDRONE_ANIMATIONS_FILP_DIRECTION_ENUM direction){
        ARCOMMANDS_GENERATOR_ERROR_ENUM cmdError = ARCOMMANDS_GENERATOR_ERROR_ENUM.ARCOMMANDS_GENERATOR_OK;
        boolean sentStatus = false;
        ARCommand cmd = new ARCommand();
    }


}
