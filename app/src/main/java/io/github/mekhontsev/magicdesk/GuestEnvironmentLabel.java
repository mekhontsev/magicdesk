package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.List;

/** Only visible rows subscribe; recycling and detachment release the old environment. */
final class GuestEnvironmentLabel extends TextView {
    private GuestEnvironmentCatalog.Entry entry;
    private String title;
    private boolean details;
    private Runnable release;

    GuestEnvironmentLabel(Context context) { super(context); }
    void bind(GuestEnvironmentCatalog.Entry value,String caption,boolean showDetails) {
        unsubscribe();
        entry=value; title=caption; details=showDetails;
        render(GuestAppearance.EMPTY);
        if (isAttachedToWindow()) subscribe();
    }
    private void render(GuestAppearance appearance) {
        var text=new SpannableStringBuilder(title);
        if (details && !appearance.prettyName().isEmpty() && !appearance.prettyName().equalsIgnoreCase(entry.name())) {
            text.append("\n").append(appearance.prettyName());
            text.setSpan(new RelativeSizeSpan(.82f),title.length()+1,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        setText(text);
        var icon=appearance.logo()==null ? getContext().getDrawable(R.mipmap.ic_launcher).mutate()
                : new BitmapDrawable(getResources(),appearance.logo());
        int size=UiToolLayout.dp(getContext(),24);
        float ratio=(float)Math.max(1,icon.getIntrinsicWidth())/Math.max(1,icon.getIntrinsicHeight());
        icon.setBounds(0,0,Math.max(1,Math.round(size*Math.min(1,ratio))),Math.max(1,Math.round(size/Math.max(1,ratio))));
        setCompoundDrawablesRelative(icon,null,null,null);
        setCompoundDrawablePadding(UiToolLayout.dp(getContext(),8));
    }
    private void subscribe() {
        if (entry==null || release!=null) return;
        var cache=ApplicationIcons.get(getContext()).distributions;
        Runnable changed=()->{
            var appearance=cache.snapshot().get(entry);
            render(appearance!=null ? appearance : GuestAppearance.EMPTY);
        };
        release=cache.subscribe(entry,changed);
        changed.run();
    }
    private void unsubscribe() { if (release!=null) { release.run(); release=null; } }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); subscribe(); }
    @Override protected void onDetachedFromWindow() { unsubscribe(); super.onDetachedFromWindow(); }

    static final class Adapter extends UiChoiceAdapter<GuestEnvironmentCatalog.Entry> {
        private final boolean executor;
        Adapter(Context context,List<GuestEnvironmentCatalog.Entry> entries,boolean executor) {
            super(context,entries); this.executor=executor;
        }
        @Override public View getView(int pos,View old,ViewGroup parent) {
            var row=old instanceof GuestEnvironmentLabel previous ? previous : new GuestEnvironmentLabel(getContext());
            int pad=UiToolLayout.dp(getContext(),8);
            row.setPadding(pad*2,pad,pad*2,pad); row.setMinHeight(pad*6); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMaxLines(2); row.setEllipsize(android.text.TextUtils.TruncateAt.END); row.setTextSize(16);
            UiAppearance.text(row,UiColor.TEXT);
            var entry=getItem(pos); row.bind(entry,executor ? entry.label(getContext()) : entry.name(),true);
            return row;
        }
        @Override public View getDropDownView(int pos,View old,ViewGroup parent) {
            var row=getView(pos,old,parent); UiAppearance.background(row,UiColor.PANEL); return row;
        }
    }
}
