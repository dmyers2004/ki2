package com.valterc.ki2.ant;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import com.dsi.ant.AntService;
import com.dsi.ant.channel.AntChannel;
import com.dsi.ant.channel.AntChannelProvider;
import com.dsi.ant.channel.Capabilities;
import com.dsi.ant.channel.IAntAdapterEventHandler;
import com.dsi.ant.channel.IAntChannelEventHandler;
import com.dsi.ant.channel.NetworkKey;
import com.dsi.ant.channel.PredefinedNetwork;
import com.dsi.ant.message.ChannelId;
import com.dsi.ant.message.ChannelType;
import com.dsi.ant.message.ExtendedAssignment;
import com.dsi.ant.message.HighPrioritySearchTimeout;
import com.dsi.ant.message.LibConfig;
import com.dsi.ant.message.LowPrioritySearchTimeout;
import com.valterc.ki2.ant.channel.AntChannelWrapper;
import com.valterc.ki2.ant.channel.ChannelConfiguration;
import com.valterc.ki2.ant.channel.ScanChannelConfiguration;

import timber.log.Timber;

public class AntManager {

    private static final LibConfig libConfig = new LibConfig(true, true, false);

    private final Context context;
    private final IAntStateListener stateListener;

    private boolean antServiceBound;
    private AntService antService;
    private AntChannelProvider antChannelProvider;

    private final ServiceConnection antServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            Timber.i("ANT service connected");
            antService = new AntService(service);

