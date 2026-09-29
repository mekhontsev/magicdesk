static _Thread_local int count = 40;
int next_value(void) { return ++count; }
