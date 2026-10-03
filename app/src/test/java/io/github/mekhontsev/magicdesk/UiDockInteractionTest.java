package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class UiDockInteractionTest {
    @Test public void publishingInputDoesNotResetHoverButReplacingItemsDoes() throws Exception {
        RuntimeSourceFixture.verify("""
            static class R { static class id { static int appearance_dock_item=1; } }
            static class View {
                static int VISIBLE=0; boolean tagged;
                int getVisibility(){return VISIBLE;} Object getTag(int id){return tagged;}
            }
            static class ViewGroup extends View {
                List<View> children=new ArrayList<>();
                int getChildCount(){return children.size();} View getChildAt(int i){return children.get(i);}
            }
            record Item(View view) {}
            List<Item> items=new ArrayList<>(); View root; int clears, updates;
            boolean enabled(){return true;} void updatePositions(){updates++;}
            void clear(){clears++;items.clear();root=null;}
            void collect(View view) {
                if(view.tagged)items.add(new Item(view));
                else if(view instanceof ViewGroup g)g.children.forEach(this::collect);
            }
            """ + RuntimeSourceFixture.methods("UiDockEffects", "layout", "matchingItems") + """
            public static void verify() {
                Fixture f=new Fixture(); ViewGroup root=new ViewGroup(); View a=new View(); a.tagged=true;
                root.children.add(a); f.layout(root,true);
                check(f.clears==1 && f.items.size()==1,"initial collection");
                f.layout(root,false);
                check(f.clears==1 && f.updates==1,"region layout reset hover");
                View b=new View(); b.tagged=true;root.children.set(0,b);f.layout(root,false);
                check(f.clears==2 && f.items.get(0).view==b,"replacement kept stale action");
                f.layout(root,true);check(f.clears==3,"resize did not refresh clipping");
            }
            """);
    }

    @Test public void transformedHitTargetsKeepOriginalActionsAndGestureOwnership() throws Exception {
        RuntimeSourceFixture.verify("""
            static class View {
                float scale=1, tx, ty; boolean shown=true, enabled=true;
                int touches, generic, lastAction; float lastX, lastY; boolean genericHandled=true;
                int getWidth(){return 48;} int getHeight(){return 48;}
                float getScaleX(){return scale;} float getScaleY(){return scale;}
                float getTranslationX(){return tx;} float getTranslationY(){return ty;}
                boolean isShown(){return shown;} boolean isEnabled(){return enabled;}
                boolean dispatchTouchEvent(MotionEvent e){touches++; record(e); return true;}
                boolean dispatchGenericMotionEvent(MotionEvent e){generic++; record(e); return genericHandled;}
                void record(MotionEvent e){lastAction=e.action; lastX=e.x; lastY=e.y;}
            }
            static class MotionEvent {
                static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_BUTTON_PRESS=11,ACTION_BUTTON_RELEASE=12;
                static final int ACTION_HOVER_ENTER=9,ACTION_HOVER_EXIT=10,ACTION_HOVER_MOVE=7;
                int action,buttons; float x,y; boolean recycled, mouse=true;
                MotionEvent(int a,float x,float y){action=a;this.x=x;this.y=y;}
                static MotionEvent obtain(MotionEvent e){return new MotionEvent(e.action,e.x,e.y);}
                int getActionMasked(){return action;} float getX(){return x;} float getY(){return y;}
                boolean isFromSource(int source){return mouse;}
                int getButtonState(){return buttons;}
                void setAction(int a){action=a;} void setLocation(float x,float y){this.x=x;this.y=y;}
                void recycle(){recycled=true;}
            }
            static class UiMotion { static boolean running; static boolean runningWithin(View root){return running;} }
            View root=new View(); boolean enabled=true;
            List<Item> items=new ArrayList<>(); Item pressed,hover; float pointer;
            static class Edge { boolean vertical(){return false;} } Edge edge=new Edge();
            int animations;
            boolean enabled(){return enabled;} void updatePositions(){} void stopAnimation(){}
            void reset(){} void animate(){animations++;}
            """ + RuntimeSourceFixture.nestedClass("UiDockEffects", "Item")
                + RuntimeSourceFixture.methods("UiDockEffects", "hit", "deliver", "touch", "generic", "hover") + """
            public static void verify() {
                Fixture f=new Fixture(); View first=new View(), second=new View();
                Item a=new Item(first,0,40), b=new Item(second,48,40);
                f.items.add(a); f.items.add(b);
                first.scale=2; first.ty=-12; a.amount=1; b.amount=.2f;
                check(f.hit(24,10)==a, "overflow did not hit enlarged icon");
                check(f.hit(50,50)==a, "overlap did not choose topmost icon");
                f.hover=a;
                MotionEvent exit=new MotionEvent(10,24,10);exit.buttons=1;
                check(f.hover(exit) && f.hover==a && f.animations==0, "mouse press collapsed hover before DOWN");
                MotionEvent down=new MotionEvent(0,24,10);
                down.mouse=false;
                check(!f.touch(down) && first.touches==0, "finger scrolling bypassed the framework");
                down.mouse=true;
                check(f.touch(down) && first.touches==1, "original action view was bypassed");
                check(first.lastX==24 && first.lastY==3, "inverse transform incorrect");
                check(down.x==24 && down.y==10 && !down.recycled, "original event mutated");
                f.touch(new MotionEvent(2,90,50)); f.touch(new MotionEvent(1,90,50));
                check(first.touches==3 && second.touches==0 && f.pressed==null, "gesture switched targets");
                check(f.generic(new MotionEvent(11,24,10)) && first.generic==1, "context click lost outside slot");
                first.genericHandled=false;
                check(f.generic(new MotionEvent(12,24,10)) && first.generic==2, "transformed dispatch would be replayed by parent");
                first.enabled=false; check(f.hit(50,50)==b, "disabled item received input");
                first.enabled=true; a.inViewport=false; check(f.hit(24,10)==null, "scrolled-out item received input");
                a.inViewport=true; first.shown=false; check(f.hit(24,10)==null, "hidden item received input");
                first.shown=true; f.enabled=false; check(!f.touch(down), "disabled effect intercepted touch");
                f.enabled=true; UiMotion.running=true; check(!f.touch(down), "reveal transform bypassed framework dispatch");
            }
            """);
    }
}
