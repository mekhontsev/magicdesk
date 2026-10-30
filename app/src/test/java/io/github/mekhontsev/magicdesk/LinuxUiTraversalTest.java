package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Execute the production traversal and waits with a deterministic guest and clock. */
public final class LinuxUiTraversalTest {
    @Test public void subscriptionSetupResumesWithinOneBudgetAndErrorsNameTheCall() throws Exception {
        RuntimeSourceFixture.verify("""
                static class JSONException extends RuntimeException {}
                static class JSONObject {
                    final Map<String,Object> values=new HashMap<>();
                    JSONObject put(String k,Object v) { values.put(k,v); return this; }
                    String getString(String k) { return (String)values.get(k); }
                }
                static class JSONArray {
                    final List<JSONObject> values=new ArrayList<>();
                    JSONArray put(JSONObject v) { values.add(v); return this; }
                    JSONObject getJSONObject(int i) { return values.get(i); }
                }
                static class SystemClock { static long now; static long uptimeMillis() { return now; } }
                static class LinuxBusWire { static JSONObject value(String type,Object value) { return new JSONObject().put("type",type).put("value",value); } }
                static class LinuxBusConnection {
                    final List<String> calls=new ArrayList<>();
                    boolean fail;
                    int cost=10;
                    void tick(String operation,int timeout) {
                        check(timeout>0 && timeout<=3000,"unbounded call");
                        calls.add(operation); SystemClock.now+=Math.min(cost,timeout);
                    }
                    JSONObject request(int op,int bus,JSONObject args,int timeout) {
                        tick(args.getString("rule"),timeout); return new JSONObject();
                    }
                    JSONArray call(int bus,String destination,String path,String iface,String member,JSONArray args,int timeout) throws IOException {
                        tick(member,timeout);
                        if(fail) throw new BusError("org.freedesktop.DBus.Error.NoReply","not responding");
                        return new JSONArray().put(new JSONObject().put("value",":1.0"));
                    }
                    static class BusError extends IOException {
                        final String name;
                        BusError(String name,String detail) { super(detail); this.name=name; }
                    }
                }
                static class Client {
                    static final String RULE="objects",OWNER_RULE="owners",WINDOW_RULE="windows",FOCUS_RULE="focus";
                    static final String REGISTRY="org.a11y.atspi.Registry",REGISTRY_PATH="/org/a11y/atspi/registry";
                    static final String ROOT="/org/a11y/atspi/accessible/root",PREFIX="org.a11y.atspi.";
                    final LinuxBusConnection bus=new LinuxBusConnection();
                    boolean observing,modernRegistry=true;
                    final Set<String> registeredEvents=new LinkedHashSet<>(),matchRules=new LinkedHashSet<>();
                    Ref root;
                    record Ref(String destination,String path) {}
                """ + RuntimeSourceFixture.nestedClass("AtSpiClient", "ObservationDeadline")
                + RuntimeSourceFixture.methods("AtSpiClient", "observe", "registry", "call", "remaining", "deadline", "args", "v", "scalar")
                        .replace("android.os.SystemClock", "SystemClock") + """
                }
                public static void verify() throws Exception {
                    Client client=new Client();
                    try { client.observe(40); throw new AssertionError("setup ignored budget"); }
                    catch(Client.ObservationDeadline expected) {}
                    check(SystemClock.now==40 && client.matchRules.size()==4,"acknowledged subscriptions were discarded");
                    client.observe(100);
                    check(client.observing && client.bus.calls.size()==8,"setup duplicated match registrations");
                    client.observe(100);
                    check(client.bus.calls.size()==8,"ready setup sent more requests");
                    client.bus.cost=900;
                    for(int i=0;i<10;i++)
                        client.call(new Client.Ref(":1.9","/test/node"),"Accessible","GetState",new JSONArray(),Long.MAX_VALUE);
                    check(SystemClock.now==9080,"responsive calls inherited a traversal cutoff");
                    client.bus.cost=10;
                    client.bus.fail=true;
                    try { client.call(new Client.Ref(":1.9","/test/node"),"Accessible","GetState",new JSONArray(),Long.MAX_VALUE); }
                    catch(LinuxBusConnection.BusError error) {
                        check(error.name.equals("org.freedesktop.DBus.Error.NoReply"),"D-Bus error identity changed");
                        check(error.getMessage().contains(":1.9 /test/node org.a11y.atspi.Accessible.GetState"),"missing timeout attribution");
                        return;
                    }
                    throw new AssertionError("remote timeout disappeared");
                }
                """);
    }

