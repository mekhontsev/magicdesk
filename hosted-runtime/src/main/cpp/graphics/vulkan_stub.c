#include "graphics_internal.h"
bool mdg_vk_ready(const MdgDevice *device) { (void)device; return false; }
bool mdg_vk_create(MdgDevice *d) { (void)d; return false; }
void mdg_vk_destroy(MdgDevice *d) { (void)d; }
bool mdg_vk_image(MdgImage *i) { (void)i; return false; }
bool mdg_vk_linear_dmabuf(const MdgDevice *d) { (void)d; return false; }
bool mdg_vk_import_dmabuf(MdgImage *i, const MdgLinearDmaBuf *b, MdgImportError *e) { (void)i; (void)b; (void)e; return false; }
bool mdg_dmabuf_acquire(MdgImage *i, int *fd) { (void)i; *fd = -1; return false; }
MdgSubmitResult mdg_vk_prepare(MdgPass *p, int *fd) { (void)p; *fd = -1; return MDG_SUBMIT_OK; }
void mdg_vk_image_destroy(MdgImage *i) { (void)i; }
bool mdg_vk_submit(MdgPass *p) { (void)p; return false; }
bool mdg_vk_complete(MdgPass *p) { (void)p; return true; }
void mdg_vk_pass_destroy(MdgPass *p) { (void)p; }
