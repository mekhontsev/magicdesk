#include "graphics_internal.h"
bool mdg_vk_ready(const MdgDevice *device) { (void)device; return false; }
bool mdg_vk_create(MdgDevice *d) { (void)d; return false; }
void mdg_vk_destroy(MdgDevice *d) { (void)d; }
bool mdg_vk_image(MdgImage *i) { (void)i; return false; }
void mdg_vk_image_destroy(MdgImage *i) { (void)i; }
bool mdg_vk_submit(MdgPass *p) { (void)p; return false; }
bool mdg_vk_complete(MdgPass *p) { (void)p; return true; }
void mdg_vk_pass_destroy(MdgPass *p) { (void)p; }
