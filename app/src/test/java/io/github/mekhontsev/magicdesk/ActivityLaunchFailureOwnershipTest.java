package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ActivityLaunchFailureOwnershipTest {
    @Test public void windowLaunchReturnsFailureToItsCallerWithoutShowingItAgain() throws Exception {
        RuntimeSourceFixture.verify("""
            interface LaunchFailureAction { void run(Exception error); }
            static class DesktopActivityLaunchResult {
                interface Completion { void onComplete(DesktopActivityLaunchResult result); }
                Throwable error;
                static DesktopActivityLaunchResult failed(Throwable error) {
                    var result = new DesktopActivityLaunchResult(); result.error = error; return result;
                }
            }
            static class Activity {
                int failures;
                void runOnUiThread(Runnable action) { action.run(); }
                boolean isActivityUnavailable() { return false; }
                void showLaunchFailure(Exception error) { failures++; }
            }
            static class Controller {
                final Activity mActivity = new Activity();
                static void runIfPresent(LaunchFailureAction action, Exception error) { if (action != null) action.run(error); }
                static void complete(DesktopActivityLaunchResult.Completion done, DesktopActivityLaunchResult result) {
                    if (done != null) done.onComplete(result);
                }
            """ + RuntimeSourceFixture.methods("AppTaskController", "failWindowLaunch") + """
            }
            public static void verify() {
                var controller = new Controller();
                var error = new IOException("task vanished");
                int[] cleanup = {0}, completions = {0};
                controller.failWindowLaunch(e -> cleanup[0]++, result -> {
                    check(result.error == error, "original error reaches owner"); completions[0]++;
                }, error);
                check(cleanup[0] == 1 && completions[0] == 1, "cleanup and result delivery are retained");
                check(controller.mActivity.failures == 0, "no unsolicited UI failure for an owned launch");
                controller.failWindowLaunch(e -> cleanup[0]++, null, error);
                check(cleanup[0] == 2 && controller.mActivity.failures == 1, "direct UI launch still reports failure");
            }
            """);
    }

    @Test public void ordinaryLaunchAlsoDelegatesFailureToItsCaller() throws Exception {
        RuntimeSourceFixture.verify("""
            static class DesktopLaunchRequest { }
            static class DesktopActivityLaunchResult {
                interface Completion { void onComplete(Throwable result); }
                static Throwable failed(Throwable error) { return error; }
            }
            static class Context {
                int failures;
                void onFailure(DesktopLaunchRequest request, Throwable error) { failures++; }
            """ + RuntimeSourceFixture.methods("StandaloneDesktopLaunchContext", "failed") + """
            }
            public static void verify() {
                var context = new Context(); var request = new DesktopLaunchRequest();
                var error = new IOException("unavailable"); int[] completions = {0};
                context.failed(request, error, result -> { check(result == error, "error delivery"); completions[0]++; });
                check(completions[0] == 1 && context.failures == 0, "caller owns error presentation");
                context.failed(request, error, null);
                check(context.failures == 1, "direct UI error remains visible");
            }
            """);
    }

    @Test public void coordinatorReportsUnownedFailuresButLeavesOwnedResultsToTheCaller() throws Exception {
        RuntimeSourceFixture.verify("""
            static class DesktopLaunchRequest { }
            static class DesktopActivityLaunchResult {
                interface Completion { void onComplete(DesktopActivityLaunchResult result); }
                String error;
                DesktopActivityLaunchResult(String error) { this.error = error; }
                boolean succeeded() { return error.isEmpty(); }
            }
            static class Context {
                int failures;
                final List<Runnable> main = new ArrayList<>();
                void onMain(Runnable action) { main.add(action); }
                void onFailure(DesktopLaunchRequest request, Throwable error) {
                    check(error.getMessage().equals("task vanished"), "error detail preserved"); failures++;
                }
                void drain() { while (!main.isEmpty()) main.remove(0).run(); }
            }
            static class Coordinator {
                final Context mContext = new Context();
            """ + RuntimeSourceFixture.methods("DesktopLaunchCoordinator", "completeActivity", "complete") + """
            }
            public static void verify() {
                var coordinator = new Coordinator(); var request = new DesktopLaunchRequest();
                var failure = new DesktopActivityLaunchResult("task vanished");
                int[] completions = {0};
                coordinator.completeActivity(request, result -> {
                    check(result == failure, "exact result reaches the caller"); completions[0]++;
                }, failure);
                coordinator.mContext.drain();
                check(completions[0] == 1 && coordinator.mContext.failures == 0, "caller alone owns failure");
                coordinator.completeActivity(request, null, failure);
                check(coordinator.mContext.failures == 0 && coordinator.mContext.main.size() == 1,
                        "direct launch reports through its UI context");
                coordinator.mContext.drain();
                check(coordinator.mContext.failures == 1, "direct UI launch reports exactly once");
                coordinator.completeActivity(request, null, new DesktopActivityLaunchResult(""));
                coordinator.mContext.drain();
                check(coordinator.mContext.failures == 1, "success has no error UI");
            }
            """);
    }
}
