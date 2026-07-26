// SPDX-License-Identifier: GPL-2.0
#include <linux/kprobes.h>
#include <linux/module.h>
#include <linux/types.h>

#include <drm/drm_connector.h>
#include <drm/drm_modes.h>

#define DP_DISPLAY_PANEL_OFFSET 0xb0
#define DP_PANEL_MODE_OVERRIDE_OFFSET 0x3d5
#define DP_PANEL_OVERRIDE_WIDTH_OFFSET 0x3d8
#define DP_PANEL_OVERRIDE_HEIGHT_OFFSET 0x3dc
#define DP_PANEL_OVERRIDE_REFRESH_OFFSET 0x3e0
#define DP_PANEL_OVERRIDE_ASPECT_OFFSET 0x3e4

static void *active_panel;
static unsigned long disconnect_hits;
static unsigned long stale_override_hits;

module_param(disconnect_hits, ulong, 0444);
MODULE_PARM_DESC(disconnect_hits, "Number of intercepted DP disconnects");
module_param(stale_override_hits, ulong, 0444);
MODULE_PARM_DESC(stale_override_hits, "Number of rejected stale mode overrides");

struct edid_probe_data {
	struct drm_connector *connector;
};

static int reset_mode_override(struct kprobe *probe, struct pt_regs *regs)
{
	void *display = (void *)regs->regs[0];
	void *panel;

	(void)probe;
	if (!display)
		return 0;

	panel = READ_ONCE(*(void **)((u8 *)display + DP_DISPLAY_PANEL_OFFSET));
	if (panel) {
		WRITE_ONCE(active_panel, panel);
		WRITE_ONCE(*(u8 *)((u8 *)panel + DP_PANEL_MODE_OVERRIDE_OFFSET), 0);
		WRITE_ONCE(disconnect_hits, READ_ONCE(disconnect_hits) + 1);
	}

	return 0;
}

static int remember_connector(struct kretprobe_instance *instance,
			      struct pt_regs *regs)
{
	struct edid_probe_data *data = (struct edid_probe_data *)instance->data;

	data->connector = (struct drm_connector *)regs->regs[0];
	return 0;
}

static int reject_stale_override(struct kretprobe_instance *instance,
				 struct pt_regs *regs)
{
	struct edid_probe_data *data = (struct edid_probe_data *)instance->data;
	struct drm_connector *connector = data->connector;
	struct drm_display_mode *mode;
	void *panel = READ_ONCE(active_panel);
	u32 width, height, refresh, aspect;

	(void)regs;
	if (!connector || !panel ||
	    !READ_ONCE(*(u8 *)((u8 *)panel + DP_PANEL_MODE_OVERRIDE_OFFSET)))
		return 0;

	width = READ_ONCE(*(u32 *)((u8 *)panel + DP_PANEL_OVERRIDE_WIDTH_OFFSET));
	height = READ_ONCE(*(u32 *)((u8 *)panel + DP_PANEL_OVERRIDE_HEIGHT_OFFSET));
	refresh = READ_ONCE(*(u32 *)((u8 *)panel + DP_PANEL_OVERRIDE_REFRESH_OFFSET));
	aspect = READ_ONCE(*(u32 *)((u8 *)panel + DP_PANEL_OVERRIDE_ASPECT_OFFSET));

	list_for_each_entry(mode, &connector->probed_modes, head) {
		if (mode->hdisplay == width && mode->vdisplay == height &&
		    drm_mode_vrefresh(mode) == refresh &&
		    mode->picture_aspect_ratio == aspect)
			return 0;
	}

	WRITE_ONCE(*(u8 *)((u8 *)panel + DP_PANEL_MODE_OVERRIDE_OFFSET), 0);
	WRITE_ONCE(stale_override_hits, READ_ONCE(stale_override_hits) + 1);
	return 0;
}

static struct kprobe disconnect_probe = {
	.symbol_name = "dp_display_disconnect_sync",
	.pre_handler = reset_mode_override,
};

static struct kretprobe edid_probe = {
	.kp.symbol_name = "nubia_edid_modes",
	.entry_handler = remember_connector,
	.handler = reject_stale_override,
	.data_size = sizeof(struct edid_probe_data),
	.maxactive = 4,
};

static int __init dp_mode_reset_init(void)
{
	int ret;

	static_assert(offsetof(struct drm_connector, modes) == 0xb0);
	static_assert(offsetof(struct drm_connector, probed_modes) == 0xc8);
	static_assert(offsetof(struct drm_display_mode, head) == 0x40);
	static_assert(offsetof(struct drm_display_mode, hdisplay) == 0x4);
	static_assert(offsetof(struct drm_display_mode, vdisplay) == 0xe);

	ret = register_kprobe(&disconnect_probe);
	if (ret)
		return ret;

	ret = register_kretprobe(&edid_probe);
	if (ret)
		unregister_kprobe(&disconnect_probe);

	return ret;
}

static void __exit dp_mode_reset_exit(void)
{
	unregister_kretprobe(&edid_probe);
	unregister_kprobe(&disconnect_probe);
}

module_init(dp_mode_reset_init);
module_exit(dp_mode_reset_exit);

MODULE_AUTHOR("mekhontsev");
MODULE_DESCRIPTION("Reset stale Nubia DP mode override on HPD disconnect");
MODULE_LICENSE("GPL");
