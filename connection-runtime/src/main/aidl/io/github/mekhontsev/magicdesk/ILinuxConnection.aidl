package io.github.mekhontsev.magicdesk;
import android.os.Bundle;
interface ILinuxConnection {
    Bundle describe();
    oneway void close();
}
