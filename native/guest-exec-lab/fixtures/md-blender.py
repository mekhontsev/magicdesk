"""Opt-in Blender fixture: report a real viewport draw, without startup prompts."""
import bpy
import gpu
import json

bpy.context.preferences.view.show_splash = False
bpy.context.preferences.view.use_save_prompt = False
frames = 0


def drawn():
    global frames
    frames += 1
    if frames == 2:
        print("MD_FRAME_READY " + json.dumps({
            "renderer": gpu.platform.renderer_get(),
            "version": gpu.platform.version_get(),
            "vendor": gpu.platform.vendor_get(),
            "frames": frames,
        }), flush=True)
    elif frames == 1:
        bpy.context.area.tag_redraw()


bpy.types.SpaceView3D.draw_handler_add(drawn, (), "WINDOW", "POST_PIXEL")