            try {
                antChannelProvider = antService.getChannelProvider();
            } catch (RemoteException e) {
                Timber.e(e, "Unable to get ANT channel provider");
            }
            triggerStateChange();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            antService = null;
            antChannelProvider = null;
            Timber.i("ANT service disconnected");
            triggerStateChange();
        }
    };

    private final BroadcastReceiver channelProviderStateChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AntChannelProvider.ACTION_CHANNEL_PROVIDER_STATE_CHANGED.equals(intent.getAction())) {
                int numChannelsAvailable = intent.getIntExtra(AntChannelProvider.NUM_CHANNELS_AVAILABLE, 0);
                boolean legacyInterfaceInUse = intent.getBooleanExtra(AntChannelProvider.LEGACY_INTERFACE_IN_USE, false);
                Timber.d("Received ANT channel provider broadcast: Number of channels available: %s, Legacy interface: %s", numChannelsAvailable, legacyInterfaceInUse);
            }
        }
    };

    public AntManager(Context context, IAntStateListener stateListener) {
        this.context = context;
        this.stateListener = stateListener;

        antServiceBound = AntService.bindService(context, antServiceConnection);
        Timber.i("ANT service bind: %s", antServiceBound);
    }

    private void triggerStateChange() {
        if (stateListener != null){
            stateListener.onAntStateChange(isReady());
        }
    }

    public void dispose() {
        try {
            context.unregisterReceiver(channelProviderStateChangedReceiver);
        } catch (IllegalArgumentException e) {
            // Not bound
        }

        if (antServiceBound) {
            try {
                context.unbindService(antServiceConnection);
            } catch (IllegalArgumentException e) {
                // Not bound
            }
            antServiceBound = false;
        }
    }

    /**
     * Get the number of available channels from this provider.
     *
     * @return Number of available channels from this provider.
     * @throws Exception If the ANT service becomes unavailable.
     */
    public int getAvailableChannelCount() throws Exception{
        if (!isReady())
        {
            throw new RuntimeException("ANT channel provider is not available");
        }

        return antChannelProvider.getNumChannelsAvailable();
    }

    /**
     * Get an ANT channel. The channel will be open.
     *
     * @param channelConfiguration Channel configuration.
     * @param channelEventHandler Optional channel event handler.
     * @param adapterEventHandler Optional adapter event handler.
     * @return ANT channel wrapper.
     * @throws Exception If the ANT service becomes unavailable.
     */
    public AntChannelWrapper getAntChannel(
            ChannelConfiguration channelConfiguration,
            IAntChannelEventHandler channelEventHandler,
            IAntAdapterEventHandler adapterEventHandler) throws Exception {
        if (!isReady())
        {
            throw new RuntimeException("ANT channel provider is not available");
        }

        AntChannel antChannel;

        try {
            NetworkKey networkKey = channelConfiguration.getNetworkKey();
            if (networkKey != null) {
                antChannel = antChannelProvider.acquireChannelOnPrivateNetwork(context, networkKey);
            }else {
                antChannel = antChannelProvider.acquireChannel(this.context, PredefinedNetwork.ANT_PLUS);
            }
        } catch (Exception e) {
            Timber.e(e, "Unable to create ANT channel");
            throw e;
        }

        try {
            antChannel.setChannelEventHandler(channelEventHandler);
            antChannel.setAdapterEventHandler(adapterEventHandler);

            antChannel.assign(channelConfiguration.getChannelType());
            antChannel.setRfFrequency(channelConfiguration.getRfFrequency());
            antChannel.setPeriod(channelConfiguration.getPeriod());

            Integer searchPriority = channelConfiguration.getSearchPriority();
            if (searchPriority != null) {
                antChannel.setSearchPriority(searchPriority);
            }

            LowPrioritySearchTimeout lowPrioritySearchTimeout = channelConfiguration.getLowPrioritySearchTimeout();
            if (lowPrioritySearchTimeout != null) {
                antChannel.setSearchTimeout(lowPrioritySearchTimeout, HighPrioritySearchTimeout.DISABLED);
            }

            antChannel.setAdapterWideLibConfig(libConfig);
            antChannel.setChannelId(channelConfiguration.getChannelId());

            return new AntChannelWrapper(antChannel);
        } catch (Exception e) {
            Timber.e(e, "Unable to create ANT channel");

            antChannel.clearChannelEventHandler();
            antChannel.clearAdapterEventHandler();
            antChannel.release();

            throw e;
        }
    }

    /**
     * Get an ANT scan channel. The channel will be open.
     *
     * @param scanChannelConfiguration Scan channel configuration.
     * @param channelEventHandler Optional channel event handler.
     * @param adapterEventHandler Optional adapter event handler.
     * @return ANT channel wrapper.
     * @throws Exception If the ANT service becomes unavailable.
     */
    public AntChannelWrapper getScanAntChannel(
            ScanChannelConfiguration scanChannelConfiguration,
            IAntChannelEventHandler channelEventHandler,
            IAntAdapterEventHandler adapterEventHandler) throws Exception
    {
        if (!isReady())
        {
            throw new RuntimeException("ANT channel provider is not available");
        }

        Capabilities capabilities = new Capabilities();
        ExtendedAssignment extendedAssignment = new ExtendedAssignment();
        capabilities.supportBackgroundScanning(true);
        extendedAssignment.enableBackgroundScanning();

        AntChannel antChannel;

        try {
            NetworkKey networkKey = scanChannelConfiguration.getNetworkKey();
            if (networkKey != null) {
                antChannel = antChannelProvider.acquireChannelOnPrivateNetwork(context, networkKey, capabilities);
            }else {
                antChannel = antChannelProvider.acquireChannel(this.context, PredefinedNetwork.ANT_PLUS, capabilities);
            }
        } catch (Exception e) {
            Timber.e(e, "Unable to create ANT channel");
            throw e;
        }

        try {
            antChannel.setChannelEventHandler(channelEventHandler);
            antChannel.setAdapterEventHandler(adapterEventHandler);

            antChannel.assign(ChannelType.SLAVE_RECEIVE_ONLY, extendedAssignment);
            antChannel.setRfFrequency(scanChannelConfiguration.getRfFrequency());
            antChannel.setSearchPriority(11);

            Integer period = scanChannelConfiguration.getPeriod();
            if (period != null) {
                antChannel.setPeriod(period);
            }

            antChannel.setAdapterWideLibConfig(libConfig);
            antChannel.setChannelId(new ChannelId(0, 0, 0));

            return new AntChannelWrapper(antChannel);
        } catch (Exception e) {
            Timber.e(e, "Unable to create ANT channel");

            antChannel.clearChannelEventHandler();
            antChannel.clearAdapterEventHandler();
            antChannel.release();

            throw e;
        }
    }

    /**
     * Indicates if the ANT manager is ready to create ANT channels.
     *
     * @return True if ANT is ready, False otherwise.
     */
    public boolean isReady(){
        return antChannelProvider != null;
    }

}
