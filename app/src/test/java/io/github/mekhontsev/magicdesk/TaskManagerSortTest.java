package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public final class TaskManagerSortTest {
    private SystemMonitorRepository.ProcessEntry p(int pid, String name, float cpu, long memory) {
        return new SystemMonitorRepository.ProcessEntry(new SystemProcessSnapshot(pid,10001,1,100,0,memory,name,"S"),cpu);
    }
    @Test public void processNamesUseVisibleBasenameAndIdentityForTies() {
        var z=p(10,"/bin/z",0,1); var a=p(12,"/other/a",0,1); var a2=p(11,"A",0,1);
        assertEquals(List.of(a2,a,z),List.of(z,a,a2).stream().sorted(TaskManagerSort.NAME.processes()).toList());
    }
    @Test public void resourceSortsAreDescendingWithUnknownLast() {
        var unknown=p(10,"unknown",-1,-1);var small=p(12,"small",0,10);var large=p(11,"large",240,100);
        for(var sort:List.of(TaskManagerSort.CPU,TaskManagerSort.MEMORY))
            assertEquals(List.of(large,small,unknown),List.of(unknown,small,large).stream().sorted(sort.processes()).toList());
    }
    @Test public void applicationsShareOrderingButUseAggregatedResources() {
        var a=new TaskManagerApplications.Entry("a","Alpha","","",null,List.of(),Set.of());
        var b=new TaskManagerApplications.Entry("b","beta","","",null,List.of(),Set.of());
        var u=new TaskManagerApplications.Entry("u","Unknown","","",null,List.of(),Set.of());
        var resources=Map.of("a",new SystemMonitorRepository.Resources(1,10),
                "b",new SystemMonitorRepository.Resources(5,100),"u",SystemMonitorRepository.Resources.UNKNOWN);
        assertEquals(List.of(a,b,u),List.of(u,b,a).stream().sorted(TaskManagerSort.NAME.applications(e->resources.get(e.id()))).toList());
        for(var sort:List.of(TaskManagerSort.CPU,TaskManagerSort.MEMORY))
            assertEquals(List.of(b,a,u),List.of(u,a,b).stream().sorted(sort.applications(e->resources.get(e.id()))).toList());
    }
}
