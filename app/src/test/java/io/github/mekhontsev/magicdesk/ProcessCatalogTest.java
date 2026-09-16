package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class ProcessCatalogTest {
    @Test public void cyclicAncestryStillHasOneExpandableRoot() {
        var a=p(10,10001,11,20); var b=p(11,10001,10,20);
        var catalog=new ProcessCatalog(List.of(a,b));
        var order=Comparator.comparingInt((SystemMonitorRepository.ProcessEntry e)->e.process().pid);
        assertEquals(1,catalog.tree(p->true,Set.of(),order).size());
        assertEquals(2,catalog.tree(p->true,Set.of(a.process().identity(),b.process().identity()),order).size());
    }
    private SystemMonitorRepository.ProcessEntry p(int pid, int uid, int parent, long start) {
        return new SystemMonitorRepository.ProcessEntry(SystemMonitorRepositoryTest.process(pid, uid, parent, start, 0), 0);
    }
    @Test public void descendantsRequireOwnerAndValidParentIncarnation() {
        var a=p(10,10001,1,20); var b=p(11,10001,10,21); var c=p(12,10002,10,22); var d=p(13,10001,10,5);
        var catalog=new ProcessCatalog(List.of(a,b,c,d));
        assertEquals(Set.of(a.process().identity(),b.process().identity()),catalog.descendants(Set.of(10),10001));
    }
    @Test public void filteredParentDoesNotHideMatchingChildren() {
        var a=p(10,10001,1,20); var b=p(11,10001,10,21);
        var rows=new ProcessCatalog(List.of(a,b)).tree(p->p.pid==11,Set.of(),Comparator.comparingInt(e->e.process().pid));
        assertEquals(1,rows.size()); assertEquals(0,rows.get(0).depth()); assertEquals(11,rows.get(0).entry().process().pid);
    }
    @Test public void expandedIdentityDoesNotExpandReusedPid() {
        var a=p(10,10001,1,20); var b=p(11,10001,10,21);
        var catalog=new ProcessCatalog(List.of(a,b));
        assertEquals(2,catalog.tree(p->true,Set.of(a.process().identity()),Comparator.comparingInt(e->e.process().pid)).size());
        assertEquals(1,catalog.tree(p->true,Set.of(p(10,10001,1,19).process().identity()),Comparator.comparingInt(e->e.process().pid)).size());
    }
}
