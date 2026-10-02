package io.github.mekhontsev.magicdesk;

import java.util.List;

/** Axis-neutral groups. Overflow scrolls; centering never overlaps side groups. */
final class ShellComponentLayout {
    record Slot(int minimum, int preferred, boolean flexible, ShellComposition.Group group) {
        Slot {
            if (minimum < 0 || preferred < minimum) throw new IllegalArgumentException("invalid component size");
            java.util.Objects.requireNonNull(group);
        }
    }
    record Layout(int[] offsets, int[] lengths, int extent) { }

    static Layout resolve(List<Slot> slots, int available) {
        int minimum = 0, flexible = 0;
        boolean[] present = new boolean[3];
        for (var slot : slots) {
            minimum += slot.minimum();
            if (slot.flexible()) flexible++;
            if (slot.preferred() > 0 || slot.flexible()) present[slot.group().ordinal()] = true;
        }
        int groups = 0;
        for (boolean value : present) if (value) groups++;
        int extent = Math.max(Math.max(0, available), minimum), extra = extent - minimum;
        int[] lengths = new int[slots.size()], totals = new int[3], offsets = new int[slots.size()];
        for (int i = 0; i < lengths.length; i++) lengths[i] = slots.get(i).minimum();
        // A start-only toolbar fills its axis. Anchored groups grow only to their natural contents.
        boolean fill = groups <= 1 && present[ShellComposition.Group.START.ordinal()];
        while (extra > 0 && flexible > 0) {
            int remaining = flexible;
            for (int i = 0; i < lengths.length; i++) {
                var slot = slots.get(i);
                if (!slot.flexible() || !fill && lengths[i] >= slot.preferred()) continue;
                int share = extra / remaining;
                if (!fill) share = Math.min(share, slot.preferred() - lengths[i]);
                lengths[i] += share; extra -= share; remaining--;
            }
            if (fill) break;
            flexible = 0;
            for (int i = 0; i < lengths.length; i++) {
                if (slots.get(i).flexible() && lengths[i] < slots.get(i).preferred()) flexible++;
            }
        }
        for (int i = 0; i < lengths.length; i++) totals[slots.get(i).group().ordinal()] += lengths[i];
        int[] next = {0, Math.max(totals[0], Math.min((extent - totals[1]) / 2, extent - totals[2] - totals[1])), extent - totals[2]};
        for (int i = 0; i < lengths.length; i++) {
            int group = slots.get(i).group().ordinal();
            offsets[i] = next[group]; next[group] += lengths[i];
        }
        return new Layout(offsets, lengths, extent);
    }
}
