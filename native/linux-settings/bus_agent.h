#pragma once
/* Called after entering the selected guest/user and its D-Bus session. */
int md_bus_agent_start(void);
/* Native graphical launches retain their private bus through the admitted session socket. */
int md_bus_session_start(const char *helper);
int md_bus_session_serve(int ready);
