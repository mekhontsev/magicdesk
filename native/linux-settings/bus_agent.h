#pragma once
/* Called after entering the selected guest/user and its D-Bus session. */
int md_bus_agent_start(void);
int md_bus_agent_serve(int socket);
