package io.github.mekhontsev.magicdesk;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Native shell contents, independent of Views, service permissions and task ownership. */
public record ShellComposition(List<ShellPanel> panels, Start start) {
    public enum Kind {
        START, TASKS, SHOW_DESKTOP, OPEN_TASKS, NOTIFICATIONS, KEYBOARD_LAYOUT,
        PHONE_SCREEN, QUICK_CONTROLS, BATTERY, CLOCK, SPACER
    }
    public enum Visibility { ALWAYS, EXPANDED, EXTERNAL }
    public enum Clock { TIME, DATE, DATE_TIME }
    public enum Group { START, CENTER, END }
    public enum Battery { PERCENT, ICON, BOTH }
    public enum Indicator { LINE, DOT, NONE }
    public record Component(Kind type, int widthDp, int minViewportDp,
            Visibility visibility, String label, Clock clock, Group group, Battery battery, Indicator indicator) {
        public Component {
            Objects.requireNonNull(type); Objects.requireNonNull(visibility);
            Objects.requireNonNull(label); Objects.requireNonNull(clock);
            Objects.requireNonNull(group); Objects.requireNonNull(battery); Objects.requireNonNull(indicator);
            ShellAppearance.range(widthDp, 0, 240, "component width");
            if (widthDp > 0 && widthDp < 32) throw new IllegalArgumentException("component width must be 0 or at least 32");
            ShellAppearance.range(minViewportDp, 0, 4096, "minimum viewport");
            if (label.length() > 32 || label.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("component label must be at most 32 printable characters");
            }
            if (!label.isEmpty() && type != Kind.START) throw new IllegalArgumentException("Only Start has a configurable label");
            if (clock != Clock.TIME && type != Kind.CLOCK) throw new IllegalArgumentException("Only Clock has a clock format");
            if (battery != Battery.PERCENT && type != Kind.BATTERY) throw new IllegalArgumentException("Only Battery has a battery format");
            if (indicator != Indicator.LINE && type != Kind.TASKS) throw new IllegalArgumentException("Only Tasks has a task indicator");
        }
        public boolean visible(boolean compact, boolean external, int viewportDp) {
            return viewportDp >= minViewportDp && switch (visibility) {
                case ALWAYS -> true;
                case EXPANDED -> !compact;
                case EXTERNAL -> external && !compact;
            };
        }
        public static Component of(Kind kind) {
            return new Component(kind, 0, 0, kind == Kind.PHONE_SCREEN ? Visibility.EXTERNAL
                    : kind == Kind.KEYBOARD_LAYOUT ? Visibility.EXPANDED : Visibility.ALWAYS, "", Clock.TIME,
                    Group.START, Battery.PERCENT, Indicator.LINE);
        }
        public Component withPresentation(Group position, Battery power, Indicator marker) {
            return new Component(type, widthDp, minViewportDp, visibility, label, clock, position, power, marker);
        }
    }
    public enum Section { RECENT, APPS, RUNNING, TOOLS }
    public enum Presentation { GRID, LIST }
    public enum Navigation { SCROLL, PAGES }
    public record Start(List<Section> sections, Presentation presentation, int tileWidthDp, int iconSizeDp,
            Navigation navigation, int gapDp) {
        public Start {
            sections = List.copyOf(sections); Objects.requireNonNull(presentation);
            Objects.requireNonNull(navigation);
            if (!sections.contains(Section.APPS) || new HashSet<>(sections).size() != sections.size()) {
                throw new IllegalArgumentException("Start sections must include apps and must not repeat");
            }
            ShellAppearance.range(tileWidthDp, 80, 200, "tile width");
            ShellAppearance.range(iconSizeDp, 24, 64, "icon size");
            ShellAppearance.range(gapDp, 0, 24, "entry gap");
        }
        public static Start defaults() {
            return new Start(List.of(Section.RECENT, Section.APPS, Section.RUNNING, Section.TOOLS),
                    Presentation.GRID, 88, 44, Navigation.SCROLL, 4);
        }
    }
    public ShellComposition {
        panels = List.copyOf(panels); Objects.requireNonNull(start);
        if (panels.isEmpty() || panels.size() > 4) throw new IllegalArgumentException("Shell needs 1-4 panels");
        var ids = new HashSet<String>();
        var seen = new HashSet<Kind>();
        for (ShellPanel panel : panels) {
            if (!ids.add(panel.id())) throw new IllegalArgumentException("Repeated panel id: " + panel.id());
            for (Component component : panel.components()) {
                if (component.type() != Kind.SPACER && !seen.add(component.type())) {
                    throw new IllegalArgumentException("Repeated shell component: " + component.type());
                }
            }
        }
    }
    public ShellPanel panel(String id) { return panels.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null); }
    public ShellPanel panelFor(Kind kind) {
        return panels.stream().filter(p -> p.components().stream().anyMatch(c -> c.type() == kind)).findFirst().orElse(panels.get(0));
    }
    public static ShellComposition defaults() {
        return new ShellComposition(List.of(new ShellPanel("main", ShellPanel.Edge.BOTTOM, ShellAppearance.PanelStyle.defaults(),
                java.util.Arrays.stream(Kind.values()).filter(k -> k != Kind.SPACER).map(Component::of).toList())), Start.defaults());
    }
}
