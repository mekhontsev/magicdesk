package io.github.mekhontsev.magicdesk;

import android.os.Parcel;
import android.os.Parcelable;

/** Bounded task-stack trace result produced only by the desktop self-test. */
public final class SelfTestTaskStackReport implements Parcelable {
    public static final Creator<SelfTestTaskStackReport> CREATOR =
            new Creator<SelfTestTaskStackReport>() {
                @Override
                public SelfTestTaskStackReport createFromParcel(
                        final Parcel source) {
                    return new SelfTestTaskStackReport(source);
                }

                @Override
                public SelfTestTaskStackReport[] newArray(final int size) {
                    return new SelfTestTaskStackReport[size];
                }
            };

    public final boolean available;
    public final int stageCount;
    public final int sampleCount;
    public final int eventCount;
    public final int droppedSamples;
    public final String[] anomalies;
    public final String error;

    SelfTestTaskStackReport(
            final boolean available,
            final int stageCount,
            final int sampleCount,
            final int eventCount,
            final int droppedSamples,
            final String[] anomalies,
            final String error) {
        this.available = available;
        this.stageCount = stageCount;
        this.sampleCount = sampleCount;
        this.eventCount = eventCount;
        this.droppedSamples = droppedSamples;
        this.anomalies = copy(anomalies);
        this.error = error == null ? "" : error;
    }

    private SelfTestTaskStackReport(final Parcel source) {
        available = source.readBoolean();
        stageCount = source.readInt();
        sampleCount = source.readInt();
        eventCount = source.readInt();
        droppedSamples = source.readInt();
        anomalies = copy(source.createStringArray());
        final String readError = source.readString();
        error = readError == null ? "" : readError;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel destination, final int flags) {
        destination.writeBoolean(available);
        destination.writeInt(stageCount);
        destination.writeInt(sampleCount);
        destination.writeInt(eventCount);
        destination.writeInt(droppedSamples);
        destination.writeStringArray(anomalies);
        destination.writeString(error);
    }

    static SelfTestTaskStackReport unavailable(final String error) {
        return new SelfTestTaskStackReport(
                false, 0, 0, 0, 0,
                new String[0], error);
    }

    private static String[] copy(final String[] values) {
        return values == null ? new String[0] : values.clone();
    }
}
