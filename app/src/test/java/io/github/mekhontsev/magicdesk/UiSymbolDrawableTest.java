package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class UiSymbolDrawableTest {
    @Test public void nestedSymbolsRetainEnabledStateTintAndBoundsAcrossResourceChanges() throws Exception {
        RuntimeSourceFixture.verify("""
                enum UiColor { TEXT }
                record ColorStateList(int disabled,int enabled) { int color(int[] state) { return state.length==0?disabled:enabled; } }
                static class Canvas {} static class ColorFilter {} static class Rect {} static class Bitmap {}
                static class Drawable {
                    interface Callback { void invalidateDrawable(Drawable d); void scheduleDrawable(Drawable d,Runnable r,long when); void unscheduleDrawable(Drawable d,Runnable r); }
                    int[] state={}; Rect bounds=new Rect(); int alpha=255; ColorFilter filter; ColorStateList tint; Callback callback;
                    Drawable mutate(){return this;} int getIntrinsicWidth(){return 24;} int getIntrinsicHeight(){return 24;}
                    Rect getBounds(){return bounds;} void setBounds(Rect r){bounds=r;onBoundsChange(r);} protected void onBoundsChange(Rect r){}
                    void setAlpha(int a){alpha=a;} void setColorFilter(ColorFilter f){filter=f;} void setTintList(ColorStateList t){tint=t;}
                    void setCallback(Callback c){callback=c;} int[] getState(){return state;}
                    boolean setState(int[] s){state=s;return onStateChange(s);} protected boolean onStateChange(int[] s){return true;}
                    boolean isStateful(){return tint!=null;} void draw(Canvas c){} int getOpacity(){return 0;}
                    void invalidateSelf(){} void scheduleSelf(Runnable r,long t){} void unscheduleSelf(Runnable r){}
                }
                static class Resources { Drawable last; Drawable getDrawable(int id,Object theme){return last=new Drawable();} }
                static class BitmapDrawable extends Drawable { BitmapDrawable(Resources r,Bitmap b){} }
                static class Context { Resources resources=new Resources(); Resources getResources(){return resources;} }
                static class AppearanceScopeSource {
                    static int resource=1; static Bitmap asset; static ColorStateList tint=new ColorStateList(10,20);
                    AppearanceScopeSource(Context c){} AppearanceScopeSource resolve(){return this;}
                    AppearanceScopeSource theme(){return this;} AppearanceScopeSource resources(){return this;}
                    AppearanceScopeSource assets(){return this;} Bitmap icon(String path){return asset;}
                    Map<ShellResources.Icon,String> iconAssets(){return asset==null?Map.of():Map.of(ShellResources.Icon.TEST,"test");}
                }
                static class ShellResources { enum Icon { TEST } }
                static class ShellIconResources {
                    static int resolve(int id,AppearanceScopeSource source){return source.resource;}
                    static ShellResources.Icon role(int id){return ShellResources.Icon.TEST;}
                }
                static class UiAppearance { static ColorStateList states(AppearanceScopeSource theme,UiColor role){return theme.tint;} }
                public static void verify() {
                    Context context=new Context();
                    UiSymbolDrawable icon=new UiSymbolDrawable(context,1,UiColor.TEXT);
                    check(icon.isStateful(),"wrapper hid stateful tint from Android");
                    icon.setState(new int[]{1});
                    check(context.resources.last.tint.color(context.resources.last.state)==20,"enabled state lost");
                    icon.setState(new int[]{});
                    check(context.resources.last.tint.color(context.resources.last.state)==10,"disabled state lost");
                    var bounds=new Rect(); var filter=new ColorFilter();
                    icon.setBounds(bounds); icon.setAlpha(170); icon.setColorFilter(filter);
                    AppearanceScopeSource.resource=2; AppearanceScopeSource.tint=new ColorStateList(30,40); icon.refresh();
                    Drawable replacement=context.resources.last;
                    check(replacement.bounds==bounds && replacement.alpha==170 && replacement.filter==filter,"resource replacement lost geometry/filter");
                    check(replacement.tint.color(replacement.state)==30,"theme edit enabled a disabled icon");
                    icon.setState(new int[]{1});
                    var custom=new ColorStateList(50,60); icon.setTintList(custom);
                    check(replacement.tint.color(replacement.state)==60,"control tint ignored by wrapper");
                    icon.setState(new int[]{}); icon.refresh();
                    check(replacement.tint.color(replacement.state)==50,"control disabled tint lost on refresh");
                    icon.setTintList(null); check(replacement.tint.color(replacement.state)==30,"clearing override lost semantic tint");
                    AppearanceScopeSource.asset=new Bitmap(); icon.refresh();
                    check(!icon.isStateful(),"custom bitmap artwork was recolored by default");
                    icon.setTintList(custom); check(icon.isStateful(),"explicit state tint ignored for custom asset");
                }
                """ + RuntimeSourceFixture.nestedClass("UiSymbolDrawable", "UiSymbolDrawable")
                        .replace("final class UiSymbolDrawable", "static final class UiSymbolDrawable")
                        .replace("android.graphics.PixelFormat.TRANSLUCENT", "0"));
    }
}
