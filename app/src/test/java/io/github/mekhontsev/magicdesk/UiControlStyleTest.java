package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class UiControlStyleTest {
    @Test public void liveStylesRetainStateDrawablesAndRestoreHostMetrics() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static final UiColor TRANSPARENT=UiColor.TRANSPARENT, TEXT=UiColor.TEXT, MUTED=UiColor.MUTED,
                    SURFACE=UiColor.SURFACE, SURFACE_HIGH=UiColor.SURFACE_HIGH, ACCENT_CONTAINER=UiColor.ACCENT_CONTAINER;
                static class android {
                    static class R { static class attr {
                        static final int state_enabled=1,state_pressed=2,state_focused=3,state_checked=4,state_selected=5,state_hovered=6;
                    } }
                    static class widget { static class Switch extends TextView {
                        ColorStateList thumb,track;
                        void setThumbTintList(ColorStateList colors) { thumb=colors; }
                        void setTrackTintList(ColorStateList colors) { track=colors; }
                    } }
                }
                record ColorStateList(int[][] states,int[] colors) {}
                static class Metrics { float density=2; }
                static class Resources { Metrics metrics=new Metrics(); Metrics getDisplayMetrics() { return metrics; } }
                static class GradientDrawable {
                    int color,width,outline; float radius;
                    void setColor(int value) { color=value; }
                    void setCornerRadius(float value) { radius=value; }
                    void setStroke(int value,int color) { width=value; outline=color; }
                }
                static class StateListDrawable {
                    List<GradientDrawable> paints=new ArrayList<>();
                    void addState(int[] state,GradientDrawable paint) { paints.add(paint); }
                    void setEnterFadeDuration(int value) {} void setExitFadeDuration(int value) {}
                }
                static class View {
                    final Resources resources=new Resources();
                    int left=7,top=5,right=9,bottom=6,minimum=42;
                    boolean attached;
                    StateListDrawable background;
                    Resources getResources() { return resources; }
                    boolean isAttachedToWindow() { return attached; }
                    StateListDrawable getBackground() { return background; }
                    void setBackground(StateListDrawable value) { background=value; }
                    int getPaddingLeft() { return left; } int getPaddingTop() { return top; }
                    int getPaddingRight() { return right; } int getPaddingBottom() { return bottom; }
                    int getMinimumHeight() { return minimum; }
                    void setMinimumHeight(int value) { minimum=value; }
                    void setPadding(int l,int t,int r,int b) { left=l;top=t;right=r;bottom=b; }
                }
                static class TextView extends View {
                    int minHeight=40,selection=3; String text="unchanged search";
                    ColorStateList colors,compound;
                    int getMinHeight() { return minHeight; } void setMinHeight(int value) { minHeight=value; }
                    void setTextColor(ColorStateList value) { colors=value; }
                    void setCompoundDrawableTintList(ColorStateList value) { compound=value; }
                }
                static class ImageView extends View { ColorStateList colors; void setImageTintList(ColorStateList value) { colors=value; } }
                static class UiAppearance {
                """ + RuntimeSourceFixture.methods("UiAppearance", "states") + """
                }
                public static void verify() {
                    var base=ShellAppearance.defaults();
                    var paint=new ShellControls.Paint(UiColor.ACCENT,UiColor.ON_ACCENT,UiColor.TRANSPARENT,UiColor.TEXT,0,1);
                    var style=new ShellControls.Style(ShellControls.Shape.CAPSULE,8f,2f,10,4,48,16f,600,paint,Map.of());
                    var themed=base.withControls(new ShellControls(Map.of(ShellControls.Role.SEARCH_FIELD,style)));
                    var view=new TextView();
                    var control=new UiControlStyle(ShellControls.Role.SEARCH_FIELD,UiColor.ACCENT,false);
                    control.apply(view,themed,UiColor.TEXT);
                    check(view.left==7 && view.minimum==42,"captured partially configured construction metrics");
                    view.attached=true;
                    control.apply(view,themed,UiColor.TEXT);
                    check(view.left==20 && view.right==20 && view.top==8 && view.minimum==96 && view.minHeight==96,"metrics not applied");
                    var background=view.background;
                    check(background.paints.size()==7,"missing state/checked mapping");
                    check(background.paints.stream().allMatch(p->p.radius==10000),"state changes geometry");
                    check(view.colors.colors()[6]==base.palette().color(UiColor.ON_ACCENT),"paired content color missing");
                    check(background.paints.get(2).width==4,"focus outline missing");
                    control.apply(view,base,UiColor.TEXT);
                    check(view.background==background,"replaced retained drawable");
                    check(view.left==7 && view.top==5 && view.right==9 && view.bottom==6,"cancel lost baseline padding");
                    check(view.minimum==42 && view.minHeight==40,"cancel lost minimum size");
                    check(view.text.equals("unchanged search") && view.selection==3,"changed input contents");
                    control.setBaselinePadding(11,12,13,14);
                    control.apply(view,themed,UiColor.TEXT);
                    check(view.left==20 && view.top==8,"host metrics override explicit theme");
                    control.apply(view,base,UiColor.TEXT);
                    check(view.left==11 && view.top==12 && view.right==13 && view.bottom==14,"live host metrics lost on cancellation");
                    var panel=new UiControlStyle(ShellControls.Role.PANEL_BUTTON,UiColor.ACCENT,false);
                    var button=new TextView(); button.attached=true; panel.apply(button,base,UiColor.ACCENT);
                    check(button.background.paints.get(6).color==0,"idle panel button is not transparent");
                    check(button.background.paints.get(6).width==0,"idle panel button has outline");
                    check(button.colors.colors()[6]==base.palette().color(UiColor.ACCENT),"lost host status color");
                    panel.apply(button,base,UiColor.TEXT);
                    check(button.colors.colors()[6]==base.palette().color(UiColor.TEXT),"status color did not update");
                    var toggle=new android.widget.Switch();
                    new UiControlStyle(ShellControls.Role.SWITCH,UiColor.ACCENT,false).apply(toggle,base,UiColor.TEXT);
                    check(toggle.background==null && toggle.thumb!=null && toggle.track!=null,"replaced platform switch");
                    check(toggle.thumb.colors()[3]==base.palette().color(UiColor.ACCENT),"checked switch lost accent");
                    for (String preset : List.of("light","dark","contrast")) {
                        var theme=ShellAppearance.preset(preset);
                        int disabled=ShellControls.disabledContent(theme.palette());
                        check(disabled>>>24==97,"disabled content is not visibly dimmed");
                        for (var role : ShellControls.Role.values()) {
                            if (role==ShellControls.Role.SWITCH) continue;
                            new UiControlStyle(role,UiColor.ACCENT,false).apply(button,theme,UiColor.TEXT);
                            check(button.colors.colors()[0]==disabled,"disabled text disagrees for "+role);
                            check(button.compound==button.colors,"compound icon has different states");
                            var image=new ImageView();
                            new UiControlStyle(role,UiColor.ACCENT,false).apply(image,theme,UiColor.TEXT);
                            check(image.colors.colors()[0]==disabled,"disabled image disagrees for "+role);
                        }
                        new UiControlStyle(ShellControls.Role.SWITCH,UiColor.ACCENT,false).apply(toggle,theme,UiColor.TEXT);
                        check(toggle.thumb.colors()[0]==disabled && toggle.colors.colors()[0]==disabled,"disabled switch label and thumb disagree");
                        check(toggle.colors.colors()[1]==theme.palette().color(UiColor.TEXT),"switch label lost enabled content color");
                    }
                }
                """ + RuntimeSourceFixture.nestedClass("UiControlStyle", "UiControlStyle")
                        .replace("final class UiControlStyle", "static final class UiControlStyle"),
                "ShellAppearance", "ShellControls", "ShellComposition", "ShellPanel", "ShellMotion", "ShellResources", "UiColor");
    }
}
