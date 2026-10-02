package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class UiDialogAppearanceTest {
    @Test public void lazyAndRecycledChoiceRowsShareDialogColorsWithoutReplacingClicks() throws Exception {
        String method = RuntimeSourceFixture.methods("UiAppearance", "dialogContents")
                .replace("android.widget.", "").replace("android.view.", "").replace("android.R.attr.", "Attrs.");
        RuntimeSourceFixture.verify("""
                enum UiColor { TEXT, MUTED, ACCENT }
                enum Property { CHECK_MARK }
                static class R { static class id { static int appearance_binding=1; } }
                static class Attrs { static int state_enabled=1,state_checked=2; }
                static class Theme { Theme palette(){return this;} int color(UiColor role){return role.ordinal();} }
                static class ShellControls { static int disabledContent(Theme theme){return 3;} }
                static class ColorStateList { int[] colors; ColorStateList(int[][] states,int[] colors){this.colors=colors;} }
                interface Style {void apply(View v,Theme t);}
                static class View { Object tag; Object getTag(int id){return tag;} }
                static class TextView extends View { UiColor text; }
                static class CheckedTextView extends TextView {
                    ColorStateList mark, compound; void setCheckMarkTintList(ColorStateList c){mark=c;}
                    void setCompoundDrawableTintList(ColorStateList c){compound=c;}
                }
                static class ViewGroup extends View {
                    interface OnHierarchyChangeListener {void onChildViewAdded(View p,View c);void onChildViewRemoved(View p,View c);}
                    List<View> children=new ArrayList<>(); OnHierarchyChangeListener listener;
                    int getChildCount(){return children.size();} View getChildAt(int i){return children.get(i);}
                    void add(View v){children.add(v);if(listener!=null)listener.onChildViewAdded(this,v);}
                }
                static class AbsListView extends ViewGroup {
                    Object click=new Object(); void setOnHierarchyChangeListener(OnHierarchyChangeListener l){listener=l;}
                }
                static void textStates(TextView v,UiColor role){v.text=role;v.tag=true;}
                static void bind(View v,Property p,Style style){style.apply(v,new Theme());v.tag=true;}
                """ + method + """
                public static void verify() {
                    var root=new ViewGroup();var list=new AbsListView();root.add(list);
                    Object click=list.click;
                    dialogContents(root);
                    var first=new CheckedTextView();list.add(first);
                    check(first.text==UiColor.TEXT,"late row retained system text color");
                    check(Arrays.equals(first.mark.colors,new int[]{3,2,0}),"choice mark does not follow palette");
                    check(first.compound==first.mark,"drawableStart choice indicator does not follow palette");
                    list.listener.onChildViewRemoved(list,first);list.add(first);
                    check(first.text==UiColor.TEXT && list.click==click,"recycling changed style or selection handler");
                    var explicit=new TextView();explicit.tag=true;explicit.text=UiColor.ACCENT;list.add(explicit);
                    check(explicit.text==UiColor.ACCENT,"explicit semantic color overwritten");
                    var later=new CheckedTextView();list.add(later);
                    check(later.mark!=null && later.text==UiColor.TEXT,"scroll-created row was missed");
                }
                """);
    }
}
