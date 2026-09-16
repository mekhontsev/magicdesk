package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;
import java.util.function.Predicate;

/** Compact applications/processes views; recycled rows never own runtime resources. */
final class TaskManagerView {
    private final Activity activity;
    private final TaskManagerActions actions;
    private final LinearLayout root;
    private final TextView status, warning, empty;
    private final Spinner filter, sort, applicationSort;
    private final EditText search;
    private final ListView list;
    private final Rows adapter = new Rows();
    private final Set<SystemProcessSnapshot.Identity> expanded = new HashSet<>();
    private List<TaskManagerApplications.Entry> applications = List.of();
    private SystemMonitorRepository.Snapshot monitor = SystemMonitorRepository.Snapshot.unavailable("");
    private List<TaskManagerApplications.Entry> shownApps = List.of();
    private List<ProcessCatalog.Row> shownProcesses = List.of();
    private final Map<String, SystemMonitorRepository.Resources> applicationResources = new HashMap<>();
    private Set<SystemProcessSnapshot.Identity> selectedProcesses = Set.of();
    private String selectedTitle = "";
    private boolean processes;
    private final RadioButton processTab;

    TaskManagerView(Activity activity, Runnable refresh, TaskManagerActions actions) {
        this.activity = activity; this.actions = actions;
        root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        SystemBarInsets.addToPadding(root);

        final LinearLayout header = horizontal();
        final RadioGroup tabs = new RadioGroup(activity); tabs.setOrientation(LinearLayout.HORIZONTAL);
        final RadioButton apps = tab(R.string.task_manager_applications);
        processTab = tab(R.string.task_manager_processes);
        tabs.addView(apps); tabs.addView(processTab); apps.setChecked(true);
        header.addView(tabs, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button(R.drawable.ic_file_refresh, R.string.action_refresh, v -> refresh.run()), square(44));
        root.addView(header);
        status = text(12, true); status.setPadding(0, dp(6), 0, dp(6)); root.addView(status);
        warning = text(12, false); warning.setMaxLines(2); warning.setTextColor(0xFFFFC857); root.addView(warning);

        search = new EditText(activity);
        search.setTextSize(14); search.setSingleLine(true); search.setHint(R.string.task_manager_search);
        search.setTextColor(DesktopUiFactory.COLOR_TEXT); search.setHintTextColor(DesktopUiFactory.COLOR_MUTED);
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(44)));

        final LinearLayout options = horizontal();
        filter = spinner(new String[]{activity.getString(R.string.task_manager_user_processes), "Termux",
                activity.getString(R.string.task_manager_all_processes), activity.getString(R.string.task_manager_selection)});
        final String[] ordering = {activity.getString(R.string.command_app_name), "CPU",
                activity.getString(R.string.task_manager_memory), "PID", activity.getString(R.string.task_manager_tree)};
        applicationSort = spinner(Arrays.copyOf(ordering, 3));
        sort = spinner(ordering); sort.setSelection(TaskManagerSort.TREE.ordinal());
        final View spacer = new View(activity);
        options.addView(filter, new LinearLayout.LayoutParams(0, dp(44), 1));
        options.addView(spacer, new LinearLayout.LayoutParams(0, dp(44), 1));
        options.addView(sort, new LinearLayout.LayoutParams(dp(136), dp(44)));
        options.addView(applicationSort, new LinearLayout.LayoutParams(dp(136), dp(44)));
        filter.setVisibility(View.GONE); sort.setVisibility(View.GONE);
        root.addView(options);

        final View divider = new View(activity); divider.setBackgroundColor(DesktopUiFactory.COLOR_MUTED);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        empty = text(14, true); empty.setText(R.string.task_manager_empty); empty.setGravity(Gravity.CENTER);
        root.addView(empty, new LinearLayout.LayoutParams(-1, dp(64)));
        list = new ListView(activity); list.setAdapter(adapter); list.setEmptyView(empty);
        list.setDivider(new ColorDrawable(0xFF39424D)); list.setDividerHeight(dp(1));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        tabs.setOnCheckedChangeListener((group, id) -> {
            processes = id == processTab.getId();
            filter.setVisibility(processes ? View.VISIBLE : View.GONE);
            sort.setVisibility(processes ? View.VISIBLE : View.GONE);
            spacer.setVisibility(processes ? View.GONE : View.VISIBLE);
            applicationSort.setVisibility(processes ? View.GONE : View.VISIBLE);
            rebuild();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { rebuild(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        final AdapterView.OnItemSelectedListener selection = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) { rebuild(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        filter.setOnItemSelectedListener(selection); sort.setOnItemSelectedListener(selection);
        applicationSort.setOnItemSelectedListener(selection);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (processes) actions.processMenu(view, shownProcesses.get(position).entry().process());
            else actions.open(shownApps.get(position));
        });
    }

    View root() { return root; }

    void showUnavailable(String error) {
        render(List.of(), SystemMonitorRepository.Snapshot.unavailable(error), "");
    }

    void render(List<TaskManagerApplications.Entry> apps, SystemMonitorRepository.Snapshot snapshot, String error) {
        applications = apps; monitor = snapshot;
        applicationResources.clear();
        for (var entry : apps) applicationResources.put(entry.id(), snapshot.resources(entry.processes()));
        expanded.retainAll(snapshot.processes().stream().map(e -> e.process().identity()).collect(java.util.stream.Collectors.toSet()));
        final String details = String.join(" ", snapshot.error(), error).trim();
        warning.setText(details); warning.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
        rebuild();
    }

    void showProcesses(Set<SystemProcessSnapshot.Identity> ids, String title) {
        selectedProcesses = Set.copyOf(ids); selectedTitle = title;
        search.setText(""); filter.setSelection(3); sort.setSelection(TaskManagerSort.CPU.ordinal());
        processTab.setChecked(true); rebuild();
    }

    private void rebuild() {
        if (list == null) return;
        final String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shownApps = applications.stream().filter(e -> query.isEmpty()
                || (e.title() + " " + e.detail()).toLowerCase(Locale.ROOT).contains(query))
                .sorted(TaskManagerSort.values()[applicationSort.getSelectedItemPosition()]
                        .applications(e -> applicationResources.get(e.id()))).toList();
        final int termuxUid = TermuxIntegration.inspect(activity).uid;
        final int user = AppProfile.current(activity).userId;
        final int selectedFilter = filter.getSelectedItemPosition();
        final Predicate<SystemProcessSnapshot> accept = p -> {
            boolean owner = switch (selectedFilter) {
                case 1 -> p.uid == termuxUid && termuxUid >= 0;
                case 2 -> true;
                case 3 -> selectedProcesses.contains(p.identity());
                default -> p.uid / 100000 == user && p.uid % 100000 >= 10000;
            };
            return owner && (query.isEmpty() || (p.name + " " + p.pid + " " + p.uid).toLowerCase(Locale.ROOT).contains(query));
        };
        final TaskManagerSort order = TaskManagerSort.values()[sort.getSelectedItemPosition()];
        final var comparator = order.processes();
        shownProcesses = order == TaskManagerSort.TREE && query.isEmpty()
                ? new ProcessCatalog(monitor.processes()).tree(accept, expanded, comparator)
                : monitor.processes().stream().filter(e -> accept.test(e.process())).sorted(comparator)
                    .map(e -> new ProcessCatalog.Row(e, 0, false)).toList();
        final int count = processes ? shownProcesses.size() : shownApps.size();
        final String summary = activity.getString(R.string.task_manager_resources, count,
                percent(monitor.cpuPercent()), memory(monitor.availableMemoryKb()));
        status.setText(processes && selectedFilter == 3
                ? activity.getString(R.string.task_manager_selection_summary, summary, selectedTitle) : summary);
        adapter.notifyDataSetChanged();
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return processes ? shownProcesses.size() : shownApps.size(); }
        @Override public Object getItem(int pos) { return processes ? shownProcesses.get(pos) : shownApps.get(pos); }
        @Override public long getItemId(int pos) { return pos; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            final Row row;
            if (recycled == null) { row = new Row(); recycled = row.root; recycled.setTag(row); }
            else row = (Row) recycled.getTag();
            if (processes) {
                final var item = shownProcesses.get(position);
                final var p = item.entry().process();
                row.title.setText(TaskManagerSort.processName(p));
                row.detail.setText(activity.getString(R.string.task_manager_process_identity, p.pid, p.uid, p.state));
                row.metrics.setText(activity.getString(R.string.task_manager_process_resources,
                        percent(item.entry().cpuPercent()), memory(p.rssKb)));
                row.expand.setVisibility(item.children() ? View.VISIBLE : View.INVISIBLE);
                row.expand.setImageResource(expanded.contains(p.identity()) ? R.drawable.ic_arrow_down : R.drawable.ic_chevron_right);
                row.expand.setContentDescription(activity.getString(R.string.task_manager_tree) + " " + p.pid);
                row.expand.setOnClickListener(v -> {
                    if (!expanded.remove(p.identity())) expanded.add(p.identity()); rebuild();
                });
                row.root.setPadding(dp(Math.min(item.depth(), 6) * 12), 0, 0, 0);
                row.more.setOnClickListener(v -> actions.processMenu(v, p));
            } else {
                final var entry = shownApps.get(position);
                row.title.setText(entry.title());
                final String displays = entry.windows().stream().map(t -> "[" + t.displayId + "]")
                        .distinct().collect(java.util.stream.Collectors.joining(" "));
                row.detail.setText(activity.getString(R.string.task_manager_entry_detail, entry.detail(), displays.isEmpty()
                        ? activity.getString(R.string.terminal_window_closed) : displays));
                final var resources = applicationResources.get(entry.id());
                row.metrics.setText(activity.getString(R.string.task_manager_process_resources,
                        percent(resources.cpuPercent()), memory(resources.rssKb())));
                row.expand.setVisibility(View.GONE); row.root.setPadding(0, 0, 0, 0);
                row.more.setOnClickListener(v -> actions.menu(v, entry));
            }
            row.title.setTooltipText(row.title.getText()); row.detail.setTooltipText(row.detail.getText());
            return recycled;
        }
    }

    private final class Row {
        final LinearLayout root = horizontal();
        final TextView title = text(14, false), detail = text(11, false), metrics = text(11, false);
        final ImageButton expand = button(R.drawable.ic_chevron_right, R.string.task_manager_tree, null);
        final ImageButton more = button(R.drawable.ic_more, R.string.terminal_session_actions_generic, null);
        Row() {
            root.setMinimumHeight(dp(78));
            root.addView(expand, square(32));
            final LinearLayout identity = new LinearLayout(activity); identity.setOrientation(LinearLayout.VERTICAL);
            identity.setPadding(dp(4), dp(6), dp(6), dp(6));
            title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
            detail.setTextColor(DesktopUiFactory.COLOR_MUTED); detail.setMaxLines(2); detail.setEllipsize(TextUtils.TruncateAt.END);
            identity.addView(title); identity.addView(detail);
            root.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
            metrics.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            root.addView(metrics, new LinearLayout.LayoutParams(dp(98), -1));
            root.addView(more, square(44));
        }
    }

    private RadioButton tab(int label) {
        final RadioButton button = new RadioButton(activity);
        button.setId(View.generateViewId()); button.setText(label); button.setTextSize(14);
        button.setTextColor(DesktopUiFactory.COLOR_TEXT); return button;
    }
    private Spinner spinner(String[] values) {
        final Spinner spinner = new Spinner(activity);
        final ArrayAdapter<String> choices = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, values);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(choices); return spinner;
    }
    private LinearLayout horizontal() {
        final LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); return row;
    }
    private TextView text(int size, boolean single) {
        final TextView text = new TextView(activity); text.setTextSize(size);
        text.setTextColor(DesktopUiFactory.COLOR_TEXT);
        if (single) { text.setSingleLine(true); text.setEllipsize(TextUtils.TruncateAt.END); }
        return text;
    }
    private ImageButton button(int icon, int label, View.OnClickListener click) {
        final ImageButton button = new ImageButton(activity); button.setImageResource(icon);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(DesktopUiFactory.COLOR_TEXT));
        button.setBackgroundColor(Color.TRANSPARENT); button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setContentDescription(activity.getString(label)); button.setTooltipText(button.getContentDescription());
        button.setFocusable(false); button.setOnClickListener(click); return button;
    }
    private String memory(long kb) { return kb < 0 ? "--" : Formatter.formatShortFileSize(activity, kb * 1024); }
    private static String percent(float value) { return value < 0 ? "--" : String.format(Locale.ROOT, "%.1f%%", value); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams square(int size) { return new LinearLayout.LayoutParams(dp(size), dp(size)); }
}
