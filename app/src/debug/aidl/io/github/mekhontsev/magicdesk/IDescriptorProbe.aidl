package io.github.mekhontsev.magicdesk;
import android.os.ParcelFileDescriptor;

oneway interface IDescriptorProbe {
    void deliver(in ParcelFileDescriptor descriptor);
}