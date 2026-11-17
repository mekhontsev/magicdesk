package io.github.mekhontsev.magicdesk;

import java.nio.file.Path;
import org.junit.Test;

public final class IndependentEnvironmentLifecycleTest {
    @Test public void connectionAcceptanceAndCancellationSurviveLateServiceBinding() throws Exception {
        String source=Path.of("../guest-template/src/main/java/io/github/mekhontsev/magicdesk/guestapp/EnvironmentActivity.java")
                .toAbsolutePath().normalize().toString();
        String methods=RuntimeSourceFixture.methods(source,"onActivityResult","deliverConnectionResult","onServiceConnected");
        RuntimeSourceFixture.verify("""
                interface IBinder { }
                static class ComponentName { }
                static class Intent { Object getData() { return null; } }
                static class EnvironmentService {
                    Runnable changed;
                    Intent accepted;
                    int cancelled;
                    void acceptHost(Intent reply) { accepted=reply; }
                    void disconnectHost() { cancelled++; }
                    static class Connection implements IBinder {
                        final EnvironmentService owner;
                        Connection(EnvironmentService owner) { this.owner=owner; }
                        EnvironmentService service() { return owner; }
                    }
                }
                static class Terminal { void attach(EnvironmentService service) { } }
                static class Base { protected void onActivityResult(int request,int result,Intent data) { } }
                static class Activity extends Base {
                    static final int RESULT_OK=-1;
                    EnvironmentService service;
                    Terminal terminal=new Terminal();
                    Runnable updated=() -> { };
                    Object pending;
                    boolean pendingExport,waitingForArchive,pendingConnectionResult;
                    Intent pendingConnection;
                    void openPending() { }
                """ + methods + """
                }
                public static void verify() {
                    for (boolean accept : new boolean[]{false,true}) {
                        for (boolean bound : new boolean[]{false,true}) {
                            Activity activity=new Activity();
                            EnvironmentService service=new EnvironmentService();
                            if (bound) activity.service=service;
                            Intent reply=accept ? new Intent() : null;
                            activity.onActivityResult(5,accept ? -1 : 0,reply);
                            check(bound!=activity.pendingConnectionResult,"Result custody lost before binding");
                            if (!bound) activity.onServiceConnected(new ComponentName(),new EnvironmentService.Connection(service));
                            check(service.accepted==reply && service.cancelled==(accept ? 0 : 1),"Incorrect result delivery");
                            check(!activity.pendingConnectionResult && activity.pendingConnection==null,"Result retained after delivery");
                            activity.deliverConnectionResult();
                            check(service.cancelled==(accept ? 0 : 1),"Cancellation delivered twice");
                        }
                    }
                }
                """);
    }

    @Test public void terminalCompletionPublishesOnceBeforeAdmittingAnotherOperation() throws Exception {
        String source=Path.of("../guest-template/src/main/java/io/github/mekhontsev/magicdesk/guestapp/EnvironmentService.java")
                .toAbsolutePath().normalize().toString();
        String completion=RuntimeSourceFixture.finallyBlock(source,"startTerminal");
        String state=RuntimeSourceFixture.methods(source,"active","endTerminal");
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static final class MainQueue {
                    final java.util.ArrayDeque<Runnable> callbacks=new java.util.ArrayDeque<>();
                    void post(Runnable task) { callbacks.add(task); }
                }
                static final class Service {
                    final MainQueue main=new MainQueue();
                    OperationResources operation;
                    Object terminal=new Object();
                    String status="Starting Linux";
                    int notifications;
                    void notifyChanged() { notifications++; }
                """ + state + """
                    void complete(Exception failure, OperationResources resources, OperationResources.Slot owner)
                """ + completion + """
                }
                public static void verify() {
                    for (boolean cancel : new boolean[]{false,true}) {
                        for (Exception failure : new Exception[]{null,new IOException("PTY read failed")}) {
                            Service service=new Service();
                            OperationResources lifetime=new OperationResources();
                            OperationResources operation=new OperationResources();
                            var owner=lifetime.reserve(); owner.attach(operation);
                            int[] released={0}; operation.reserve().attach(() -> released[0]++);
                            service.operation=operation;
                            if (cancel) service.endTerminal();
                            service.complete(failure,operation,owner);
                            check(service.active(),"A replacement launch was admitted before UI completion");
                            check(service.terminal!=null,"The UI lost its retained operation prematurely");
                            check(service.notifications==0,"Worker mutated the UI");
                            check(released[0]==1,"PTY ownership was not released exactly once");
                            check(service.main.callbacks.size()==1,"Missing or duplicate completion");
                            service.main.callbacks.remove().run();
                            check(!service.active() && service.terminal==null,"Completion retained the operation");
                            check(service.notifications==1,"Completion was not published");
                            check(service.status.equals(cancel || failure==null ? "Session ended" : failure.toString()),
                                    "Cancellation/EOF/error status was lost: "+service.status);
                            lifetime.close(); check(released[0]==1,"Completion released the resource twice");
                        }
                    }
                }
                """, "OperationResources");
    }
}
