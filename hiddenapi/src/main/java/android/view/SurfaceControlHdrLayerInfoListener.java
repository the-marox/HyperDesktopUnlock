package android.view;

import android.os.IBinder;

/**
 * Stub di android.view.SurfaceControlHdrLayerInfoListener (@hide, Android 12+).
 * SurfaceFlinger avvisa quando sul display ci sono livelli HDR (video, foto HDR...).
 * Fino ad Android 14 il callback ha 5 parametri, da Android 15 un sesto
 * (maxDesiredHdrSdrRatio): la sottoclasse del modulo li implementa entrambi.
 */
public abstract class SurfaceControlHdrLayerInfoListener {
    public void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers,
            int maxW, int maxH, int flags) {}

    public void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers,
            int maxW, int maxH, int flags, float maxDesiredHdrSdrRatio) {}

    public void register(IBinder displayToken) {}

    public void unregister(IBinder displayToken) {}
}