    @Test public void deadlinePresenceAndUnresponsivePeersRemainBounded() throws Exception {
        RuntimeSourceFixture.verify("""
                static class JSONException extends RuntimeException {}
                static class JSONObject {
                    static final Object NULL = new Object();
                    static final Map<String, Map<String,Object>> copies = new HashMap<>();
                    final Map<String,Object> values = new HashMap<>();
                    JSONObject() {}
                    JSONObject(String copy) { values.putAll(copies.get(copy)); }
                    public String toString() { String id=UUID.randomUUID().toString(); copies.put(id,new HashMap<>(values)); return id; }
                    JSONObject put(String k,Object v) { values.put(k,v); return this; }
                    Object opt(String k) { return values.get(k); }
                    Object get(String k) { return values.get(k); }
                    boolean has(String k) { return values.containsKey(k); }
                    boolean isNull(String k) { return !has(k) || values.get(k)==NULL; }
                    String getString(String k) { return (String)values.get(k); }
                    String optString(String k,String fallback) { return has(k) ? getString(k) : fallback; }
                    boolean getBoolean(String k) { return (boolean)values.get(k); }
                    boolean optBoolean(String k) { return values.get(k)==Boolean.TRUE; }
                    long getLong(String k) { return ((Number)values.get(k)).longValue(); }
                    int length() { return values.size(); }
                    Iterator<String> keys() { return values.keySet().iterator(); }
                    JSONObject getJSONObject(String k) { return (JSONObject)values.get(k); }
                    JSONArray getJSONArray(String k) { return (JSONArray)values.get(k); }
                }
                static class JSONArray {
                    final List<Object> values = new ArrayList<>();
                    JSONArray put(Object v) { values.add(v); return this; }
                    int length() { return values.size(); }
                }
                static class SystemClock { static long now; static long uptimeMillis() { return now; } }
                static class ShellAccess { static String usefulMessage(Throwable error) { return error.getMessage(); } }
                static class LinuxBusConnection {
                    String id="linux-fixture";
                    boolean live=true;
                    boolean alive() { return live; }
                    long generation() { return 0; }
                    CompletableFuture<Long> after(long generation) { throw new AssertionError("unexpected event wait"); }
                    static class BusError extends IOException {
                        final String name;
                        BusError(String name) { super(name); this.name=name; }
                    }
                }
                static class AtSpiClient {
                    final LinuxBusConnection bus = new LinuxBusConnection();
                    final List<Ref> roots = new ArrayList<>();
                    final Map<Ref,Node> nodes = new LinkedHashMap<>();
                    final List<Ref> reads = new ArrayList<>();
                    int cost=10, timeouts;
                    Runnable onRead=() -> {};
                    record Ref(String destination,String path) {}
                    record Node(Ref ref,JSONObject data,JSONArray children,List<String> actionNames,String identity,boolean textComplete) {}
                    static class ObservationDeadline extends IOException {}
                    static long deadline() { return SystemClock.now+3000; }
                    void observe(long deadline) throws IOException {
                        if (SystemClock.now>=deadline) throw new ObservationDeadline();
                    }
                    List<Ref> applications(long deadline) throws IOException { observe(deadline); return roots; }
                    Node read(Ref ref,long deadline) throws IOException {
                        reads.add(ref);
                        onRead.run();
                        if (ref.path.startsWith("hang")) {
                            timeouts++; SystemClock.now+=Math.min(1000,deadline-SystemClock.now);
                            throw new LinuxBusConnection.BusError("org.freedesktop.DBus.Error.NoReply");
                        }
                        if (SystemClock.now+cost>deadline) { SystemClock.now=deadline; throw new ObservationDeadline(); }
                        SystemClock.now+=cost; return nodes.get(ref);
                    }
                    static List<Ref> references(JSONArray children) {
                        return children.values.stream().map(v -> (Ref)v).toList();
                    }
                    Ref add(String owner,String path,Ref parent) {
                        Ref ref=new Ref(owner,path);
                        JSONObject data=new JSONObject().put("text",path).put("name",path).put("description","")
                            .put("password",false).put("applicationId",owner);
                        nodes.put(ref,new Node(ref,data,new JSONArray(),List.of(),path,true));
                        if (parent==null) roots.add(ref); else nodes.get(parent).children.put(ref);
                        return ref;
                    }
                }
                """ + "static " + RuntimeSourceFixture.nestedClass("UiSelector", "UiSelector")
                + RuntimeSourceFixture.nestedClass("LinuxUiAutomation", "Session")
                + RuntimeSourceFixture.nestedClass("LinuxUiAutomation", "Handle")
                + RuntimeSourceFixture.nestedClass("LinuxUiAutomation", "Snapshot")
                + RuntimeSourceFixture.nestedClass("LinuxUiAutomation", "Pending") + """
                final LinkedHashMap<String,Snapshot> snapshots=new LinkedHashMap<>();
                Snapshot snapshot(String id) { return snapshots.values().stream().filter(s -> s.handles.containsKey(id)).findFirst().orElseThrow(); }
                static void requireUnchanged(Session session,Handle handle) {}
                """ + RuntimeSourceFixture.methods("LinuxUiAutomation", "inspect", "waitFor", "waitResult", "requireActive", "expire") + """
                static JSONObject query(String name,int timeout) {
                    return new JSONObject().put("selector",new JSONObject().put("name",name)).put("timeoutMillis",timeout);
                }
                public static void verify() throws Exception {
                    Fixture f=new Fixture(); AtSpiClient client=new AtSpiClient();
                    var root=client.add(":1.1","root",null);
                    for (int i=0;i<200;i++) client.add(":1.1","row"+i,root);
                    Session session=new Session(client,new CompletableFuture<>());
                    var result=f.waitFor(session,query("missing",50));
                    check(SystemClock.now==50 && result.getBoolean("timedOut"),"wait restarted its deadline");
                    check(!result.getBoolean("complete") && result.getString("truncationReason").equals("deadline"),"deadline hid partial traversal");
                    SystemClock.now=0; client.reads.clear();
                    result=f.waitFor(session,query("row0",5000));
                    check(result.getBoolean("matched") && SystemClock.now==20 && client.reads.size()==2,"presence waited for unrelated siblings");
                    check(!result.getBoolean("complete"),"early match claimed complete tree");
                    SystemClock.now=0; client.cost=20;
                    result=f.inspect(session,new JSONObject().put("maxNodes",256));
                    check(result.getBoolean("complete") && result.getJSONArray("nodes").length()==201,"inspect stopped at first result");
                    check(result.getLong("durationMillis")==4020 && result.getString("truncationReason").isEmpty(),"responsive tree stopped after three seconds");
                    SystemClock.now=0;
                    result=f.waitFor(session,query("row199",5000));
                    check(result.getBoolean("matched") && SystemClock.now==4020,"presence traversal kept a separate three-second cutoff");
                    SystemClock.now=0;
                    result=f.waitFor(session,query("missing",5000).put("condition","absent"));
                    check(result.getBoolean("matched") && result.getBoolean("complete") && SystemClock.now==4020,"responsive full traversal did not prove absence");
                    SystemClock.now=0;
                    result=f.waitFor(session,query("row199",3500));
                    check(result.getBoolean("timedOut") && SystemClock.now==3500,"progress extended the explicit wait deadline");
                    result=f.inspect(session,new JSONObject().put("maxNodes",100));
                    check(!result.getBoolean("complete") && result.getJSONArray("nodes").length()==100
                        && result.getString("truncationReason").equals("max_nodes"),"reply progress removed volume bounds");
                    SystemClock.now=0; client.cost=400;
                    result=f.inspect(session,new JSONObject().put("maxNodes",256));
                    String id=result.getString("snapshotId");
                    check(SystemClock.now==80400 && result.getBoolean("complete"),"long responsive traversal was cut off");
                    f.expire();
                    check(f.snapshots.containsKey(id),"long traversal returned expired handles");
                    SystemClock.now+=60001; f.expire();
                    check(!f.snapshots.containsKey(id),"published snapshot never expired");
                    result=f.waitFor(session,query("root",0));
                    check(result.getBoolean("timedOut") && !result.getBoolean("complete"),"zero deadline submitted work");
                    check(result.getString("unavailableReason").equals("observation_incomplete"),"unobserved root claimed absent");

                    SystemClock.now=0; client=new AtSpiClient();
                    root=client.add(":1.1","root",null);
                    for(int i=0;i<10;i++) client.add(":1.1","hang"+i,root);
                    var good=client.add(":1.2","good",null); client.add(":1.2","target",good);
                    session=new Session(client,new CompletableFuture<>());
                    result=f.waitFor(session,query("target",3000));
                    check(result.getBoolean("matched") && client.timeouts==1,"hung owner starved responsive application");
                    check(result.getJSONArray("errors").length()==1 && !result.getBoolean("complete"),"timeout was hidden");
                    f.inspect(session,new JSONObject());
                    check(client.timeouts==2,"unresponsive owner was permanently blacklisted");
                    SystemClock.now=0;
                    result=f.waitFor(session,query("missing",100).put("condition","absent"));
                    check(result.getBoolean("timedOut") && !result.getBoolean("matched"),"timeout proved absence");

                    SystemClock.now=0; client=new AtSpiClient();
                    root=client.add(":1.1","root",null);
                    client.add(":1.1","child",root);
                    var released=new CompletableFuture<Void>();
                    session=new Session(client,released);
                    client.onRead=() -> released.complete(null);
                    int retained=f.snapshots.size();
                    try { f.inspect(session,new JSONObject()); throw new AssertionError("release did not cancel traversal"); }
                    catch(IOException expected) { check(expected.getMessage().contains("released"),"wrong cancellation cause"); }
                    check(client.reads.size()==1 && f.snapshots.size()==retained,"release continued traversal or published handles");
                    client.onRead=() -> {};
                    session=new Session(client,new CompletableFuture<>());
                    Thread.currentThread().interrupt();
                    try { f.inspect(session,new JSONObject()); throw new AssertionError("interruption did not cancel traversal"); }
                    catch(IOException expected) { check(Thread.currentThread().isInterrupted(),"interrupt flag lost"); }
                    finally { Thread.interrupted(); }
                    client.bus.live=false;
                    try { f.inspect(session,new JSONObject()); throw new AssertionError("dead session continued traversal"); }
                    catch(IOException expected) { check(expected.getMessage().contains("ended"),"wrong disconnection cause"); }
                }
                """);
    }
}
