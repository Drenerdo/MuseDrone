package com.interaxon.test.libmuse;

/**
 * Created by andresmith on 9/7/15.
 */

public interface DeviceControllerListener
{
    public void onDisconnect();
    public void onUpdateBattery(final byte percent);
}
