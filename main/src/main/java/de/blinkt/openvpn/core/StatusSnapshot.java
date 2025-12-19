package de.blinkt.openvpn.core;

import android.os.Parcel;
import android.os.Parcelable;

public class StatusSnapshot implements Parcelable {
    public final String state;
    public final String message;
    public final int resid;
    public final ConnectionStatus level;
    public final long timestampMs;

    public StatusSnapshot(String state, String message, int resid, ConnectionStatus level, long timestampMs) {
        this.state = state;
        this.message = message;
        this.resid = resid;
        this.level = level;
        this.timestampMs = timestampMs;
    }

    protected StatusSnapshot(Parcel in) {
        state = in.readString();
        message = in.readString();
        resid = in.readInt();
        level = in.readParcelable(ConnectionStatus.class.getClassLoader());
        timestampMs = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(state);
        dest.writeString(message);
        dest.writeInt(resid);
        dest.writeParcelable(level, flags);
        dest.writeLong(timestampMs);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<StatusSnapshot> CREATOR = new Creator<StatusSnapshot>() {
        @Override
        public StatusSnapshot createFromParcel(Parcel in) {
            return new StatusSnapshot(in);
        }

        @Override
        public StatusSnapshot[] newArray(int size) {
            return new StatusSnapshot[size];
        }
    };
}
