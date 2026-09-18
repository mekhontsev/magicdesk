package io.github.mekhontsev.magicdesk;

/** Explicit work lifetime, independent of Desktop, HOME and display allocation. */
interface IBackgroundWorkLease {
    void renew(long durationMillis);
    String state();
    void close();
}
