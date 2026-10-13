package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopActiveWindowBorderTest {
    @Test public void changedGeometryReplacesEdgesWithoutWindowMoveAnimations() throws Exception {
        RuntimeSourceFixture.verify("""
            interface IBinder {}
            static class Rect {
                int left,top,right,bottom;
                Rect() {}
                Rect(int l,int t,int r,int b) {left=l;top=t;right=r;bottom=b;}
                int width(){return right-left;} int height(){return bottom-top;}
                boolean isEmpty(){return width()<=0 || height()<=0;}
                void setEmpty(){left=top=right=bottom=0;}
                void set(Rect r){left=r.left;top=r.top;right=r.right;bottom=r.bottom;}
                boolean intersect(Rect r){
                    if(left>=r.right || right<=r.left || top>=r.bottom || bottom<=r.top)return false;
                    left=Math.max(left,r.left);top=Math.max(top,r.top);
                    right=Math.min(right,r.right);bottom=Math.min(bottom,r.bottom);return true;
                }
                public boolean equals(Object o){return o instanceof Rect r && left==r.left
                    && top==r.top && right==r.right && bottom==r.bottom;}
            }
            static class View {void setBackgroundColor(int color){}}
            static class Context {
                Context getResources(){return this;} Context getDisplayMetrics(){return this;}
                float density=1;
                InputManager getSystemService(Class<?> type){return new InputManager();}
            }
            static class InputManager {float getMaximumObscuringOpacityForTouch(){return .8f;}}
            enum UiColor {ACCENT}
            static class UiAppearance {static int color(Context c,UiColor r){return 1;}}
            static class PixelFormat {static final int TRANSLUCENT=0;}
            static class Gravity {static final int TOP=1,LEFT=2;}
            static class WindowManager {
                final List<String> events=new ArrayList<>();
                static class LayoutParams {
                    static final int TYPE_APPLICATION_PANEL=1, FLAG_NOT_FOCUSABLE=2,
                        FLAG_NOT_TOUCHABLE=4, FLAG_NOT_TOUCH_MODAL=8, SOFT_INPUT_ADJUST_NOTHING=16;
                    int gravity,x,y,softInputMode,windowAnimations,flags; float alpha; IBinder token;
                    LayoutParams(int w,int h,int type,int flags,int format){this.flags=flags;}
                    void setFitInsetsTypes(int types){} void setTitle(String title){}
                }
                void addView(View view,LayoutParams p){
                    check((p.flags&4)!=0 && (p.flags&2)!=0,"border must not take input or focus");
                    check(p.windowAnimations==0 && p.alpha<=.8f,"passive border limits");events.add("add");
                }
                void removeViewImmediate(View view){events.add("remove");}
            }
            final Context context=new Context();
            final WindowManager windows=new WindowManager();
            final View[] edges={new View(),new View(),new View(),new View()};
            final Rect[] applied={new Rect(),new Rect(),new Rect(),new Rect()};
            final boolean[] added=new boolean[4];
            public static void verify(){
                var f=new Fixture(); var token=new IBinder(){}; var area=new Rect(0,0,1000,800);
                f.update(token,new Rect(20,20,400,700),area);
                check(f.windows.events.equals(List.of("add","add","add","add")),"initial border");
                f.windows.events.clear();
                f.update(token,new Rect(20,20,400,700),area);
                check(f.windows.events.isEmpty(),"unchanged geometry must not recreate chrome");
                f.update(token,new Rect(500,20,900,700),area);
                check(f.windows.events.equals(List.of("remove","remove","remove","remove",
                    "add","add","add","add")),"remove old border before showing the new target");
                f.windows.events.clear(); f.update(token,null,area);
                check(f.windows.events.equals(List.of("remove","remove","remove","remove")),"cleanup");
            }
            """ + RuntimeSourceFixture.methods("DesktopActiveWindowBorder", "update", "remove", "clear"));
    }
}
