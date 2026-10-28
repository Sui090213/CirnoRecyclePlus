package nep.timeline.cirno.entity;

import android.os.IBinder;

import java.util.HashSet;
import java.util.Set;

import lombok.Getter;

@Getter
public class AppState {
    private final AppRecord parent;
    // 这些状态由各 Hook 线程写、Memory 线程读: volatile 保证读到的不是过期值,
    // 避免把「刚解冻、马上回前台」的应用当成冻结应用回收.
    private volatile boolean visible = false;
    private volatile boolean location = false;
    private volatile boolean audio = false;
    private volatile boolean recording = false;
    private volatile boolean vpn = false;
    private final Set<IBinder> activities = new HashSet<>();
    private final Set<IBinder> locationListeners = new HashSet<>();
    private final Set<Integer> interfaceIds = new HashSet<>();
    private final Set<Integer> recodingIds = new HashSet<>();

    public AppState(AppRecord appRecord) {
        this.parent = appRecord;
    }

    public synchronized boolean setVisible(boolean value) {
        if (visible == value)
            return false;
        visible = value;
        return true;
    }

    public synchronized boolean setLocation(boolean value) {
        if (location == value)
            return false;
        location = value;
        return true;
    }

    public synchronized boolean setAudio(boolean value) {
        if (audio == value)
            return false;
        audio = value;
        return true;
    }

    public synchronized boolean setRecording(boolean value) {
        if (recording == value)
            return false;
        recording = value;
        return true;
    }

    public synchronized boolean setVpn(boolean value) {
        if (vpn == value)
            return false;
        vpn = value;
        return true;
    }
}
