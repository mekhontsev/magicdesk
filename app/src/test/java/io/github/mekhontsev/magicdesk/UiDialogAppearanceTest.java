package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class UiDialogAppearanceTest {
    @Test public void everyModalStylesItsCreatedContentBeforePresentation() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Activity { }
                static class R { static class id { static int content=1; } }
                static class AlertDialog {
                    static class Builder {
                        Builder(Activity owner) { }
                        AlertDialog create() { return new AlertDialog(); }
                    }
                    Activity owner;
                    boolean created, themed, fitted, showing;
                    Runnable onDismiss;
                    void setOwnerActivity(Activity value) { owner=value; }
                    Activity getOwnerActivity() { return owner; }
                    boolean isShowing() { return showing; }
                    void dismiss() { showing=false;if(onDismiss!=null)onDismiss.run(); }
                    void create() { created=true; }
                    AlertDialog findViewById(int id) { return this; }
                }
                static class UiAppearance {
                    static void dialog(AlertDialog dialog) {
                        check(dialog.created && dialog.owner!=null,"appearance applied before content/owner exists");
                        dialog.themed=true;
                    }
                }
                static class DialogContentInsets {
                    static void bind(AlertDialog content,Activity owner) {
                        check(content.created && content.owner==owner,"wrong modal content or owner");
                        content.fitted=true;
                    }
                }
                """ + "static " + RuntimeSourceFixture.nestedClass("UiDialogs", "UiDialogs")
                        .replace("android.R.id.", "R.id.") + """
                public static void verify() {
                    Activity owner=new Activity();
                    AlertDialog first=UiDialogs.builder(owner).create();
                    check(first.owner==owner && first.themed && first.fitted,"default modal skipped scoped appearance");
                    AlertDialog second=UiDialogs.builder(owner).create();
                    check(first!=second && second.themed && second.fitted,"appearance depends on a prior modal");
                    Activity other=new Activity();
                    AlertDialog external=UiDialogs.builder(other).create();
                    first.showing=second.showing=external.showing=true;
                    first.onDismiss=() -> UiDialogs.builder(other).create();
                    UiDialogs.dismissOwned(owner);
                    check(!first.showing && !second.showing && external.showing,"dismiss crossed owner boundary");
                    UiDialogs.dismissOwned(owner);
                    check(external.showing,"repeated dismiss affected another activity");
                }
                """);
    }

    @Test public void lazyAndRecycledChoiceRowsShareDialogColorsWithoutReplacingClicks() throws Exception {
        String method = RuntimeSourceFixture.methods("UiAppearance", "contents")
                .replace("android.widget.", "").replace("android.view.", "").replace("android.R.attr.", "Attrs.");
        RuntimeSourceFixture.verify("""
                enum UiColor { TEXT, MUTED, ACCENT, SURFACE }
                enum Property { CHECK_MARK }
                static class R { static class id { static int appearance_binding=1; } }
                static class Attrs { static int state_enabled=1,state_checked=2; }
                static class Theme { Theme palette(){return this;} int color(UiColor role){return role.ordinal();} }
                static class ShellControls { static int disabledContent(Theme theme){return 3;} }
                static class ColorStateList { int[] colors; ColorStateList(int[][] states,int[] colors){this.colors=colors;} }
                interface Style {void apply(View v,Theme t);}
                static class View { Object tag; Object getTag(int id){return tag;} }
                static class TextView extends View { UiColor text; }
                static class EditText extends TextView { UiColor hint, background; }
                static class Button extends TextView { UiColor background; }
                static class CompoundButton extends Button { }
                static class ProgressBar extends View { UiColor progress; }
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
                static void hint(TextView v,UiColor role){((EditText)v).hint=role;}
                static void backgroundTint(View v,UiColor role){
                    if(v instanceof EditText e)e.background=role; else ((Button)v).background=role;
                }
                static void progress(ProgressBar v,UiColor role){v.progress=role;v.tag=true;}
                static void bind(View v,Property p,Style style){style.apply(v,new Theme());v.tag=true;}
                """ + method + """
                public static void verify() {
                    var root=new ViewGroup();var list=new AbsListView();root.add(list);
                    Object click=list.click;
                    contents(root);
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
                    var input=new EditText();list.add(input);
                    check(input.text==UiColor.TEXT && input.hint==UiColor.MUTED && input.background==UiColor.MUTED,
                            "input hint or underline retained the platform palette");
                    var button=new Button();list.add(button);
                    check(button.text==UiColor.TEXT && button.background==UiColor.SURFACE,"button has mixed contrast");
                    var toggle=new CompoundButton();list.add(toggle);
                    check(toggle.background==null,"checkbox received a button background");
                    var progress=new ProgressBar();list.add(progress);
                    check(progress.progress==UiColor.ACCENT,"slider retained the platform accent");
                }
                """);
    }
}
